import { afterEach, describe, expect, it, vi } from 'vitest'
import { fromProviderPosition, toProviderPosition, fromProviderZoom, toProviderZoom, type MapPosition } from '../src/services/map-coordinates'
import { localMapResource } from '../src/services/map-resources'
import { getMapProvider, loadMapFactory } from '../src/services/map'
import { loadAMap } from '../src/services/amap'

afterEach(() => vi.unstubAllGlobals())

describe('map coordinates and resources', () => {
  const points: MapPosition[] = [[116.397, 39.909], [113.264, 23.129], [87.616, 43.825], [121.565, 25.033]]
  it('keeps offline positions and flight click targets in WGS84', () => {
    for (const point of points) {
      expect(toProviderPosition('offline', point)).toEqual(point)
      expect(fromProviderPosition('offline', point)).toEqual(point)
    }
  })
  it('round-trips AMap clicks without accumulating errors on repeated switches', () => {
    for (const point of points) {
      let result = point
      for (let index = 0; index < 20; index++) {
        result = fromProviderPosition('amap', toProviderPosition('amap', result))
        result = fromProviderPosition('offline', toProviderPosition('offline', result))
      }
      expect(result[0]).toBeCloseTo(point[0], 6)
      expect(result[1]).toBeCloseTo(point[1], 6)
    }
  })
  it('preserves font placeholders and rejects off-site dependencies', () => {
    const origin = 'http://192.168.88.197:8080'
    const base = `${origin}/maps/releases/china-v1/`
    expect(localMapResource('fonts/{fontstack}/{range}.pbf', base, origin))
      .toBe(`${base}fonts/{fontstack}/{range}.pbf`)
    expect(() => localMapResource('https://cdn.example/font.pbf', base, origin)).toThrow()
    expect(() => localMapResource('//cdn.example/sprite', base, origin)).toThrow()
  })
  it('keeps the same ground scale when switching 256px and 512px map engines', () => {
    for (const zoom of [2, 11, 16, 20]) {
      expect(512 * 2 ** toProviderZoom('offline', zoom)).toBe(256 * 2 ** zoom)
      expect(fromProviderZoom('offline', toProviderZoom('offline', zoom))).toBe(zoom)
      expect(toProviderZoom('amap', zoom)).toBe(zoom)
    }
  })
  it('defaults a fresh session to offline without requiring an AMap key', () => {
    vi.stubGlobal('window', { YOOX_RUNTIME: {} })
    expect(getMapProvider()).toBe('offline')
  })
})

describe('AMap loader recovery', () => {
  function environment() {
    const scripts: any[] = []
    const timers: Array<() => void> = []
    const browser: any = {
      YOOX_RUNTIME: { amapKey: 'test-key' },
      setTimeout: (callback: () => void) => { timers.push(callback); return timers.length },
      clearTimeout: vi.fn()
    }
    vi.stubGlobal('window', browser)
    vi.stubGlobal('document', {
      createElement: () => {
        const script = { remove: vi.fn(), onload: undefined, onerror: undefined }
        scripts.push(script)
        return script
      },
      head: { appendChild: vi.fn() }
    })
    return { browser, scripts, timers }
  }
  it('retries after a loaded script fails to expose the SDK', async () => {
    const { browser, scripts } = environment()
    const failed = loadAMap()
    const assertion = expect(failed).rejects.toThrow('AMap 未就绪')
    scripts[0].onload()
    await assertion
    expect(browser.__amapLoading).toBeUndefined()
    const retry = loadAMap()
    browser.AMap = { Map: vi.fn() }
    scripts[1].onload()
    expect(await retry).toBe(browser.AMap)
  })
  it('ignores a late event from a timed-out script while a retry is active', async () => {
    const { browser, scripts, timers } = environment()
    const failed = loadAMap()
    const assertion = expect(failed).rejects.toThrow('加载超时')
    timers[0]()
    await assertion
    const retry = loadAMap()
    scripts[0].onload()
    expect(browser.__amapLoading).toBe(retry)
    browser.AMap = { Map: vi.fn() }
    scripts[1].onload()
    expect(await retry).toBe(browser.AMap)
  })
})

it('adapts markers, trails, viewport and clicks while preserving WGS84 business data', async () => {
  const position: MapPosition = [116.397, 39.909]
  const display = toProviderPosition('amap', position)
  const handlers = new Map<string, (event: unknown) => void>()
  const setPosition = vi.fn()
  const setPath = vi.fn()
  const remove = vi.fn()
  const destroy = vi.fn()
  const setLayers = vi.fn()
  const disconnect = vi.fn()
  class NativeMap {
    add = vi.fn()
    remove = remove
    destroy = destroy
    setLayers = setLayers
    setMapStyle = vi.fn()
    setCenter = vi.fn()
    getCenter = () => ({ getLng: () => display[0], getLat: () => display[1] })
    getZoom = () => 16
    on = (event: string, callback: (value: unknown) => void) => handlers.set(event, callback)
  }
  class NativeMarker {
    setPosition = setPosition
    setContent = vi.fn()
    show = vi.fn()
    hide = vi.fn()
  }
  class NativeLine { setPath = setPath }
  class Layer {
    static Satellite = Layer
    static RoadNet = Layer
  }
  vi.stubGlobal('window', { AMap: { Map: NativeMap, Marker: NativeMarker, Polyline: NativeLine, TileLayer: Layer } })
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect = disconnect })
  const factory = await loadMapFactory('amap')
  const map = await factory({} as HTMLElement, { center: position, zoom: 16, satellite: false })
  await map.ready
  const marker = map.createMarker({ content: '<span>飞机</span>', position })
  marker.setPosition(position)
  expect(setPosition).toHaveBeenCalledWith(display)
  const line = map.createLine({ path: [position, [116.398, 39.910]], strokeColor: 'blue', strokeWeight: 3, strokeOpacity: 1 })
  line.setPath([position, [116.398, 39.910]])
  expect(setPath.mock.calls[0][0][0]).toEqual(display)
  expect(map.getCenter()[0]).toBeCloseTo(position[0], 6)
  const click = vi.fn()
  map.on('click', click)
  handlers.get('click')!({ lnglat: { getLng: () => display[0], getLat: () => display[1] } })
  expect(click.mock.calls[0][0].position[0]).toBeCloseTo(position[0], 6)
  handlers.get('click')!({ lnglat: { lng: display[0], lat: display[1] } })
  expect(click.mock.calls[1][0].position[1]).toBeCloseTo(position[1], 6)
  handlers.get('click')!({})
  expect(click).toHaveBeenCalledTimes(2)
  map.setSatellite(true)
  expect(setLayers.mock.calls[0][0]).toHaveLength(2)
  map.remove(marker)
  expect(remove).toHaveBeenCalled()
  map.destroy()
  map.destroy()
  expect(destroy).toHaveBeenCalledTimes(1)
  expect(disconnect).toHaveBeenCalledTimes(1)
})
