import * as maplibregl from 'maplibre-gl'
import type { GeoJSONSource, StyleSpecification } from 'maplibre-gl'
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url'
import { Protocol } from 'pmtiles'
import 'maplibre-gl/dist/maplibre-gl.css'
import type { MapFactory, MapPosition } from './map'
import { localMapResource } from './map-resources'
import { fromProviderZoom, toProviderZoom } from './map-coordinates'

let protocolInstalled = false
const fonts = new Map<string, Promise<FontFace>>()
// MapLibre 6's default URL is relative to its own unbundled module. Explicitly
// bundle the worker and shared dependencies so a fresh offline install has them.
maplibregl.setWorkerUrl(workerUrl)

function localUrl(path: string, base = window.location.href): string {
  return localMapResource(path, base, window.location.origin)
}

export async function loadOfflineMapFactory(): Promise<MapFactory> {
  const root = localUrl(`${(window.YOOX_RUNTIME?.mapBaseUrl || '/maps').replace(/\/$/, '')}/`)
  const response = await fetch(`${root}current.json`, { cache: 'no-store', signal: AbortSignal.timeout(10000) })
  if (!response.ok) throw new Error('全国离线地图包未安装，请安装包含 maps 的完整离线包')
  const current = await response.json() as { version?: string }
  if (!current.version || !/^[a-zA-Z0-9][a-zA-Z0-9._-]*$/.test(current.version)) throw new Error('离线地图版本清单无效')
  const base = `${root}releases/${current.version}/`
  const family = `YOOX CJK ${current.version}`
  if (!fonts.has(base)) {
    const loading = fetch(`${base}fonts/NotoSansCJKsc-Regular.otf`, {
      signal: AbortSignal.timeout(30000)
    }).then(async response => {
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      return new FontFace(family, await response.arrayBuffer()).load()
    })
    fonts.set(base, loading.then(font => {
      document.fonts.add(font)
      return font
    }).catch(error => {
      fonts.delete(base)
      throw new Error(`离线中文字体加载失败：${error.message}`)
    }))
  }
  await fonts.get(base)
  const styleResponse = await fetch(`${base}style.json`, { signal: AbortSignal.timeout(10000) })
  if (!styleResponse.ok) throw new Error('离线地图样式缺失，请重新安装地图包')
  const style = await styleResponse.json() as StyleSpecification
  // Resolve every dependency against the immutable map release, never a CDN.
  style.glyphs = localUrl(style.glyphs!, base)
  if (typeof style.sprite !== 'string') throw new Error('离线地图图标配置无效')
  style.sprite = localUrl(style.sprite, base)
  for (const source of Object.values(style.sources)) {
    if (source.type !== 'vector' || !source.url?.startsWith('pmtiles://')) {
      throw new Error('离线底图必须使用本地 PMTiles 数据')
    }
    source.url = `pmtiles://${localUrl(source.url.slice('pmtiles://'.length), base)}`
  }
  if (!protocolInstalled) {
    maplibregl.addProtocol('pmtiles', new Protocol().tile)
    protocolInstalled = true
  }
  return async (container, options) => {
    container.style.setProperty('--yoox-map-cjk', `"${family}"`)
    let native: maplibregl.Map
    try {
      native = new maplibregl.Map({
      container, style, center: options.center, zoom: toProviderZoom('offline', options.zoom),
      minZoom: 1, maxZoom: 19, attributionControl: { compact: true },
      maxPitch: 0,
      // The upstream PBF files contain no Han glyphs. Generate those from the
      // explicitly loaded, packaged font, never an OS font or remote service.
      localIdeographFontFamily: family
      })
    } catch (reason) {
      if (!(reason instanceof maplibregl.GPUInitializationError)) throw reason
      // Hardware acceleration can be unavailable in embedded/remote browsers.
      // Use the same local vector archive and font through Canvas, without
      // requiring an online map or repeatedly retrying the unsupported GPU API.
      const sources = Object.values(style.sources)
      const source = sources[0]
      if (sources.length !== 1 || source?.type !== 'vector' || !source.url) {
        throw new Error('离线地图包不支持兼容模式，请检查底图配置')
      }
      const { createCanvasOfflineMap } = await import('./offline-map-canvas')
      return createCanvasOfflineMap(container, options, {
        url: source.url.slice('pmtiles://'.length), family, version: current.version!
      })
    }
    native.addControl(new maplibregl.ScaleControl({ unit: 'metric' }), 'bottom-left')
    const observer = new ResizeObserver(() => native.resize())
    observer.observe(container)
    let destroyed = false
    let rejectReady: (error: Error) => void = () => {}
    let timer: ReturnType<typeof setTimeout>
    const ready = new Promise<void>((resolve, reject) => {
      rejectReady = reject
      timer = setTimeout(() => reject(new Error('离线地图加载超时，请检查地图包与本地连接')), 30000)
      native.once('load', () => { clearTimeout(timer); resolve() })
      native.on('error', event => {
        clearTimeout(timer)
        reject(new Error(`离线地图资源加载失败：${event.error.message}`))
      })
    })
    let nextLine = 0
    return {
      ready,
      dataVersion: current.version,
      renderMode: 'webgl',
      createMarker(markerOptions) {
        const element = document.createElement('div')
        element.innerHTML = markerOptions.content
        element.style.zIndex = String(markerOptions.zIndex ?? 0)
        const marker = new maplibregl.Marker({
          element, anchor: 'center',
          rotationAlignment: markerOptions.rotateWithMap ? 'map' : 'viewport'
        })
          .setLngLat(markerOptions.position ?? options.center).addTo(native)
        return {
          setPosition: position => { marker.setLngLat(position) },
          setContent: content => { element.innerHTML = content },
          show: () => { element.style.display = '' },
          hide: () => { element.style.display = 'none' },
          remove: () => { marker.remove() }
        }
      },
      createLine(lineOptions) {
        const id = `yoox-trail-${++nextLine}`
        const data = (path: MapPosition[]) => ({
          type: 'Feature' as const, properties: {}, geometry: { type: 'LineString' as const, coordinates: path }
        })
        native.addSource(id, { type: 'geojson', data: data(lineOptions.path) })
        native.addLayer({
          id, type: 'line', source: id,
          layout: { 'line-cap': 'round', 'line-join': 'round' },
          paint: {
            'line-color': lineOptions.strokeColor,
            'line-width': lineOptions.strokeWeight,
            'line-opacity': lineOptions.strokeOpacity
          }
        })
        return {
          setPath: path => {
            if (destroyed) return
            const source = native.getSource(id) as GeoJSONSource | undefined
            void source?.setData(data(path)).catch(reason => {
              // A provider switch removes the source while its worker may
              // still be processing the previous telemetry update.
              if (!destroyed && native.getSource(id)) console.warn('轨迹更新失败', reason)
            })
          },
          remove: () => {
            if (destroyed) return
            if (native.getLayer(id)) native.removeLayer(id)
            if (native.getSource(id)) native.removeSource(id)
          }
        }
      },
      remove: overlay => overlay.remove(),
      setCenter: position => { native.setCenter(position) },
      getCenter: () => native.getCenter().wrap().toArray() as MapPosition,
      getZoom: () => fromProviderZoom('offline', native.getZoom()),
      setSatellite: () => {},
      on(event: string, callback: (event?: any) => void) {
        if (event === 'click') {
          native.on('click', event => callback({ position: event.lngLat.wrap().toArray() as MapPosition }))
        } else if (event === 'zoomchange') {
          // Programmatic moves must not disable aircraft following.
          native.on('zoomstart', event => { if (event.originalEvent) callback() })
        } else {
          native.on('dragstart', () => callback())
        }
      },
      destroy() {
        if (destroyed) return
        destroyed = true
        clearTimeout(timer)
        rejectReady(new Error('地图已关闭'))
        observer.disconnect()
        native.remove()
      }
    }
  }
}
