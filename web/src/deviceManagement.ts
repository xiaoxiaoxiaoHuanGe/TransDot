export const defaultDeviceName = '浏览器设备'
export const deviceNameStorageKey = 'transdot.browser_device_name_v1'

type NavigatorSummary = {
  userAgent?: string
  userAgentData?: {
    brands?: Array<{ brand: string }>
    platform?: string
  }
}

export function normalizeDeviceName(value: string): string {
  const normalized = value.trim()
  if (!normalized || Array.from(normalized).length > 64 || /\p{Cc}/u.test(normalized)) {
    throw new Error('设备名称必须为 1 至 64 个字符，且不能包含控制字符。')
  }
  return normalized
}

export function defaultBrowserDeviceName(summary: NavigatorSummary): string {
  const brands = summary.userAgentData?.brands?.map((entry) => entry.brand).filter((brand) => !/not.?a.?brand/i.test(brand)) ?? []
  const userAgent = summary.userAgent ?? ''
  const browser = brands.find((brand) => /google chrome/i.test(brand)) ? 'Chrome'
    : brands.find((brand) => /microsoft edge/i.test(brand)) ? 'Edge'
      : brands.find((brand) => /firefox/i.test(brand)) ? 'Firefox'
        : /Edg\//.test(userAgent) ? 'Edge'
          : /Firefox\//.test(userAgent) ? 'Firefox'
            : /Chrome\//.test(userAgent) ? 'Chrome'
              : /Safari\//.test(userAgent) ? 'Safari'
                : ''
  const rawPlatform = summary.userAgentData?.platform ?? userAgent
  const platform = /Windows/i.test(rawPlatform) ? 'Windows'
    : /Android/i.test(rawPlatform) ? 'Android'
      : /iPhone|iPad|iOS/i.test(rawPlatform) ? 'iOS'
        : /Mac/i.test(rawPlatform) ? 'macOS'
          : /Linux/i.test(rawPlatform) ? 'Linux'
            : ''
  return browser && platform ? `${browser} · ${platform}` : browser || defaultDeviceName
}

export function sourceDeviceLabel(message: {
  source_device_id: string
  source_device_type: 'android_master' | 'windows_browser'
  source_device_name?: string
}, currentDeviceID: string): string {
  const fallback = message.source_device_type === 'android_master' ? 'Android' : 'Windows'
  const name = message.source_device_name?.trim() || fallback
  return message.source_device_id === currentDeviceID ? `我 · ${name}` : name
}

export function applyDeviceDisplayName<T extends { source_device_id: string, source_device_name?: string }>(
  messages: T[],
  deviceID: string,
  displayName: string,
): T[] {
  return messages.map((message) => message.source_device_id === deviceID
    ? { ...message, source_device_name: displayName }
    : message)
}

export function isRevocationEvent(eventType: string, data: unknown, currentDeviceID: string): boolean {
  if (eventType === 'device.replaced') return true
  if (eventType !== 'device.revoked' || typeof data !== 'object' || data === null) return false
  return (data as { id?: unknown }).id === currentDeviceID
}

export function pairingFailureMessage(code?: string): string | undefined {
  if (code === 'BROWSER_LIMIT_REACHED') return '已达到浏览器上限，请先在 Android 的“已授权浏览器”中撤销一台设备。'
  return undefined
}
