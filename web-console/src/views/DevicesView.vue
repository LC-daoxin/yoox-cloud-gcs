<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { get, put, del } from '../services/api'
import { useSessionStore } from '../stores/session'
import type { Device } from '../types'

const session = useSessionStore()

// 接入配置自动按部署地址生成
const rt = (window as any).YOOX_RUNTIME || {}
const isLoopbackHost = (host: string) => host === 'localhost' || host === '127.0.0.1' || host === '::1'
const configuredPublicHost = String(rt.publicHost || '').trim()
const publicHost = isLoopbackHost(window.location.hostname)
  ? configuredPublicHost || window.location.hostname
  : window.location.hostname
const mqttUser = computed(() => session.user?.mqtt_username || '未配置')
const mqttPwd = computed(() => session.user?.mqtt_password || '未配置')
const pilotGatewayPort = String(rt.pilotGatewayPort || 9000)
const pilotGatewayHost = `${publicHost}:${pilotGatewayPort}`
const pilotHttpScheme = window.location.protocol === 'https:' ? 'https' : 'http'
const wsScheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
const connConfig = computed(() => [
  { key: 'mqtt',     label: 'MQTT 地址', value: `mqtt://${publicHost}:1883` },
  { key: 'mqttUser', label: 'MQTT 账号', value: mqttUser.value },
  { key: 'mqttPwd',  label: 'MQTT 密码', value: mqttPwd.value },
  { key: 'server',   label: '登录地址',  value: `${pilotHttpScheme}://${pilotGatewayHost}` },
  { key: 'account',  label: '账号',      value: mqttUser.value },
  { key: 'password', label: '密码',      value: mqttPwd.value },
  { key: 'ws',       label: 'WebSocket', value: `${wsScheme}://${pilotGatewayHost}/api/v1/ws` }
])
const copiedKey = ref('')
async function copy(key: string, val: string) {
  try {
    await navigator.clipboard.writeText(val)
    copiedKey.value = key
    setTimeout(() => { if (copiedKey.value === key) copiedKey.value = '' }, 1500)
  } catch { /* 忽略剪贴板权限失败 */ }
}

// 设备列表
const devices = ref<Device[]>([])
const loading = ref(true)
const loadError = ref('')
const search = ref('')
const filtered = computed(() => {
  const kw = search.value.toLowerCase()
  return devices.value.filter((d) =>
    !kw || [
      d.device_sn,
      d.device_name,
      d.nickname,
      aircraftSn(d),
      remoteControllerSn(d)
    ].some(
      (v) => String(v ?? '').toLowerCase().includes(kw)
    )
  )
})

function domainValue(device: Device): number | undefined {
  const value = Number(device.domain)
  return Number.isFinite(value) ? value : undefined
}

function aircraftSn(device: Device): string {
  if (device.aircraft_sn) return device.aircraft_sn
  if (device.children?.device_sn) return device.children.device_sn
  if (device.child_sn) return device.child_sn
  return domainValue(device) === 0 ? device.device_sn : ''
}

function remoteControllerSn(device: Device): string {
  if (device.remote_controller_sn) return device.remote_controller_sn
  return domainValue(device) === 2 ? device.device_sn : ''
}

function firmwareVersion(device: Device): string {
  return device.children?.firmware_version || device.firmware_version || ''
}

function reportedModel(value?: string): string {
  const model = value?.trim() || ''
  return model.toLowerCase() === 'undefined' ? '' : model
}

function aircraftModel(device: Device): string {
  if (device.children?.device_name) return reportedModel(device.children.device_name)
  return domainValue(device) === 0 ? reportedModel(device.device_name) : ''
}

const remoteControllerModels: Record<string, string> = {
  '20119/0': 'YOOX Smart Controller V3'
}

function remoteControllerModel(device: Device): string {
  if (domainValue(device) !== 2) return ''

  const reported = reportedModel(device.device_name)
  if (reported) return reported

  return remoteControllerModels[`${device.type}/${device.sub_type}`] || ''
}

function formatTime(value?: string): string {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

function isOnline(device: Device): boolean {
  return device.status === true || device.status === 'online' || device.status === '1'
}
async function load() {
  loading.value = true; loadError.value = ''
  try {
    devices.value = await get<Device[]>(`/manage/api/v1/devices/${session.workspaceId}/devices`)
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : '设备加载失败'
  } finally { loading.value = false }
}

const detail = ref<Device | null>(null)
const deleting = ref(false)
const editName = ref('')
const savingName = ref(false)
const editError = ref('')

function openDetail(device: Device) {
  detail.value = device
  editName.value = device.nickname || device.device_name || ''
  editError.value = ''
}

async function saveName() {
  if (!detail.value) return
  const nickname = editName.value.trim()
  if (!nickname) {
    editError.value = '设备名称不能为空'
    return
  }
  savingName.value = true
  editError.value = ''
  const deviceSn = detail.value.device_sn
  try {
    await put(`/manage/api/v1/devices/${session.workspaceId}/devices/${deviceSn}`, { nickname })
    await load()
    detail.value = devices.value.find((device) => device.device_sn === deviceSn) || null
    if (detail.value) editName.value = detail.value.nickname || nickname
  } catch (e) {
    editError.value = e instanceof Error ? e.message : '名称保存失败'
  } finally {
    savingName.value = false
  }
}

async function removeDevice() {
  if (!detail.value) return
  const sn = detail.value.device_sn
  if (!window.confirm(`确认解除设备「${detail.value.nickname || detail.value.device_name || sn}」的工作空间绑定？\n设备保持连接或再次上云后会自动重新注册。`)) return
  deleting.value = true
  try {
    await del(`/manage/api/v1/devices/${sn}/unbinding`)
    detail.value = null
    await load()
  } catch (e) {
    alert('删除失败：' + (e instanceof Error ? e.message : '未知错误'))
  } finally {
    deleting.value = false
  }
}

onMounted(async () => {
  try {
    await session.refresh()
  } catch {
    return
  }
  await load()
})
</script>

<template>
  <div class="dv-page">
    <div class="dv-grid">
      <!-- 设备列表 -->
      <div class="dv-left">
        <div class="toolbar">
          <div class="search"><span>⌕</span><input v-model="search" placeholder="搜索序列号或名称" /></div>
          <button class="ghost" @click="load">刷新</button>
        </div>
        <div v-if="loadError" class="notice danger">{{ loadError }}</div>
        <article class="panel">
          <div v-if="loading" class="empty">加载中…</div>
          <div v-else-if="!filtered.length" class="dv-empty">
            <span style="font-size:36px;opacity:.15">◇</span>
            <strong>暂无注册设备</strong>
            <p>按右侧参数连接 APP，平台将在首次收到设备拓扑后自动注册飞机与遥控器。</p>
          </div>
          <div v-else class="dv-table-wrap">
            <table class="dv-table">
              <thead><tr><th>名称</th><th>飞机 SN</th><th>遥控器 SN</th><th>固件</th><th>状态</th><th></th></tr></thead>
              <tbody>
                <tr v-for="d in filtered" :key="d.device_sn"
                  :class="{ 'row-sel': detail?.device_sn === d.device_sn }" @click="openDetail(d)">
                  <td><div class="dn-cell"><span :class="['dn-dot', { live: isOnline(d) }]"></span>{{ d.nickname || d.device_name || '—' }}</div></td>
                  <td class="mono">{{ aircraftSn(d) || '待上报' }}</td>
                  <td class="mono">{{ remoteControllerSn(d) || '—' }}</td>
                  <td>{{ firmwareVersion(d) || '—' }}</td>
                  <td><span :class="['sdot', { live: isOnline(d) }]"><i></i>{{ isOnline(d) ? '在线' : '离线' }}</span></td>
                  <td><button class="link-button" @click.stop="openDetail(d)">详情</button></td>
                </tr>
              </tbody>
            </table>
          </div>
        </article>
        <p class="dv-note">APP 连接云服务并上报拓扑后，平台会自动注册并关联遥控器与飞机。未收到飞机拓扑时，飞机 SN 显示“待上报”。</p>
      </div>

      <!-- 接入配置 -->
      <aside class="conn-panel panel">
        <p class="eyebrow">APP 接入配置</p>
        <h2>遥控器云服务参数</h2>
        <p class="help">将以下参数逐项填入遥控器「设置 → 云服务」。</p>
        <div class="conf-list">
          <div v-for="item in connConfig" :key="item.key" class="conf-row">
            <span class="conf-lbl">{{ item.label }}</span>
            <code class="conf-val">{{ item.value }}</code>
            <button class="ghost small" @click="copy(item.key, item.value)">{{ copiedKey === item.key ? '已复制' : '复制' }}</button>
          </div>
        </div>
        <div class="conn-steps">
          <p class="eyebrow" style="margin-top:14px">配置步骤</p>
          <ol>
            <li>遥控器「设置 → 云服务」→ 上云设备选「遥控器」</li>
            <li>按上方参数逐项填写 MQTT 和登录信息</li>
            <li>登录地址端口使用 <strong class="port-tip">9000</strong></li>
            <li>WebSocket 使用同一 9000 端口，路径 <code>/api/v1/ws</code></li>
            <li>连接成功后在「虚拟座舱」执行远程控制</li>
          </ol>
        </div>
      </aside>
    </div>

    <!-- 详情 -->
    <div v-if="detail" class="drawer-backdrop" @click.self="detail = null">
      <aside class="drawer">
        <button class="drawer-close" @click="detail = null">×</button>
        <p class="eyebrow">设备详情</p>
        <h2>{{ detail.nickname || detail.device_name || '未命名' }}</h2>
        <form class="name-editor" @submit.prevent="saveName">
          <label for="device-nickname">平台设备名称</label>
          <div>
            <input id="device-nickname" v-model="editName" maxlength="64" placeholder="输入设备名称" />
            <button class="primary" :disabled="savingName">{{ savingName ? '保存中…' : '保存名称' }}</button>
          </div>
          <p v-if="editError" class="field-error">{{ editError }}</p>
        </form>
        <dl>
          <dt>飞机 SN</dt><dd class="mono">{{ aircraftSn(detail) || '待上报' }}</dd>
          <dt>遥控器 SN</dt><dd class="mono">{{ remoteControllerSn(detail) || '—' }}</dd>
          <dt>飞机型号</dt><dd>{{ aircraftModel(detail) || '待上报' }}</dd>
          <dt>遥控器型号</dt><dd>{{ remoteControllerModel(detail) || '—' }}</dd>
          <dt>网关 SN</dt><dd class="mono">{{ detail.device_sn }}</dd>
          <dt>固件版本</dt><dd>{{ firmwareVersion(detail) || '未上报' }}</dd>
          <dt>最近连接</dt><dd>{{ formatTime(detail.login_time) || '未上报' }}</dd>
          <dt>设备域</dt><dd>{{ detail.domain ?? '—' }}</dd>
          <dt>型号/子型</dt><dd>{{ detail.type ?? '—' }} / {{ detail.sub_type ?? '—' }}</dd>
          <dt>绑定状态</dt><dd>{{ detail.bound_status ? '已绑定' : '未绑定' }}</dd>
          <dt>在线状态</dt><dd>{{ isOnline(detail) ? '在线' : '离线' }}</dd>
        </dl>
        <div class="drawer-actions">
          <button class="danger full" :disabled="deleting" @click="removeDevice">
            {{ deleting ? '解绑中…' : '解除设备绑定' }}
          </button>
        </div>
        <p class="drawer-note">解绑只移除当前归属；设备保持连接或再次上云后会自动重新注册。</p>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.dv-page { display: flex; flex-direction: column; gap: 16px; }
.dv-grid { display: grid; grid-template-columns: minmax(0, 1fr) 340px; gap: 16px; align-items: start; max-width: 100%; }
.dv-left { display: flex; flex-direction: column; gap: 12px; min-width: 0; }
.dv-table-wrap { overflow-x: auto; margin: 0 -2px; }
.dv-table { width: 100%; min-width: 600px; border-collapse: collapse; }
.dv-table th,.dv-table td { padding: 10px 12px; border-bottom: 1px solid var(--panel-border); text-align: left; font-size: 13px; white-space: nowrap; }
.dv-table th { color: var(--muted); font-weight: 500; }
.dv-table tr:hover td { background: rgba(255,255,255,.025); cursor: pointer; }
.dv-table tr.row-sel td { background: rgba(53,214,164,.07); }
.dn-cell { display: flex; align-items: center; gap: 8px; }
.dn-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--muted); flex-shrink: 0; }
.dn-dot.live { background: #35d6a4; box-shadow: 0 0 5px #35d6a4; }
.sdot { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; padding: 2px 8px; border-radius: 20px; background: rgba(255,255,255,.05); color: var(--muted); }
.sdot i { width: 6px; height: 6px; border-radius: 50%; background: var(--muted); }
.sdot.live { color: #35d6a4; }
.sdot.live i { background: #35d6a4; }
.dv-empty { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 48px 24px; text-align: center; }
.dv-note { font-size: 12px; color: var(--muted); line-height: 1.6; }
.conn-panel { display: flex; flex-direction: column; gap: 10px; min-width: 0; position: sticky; top: 16px; }
.conf-list { display: flex; flex-direction: column; gap: 6px; }
.conf-row { display: grid; grid-template-columns: 72px 1fr auto; align-items: center; gap: 8px; padding: 8px 10px; background: rgba(255,255,255,.025); border: 1px solid var(--panel-border); border-radius: 8px; }
.conf-lbl { font-size: 11px; color: var(--muted); }
.conf-val { font-size: 12px; font-family: ui-monospace,monospace; word-break: break-all; color: var(--text); }
.conn-steps { font-size: 13px; color: var(--muted); }
.conn-steps ol { padding-left: 18px; line-height: 2.1; margin: 0; }
.port-tip { color: #ff5d6c; }
.name-editor { display: grid; gap: 7px; margin: 18px 0; }
.name-editor > label { color: var(--muted); font-size: 11px; }
.name-editor > div { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 8px; }
.name-editor input { min-width: 0; }
.field-error { margin: 0; color: #ff5d6c; font-size: 11px; }
.drawer-actions { margin-top: 18px; padding-top: 14px; border-top: 1px solid var(--panel-border); }
.danger { background: rgba(255,93,108,.12); border: 1px solid rgba(255,93,108,.4); color: #ff5d6c; border-radius: 8px; padding: 9px 16px; font-size: 13px; cursor: pointer; }
.danger:hover:not(:disabled) { background: rgba(255,93,108,.22); }
.danger:disabled { opacity: .5; cursor: not-allowed; }
.dv-left .toolbar { flex-wrap: nowrap; gap: 10px; }
.dv-left .toolbar .search { flex: 1 1 auto; height: 38px; min-width: 0; }
.dv-left .toolbar > button { white-space: nowrap; flex-shrink: 0; min-height: 38px; height: 38px; align-self: center; }
@media (max-width: 1080px) { .dv-grid { grid-template-columns: 1fr; } .conn-panel { position: static; } }
</style>
