export type WhepState = 'connecting' | 'waiting' | 'playing' | 'disconnected' | 'failed'

export interface WhepStats {
  bitrateKbps: number
  width: number
  height: number
  framesDecoded?: number
}

export interface WhepOptions {
  timeoutMs?: number
  // Allow a just-started publisher to finish RTSP setup before WHEP is ready.
  publisherWaitMs?: number
  onState?: (state: WhepState) => void
  onStats?: (stats: WhepStats) => void
}

export type WhepErrorCode = 'cancelled' | 'http' | 'connectivity' | 'first-frame'

export class WhepError extends Error {
  constructor(
    message: string,
    readonly code: WhepErrorCode,
    readonly status?: number
  ) {
    super(message)
    this.name = 'WhepError'
  }
}

export function isWhepConnectivityError(reason: unknown): reason is WhepError {
  return reason instanceof WhepError && reason.code === 'connectivity'
}

interface WhepSession {
  generation: number
  peer: RTCPeerConnection
  video: HTMLVideoElement
  stream?: MediaStream
  resourceUrl?: string
  request: AbortController
}

export class WhepPlayer {
  private session?: WhepSession
  private statsTimer = 0
  private generation = 0

  async play(video: HTMLVideoElement, endpoint: string, options: WhepOptions = {}) {
    // Invalidate the previous attempt before awaiting its network cleanup. This
    // prevents two concurrent play() calls from both becoming the active owner.
    const generation = ++this.generation
    window.clearInterval(this.statsTimer)
    this.statsTimer = 0
    await this.closeActiveSession()
    if (generation !== this.generation) throw new WhepError('直播连接已取消', 'cancelled')

    const timeoutMs = options.timeoutMs ?? 16_000
    const deadline = Date.now() + timeoutMs
    const publisherDeadline = Math.min(deadline, Date.now() + (options.publisherWaitMs ?? 0))
    let lastError: unknown

    while (generation === this.generation && Date.now() < deadline) {
      let session: WhepSession | undefined
      try {
        options.onState?.('connecting')
        session = await this.open(video, endpoint, generation, options, Math.max(1, deadline - Date.now()))
        if (!this.isCurrent(session)) throw new WhepError('直播连接已取消', 'cancelled')
        await waitForPeerConnection(session.peer, Math.max(1_000, deadline - Date.now()))
        if (!this.isCurrent(session)) throw new WhepError('直播连接已取消', 'cancelled')
        options.onState?.('waiting')
        await waitForFirstFrame(video, Math.max(1_000, deadline - Date.now()))
        if (!this.isCurrent(session)) throw new WhepError('直播连接已取消', 'cancelled')
        options.onState?.('playing')
        this.startStats(session, options)
        return
      } catch (reason) {
        lastError = reason
        if (session) await this.closeSession(session)
        if (generation !== this.generation || Date.now() >= deadline) break
        // The initial publisher probe fails immediately. After an acknowledged
        // start, a short 404 window is normal while RTSP ANNOUNCE/RECORD finishes.
        // Never retry authorization or other HTTP errors as publisher startup.
        if (reason instanceof WhepError && reason.code === 'http' &&
            (reason.status !== 404 || Date.now() + 600 >= publisherDeadline)) break
        await delay(600)
      }
    }

    if (generation === this.generation) options.onState?.('failed')
    throw lastError instanceof Error ? lastError : new Error('等待实时视频帧超时')
  }

  private async open(
    video: HTMLVideoElement,
    endpoint: string,
    generation: number,
    options: WhepOptions,
    timeoutMs: number
  ) {
    video.autoplay = true
    video.muted = true
    video.playsInline = true
    const peer = new RTCPeerConnection()
    const session: WhepSession = { generation, peer, video, request: new AbortController() }
    const requestTimeout = window.setTimeout(() => session.request.abort(), timeoutMs)
    this.session = session
    peer.addTransceiver('video', { direction: 'recvonly' })
    peer.ontrack = (event) => {
      if (!this.isCurrent(session) || event.track.kind !== 'video') return
      session.stream = event.streams[0] ?? new MediaStream([event.track])
      video.srcObject = session.stream
      // A failed/empty publisher can make MediaMTX create and close several
      // short WHEP sessions. Closing srcObject interrupts play(); the first-frame
      // timeout below owns that failure, so do not leak AbortError to the console.
      void video.play().catch(() => undefined)
      event.track.addEventListener('unmute', () => {
        if (this.isCurrent(session)) void video.play().catch(() => undefined)
      }, { once: true })
    }
    peer.onconnectionstatechange = () => {
      if (!this.isCurrent(session)) return
      if (peer.connectionState === 'failed') options.onState?.('failed')
      if (peer.connectionState === 'disconnected') options.onState?.('disconnected')
    }

    try {
      const offer = await peer.createOffer()
      await peer.setLocalDescription(offer)
      await waitForIce(peer)
      if (!this.isCurrent(session)) throw new WhepError('直播连接已取消', 'cancelled')
      const response = await fetch(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/sdp' },
        body: peer.localDescription?.sdp,
        signal: session.request.signal
      })
      if (!response.ok) throw new WhepError(`WHEP 建链失败：HTTP ${response.status}`, 'http', response.status)

      const location = response.headers.get('Location')
      if (location) {
        session.resourceUrl = new URL(location, new URL(endpoint, window.location.href)).toString()
      }
      if (!this.isCurrent(session)) throw new WhepError('直播连接已取消', 'cancelled')
      await peer.setRemoteDescription({ type: 'answer', sdp: await response.text() })
      return session
    } catch (reason) {
      await this.closeSession(session)
      throw reason
    } finally {
      window.clearTimeout(requestTimeout)
    }
  }

  private startStats(session: WhepSession, options: WhepOptions) {
    window.clearInterval(this.statsTimer)
    let previousBytes = 0
    let previousStatsAt = 0
    this.statsTimer = window.setInterval(async () => {
      if (!this.isCurrent(session)) return
      const reports = await session.peer.getStats().catch(() => undefined)
      if (!reports || !this.isCurrent(session)) return
      reports.forEach((report) => {
        if (report.type !== 'inbound-rtp' || report.kind !== 'video') return
        const now = Date.now()
        const bytes = Number(report.bytesReceived ?? 0)
        const elapsed = (now - previousStatsAt) / 1000
        const bitrateKbps = previousStatsAt > 0 && elapsed > 0
          ? Math.max(0, Math.round((bytes - previousBytes) * 8 / elapsed / 1000))
          : 0
        previousBytes = bytes
        previousStatsAt = now
        options.onStats?.({
          bitrateKbps,
          width: session.video.videoWidth,
          height: session.video.videoHeight,
          framesDecoded: report.framesDecoded
        })
      })
    }, 1_000)
  }

  private isCurrent(session: WhepSession) {
    return session.generation === this.generation && this.session === session
  }

  private async closeActiveSession() {
    const session = this.session
    if (session) await this.closeSession(session)
  }

  private async closeSession(session: WhepSession) {
    if (this.session === session) this.session = undefined
    if (session.video.srcObject === session.stream) session.video.srcObject = null
    session.peer.close()
    session.request.abort()
    const resource = session.resourceUrl
    session.resourceUrl = undefined
    if (resource) {
      const cleanup = new AbortController()
      const timeout = window.setTimeout(() => cleanup.abort(), 1500)
      try {
        await fetch(resource, { method: 'DELETE', signal: cleanup.signal }).catch(() => undefined)
      } finally {
        window.clearTimeout(timeout)
      }
    }
  }

  async stop() {
    ++this.generation
    window.clearInterval(this.statsTimer)
    this.statsTimer = 0
    await this.closeActiveSession()
  }

  async waitForFrame(timeoutMs = 3_000) {
    const session = this.session
    if (!session) throw new WhepError('直播连接已取消', 'cancelled')
    await new Promise<void>((resolve, reject) => {
      const video = session.video
      const initialTime = video.currentTime
      let callback: number | undefined
      let settled = false
      const timeout = window.setTimeout(() => finish(false), timeoutMs)
      const poll = window.setInterval(() => {
        if (video.currentTime !== initialTime && video.readyState >= 2) finish(true)
      }, 100)
      const cancelled = () => finish(false)
      function finish(success: boolean) {
        if (settled) return
        settled = true
        window.clearTimeout(timeout)
        window.clearInterval(poll)
        session!.request.signal.removeEventListener('abort', cancelled)
        if (callback !== undefined) video.cancelVideoFrameCallback(callback)
        if (success) resolve()
        else reject(new WhepError('等待新镜头视频帧超时或连接已取消', 'first-frame'))
      }
      session.request.signal.addEventListener('abort', cancelled, { once: true })
      if (session.request.signal.aborted) return finish(false)
      if (typeof video.requestVideoFrameCallback === 'function') {
        callback = video.requestVideoFrameCallback(() => finish(true))
      }
    })
    if (!this.isCurrent(session)) throw new WhepError('直播连接已取消', 'cancelled')
  }
}

function waitForFirstFrame(video: HTMLVideoElement, timeoutMs: number) {
  return new Promise<void>((resolve, reject) => {
    let settled = false
    let frameCallbackId: number | undefined
    const timeout = window.setTimeout(() => finish(false), timeoutMs)
    const poll = window.setInterval(check, 100)
    const events: Array<keyof HTMLMediaElementEventMap> = [
      'loadeddata', 'canplay', 'playing', 'timeupdate', 'resize'
    ]
    const onMediaProgress = () => check()
    function hasDecodedFrame() {
      return video.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA &&
        video.videoWidth > 0 && video.videoHeight > 0
    }
    function check() {
      if (hasDecodedFrame()) finish(true)
    }
    function finish(success: boolean) {
      if (settled) return
      settled = true
      window.clearTimeout(timeout)
      window.clearInterval(poll)
      for (const event of events) video.removeEventListener(event, onMediaProgress)
      if (frameCallbackId !== undefined && typeof video.cancelVideoFrameCallback === 'function') {
        video.cancelVideoFrameCallback(frameCallbackId)
      }
      if (success) resolve()
      else reject(new WhepError('已建立 WebRTC 连接，但未收到可解码的视频首帧', 'first-frame'))
    }
    for (const event of events) video.addEventListener(event, onMediaProgress)
    if (typeof video.requestVideoFrameCallback === 'function') {
      frameCallbackId = video.requestVideoFrameCallback(() => finish(true))
    }
    void video.play().catch(() => undefined)
    check()
  })
}

function waitForPeerConnection(peer: RTCPeerConnection, timeoutMs: number) {
  return new Promise<void>((resolve, reject) => {
    let settled = false
    const timeout = window.setTimeout(() => finish(false), timeoutMs)
    function connected() {
      return peer.connectionState === 'connected' ||
        peer.iceConnectionState === 'connected' ||
        peer.iceConnectionState === 'completed'
    }
    function failed() {
      return peer.connectionState === 'failed' || peer.connectionState === 'closed' ||
        peer.iceConnectionState === 'failed' || peer.iceConnectionState === 'closed'
    }
    function check() {
      if (connected()) finish(true)
      else if (failed()) finish(false)
    }
    function finish(success: boolean) {
      if (settled) return
      settled = true
      window.clearTimeout(timeout)
      peer.removeEventListener('connectionstatechange', check)
      peer.removeEventListener('iceconnectionstatechange', check)
      if (success) resolve()
      else reject(new WhepError(
        'WebRTC 媒体通道未建立，请检查服务器 TCP/UDP 8000 端口和网络可达性',
        'connectivity'))
    }
    peer.addEventListener('connectionstatechange', check)
    peer.addEventListener('iceconnectionstatechange', check)
    check()
  })
}

function waitForIce(peer: RTCPeerConnection) {
  if (peer.iceGatheringState === 'complete') return Promise.resolve()
  return new Promise<void>((resolve) => {
    const timeout = window.setTimeout(done, 2_500)
    function done() {
      window.clearTimeout(timeout)
      peer.removeEventListener('icegatheringstatechange', change)
      resolve()
    }
    function change() {
      if (peer.iceGatheringState === 'complete') done()
    }
    peer.addEventListener('icegatheringstatechange', change)
  })
}

function delay(ms: number) {
  return new Promise((resolve) => window.setTimeout(resolve, ms))
}
