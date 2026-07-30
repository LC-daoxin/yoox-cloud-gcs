<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { get, listFrom } from '../services/api'
import { deviceWs, type WsMessage } from '../services/ws'
import { loadAMap, getAmapKey } from '../services/amap'
import { wgs84ToGcj02 } from '../services/geo'
import { useSessionStore } from '../stores/session'
import type { Device, DeviceTelemetry, OsdHost } from '../types'

const session = useSessionStore()
const devices = ref<Device[]>([])
const jobs = ref<Record<string, unknown>[]>([])
const serviceHealthy = ref(false)
const loading = ref(true)
const notice = ref('')
const mapEl = ref<HTMLDivElement>()
const liveCount = ref(0)
const hasKey = computed(() => Boolean(getAmapKey()))

const onlineCount = computed(() => devices.value.filter((d) =>
  d.status === 'online' || d.status === 'ONLINE' || d.child_sn
).length)
const dockCount = computed(() => devices.value.filter((d) => d.domain === 3 || Boolean(d.child_sn)).length)

let map: any
let AMapRef: any
const markers = new Map<string, any>()
const live = reactive<Record<string, [number, number]>>({})
let unsub: (() => void) | undefined

function onMsg(msg: WsMessage) {
  if (msg.biz_code === 'gateway_osd' || msg.biz_code === 'device_osd') {
    const payload = msg.data as DeviceTelemetry
    const host = payload?.host as OsdHost
    if (!payload?.sn || !host) return
    const lng = Number(host.longitude)
    const lat = Number(host.latitude)
    if (!Number.isFinite(lng) || !Number.isFinite(lat) || (lng === 0 && lat === 0)) return
    live[payload.sn] = wgs84ToGcj02(lng, lat)
    liveCount.value = Object.keys(live).length
    drawMarker(payload.sn)
  }
}

function drawMarker(sn: string) {
  if (!map || !AMapRef) return
  const pos = live[sn]
  let marker = markers.get(sn)
  if (!marker) {
    marker = new AMapRef.Marker({ position: pos, anchor: 'center',
      content: '<div class="dash-dot"></div>' })
    map.add(marker)
    markers.set(sn, marker)
  } else {
    marker.setPosition(pos)
  }
  map.setCenter(pos)
}

async function initMap() {
  if (!hasKey.value || !mapEl.value) return
  try {
    AMapRef = await loadAMap()
    map = new AMapRef.Map(mapEl.value, { zoom: 11, center: [116.397, 39.909], mapStyle: 'amap://styles/darkblue', viewMode: '2D' })
    Object.keys(live).forEach(drawMarker)
  } catch { /* 地图不可用时保持占位 */ }
}

onMounted(async () => {
  try {
    const workspace = session.workspaceId
    const [deviceData, jobData, health] = await Promise.allSettled([
      get<Device[]>(`/manage/api/v1/devices/${workspace}/devices`),
      get<unknown>(`/wayline/api/v1/workspaces/${workspace}/jobs?page=1&page_size=6`),
      fetch('/actuator/health').then((r) => r.ok ? r.json() : Promise.reject())
    ])
    if (deviceData.status === 'fulfilled') devices.value = deviceData.value
    if (jobData.status === 'fulfilled') jobs.value = listFrom<Record<string, unknown>>(jobData.value)
    if (health.status === 'fulfilled') serviceHealthy.value = health.value.status === 'UP'
    if (deviceData.status === 'rejected') notice.value = deviceData.reason?.message ?? '设备数据暂不可用'
  } finally {
    loading.value = false
  }
  unsub = deviceWs.subscribe(onMsg)
  await initMap()
})

onBeforeUnmount(() => { unsub?.(); if (map) { map.destroy?.(); map = undefined } })
</script>

<template>
  <div class="stack">
    <div v-if="notice" class="notice">{{ notice }}</div>
    <div class="metric-grid">
      <article class="metric"><span>在线设备</span><strong>{{ onlineCount }}</strong><small>共 {{ devices.length }} 台已接入</small></article>
      <article class="metric"><span>机巢数量</span><strong>{{ dockCount }}</strong><small>统一管理与远程调度</small></article>
      <article class="metric"><span>今日任务</span><strong>{{ jobs.length }}</strong><small>最近任务队列</small></article>
      <article class="metric accent"><span>平台健康度</span><strong>{{ serviceHealthy ? '正常' : (loading ? '检查中' : '待确认') }}</strong><small>API / 数据 / 媒体链路</small></article>
    </div>

    <div class="dashboard-grid">
      <article class="panel map-panel">
        <div class="panel-head"><div><p class="eyebrow">FLEET MAP</p><h2>设备态势</h2></div><RouterLink to="/map">地图监控</RouterLink></div>
        <div class="dash-map">
          <div v-if="hasKey" ref="mapEl" class="dash-map-canvas"></div>
          <div v-else class="empty centered">
            未配置高德地图 Key（runtime-config.js）。配置后此处展示实时设备位置。
            <br />当前在线飞行器：{{ liveCount }}
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
.dash-map { position: relative; height: 320px; border-radius: var(--radius-md); overflow: hidden; border: 1px solid var(--panel-border); }
.dash-map-canvas { position: absolute; inset: 0; }
.dash-map .centered { display: grid; place-items: center; height: 100%; text-align: center; padding: 20px; }
:global(.dash-dot) { width: 12px; height: 12px; border-radius: 50%; background: var(--success); border: 2px solid #04121f; box-shadow: 0 0 8px var(--success); }
</style>
