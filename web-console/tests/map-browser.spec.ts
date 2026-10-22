import { test, expect, type WebSocketRoute } from '@playwright/test'

test('cold offline dashboard and cockpit load fonts, sprites and local tiles without external requests', async ({ page, baseURL }, testInfo) => {
  const unexpected: string[] = []
  const assets: string[] = []
  const errors: string[] = []
  await page.routeWebSocket('**/api/v1/ws**', () => {})
  // Fake only business state. The map, fonts and tiles must come from the actual
  // deployment. Never send control commands to real devices during map testing.
  await page.addInitScript(() => {
    localStorage.setItem('yoox_access_token', 'map-browser-test')
    localStorage.setItem('yoox_session_user', JSON.stringify({
      access_token: 'map-browser-test', workspace_id: 'map-test', username: 'map-test'
    }))
  })
  await page.route('**/*', async route => {
    const url = new URL(route.request().url())
    if (url.origin !== new URL(baseURL!).origin) {
      unexpected.push(url.href)
      return route.abort()
    }
    if (/^\/(manage|control|wayline|media|storage|map\/api)\//.test(url.pathname)) {
      return route.fulfill({ json: { code: 0, data: [] } })
    }
    if (url.pathname === '/actuator/health') return route.fulfill({ json: { status: 'UP' } })
    return route.continue()
  })
  page.on('pageerror', error => errors.push(error.message))
  page.on('response', response => {
    if (response.url().includes('/maps/')) {
      assets.push(response.url())
      if (response.status() >= 400) errors.push(`${response.status()} ${response.url()}`)
    }
  })
  await page.goto('/')
  await expect(page.getByLabel('地图来源')).toHaveValue('offline')
  await expect(page.locator('.maplibregl-canvas')).toBeVisible({ timeout: 60000 })
  await expect(page.getByText('正在加载地图…')).toHaveCount(0, { timeout: 60000 })
  await expect(page.locator('.dash-map-message')).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('offline-dashboard.png') })
  await page.getByRole('link', { name: '地图监控' }).click()
  await expect(page).toHaveURL(/\/cockpit$/)
  await expect(page.getByLabel('地图来源')).toHaveValue('offline')
  await expect(page.locator('.cx-fallback')).toHaveCount(0, { timeout: 60000 })
  await expect(page.getByTitle('在地图上选择云台 Look At 目标', { exact: true })).toBeEnabled()
  // Selecting a point opens the existing dialog; no submit or flight command.
  await page.getByTitle('在地图上选择云台 Look At 目标', { exact: true }).click()
  const canvas = page.locator('.maplibregl-canvas')
  const bounds = await canvas.boundingBox()
  await canvas.click({ position: { x: bounds!.width * .45, y: bounds!.height * .45 } })
  await expect(page.getByText('Look At 目标', { exact: true }).first()).toBeVisible()
  await expect(page.getByText('Look At 目标', { exact: true }).first()).toHaveCSS('font-family', /YOOX CJK/)
  await page.screenshot({ path: testInfo.outputPath('offline-cockpit.png') })
  expect(assets.some(url => url.endsWith('NotoSansCJKsc-Regular.otf'))).toBe(true)
  expect(assets.some(url => /\.pmtiles/.test(url))).toBe(true)
  expect(assets.some(url => /sprites\/v4\/dark.*\.png/.test(url))).toBe(true)
  expect(assets.some(url => /fonts\/.*\.pbf/.test(url))).toBe(true)
  expect(unexpected).toEqual([])
  expect(errors).toEqual([])
})

test('AMap failure recovers offline with telemetry overlays and selected target preserved', async ({ page, baseURL }, testInfo) => {
  let telemetrySocket: WebSocketRoute | undefined
  await page.routeWebSocket('**/api/v1/ws**', socket => { telemetrySocket = socket })
  await page.addInitScript(() => {
    localStorage.setItem('yoox_access_token', 'map-browser-test')
    localStorage.setItem('yoox_session_user', JSON.stringify({
      access_token: 'map-browser-test', workspace_id: 'map-test', username: 'map-test'
    }))
  })
  await page.route('**/*', route => {
    const url = new URL(route.request().url())
    if (url.origin !== new URL(baseURL!).origin) return route.abort()
    if (/^\/(manage|control|wayline|media|storage|map\/api)\//.test(url.pathname)) {
      if (url.pathname.endsWith('/jobs/point-flight/status')) {
        return route.fulfill({ json: { code: 0, data: {
          sn: 'test-rc', kind: 'flyto', fly_to_id: 'map-test-task', updated_at: Date.now(),
          status: 'wayline_progress', active: true, result: 0,
          remaining_distance: 150, remaining_time: 60,
          planned_path_points: [{ longitude: 116.3995, latitude: 39.9085, height: 20 }]
        } } })
      }
      const data = url.pathname.endsWith('/map-test/devices') ? [
        { device_sn: 'test-rc', domain: 3, status: 'online', child_sn: 'test-aircraft', nickname: '地图验证遥控器' },
        { device_sn: 'test-aircraft', domain: 0, status: 'online', nickname: '地图验证飞机' }
      ] : []
      return route.fulfill({ json: { code: 0, data } })
    }
    return route.continue()
  })
  await page.goto('/cockpit')
  await expect(page.locator('.cx-fallback')).toHaveCount(0, { timeout: 60000 })
  await expect.poll(() => Boolean(telemetrySocket)).toBe(true)
  telemetrySocket!.send(JSON.stringify({ biz_code: 'gateway_osd', data: {
    sn: 'test-rc', host: { longitude: 116.396, latitude: 39.908 }
  } }))
  for (const longitude of [116.397, 116.398]) {
    telemetrySocket!.send(JSON.stringify({ biz_code: 'device_osd', data: {
      sn: 'test-aircraft', host: {
        longitude, latitude: 39.909, attitude_head: 45, mode_code: 37,
        payload: [{ measure_target_error_state: 0, measure_target_distance: 120,
          measure_target_longitude: 116.399, measure_target_latitude: 39.910, measure_target_altitude: 50 }]
      }
    } }))
  }
  await expect(page.locator('.maplibregl-marker')).toHaveCount(4)
  await expect(page.locator('.point-flight-map-status')).toBeVisible()
  await expect(page.getByRole('button', { name: '结束任务', exact: true })).toBeEnabled()
  await page.getByTitle('在地图上选择云台 Look At 目标', { exact: true }).click()
  const canvas = page.locator('.maplibregl-canvas')
  const bounds = await canvas.boundingBox()
  await canvas.click({ position: { x: bounds!.width * .45, y: bounds!.height * .35 } })
  await expect(page.locator('.maplibregl-marker')).toHaveCount(5)
  const longitude = await page.getByLabel('经度', { exact: true }).inputValue()
  const latitude = await page.getByLabel('纬度', { exact: true }).inputValue()
  // The running-task banner must not cover the provider selector.
  await page.getByLabel('地图来源').click()
  await page.keyboard.press('Escape')
  await page.getByLabel('地图来源').selectOption('amap')
  await expect(page.locator('.fb-tip')).toContainText(/高德地图/, { timeout: 20000 })
  await expect(page.getByLabel('地图来源')).toBeEnabled()
  await page.getByLabel('地图来源').selectOption('offline')
  await expect(page.locator('.cx-fallback')).toHaveCount(0, { timeout: 60000 })
  await expect(page.locator('.map-tools').getByTitle('定位飞行器', { exact: true })).toBeEnabled()
  await expect(page.locator('.maplibregl-marker')).toHaveCount(5)
  await expect(page.locator('.point-flight-map-status')).toBeVisible()
  await expect(page.getByLabel('经度', { exact: true })).toHaveValue(longitude)
  await expect(page.getByLabel('纬度', { exact: true })).toHaveValue(latitude)
  await page.screenshot({ path: testInfo.outputPath('offline-cockpit-preserved.png') })
})

test('live AMap keeps satellite and road map switching', async ({ page, baseURL }, testInfo) => {
  test.skip(process.env.YOOX_MAP_TEST_AMAP !== '1', 'Requires internet access and a valid deployment AMap key')
  await page.routeWebSocket('**/api/v1/ws**', () => {})
  await page.addInitScript(() => {
    localStorage.setItem('yoox_access_token', 'map-browser-test')
    localStorage.setItem('yoox_session_user', JSON.stringify({
      access_token: 'map-browser-test', workspace_id: 'map-test', username: 'map-test'
    }))
  })
  await page.route('**/*', route => {
    const url = new URL(route.request().url())
    if (url.origin === new URL(baseURL!).origin &&
        /^\/(manage|control|wayline|media|storage|map\/api)\//.test(url.pathname)) {
      return route.fulfill({ json: { code: 0, data: [] } })
    }
    return route.continue()
  })
  await page.goto('/cockpit')
  await expect(page.locator('.cx-fallback')).toHaveCount(0, { timeout: 60000 })
  await page.getByLabel('地图来源').selectOption('amap')
  await expect(page.locator('.amap-maps')).toBeVisible({ timeout: 30000 })
  await expect(page.locator('.cx-fallback')).toHaveCount(0)
  await expect(page.getByTitle('切换为地图模式', { exact: true })).toBeEnabled()
  await page.getByTitle('切换为地图模式', { exact: true }).click()
  await expect(page.getByTitle('切换为卫星模式', { exact: true })).toBeEnabled()
  await page.screenshot({ path: testInfo.outputPath('amap-road-map.png') })
  await page.getByLabel('地图来源').selectOption('offline')
  await expect(page.locator('.cx-fallback')).toHaveCount(0, { timeout: 60000 })
  await expect(page.locator('.maplibregl-canvas')).toBeVisible()
})
