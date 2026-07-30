<script setup lang="ts">
import mqtt, { type MqttClient } from 'mqtt'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { get, post } from '../services/api'
import { WhepPlayer } from '../services/whep'
import { deviceWs } from '../services/ws'
import { loadAMap, getAmapKey } from '../services/amap'
import { wgs84ToGcj02 } from '../services/geo'
import { useSessionStore } from '../stores/session'
import type { CapacityDevice, Device, DeviceTelemetry } from '../types'

interface Broker { address: string; username: string; password: string; client_id: string }
interface Acl { pub: string[]; sub: string[] }

const session = useSessionStore()
const devices     = ref<Device[]>([])
const capacity    = ref<CapacityDevice[]>([])
const dockSn      = ref('')
const selectedVideoId = ref('')
const acknowledged = ref(false)
const state        = ref<'idle' | 'connecting' | 'active'>('idle')
const error        = ref('')
const videoError   = ref('')
const videoPlaying = ref(false)
const videoElement = ref<HTMLVideoElement>()
const lens         = ref<'normal' | 'zoom' | 'ir'>('normal')
const latency      = ref(0)
const telemetry    = reactive({ altitude: 0, height: 0, speed: 0, satellites: 0, battery: 0, heading: 0, latitude: 0, longitude: 0 })
const sticks       = reactive({ leftX: 0, leftY: 0, rightX: 0, rightY: 0 })
const player = new WhepPlayer()
const pressed = new Set<string>()
let client: MqttClient | undefined
let broker: Broker | undefined
let acl: Acl | undefined
let heartbeatTimer = 0
let controlTimer   = 0
let seq = 0

// 地图
const mapContainer = ref<HTMLDivElement>()
const mapSatellite = ref(true)
const hasMapKey    = computed(() => Boolean(getAmapKey()))
let AMapRef: any, map: any, droneMarker: any, droneTrail: any
const trailPts: [number, number][] = []
let wsUnsub: (() => void) | undefined

const docks = computed(() => devices.value.filter((d) => Boolean(d.child_sn) || d.domain === 3))
const selectedDock   = computed(() => docks.value.find((d) => d.device_sn === dockSn.value))
const sources = computed(() => capacity.value.flatMap((dev) =>
  (dev.cameras_list ?? []).flatMap((cam) =>
    (cam.videos_list ?? []).map((v) => ({ ...v, label: `${dev.name} · ${cam.name}` }))
  )
))
const selectedSource = computed(() => sources.value.find((s) => s.id === selectedVideoId.value))
const active         = computed(() => state.value === 'active')

// ────────── AMap ──────────

function droneIcon(hdg: number, isCtrl: boolean) {
  const c = isCtrl ? '#3fa9ff' : '#35d6a4'
  return `<div style="transform:rotate(${hdg}deg);filter:drop-shadow(0 2px 8px rgba(0,0,0,.65));line-height:0">
    <svg viewBox="0 0 24 24" width="40" height="40">
      <path d="M12 2L22 20.5L12 16L2 20.5Z" fill="${c}" stroke="rgba(255,255,255,.8)" stroke-width="1.4"/>
      <circle cx="12" cy="12" r="2.5" fill="white" opacity=".85"/>
    </svg></div>`
}

async function initMap() {
  if (!hasMapKey.value || !mapContainer.value) return
  try {
    AMapRef = await loadAMap()
    map = new AMapRef.Map(mapContainer.value, {
      zoom: 16, center: [116.397, 39.909],
      layers: [new AMapRef.TileLayer.Satellite(), new AMapRef.TileLayer.RoadNet()],
      viewMode: '2D'
    })
    droneMarker = new AMapRef.Marker({ anchor: 'center', content: droneIcon(0, false), zIndex: 200 })
    droneTrail  = new AMapRef.Polyline({ path: [], strokeColor: '#3fa9ff', strokeWeight: 3, strokeOpacity: .85, zIndex: 100 })
    map.add([droneMarker, droneTrail])
  } catch { /* 地图不可用 */ }
}

function updateMap(lng: number, lat: number, hdg: number) {
  if (!map || !AMapRef || !Number.isFinite(lng) || !Number.isFinite(lat) || (lng === 0 && lat === 0)) return
  const [gLng, gLat] = wgs84ToGcj02(lng, lat)
  droneMarker?.setPosition([gLng, gLat])
  droneMarker?.setContent(droneIcon(hdg, active.value))
  const last = trailPts[trailPts.length - 1]
  if (!last || Math.abs(last[0] - gLng) > 1e-5 || Math.abs(last[1] - gLat) > 1e-5) {
    trailPts.push([gLng, gLat])
    if (trailPts.length > 500) trailPts.shift()
    droneTrail?.setPath(trailPts)
  }
}

function centerOnDrone() {
  if (map && (telemetry.longitude !== 0 || telemetry.latitude !== 0)) {
    const [gLng, gLat] = wgs84ToGcj02(telemetry.longitude, telemetry.latitude)
    map.setCenter([gLng, gLat])
  }
}

function toggleMapSat() {
  mapSatellite.value = !mapSatellite.value
  if (!map || !AMapRef) return
  if (mapSatellite.value) {
    map.setLayers([new AMapRef.TileLayer.Satellite(), new AMapRef.TileLayer.RoadNet()])
  } else {
    map.setLayers([new AMapRef.TileLayer()])
    map.setMapStyle('amap://styles/darkblue')
  }
}

// ────────── 生命周期 ──────────

onMounted(async () => {
  try {
    const [deviceData, liveData] = await Promise.all([
      get<Device[]>(`/manage/api/v1/devices/${session.workspaceId}/devices`),
      get<CapacityDevice[]>('/manage/api/v1/live/capacity')
    ])
    devices.value  = deviceData
    capacity.value = liveData
    dockSn.value   = docks.value[0]?.device_sn ?? ''
    selectedVideoId.value = sources.value[0]?.id ?? ''
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '座舱数据加载失败'
  }
  // 订阅 WebSocket OSD（未进 DRC 时也能更新地图位置）
  wsUnsub = deviceWs.subscribe((msg) => {
    if (msg.biz_code === 'gateway_osd' || msg.biz_code === 'device_osd') {
      const d = (msg.data as DeviceTelemetry)?.host
      if (d?.longitude && d?.latitude) {
        updateMap(Number(d.longitude), Number(d.latitude), Number(d.attitude_head ?? 0))
      }
    }
  })
  await initMap()
})

onBeforeUnmount(async () => {
  wsUnsub?.()
  await exit()
  if (map) { map.destroy?.(); map = undefined }
})

// ────────── DRC 控制 ──────────

async function enter() {
  if (!dockSn.value || !acknowledged.value) return
  state.value = 'connecting'
  error.value = ''
  try {
    broker = await post<Broker>(`/control/api/v1/workspaces/${session.workspaceId}/drc/connect`, { client_id: '', expire_sec: 3600 })
    acl    = await post<Acl>(`/control/api/v1/workspaces/${session.workspaceId}/drc/enter`, {
      client_id: broker.client_id, dock_sn: dockSn.value, expire_sec: 3600,
      device_info: { osd_frequency: 10, hsi_frequency: 1 }
    })
    await connectMqtt(broker, acl)
    state.value = 'active'
    heartbeatTimer = window.setInterval(heartbeat, 1000)
    controlTimer   = window.setInterval(publishControl, 100)
    window.addEventListener('keydown', handleKey)
    window.addEventListener('keyup', handleKey)
    window.addEventListener('blur', releaseKeys)
    if (selectedVideoId.value) void startVideo()
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '进入虚拟座舱失败'
    state.value = 'idle'
    client?.end(true)
  }
}

function connectMqtt(config: Broker, permissions: Acl) {
  return new Promise<void>((resolve, reject) => {
    client = mqtt.connect(config.address, {
      clientId: config.client_id, username: config.username, password: config.password,
      reconnectPeriod: 1500, connectTimeout: 8000, clean: true
    })
    const timeout = window.setTimeout(() => reject(new Error('MQTT DRC 连接超时')), 9000)
    client.once('connect', () => {
      window.clearTimeout(timeout)
      permissions.sub.forEach((t) => client?.subscribe(t, { qos: 0 }))
      client?.on('message', handleMessage)
      resolve()
    })
    client.once('error', (reason) => { window.clearTimeout(timeout); reject(reason) })
  })
}

function envelope(method: string, data: unknown) {
  const id = crypto.randomUUID()
  return JSON.stringify({ tid: id, bid: id, timestamp: Date.now(), method, data })
}

function publish(method: string, data: unknown = '') {
  const topic = acl?.pub[0]
  if (client?.connected && topic) client.publish(topic, envelope(method, data), { qos: 0 })
}

function heartbeat() { publish('heart_beat', { seq: ++seq, timestamp: Date.now() }) }

function publishControl() {
  if (!active.value) return
  publish('drone_control', {
    seq: ++seq,
    x: +(sticks.rightY * -17).toFixed(2),
    y: +(sticks.rightX *  17).toFixed(2),
    h: +(sticks.leftY  *  -5).toFixed(2),
    w: +(sticks.leftX  *  90).toFixed(2),
    freq: 10, delay_time: 300
  })
}

function handleMessage(_topic: string, payload: Uint8Array) {
  try {
    const message = JSON.parse(new TextDecoder().decode(payload))
    const data = message.data?.data ?? message.data ?? {}
    if (message.method === 'heart_beat') latency.value = Math.max(0, Date.now() - Number(data.timestamp ?? Date.now()))
    if (message.method === 'osd_info_push') {
      telemetry.altitude  = Number(data.elevation ?? data.altitude      ?? telemetry.altitude)
      telemetry.height    = Number(data.height                          ?? telemetry.height)
      telemetry.speed     = Number(data.horizontal_speed ?? data.speed  ?? telemetry.speed)
      telemetry.satellites= Number(data.gps_number ?? data.satellites   ?? telemetry.satellites)
      telemetry.battery   = Number(data.capacity_percent ?? data.battery?? telemetry.battery)
      telemetry.heading   = Number(data.attitude_head ?? data.heading   ?? telemetry.heading)
      telemetry.latitude  = Number(data.latitude  ?? telemetry.latitude)
      telemetry.longitude = Number(data.longitude ?? telemetry.longitude)
      // 同步地图位置
      if (data.latitude && data.longitude) {
        updateMap(Number(data.longitude), Number(data.latitude), Number(data.attitude_head ?? telemetry.heading))
      }
    }
  } catch { /* 未知负载不影响控制链路 */ }
}

function moveStick(side: 'left' | 'right', event: PointerEvent) {
  if (!active.value) return
  const target = event.currentTarget as HTMLElement
  if (event.type === 'pointerdown') target.setPointerCapture(event.pointerId)
  if (!target.hasPointerCapture(event.pointerId)) return
  const rect = target.getBoundingClientRect()
  const x = Math.max(-1, Math.min(1, (event.clientX - rect.left  - rect.width  / 2) / (rect.width  * .34)))
  const y = Math.max(-1, Math.min(1, (event.clientY - rect.top   - rect.height / 2) / (rect.height * .34)))
  if (side === 'left') { sticks.leftX  = x; sticks.leftY  = y }
  else                 { sticks.rightX = x; sticks.rightY = y }
}
function releaseStick(side: 'left' | 'right') {
  if (side === 'left') { sticks.leftX  = 0; sticks.leftY  = 0 }
  else                 { sticks.rightX = 0; sticks.rightY = 0 }
  publishControl()
}
function handleKey(event: KeyboardEvent) {
  if (!active.value || ['INPUT', 'SELECT', 'TEXTAREA'].includes((event.target as HTMLElement)?.tagName)) return
  const watched = ['KeyW', 'KeyA', 'KeyS', 'KeyD', 'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight']
  if (!watched.includes(event.code)) return
  event.preventDefault()
  if (event.type === 'keydown') pressed.add(event.code); else pressed.delete(event.code)
  sticks.leftY  = Number(pressed.has('KeyS'))     - Number(pressed.has('KeyW'))
  sticks.leftX  = Number(pressed.has('KeyD'))     - Number(pressed.has('KeyA'))
  sticks.rightY = Number(pressed.has('ArrowDown'))- Number(pressed.has('ArrowUp'))
  sticks.rightX = Number(pressed.has('ArrowRight'))- Number(pressed.has('ArrowLeft'))
}
function releaseKeys() {
  pressed.clear()
  sticks.leftX = sticks.leftY = sticks.rightX = sticks.rightY = 0
  publishControl()
}

async function startVideo() {
  if (!selectedSource.value || videoPlaying.value) return
  videoError.value = ''
  try {
    const response = await post<{ url: string }>('/manage/api/v1/live/streams/start', {
      video_id: selectedSource.value.id, url_type: 2, video_quality: 0
    })
    if (!response.url || response.url.toLowerCase().startsWith('rtsp://')) throw new Error('WHEP 播放地址未配置')
    await nextTick()
    if (!videoElement.value) throw new Error('视频容器不可用')
    await player.play(videoElement.value, response.url)
    videoPlaying.value = true
  } catch (reason) { videoError.value = reason instanceof Error ? reason.message : '主画面启动失败' }
}
async function stopVideo() {
  await player.stop()
  if (selectedSource.value) await post('/manage/api/v1/live/streams/stop', { video_id: selectedSource.value.id }).catch(() => undefined)
  videoPlaying.value = false
}
async function switchLens(value: 'normal' | 'zoom' | 'ir') {
  lens.value = value
  if (!selectedSource.value || !videoPlaying.value) return
  await post('/manage/api/v1/live/streams/switch', { video_id: selectedSource.value.id, url_type: 2, video_quality: 0, video_type: value })
    .catch((reason) => { videoError.value = reason instanceof Error ? reason.message : '镜头切换失败' })
}

async function returnHome() {
  if (!active.value || !window.confirm('确认向当前设备下发返航指令？')) return
  await post(`/control/api/v1/devices/${dockSn.value}/jobs/return_home`)
    .catch((reason) => { error.value = reason instanceof Error ? reason.message : '返航指令失败' })
}
function emergencyStop() {
  if (!active.value || !window.confirm('确认触发紧急制动？此操作可能影响飞行安全。')) return
  publish('drone_emergency_stop')
}

async function exit() {
  window.clearInterval(heartbeatTimer)
  window.clearInterval(controlTimer)
  window.removeEventListener('keydown', handleKey)
  window.removeEventListener('keyup', handleKey)
  window.removeEventListener('blur', releaseKeys)
  releaseKeys()
  await stopVideo()
  if (broker && dockSn.value) {
    await post(`/control/api/v1/workspaces/${session.workspaceId}/drc/exit`, {
      client_id: broker.client_id, dock_sn: dockSn.value, expire_sec: 3600,
      device_info: { osd_frequency: 10, hsi_frequency: 1 }
    }).catch(() => undefined)
  }
  client?.end(true)
  client = undefined; broker = undefined; acl = undefined
  state.value = 'idle'
}

function toggleFullscreen() {
  if (document.fullscreenElement) void document.exitFullscreen()
  else void document.querySelector('.cockpit-pro')?.requestFullscreen()
}
</script>

<template>
  <div class="cockpit-pro">
    <!-- ── 顶栏 ──────────────────────────────────────────── -->
    <header class="cockpit-bar">
      <div class="bar-left">
        <span :class="['mode-badge', { active }]">
          <i></i>{{ active ? '手动飞行 · DRC' : '虚拟座舱 · 待命' }}
        </span>
        <span v-if="active" class="latency-badge" :class="{ warn: latency > 300 }">{{ latency }} ms</span>
      </div>
      <strong class="bar-title">{{ selectedDock?.nickname || selectedDock?.device_name || 'YOOX Cloud GCS' }}</strong>
      <div class="bar-right">
        <button class="bar-btn" @click="toggleMapSat">{{ mapSatellite ? '🛰 卫星' : '🗺 地图' }}</button>
        <button class="bar-btn" @click="centerOnDrone" title="定位飞行器">⊕ 定位</button>
        <button class="bar-btn" @click="toggleFullscreen">⛶ 全屏</button>
        <RouterLink to="/" class="bar-btn">← 退出</RouterLink>
      </div>
    </header>

    <!-- ── 左侧边栏 ──────────────────────────────────────── -->
    <aside class="session-rail">
      <div class="rail-head"><strong>在线设备</strong><span class="rail-cnt">{{ docks.length }}</span></div>
      <div class="rail-devices">
        <button v-for="dock in docks" :key="dock.device_sn"
          :class="['dev-card', { selected: dock.device_sn === dockSn }]"
          :disabled="state !== 'idle'"
          @click="dockSn = dock.device_sn">
          <div class="dev-card-row">
            <span :class="['dev-status', { active: dock.device_sn === dockSn && active }]"><i></i></span>
            <strong>{{ dock.nickname || dock.device_name || 'YOOX 设备' }}</strong>
          </div>
          <div class="dev-card-sn">{{ dock.device_sn }}</div>
          <div v-if="dock.device_sn === dockSn && active" class="dev-card-stats">
            <span>▣ {{ telemetry.battery.toFixed(0) }}%</span>
            <span>⌁ {{ latency }} ms</span>
            <span>↑ {{ telemetry.altitude.toFixed(0) }} m</span>
          </div>
        </button>
        <div v-if="!docks.length" class="rail-empty">暂无在线设备<br><small>请检查设备连接</small></div>
      </div>
      <div class="rail-control">
        <label class="safety-check">
          <input v-model="acknowledged" type="checkbox" :disabled="state !== 'idle'" />
          <span>已确认空域、设备与现场安全</span>
        </label>
        <p v-if="error" class="form-error">{{ error }}</p>
        <button v-if="!active"
          class="primary full"
          :disabled="!dockSn || !acknowledged || state === 'connecting'"
          @click="enter">
          {{ state === 'connecting' ? '获取控制权…' : '进入手动飞行' }}
        </button>
        <button v-else class="ghost full" @click="exit">释放控制权</button>
      </div>
    </aside>

    <!-- ── 中心：高德卫星地图 ─────────────────────────────── -->
    <section class="cockpit-map-section">
      <!-- AMap 渲染容器 -->
      <div ref="mapContainer" class="cx-canvas"></div>

      <!-- 未配置 Key 时的备用指南针视图 -->
      <div v-if="!hasMapKey" class="cx-fallback">
        <div class="fb-compass" :style="{ transform: `rotate(${-telemetry.heading}deg)` }">
          <span class="fb-n">N</span>
          <span class="fb-s">S</span>
          <span class="fb-w">W</span>
          <span class="fb-e">E</span>
        </div>
        <div class="fb-arrow" :style="{ transform: `rotate(${telemetry.heading}deg)` }">▲</div>
        <p class="fb-tip">未配置高德地图 Key（runtime-config.js）</p>
      </div>

      <!-- 飞行数据叠加层（激活时显示） -->
      <div v-if="active" class="cx-hud">
        <div class="hud-chip"><span>ALT</span><strong>{{ telemetry.altitude.toFixed(1) }} m</strong></div>
        <div class="hud-chip"><span>AGL</span><strong>{{ telemetry.height.toFixed(1) }} m</strong></div>
        <div class="hud-chip"><span>SPD</span><strong>{{ telemetry.speed.toFixed(1) }} m/s</strong></div>
        <div class="hud-chip"><span>HDG</span><strong>{{ telemetry.heading.toFixed(0) }}°</strong></div>
        <div class="hud-chip" :class="{ 'bat-low': telemetry.battery < 25 }"><span>BAT</span><strong>{{ telemetry.battery.toFixed(0) }}%</strong></div>
        <div class="hud-chip"><span>GPS</span><strong>{{ telemetry.satellites }}</strong></div>
      </div>

      <!-- GPS 坐标 -->
      <div v-if="telemetry.latitude !== 0 || telemetry.longitude !== 0" class="cx-coords">
        {{ telemetry.latitude.toFixed(5) }},&thinsp;{{ telemetry.longitude.toFixed(5) }}
      </div>
    </section>

    <!-- ── 右侧面板：视频 + 遥测 + 操控 ─────────────────── -->
    <section class="flight-view">
      <!-- 镜头选择 + 视频源 -->
      <div class="lens-bar">
        <button :class="{ active: lens === 'normal' }" @click="switchLens('normal')">广角</button>
        <button :class="{ active: lens === 'zoom'   }" @click="switchLens('zoom')">变焦</button>
        <button :class="{ active: lens === 'ir'     }" @click="switchLens('ir')">红外</button>
        <select v-model="selectedVideoId" :disabled="videoPlaying" class="src-select">
          <option value="" disabled>选择视频源</option>
          <option v-for="s in sources" :key="s.id" :value="s.id">{{ s.label }}</option>
        </select>
      </div>

      <!-- 视频画面 -->
      <div class="video-box">
        <video ref="videoElement" autoplay muted playsinline></video>
        <div v-if="!videoPlaying" class="video-ph">
          <span>Y</span>
          <p>{{ videoError || '等待主视频画面' }}</p>
          <button class="ghost small" :disabled="!selectedVideoId || !active" @click="startVideo">启动直播</button>
        </div>
        <div v-if="videoPlaying" class="video-badge">LIVE</div>
      </div>

      <!-- 遥测仪表 -->
      <div class="tele-panel">
        <!-- 罗盘 -->
        <div class="compass-wrap">
          <div class="compass-ring" :style="{ transform: `rotate(${-telemetry.heading}deg)` }">
            <span class="cn">N</span><span class="cs">S</span>
            <span class="cw">W</span><span class="ce">E</span>
          </div>
          <div class="compass-pointer">▲</div>
          <span class="compass-val">{{ telemetry.heading.toFixed(0) }}°</span>
        </div>
        <!-- 数值仪表 -->
        <div class="gauge-grid">
          <div class="gauge"><small>ALT</small><strong>{{ telemetry.altitude.toFixed(1) }}</strong><em>m</em></div>
          <div class="gauge"><small>AGL</small><strong>{{ telemetry.height.toFixed(1) }}</strong><em>m</em></div>
          <div class="gauge"><small>SPD</small><strong>{{ telemetry.speed.toFixed(1) }}</strong><em>m/s</em></div>
          <div class="gauge" :class="{ 'bat-low': telemetry.battery < 25 }"><small>BAT</small><strong>{{ telemetry.battery.toFixed(0) }}</strong><em>%</em></div>
          <div class="gauge"><small>GPS</small><strong>{{ telemetry.satellites }}</strong><em>颗</em></div>
          <div class="gauge"><small>LAG</small><strong>{{ latency }}</strong><em>ms</em></div>
        </div>
      </div>

      <!-- 双摇杆 + 操作按钮 -->
      <div class="stick-section">
        <div class="stick-area">
          <!-- 左杆：油门(↑↓) + 偏航(←→) -->
          <div class="stick-label">油门 / 偏航<br><small>W·S·A·D</small></div>
          <div class="stick-pad"
            @pointerdown="moveStick('left', $event)" @pointermove="moveStick('left', $event)"
            @pointerup="releaseStick('left')" @pointercancel="releaseStick('left')">
            <span class="stick-knob" :style="{ transform: `translate(${sticks.leftX * 30}px,${sticks.leftY * 30}px)` }"></span>
          </div>
        </div>
        <div class="stick-center">
          <button class="rth-btn"  :disabled="!active" @click="returnHome">⌂<br><small>返航</small></button>
          <button class="stop-btn" :disabled="!active" @click="emergencyStop">⏸<br><small>制动</small></button>
        </div>
        <div class="stick-area">
          <!-- 右杆：俯仰(↑↓) + 横滚(←→) -->
          <div class="stick-pad"
            @pointerdown="moveStick('right', $event)" @pointermove="moveStick('right', $event)"
            @pointerup="releaseStick('right')" @pointercancel="releaseStick('right')">
            <span class="stick-knob" :style="{ transform: `translate(${sticks.rightX * 30}px,${sticks.rightY * 30}px)` }"></span>
          </div>
          <div class="stick-label">俯仰 / 横滚<br><small>↑·↓·←·→</small></div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
/* ─── 全局布局 ────────────────────────────────────── */
.cockpit-pro {
  display: grid;
  grid-template:
    "bar bar bar" 48px
    "rail map flight" 1fr
    / 220px 1fr 320px;
  height: calc(100vh - 60px);
  min-height: 0;
  background: #090e16;
  overflow: hidden;
}

/* ─── 顶栏 ────────────────────────────────────────── */
.cockpit-bar {
  grid-area: bar;
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 16px;
  background: rgba(5,9,16,.96);
  border-bottom: 1px solid rgba(255,255,255,.07);
  z-index: 20;
}
.bar-left, .bar-right { display: flex; align-items: center; gap: 8px; }
.bar-title { font-size: 14px; font-weight: 600; }
.mode-badge { display: flex; align-items: center; gap: 6px; font-size: 13px; color: var(--muted, #6b7789); padding: 4px 10px; border-radius: 20px; border: 1px solid rgba(255,255,255,.08); }
.mode-badge i { width: 7px; height: 7px; border-radius: 50%; background: var(--muted, #6b7789); }
.mode-badge.active { color: #3fa9ff; border-color: rgba(63,169,255,.3); }
.mode-badge.active i { background: #3fa9ff; box-shadow: 0 0 6px #3fa9ff; animation: blink 1.5s ease infinite; }
.latency-badge { font-size: 12px; color: #35d6a4; padding: 3px 8px; border-radius: 20px; border: 1px solid rgba(53,214,164,.3); font-family: monospace; }
.latency-badge.warn { color: #f5a623; border-color: rgba(245,166,35,.3); }
.bar-btn { background: rgba(255,255,255,.06); border: 1px solid rgba(255,255,255,.1); color: var(--text, #dfe6f1); border-radius: 8px; padding: 5px 12px; font-size: 12px; cursor: pointer; text-decoration: none; }
.bar-btn:hover { border-color: #3fa9ff; }

/* ─── 左侧边栏 ────────────────────────────────────── */
.session-rail {
  grid-area: rail;
  display: flex; flex-direction: column; gap: 10px;
  padding: 14px 12px;
  border-right: 1px solid rgba(255,255,255,.06);
  background: rgba(6,10,18,.9);
  overflow-y: auto;
}
.rail-head { display: flex; align-items: center; justify-content: space-between; }
.rail-head strong { font-size: 13px; }
.rail-cnt { font-size: 12px; background: rgba(255,255,255,.08); border-radius: 10px; padding: 1px 8px; }
.rail-devices { display: flex; flex-direction: column; gap: 8px; flex: 1; }
.dev-card { text-align: left; padding: 10px; border-radius: 10px; border: 1px solid rgba(255,255,255,.07); background: rgba(255,255,255,.03); cursor: pointer; color: var(--text, #dfe6f1); transition: border-color .15s; display: flex; flex-direction: column; gap: 4px; }
.dev-card:hover { border-color: rgba(63,169,255,.4); }
.dev-card.selected { border-color: #3fa9ff; background: rgba(63,169,255,.07); }
.dev-card:disabled { opacity: .5; cursor: not-allowed; }
.dev-card-row { display: flex; align-items: center; gap: 6px; }
.dev-status i { display: block; width: 8px; height: 8px; border-radius: 50%; background: var(--muted, #6b7789); }
.dev-status.active i { background: #3fa9ff; box-shadow: 0 0 5px #3fa9ff; }
.dev-card-sn { font-size: 10px; font-family: monospace; color: var(--muted, #6b7789); }
.dev-card-stats { display: flex; gap: 8px; font-size: 11px; color: #35d6a4; }
.rail-empty { text-align: center; padding: 20px 0; color: var(--muted, #6b7789); font-size: 13px; }
.rail-empty small { display: block; margin-top: 4px; font-size: 11px; }
.rail-control { display: flex; flex-direction: column; gap: 10px; padding-top: 8px; border-top: 1px solid rgba(255,255,255,.06); }
.safety-check { display: flex; align-items: flex-start; gap: 8px; font-size: 12px; color: var(--muted, #6b7789); cursor: pointer; }
.safety-check input { margin-top: 2px; }

/* ─── 地图区 ──────────────────────────────────────── */
.cockpit-map-section {
  grid-area: map;
  position: relative;
  overflow: hidden;
  background: #060b12;
}
.cx-canvas { position: absolute; inset: 0; }

/* 无 Key 时的备用指南针 */
.cx-fallback { position: absolute; inset: 0; display: grid; place-items: center; }
.fb-compass { position: relative; width: 110px; height: 110px; border: 1px solid rgba(255,255,255,.12); border-radius: 50%; }
.fb-n,.fb-s,.fb-w,.fb-e { position: absolute; font-size: 11px; font-weight: 700; }
.fb-n { top:6px;left:50%;transform:translateX(-50%);color:#ff5d6c; }
.fb-s { bottom:6px;left:50%;transform:translateX(-50%);color:var(--muted,#6b7789); }
.fb-w { left:6px;top:50%;transform:translateY(-50%);color:var(--muted,#6b7789); }
.fb-e { right:6px;top:50%;transform:translateY(-50%);color:var(--muted,#6b7789); }
.fb-arrow { position: absolute; font-size: 28px; color: #3fa9ff; pointer-events: none; }
.fb-tip { position: absolute; bottom: 20px; left: 50%; transform: translateX(-50%); font-size: 11px; color: var(--muted,#6b7789); white-space: nowrap; }

/* 飞行数据 HUD */
.cx-hud { position: absolute; bottom: 12px; left: 12px; display: flex; gap: 8px; flex-wrap: wrap; z-index: 5; }
.hud-chip { background: rgba(5,9,16,.82); border: 1px solid rgba(255,255,255,.1); border-radius: 8px; padding: 6px 10px; display: flex; flex-direction: column; align-items: center; min-width: 52px; }
.hud-chip span { font-size: 9px; color: var(--muted,#6b7789); letter-spacing: .1em; }
.hud-chip strong { font-size: 15px; color: var(--text,#dfe6f1); }
.hud-chip.bat-low strong { color: #ff5d6c; }

/* GPS 坐标 */
.cx-coords { position: absolute; top: 10px; left: 12px; font-size: 11px; font-family: monospace; color: rgba(255,255,255,.55); background: rgba(5,9,16,.7); padding: 4px 10px; border-radius: 6px; z-index: 5; }

/* ─── 右侧面板 ────────────────────────────────────── */
.flight-view {
  grid-area: flight;
  display: flex; flex-direction: column; gap: 0;
  border-left: 1px solid rgba(255,255,255,.06);
  background: rgba(6,10,18,.95);
  overflow: hidden;
}
.lens-bar { display: flex; gap: 4px; padding: 8px 10px; border-bottom: 1px solid rgba(255,255,255,.06); flex-shrink: 0; }
.lens-bar button { font-size: 12px; padding: 4px 10px; border-radius: 7px; border: 1px solid rgba(255,255,255,.08); background: transparent; color: var(--muted,#6b7789); cursor: pointer; }
.lens-bar button.active { border-color: #3fa9ff; color: #3fa9ff; }
.src-select { margin-left: auto; font-size: 12px; background: rgba(255,255,255,.05); border: 1px solid rgba(255,255,255,.1); color: var(--text,#dfe6f1); border-radius: 7px; padding: 4px 8px; }

/* 视频区 */
.video-box { position: relative; flex-shrink: 0; height: 180px; background: #040810; }
.video-box video { width: 100%; height: 100%; object-fit: cover; }
.video-ph { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px; }
.video-ph span { font-size: 28px; font-weight: 900; color: rgba(63,169,255,.3); }
.video-ph p { font-size: 12px; color: var(--muted,#6b7789); }
.video-badge { position: absolute; top: 8px; left: 10px; background: #ff5d6c; color: white; font-size: 10px; font-weight: 700; padding: 2px 8px; border-radius: 4px; letter-spacing: .1em; }

/* 遥测仪表 */
.tele-panel { display: flex; gap: 10px; padding: 10px; flex-shrink: 0; border-top: 1px solid rgba(255,255,255,.06); }
.compass-wrap { position: relative; width: 80px; height: 80px; flex-shrink: 0; display: flex; align-items: center; justify-content: center; }
.compass-ring { position: absolute; inset: 0; border: 1px solid rgba(255,255,255,.14); border-radius: 50%; }
.cn,.cs,.cw,.ce { position: absolute; font-size: 10px; font-weight: 700; }
.cn { top:3px; left:50%; transform:translateX(-50%); color:#ff5d6c; }
.cs { bottom:3px; left:50%; transform:translateX(-50%); color:var(--muted,#6b7789); }
.cw { left:3px; top:50%; transform:translateY(-50%); color:var(--muted,#6b7789); }
.ce { right:3px; top:50%; transform:translateY(-50%); color:var(--muted,#6b7789); }
.compass-pointer { font-size: 22px; color: #3fa9ff; z-index: 1; }
.compass-val { position: absolute; bottom: -16px; left: 50%; transform: translateX(-50%); font-size: 11px; color: var(--text,#dfe6f1); white-space: nowrap; }
.gauge-grid { flex: 1; display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px; }
.gauge { background: rgba(255,255,255,.04); border: 1px solid rgba(255,255,255,.07); border-radius: 8px; padding: 6px 8px; display: flex; flex-direction: column; align-items: center; }
.gauge small { font-size: 9px; color: var(--muted,#6b7789); letter-spacing: .1em; }
.gauge strong { font-size: 16px; }
.gauge em { font-size: 9px; color: var(--muted,#6b7789); font-style: normal; }
.gauge.bat-low strong { color: #ff5d6c; }

/* 摇杆区 */
.stick-section { display: flex; align-items: center; justify-content: space-between; padding: 12px 10px; gap: 8px; flex: 1; border-top: 1px solid rgba(255,255,255,.06); }
.stick-area { display: flex; flex-direction: column; align-items: center; gap: 6px; }
.stick-label { font-size: 10px; color: var(--muted,#6b7789); text-align: center; line-height: 1.4; }
.stick-pad {
  width: 90px; height: 90px; border-radius: 50%;
  border: 2px solid rgba(255,255,255,.12);
  background: radial-gradient(circle at 50% 50%, rgba(63,169,255,.06), transparent 70%);
  position: relative; touch-action: none; cursor: crosshair;
  display: grid; place-items: center;
}
.stick-knob {
  position: absolute; width: 28px; height: 28px; border-radius: 50%;
  background: radial-gradient(circle at 35% 35%, #3fa9ff, #1a6fbe);
  box-shadow: 0 2px 10px rgba(63,169,255,.5);
  transition: box-shadow .1s;
}
.stick-center { display: flex; flex-direction: column; gap: 8px; align-items: center; }
.rth-btn, .stop-btn { width: 52px; height: 52px; border-radius: 50%; border: none; font-size: 18px; cursor: pointer; font-weight: 700; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 1px; padding: 0; }
.rth-btn small, .stop-btn small { font-size: 8px; font-weight: 400; }
.rth-btn { background: rgba(63,169,255,.15); border: 1px solid rgba(63,169,255,.4); color: #3fa9ff; }
.rth-btn:hover:not(:disabled) { background: rgba(63,169,255,.25); }
.stop-btn { background: rgba(255,93,108,.12); border: 1px solid rgba(255,93,108,.4); color: #ff5d6c; }
.stop-btn:hover:not(:disabled) { background: rgba(255,93,108,.25); }
.rth-btn:disabled, .stop-btn:disabled { opacity: .4; cursor: not-allowed; }

@keyframes blink { 0%,100% { opacity: 1; } 50% { opacity: .3; } }
</style>

