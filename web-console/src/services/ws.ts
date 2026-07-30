// 平台 WebSocket 客户端：订阅设备上下线、OSD 遥测、任务进度等实时推送。
// 后端端点：/api/v1/ws?x-auth-token=<token>，消息格式：{ biz_code, version, timestamp, data }
import { getToken } from './api'

export interface WsMessage<T = any> {
  biz_code: string
  version?: string
  timestamp?: number
  data: T
}

type Handler = (msg: WsMessage) => void

class DeviceWebSocket {
  private socket?: WebSocket
  private handlers = new Set<Handler>()
  private reconnectTimer = 0
  private manualClose = false
  private connected = false

  connect() {
    const token = getToken()
    if (!token || this.socket) return
    this.manualClose = false
    const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const url = `${scheme}://${window.location.host}/api/v1/ws?x-auth-token=${encodeURIComponent(token)}`
    try {
      this.socket = new WebSocket(url)
    } catch {
      this.scheduleReconnect()
      return
    }
    this.socket.onopen = () => { this.connected = true }
    this.socket.onmessage = (event) => {
      let msg: WsMessage
      try {
        msg = JSON.parse(typeof event.data === 'string' ? event.data : '')
      } catch {
        return
      }
      if (!msg || !msg.biz_code) return
      this.handlers.forEach((handler) => {
        try { handler(msg) } catch { /* 单个订阅异常不影响其它订阅 */ }
      })
    }
    this.socket.onclose = () => {
      this.connected = false
      this.socket = undefined
      if (!this.manualClose) this.scheduleReconnect()
    }
    this.socket.onerror = () => { this.socket?.close() }
  }

  private scheduleReconnect() {
    window.clearTimeout(this.reconnectTimer)
    this.reconnectTimer = window.setTimeout(() => this.connect(), 3000)
  }

  isConnected() { return this.connected }

  /** 订阅所有消息，返回取消订阅函数。 */
  subscribe(handler: Handler): () => void {
    this.handlers.add(handler)
    this.connect()
    return () => {
      this.handlers.delete(handler)
      if (this.handlers.size === 0) this.close()
    }
  }

  close() {
    this.manualClose = true
    window.clearTimeout(this.reconnectTimer)
    this.socket?.close()
    this.socket = undefined
    this.connected = false
  }
}

export const deviceWs = new DeviceWebSocket()
