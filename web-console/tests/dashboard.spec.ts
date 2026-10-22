import { test, expect, type Page, type WebSocketRoute } from '@playwright/test'

async function setup(page: Page, noWebgl: boolean) {
  const origin = new URL(process.env.YOOX_DASHBOARD_TEST_URL || 'http://127.0.0.1:5174').origin
  let socket: WebSocketRoute | undefined
  let devices = ['A', 'B'].map(name => ({
    device_sn: `rc-${name}`, domain: 2, status: true, aircraft_sn: `air-${name}`, child_device_sn: `air-${name}`,
    children: { device_sn: `air-${name}`, domain: 0, status: true, nickname: `飞机 ${name}` }
  }))
  const writes: string[] = []
  const external: string[] = []
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(disabled => {
    localStorage.setItem('yoox_access_token', 'dashboard-test')
    localStorage.setItem('yoox_session_user', JSON.stringify({
      access_token: 'dashboard-test', workspace_id: 'dashboard-test', username: 'test'
    }))
    if (disabled) {
      const getContext = HTMLCanvasElement.prototype.getContext
      HTMLCanvasElement.prototype.getContext = function (type: string, ...args: any[]) {
        if (['webgl2', 'webgl', 'experimental-webgl'].includes(type)) return null
        return (getContext as any).call(this, type, ...args)
      } as any
    }
  }, noWebgl)
  await page.routeWebSocket('**/api/v1/ws**', route => { socket = route })
  await page.route('**/*', route => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.origin !== origin) {
      external.push(url.href)
      return route.abort()
    }
    if (/^\/(manage|control|wayline|media|storage|map\/api)\//.test(url.pathname)) {
      if (request.method() !== 'GET') writes.push(url.pathname)
      const data = url.pathname.endsWith('/dashboard-test/devices') ? devices
        : url.pathname.endsWith('/jobs/point-flight/status') ? null : []
      return route.fulfill({ json: { code: 0, data } })
    }
    if (url.pathname === '/actuator/health') return route.fulfill({ json: { status: 'UP' } })
    return route.fallback()
  })
  await page.goto('/')
  await expect.poll(() => !!socket).toBe(true)
  return {
    writes, external, errors,
    offline: () => {
      devices = devices.map(device => ({ ...device, status: false }))
      socket!.send(JSON.stringify({ biz_code: 'device_offline', data: { sn: 'rc-A' } }))
    },
    online: () => {
      devices = devices.map(device => ({ ...device, status: true }))
      socket!.send(JSON.stringify({ biz_code: 'device_online', data: { sn: 'rc-A' } }))
    },
    telemetry: () => socket!.send(JSON.stringify({ biz_code: 'device_osd', data: {
      sn: 'air-A', host: { longitude: 116.397, latitude: 39.909, mode_code: 0, attitude_head: 45 }
    } }))
  }
}

test('boolean online status and topology events keep the dashboard count accurate', async ({ page }) => {
  const fixture = await setup(page, false)
  const online = page.locator('.metric').filter({ hasText: '在线设备' }).locator('strong')
  await expect(online).toHaveText('2')
  await expect(page.locator('.metric').filter({ hasText: '机巢数量' }).locator('strong')).toHaveText('0')
  fixture.offline()
  await expect(online).toHaveText('0')
  fixture.online()
  await expect(online).toHaveText('2')
  expect(fixture.writes).toEqual([])
})

test('without WebGL the offline dashboard renders real local tiles and cockpit preserves WGS84 targets', async ({ page }, info) => {
  const fixture = await setup(page, true)
  await expect(page.locator('.metric').filter({ hasText: '在线设备' }).locator('strong')).toHaveText('2')
  await expect(page.locator('.yoox-canvas-map')).toBeVisible({ timeout: 45000 })
  await expect(page.locator('.dash-map-message')).toHaveCount(0, { timeout: 45000 })
  await expect(page.getByText('兼容模式', { exact: true })).toHaveCount(0)
  const tiles = page.locator('canvas.leaflet-tile-loaded')
  await expect(tiles.first()).toBeVisible()
  const pixels = await tiles.evaluateAll(nodes => nodes.reduce((count, tile) => {
    const canvas = tile as HTMLCanvasElement
    const ctx = canvas.getContext('2d')!
    const data = ctx.getImageData(0, 0, canvas.width, canvas.height).data
    for (let i = 4; i < data.length; i += 64) {
      if (data[i] !== data[0] || data[i + 1] !== data[1] || data[i + 2] !== data[2]) count++
    }
    return count
  }, 0))
  expect(pixels).toBeGreaterThan(100)
  fixture.telemetry()
  await expect(page.locator('.yoox-canvas-marker')).toHaveCount(1)
  await page.screenshot({ path: info.outputPath('dashboard-canvas-online-two.png') })
  await page.getByRole('link', { name: '地图监控', exact: true }).click()
  await expect(page.locator('.yoox-canvas-map')).toBeVisible({ timeout: 45000 })
  await expect(page.locator('.cx-fallback')).toHaveCount(0, { timeout: 45000 })
  await expect(page.getByText('兼容模式', { exact: true })).toHaveCount(0)
  fixture.telemetry()
  await expect(page.locator('.yoox-canvas-marker-content').first()).toBeVisible()
  await page.getByTitle('在地图上选择云台 Look At 目标', { exact: true }).click()
  const map = page.locator('.yoox-canvas-map')
  const box = (await map.boundingBox())!
  await map.click({ position: { x: box.width / 2, y: box.height / 2 } })
  // Allow pixel rounding at the current zoom, but never a GCJ-02/WGS84 offset.
  await expect(page.getByLabel('经度', { exact: true })).toBeVisible()
  expect(Number(await page.getByLabel('经度', { exact: true }).inputValue())).toBeCloseTo(116.397, 4)
  expect(Number(await page.getByLabel('纬度', { exact: true }).inputValue())).toBeCloseTo(39.909, 4)
  await page.screenshot({ path: info.outputPath('cockpit-canvas-target.png') })
  expect(fixture.writes).toEqual([])
  expect(fixture.external).toEqual([])
  expect(fixture.errors).toEqual([])
})

test('a missing offline archive shows a Chinese error rather than a successful blank canvas', async ({ page }) => {
  await page.route('**/maps/**/*.pmtiles', route => route.fulfill({ status: 404 }))
  await setup(page, true)
  await expect(page.locator('.dash-map-message')).toContainText('离线地图数据加载失败', { timeout: 45000 })
  await expect(page.getByRole('button', { name: '重新加载地图' })).toBeVisible()
})
