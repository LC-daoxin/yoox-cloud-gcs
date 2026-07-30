import type { ApiEnvelope } from '../types'

const TOKEN_KEY = 'yoox_access_token'

export class ApiError extends Error {
  constructor(message: string, public readonly code = -1, public readonly status = 0) {
    super(message)
  }
}

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) ?? ''
}

export function setToken(token: string) {
  if (token) localStorage.setItem(TOKEN_KEY, token)
  else localStorage.removeItem(TOKEN_KEY)
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  const token = getToken()
  if (token) headers.set('x-auth-token', token)

  const response = await fetch(path, { ...init, headers })
  if (response.status === 401) {
    setToken('')
    window.dispatchEvent(new Event('yoox:unauthorized'))
    throw new ApiError('登录已过期，请重新登录', 401, response.status)
  }

  const payload = await response.json().catch(() => null) as ApiEnvelope<T> | null
  if (!response.ok) throw new ApiError(payload?.message || `HTTP ${response.status}`, payload?.code, response.status)
  if (!payload) throw new ApiError('服务返回了空响应')
  if (payload.code !== 0) throw new ApiError(payload.message || '请求失败', payload.code, response.status)
  return payload.data
}

export const get = <T>(path: string) => api<T>(path)
export const post = <T>(path: string, body?: unknown) =>
  api<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) })
export const put = <T>(path: string, body?: unknown) =>
  api<T>(path, { method: 'PUT', body: body === undefined ? undefined : JSON.stringify(body) })
export const del = <T>(path: string, body?: unknown) =>
  api<T>(path, { method: 'DELETE', body: body === undefined ? undefined : JSON.stringify(body) })

export function listFrom<T>(value: unknown): T[] {
  if (Array.isArray(value)) return value as T[]
  if (value && typeof value === 'object') {
    const object = value as Record<string, unknown>
    for (const key of ['list', 'records', 'data']) {
      if (Array.isArray(object[key])) return object[key] as T[]
    }
  }
  return []
}
