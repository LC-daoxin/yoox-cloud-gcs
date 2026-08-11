<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { del, get, getToken, listFrom, post, put } from '../services/api'
import { deviceWs, type WsMessage } from '../services/ws'
import { useSessionStore } from '../stores/session'
import type { Device } from '../types'

interface Wayline {
  id: string
  name: string
  drone_model_key?: string
  payload_model_keys?: string[]
  template_types?: number[]
  user_name?: string
  update_time?: number
  favorited?: boolean
}
interface Job {
  job_id: string
  job_name: string
  file_name: string
  dock_sn: string
  dock_name: string
  execute_time: string
  status: number
  progress: number
  code: number
  username: string
}

const session = useSessionStore()
const waylines = ref<Wayline[]>([])
const jobs = ref<Job[]>([])
const devices = ref<Device[]>([])
const tab = ref<'files' | 'jobs'>('files')
const loading = ref(false)
const error = ref('')
const fileInput = ref<HTMLInputElement>()
const showCreate = ref(false)
const jobForm = ref({
  name: '',
  fileId: '',
  dockSn: '',
  rthAltitude: 100,
  outOfControlAction: 0,
  minBatteryCapacity: 60,
  waylinePrecisionType: 0,
  barrierSwitchState: 1,
  takeoffAltitude: 100,
  firstWaypointSpeed: 10,
  returnSpeed: 10,
  mediaUploadMethod: 0,
  useAlternateLandPoint: false,
  alternateLongitude: 0,
  alternateLatitude: 0,
  safeLandHeight: 10
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const workspace = session.workspaceId
    const [filesData, jobsData, deviceData] = await Promise.all([
      get<unknown>(`/wayline/api/v1/workspaces/${workspace}/waylines?page=1&page_size=50&order_by=update_time%20desc`),
      get<unknown>(`/wayline/api/v1/workspaces/${workspace}/jobs?page=1&page_size=50`),
      get<Device[]>(`/manage/api/v1/devices/${workspace}/devices`)
    ])
    waylines.value = listFrom<Wayline>(filesData)
    jobs.value = listFrom<Job>(jobsData)
    devices.value = deviceData
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '航线数据加载失败'
  } finally {
    loading.value = false
  }
}

async function upload(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  if (!file.name.toLowerCase().endsWith('.kmz')) {
    error.value = '请选择 KMZ 航线文件'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const form = new FormData()
    form.append('file', file)
    const response = await fetch(`/wayline/api/v1/workspaces/${session.workspaceId}/waylines/file/upload`, {
      method: 'POST',
      headers: { 'x-auth-token': getToken() },
      body: form
    })
    const result = await response.json()
    if (!response.ok || result.code !== 0) throw new Error(result.message || '上传失败')
    await load()
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '上传失败'
  } finally {
    loading.value = false
    input.value = ''
  }
}

async function remove(file: Wayline) {
  if (!window.confirm(`确认删除航线“${file.name}”？`)) return
  try {
    await del(`/wayline/api/v1/workspaces/${session.workspaceId}/waylines/${file.id}`)
    await load()
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '删除失败'
  }
}

function statusName(value: number) {
  return ({ 1: '待执行', 2: '进行中', 3: '成功', 4: '已取消', 5: '失败', 6: '已暂停' } as Record<number, string>)[value] ?? `状态 ${value}`
}

function isJobFinished(status: number) {
  return [3, 4, 5].includes(status)
}

function errorCodeText(code: number | undefined) {
  if (!code || code === 0) return ''
  return `错误码 ${code}`
}

function openCreate() {
  const file = waylines.value[0]
  const dock = devices.value.find((device) => Number(device.domain) === 3 || Boolean(device.aircraft_sn || device.child_device_sn || device.child_sn || device.children?.device_sn))
  jobForm.value = {
    name: file ? `${file.name}-任务` : '',
    fileId: file?.id ?? '',
    dockSn: dock?.device_sn ?? '',
    rthAltitude: 100,
    outOfControlAction: 0,
    minBatteryCapacity: 60,
    waylinePrecisionType: 0,
    barrierSwitchState: 1,
    takeoffAltitude: 100,
    firstWaypointSpeed: 10,
    returnSpeed: 10,
    mediaUploadMethod: 0,
    useAlternateLandPoint: false,
    alternateLongitude: 0,
    alternateLatitude: 0,
    safeLandHeight: 10
  }
  showCreate.value = true
}

async function createJob() {
  const file = waylines.value.find((item) => item.id === jobForm.value.fileId)
  if (!file) return
  loading.value = true
  error.value = ''
  try {
    await post(`/wayline/api/v1/workspaces/${session.workspaceId}/flight-tasks`, {
      name: jobForm.value.name,
      file_id: jobForm.value.fileId,
      dock_sn: jobForm.value.dockSn,
      wayline_type: file.template_types?.[0] ?? 0,
      task_type: 0,
      rth_altitude: jobForm.value.rthAltitude,
      out_of_control_action: jobForm.value.outOfControlAction,
      min_battery_capacity: jobForm.value.minBatteryCapacity,
      wayline_precision_type: jobForm.value.waylinePrecisionType,
      barrier_switch_state: jobForm.value.barrierSwitchState,
      takeoff_altitude: jobForm.value.takeoffAltitude,
      first_waypoint_speed: jobForm.value.firstWaypointSpeed,
      return_speed: jobForm.value.returnSpeed,
      media_upload_method: jobForm.value.mediaUploadMethod,
      alternate_land_point: jobForm.value.useAlternateLandPoint
        ? {
            longitude: jobForm.value.alternateLongitude,
            latitude: jobForm.value.alternateLatitude,
            safe_land_height: jobForm.value.safeLandHeight,
            is_configured: 1
          }
        : { is_configured: 0 }
    })
    showCreate.value = false
    tab.value = 'jobs'
    await load()
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '任务创建失败'
  } finally {
    loading.value = false
  }
}

async function cancelJob(job: Job) {
  if (!window.confirm(`确认取消任务“${job.job_name}”？`)) return
  try {
    await del(`/wayline/api/v1/workspaces/${session.workspaceId}/jobs?job_id=${encodeURIComponent(job.job_id)}`)
    await load()
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '任务取消失败'
  }
}

async function deleteJob(job: Job) {
  if (!window.confirm(`确认删除任务记录“${job.job_name}”？此操作仅移除记录，不会影响设备。`)) return
  try {
    await del(`/wayline/api/v1/workspaces/${session.workspaceId}/jobs/${encodeURIComponent(job.job_id)}`)
    await load()
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '任务删除失败'
  }
}

async function changeJob(job: Job, status: 0 | 1) {
  try {
    await put(`/wayline/api/v1/workspaces/${session.workspaceId}/jobs/${job.job_id}`, { status })
    await load()
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '任务状态更新失败'
  }
}

// 设备上报的航线任务状态（flighttask_progress.status）→ 任务表数字状态。
const statusFromProgress: Record<string, number> = {
  sent: 2, in_progress: 2, paused: 6, ok: 3,
  failed: 5, canceled: 4, partially_done: 3, rejected: 5, timeout: 5
}

// 订阅 WebSocket 的 flighttask_progress，实时刷新对应任务的进度与状态，
// 无需轮询。data 结构为 EventsReceiver：{ bid=jobId, output:{ status, progress:{ percent } } }。
function onWaylineProgress(msg: WsMessage) {
  if (msg.biz_code !== 'flighttask_progress') return
  const data = (msg.data ?? {}) as Record<string, any>
  const jobId = String(data.bid ?? data.job_id ?? '')
  if (!jobId) return
  const job = jobs.value.find((item) => item.job_id === jobId)
  if (!job) return
  const output = (data.output ?? {}) as Record<string, any>
  const percent = Number(output?.progress?.percent)
  if (Number.isFinite(percent)) job.progress = percent
  const status = String(output?.status ?? '')
  if (status in statusFromProgress) job.status = statusFromProgress[status]
}

let unsubscribe: (() => void) | undefined
onMounted(() => {
  load()
  unsubscribe = deviceWs.subscribe(onWaylineProgress)
})
onBeforeUnmount(() => unsubscribe?.())
</script>

<template>
  <div class="stack">
    <div class="toolbar">
      <div class="tabs"><button :class="{ active: tab === 'files' }" @click="tab = 'files'">航线库 <b>{{ waylines.length }}</b></button><button :class="{ active: tab === 'jobs' }" @click="tab = 'jobs'">飞行任务 <b>{{ jobs.length }}</b></button></div>
      <div class="toolbar-actions"><button class="ghost" :disabled="loading || !waylines.length" @click="openCreate">创建飞行任务</button><input ref="fileInput" hidden type="file" accept=".kmz" @change="upload" /><button class="primary" :disabled="loading" @click="fileInput?.click()">上传 KMZ 航线</button></div>
    </div>
    <div v-if="error" class="notice danger">{{ error }}</div>
    <article v-if="tab === 'files'" class="panel table-panel">
      <table><thead><tr><th>航线名称</th><th>飞行器型号</th><th>负载</th><th>类型</th><th>更新时间</th><th></th></tr></thead>
        <tbody><tr v-for="file in waylines" :key="file.id">
          <td><div class="device-name"><span class="device-glyph">⌁</span><div><strong>{{ file.name }}</strong><small>{{ file.user_name || 'YOOX' }}</small></div></div></td>
          <td class="mono">{{ file.drone_model_key || '—' }}</td><td>{{ file.payload_model_keys?.join(', ') || '—' }}</td>
          <td>{{ file.template_types?.join(', ') || '航点航线' }}</td><td>{{ file.update_time ? new Date(file.update_time).toLocaleString() : '—' }}</td>
          <td><button class="link-button danger-text" @click="remove(file)">删除</button></td>
        </tr></tbody>
      </table>
      <div v-if="!loading && !waylines.length" class="empty">航线库为空，上传由航线编辑器导出的 KMZ 文件开始使用。</div>
    </article>
    <article v-else class="panel table-panel">
      <table><thead><tr><th>任务</th><th>航线</th><th>执行机巢</th><th>创建者</th><th>计划时间</th><th>进度</th><th>状态</th><th>操作</th></tr></thead>
        <tbody><tr v-for="job in jobs" :key="job.job_id"><td><strong>{{ job.job_name }}</strong></td><td>{{ job.file_name }}</td><td :title="job.dock_sn">{{ job.dock_name || '-' }}<br><small class="mono">{{ job.dock_sn || '-' }}</small></td><td>{{ job.username || '-' }}</td><td>{{ job.execute_time || '立即执行' }}</td><td><div class="progress"><i :style="{ width: `${job.progress || 0}%` }"></i></div></td><td><span class="online-tag"><i></i>{{ statusName(job.status) }}</span><small v-if="job.code && job.code !== 0" class="danger-text">{{ errorCodeText(job.code) }}</small></td><td><div class="table-actions"><button v-if="job.status === 2" class="link-button" @click="changeJob(job, 0)">暂停</button><button v-if="job.status === 6" class="link-button" @click="changeJob(job, 1)">恢复</button><button v-if="[1, 2, 6].includes(job.status)" class="link-button danger-text" @click="cancelJob(job)">取消</button><button v-if="isJobFinished(job.status)" class="link-button danger-text" @click="deleteJob(job)">删除</button></div></td></tr></tbody>
      </table>
      <div v-if="!loading && !jobs.length" class="empty">暂无飞行任务。任务创建接口已开放，可由调度系统或 API 客户端调用。</div>
    </article>

    <div v-if="showCreate" class="drawer-backdrop" @click.self="showCreate = false">
      <aside class="drawer task-drawer">
        <button class="drawer-close" @click="showCreate = false">×</button>
        <p class="eyebrow">FLIGHT TASK</p><h2>创建立即执行任务</h2>
        <form class="task-form" @submit.prevent="createJob">
          <label>任务名称<input v-model="jobForm.name" required maxlength="64" /></label>
          <label>航线
            <select v-model="jobForm.fileId" required><option v-for="file in waylines" :key="file.id" :value="file.id">{{ file.name }}</option></select>
          </label>
          <label>执行机巢
            <select v-model="jobForm.dockSn" required><option v-for="device in devices.filter((item) => Number(item.domain) === 3 || Boolean(item.aircraft_sn || item.child_device_sn || item.child_sn || item.children?.device_sn))" :key="device.device_sn" :value="device.device_sn">{{ device.nickname || device.device_name || '未命名' }}（{{ device.device_sn }}）</option></select>
          </label>
          <div class="field-grid">
            <label>返航高度（米）<input v-model.number="jobForm.rthAltitude" type="number" min="20" max="500" required /></label>
            <label>最低电量（%）<input v-model.number="jobForm.minBatteryCapacity" type="number" min="15" max="100" required /></label>
            <label>失控动作<select v-model.number="jobForm.outOfControlAction"><option :value="0">返航</option><option :value="1">悬停</option><option :value="2">降落</option></select></label>
            <label>航线精度<select v-model.number="jobForm.waylinePrecisionType"><option :value="0">GPS 任务</option><option :value="1">高精度 RTK 任务</option></select></label>
            <label>避障开关<select v-model.number="jobForm.barrierSwitchState"><option :value="1">打开避障</option><option :value="0">关闭避障</option></select></label>
            <label>起飞高度（米）<input v-model.number="jobForm.takeoffAltitude" type="number" min="1" max="1500" required /></label>
            <label>去首航点速度（m/s）<input v-model.number="jobForm.firstWaypointSpeed" type="number" min="1" max="25" required /></label>
            <label>返航速度（m/s）<input v-model.number="jobForm.returnSpeed" type="number" min="1" max="25" required /></label>
            <label>媒体上传方式<select v-model.number="jobForm.mediaUploadMethod"><option :value="0">落地上传</option><option :value="1">边飞边传</option></select></label>
          </div>
          <label class="checkbox-row"><input v-model="jobForm.useAlternateLandPoint" type="checkbox" />设置备降点</label>
          <div v-if="jobForm.useAlternateLandPoint" class="field-grid">
            <label>备降点经度<input v-model.number="jobForm.alternateLongitude" type="number" step="0.0000001" min="-180" max="180" required /></label>
            <label>备降点纬度<input v-model.number="jobForm.alternateLatitude" type="number" step="0.0000001" min="-90" max="90" required /></label>
            <label>安全降落高度（米）<input v-model.number="jobForm.safeLandHeight" type="number" min="1" max="100" required /></label>
          </div>
          <div class="safety-card"><span>飞行安全</span><small>提交前请确认航线、返航高度、空域、电量、天气、现场人员和应急接管条件。</small></div>
          <p v-if="error" class="form-error">{{ error }}</p>
          <button class="primary full" :disabled="loading || !jobForm.fileId || !jobForm.dockSn">{{ loading ? '正在下发…' : '创建并立即执行' }}</button>
        </form>
      </aside>
    </div>
  </div>
</template>
