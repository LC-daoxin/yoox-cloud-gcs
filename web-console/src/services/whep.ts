export type WhepState = 'connecting' | 'waiting' | 'playing' | 'disconnected' | 'failed'

export interface WhepStats {
  bitrateKbps: number
  width: number
  height: number
}

export interface WhepOptions {
  timeoutMs?: number
  onState?: (state: WhepState) => void
  onStats?: (stats: WhepStats) => void
}

interface WhepSession {
  generation: number
  peer: RTCPeerConnection
  video: HTMLVideoElement
  stream?: MediaStream
  resourceUrl?: string
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
    if (generation !== this.generation) throw new Error('直播连接已取消')

    const timeoutMs = options.timeoutMs ?? 16_000
    const deadline = Date.now() + timeoutMs
    let lastError: unknown

    while (generation === this.generation && Date.now() < deadline) {
      let session: WhepSession | undefined
      try {
        options.onState?.('connecting')
        session = await this.open(video, endpoint, generation, options)
        if (!this.isCurrent(session)) throw new Error('直播连接已取消')
        options.onState?.('waiting')
        await waitForFirstFrame(video, Math.max(1_000, deadline - Date.now()))
        if (!this.isCurrent(session)) throw new Error('直播连接已取消')
        options.onState?.('playing')
        this.startStats(session, options)
        return
      } catch (reason) {
        lastError = reason
        if (session) await this.closeSession(session)
        if (generation !== this.generation || Date.now() >= deadline) break
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
    options: WhepOptions
  ) {
    const peer = new RTCPeerConnection()
    const session: WhepSession = { generation, peer, video }
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
      if (!this.isCurrent(session)) throw new Error('直播连接已取消')
      const response = await fetch(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/sdp' },
        body: peer.localDescription?.sdp
      })
      if (!response.ok) throw new Error(`WHEP 建链失败：HTTP ${response.status}`)

      const location = response.headers.get('Location')
      if (location) {
        session.resourceUrl = new URL(location, new URL(endpoint, window.location.href)).toString()
      }
      if (!this.isCurrent(session)) throw new Error('直播连接已取消')
      await peer.setRemoteDescription({ type: 'answer', sdp: await response.text() })
      return session
    } catch (reason) {
      await this.closeSession(session)
      throw reason
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
          height: session.video.videoHeight
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
    const resource = session.resourceUrl
    session.resourceUrl = undefined
    if (resource) await fetch(resource, { method: 'DELETE' }).catch(() => undefined)
  }

  async stop() {
    ++this.generation
    window.clearInterval(this.statsTimer)
    this.statsTimer = 0
    await this.closeActiveSession()
  }
}

function waitForFirstFrame(video: HTMLVideoElement, timeoutMs: number) {
  return new Promise<void>((resolve, reject) => {
    let settled = false
    const timeout = window.setTimeout(() => finish(false), timeoutMs)
    const onPlaying = () => {
      if (video.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA) finish(true)
    }
    function finish(success: boolean) {
      if (settled) return
      settled = true
      window.clearTimeout(timeout)
      video.removeEventListener('playing', onPlaying)
      if (success) resolve()
      else reject(new Error('已下发直播，但未收到可解码的视频首帧'))
    }
    video.addEventListener('playing', onPlaying)
    if (typeof video.requestVideoFrameCallback === 'function') {
      video.requestVideoFrameCallback(() => finish(true))
    } else if (video.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA) {
      finish(true)
    }
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
