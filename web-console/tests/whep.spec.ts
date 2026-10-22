import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { WhepPlayer } from '../src/services/whep'

class Peer extends EventTarget {
  iceGatheringState = 'complete'
  connectionState = 'connected'
  localDescription = { sdp: 'offer' }
  addTransceiver() {}
  async createOffer() { return this.localDescription }
  async setLocalDescription() {}
  async setRemoteDescription() {}
  close() { this.connectionState = 'closed' }
}

let player: WhepPlayer
let video: HTMLVideoElement
let request: ReturnType<typeof vi.fn>

beforeEach(() => {
  vi.useFakeTimers()
  vi.stubGlobal('window', {
    setTimeout, clearTimeout, setInterval, clearInterval,
    location: { href: 'http://10.42.0.1:8080/cockpit' }
  })
  vi.stubGlobal('HTMLMediaElement', { HAVE_CURRENT_DATA: 2 })
  vi.stubGlobal('RTCPeerConnection', Peer)
  request = vi.fn()
  vi.stubGlobal('fetch', request)
  video = Object.assign(new EventTarget(), {
    readyState: 2, videoWidth: 1280, videoHeight: 720,
    play: async () => undefined
  }) as unknown as HTMLVideoElement
  player = new WhepPlayer()
})

afterEach(async () => {
  await player.stop()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

it('allows a publisher to become ready after an acknowledged start', async () => {
  request.mockResolvedValueOnce(new Response('', { status: 404 }))
    .mockResolvedValueOnce(new Response('', { status: 404 }))
    .mockResolvedValueOnce(new Response('answer', {
      status: 201, headers: { Location: '/webrtc/camera/whep/session' }
    }))
    .mockResolvedValue(new Response(null, { status: 200 }))
  const playback = player.play(video, '/webrtc/camera/whep', { publisherWaitMs: 6000 })
  await vi.advanceTimersByTimeAsync(1200)
  await playback
  expect(request).toHaveBeenCalledTimes(3)
  await player.stop()
  expect(request).toHaveBeenLastCalledWith(
    'http://10.42.0.1:8080/webrtc/camera/whep/session', expect.objectContaining({ method: 'DELETE' }))
})

it('keeps the initial absent-publisher probe immediate', async () => {
  request.mockResolvedValue(new Response('', { status: 404 }))
  await expect(player.play(video, '/webrtc/camera/whep')).rejects.toMatchObject({ status: 404 })
  expect(request).toHaveBeenCalledTimes(1)
})

it('stops waiting for a publisher within the configured window', async () => {
  request.mockResolvedValue(new Response('', { status: 404 }))
  const outcome = expect(player.play(video, '/webrtc/camera/whep', {
    publisherWaitMs: 1800
  })).rejects.toMatchObject({ status: 404 })
  await vi.advanceTimersByTimeAsync(1800)
  await outcome
  expect(request).toHaveBeenCalledTimes(3)
})

it('does not retry authentication failures', async () => {
  request.mockResolvedValue(new Response('', { status: 401 }))
  await expect(player.play(video, '/webrtc/camera/whep', {
    publisherWaitMs: 6000
  })).rejects.toMatchObject({ status: 401 })
  expect(request).toHaveBeenCalledTimes(1)
})

it('does not reopen a cancelled publisher wait', async () => {
  request.mockResolvedValue(new Response('', { status: 404 }))
  const outcome = expect(player.play(video, '/webrtc/camera/whep', {
    publisherWaitMs: 6000
  })).rejects.toThrow()
  await vi.advanceTimersByTimeAsync(100)
  await player.stop()
  await vi.advanceTimersByTimeAsync(600)
  await outcome
  expect(request).toHaveBeenCalledTimes(1)
})

it('cancels an outstanding WHEP POST when switching views', async () => {
  request.mockImplementation((_url, options) => new Promise((_resolve, reject) => {
    options.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
  }))
  const outcome = expect(player.play(video, '/webrtc/camera/whep')).rejects.toThrow()
  await vi.advanceTimersByTimeAsync(0)
  expect(request).toHaveBeenCalledTimes(1)
  await player.stop()
  await outcome
  expect(request.mock.calls[0][1].signal.aborted).toBe(true)
})

it('bounds DELETE cleanup when the old media gateway is unresponsive', async () => {
  request.mockResolvedValueOnce(new Response('answer', {
    status: 201, headers: { Location: '/webrtc/camera/whep/session' }
  })).mockImplementation((_url, options) => new Promise((_resolve, reject) => {
    options.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
  }))
  await player.play(video, '/webrtc/camera/whep')
  const cleanup = player.stop()
  await vi.advanceTimersByTimeAsync(1500)
  await cleanup
  expect(request.mock.calls[1][1].signal.aborted).toBe(true)
})

it('bounds a stalled WHEP POST by the playback deadline', async () => {
  request.mockImplementation((_url, options) => new Promise((_resolve, reject) => {
    options.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
  }))
  const outcome = expect(player.play(video, '/webrtc/camera/whep', { timeoutMs: 1000 })).rejects.toThrow()
  await vi.advanceTimersByTimeAsync(1000)
  await outcome
  expect(request.mock.calls[0][1].signal.aborted).toBe(true)
})

it('cancels a pending new-lens frame wait when its reader is closed', async () => {
  request.mockResolvedValue(new Response('answer', { status: 201 }))
  await player.play(video, '/webrtc/camera/whep')
  const outcome = expect(player.waitForFrame()).rejects.toThrow()
  await player.stop()
  await outcome
  expect(vi.getTimerCount()).toBe(0)
})
