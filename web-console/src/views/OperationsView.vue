<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { get } from '../services/api'

interface ComponentHealth { status?: string; details?: Record<string, unknown> }
const health = ref<Record<string, ComponentHealth>>({})
const overall = ref('UNKNOWN')
const checkedAt = ref('')

// 服务端口探测
interface ServiceProbe { name: string; icon: string; url: string; status: 'checking' | 'up' | 'down' }
const services = ref<ServiceProbe[]>([])
const consoleHost = window.location.hostname

// 组件中文名映射
const componentNames: Record<string, string> = {
  db: 'MySQL 数据库',
  redis: 'Redis 缓存',
  diskSpace: '磁盘空间',
  ping: '存活探针',
  livenessState: '存活状态',
  readinessState: '就绪状态',
  refreshScope: '配置刷新'
}

// 日志
interface LogEntry { time: string; level: string; msg: string; highlight: boolean }
const logs = ref<LogEntry[]>([])
const logKeyword = ref('')
const logLoading = ref(false)
const logAutoRefresh = ref(true)
let logTimer = 0

function probeService(svc: ServiceProbe) {
  svc.status = 'checking'
  // 用 fetch 探测端口，能连上(无论返回什么)即视为可达
  fetch(svc.url, { mode: 'no-cors', signal: AbortSignal.timeout(4000) })
    .then(() => { svc.status = 'up' })
    .catch(() => { svc.status = 'down' })
}

async function loadHealth() {
  try {
    const response = await fetch('/actuator/health')
    const data = await response.json()
    overall.value = data.status || 'UNKNOWN'
    health.value = data.components ?? {}
  } catch {
    overall.value = 'UNAVAILABLE'
  }
  checkedAt.value = new Date().toLocaleTimeString()
}

function initServices() {
  const list: ServiceProbe[] = [
    { name: 'Web 控制台', icon: '◉', url: `http://${consoleHost}:8080/healthz`, status: 'checking' },
    { name: 'MQTT (EMQX)', icon: '◇', url: `http://${consoleHost}:1883`, status: 'checking' },
    { name: 'RTSP (MediaMTX)', icon: '▣', url: `http://${consoleHost}:8554`, status: 'checking' },
    { name: 'MinIO Console', icon: '▧', url: `http://${consoleHost}:9001`, status: 'checking' },
    { name: 'API 文档门户', icon: 'Doc', url: `http://${consoleHost}:8081/healthz`, status: 'checking' }
  ]
  services.value = list
  list.forEach(probeService)
}

const healthList = computed(() =>
  Object.entries(health.value).map(([key, val]) => ({
    key,
    name: componentNames[key] || key,
    status: val.status || 'UNKNOWN',
    isUp: (val.status || '').toUpperCase() === 'UP'
  }))
)

function parseLog(line: string): LogEntry {
  // 格式: 2026-07-29 20:26:30.618  INFO 1 --- [main] c.y.x.y : message
  const m = line.match(/^(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+)\s+(INFO|WARN|ERROR|DEBUG|TRACE)\s/)
  const highlight = line.includes('拒绝上线') || line.includes('未在平台注册') || line.includes('ERROR')
  return {
    time: m ? m[1] : '',
    level: m ? m[2] : '',
    msg: m ? line.substring(m[0].length) : line,
    highlight
  }
}

async function loadLogs() {
  logLoading.value = true
  try {
    const kw = logKeyword.value.trim()
    const path = kw
      ? `/manage/api/v1/ops/logs?lines=300&keyword=${encodeURIComponent(kw)}`
      : '/manage/api/v1/ops/logs?lines=300'
    const lines = await get<string[]>(path)
    logs.value = lines.map(parseLog)
  } catch {
    logs.value = [{ time: '', level: '', msg: '日志加载失败', highlight: true }]
  } finally {
    logLoading.value = false
  }
}

function toggleAutoRefresh() {
  logAutoRefresh.value = !logAutoRefresh.value
  if (logAutoRefresh.value) {
    logTimer = window.setInterval(loadLogs, 5000)
  } else {
    window.clearInterval(logTimer)
  }
}

onMounted(() => {
  loadHealth()
  initServices()
  loadLogs()
  if (logAutoRefresh.value) logTimer = window.setInterval(loadLogs, 5000)
})
</script>

<template>
  <div class="stack">
    <div class="metric-grid">
      <article class="metric accent"><span>核心 API</span><strong :class="overall === 'UP' ? 'val-up' : 'val-down'">{{ overall }}</strong><small>检查于 {{ checkedAt }}</small></article>
      <article class="metric"><span>部署模式</span><strong>Compose</strong><small>全服务容器化运行</small></article>
      <article class="metric"><span>媒体接入</span><strong>RTSP</strong><small>MediaMTX / WHEP</small></article>
      <article class="metric"><span>运维版本</span><strong>P0</strong><small>可观测组件按 profile 启用</small></article>
    </div>

    <!-- 依赖健康度 + 服务探测 -->
    <article class="panel">
      <div class="panel-head">
        <div><p class="eyebrow">HEALTH CHECK</p><h2>依赖健康度</h2></div>
        <button class="link-button" @click="loadHealth">重新检查</button>
      </div>
      <div class="health-grid">
        <div v-for="item in healthList" :key="item.key" :class="['health-card', item.isUp ? 'ok' : 'bad']">
          <span class="hc-icon">{{ item.isUp ? '✓' : '✕' }}</span>
          <div class="hc-info"><strong>{{ item.name }}</strong><small>{{ item.key }}</small></div>
          <span :class="['hc-badge', item.isUp ? 'up' : 'down']">{{ item.status }}</span>
        </div>
        <div v-if="!healthList.length" class="empty" style="grid-column:1/-1">健康详情暂不可用。</div>
      </div>

      <div class="svc-head"><p class="eyebrow">SERVICE PROBE</p><h3>服务端口探测</h3></div>
      <div class="svc-grid">
        <div v-for="svc in services" :key="svc.name" :class="['svc-card', svc.status]">
          <span class="svc-icon">{{ svc.icon }}</span>
          <div class="svc-info"><strong>{{ svc.name }}</strong><small>{{ svc.url.split('://')[1] || svc.url }}</small></div>
          <span :class="['svc-dot', svc.status]"></span>
        </div>
      </div>
    </article>

    <!-- 实时日志 -->
    <article class="panel log-panel">
      <div class="panel-head">
        <div><p class="eyebrow">LIVE LOG</p><h2>系统日志</h2></div>
        <div class="log-controls">
          <input v-model="logKeyword" placeholder="过滤关键字" class="log-filter" @keyup.enter="loadLogs" />
          <button class="link-button" @click="loadLogs">查询</button>
          <button class="link-button" :class="{ active: logAutoRefresh }" @click="toggleAutoRefresh">{{ logAutoRefresh ? '⏸ 暂停' : '▶ 自动' }}</button>
        </div>
      </div>
      <div class="log-view">
        <div v-if="logLoading && !logs.length" class="empty">加载中…</div>
        <div v-for="(entry, i) in logs" :key="i" :class="['log-line', entry.level.toLowerCase(), { hl: entry.highlight }]">
          <span v-if="entry.time" class="log-time">{{ entry.time }}</span>
          <span v-if="entry.level" :class="['log-level', entry.level.toLowerCase()]">{{ entry.level }}</span>
          <span class="log-msg">{{ entry.msg }}</span>
        </div>
        <div v-if="!logs.length && !logLoading" class="empty">暂无日志</div>
      </div>
      <div class="log-foot">
        <span>共 {{ logs.length }} 行</span>
        <span v-if="logAutoRefresh" class="live-pill"><i></i>实时刷新 5s</span>
      </div>
    </article>
  </div>
</template>

<style scoped>
.val-up { color: #35d6a4; }
.val-down { color: #ff5d6c; }

.health-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; margin-bottom: 18px; }
.health-card { display: flex; align-items: center; gap: 10px; padding: 12px 14px; border-radius: 10px; border: 1px solid rgba(255,255,255,.08); background: rgba(255,255,255,.025); }
.health-card.ok { border-left: 3px solid #35d6a4; }
.health-card.bad { border-left: 3px solid #ff5d6c; }
.hc-icon { width: 24px; height: 24px; border-radius: 50%; display: grid; place-items: center; font-size: 13px; font-weight: 700; flex-shrink: 0; }
.health-card.ok .hc-icon { background: rgba(53,214,164,.15); color: #35d6a4; }
.health-card.bad .hc-icon { background: rgba(255,93,108,.15); color: #ff5d6c; }
.hc-info { flex: 1; min-width: 0; }
.hc-info strong { display: block; font-size: 13px; }
.hc-info small { font-size: 11px; color: var(--muted); font-family: monospace; }
.hc-badge { font-size: 10px; font-weight: 700; padding: 2px 8px; border-radius: 20px; }
.hc-badge.up { background: rgba(53,214,164,.15); color: #35d6a4; }
.hc-badge.down { background: rgba(255,93,108,.15); color: #ff5d6c; }

.svc-head { margin-top: 18px; margin-bottom: 10px; }
.svc-head h3 { font-size: 15px; }
.svc-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 8px; }
.svc-card { display: flex; align-items: center; gap: 8px; padding: 10px 12px; border-radius: 8px; border: 1px solid rgba(255,255,255,.06); background: rgba(255,255,255,.02); }
.svc-icon { font-size: 16px; width: 28px; text-align: center; }
.svc-info { flex: 1; min-width: 0; }
.svc-info strong { font-size: 12px; display: block; }
.svc-info small { font-size: 10px; color: var(--muted); font-family: monospace; }
.svc-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.svc-dot.checking { background: #f5a623; animation: pulse 1s infinite; }
.svc-dot.up { background: #35d6a4; }
.svc-dot.down { background: #ff5d6c; }

.log-panel { display: flex; flex-direction: column; }
.log-controls { display: flex; gap: 8px; align-items: center; }
.log-filter { width: 220px; height: 30px; padding: 0 10px; border-radius: 7px; border: 1px solid rgba(255,255,255,.1); background: rgba(0,0,0,.2); color: var(--text); font-size: 12px; }
.log-filter:focus { border-color: #3fa9ff; outline: none; }
.link-button.active { color: #35d6a4; }

.log-view { flex: 1; max-height: 400px; overflow-y: auto; background: #040810; border: 1px solid rgba(255,255,255,.06); border-radius: 8px; padding: 10px 0; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; line-height: 1.7; }
.log-line { display: flex; gap: 8px; padding: 1px 14px; }
.log-line:hover { background: rgba(255,255,255,.02); }
.log-line.hl { background: rgba(255,93,108,.06); border-left: 2px solid #ff5d6c; padding-left: 12px; }
.log-time { color: var(--muted); white-space: nowrap; flex-shrink: 0; }
.log-level { font-weight: 700; width: 42px; flex-shrink: 0; }
.log-level.info { color: #3fa9ff; }
.log-level.warn { color: #f5a623; }
.log-level.error { color: #ff5d6c; }
.log-level.debug { color: #6b7789; }
.log-msg { color: #c8d3e0; word-break: break-all; }
.log-line.error .log-msg { color: #ff8a8a; }

.log-foot { display: flex; justify-content: space-between; align-items: center; margin-top: 8px; font-size: 11px; color: var(--muted); }
.live-pill { display: flex; align-items: center; gap: 5px; color: #35d6a4; }
.live-pill i { width: 6px; height: 6px; border-radius: 50%; background: #35d6a4; animation: pulse 1.5s infinite; }

@keyframes pulse { 0%,100% { opacity: 1; } 50% { opacity: .3; } }
</style>
