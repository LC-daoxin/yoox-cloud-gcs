import type { ApiEnvelope } from '../types'
import { addInteractionLog } from './interaction-log'

const TOKEN_KEY = 'yoox_access_token'

export interface ApiRequestOptions {
  signal?: AbortSignal
  timeoutMs?: number
  authToken?: string
}

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

export async function api<T>(
  path: string,
  init: RequestInit = {},
  options: ApiRequestOptions = {}
): Promise<T> {
  const startedAt = performance.now()
  const method = init.method ?? 'GET'
  const headers = new Headers(init.headers)
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  const token = options.authToken ?? getToken()
  if (token) headers.set('x-auth-token', token)

  let requestPayload: unknown
  if (typeof init.body === 'string') {
    try { requestPayload = JSON.parse(init.body) } catch { requestPayload = init.body }
  }
  addInteractionLog({
    transport: 'HTTP',
    direction: 'OUT',
    method,
    path,
    summary: `${method} ${path}`,
    payload: requestPayload
  })

  const timeoutController = options.timeoutMs && options.timeoutMs > 0
    ? new AbortController()
    : undefined
  const callerSignal = options.signal ?? init.signal
  let timedOut = false
  let timeout = 0
  const abortFromCaller = () => timeoutController?.abort(callerSignal?.reason)
  const cleanupRequest = () => {
    if (timeout) window.clearTimeout(timeout)
    callerSignal?.removeEventListener('abort', abortFromCaller)
  }
  if (timeoutController) {
    if (callerSignal?.aborted) abortFromCaller()
    else callerSignal?.addEventListener('abort', abortFromCaller, { once: true })
    timeout = window.setTimeout(() => {
      timedOut = true
      timeoutController.abort(new DOMException('Request timed out', 'TimeoutError'))
    }, options.timeoutMs)
  }

  let response: Response
  try {
    response = await fetch(path, {
      ...init,
      headers,
      signal: timeoutController?.signal ?? callerSignal
    })
  } catch (reason) {
    const requestError = timedOut
      ? new ApiError(`请求超时（${options.timeoutMs} ms）`)
      : reason
    addInteractionLog({
      transport: 'HTTP',
      direction: 'ERROR',
      method,
      path,
      durationMs: Math.round(performance.now() - startedAt),
      summary: requestError instanceof Error ? requestError.message : '网络请求失败'
    })
    cleanupRequest()
    throw requestError
  }

  let payload: ApiEnvelope<T> | null
  try {
    payload = await response.json() as ApiEnvelope<T>
  } catch (reason) {
    if (timedOut) throw new ApiError(`请求超时（${options.timeoutMs} ms）`)
    payload = null
  } finally {
    cleanupRequest()
  }
  addInteractionLog({
    transport: 'HTTP',
    direction: response.ok && payload?.code === 0 ? 'IN' : 'ERROR',
    method,
    path,
    status: response.status,
    durationMs: Math.round(performance.now() - startedAt),
    summary: `${method} ${path} · HTTP ${response.status}`,
    payload
  })
  if (response.status === 401) {
    // A late cleanup request may intentionally use an older per-request token.
    // Its 401 must not log out a newer session that has since signed in.
    if (token === getToken()) {
      setToken('')
      window.dispatchEvent(new Event('yoox:unauthorized'))
    }
    throw new ApiError('登录已过期，请重新登录', 401, response.status)
  }

  if (!response.ok) throw new ApiError(payload?.message || `HTTP ${response.status}`, payload?.code, response.status)
  if (!payload) throw new ApiError('服务返回了空响应')
  if (payload.code !== 0) throw new ApiError(payload.message || '请求失败', payload.code, response.status)
  return payload.data
}

export const get = <T>(path: string, options?: ApiRequestOptions) => api<T>(path, {}, options)
export const post = <T>(path: string, body?: unknown, options?: ApiRequestOptions) =>
  api<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) }, options)
export const put = <T>(path: string, body?: unknown, options?: ApiRequestOptions) =>
  api<T>(path, { method: 'PUT', body: body === undefined ? undefined : JSON.stringify(body) }, options)
export const del = <T>(path: string, body?: unknown, options?: ApiRequestOptions) =>
  api<T>(path, { method: 'DELETE', body: body === undefined ? undefined : JSON.stringify(body) }, options)

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
