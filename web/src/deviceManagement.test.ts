import { describe, expect, it } from 'vitest'
import {
  defaultBrowserDeviceName,
  applyDeviceDisplayName,
  isRevocationEvent,
  normalizeDeviceName,
  pairingFailureMessage,
  sourceDeviceLabel,
} from './deviceManagement'

describe('device naming', () => {
  it('normalizes valid Unicode names and rejects unsafe boundaries', () => {
    expect(normalizeDeviceName('  书房电脑  ')).toBe('书房电脑')
    expect(() => normalizeDeviceName('')).toThrow()
    expect(() => normalizeDeviceName('bad\nname')).toThrow()
    expect(() => normalizeDeviceName('界'.repeat(65))).toThrow()
  })

  it('generates a conservative browser and platform default', () => {
    expect(defaultBrowserDeviceName({
      userAgentData: { brands: [{ brand: 'Chromium' }, { brand: 'Google Chrome' }], platform: 'Windows' },
      userAgent: '',
    })).toBe('Chrome · Windows')
    expect(defaultBrowserDeviceName({ userAgent: '' })).toBe('浏览器设备')
  })
})

describe('multi-browser compatibility policies', () => {
  it('labels timeline sources using the device name with legacy fallback', () => {
    expect(sourceDeviceLabel({ source_device_id: 'self', source_device_type: 'windows_browser', source_device_name: 'Office' }, 'self')).toBe('我 · Office')
    expect(sourceDeviceLabel({ source_device_id: 'other', source_device_type: 'windows_browser' }, 'self')).toBe('Windows')
    expect(sourceDeviceLabel({ source_device_id: 'phone', source_device_type: 'android_master', source_device_name: 'Android Master' }, 'self')).toBe('Android Master')
  })

  it('updates historical source labels after a device rename event', () => {
    const messages = [
      { source_device_id: 'renamed', source_device_name: 'Old' },
      { source_device_id: 'other', source_device_name: 'Other' },
    ]
    expect(applyDeviceDisplayName(messages, 'renamed', 'New')).toEqual([
      { source_device_id: 'renamed', source_device_name: 'New' },
      messages[1],
    ])
  })

  it('only invalidates the browser targeted by a new revocation event', () => {
    expect(isRevocationEvent('device.revoked', { id: 'self' }, 'self')).toBe(true)
    expect(isRevocationEvent('device.revoked', { id: 'other' }, 'self')).toBe(false)
    expect(isRevocationEvent('device.replaced', {}, 'self')).toBe(true)
    expect(isRevocationEvent('device.updated', { id: 'self' }, 'self')).toBe(false)
  })

  it('explains that capacity must be managed on Android', () => {
    expect(pairingFailureMessage('BROWSER_LIMIT_REACHED')).toContain('Android')
  })
})
