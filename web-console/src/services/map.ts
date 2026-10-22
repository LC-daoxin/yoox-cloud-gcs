import { loadAMap } from './amap'
import { fromProviderPosition, toProviderPosition, type MapPosition, type MapProvider } from './map-coordinates'

export type { MapPosition, MapProvider } from './map-coordinates'

export interface MapMarker {
  setPosition(position: MapPosition): void
  setContent(content: string): void
  show(): void
  hide(): void
  remove(): void
}

export interface MapLine {
  setPath(path: MapPosition[]): void
  remove(): void
}

export interface MarkerOptions {
  position?: MapPosition
  content: string
  zIndex?: number
  anchor?: string
  rotateWithMap?: boolean
}

export interface LineOptions {
  path: MapPosition[]
  strokeColor: string
  strokeWeight: number
  strokeOpacity: number
  zIndex?: number
}

export interface MapOptions {
  center: MapPosition
  zoom: number
  satellite: boolean
}

export interface GcsMap {
  ready: Promise<void>
  dataVersion?: string
  renderMode?: 'webgl' | 'canvas'
  createMarker(options: MarkerOptions): MapMarker
  createLine(options: LineOptions): MapLine
  remove(overlay: MapMarker | MapLine): void
  setCenter(position: MapPosition): void
  getCenter(): MapPosition
  getZoom(): number
  setSatellite(satellite: boolean): void
  on(event: 'dragstart' | 'zoomchange', callback: () => void): void
  on(event: 'click', callback: (event: { position: MapPosition }) => void): void
  destroy(): void
}

export type MapFactory = (container: HTMLElement, options: MapOptions) => GcsMap | Promise<GcsMap>

export function defaultMapProvider(): MapProvider {
  return window.YOOX_RUNTIME?.mapProvider === 'amap' ? 'amap' : 'offline'
}

// Keep a user's choice while navigating, but always start a new page load in the
// deployment's configured mode. A previous online session must not break a cold
// start at an offline site.
let preferredProvider: MapProvider | undefined
export function getMapProvider(): MapProvider {
  return preferredProvider ?? defaultMapProvider()
}
export function rememberMapProvider(provider: MapProvider) {
  preferredProvider = provider
}

export async function loadMapFactory(provider: MapProvider): Promise<MapFactory> {
  if (provider === 'offline') {
    const { loadOfflineMapFactory } = await import('./offline-map')
    return loadOfflineMapFactory()
  }
  const AMap = await loadAMap()
  return (container, options) => {
    const native = new AMap.Map(container, {
      center: toProviderPosition('amap', options.center),
      zoom: options.zoom,
      viewMode: '2D',
      layers: options.satellite
        ? [new AMap.TileLayer.Satellite(), new AMap.TileLayer.RoadNet()]
        : [new AMap.TileLayer()],
      mapStyle: options.satellite ? undefined : 'amap://styles/darkblue'
    })
    const observer = new ResizeObserver(() => native.resize?.())
    observer.observe(container)
    let destroyed = false
    return {
      ready: Promise.resolve(),
      createMarker(markerOptions) {
        const { rotateWithMap: _rotateWithMap, ...nativeOptions } = markerOptions
        const marker = new AMap.Marker({
          ...nativeOptions,
          position: toProviderPosition('amap', markerOptions.position ?? options.center)
        })
        native.add(marker)
        return {
          setPosition: position => marker.setPosition(toProviderPosition('amap', position)),
          setContent: content => marker.setContent(content),
          show: () => marker.show(),
          hide: () => marker.hide(),
          remove: () => native.remove(marker)
        }
      },
      createLine(lineOptions) {
        const line = new AMap.Polyline({
          ...lineOptions,
          path: lineOptions.path.map(position => toProviderPosition('amap', position))
        })
        native.add(line)
        return {
          setPath: path => line.setPath(path.map(position => toProviderPosition('amap', position))),
          remove: () => native.remove(line)
        }
      },
      remove: overlay => overlay.remove(),
      setCenter: position => native.setCenter(toProviderPosition('amap', position)),
      getCenter: () => {
        const position = native.getCenter()
        return fromProviderPosition('amap', [position.getLng(), position.getLat()])
      },
      getZoom: () => native.getZoom(),
      setSatellite: satellite => {
        native.setLayers(satellite
          ? [new AMap.TileLayer.Satellite(), new AMap.TileLayer.RoadNet()]
          : [new AMap.TileLayer()])
        if (!satellite) native.setMapStyle('amap://styles/darkblue')
      },
      on(event: string, callback: (event?: any) => void) {
        native.on(event, event === 'click'
          ? (event: any) => {
            const lng = Number(event.lnglat?.getLng?.() ?? event.lnglat?.lng)
            const lat = Number(event.lnglat?.getLat?.() ?? event.lnglat?.lat)
            if (Number.isFinite(lng) && Number.isFinite(lat)) {
              callback({ position: fromProviderPosition('amap', [lng, lat]) })
            }
          }
          : callback)
      },
      destroy() {
        if (destroyed) return
        destroyed = true
        observer.disconnect()
        native.destroy()
      }
    }
  }
}
