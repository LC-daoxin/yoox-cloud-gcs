import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { VideoSessions, type VideoSessionView, type VideoSource } from '../src/services/video-sessions'
import { WhepError, type WhepOptions, type WhepPlayer } from '../src/services/whep'

class Player {
  options?: WhepOptions
  play = vi.fn(async (_video: HTMLVideoElement, _url: string, options: WhepOptions) => {
    this.options = options
    options.onState?.('connecting')
    options.onState?.('playing')
  })
  waitForFrame = vi.fn(async () => undefined)
  stop = vi.fn(async () => undefined)
  frames(count = 100) {
    this.options?.onStats?.({ bitrateKbps: 2500, width: 960, height: 720, framesDecoded: count })
  }
}
const sources: VideoSource[] = ['A', 'B', 'C'].map(name => ({
  key: `air-${name}/camera`, deviceSn: `air-${name}`, cameraIndex: 'camera',
  label: name, lenses: ['zoom', 'ir']
}))
let pool: VideoSessions
let players: Player[]
let views: VideoSessionView[]
let start: ReturnType<typeof vi.fn>
let stop: ReturnType<typeof vi.fn>
let lens: ReturnType<typeof vi.fn>
let quality: ReturnType<typeof vi.fn>
const video = () => ({} as HTMLVideoElement)
const drain = () => vi.advanceTimersByTimeAsync(0)

beforeEach(() => {
  vi.useFakeTimers()
  vi.stubGlobal('window', { setTimeout, clearTimeout })
  players = []
  views = []
  start = vi.fn(async source => ({ url: `/webrtc/${source.deviceSn}-camera/whep`, reused: false }))
  stop = vi.fn(async () => true)
  lens = vi.fn(async () => undefined)
  quality = vi.fn(async () => undefined)
  pool = new VideoSessions({
    start, stop, setLens: lens, setQuality: quality, owns: () => true,
    uncertainStart: () => false, changed: value => { views = value },
    createPlayer: () => {
      const player = new Player()
      players.push(player)
      return player as unknown as WhepPlayer
    }
  })
})
afterEach(async () => {
  await pool.close()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

async function warmBoth() {
  pool.select(sources.slice(0, 2), sources[0].key)
  pool.attach(sources[0].key, video())
  pool.attach(sources[1].key, video())
  await vi.advanceTimersByTimeAsync(250)
  players.forEach(player => player.frames())
  expect(views.every(view => view.playing)).toBe(true)
}

it('switches 100 times with exactly two readers and no repeated encoder commands', async () => {
  await warmBoth()
  for (let i = 0; i < 100; i++) {
    pool.select(sources.slice(0, 2), sources[i % 2].key)
    expect(views.find(view => view.source.key === sources[i % 2].key)?.playing).toBe(true)
  }
  expect(players).toHaveLength(2)
  for (const player of players) {
    expect(player.play).toHaveBeenCalledTimes(1)
    expect(player.stop).not.toHaveBeenCalled()
  }
  expect(start).not.toHaveBeenCalled()
  expect(stop).not.toHaveBeenCalled()
  expect(lens).toHaveBeenCalledTimes(2)
  expect(quality).toHaveBeenCalledTimes(2)
})

it('shows the first frame while a slow quality command is still pending', async () => {
  let finish!: () => void
  quality.mockImplementationOnce(() => new Promise<void>(resolve => { finish = resolve }))
  pool.select(sources.slice(0, 2), sources[0].key)
  pool.attach(sources[0].key, video())
  await drain()
  expect(views[0]).toMatchObject({ playing: true, settingsPending: true })
  finish()
  await drain()
  expect(views[0].settingsPending).toBe(false)
})

it('keeps lens and quality choices on their own publisher across view switches', async () => {
  await warmBoth()
  await pool.setLens(sources[0].key, 'ir')
  await pool.setQuality(sources[0].key, 3)
  pool.select(sources.slice(0, 2), sources[1].key)
  expect(views.find(view => view.source.key === sources[1].key)).toMatchObject({ lens: 'zoom', quality: 2 })
  pool.select(sources.slice(0, 2), sources[0].key)
  expect(views.find(view => view.source.key === sources[0].key)).toMatchObject({ lens: 'ir', quality: 3 })
  expect(lens).toHaveBeenCalledTimes(3)
  expect(quality).toHaveBeenCalledTimes(3)
})

it('keeps a background failure isolated and rejects its stale frame on selection', async () => {
  await warmBoth()
  players[1].options?.onState?.('disconnected')
  expect(views[0].playing).toBe(true)
  expect(views[1].playing).toBe(false)
  await vi.advanceTimersByTimeAsync(3500)
  // A has stopped decoding despite a connected transport. Never present its
  // frozen frame as a ready fast-switch target.
  pool.select(sources.slice(0, 2), sources[0].key)
  expect(views[0].playing).toBe(false)
  await drain()
  expect(players[0].play).toHaveBeenCalledTimes(2)
})

it('does not cancel or duplicate a cold start when its device becomes background', async () => {
  let finish!: (value: { url: string }) => void
  start.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  pool.select(sources.slice(0, 2), sources[0].key)
  players[0].play.mockRejectedValueOnce(new WhepError('missing', 'http', 404))
  pool.attach(sources[0].key, video())
  await drain()
  pool.select(sources.slice(0, 2), sources[1].key)
  pool.attach(sources[1].key, video())
  finish({ url: '/webrtc/air-A-camera/whep' })
  await drain()
  expect(start).toHaveBeenCalledTimes(1)
  expect(start.mock.calls[0][0].key).toBe(sources[0].key)
  expect(views.every(view => view.playing)).toBe(true)
  expect(players[0].stop).not.toHaveBeenCalled()
})

it('evicts only the unselected reader and ignores its late callbacks', async () => {
  await warmBoth()
  pool.select(sources, sources[2].key)
  expect(views.map(view => view.source.key).sort()).toEqual([sources[0].key, sources[2].key])
  expect(players[1].stop).toHaveBeenCalledTimes(1)
  expect(players[0].stop).not.toHaveBeenCalled()
  players[1].options?.onState?.('failed')
  expect(views.find(view => view.source.key === sources[0].key)?.playing).toBe(true)
  expect(views).toHaveLength(2)
})

it('A → B → C retains B for preview, regardless of insertion or topology order', async () => {
  await warmBoth()
  pool.select(sources, sources[1].key)
  pool.select(sources, sources[2].key)
  expect(views.map(view => view.source.key).sort()).toEqual([sources[1].key, sources[2].key])
  expect(players[0].stop).toHaveBeenCalledTimes(1)
  expect(players[1].stop).not.toHaveBeenCalled()
  pool.attach(sources[2].key, video())
  await drain()
  pool.select([...sources].reverse(), sources[2].key)
  pool.select(sources, sources[2].key)
  expect(views.map(view => view.source.key).sort()).toEqual([sources[1].key, sources[2].key])
  expect(players[1].play).toHaveBeenCalledTimes(1)
  expect(players[2].play).toHaveBeenCalledTimes(1)
  pool.select(sources, sources[0].key)
  expect(views.map(view => view.source.key).sort()).toEqual([sources[0].key, sources[2].key])
})

it('skips an offline recent device and prefers it again when it returns', async () => {
  await warmBoth()
  pool.select(sources, sources[1].key)
  pool.select(sources, sources[2].key)
  pool.select([sources[0], sources[2]], sources[2].key)
  expect(views.map(view => view.source.key).sort()).toEqual([sources[0].key, sources[2].key])
  pool.select(sources, sources[2].key)
  expect(views.map(view => view.source.key).sort()).toEqual([sources[1].key, sources[2].key])
})

it('explicit stop stays stopped through topology refresh and closes only that device', async () => {
  await warmBoth()
  await pool.stop(sources[0].key)
  pool.select(sources.slice(0, 2), sources[0].key)
  await vi.advanceTimersByTimeAsync(16000)
  players[1].frames(200)
  pool.tick()
  await pool.retry(sources[0].key, false)
  expect(players[0].play).toHaveBeenCalledTimes(1)
  expect(players[1].stop).not.toHaveBeenCalled()
  expect(stop).toHaveBeenCalledWith(sources[0], true)
  await pool.start(sources[0].key)
  expect(players[0].play).toHaveBeenCalledTimes(2)
})

it('rolls back a rejected quality choice without hiding the video or changing the other device', async () => {
  await warmBoth()
  quality.mockRejectedValueOnce(new Error('device refused quality'))
  await pool.setQuality(sources[0].key, 3)
  expect(views[0]).toMatchObject({ quality: 2, playing: true, settingsPending: false, error: 'device refused quality' })
  expect(views[1]).toMatchObject({ quality: 2, playing: true, error: '' })
})

it('exit closes both readers and prevents a delayed start from reopening one', async () => {
  let finish!: (value: { url: string }) => void
  start.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  pool.select(sources.slice(0, 2), sources[0].key)
  players[0].play.mockRejectedValueOnce(new WhepError('missing', 'http', 404))
  pool.attach(sources[0].key, video())
  await drain()
  await pool.close()
  finish({ url: '/webrtc/air-A-camera/whep' })
  await vi.advanceTimersByTimeAsync(5000)
  expect(views).toEqual([])
  expect(players[0].play).toHaveBeenCalledTimes(1)
  expect(players[0].stop).toHaveBeenCalledTimes(1)
  expect(players[1].stop).toHaveBeenCalledTimes(1)
})
