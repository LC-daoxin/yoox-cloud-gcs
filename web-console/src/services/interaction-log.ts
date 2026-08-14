import { readonly, shallowRef } from 'vue'

export type InteractionTransport = 'HTTP' | 'MQTT' | 'WebSocket' | 'SYSTEM'
export type InteractionDirection = 'IN' | 'OUT' | 'INFO' | 'ERROR'

export interface InteractionLogEntry {
  id: string
  timestamp: number
  transport: InteractionTransport
  direction: InteractionDirection
  method?: string
  path?: string
  topic?: string
  status?: number | string
  durationMs?: number
  summary?: string
  payload?: unknown
}

const STORAGE_KEY = 'yoox_interaction_logs'
const MAX_ENTRIES = 1000
const MAX_PERSISTED_CHARACTERS = 500_000
const VIEW_FLUSH_INTERVAL_MS = 250
const STORAGE_FLUSH_INTERVAL_MS = 1000
const SENSITIVE_KEY = /password|passwd|token|secret|authorization|credential|mqtt_password/i

function sanitize(value: unknown, depth = 0): unknown {
  if (depth > 8) return '[max depth]'
  if (Array.isArray(value)) return value.map((item) => sanitize(item, depth + 1))
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value as Record<string, unknown>).map(([key, item]) => [
      key,
      SENSITIVE_KEY.test(key) ? '***' : sanitize(item, depth + 1)
    ]))
  }
  if (typeof value === 'string' && value.length > 20_000) return `${value.slice(0, 20_000)}…`
  return value
}

function loadEntries(): InteractionLogEntry[] {
  try {
    const stored = sessionStorage.getItem(STORAGE_KEY)
    const parsed = stored ? JSON.parse(stored) : []
    return Array.isArray(parsed) ? parsed.slice(0, MAX_ENTRIES) : []
  } catch {
    return []
  }
}

// DRC 会同时产生 10 Hz 下行、回包和 OSD。原实现每帧都在深响应式数组头部
// unshift，并同步 JSON.stringify 全部 1000 条后写 sessionStorage，主线程会出现
// 数百毫秒卡顿，进而让 delay_time=300 的飞控包过期。高频原始日志先写普通
// 缓冲区，再以 4 Hz 刷新界面、1 Hz 批量持久化。
let entryBuffer = loadEntries()
const entries = shallowRef<InteractionLogEntry[]>(entryBuffer.slice())
let viewFlushTimer: ReturnType<typeof setTimeout> | undefined
let storageFlushTimer: ReturnType<typeof setTimeout> | undefined
let storagePersistenceDisabled = false
let sequence = 0

function createId() {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  sequence += 1
  return `${Date.now().toString(36)}-${sequence.toString(36)}-${Math.random().toString(36).slice(2, 9)}`
}

function flushView() {
  viewFlushTimer = undefined
  entries.value = entryBuffer.slice()
}

function serializeEntriesWithinBudget() {
  const serializedEntries: string[] = []
  let usedCharacters = 2
  for (const entry of entryBuffer) {
    let serialized: string
    try {
      serialized = JSON.stringify(entry)
    } catch {
      continue
    }
    const separatorCharacters = serializedEntries.length > 0 ? 1 : 0
    if (usedCharacters + separatorCharacters + serialized.length > MAX_PERSISTED_CHARACTERS) break
    serializedEntries.push(serialized)
    usedCharacters += separatorCharacters + serialized.length
  }
  return `[${serializedEntries.join(',')}]`
}

function persist() {
  storageFlushTimer = undefined
  if (storagePersistenceDisabled) return
  try {
    if (entryBuffer.length === 0) sessionStorage.removeItem(STORAGE_KEY)
    else sessionStorage.setItem(STORAGE_KEY, serializeEntriesWithinBudget())
  } catch {
    // 配额或浏览器策略拒绝存储时，本页会话内继续保留日志，但不在每秒重试
    // 同一个同步异常，避免阻塞 10 Hz DRC 控制循环。
    storagePersistenceDisabled = true
  }
}

function scheduleFlushes() {
  if (viewFlushTimer === undefined) {
    viewFlushTimer = setTimeout(flushView, VIEW_FLUSH_INTERVAL_MS)
  }
  if (!storagePersistenceDisabled && storageFlushTimer === undefined) {
    storageFlushTimer = setTimeout(persist, STORAGE_FLUSH_INTERVAL_MS)
  }
}

function flushStorageNow() {
  if (storageFlushTimer !== undefined) clearTimeout(storageFlushTimer)
  storageFlushTimer = undefined
  persist()
}

if (typeof window !== 'undefined') {
  window.addEventListener('pagehide', flushStorageNow)
}

export function addInteractionLog(entry: Omit<InteractionLogEntry, 'id' | 'timestamp'> & {
  id?: string
  timestamp?: number
}) {
  entryBuffer.unshift({
    ...entry,
    id: entry.id ?? createId(),
    timestamp: entry.timestamp ?? Date.now(),
    payload: sanitize(entry.payload)
  })
  if (entryBuffer.length > MAX_ENTRIES) entryBuffer.length = MAX_ENTRIES
  scheduleFlushes()
}

export function clearInteractionLogs() {
  entryBuffer = []
  if (viewFlushTimer !== undefined) clearTimeout(viewFlushTimer)
  if (storageFlushTimer !== undefined) clearTimeout(storageFlushTimer)
  viewFlushTimer = undefined
  storageFlushTimer = undefined
  entries.value = []
  storagePersistenceDisabled = false
  try {
    sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    storagePersistenceDisabled = true
  }
}

export function useInteractionLogs() {
  return readonly(entries)
}
