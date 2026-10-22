import type { Device } from '../types'

// The current API returns booleans; older gateways used strings/numbers.
// A persisted aircraft binding says nothing about the gateway being online.
export function isDeviceOnline(device: Pick<Device, 'status'>): boolean {
  return device.status === true || device.status === 1 ||
    ['online', 'true', '1'].includes(String(device.status ?? '').trim().toLowerCase())
}

export function deviceCounts(devices: Device[]) {
  const unique = [...new Map(devices.map(device => [device.device_sn, device])).values()]
  return {
    total: unique.length,
    online: unique.filter(isDeviceOnline).length,
    docks: unique.filter(device => Number(device.domain) === 3).length
  }
}
