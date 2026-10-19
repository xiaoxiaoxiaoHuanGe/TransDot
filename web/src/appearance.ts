export type ThemeMode = 'dark' | 'system' | 'light'
export const themeOptions: { mode: ThemeMode, label: string, path: string }[] = [
  { mode: 'dark', label: '深色', path: 'M20.5 13A8.5 8.5 0 0 1 11 3.5 8.5 8.5 0 1 0 20.5 13Z' },
  { mode: 'system', label: '跟随系统', path: 'M4 4h16v12H4z M8 20h8 M12 16v4' },
  { mode: 'light', label: '浅色', path: 'M12 2v2 M12 20v2 M2 12h2 M20 12h2 M5 5l1.5 1.5 M17.5 17.5 19 19 M5 19l1.5-1.5 M17.5 6.5 19 5 M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0' },
]
export function parseThemeMode(value: string | null): ThemeMode {
  return value === 'dark' || value === 'light' ? value : 'system'
}
export function resolveTheme(mode: ThemeMode, systemDark: boolean) {
  return mode === 'system' ? systemDark ? 'dark' : 'light' : mode
}
export function applyTheme() {
  let mode: ThemeMode = 'system'
  try { mode = parseThemeMode(localStorage.getItem('transdot-theme')) } catch { /* Storage may be disabled. */ }
  const theme = resolveTheme(mode, matchMedia('(prefers-color-scheme: dark)').matches)
  document.documentElement.dataset.theme = theme
  document.documentElement.style.colorScheme = theme
  document.querySelectorAll('meta[name="theme-color"]').forEach((meta) => meta.setAttribute('content', theme === 'dark' ? '#111315' : '#f4f6f9'))
  return mode
}
