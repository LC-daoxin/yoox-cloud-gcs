<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { get, listFrom } from '../services/api'
import { deviceWs, type WsMessage } from '../services/ws'
import { deviceCounts } from '../services/device-status'
import { getMapProvider, loadMapFactory, rememberMapProvider, type GcsMap, type MapMarker, type MapProvider } from '../services/map'
import { useSessionStore } from '../stores/session'
import type { Device, DeviceTelemetry, OsdHost } from '../types'

const session = useSessionStore()
const devices = ref<Device[]>([])
const jobs = ref<Record<string, unknown>[]>([])
const serviceHealthy = ref(false)
const loading = ref(true)
const devicesReady = ref(false)
const notice = ref('')
const mapEl = ref<HTMLDivElement>()
const mapProvider = ref<MapProvider>(getMapProvider())
const mapLoading = ref(false)
const mapError = ref('')
const mapVersion = ref('')
let mapGeneration = 0
const counts = computed(() => deviceCounts(devices.value))
let deviceRefreshTimer = 0
let devicePollTimer = 0
let deviceRequest = 0
let disposed = false

let map: GcsMap | undefined
let pendingMap: GcsMap | undefined
const markers = new Map<string, MapMarker>()
const live = reactive<Record<string, [number, number]>>({})
let unsub: (() => void) | undefined

function onMsg(msg: WsMessage) {
  if (['device_online', 'device_offline', 'device_update_topo'].includes(msg.biz_code)) {
    window.clearTimeout(deviceRefreshTimer)
    deviceRefreshTimer = window.setTimeout(() => { void refreshDevices() }, 200)
  }
  if (msg.biz_code === 'gateway_osd' || msg.biz_code === 'device_osd') {
    const payload = msg.data as DeviceTelemetry
    const host = payload?.host as OsdHost
    if (!payload?.sn || !host) return
    const lng = Number(host.longitude)
    const lat = Number(host.latitude)
    if (!Number.isFinite(lng) || !Number.isFinite(lat) || (lng === 0 && lat === 0)) return
    live[payload.sn] = [lng, lat]
    drawMarker(payload.sn)
  }
}

async function refreshDevices() {
  const request = ++deviceRequest
  try {
    const data = await get<Device[]>(`/manage/api/v1/devices/${session.workspaceId}/devices`, { timeoutMs: 10_000 })
    if (disposed || request !== deviceRequest) return
    devices.value = data
    devicesReady.value = true
    notice.value = ''
  } catch (reason) {
    if (!disposed && request === deviceRequest) notice.value = reason instanceof Error ? reason.message : '设备数据暂不可用'
  }
}

function drawMarker(sn: string) {
  if (!map) return
  const pos = live[sn]
  let marker = markers.get(sn)
  if (!marker) {
    marker = map.createMarker({ position: pos, anchor: 'center',
      content: '<div class="dash-dot"></div>' })
    markers.set(sn, marker)
  } else {
    marker.setPosition(pos)
  }
  map.setCenter(pos)
}

async function initMap() {
  if (!mapEl.value) return
  const generation = ++mapGeneration
  const provider = mapProvider.value
  const center = map?.getCenter() ?? [116.397, 39.909] as [number, number]
  const zoom = map?.getZoom() ?? 11
  map?.destroy()
  map = undefined
  markers.clear()
  mapLoading.value = true
  mapError.value = ''
  mapVersion.value = ''
  let candidate: GcsMap | undefined
  try {
    const factory = await loadMapFactory(provider)
    if (generation !== mapGeneration || !mapEl.value) return
    candidate = await factory(mapEl.value, { center, zoom, satellite: false })
    if (generation !== mapGeneration) {
      void candidate.ready.catch(() => undefined)
      candidate.destroy()
      return
    }
    pendingMap = candidate
    await candidate.ready
    if (generation !== mapGeneration) { candidate.destroy(); return }
    map = candidate
    mapVersion.value = map.dataVersion ?? ''
    rememberMapProvider(provider)
    Object.keys(live).forEach(drawMarker)
  } catch (reason) {
    candidate?.destroy()
    if (generation === mapGeneration) {
      mapError.value = reason instanceof Error ? reason.message : '地图加载失败'
    }
  } finally {
    if (pendingMap === candidate) pendingMap = undefined
    if (generation === mapGeneration) mapLoading.value = false
  }
}

onMounted(async () => {
  unsub = deviceWs.subscribe(onMsg)
  // Also recover from missed events during a WebSocket reconnect.
  devicePollTimer = window.setInterval(() => { void refreshDevices() }, 15_000)
  try {
    const workspace = session.workspaceId
    const [, jobData, health] = await Promise.allSettled([
      refreshDevices(),
      get<unknown>(`/wayline/api/v1/workspaces/${workspace}/jobs?page=1&page_size=6`),
      fetch('/actuator/health').then((r) => r.ok ? r.json() : Promise.reject())
    ])
    if (disposed) return
    if (jobData.status === 'fulfilled') jobs.value = listFrom<Record<string, unknown>>(jobData.value)
    if (health.status === 'fulfilled') serviceHealthy.value = health.value.status === 'UP'
  } finally {
    loading.value = false
  }
  if (disposed) return
  await initMap()
})

onBeforeUnmount(() => {
  disposed = true
  ++deviceRequest
  ++mapGeneration
  window.clearTimeout(deviceRefreshTimer)
  window.clearInterval(devicePollTimer)
  pendingMap?.destroy()
  unsub?.()
  if (map) { map.destroy(); map = undefined }
})
</script>

<template>
  <div class="stack">
    <div v-if="notice" class="notice">{{ notice }}</div>
    <div class="metric-grid">
      <article class="metric"><span>在线设备</span><strong>{{ devicesReady ? counts.online : '—' }}</strong><small>共 {{ counts.total }} 台已接入</small></article>
      <article class="metric"><span>机巢数量</span><strong>{{ devicesReady ? counts.docks : '—' }}</strong><small>统一管理与远程调度</small></article>
      <article class="metric"><span>今日任务</span><strong>{{ jobs.length }}</strong><small>最近任务队列</small></article>
      <article class="metric accent"><span>平台健康度</span><strong>{{ serviceHealthy ? '正常' : (loading ? '检查中' : '待确认') }}</strong><small>API / 数据 / 媒体链路</small></article>
    </div>

    <div class="dashboard-grid">
      <article class="panel map-panel">
        <div class="panel-head">
          <div><p class="eyebrow">FLEET MAP</p><h2>设备态势</h2></div>
          <div class="dash-map-actions">
            <div class="dash-map-source">
              <select v-model="mapProvider" aria-label="地图来源" :disabled="mapLoading" @change="initMap">
                <option value="offline">全国离线地图</option>
                <option value="amap">高德在线地图</option>
              </select>
              <small v-if="mapProvider === 'offline' && mapVersion" title="离线底图版本">{{ mapVersion }}</small>
            </div>
            <RouterLink to="/cockpit">地图监控</RouterLink>
          </div>
        </div>
        <div class="dash-map">
          <div ref="mapEl" class="dash-map-canvas"></div>
          <div v-if="mapLoading || mapError" class="empty centered dash-map-message">
            <span>{{ mapLoading ? '正在加载地图…' : mapError }}</span>
            <button v-if="!mapLoading" @click="initMap">重新加载地图</button>
          </div>
        </div>
      </article>
      <article class="panel">
        <div class="panel-head"><div><p class="eyebrow">MISSION QUEUE</p><h2>最近任务</h2></div><RouterLink to="/waylines">任务中心</RouterLink></div>
        <div v-if="jobs.length" class="list">
          <div v-for="(job, index) in jobs" :key="index" class="list-row">
            <span class="status-dot"></span>
            <div><strong>{{ job.job_name || job.name || `飞行任务 ${index + 1}` }}</strong><small>{{ job.status || '等待调度' }}</small></div>
            <time>{{ job.execute_time || job.create_time || '—' }}</time>
          </div>
        </div>
        <div v-else class="empty">暂无任务。上传 KMZ 航线并创建首个飞行任务。</div>
      </article>
    </div>
  </div>
</template>

<style scoped>
.dash-map-actions { display: flex; align-items: center; gap: 12px; }
.dash-map-source { display: grid; gap: 3px; }
.dash-map-source small { color: #9fb5c7; font-size: 10px; }
.dash-map-actions select { background: #0c192b; color: #e6efff; border: 1px solid #344356; border-radius: 6px; padding: 6px; }
.dash-map-message { position: absolute; inset: 0; background: #0c192b; }
.dash-map { position: relative; height: 320px; border-radius: var(--radius-md); overflow: hidden; border: 1px solid var(--panel-border); }
.dash-map-canvas { position: absolute; inset: 0; }
.dash-map .centered { display: grid; place-items: center; height: 100%; text-align: center; padding: 20px; }
:global(.dash-dot) { width: 12px; height: 12px; border-radius: 50%; background: var(--success); border: 2px solid #04121f; box-shadow: 0 0 8px var(--success); }
</style>
