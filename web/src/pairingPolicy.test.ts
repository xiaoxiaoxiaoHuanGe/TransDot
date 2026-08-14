import { describe, expect, it } from 'vitest'
import { instanceHostLabel, pairingTransportGuidance, parseRetryAfterSeconds } from './pairingPolicy'

describe('pairingTransportGuidance', () => {
  it.each([
    ['https:', 'file.example.com'],
    ['http:', 'localhost'],
    ['http:', '127.0.0.1'],
    ['http:', '::1'],
    ['http:', '[::1]'],
  ])('allows %s//%s', (protocol, hostname) => {
    expect(pairingTransportGuidance({ protocol, hostname, port: '5758' })).toBeNull()
  })

  it('blocks LAN HTTP with localhost and HTTPS guidance', () => {
    const guidance = pairingTransportGuidance({ protocol: 'http:', hostname: '192.168.137.47', port: '5758' })
    expect(guidance).toContain('http://localhost:5758')
    expect(guidance).toContain('HTTPS')
  })
})

describe('parseRetryAfterSeconds', () => {
  it('normalizes numeric and invalid values', () => {
    expect(parseRetryAfterSeconds('37')).toBe(37)
    expect(parseRetryAfterSeconds(null)).toBe(120)
    expect(parseRetryAfterSeconds('soon')).toBe(120)
    expect(parseRetryAfterSeconds('0')).toBe(1)
  })
})

describe('instanceHostLabel', () => {
  it('keeps the current host and supplies a fallback', () => {
    expect(instanceHostLabel('localhost:5758')).toBe('localhost:5758')
    expect(instanceHostLabel('  ')).toBe('当前服务器')
  })
})
