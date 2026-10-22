import L from 'leaflet'
import { namedFlavor } from '@protomaps/basemaps'
import { leafletLayer, paintRules, CenteredTextSymbolizer, LineLabelSymbolizer, PmtilesSource, type LabelRule } from 'protomaps-leaflet'
import 'leaflet/dist/leaflet.css'
import './offline-map-canvas.css'
import type { GcsMap, MapOptions, MapPosition } from './map'

// protomaps-leaflet's documented frontend uses Leaflet's global namespace.
Object.assign(window, { L })
const latLng = ([lng, lat]: MapPosition): L.LatLngTuple => [lat, lng]
const position = (value: L.LatLng): MapPosition => [value.wrap().lng, value.lat]

export function createCanvasOfflineMap(
  container: HTMLElement,
  options: MapOptions,
  resources: { url: string; family: string; version: string }
): GcsMap {
  if (!document.createElement('canvas').getContext('2d')) {
    throw new Error('浏览器无法绘制地图，请启用图形渲染或使用其他浏览器')
  }
  container.classList.add('yoox-canvas-map')
  container.style.setProperty('--yoox-map-cjk', `"${resources.family}"`)
  const native = L.map(container, {
    center: latLng(options.center), zoom: options.zoom, minZoom: 2, maxZoom: 20,
    zoomControl: false, keyboard: false, zoomAnimation: false, fadeAnimation: false,
    attributionControl: true
  })
  native.attributionControl.setPrefix(false)
  L.control.scale({ imperial: false, position: 'bottomleft' }).addTo(native)
  const observer = new ResizeObserver(() => native.invalidateSize({ pan: false }))
  observer.observe(container)
  let destroyed = false
  let settled = false
  let resolveReady: () => void
  let rejectReady: (reason: Error) => void
  const ready = new Promise<void>((resolve, reject) => { resolveReady = resolve; rejectReady = reject })
  const timeout = window.setTimeout(() => finish(new Error('离线地图加载超时，请检查地图包与本地连接')), 30_000)
  const requests = new Set<AbortController>()
  function finish(error?: Error) {
    if (settled) return
    settled = true
    window.clearTimeout(timeout)
    if (error) rejectReady(error)
    else resolveReady()
  }

  const archive = new PmtilesSource(resources.url, true).p
  const readTile = archive.getZxy.bind(archive)
  archive.getZxy = async (z, x, y, signal) => {
    const request = new AbortController()
    const abort = () => request.abort()
    requests.add(request)
    signal?.addEventListener('abort', abort, { once: true })
    if (destroyed || signal?.aborted) request.abort()
    try {
      return await readTile(z, x, y, request.signal)
    } catch (reason) {
      if (!destroyed && !request.signal.aborted) {
        finish(new Error(`离线地图瓦片加载失败：${reason instanceof Error ? reason.message : '请检查本地连接'}`))
      }
      throw reason
    } finally {
      requests.delete(request)
      signal?.removeEventListener('abort', abort)
    }
  }

  const flavor = namedFlavor('dark')
  const text = {
    labelProps: ['name:zh-Hans', 'name:zh', 'name'], fontFamily: `"${resources.family}"`,
    fontSize: 12, fill: '#c6d8e7', stroke: '#101d2b', width: 2
  }
  const labels: LabelRule[] = [
    { dataLayer: 'roads', minzoom: 12, symbolizer: new LineLabelSymbolizer(text),
      filter: (zoom, feature) => zoom >= 16 || ['highway', 'major_road'].includes(String(feature.props.kind)) },
    { dataLayer: 'places', symbolizer: new CenteredTextSymbolizer(text),
      filter: (zoom, feature) => zoom >= Number(feature.props.min_zoom ?? 0) + 1 },
    { dataLayer: 'pois', minzoom: 16, symbolizer: new CenteredTextSymbolizer({ ...text, fontSize: 10 }) }
  ]
  // Read maxZoom from the archive rather than requesting nonexistent high-zoom
  // tiles. Canvas and Leaflet use the same 256px zoom as the business layer.
  void archive.getHeader().then(header => {
    if (destroyed || settled) return
    const layer = leafletLayer({
      url: archive, maxDataZoom: header.maxZoom, noWrap: true, lang: 'zh-Hans',
      paintRules: paintRules(flavor), labelRules: labels, backgroundColor: '#182c3c',
      attribution: '© <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap contributors</a> · Protomaps'
    })
    // The renderer otherwise swallows tile errors and emits a successful load
    // for an empty canvas; the archive wrapper above rejects ready first.
    layer.once('load', () => finish())
    layer.addTo(native)
  }).catch(reason => {
    if (!destroyed) finish(new Error(`离线地图数据加载失败：${reason instanceof Error ? reason.message : '请检查地图包'}`))
  })

  return {
    ready, dataVersion: resources.version, renderMode: 'canvas',
    createMarker(markerOptions) {
      const icon = (content: string) => L.divIcon({
        html: `<div class="yoox-canvas-marker-content">${content}</div>`,
        className: 'yoox-canvas-marker', iconSize: [0, 0], iconAnchor: [0, 0]
      })
      const marker = L.marker(latLng(markerOptions.position ?? options.center), {
        icon: icon(markerOptions.content), zIndexOffset: markerOptions.zIndex ?? 0,
        interactive: false, keyboard: false
      }).addTo(native)
      return {
        setPosition: value => { marker.setLatLng(latLng(value)) },
        setContent: content => { marker.setIcon(icon(content)) },
        show: () => { marker.setOpacity(1) }, hide: () => { marker.setOpacity(0) },
        remove: () => { marker.remove() }
      }
    },
    createLine(lineOptions) {
      const line = L.polyline(lineOptions.path.map(latLng), {
        color: lineOptions.strokeColor, weight: lineOptions.strokeWeight,
        opacity: lineOptions.strokeOpacity, interactive: false
      }).addTo(native)
      return { setPath: path => { line.setLatLngs(path.map(latLng)) }, remove: () => { line.remove() } }
    },
    remove: overlay => overlay.remove(),
    setCenter: value => { native.panTo(latLng(value), { animate: false }) },
    getCenter: () => position(native.getCenter()),
    getZoom: () => native.getZoom(),
    setSatellite: () => {},
    on(event: string, callback: (() => void) | ((event: { position: MapPosition }) => void)) {
      if (event === 'click') native.on('click', event => callback({ position: position(event.latlng) }))
      else native.on(event === 'zoomchange' ? 'zoomstart' : 'dragstart', () => (callback as () => void)())
    },
    destroy() {
      if (destroyed) return
      destroyed = true
      finish(new Error('地图已关闭'))
      observer.disconnect()
      for (const request of requests) request.abort()
      native.remove()
      container.classList.remove('yoox-canvas-map')
    }
  }
}
