import { describe, expect, it } from 'vitest'
import { deviceCounts, isDeviceOnline } from '../src/services/device-status'
import type { Device } from '../src/types'

describe('device topology status', () => {
  it.each([true, 1, '1', 'online', 'ONLINE', ' Online ', 'true'])('recognizes online status %s', status => {
    expect(isDeviceOnline({ status })).toBe(true)
  })
  it.each([false, 0, '0', 'offline', 'OFFLINE', 'false', undefined, ''])('rejects offline/unknown status %s', status => {
    expect(isDeviceOnline({ status })).toBe(false)
  })
  it('counts the API gateway rows once and does not mistake remote controllers for docks', () => {
    const devices: Device[] = ['A', 'B'].map(sn => ({
      device_sn: `rc-${sn}`, domain: 2, status: true, child_device_sn: `air-${sn}`,
      aircraft_sn: `air-${sn}`, children: { device_sn: `air-${sn}`, domain: 0, status: true }
    }))
    expect(deviceCounts(devices)).toEqual({ total: 2, online: 2, docks: 0 })
    expect(deviceCounts([...devices, devices[0]])).toEqual({ total: 2, online: 2, docks: 0 })
  })
  it('never treats a saved child binding as proof of being online', () => {
    expect(deviceCounts([
      { device_sn: 'rc', domain: 2, status: false, child_sn: 'air' },
      { device_sn: 'dock', domain: 3, status: '1' }
    ])).toEqual({ total: 2, online: 1, docks: 1 })
  })
})
