// 平台 WebSocket 客户端：订阅设备上下线、OSD 遥测、任务进度等实时推送。
// 后端端点：/api/v1/ws?x-auth-token=<token>，消息格式：{ biz_code, version, timestamp, data }
import { getToken } from './api'
import { addInteractionLog } from './interaction-log'

export interface WsMessage<T = any> {
  biz_code: string
  version?: string
  timestamp?: number
  data: T
}

type Handler = (msg: WsMessage) => void

function topicFromMessage(msg: WsMessage): string | undefined {
  const data = msg.data && typeof msg.data === 'object' ? msg.data as Record<string, any> : {}
  const sn = String(data.sn ?? data.host?.sn ?? '')
  if (msg.biz_code === 'payload_authority_grab') {
    const gatewaySn = String(data.gateway_sn ?? data.gatewaySn ?? sn)
    return gatewaySn ? `thing/product/${gatewaySn}/services_reply` : undefined
  }
  if (msg.biz_code === 'target_detect_result_report') {
    const gatewaySn = String(data.gateway_sn ?? data.gatewaySn ?? sn)
    return gatewaySn ? `thing/product/${gatewaySn}/state` : undefined
  }
  if (msg.biz_code === 'drc_hsi_info_push') {
    return sn ? `thing/product/${sn}/drc/up` : undefined
  }
  if (!sn) return undefined
  if (['gateway_osd', 'dock_osd', 'device_osd'].includes(msg.biz_code)) {
    return `thing/product/${sn}/osd`
  }
  if (['live_capacity', 'live_status', 'control_source_change', 'device_update_topo'].includes(msg.biz_code)) {
    return `thing/product/${sn}/state`
  }
  if (msg.biz_code.endsWith('_progress') || msg.biz_code === 'device_hms' ||
      msg.biz_code === 'joystick_invalid_notify' || msg.biz_code === 'drc_status_notify') {
    return `thing/product/${sn}/events`
  }
  return undefined
}

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
    this.socket.onopen = () => {
      this.connected = true
      addInteractionLog({
        transport: 'WebSocket',
        direction: 'INFO',
        path: '/api/v1/ws',
        summary: '云端 WebSocket 已连接'
      })
    }
    this.socket.onmessage = (event) => {
      let msg: WsMessage
      try {
        msg = JSON.parse(typeof event.data === 'string' ? event.data : '')
      } catch {
        return
      }
      if (!msg || !msg.biz_code) return
      if (msg.biz_code === 'interaction_log') {
        const log = msg.data as Record<string, unknown>
        addInteractionLog({
          transport: log.transport === 'MQTT' ? 'MQTT' : 'WebSocket',
          direction: log.direction === 'OUT' ? 'OUT' : 'IN',
          topic: typeof log.topic === 'string' ? log.topic : undefined,
          summary: typeof log.summary === 'string' ? log.summary : msg.biz_code,
          payload: log.payload,
          timestamp: msg.timestamp
        })
      } else {
        addInteractionLog({
          transport: 'WebSocket',
          direction: 'IN',
          topic: topicFromMessage(msg),
          summary: msg.biz_code,
          payload: msg,
          timestamp: msg.timestamp
        })
      }
      this.handlers.forEach((handler) => {
        try { handler(msg) } catch { /* 单个订阅异常不影响其它订阅 */ }
      })
    }
    this.socket.onclose = () => {
      this.connected = false
      this.socket = undefined
      addInteractionLog({
        transport: 'WebSocket',
        direction: this.manualClose ? 'INFO' : 'ERROR',
        path: '/api/v1/ws',
        summary: this.manualClose ? '云端 WebSocket 已关闭' : '云端 WebSocket 断开，等待重连'
      })
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
