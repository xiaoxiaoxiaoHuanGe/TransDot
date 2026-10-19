import { useEffect, useState } from 'react'
import { applyTheme, themeOptions, type ThemeMode } from './appearance'

export function ThemeSwitch() {
  const [mode, setMode] = useState(applyTheme)
  useEffect(() => {
    const media = matchMedia('(prefers-color-scheme: dark)')
    const refresh = () => setMode(applyTheme())
    media.addEventListener('change', refresh)
    window.addEventListener('storage', refresh)
    return () => { media.removeEventListener('change', refresh); window.removeEventListener('storage', refresh) }
  }, [])
  const select = (next: ThemeMode) => {
    setMode(next)
    try { localStorage.setItem('transdot-theme', next) } catch { /* Keep the current session usable. */ }
    const theme = next === 'system' ? matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light' : next
    document.documentElement.dataset.theme = theme
    document.documentElement.style.colorScheme = theme
    document.querySelectorAll('meta[name="theme-color"]').forEach((meta) => meta.setAttribute('content', theme === 'dark' ? '#111315' : '#f4f6f9'))
  }
  return <div className="theme-switch" role="group" aria-label="主题外观">
    {themeOptions.map((option) => <button key={option.mode} type="button" title={option.label} aria-label={option.label} aria-pressed={mode === option.mode} onClick={() => select(option.mode)}>
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d={option.path} /></svg>
    </button>)}
  </div>
}
