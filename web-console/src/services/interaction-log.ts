import { readonly, ref } from 'vue'

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

const entries = ref<InteractionLogEntry[]>(loadEntries())
let sequence = 0

function createId() {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  sequence += 1
  return `${Date.now().toString(36)}-${sequence.toString(36)}-${Math.random().toString(36).slice(2, 9)}`
}

function persist() {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(entries.value))
  } catch {
    // 浏览器存储不可用时仍保留当前页面内的日志。
  }
}

export function addInteractionLog(entry: Omit<InteractionLogEntry, 'id' | 'timestamp'> & {
  id?: string
  timestamp?: number
}) {
  entries.value.unshift({
    ...entry,
    id: entry.id ?? createId(),
    timestamp: entry.timestamp ?? Date.now(),
    payload: sanitize(entry.payload)
  })
  if (entries.value.length > MAX_ENTRIES) entries.value.length = MAX_ENTRIES
  persist()
}

export function clearInteractionLogs() {
  entries.value = []
  persist()
}

export function useInteractionLogs() {
  return readonly(entries)
}
