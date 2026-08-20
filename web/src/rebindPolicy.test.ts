import { describe, expect, it } from 'vitest'
import { rebindScreenForStatus } from './rebindPolicy'

describe('rebindScreenForStatus', () => {
  it('keeps waiting only for pending sessions', () => {
    expect(rebindScreenForStatus('pending')).toBe('rebind')
    expect(rebindScreenForStatus('consumed')).toBe('paired')
    expect(rebindScreenForStatus('expired')).toBe('expired')
  })
})
