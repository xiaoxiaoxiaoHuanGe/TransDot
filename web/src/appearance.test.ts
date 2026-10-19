import { describe, expect, it } from 'vitest'
import { resolveTheme, parseThemeMode, themeOptions } from './appearance'

describe('appearance', () => {
  it('keeps the reference order dark, system, light', () => {
    expect(themeOptions.map((option) => option.mode)).toEqual(['dark', 'system', 'light'])
  })
  it('only follows the operating system in system mode', () => {
    expect(resolveTheme('light', true)).toBe('light')
    expect(resolveTheme('dark', false)).toBe('dark')
    expect(resolveTheme('system', true)).toBe('dark')
    expect(resolveTheme('system', false)).toBe('light')
  })
  it('falls back to system for invalid persisted preferences', () => {
    expect(parseThemeMode(null)).toBe('system')
    expect(parseThemeMode('invalid')).toBe('system')
    expect(parseThemeMode('dark')).toBe('dark')
  })
})
