import { gcj02ToWgs84, wgs84ToGcj02 } from './geo'

export type MapProvider = 'offline' | 'amap'
export type MapPosition = [number, number]

// The business layer always uses WGS84, including click targets and saved trails.
export function toProviderPosition(provider: MapProvider, position: MapPosition): MapPosition {
  return provider === 'amap' ? wgs84ToGcj02(...position) : [...position]
}

export function fromProviderPosition(provider: MapProvider, position: MapPosition): MapPosition {
  return provider === 'amap' ? gcj02ToWgs84(...position) : [...position]
}

// Use AMap's 256-pixel zoom convention for saved viewports. MapLibre's world
// uses 512 pixels at zoom zero, so the same ground scale is one level lower.
export function toProviderZoom(provider: MapProvider, zoom: number): number {
  return provider === 'offline' ? zoom - 1 : zoom
}

export function fromProviderZoom(provider: MapProvider, zoom: number): number {
  return provider === 'offline' ? zoom + 1 : zoom
}
