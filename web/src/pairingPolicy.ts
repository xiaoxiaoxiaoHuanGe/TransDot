export type PairingLocation = Pick<Location, 'protocol' | 'hostname' | 'port'>

const LOOPBACK_HOSTS = new Set(['localhost', '127.0.0.1', '::1'])

export function pairingTransportGuidance(location: PairingLocation) {
  if (location.protocol === 'https:' || LOOPBACK_HOSTS.has(location.hostname.toLowerCase())) return null
  const port = location.port ? `:${location.port}` : ''
  return `当前 HTTP 地址无法保存安全配对凭据。同一台电脑请打开 http://localhost${port}；其他设备请配置 HTTPS。`
}

export function parseRetryAfterSeconds(value: string | null, fallback = 120) {
  const seconds = Number.parseInt(value ?? '', 10)
  return Number.isFinite(seconds) ? Math.max(1, seconds) : fallback
}

export function instanceHostLabel(host: string) {
  return host.trim() || '当前服务器'
}
