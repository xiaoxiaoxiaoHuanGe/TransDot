import { ChangeEvent, ClipboardEvent, DragEvent, FormEvent, KeyboardEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { QRCodeSVG } from 'qrcode.react'

type PairingStatus = 'pending' | 'approved' | 'rejected' | 'expired' | 'consumed'
type ScreenState = 'loading' | 'pairing' | 'paired' | 'rejected' | 'expired' | 'replaced' | 'error'
type ConnectionState = 'connecting' | 'connected' | 'offline'

type PairingSession = {
  session_id: string
  pairing_code: string
  qr_payload: string
  expires_at: string
  poll_interval_seconds: number
}

type AuthSession = {
  authenticated: boolean
  device_id: string
  device_type: 'windows_browser'
}

type TimelineMessage = {
  id: string
  type: 'text' | 'image' | 'file'
  batch_id: string | null
  source_device_id: string
  source_device_type: 'android_master' | 'windows_browser'
  text_content: string | null
  created_at: string
  metadata_expires_at: string | null
  file?: FileAttachment
}

type FileAttachment = {
  id: string
  original_filename: string
  mime_type: string
  size_bytes: number
  status: 'uploading' | 'available' | 'expired' | 'failed' | 'deleted'
  expires_at: string | null
  expired_reason?: 'ttl' | 'capacity'
  download_url: string
  thumbnail_url?: string
}

type UploadTicket = {
  file_id: string
  upload_id: string
  filename: string
  mime_type: string
  size_bytes: number
  kind: 'image' | 'file'
  upload_url: string
  thumbnail_upload_url?: string
}

type UploadBatch = {
  id: string
  status: string
  total_bytes: number
  reserved_bytes: number
  expires_at: string
  uploads: UploadTicket[]
}

type PendingUpload = {
  id: string
  file: File
  ticket: UploadTicket
  progress: number
  status: 'preparing' | 'uploading' | 'failed' | 'complete'
  error?: string
}

type MessagePage = {
  messages: TimelineMessage[]
  next_before?: string
}

type RealtimeEnvelope = {
  event_id: string
  type: 'message.created' | 'message.deleted' | 'device.replaced' | string
  timestamp: string
  data: unknown
}

type ErrorEnvelope = {
  error?: {
    code?: string
    message?: string
  }
}

class ApiError extends Error {
  status: number
  code?: string

  constructor(message: string, status: number, code?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    cache: 'no-store',
    credentials: 'same-origin',
    headers: {
      Accept: 'application/json',
      ...init?.headers,
    },
  })
  if (!response.ok) {
    const body = await response.json().catch(() => ({})) as ErrorEnvelope
    throw new ApiError(body.error?.message || `请求失败（HTTP ${response.status}）`, response.status, body.error?.code)
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

function isAbort(error: unknown) {
  return error instanceof DOMException && error.name === 'AbortError'
}

function formatPairingCode(code: string) {
  return `${code.slice(0, 3)} ${code.slice(3)}`
}

function mergeMessages(current: TimelineMessage[], incoming: TimelineMessage[]) {
  const byID = new Map(current.map((message) => [message.id, message]))
  for (const message of incoming) byID.set(message.id, message)
  return Array.from(byID.values()).sort((left, right) => {
    const timeDifference = new Date(left.created_at).getTime() - new Date(right.created_at).getTime()
    return timeDifference || left.id.localeCompare(right.id)
  })
}

function isTimelineMessage(value: unknown): value is TimelineMessage {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<TimelineMessage>
  return typeof candidate.id === 'string'
    && typeof candidate.created_at === 'string'
    && (candidate.type === 'text' || candidate.type === 'image' || candidate.type === 'file')
}

function uploadBinary<T>(path: string, body: Blob, contentType: string, onProgress?: (progress: number) => void): Promise<T> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('PUT', path)
    xhr.responseType = 'json'
    xhr.withCredentials = true
    xhr.setRequestHeader('Accept', 'application/json')
    xhr.setRequestHeader('Content-Type', contentType || 'application/octet-stream')
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) onProgress?.(Math.round((event.loaded / event.total) * 100))
    }
    xhr.onerror = () => reject(new ApiError('网络连接中断。', 0, 'NETWORK_ERROR'))
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve(xhr.response as T)
        return
      }
      const envelope = (xhr.response || {}) as ErrorEnvelope
      reject(new ApiError(envelope.error?.message || `上传失败（HTTP ${xhr.status}）`, xhr.status, envelope.error?.code))
    }
    xhr.send(body)
  })
}

async function createThumbnail(file: File): Promise<Blob> {
  const bitmap = await createImageBitmap(file)
  try {
    const scale = Math.min(1, 720 / Math.max(bitmap.width, bitmap.height))
    const canvas = document.createElement('canvas')
    canvas.width = Math.max(1, Math.round(bitmap.width * scale))
    canvas.height = Math.max(1, Math.round(bitmap.height * scale))
    const context = canvas.getContext('2d')
    if (!context) throw new Error('当前浏览器无法生成图片缩略图。')
    context.drawImage(bitmap, 0, 0, canvas.width, canvas.height)
    const thumbnail = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', .84))
    if (!thumbnail) throw new Error('图片缩略图生成失败。')
    return thumbnail
  } finally {
    bitmap.close()
  }
}

function uploadErrorMessage(error: unknown) {
  if (!(error instanceof ApiError)) return error instanceof Error ? error.message : '上传失败。'
  const localized: Record<string, string> = {
    FILE_TOO_LARGE: '单个文件不能超过 300 MB。',
    BATCH_TOO_LARGE: '单批文件不能超过 500 MB。',
    TOO_MANY_FILES: '一次最多选择 20 个文件。',
    INSUFFICIENT_STORAGE: '临时文件池空间不足，请稍后重试。',
    UPLOAD_EXPIRED: '上传会话已过期，请重新选择文件。',
    UPLOAD_INCOMPLETE: '文件上传不完整，请重试。',
  }
  return error.code && localized[error.code] ? localized[error.code] : error.message
}

function isPreviewableImage(file: File) {
  return ['image/jpeg', 'image/png', 'image/webp', 'image/gif', 'image/bmp'].includes(file.type.toLowerCase())
}

function App() {
  const [screen, setScreen] = useState<ScreenState>('loading')
  const [session, setSession] = useState<PairingSession | null>(null)
  const [authSession, setAuthSession] = useState<AuthSession | null>(null)
  const [errorMessage, setErrorMessage] = useState('')
  const [now, setNow] = useState(() => Date.now())

  const createSession = useCallback(async (signal?: AbortSignal) => {
    setScreen('loading')
    setErrorMessage('')
    setAuthSession(null)
    try {
      const nextSession = await request<PairingSession>('/api/v1/pairing/sessions', {
        method: 'POST',
        signal,
      })
      setSession(nextSession)
      setNow(Date.now())
      setScreen('pairing')
    } catch (error) {
      if (isAbort(error)) return
      setErrorMessage(error instanceof Error ? error.message : '无法创建配对会话。')
      setScreen('error')
    }
  }, [])

  const enterTimeline = useCallback(async (signal?: AbortSignal) => {
    const authenticated = await request<AuthSession>('/api/v1/auth/session', { signal })
    setAuthSession(authenticated)
    setScreen('paired')
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    enterTimeline(controller.signal).catch((error: unknown) => {
      if (isAbort(error)) return
      if (error instanceof ApiError && error.status === 401) {
        void createSession(controller.signal)
        return
      }
      setErrorMessage(error instanceof Error ? error.message : '无法检查浏览器状态。')
      setScreen('error')
    })
    return () => controller.abort()
  }, [createSession, enterTimeline])

  useEffect(() => {
    if (screen !== 'pairing' || !session) return
    const controller = new AbortController()
    const intervalSeconds = Math.max(1, session.poll_interval_seconds || 2)

    const poll = async () => {
      try {
        const result = await request<{ status: PairingStatus }>(
          `/api/v1/pairing/sessions/${encodeURIComponent(session.session_id)}/status`,
          { signal: controller.signal },
        )
        if (result.status === 'approved' || result.status === 'consumed') {
          await enterTimeline(controller.signal)
        } else if (result.status === 'rejected') {
          setScreen('rejected')
        } else if (result.status === 'expired') {
          setScreen('expired')
        }
      } catch (error) {
        if (isAbort(error)) return
        if (error instanceof ApiError && (error.code === 'PAIRING_EXPIRED' || error.code === 'PAIRING_INVALID')) {
          setScreen('expired')
          return
        }
        setErrorMessage(error instanceof Error ? error.message : '无法查询配对状态。')
        setScreen('error')
      }
    }

    const timer = window.setInterval(() => void poll(), intervalSeconds * 1000)
    void poll()
    return () => {
      controller.abort()
      window.clearInterval(timer)
    }
  }, [enterTimeline, screen, session])

  useEffect(() => {
    if (screen !== 'pairing') return
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [screen])

  const secondsRemaining = useMemo(() => {
    if (!session) return 0
    return Math.max(0, Math.ceil((new Date(session.expires_at).getTime() - now) / 1000))
  }, [now, session])

  useEffect(() => {
    if (screen === 'pairing' && secondsRemaining === 0) setScreen('expired')
  }, [screen, secondsRemaining])

  const handleInvalidSession = useCallback(() => {
    setAuthSession(null)
    setScreen('replaced')
  }, [])

  if (screen === 'paired' && authSession) {
    return <TimelineApp authSession={authSession} onSessionInvalid={handleInvalidSession} />
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <Brand />
        <span className={`status-pill status-pill--${screen}`}>
          <span className="status-dot" aria-hidden="true" />
          {screen === 'pairing' ? '等待手机确认' : screen === 'replaced' ? '浏览器已被替换' : '安全连接'}
        </span>
      </header>

      <main className="content">
        {screen === 'loading' && <LoadingState />}
        {screen === 'pairing' && session && (
          <PairingCard session={session} secondsRemaining={secondsRemaining} />
        )}
        {(screen === 'expired' || screen === 'rejected' || screen === 'replaced' || screen === 'error') && (
          <RetryState
            title={
              screen === 'expired' ? '配对码已过期'
                : screen === 'rejected' ? '手机已拒绝配对'
                  : screen === 'replaced' ? '这台 Windows 已被替换'
                    : '暂时无法连接'
            }
            message={
              screen === 'error' ? errorMessage
                : screen === 'replaced' ? 'Android Master 已授权另一台 Windows。重新配对会再次请求手机确认。'
                  : '生成新的二维码后，再用 Android Master 扫描确认。'
            }
            onRetry={() => void createSession()}
          />
        )}
      </main>

      <footer>
        <span>Private · Self-hosted</span>
        <span>V1 Complete</span>
      </footer>
    </div>
  )
}

function TimelineApp({ authSession, onSessionInvalid }: { authSession: AuthSession, onSessionInvalid: () => void }) {
  const [messages, setMessages] = useState<TimelineMessage[]>([])
  const [nextBefore, setNextBefore] = useState('')
  const [draft, setDraft] = useState('')
  const [initialLoading, setInitialLoading] = useState(true)
  const [loadingOlder, setLoadingOlder] = useState(false)
  const [sending, setSending] = useState(false)
  const [connection, setConnection] = useState<ConnectionState>('connecting')
  const [errorMessage, setErrorMessage] = useState('')
  const [searchOpen, setSearchOpen] = useState(false)
  const [searchQuery, setSearchQuery] = useState('')
  const [searchResults, setSearchResults] = useState<TimelineMessage[]>([])
  const [searching, setSearching] = useState(false)
  const [highlightedID, setHighlightedID] = useState('')
  const [deleteTarget, setDeleteTarget] = useState<TimelineMessage | null>(null)
  const [deleting, setDeleting] = useState(false)
  const [attachmentOpen, setAttachmentOpen] = useState(false)
  const [pendingUploads, setPendingUploads] = useState<PendingUpload[]>([])
  const [dragging, setDragging] = useState(false)
  const [viewer, setViewer] = useState<{ images: TimelineMessage[], index: number } | null>(null)
  const bottomRef = useRef<HTMLDivElement | null>(null)
  const photoInputRef = useRef<HTMLInputElement | null>(null)
  const fileInputRef = useRef<HTMLInputElement | null>(null)
  const synchronizingRef = useRef(false)
  const bufferedEventsRef = useRef<RealtimeEnvelope[]>([])

  const handleAuthError = useCallback((error: unknown) => {
    if (error instanceof ApiError && error.status === 401) {
      onSessionInvalid()
      return true
    }
    return false
  }, [onSessionInvalid])

  const applyRealtimeEvent = useCallback((event: RealtimeEnvelope) => {
    if (event.type === 'message.created' && isTimelineMessage(event.data)) {
      const createdMessage = event.data
      setMessages((current) => mergeMessages(current, [createdMessage]))
    } else if (event.type === 'message.deleted') {
      const messageID = (event.data as { message_id?: unknown })?.message_id
      if (typeof messageID === 'string') {
        setMessages((current) => current.filter((message) => message.id !== messageID))
        setSearchResults((current) => current.filter((message) => message.id !== messageID))
      }
    } else if (event.type === 'file.expired') {
      const messageID = (event.data as { message_id?: unknown })?.message_id
      if (typeof messageID === 'string') {
        const expire = (values: TimelineMessage[]) => values.map((message) => message.id === messageID && message.file
          ? { ...message, file: { ...message.file, status: 'expired' as const } }
          : message)
        setMessages(expire)
        setSearchResults(expire)
      }
    } else if (event.type === 'device.replaced') {
      onSessionInvalid()
    }
  }, [onSessionInvalid])

  const loadLatest = useCallback(async (signal?: AbortSignal) => {
    synchronizingRef.current = true
    bufferedEventsRef.current = []
    try {
      const page = await request<MessagePage>('/api/v1/messages?limit=50', { signal })
      setMessages(page.messages)
      setNextBefore(page.next_before || '')
      setErrorMessage('')
    } catch (error) {
      if (isAbort(error) || handleAuthError(error)) return
      setErrorMessage(error instanceof Error ? error.message : '无法加载时间线。')
    } finally {
      setInitialLoading(false)
      synchronizingRef.current = false
      const buffered = bufferedEventsRef.current
      bufferedEventsRef.current = []
      buffered.forEach(applyRealtimeEvent)
    }
  }, [applyRealtimeEvent, handleAuthError])

  useEffect(() => {
    const controller = new AbortController()
    void loadLatest(controller.signal)
    return () => controller.abort()
  }, [loadLatest])

  useEffect(() => {
    let disposed = false
    let socket: WebSocket | null = null
    let retryTimer = 0
    let retryAttempt = 0

    const connect = () => {
      if (disposed) return
      setConnection('connecting')
      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
      socket = new WebSocket(`${protocol}//${window.location.host}/ws`)
      socket.onopen = () => {
        retryAttempt = 0
        setConnection('connected')
        if (!synchronizingRef.current) void loadLatest()
      }
      socket.onmessage = (messageEvent) => {
        try {
          const event = JSON.parse(String(messageEvent.data)) as RealtimeEnvelope
          if (event.type === 'device.replaced') {
            disposed = true
            socket?.close()
            applyRealtimeEvent(event)
          } else if (synchronizingRef.current) {
            bufferedEventsRef.current.push(event)
          } else {
            applyRealtimeEvent(event)
          }
        } catch {
          // Unknown events are ignored; REST reconciliation remains authoritative.
        }
      }
      socket.onerror = () => setConnection('offline')
      socket.onclose = () => {
        if (disposed) return
        setConnection('offline')
        const delay = Math.min(10_000, 800 * 2 ** retryAttempt)
        retryAttempt += 1
        retryTimer = window.setTimeout(connect, delay)
      }
    }

    connect()
    return () => {
      disposed = true
      window.clearTimeout(retryTimer)
      socket?.close()
    }
  }, [applyRealtimeEvent, loadLatest])

  const loadOlder = async () => {
    if (!nextBefore || loadingOlder) return
    setLoadingOlder(true)
    try {
      const page = await request<MessagePage>(`/api/v1/messages?limit=50&before=${encodeURIComponent(nextBefore)}`)
      setMessages((current) => mergeMessages(page.messages, current))
      setNextBefore(page.next_before || '')
    } catch (error) {
      if (!handleAuthError(error)) setErrorMessage(error instanceof Error ? error.message : '无法加载更早消息。')
    } finally {
      setLoadingOlder(false)
    }
  }

  const sendMessage = async () => {
    const byteLength = new TextEncoder().encode(draft).length
    if (!draft.trim() || sending || byteLength > 100 * 1024) return
    setSending(true)
    setErrorMessage('')
    try {
      const created = await request<TimelineMessage>('/api/v1/messages/text', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json; charset=utf-8' },
        body: JSON.stringify({ text: draft }),
      })
      setMessages((current) => mergeMessages(current, [created]))
      setDraft('')
      window.requestAnimationFrame(() => bottomRef.current?.scrollIntoView({ behavior: 'smooth' }))
    } catch (error) {
      if (!handleAuthError(error)) setErrorMessage(error instanceof Error ? error.message : '发送失败。')
    } finally {
      setSending(false)
    }
  }

  const handleComposerKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      void sendMessage()
    }
  }

  const confirmDelete = async () => {
    if (!deleteTarget || deleting) return
    setDeleting(true)
    try {
      await request<void>(`/api/v1/messages/${encodeURIComponent(deleteTarget.id)}`, { method: 'DELETE' })
      setMessages((current) => current.filter((message) => message.id !== deleteTarget.id))
      setDeleteTarget(null)
    } catch (error) {
      if (!handleAuthError(error)) setErrorMessage(error instanceof Error ? error.message : '删除失败。')
    } finally {
      setDeleting(false)
    }
  }

  const submitSearch = async (event: FormEvent) => {
    event.preventDefault()
    if (!searchQuery.trim() || searching) return
    setSearching(true)
    try {
      const response = await request<{ results: TimelineMessage[] }>(`/api/v1/search?q=${encodeURIComponent(searchQuery)}`)
      setSearchResults(response.results)
    } catch (error) {
      if (!handleAuthError(error)) setErrorMessage(error instanceof Error ? error.message : '搜索失败。')
    } finally {
      setSearching(false)
    }
  }

  const locateSearchResult = async (messageID: string) => {
    try {
      const response = await request<{ target_message_id: string, messages: TimelineMessage[] }>(
        `/api/v1/messages/${encodeURIComponent(messageID)}/context`,
      )
      setMessages(response.messages)
      setNextBefore('')
      setHighlightedID(response.target_message_id)
      setSearchOpen(false)
    } catch (error) {
      if (!handleAuthError(error)) setErrorMessage(error instanceof Error ? error.message : '无法定位消息。')
    }
  }

  useEffect(() => {
    if (!highlightedID) return
    const timer = window.setTimeout(() => {
      document.getElementById(`message-${highlightedID}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
    }, 80)
    const clearTimer = window.setTimeout(() => setHighlightedID(''), 2800)
    return () => {
      window.clearTimeout(timer)
      window.clearTimeout(clearTimer)
    }
  }, [highlightedID])

  const draftBytes = new TextEncoder().encode(draft).length

  const updatePending = useCallback((id: string, update: Partial<PendingUpload>) => {
    setPendingUploads((current) => current.map((item) => item.id === id ? { ...item, ...update } : item))
  }, [])

  const performUpload = useCallback(async (pending: PendingUpload) => {
    updatePending(pending.id, { status: 'preparing', progress: 0, error: undefined })
    try {
      if (pending.ticket.kind === 'image' && pending.ticket.thumbnail_upload_url) {
        const thumbnail = await createThumbnail(pending.file)
        await uploadBinary<void>(pending.ticket.thumbnail_upload_url, thumbnail, 'image/jpeg')
      }
      updatePending(pending.id, { status: 'uploading' })
      const created = await uploadBinary<TimelineMessage>(
        pending.ticket.upload_url,
        pending.file,
        pending.file.type || 'application/octet-stream',
        (progress) => updatePending(pending.id, { progress }),
      )
      setMessages((current) => mergeMessages(current, [created]))
      updatePending(pending.id, { status: 'complete', progress: 100 })
      window.setTimeout(() => setPendingUploads((current) => current.filter((item) => item.id !== pending.id)), 650)
      window.requestAnimationFrame(() => bottomRef.current?.scrollIntoView({ behavior: 'smooth' }))
    } catch (error) {
      if (handleAuthError(error)) return
      updatePending(pending.id, { status: 'failed', error: uploadErrorMessage(error) })
    }
  }, [handleAuthError, updatePending])

  const uploadSelectedFiles = useCallback(async (selected: File[]) => {
    const files = selected.filter((file) => file.size >= 0).slice(0, 21)
    if (files.length === 0) return
    if (files.length > 20) {
      setErrorMessage('一次最多选择 20 个文件。')
      return
    }
    if (files.some((file) => file.size > 300 * 1024 * 1024)) {
      setErrorMessage('单个文件不能超过 300 MB。')
      return
    }
    if (files.reduce((total, file) => total + file.size, 0) > 500 * 1024 * 1024) {
      setErrorMessage('单批文件不能超过 500 MB。')
      return
    }
    setAttachmentOpen(false)
    setErrorMessage('')
    try {
      const batch = await request<UploadBatch>('/api/v1/upload-batches', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json; charset=utf-8' },
        body: JSON.stringify({
          items: files.map((file) => ({
            filename: file.name,
            mime_type: file.type || 'application/octet-stream',
            size_bytes: file.size,
            kind: isPreviewableImage(file) ? 'image' : 'file',
          })),
        }),
      })
      const pending = batch.uploads.map((ticket, index): PendingUpload => ({
        id: ticket.upload_id, file: files[index], ticket, progress: 0, status: 'preparing',
      }))
      setPendingUploads((current) => [...current, ...pending])
      for (const item of pending) await performUpload(item)
    } catch (error) {
      if (!handleAuthError(error)) setErrorMessage(uploadErrorMessage(error))
    }
  }, [handleAuthError, performUpload])

  const handleFileInput = (event: ChangeEvent<HTMLInputElement>) => {
    const selected = Array.from(event.target.files || [])
    event.target.value = ''
    void uploadSelectedFiles(selected)
  }

  const handlePaste = (event: ClipboardEvent<HTMLDivElement>) => {
    const images = Array.from(event.clipboardData.items)
      .filter((item) => item.kind === 'file' && item.type.startsWith('image/'))
      .map((item) => item.getAsFile())
      .filter((file): file is File => file !== null)
    if (images.length === 0) return
    event.preventDefault()
    void uploadSelectedFiles(images)
  }

  const handleDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault()
    setDragging(false)
    const files = Array.from(event.dataTransfer.files)
    if (files.length > 0) void uploadSelectedFiles(files)
  }

  const groupedMessages = useMemo(() => groupTimelineMessages(messages), [messages])

  return (
    <div
      className="timeline-shell"
      onPaste={handlePaste}
      onDragEnter={(event) => { event.preventDefault(); setDragging(true) }}
      onDragOver={(event) => event.preventDefault()}
      onDragLeave={(event) => { if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setDragging(false) }}
      onDrop={handleDrop}
    >
      <header className="timeline-topbar">
        <div className="timeline-heading">
          <Brand />
          <div>
            <h1>私人时间线</h1>
            <p>Android Master ↔ Windows</p>
          </div>
        </div>
        <div className="timeline-actions">
          <span className={`connection-badge connection-badge--${connection}`}>
            <span />{connection === 'connected' ? '实时连接' : connection === 'connecting' ? '正在连接' : '等待重连'}
          </span>
          <button className="icon-button" type="button" onClick={() => setSearchOpen(true)} aria-label="搜索消息">
            <SearchIcon />
          </button>
        </div>
      </header>

      <main className="timeline-main">
        <section className="timeline-list" aria-live="polite">
          {nextBefore && (
            <button className="load-older" type="button" onClick={() => void loadOlder()} disabled={loadingOlder}>
              {loadingOlder ? '正在加载…' : '加载更早消息'}
            </button>
          )}
          {initialLoading ? (
            <div className="timeline-placeholder"><span className="spinner" /><p>正在同步时间线</p></div>
          ) : messages.length === 0 && pendingUploads.length === 0 ? (
            <div className="timeline-placeholder timeline-placeholder--empty">
              <span className="empty-mark">↗</span>
              <h2>发送第一条内容</h2>
              <p>输入文字、选择文件，或把文件拖到此处。</p>
            </div>
          ) : (
            <div className="message-stack">
              {groupedMessages.map((group) => {
                const message = group.messages[0]
                const own = message.source_device_id === authSession.device_id
                return (
                  <article
                    id={`message-${message.id}`}
                    key={group.key}
                    className={`message-row ${group.kind === 'images' ? 'message-row--images' : ''} ${own ? 'message-row--own' : ''} ${group.messages.some((item) => highlightedID === item.id) ? 'message-row--highlighted' : ''}`}
                  >
                    <div className="message-meta">
                      <span>{message.source_device_type === 'android_master' ? 'Android' : 'Windows'}</span>
                      <time dateTime={message.created_at}>{formatMessageTime(message.created_at)}</time>
                    </div>
                    {group.kind === 'images' ? (
                      <ImageGrid
                        messages={group.messages}
                        onOpen={(index) => setViewer({ images: group.messages, index })}
                        onDelete={setDeleteTarget}
                      />
                    ) : (
                      <MessageCard message={message} onDelete={() => setDeleteTarget(message)} onOpenImage={() => setViewer({ images: [message], index: 0 })} />
                    )}
                  </article>
                )
              })}
              {pendingUploads.map((upload) => (
                <PendingUploadCard key={upload.id} upload={upload} onRetry={() => void performUpload(upload)} />
              ))}
            </div>
          )}
          <div ref={bottomRef} />
        </section>
      </main>

      <div className="composer-region">
        {errorMessage && <div className="inline-error" role="alert">{errorMessage}</div>}
        <div className="composer">
          <button className="attachment-button" type="button" onClick={() => setAttachmentOpen(true)} title="添加照片或文件">＋</button>
          <textarea
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            onKeyDown={handleComposerKeyDown}
            rows={1}
            placeholder="输入内容……"
            aria-label="消息内容"
          />
          <div className={`byte-count ${draftBytes > 100 * 1024 ? 'byte-count--error' : ''}`}>
            {draftBytes > 90 * 1024 ? `${Math.ceil(draftBytes / 1024)} / 100 KB` : ''}
          </div>
          <button
            className="send-button"
            type="button"
            onClick={() => void sendMessage()}
            disabled={!draft.trim() || sending || draftBytes > 100 * 1024}
          >
            {sending ? '发送中' : '发送'}
          </button>
        </div>
      </div>

      <input ref={photoInputRef} className="visually-hidden" type="file" accept="image/*" multiple onChange={handleFileInput} />
      <input ref={fileInputRef} className="visually-hidden" type="file" multiple onChange={handleFileInput} />

      {dragging && (
        <div className="drop-zone" aria-hidden="true">
          <div><strong>松开即可上传</strong><span>最多 20 项 · 单批 500 MB</span></div>
        </div>
      )}

      {attachmentOpen && (
        <div className="sheet-backdrop sheet-backdrop--center" role="presentation" onMouseDown={() => setAttachmentOpen(false)}>
          <div className="attachment-sheet" role="dialog" aria-modal="true" aria-label="添加内容" onMouseDown={(event) => event.stopPropagation()}>
            <div className="sheet-header"><div><p className="eyebrow">ADD CONTENT</p><h2>发送到 Android</h2></div><button className="icon-button" type="button" onClick={() => setAttachmentOpen(false)}>×</button></div>
            <div className="attachment-options">
              <button type="button" onClick={() => photoInputRef.current?.click()}><span>▧</span><strong>照片</strong><small>最多 20 张，原图不压缩</small></button>
              <button type="button" onClick={() => fileInputRef.current?.click()}><span>⌑</span><strong>文件</strong><small>单个文件最大 300 MB</small></button>
            </div>
          </div>
        </div>
      )}

      {searchOpen && (
        <div className="sheet-backdrop" role="presentation" onMouseDown={() => setSearchOpen(false)}>
          <aside className="search-sheet" role="dialog" aria-modal="true" aria-label="搜索消息" onMouseDown={(event) => event.stopPropagation()}>
            <div className="sheet-header">
              <div><p className="eyebrow">FTS5 SEARCH</p><h2>搜索消息与文件名</h2></div>
              <button className="icon-button" type="button" onClick={() => setSearchOpen(false)} aria-label="关闭搜索">×</button>
            </div>
            <form className="search-form" onSubmit={(event) => void submitSearch(event)}>
              <input value={searchQuery} onChange={(event) => setSearchQuery(event.target.value)} autoFocus placeholder="输入关键词" />
              <button type="submit" disabled={!searchQuery.trim() || searching}>{searching ? '搜索中' : '搜索'}</button>
            </form>
            <div className="search-results">
              {searchResults.length === 0 ? (
                <p className="search-empty">输入关键词搜索最多 50 条结果。</p>
              ) : searchResults.map((message) => (
                <button key={message.id} className="search-result" type="button" onClick={() => void locateSearchResult(message.id)}>
                  <span>{message.text_content || message.file?.original_filename || '文件消息'}</span>
                  <small>{message.source_device_type === 'android_master' ? 'Android' : 'Windows'} · {formatMessageTime(message.created_at)}</small>
                </button>
              ))}
            </div>
          </aside>
        </div>
      )}

      {deleteTarget && (
        <div className="sheet-backdrop sheet-backdrop--center" role="presentation">
          <div className="confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="delete-title">
            <h2 id="delete-title">删除这条消息？</h2>
            <p>{deleteTarget.type === 'text' ? '删除会同步到 Android，并从全文搜索索引中移除。' : '删除会同步移除消息、原文件和缩略图，此操作无法撤销。'}</p>
            <div>
              <button type="button" onClick={() => setDeleteTarget(null)} disabled={deleting}>取消</button>
              <button className="danger-button" type="button" onClick={() => void confirmDelete()} disabled={deleting}>{deleting ? '删除中' : '删除'}</button>
            </div>
          </div>
        </div>
      )}

      {viewer && (
        <ImageViewer
          images={viewer.images}
          index={viewer.index}
          onIndexChange={(index) => setViewer((current) => current ? { ...current, index } : null)}
          onClose={() => setViewer(null)}
        />
      )}
    </div>
  )
}

type TimelineGroup = { key: string, kind: 'single' | 'images', messages: TimelineMessage[] }

function groupTimelineMessages(messages: TimelineMessage[]): TimelineGroup[] {
  const groups: TimelineGroup[] = []
  for (const message of messages) {
    const previous = groups.at(-1)
    if (message.type === 'image' && message.batch_id && previous?.kind === 'images'
      && previous.messages[0].batch_id === message.batch_id
      && previous.messages[0].source_device_id === message.source_device_id) {
      previous.messages.push(message)
    } else {
      groups.push({
        key: message.type === 'image' && message.batch_id ? `batch-${message.batch_id}` : message.id,
        kind: message.type === 'image' ? 'images' : 'single',
        messages: [message],
      })
    }
  }
  return groups
}

function MessageCard({ message, onDelete, onOpenImage }: { message: TimelineMessage, onDelete: () => void, onOpenImage: () => void }) {
  if (message.type === 'text') {
    return <div className="message-bubble"><p>{message.text_content}</p><button type="button" onClick={onDelete}>删除</button></div>
  }
  if (message.type === 'image') {
    return <ImageGrid messages={[message]} onOpen={onOpenImage} onDelete={() => onDelete()} />
  }
  const file = message.file
  return (
    <div className="file-card">
      <span className="file-icon">{fileExtension(file?.original_filename || '')}</span>
      <div className="file-copy"><strong title={file?.original_filename}>{file?.original_filename || '文件'}</strong><span>{formatBytes(file?.size_bytes || 0)} · {fileStatusLabel(file)}</span></div>
      {file?.status === 'available' ? <a className="file-action" href={file.download_url} download>下载</a> : <span className="file-action file-action--disabled">已过期</span>}
      <button className="card-delete" type="button" onClick={onDelete} aria-label="删除文件消息">删除</button>
    </div>
  )
}

function ImageGrid({ messages, onOpen, onDelete }: { messages: TimelineMessage[], onOpen: (index: number) => void, onDelete: (message: TimelineMessage) => void }) {
  const visible = messages.slice(0, 6)
  return (
    <div className={`image-grid image-grid--${Math.min(messages.length, 5)}`}>
      {visible.map((message, index) => {
        const file = message.file
        const expired = file?.status !== 'available'
        return (
          <div className="image-tile" key={message.id}>
            <button type="button" onClick={() => onOpen(index)} disabled={!file?.thumbnail_url && expired}>
              {file?.thumbnail_url ? <img src={file.thumbnail_url} alt={file.original_filename} loading="lazy" /> : <span className="image-placeholder">IMG</span>}
              {expired && <span className="image-expired">原图已过期</span>}
              {index === 5 && messages.length > 6 && <span className="image-more">+{messages.length - 5}</span>}
            </button>
            <button className="image-delete" type="button" onClick={() => onDelete(message)} aria-label={`删除 ${file?.original_filename || '图片'}`}>×</button>
          </div>
        )
      })}
    </div>
  )
}

function PendingUploadCard({ upload, onRetry }: { upload: PendingUpload, onRetry: () => void }) {
  return (
    <article className="pending-card">
      <div className="pending-head"><span className="file-icon">{upload.ticket.kind === 'image' ? 'IMG' : fileExtension(upload.file.name)}</span><div><strong>{upload.file.name}</strong><span>{formatBytes(upload.file.size)}</span></div></div>
      <div className="upload-progress"><span style={{ width: `${upload.progress}%` }} /></div>
      <div className="pending-state"><span>{upload.status === 'preparing' ? '准备缩略图…' : upload.status === 'uploading' ? `上传中 ${upload.progress}%` : upload.status === 'complete' ? '上传完成' : upload.error || '上传失败'}</span>{upload.status === 'failed' && <button type="button" onClick={onRetry}>重试</button>}</div>
    </article>
  )
}

function ImageViewer({ images, index, onIndexChange, onClose }: { images: TimelineMessage[], index: number, onIndexChange: (index: number) => void, onClose: () => void }) {
  const message = images[index]
  const file = message.file
  useEffect(() => {
    const onKey = (event: globalThis.KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
      if (event.key === 'ArrowLeft') onIndexChange(Math.max(0, index - 1))
      if (event.key === 'ArrowRight') onIndexChange(Math.min(images.length - 1, index + 1))
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [images.length, index, onClose, onIndexChange])
  return (
    <div className="viewer" role="dialog" aria-modal="true" aria-label="图片查看器">
      <header><button type="button" onClick={onClose}>← 返回</button><span>{index + 1} / {images.length}</span>{file?.status === 'available' ? <a href={file.download_url} download>下载原图</a> : <span>原图已过期</span>}</header>
      <div className="viewer-stage">
        {file?.status === 'available' ? <img src={file.download_url} alt={file.original_filename} /> : file?.thumbnail_url ? <img className="viewer-expired" src={file.thumbnail_url} alt={file.original_filename} /> : <p>图片已不可用</p>}
      </div>
      {images.length > 1 && <><button className="viewer-nav viewer-nav--left" type="button" disabled={index === 0} onClick={() => onIndexChange(index - 1)}>‹</button><button className="viewer-nav viewer-nav--right" type="button" disabled={index === images.length - 1} onClick={() => onIndexChange(index + 1)}>›</button></>}
      <footer>{file?.original_filename}</footer>
    </div>
  )
}

function fileExtension(filename: string) {
  const extension = filename.split('.').at(-1)?.toUpperCase() || 'FILE'
  return extension.length <= 5 ? extension : 'FILE'
}

function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 ** 2).toFixed(1)} MB`
}

function fileStatusLabel(file?: FileAttachment) {
  if (!file || file.status !== 'available') return '已过期'
  if (!file.expires_at) return '可下载'
  const hours = Math.max(0, Math.ceil((new Date(file.expires_at).getTime() - Date.now()) / 3_600_000))
  return `${hours} 小时后过期`
}

function Brand() {
  return (
    <a className="brand" href="/" aria-label="私人文件传输助手首页">
      <span className="brand-mark" aria-hidden="true">
        <svg viewBox="0 0 24 24"><path d="M7.5 8.25 12 3.75l4.5 4.5M12 4.5v10.25M5.5 13.25v4.5A2.25 2.25 0 0 0 7.75 20h8.5a2.25 2.25 0 0 0 2.25-2.25v-4.5" /></svg>
      </span>
      <span>传输助手</span>
    </a>
  )
}

function SearchIcon() {
  return <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="10.8" cy="10.8" r="6.3" /><path d="m15.5 15.5 4.2 4.2" /></svg>
}

function formatMessageTime(value: string) {
  const date = new Date(value)
  const today = new Date()
  const sameDay = date.toDateString() === today.toDateString()
  return new Intl.DateTimeFormat('zh-CN', sameDay
    ? { hour: '2-digit', minute: '2-digit' }
    : { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(date)
}

function LoadingState() {
  return (
    <section className="state-panel state-panel--center" aria-live="polite">
      <span className="spinner" aria-hidden="true" />
      <p className="eyebrow">SECURE SESSION</p>
      <h1>正在准备安全连接</h1>
      <p>检查浏览器凭据并同步私人时间线。</p>
    </section>
  )
}

function PairingCard({ session, secondsRemaining }: { session: PairingSession, secondsRemaining: number }) {
  return (
    <section className="pairing-layout" aria-labelledby="pairing-title">
      <div className="pairing-copy">
        <p className="eyebrow">WINDOWS PAIRING</p>
        <h1 id="pairing-title">用手机确认这台电脑</h1>
        <p className="lead">打开 Android Master，点击“配对 Windows”并扫描二维码。二维码只在本次会话中有效。</p>
        <ol className="steps">
          <li><span>1</span>打开手机上的传输助手</li>
          <li><span>2</span>点击“配对 Windows”</li>
          <li><span>3</span>扫描右侧二维码并确认</li>
        </ol>
        <div className="privacy-note">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3 5.5 5.8v5.7c0 4.2 2.7 7.6 6.5 9.1 3.8-1.5 6.5-4.9 6.5-9.1V5.8L12 3Z" /><path d="m9.2 12 1.8 1.8 3.8-4" /></svg>
          Browser Token 只写入 HttpOnly Cookie，不会进入 localStorage。
        </div>
      </div>

      <div className="qr-card">
        <div className="qr-frame" aria-label="Windows 配对二维码">
          <QRCodeSVG value={session.qr_payload} size={232} level="M" marginSize={2} bgColor="#ffffff" fgColor="#14171c" />
        </div>
        <div className="manual-code">
          <span>无法扫码？输入 6 位码</span>
          <strong aria-label={`配对码 ${session.pairing_code.split('').join(' ')}`}>{formatPairingCode(session.pairing_code)}</strong>
        </div>
        <div className="expiry" aria-live="polite">
          <span className="expiry-line"><span style={{ width: `${Math.min(100, secondsRemaining / 1.2)}%` }} /></span>
          <span>{secondsRemaining} 秒后失效</span>
        </div>
      </div>
    </section>
  )
}

function RetryState({ title, message, onRetry }: { title: string, message: string, onRetry: () => void }) {
  return (
    <section className="state-panel state-panel--center">
      <span className="retry-mark" aria-hidden="true">↻</span>
      <p className="eyebrow">SECURE SESSION</p>
      <h1>{title}</h1>
      <p>{message}</p>
      <button className="primary-button" type="button" onClick={onRetry}>生成新的配对码</button>
    </section>
  )
}

export default App
