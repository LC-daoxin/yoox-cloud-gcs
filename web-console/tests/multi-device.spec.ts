import { test, expect, type Page, type WebSocketRoute } from '@playwright/test'
import { readFileSync, writeFileSync } from 'node:fs'

async function setup(page: Page, options: { exitFails?: boolean; video?: boolean; playableVideo?: boolean; slowQuality?: boolean; coldVideo?: boolean; deviceNames?: string[] } = {}) {
  const deviceNames = options.deviceNames ?? ['A', 'B']
  let socket: WebSocketRoute | undefined
  let clientNumber = 0
  const requests: Array<{ path: string; body: any }> = []
  const whepRequests: Array<{ method: string; path: string }> = []
  const published = new Set<string>()
  if (options.playableVideo) await page.addInitScript(() => {
    const senders = new Map<string, { peer: RTCPeerConnection; timer: number; stream: MediaStream }>()
    ;(window as any).__testVideoAnswer = async (path: string, offer: string) => {
      const peer = new RTCPeerConnection()
      const canvas = document.createElement('canvas')
      canvas.width = 960
      canvas.height = 720
      const context = canvas.getContext('2d')!
      const name = path.match(/air-([A-Z])-camera/)?.[1] ?? '?'
      let frame = 0
      const paint = () => {
        context.fillStyle = ({ A: '#1a4266', B: '#32661a', C: '#66361a' } as Record<string, string>)[name] ?? '#333'
        context.fillRect(0, 0, 960, 720)
        context.fillStyle = 'white'
        context.font = '48px sans-serif'
        context.fillText(`TEST ${name} · ${++frame}`, 80, 200)
      }
      paint()
      const timer = window.setInterval(paint, 100)
      const stream = canvas.captureStream(10)
      await peer.setRemoteDescription({ type: 'offer', sdp: offer })
      peer.addTrack(stream.getVideoTracks()[0], stream)
      await peer.setLocalDescription(await peer.createAnswer())
      if (peer.iceGatheringState !== 'complete') await new Promise<void>(resolve => {
        const timeout = window.setTimeout(resolve, 2000)
        peer.addEventListener('icegatheringstatechange', () => {
          if (peer.iceGatheringState === 'complete') { clearTimeout(timeout); resolve() }
        })
      })
      senders.set(path, { peer, timer, stream })
      return peer.localDescription!.sdp
    }
    ;(window as any).__testVideoDelete = (path: string) => {
      const sender = senders.get(path.replace('/session', ''))
      if (!sender) return
      sender.peer.close()
      sender.stream.getTracks().forEach(track => track.stop())
      window.clearInterval(sender.timer)
    }
  })
  const devices = deviceNames.flatMap(name => [
    { device_sn: `rc-${name}`, domain: 3, status: 'online', child_sn: `air-${name}`, call_sign: name },
    { device_sn: `air-${name}`, domain: 0, status: 'online', nickname: `飞机 ${name}` }
  ])
  await page.routeWebSocket('**/api/v1/ws**', route => { socket = route })
  await page.route('**/node_modules/.vite/deps/mqtt.js*', route => route.fulfill({
    contentType: 'application/javascript', body: readFileSync(new URL('./fixtures/mqtt-multi-device.js', import.meta.url), 'utf8')
  }))
  await page.addInitScript(() => {
    localStorage.setItem('yoox_access_token', 'multi-device-test')
    localStorage.setItem('yoox_session_user', JSON.stringify({
      access_token: 'multi-device-test', workspace_id: 'test', username: 'test'
    }))
  })
  await page.route('**/*', async route => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.hostname !== '127.0.0.1') return route.abort()
    if (url.pathname.startsWith('/webrtc/')) {
      whepRequests.push({ method: request.method(), path: url.pathname })
      if (options.playableVideo) {
        if (request.method() === 'DELETE') {
          await page.evaluate(path => (window as any).__testVideoDelete(path), url.pathname)
          return route.fulfill({ status: 200 })
        }
        if (options.coldVideo && !published.has(url.pathname)) return route.fulfill({ status: 404 })
        const answer = await page.evaluate(({ path, offer }) => (window as any).__testVideoAnswer(path, offer), {
          path: url.pathname, offer: request.postData()
        })
        return route.fulfill({ status: 201, contentType: 'application/sdp', body: answer,
          headers: { Location: `${url.pathname}/session` } })
      }
      // Hold signaling open to prove device selection stays responsive.
      await new Promise(resolve => setTimeout(resolve, 5000))
      return route.fulfill({ status: 404, body: '{}' }).catch(() => {})
    }
    if (!/^\/(manage|control|wayline|media|storage|map\/api)\//.test(url.pathname)) return route.fallback()
    const body = request.postDataJSON()
    requests.push({ path: url.pathname, body })
    let data: any = []
    if (url.pathname.endsWith('/test/devices')) data = devices
    if (url.pathname.endsWith('/token/refresh')) data = { access_token: 'multi-device-test', workspace_id: 'test', username: 'test' }
    if (url.pathname.endsWith('/live/capacity')) data = options.video || options.playableVideo ? deviceNames.map(name => ({
      sn: `air-${name}`, cameras_list: [{ index: 'camera', videos_list: [{ index: '0', type: 'zoom' }] }]
    })) : []
    if (url.pathname.endsWith('/jobs/point-flight/status')) data = null
    if (url.pathname.endsWith('/drc/connect')) data = {
      client_id: `browser-${++clientNumber}`, address: 'ws://127.0.0.1:5174/mock', username: 'test', password: 'test'
    }
    if (url.pathname.endsWith('/drc/enter')) data = {
      pub: [`thing/product/${body.dock_sn}/drc/down`], sub: [`thing/product/${body.dock_sn}/drc/up`]
    }
    if (url.pathname.endsWith('/drc/exit') && options.exitFails) {
      return route.fulfill({ json: { code: 500, message: 'device exit timeout' } })
    }
    if (url.pathname.endsWith('/live/streams/start')) {
      const endpoint = `/webrtc/${body.video_id.drone_sn}-camera/whep`
      if (options.coldVideo) await new Promise(resolve => setTimeout(resolve, 1000))
      published.add(endpoint)
      data = { url: endpoint, reused: false, started_by_request: true }
    }
    if (options.slowQuality && url.pathname.endsWith('/live/streams/update')) await new Promise(resolve => setTimeout(resolve, 3000))
    return route.fulfill({ json: { code: 0, data } })
  })
  await page.goto('/cockpit')
  await expect(page.locator('.dev-card-select')).toHaveCount(deviceNames.length)
  await expect.poll(() => !!socket).toBe(true)
  const send = (biz_code: string, data: any) => socket!.send(JSON.stringify({ biz_code, data }))
  const osd = (name: string, mode = 3) => send('device_osd', {
    sn: `air-${name}`, host: { mode_code: mode, latitude: 30, longitude: 120, elevation: 20, height: 20 }
  })
  deviceNames.forEach(name => osd(name))
  const select = async (name: string) => {
    await page.locator('.dev-card-select').filter({ hasText: `飞机 ${name}` }).click()
    await expect(page.locator('.dev-card.selected')).toContainText(`飞机 ${name}`)
    osd(name)
  }
  const takeover = async () => {
    await page.locator('.cockpit-bar').getByRole('button', { name: '接管当前飞机', exact: true }).click()
    await expect(page.locator('.cockpit-bar').getByRole('button', { name: '② 退出 DRC', exact: true })).toBeEnabled()
    await expect.poll(() => page.evaluate(() => (window as any).__mqttOut?.some((r: any) => r.method === 'heart_beat'))).toBe(true)
  }
  return { requests, whepRequests, send, osd, select, takeover }
}

const drcRequests = (requests: Array<{ path: string; body: any }>) => requests.filter(r => /\/drc\/(enter|exit)$/.test(r.path))

test('view switches keep A session and background mission progress, without controlling B', async ({ page }) => {
  const f = await setup(page)
  await f.takeover()
  f.osd('A', 5)
  f.send('flighttask_progress', { sn: 'rc-A', bid: 'mission-A', output: { status: 'in_progress', progress: { percent: 30 } } })
  await f.select('B')
  await expect(page.locator('.multi-device-status')).toContainText('控制会话：A')
  f.send('flighttask_progress', { sn: 'rc-A', bid: 'mission-A', output: { status: 'in_progress', progress: { percent: 45 } } })
  await page.keyboard.press('w')
  await expect.poll(() => page.evaluate(() => (window as any).__mqttOut.filter((r: any) => r.method === 'heart_beat').length)).toBeGreaterThan(1)
  expect(drcRequests(f.requests).map(r => [r.path.split('/').pop(), r.body.dock_sn])).toEqual([['enter', 'rc-A']])
  expect(f.requests.some(r => /authority/.test(r.path) && r.path.includes('rc-B'))).toBe(false)
  expect(await page.evaluate(() => (window as any).__mqttOut.some((r: any) => r.topic.includes('rc-B')))).toBe(false)
  await f.select('A'); f.osd('A', 5)
  await expect(page.getByText('45%', { exact: true })).toBeVisible()
  expect(f.requests.some(r => r.path.includes('/flight-tasks'))).toBe(false)
})

test('one-click handoff zeros held input on A, exits A, then acquires and enters B', async ({ page }, info) => {
  const f = await setup(page)
  await f.takeover()
  await expect(page.getByRole('button', { name: 'W 键：前进', exact: true })).toBeEnabled()
  await page.keyboard.down('w')
  await expect.poll(() => page.evaluate(() => (window as any).__mqttOut.some((r: any) => r.method === 'drone_control' && r.data.x !== 0))).toBe(true)
  await f.select('B')
  await page.keyboard.up('w')
  await f.takeover()
  expect(drcRequests(f.requests).map(r => [r.path.split('/').pop(), r.body.dock_sn]))
    .toEqual([['enter', 'rc-A'], ['exit', 'rc-A'], ['enter', 'rc-B']])
  const exitIndex = f.requests.findIndex(r => r.path.endsWith('/drc/exit'))
  const authorityIndex = f.requests.findIndex(r => r.path.includes('/rc-B/authority/flight'))
  expect(authorityIndex).toBeGreaterThan(exitIndex)
  const output = await page.evaluate(() => (window as any).__mqttOut)
  const lastA = output.filter((r: any) => r.topic.includes('/rc-A/') && r.method === 'drone_control').at(-1)
  expect([lastA.data.x, lastA.data.y, lastA.data.h, lastA.data.w]).toEqual([0, 0, 0, 0])
  expect(output.filter((r: any) => r.topic.includes('/rc-B/') && r.method === 'drone_control')
    .every((r: any) => !r.data.x && !r.data.y && !r.data.h && !r.data.w)).toBe(true)
  await expect(page.locator('.multi-device-status')).toContainText('控制会话：B')
  await page.screenshot({ path: info.outputPath('handoff-to-b.png') })
})

test('unconfirmed exit never grabs or enters B, while view remains usable', async ({ page }) => {
  const f = await setup(page, { exitFails: true })
  await f.takeover(); await f.select('B')
  await page.locator('.cockpit-bar').getByRole('button', { name: '接管当前飞机', exact: true }).click()
  await expect(page.locator('.cockpit-bar').getByRole('button', { name: '② 重试退出 DRC', exact: true })).toBeEnabled()
  expect(f.requests.some(r => r.path.includes('/rc-B/authority/flight'))).toBe(false)
  expect(drcRequests(f.requests).some(r => r.path.endsWith('/enter') && r.body.dock_sn === 'rc-B')).toBe(false)
  await f.select('A')
})

test('viewing B does not reselect A on topology refresh or apply B authority events to A session', async ({ page }) => {
  const f = await setup(page)
  await f.takeover(); await f.select('B')
  f.send('control_source_change', { sn: 'rc-B', type: 1, control_source: 'B' })
  f.send('device_update_topo', { sn: 'rc-B' })
  await expect.poll(() => f.requests.filter(r => r.path.endsWith('/test/devices')).length).toBeGreaterThan(1)
  await expect(page.locator('.dev-card.selected')).toContainText('飞机 B')
  expect(drcRequests(f.requests).filter(r => r.path.endsWith('/exit'))).toHaveLength(0)
  f.send('control_source_change', { sn: 'rc-A', type: 1, control_source: 'B' })
  await expect.poll(() => drcRequests(f.requests).filter(r => r.path.endsWith('/exit')).length).toBe(1)
  expect(drcRequests(f.requests).at(-1)?.body.dock_sn).toBe('rc-A')
})

test('a pending WHEP request does not disable switching to another aircraft', async ({ page }) => {
  const f = await setup(page, { video: true })
  await f.select('B')
  await expect(page.locator('.dev-card-select').filter({ hasText: '飞机 A' })).toBeEnabled()
  await f.select('A')
  expect(f.requests.some(r => r.path.includes('/drc/'))).toBe(false)
})

test('switching back to A reuses its session and restores control without another authority request', async ({ page }) => {
  const f = await setup(page)
  await f.takeover(); await f.select('B'); await f.select('A')
  await expect(page.getByRole('button', { name: 'W 键：前进', exact: true })).toBeEnabled()
  expect(drcRequests(f.requests)).toHaveLength(1)
  expect(f.requests.filter(r => r.path.includes('/authority/flight'))).toHaveLength(1)
})

test('viewing an autonomous B does not pause it and declining takeover keeps A connected', async ({ page }) => {
  const f = await setup(page)
  await f.takeover(); await f.select('B'); f.osd('B', 5)
  await expect(page.locator('.dev-card.selected')).toContainText('航线')
  page.once('dialog', dialog => dialog.dismiss())
  await page.locator('.cockpit-bar').getByRole('button', { name: '接管当前飞机', exact: true }).click()
  expect(drcRequests(f.requests)).toHaveLength(1)
  expect(f.requests.some(r => r.path.includes('/rc-B/authority/flight'))).toBe(false)
  await expect(page.locator('.multi-device-status')).toContainText('控制会话：A')
})

test('100 warm video switches keep both decoded streams and A flight control intact', async ({ page }, info) => {
  test.setTimeout(120000)
  const f = await setup(page, { playableVideo: true })
  await expect(page.locator('.video-session video.ready')).toHaveCount(2)
  await expect(page.getByTitle('切换为高清')).toBeEnabled()
  const tracks = await page.locator('.video-session video').evaluateAll(videos => videos.map(video =>
    ((video as HTMLVideoElement).srcObject as MediaStream).getVideoTracks()[0].id))
  await f.takeover()
  f.osd('A', 5)
  const timings: number[] = []
  for (let i = 0; i < 100; i++) {
    const name = i % 2 === 0 ? 'B' : 'A'
    const before = Date.now()
    await f.select(name)
    await expect(page.locator(`.video-session.selected[data-video-key="air-${name}/camera"] video.ready`)).toHaveCount(1)
    timings.push(Date.now() - before)
  }
  expect(await page.locator('.video-session video').evaluateAll(videos => videos.map(video =>
    ((video as HTMLVideoElement).srcObject as MediaStream).getVideoTracks()[0].id))).toEqual(tracks)
  expect(f.whepRequests.filter(r => r.method === 'POST')).toHaveLength(2)
  expect(f.whepRequests.filter(r => r.method === 'DELETE')).toHaveLength(0)
  expect(f.requests.filter(r => r.path.endsWith('/streams/switch'))).toHaveLength(2)
  expect(f.requests.filter(r => r.path.endsWith('/streams/update'))).toHaveLength(2)
  expect(f.requests.filter(r => /\/streams\/(start|stop)$/.test(r.path))).toHaveLength(0)
  expect(drcRequests(f.requests).map(r => [r.path.split('/').pop(), r.body.dock_sn])).toEqual([['enter', 'rc-A']])
  expect(f.requests.some(r => /authority/.test(r.path) && r.path.includes('rc-B'))).toBe(false)
  expect(await page.evaluate(() => (window as any).__mqttOut.some((r: any) => r.topic.includes('rc-B')))).toBe(false)
  const p95 = [...timings].sort((a, b) => a - b)[94]
  expect(p95).toBeLessThan(500)
  writeFileSync(info.outputPath('warm-switch-timing.json'), JSON.stringify({ timings, p95 }, null, 2))
  await info.attach('warm-switch-timing.json', { body: JSON.stringify({ timings, p95 }), contentType: 'application/json' })
  await page.screenshot({ path: info.outputPath('two-warm-videos.png') })
})

test('slow quality updates do not hide either decoded first frame', async ({ page }) => {
  const f = await setup(page, { playableVideo: true, slowQuality: true })
  await expect(page.locator('.video-session video.ready')).toHaveCount(2)
  await expect(page.getByTitle('切换为高清')).toBeDisabled()
  await f.select('B')
  await expect(page.locator('.video-session.selected video.ready')).toHaveCount(1)
  expect(f.whepRequests.filter(r => r.method === 'POST')).toHaveLength(2)
})

test('video preferences survive warm switches and exit releases both browser readers', async ({ page }) => {
  const f = await setup(page, { playableVideo: true })
  await expect(page.locator('.video-session video.ready')).toHaveCount(2)
  await page.getByTitle('切换为高清').click()
  await expect.poll(() => f.requests.filter(r => r.path.endsWith('/streams/update') && r.body.video_quality === 3).length).toBe(1)
  await f.select('B')
  await expect(page.getByTitle('切换为标清')).toHaveClass(/active/)
  await f.select('A')
  await expect(page.getByTitle('切换为高清')).toHaveClass(/active/)
  await page.getByRole('link', { name: '← 退出' }).click()
  await expect.poll(() => f.whepRequests.filter(r => r.method === 'DELETE').length).toBe(2)
  expect(f.requests.filter(r => r.path.endsWith('/streams/stop'))).toHaveLength(0)
})

test('exit during two cold starts cleans up late publishers without reopening readers', async ({ page }) => {
  const f = await setup(page, { playableVideo: true, coldVideo: true })
  await expect.poll(() => f.requests.filter(r => r.path.endsWith('/streams/start')).length).toBe(2)
  await page.getByRole('link', { name: '← 退出' }).click()
  await expect.poll(() => f.requests.filter(r => r.path.endsWith('/streams/stop')).length).toBe(2)
  expect(f.requests.filter(r => r.path.endsWith('/streams/stop')).map(r => r.body.video_id.drone_sn).sort())
    .toEqual(['air-A', 'air-B'])
  expect(f.whepRequests.filter(r => r.method === 'POST')).toHaveLength(2)
  expect(f.requests.some(r => r.path.includes('/authority/') || r.path.includes('/drc/'))).toBe(false)
})

test('map priority hides the preview while both decoders and readers stay warm', async ({ page }, info) => {
  const f = await setup(page, { playableVideo: true })
  await expect(page.locator('.video-session video.ready')).toHaveCount(2)
  const before = await page.locator('.video-session video').evaluateAll(videos => videos.map(element => {
    const video = element as HTMLVideoElement
    return { id: (video.srcObject as MediaStream).getVideoTracks()[0].id, frames: video.getVideoPlaybackQuality().totalVideoFrames }
  }))
  await page.getByRole('button', { name: '地图优先', exact: true }).click()
  await expect(page.locator('.video-session.preview')).toHaveCSS('opacity', '0')
  await expect(page.locator('.video-session.preview')).toHaveAttribute('aria-hidden', 'true')
  // Cross the 7-second stalled-decoder watchdog window: hidden presentation
  // must neither pause decoding nor trigger a replacement connection.
  await page.waitForTimeout(8500)
  const after = await page.locator('.video-session video').evaluateAll(videos => videos.map(element => {
    const video = element as HTMLVideoElement
    return { id: (video.srcObject as MediaStream).getVideoTracks()[0].id, frames: video.getVideoPlaybackQuality().totalVideoFrames, paused: video.paused }
  }))
  for (let i = 0; i < before.length; i++) {
    expect(after[i].id).toBe(before[i].id)
    expect(after[i].frames).toBeGreaterThan(before[i].frames + 3)
    expect(after[i].paused).toBe(false)
  }
  await f.select('B')
  await expect(page.locator('.video-session.selected[data-video-key="air-B/camera"] video.ready')).toHaveCount(1)
  await expect(page.locator('.video-session.preview')).toHaveCSS('opacity', '0')
  await page.screenshot({ path: info.outputPath('map-priority-no-preview.png') })
  await page.getByRole('button', { name: '视频优先', exact: true }).click()
  await expect(page.locator('.video-session.preview')).toHaveCSS('opacity', '1')
  await expect(page.locator('.video-session.preview')).toHaveAttribute('data-video-key', 'air-A/camera')
  expect(f.whepRequests.filter(r => r.method === 'POST')).toHaveLength(2)
  expect(f.whepRequests.filter(r => r.method === 'DELETE')).toHaveLength(0)
  expect(drcRequests(f.requests)).toHaveLength(0)
})

test('three-device preview follows recent views while flight control stays on A', async ({ page }, info) => {
  const f = await setup(page, { playableVideo: true, deviceNames: ['A', 'B', 'C'] })
  await expect(page.locator('.video-session video.ready')).toHaveCount(2)
  await f.takeover()
  await f.select('B')
  await f.select('C')
  await expect(page.locator('.video-session.selected[data-video-key="air-C/camera"] video.ready')).toHaveCount(1)
  await expect(page.locator('.video-session.preview')).toHaveAttribute('data-video-key', 'air-B/camera')
  await expect(page.locator('.video-session.preview')).toContainText('B · 实时预览')
  const fetches = f.requests.filter(r => r.path.endsWith('/test/devices')).length
  f.send('device_update_topo', { sn: 'rc-C' })
  await expect.poll(() => f.requests.filter(r => r.path.endsWith('/test/devices')).length).toBeGreaterThan(fetches)
  await expect(page.locator('.video-session.preview')).toHaveAttribute('data-video-key', 'air-B/camera')
  expect(f.whepRequests.filter(r => r.method === 'POST')).toHaveLength(3)
  expect(f.whepRequests.filter(r => r.method === 'DELETE').map(r => r.path)).toEqual(['/webrtc/air-A-camera/whep/session'])
  expect(drcRequests(f.requests).map(r => [r.path.split('/').pop(), r.body.dock_sn])).toEqual([['enter', 'rc-A']])
  expect(f.requests.some(r => /authority/.test(r.path) && /rc-[BC]/.test(r.path))).toBe(false)
  expect(await page.evaluate(() => (window as any).__mqttOut.some((r: any) => /rc-[BC]/.test(r.topic)))).toBe(false)
  await expect(page.locator('.multi-device-status')).toContainText('控制会话：A')
  await page.screenshot({ path: info.outputPath('recent-preview-b-current-c.png') })
})
