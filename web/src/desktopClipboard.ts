export type ClipboardIncomingMessage = {
  id: string
  type: 'text' | 'image' | 'file'
  source_device_id: string
  text_content: string | null
  file?: { download_url: string, mime_type: string, status: string, size_bytes: number }
}

export type DesktopBridge = {
  enabled(): Promise<boolean>
  writeText(value: string): Promise<boolean>
  writeImage(pngDataUrl: string): Promise<boolean>
  setupInfo?(): Promise<{ serverUrl: string }>
  configure?(value: string): Promise<boolean>
}

declare global {
  interface Window { transdotDesktop?: DesktopBridge }
}

// The URL must be a server-generated, same-origin attachment URL.
function safeDownloadPath(value: string): string | null {
  try {
    const url = new URL(value, window.location.href)
    if (url.origin !== window.location.origin || !/^\/api\/v1\/files\/[^/]+\/download$/.test(url.pathname)) return null
    return url.pathname
  } catch { return null }
}

function pngFromBlob(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const image = new Image()
    const objectUrl = URL.createObjectURL(blob)
    image.onload = () => {
      try {
        if (image.naturalWidth * image.naturalHeight > 32 * 1024 * 1024) throw new Error('Image too large')
        const canvas = document.createElement('canvas')
        canvas.width = image.naturalWidth
        canvas.height = image.naturalHeight
        const context = canvas.getContext('2d')
        if (!context) throw new Error('Canvas not available')
        context.drawImage(image, 0, 0)
        resolve(canvas.toDataURL('image/png'))
      } catch (error) { reject(error) }
      finally { URL.revokeObjectURL(objectUrl) }
    }
    image.onerror = () => { URL.revokeObjectURL(objectUrl); reject(new Error('Image decode failed')) }
    image.src = objectUrl
  })
}

export function createDesktopClipboardReceiver(bridge: DesktopBridge | undefined, ownDeviceId: string) {
  let serial = 0
  const seen = new Set<string>()

  return {
    dispose: () => { serial++; seen.clear() },
    receive: (message: ClipboardIncomingMessage) => {
      if (!bridge || message.source_device_id === ownDeviceId || seen.has(message.id)) return
      if (seen.size > 512) seen.clear()
      seen.add(message.id)
      if (message.type !== 'text' && message.type !== 'image') return
      const current = ++serial
      void (async () => {
        try {
          if (!await bridge.enabled()) return
          if (message.type === 'text') {
            if (typeof message.text_content !== 'string') return
            if (current === serial) await bridge.writeText(message.text_content)
          } else if (message.file?.status === 'available') {
            const path = safeDownloadPath(message.file.download_url)
            if (!path || message.file.size_bytes > 12 * 1024 * 1024) return
            const response = await fetch(path, { credentials: 'same-origin', cache: 'no-store' })
            if (!response.ok) return
            const blob = await response.blob()
            if (blob.size > 12 * 1024 * 1024 || !blob.type.startsWith('image/')) return
            const png = await pngFromBlob(blob)
            if (current === serial) await bridge.writeImage(png)
          }
        } catch {
          // Clipboard errors must not interrupt normal messaging.
        }
      })()
    },
  }
}
