import { WhepPlayer, isWhepConnectivityError, type WhepState, type WhepStats } from './whep'

export type VideoLens = 'normal' | 'wide' | 'zoom' | 'ir'
export type VideoQuality = 2 | 3
export interface VideoSource {
  key: string // device/payload, not a lens or the currently viewed gateway
  deviceSn: string
  cameraIndex: string
  label: string
  lenses: VideoLens[]
}
export interface VideoSessionView {
  source: VideoSource
  state: WhepState | 'idle'
  playing: boolean
  error: string
  stats: WhepStats
  lens: VideoLens
  quality: VideoQuality
  settingsPending: boolean
  retrying: boolean
  reused: boolean
}
interface Session extends VideoSessionView {
  player: WhepPlayer
  video?: HTMLVideoElement
  generation: number
  task?: Promise<void>
  settingsTask?: Promise<void>
  appliedLens?: VideoLens
  appliedQuality?: VideoQuality
  stopped: boolean
  lastFrameAt: number
  lastAttemptAt: number
  framesDecoded?: number
}
interface VideoSessionOptions {
  start: (source: VideoSource, lens: VideoLens, quality: VideoQuality) => Promise<{ url: string; reused?: boolean }>
  stop: (source: VideoSource, explicit: boolean) => Promise<boolean>
  owns: (source: VideoSource) => boolean
  setLens: (source: VideoSource, lens: VideoLens) => Promise<unknown>
  setQuality: (source: VideoSource, quality: VideoQuality) => Promise<unknown>
  uncertainStart: (reason: unknown) => boolean
  changed: (sessions: VideoSessionView[]) => void
  createPlayer?: () => WhepPlayer
}
const emptyStats = (): WhepStats => ({ bitrateKbps: 0, width: 0, height: 0 })
const delay = (ms: number) => new Promise<void>(resolve => window.setTimeout(resolve, ms))

// This pool deliberately knows nothing about flight authority, DRC or mission
// state. Changing the view never changes a publisher or its control session.
export class VideoSessions {
  private sessions = new Map<string, Session>()
  private preferences = new Map<string, { lens: VideoLens; quality: VideoQuality }>()
  private recentlyViewed: string[] = []
  private selectedKey = ''
  private closed = false
  private warmupTimer = 0

  constructor(private options: VideoSessionOptions) {}

  select(sources: VideoSource[], selectedKey: string) {
    if (this.closed) return
    const available = new Map(sources.map(source => [source.key, source]))
    if (available.has(selectedKey) && selectedKey !== this.selectedKey) {
      this.recentlyViewed = [selectedKey, ...this.recentlyViewed.filter(key => key !== selectedKey)].slice(0, 64)
    }
    this.selectedKey = selectedKey
    // Keep the current view and the most recently viewed online publisher.
    // Background warmup and topology refreshes must not change view history.
    // Before a second device is viewed, reuse an existing/available reader.
    const keys = [...new Set([selectedKey, ...this.recentlyViewed, ...this.sessions.keys(), ...available.keys()])]
      .filter(key => available.has(key)).slice(0, 2)
    for (const [key, session] of this.sessions) {
      if (!keys.includes(key)) {
        this.sessions.delete(key)
        this.invalidate(session)
        void session.player.stop()
        void this.options.stop(session.source, false)
      }
    }
    for (const key of keys) {
      const source = available.get(key)!
      const existing = this.sessions.get(key)
      if (existing) {
        existing.source = source
        continue
      }
      const preference = this.preferences.get(key) ?? {
        lens: source.lenses.includes('zoom') ? 'zoom' : source.lenses[0] ?? 'normal',
        quality: 2
      }
      this.preferences.set(key, preference)
      this.sessions.set(key, {
        source, ...preference, state: 'idle', playing: false, error: '', stats: emptyStats(),
        settingsPending: false, retrying: false, reused: false, stopped: false,
        player: this.options.createPlayer?.() ?? new WhepPlayer(), generation: 0,
        lastFrameAt: 0, lastAttemptAt: 0
      })
    }
    const selected = this.sessions.get(selectedKey)
    if (selected?.playing && Date.now() - selected.lastFrameAt > 3_000) {
      selected.playing = false
      void this.retry(selectedKey)
    }
    this.emit()
    this.startAttached()
  }

  attach(key: string, video: HTMLVideoElement | null) {
    const session = this.sessions.get(key)
    if (!session || !video || session.video === video || this.closed) return
    session.video = video
    this.startAttached()
  }

  private startAttached() {
    const selected = this.sessions.get(this.selectedKey)
    if (selected && !selected.stopped && selected.state === 'idle') void this.start(selected.source.key)
    // Give the foreground offer a head start. This timer is independent of
    // selection so rapid A/B clicks cannot postpone warming indefinitely.
    if (this.warmupTimer || this.closed) return
    this.warmupTimer = window.setTimeout(() => {
      this.warmupTimer = 0
      for (const session of this.sessions.values()) {
        if (!session.stopped && session.state === 'idle') void this.start(session.source.key)
      }
    }, 250)
  }

  start(key: string): Promise<void> {
    const session = this.sessions.get(key)
    if (!session || !session.video || this.closed) return Promise.resolve()
    if (session.task) return session.task
    if (session.playing) return Promise.resolve()
    session.stopped = false
    session.lastAttemptAt = Date.now()
    const generation = ++session.generation
    const task = this.play(session, generation).finally(() => {
      if (session.task === task) {
        session.task = undefined
        session.retrying = false
        this.emit()
      }
    })
    session.task = task
    return task
  }

  private current(session: Session, generation: number) {
    return !this.closed && this.sessions.get(session.source.key) === session &&
      session.generation === generation && !session.stopped
  }

  private playback(session: Session, generation: number, endpoint: string, timeoutMs: number, publisherWaitMs = 0) {
    return session.player.play(session.video!, endpoint, {
      timeoutMs, publisherWaitMs,
      onState: state => {
        if (!this.current(session, generation)) return
        const wasPlaying = session.playing
        session.state = state
        if (state === 'failed' || state === 'disconnected') {
          session.playing = false
          if (wasPlaying) session.lastAttemptAt = 0
        }
        this.emit()
      },
      onStats: stats => {
        if (!this.current(session, generation)) return
        session.stats = stats
        if (stats.framesDecoded !== undefined
          ? stats.framesDecoded !== session.framesDecoded
          : stats.bitrateKbps > 0) session.lastFrameAt = Date.now()
        session.framesDecoded = stats.framesDecoded
        this.emit()
      }
    })
  }

  private async play(session: Session, generation: number, attempt = 0): Promise<void> {
    if (!this.current(session, generation)) return
    session.state = 'connecting'
    session.playing = false
    session.error = ''
    session.stats = emptyStats()
    this.emit()
    const source = session.source
    let explicitStartFailure = false
    try {
      let reused = false
      try {
        await this.playback(session, generation, `/webrtc/${source.deviceSn}-${source.cameraIndex}/whep`, 10_000)
        reused = true
      } catch (reason) {
        if (!this.current(session, generation)) return
        // A missing reader path and an unreachable browser media connection
        // are different failures. Never restart an aircraft for an ICE failure.
        if (isWhepConnectivityError(reason)) throw reason
      }
      if (!this.current(session, generation)) return
      session.reused = reused
      if (!reused) {
        let response: { url: string; reused?: boolean }
        const requestedLens = session.lens
        const requestedQuality = session.quality
        try {
          response = await this.options.start(source, requestedLens, requestedQuality)
        } catch (reason) {
          explicitStartFailure = !this.options.uncertainStart(reason)
          throw reason
        }
        if (!this.current(session, generation)) return
        session.reused = response.reused === true
        // Reused starts did not apply the requested encoder settings.
        if (!response.reused) {
          session.appliedLens = requestedLens
          session.appliedQuality = requestedQuality
        }
        if (!response.url || response.url.toLowerCase().startsWith('rtsp://')) throw new Error('WHEP 播放地址未配置')
        const playback = this.playback(session, generation, response.url, 18_000, 6_000)
          .then(() => undefined, reason => reason as unknown)
        const initial = await Promise.race([playback, delay(4_000).then(() => 'pending' as const)])
        if (!this.current(session, generation)) return
        let recovered = false
        if (initial === 'pending' && this.waitingForFrame(session)) {
          recovered = await this.recoverEncoder(session, generation)
        }
        const failure = await playback
        if (!this.current(session, generation)) return
        if (failure) {
          if (!recovered || isWhepConnectivityError(failure)) throw failure
          await this.playback(session, generation, response.url, 10_000, 6_000)
        }
      }
      if (!this.current(session, generation)) return
      // Only a changed/unknown lens gates presentation. Quality adjustment is
      // independent of first-frame display and never repeats on a warm switch.
      await this.applyLens(session, generation)
      if (!this.current(session, generation)) return
      session.playing = true
      session.state = 'playing'
      session.lastFrameAt = Date.now()
      this.emit()
      void this.applyQuality(session, generation)
    } catch (reason) {
      if (!this.current(session, generation)) return
      session.playing = false
      const message = reason instanceof Error ? reason.message : '视频启动失败'
      await session.player.stop()
      if (!this.current(session, generation)) return
      if (!isWhepConnectivityError(reason) && attempt === 1 && this.options.owns(source)) {
        await this.options.stop(source, false)
        session.appliedLens = undefined
        session.appliedQuality = undefined
      }
      if (!this.current(session, generation)) return
      if (!explicitStartFailure && attempt < 2) {
        session.error = `${message}，正在自动重试…`
        this.emit()
        await delay(1_500 * (attempt + 1))
        return this.play(session, generation, attempt + 1)
      }
      session.error = message
      session.state = 'failed'
      this.emit()
    }
  }

  private async applyLens(session: Session, generation: number) {
    if (session.appliedLens === session.lens) return
    const lens = session.lens
    await this.options.setLens(session.source, lens)
    if (!this.current(session, generation)) return
    session.appliedLens = lens
    // Discard the pre-change decoded frame before showing a newly selected lens.
    await session.player.waitForFrame()
  }

  private waitingForFrame(session: Session) {
    return session.state === 'waiting'
  }

  private async applyQuality(session: Session, generation: number) {
    if (!this.current(session, generation) || session.appliedQuality === session.quality) return
    if (session.settingsTask) return session.settingsTask
    session.settingsPending = true
    this.emit()
    const quality = session.quality
    const task = this.options.setQuality(session.source, quality).then(() => {
      if (this.current(session, generation)) session.appliedQuality = quality
    }).catch(reason => {
      if (this.current(session, generation)) session.error = reason instanceof Error ? reason.message : '清晰度应用失败'
    }).finally(() => {
      if (session.settingsTask === task) {
        session.settingsTask = undefined
        session.settingsPending = false
        this.emit()
      }
    })
    session.settingsTask = task
    return task
  }

  private async recoverEncoder(session: Session, generation: number) {
    const desired = session.lens
    const alternate = session.source.lenses.find(lens => lens !== desired)
    if (!alternate) return false
    try {
      await this.options.setLens(session.source, alternate)
      await delay(1_800)
      if (!this.current(session, generation)) return false
      await this.options.setLens(session.source, desired)
      session.appliedLens = desired
      await delay(1_200)
      return true
    } catch {
      if (this.current(session, generation)) {
        await this.options.setLens(session.source, desired).catch(() => undefined)
        session.appliedLens = undefined
      }
      return false
    }
  }

  async setLens(key: string, lens: VideoLens) {
    const session = this.sessions.get(key)
    if (!session || session.settingsPending || session.task || session.lens === lens) return
    const previous = session.lens
    session.lens = lens
    this.remember(session)
    if (!session.playing) return this.emit()
    const generation = session.generation
    session.settingsPending = true
    session.playing = false
    this.emit()
    try {
      await this.applyLens(session, generation)
    } catch (reason) {
      if (this.current(session, generation)) {
        session.lens = previous
        this.remember(session)
        session.error = reason instanceof Error ? reason.message : '镜头切换失败'
      }
    } finally {
      if (this.current(session, generation)) {
        session.settingsPending = false
        session.playing = session.state === 'playing' && session.appliedLens === session.lens
        this.emit()
      }
    }
  }

  async setQuality(key: string, quality: VideoQuality) {
    const session = this.sessions.get(key)
    if (!session || session.settingsPending || session.quality === quality) return
    const previous = session.quality
    const generation = session.generation
    session.quality = quality
    this.remember(session)
    this.emit()
    if (!session.playing) return
    await this.applyQuality(session, generation)
    if (this.current(session, generation) && session.appliedQuality !== quality) {
      session.quality = previous
      this.remember(session)
      this.emit()
    }
  }

  private remember(session: Session) {
    this.preferences.set(session.source.key, { lens: session.lens, quality: session.quality })
  }

  async retry(key: string, manual = true) {
    const session = this.sessions.get(key)
    if (!session || session.retrying || this.closed || (session.stopped && !manual)) return
    this.invalidate(session)
    const generation = session.generation
    session.retrying = true
    session.stopped = false
    this.emit()
    await session.player.stop()
    if (!this.current(session, generation)) return
    return this.start(key)
  }

  async stop(key: string) {
    const session = this.sessions.get(key)
    if (!session) return
    const starting = session.task
    this.invalidate(session)
    session.stopped = true
    session.appliedLens = undefined
    session.appliedQuality = undefined
    this.emit()
    const cleanup = (async () => {
      await session.player.stop()
      if (!await this.options.stop(session.source, true)) {
        session.error = '停止设备推流未确认，请稍后重试'
        this.emit()
      }
      // The owner adapter stops late accepted starts. Prevent a new start for
      // this same entry from racing ahead of that cleanup.
      await starting
    })().finally(() => {
      if (session.task === cleanup) session.task = undefined
    })
    session.task = cleanup
    await cleanup
  }

  tick() {
    if (this.closed) return
    for (const session of this.sessions.values()) {
      if (session.stopped || session.task || session.settingsPending) continue
      const now = Date.now()
      if (session.playing && now - session.lastFrameAt > 7_000) {
        session.playing = false
        session.state = 'disconnected'
        this.emit()
      }
      if (!session.playing && now - session.lastAttemptAt >= 15_000) void this.retry(session.source.key, false)
    }
  }

  private invalidate(session: Session) {
    ++session.generation
    session.task = undefined
    session.playing = false
    session.state = 'idle'
    session.retrying = false
    session.settingsTask = undefined
    session.settingsPending = false
    session.stats = emptyStats()
  }

  async close() {
    this.closed = true
    this.recentlyViewed = []
    window.clearTimeout(this.warmupTimer)
    const sessions = [...this.sessions.values()]
    this.sessions.clear()
    for (const session of sessions) this.invalidate(session)
    this.emit()
    await Promise.all(sessions.map(session => session.player.stop()))
  }

  private emit() {
    this.options.changed([...this.sessions.values()].map(session => ({
      source: session.source, state: session.state, playing: session.playing,
      error: session.error, stats: session.stats, lens: session.lens, quality: session.quality,
      settingsPending: session.settingsPending, retrying: session.retrying, reused: session.reused
    })))
  }
}
