import { ChangeEvent, ClipboardEvent, DragEvent, FormEvent, KeyboardEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { QRCodeSVG } from 'qrcode.react'
import { LanTransferView } from './lan/LanTransferView'
import { instanceHostLabel, pairingTransportGuidance, parseRetryAfterSeconds, unauthenticatedFlow } from './pairingPolicy'
import { RebindStatus, rebindScreenForStatus } from './rebindPolicy'
import { DownloadDirectory, downloadFilesToDirectory, fileFingerprint, partitionRepeatedFiles } from './transferTools'

type PairingStatus = 'pending' | 'approved' | 'rejected' | 'expired' | 'consumed'
type ScreenState = 'loading' | 'bootstrap' | 'pairing' | 'rebind' | 'paired' | 'rejected' | 'expired' | 'replaced' | 'insecure' | 'error'
type ConnectionState = 'connecting' | 'connected' | 'offline'

type PairingSession = {
  session_id: string
  pairing_code: string
  qr_payload: string
  expires_at: string
  poll_interval_seconds: number
}

type BootstrapSession = {
  session_id: string
  qr_payload: string
  instance_fingerprint: string
  expires_at: string
  poll_interval_seconds: number
}

type RebindSession = {
  session_id: string
  qr_payload: string
  expires_at: string
  poll_interval_seconds: number
}

type InstanceInfo = {
  instance_id: string
  instance_fingerprint: string
  initialized: boolean
  public_url: string
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
  retryAfterSeconds?: number

  constructor(message: string, status: number, code?: string, retryAfterSeconds?: number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.retryAfterSeconds = retryAfterSeconds
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
    const retryAfterSeconds = response.status === httpStatusTooManyRequests
      ? parseRetryAfterSeconds(response.headers.get('Retry-After'))
      : undefined
    throw new ApiError(body.error?.message || `请求失败（HTTP ${response.status}）`, response.status, body.error?.code, retryAfterSeconds)
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

const httpStatusTooManyRequests = 429

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
  const [bootstrapSession, setBootstrapSession] = useState<BootstrapSession | null>(null)
  const [rebindSession, setRebindSession] = useState<RebindSession | null>(null)
  const [authSession, setAuthSession] = useState<AuthSession | null>(null)
  const [errorMessage, setErrorMessage] = useState('')
  const [retryAfterSeconds, setRetryAfterSeconds] = useState(0)
  const [retryBootstrap, setRetryBootstrap] = useState(false)
  const [retryRebind, setRetryRebind] = useState(false)
  const [now, setNow] = useState(() => Date.now())
  const creatingSessionRef = useRef(false)

  const createBootstrapSession = useCallback(async (signal?: AbortSignal) => {
    setRetryRebind(false)
    setRetryBootstrap(true)
    if (creatingSessionRef.current) return
    const transportGuidance = pairingTransportGuidance(window.location)
    if (transportGuidance) { setErrorMessage(transportGuidance); setScreen('insecure'); return }
    creatingSessionRef.current = true
    setScreen('loading')
    setErrorMessage('')
    try {
      const created = await request<BootstrapSession>('/api/v1/bootstrap/sessions', { method: 'POST', signal })
      setBootstrapSession(created)
      setSession(null)
      setNow(Date.now())
      setScreen('bootstrap')
    } catch (error) {
      if (!isAbort(error)) {
        if (error instanceof ApiError && error.code === 'ALREADY_INITIALIZED') setRetryBootstrap(false)
        setRetryAfterSeconds(error instanceof ApiError && error.status === httpStatusTooManyRequests ? error.retryAfterSeconds ?? 120 : 0)
        setErrorMessage(error instanceof Error ? error.message : '无法创建手机绑定会话。'); setScreen('error')
      }
    } finally { creatingSessionRef.current = false }
  }, [])

  const createSession = useCallback(async (signal?: AbortSignal) => {
    setRetryRebind(false)
    setRetryBootstrap(false)
    if (creatingSessionRef.current) return
    const transportGuidance = pairingTransportGuidance(window.location)
    if (transportGuidance) {
      setSession(null)
      setAuthSession(null)
      setRetryAfterSeconds(0)
      setErrorMessage(transportGuidance)
      setScreen('insecure')
      return
    }

    creatingSessionRef.current = true
    setScreen('loading')
    setErrorMessage('')
    setRetryAfterSeconds(0)
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
      setRetryAfterSeconds(error instanceof ApiError && error.status === httpStatusTooManyRequests
        ? error.retryAfterSeconds ?? 120
        : 0)
      setScreen('error')
    } finally {
      creatingSessionRef.current = false
    }
  }, [])

  const enterTimeline = useCallback(async (signal?: AbortSignal) => {
    const authenticated = await request<AuthSession>('/api/v1/auth/session', { signal })
    setAuthSession(authenticated)
    setScreen('paired')
  }, [])

  const createRebindSession = useCallback(async (signal?: AbortSignal) => {
    setRetryRebind(true)
    if (creatingSessionRef.current) return
    const transportGuidance = pairingTransportGuidance(window.location)
    if (transportGuidance) { setErrorMessage(transportGuidance); setScreen('insecure'); return }
    creatingSessionRef.current = true
    setScreen('loading')
    setErrorMessage('')
    try {
      const created = await request<RebindSession>('/api/v1/rebind/sessions', { method: 'POST', signal })
      setRebindSession(created)
      setNow(Date.now())
      setScreen('rebind')
    } catch (error) {
      if (!isAbort(error)) { setErrorMessage(error instanceof Error ? error.message : '无法创建手机重绑定会话。'); setScreen('error') }
    } finally {
      creatingSessionRef.current = false
    }
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    enterTimeline(controller.signal).catch((error: unknown) => {
      if (isAbort(error)) return
      if (error instanceof ApiError && error.status === 401) {
        request<InstanceInfo>('/api/v1/instance/info', { signal: controller.signal })
          .then((info) => unauthenticatedFlow(info.initialized) === 'bootstrap'
            ? createBootstrapSession(controller.signal)
            : createSession(controller.signal))
          .catch((failure) => { if (!isAbort(failure)) { setErrorMessage(failure instanceof Error ? failure.message : '无法读取服务器状态。'); setScreen('error') } })
        return
      }
      setErrorMessage(error instanceof Error ? error.message : '无法检查浏览器状态。')
      setScreen('error')
    })
    return () => controller.abort()
  }, [createBootstrapSession, createSession, enterTimeline])

  useEffect(() => {
    if (screen !== 'bootstrap' || !bootstrapSession) return
    const controller = new AbortController()
    const poll = async () => {
      try {
        const result = await request<{ status: PairingStatus }>(`/api/v1/bootstrap/sessions/${encodeURIComponent(bootstrapSession.session_id)}/status`, { signal: controller.signal })
        if (result.status === 'approved' || result.status === 'consumed') await enterTimeline(controller.signal)
        else if (result.status === 'expired') setScreen('expired')
      } catch (error) {
        if (!isAbort(error)) { setErrorMessage(error instanceof Error ? error.message : '无法查询手机绑定状态。'); setScreen('error') }
      }
    }
    const timer = window.setInterval(() => void poll(), Math.max(1, bootstrapSession.poll_interval_seconds) * 1000)
    void poll()
    return () => { controller.abort(); window.clearInterval(timer) }
  }, [bootstrapSession, enterTimeline, screen])

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
    if (screen !== 'rebind' || !rebindSession) return
    const controller = new AbortController()
    const poll = async () => {
      try {
        const result = await request<{ status: RebindStatus }>(`/api/v1/rebind/sessions/${encodeURIComponent(rebindSession.session_id)}/status`, { signal: controller.signal })
        const next = rebindScreenForStatus(result.status)
        if (next === 'paired') { setRetryRebind(false); setRebindSession(null); await enterTimeline(controller.signal) }
        else if (next === 'expired') setScreen('expired')
      } catch (error) {
        if (!isAbort(error)) { setErrorMessage(error instanceof Error ? error.message : '无法查询手机重绑定状态。'); setScreen('error') }
      }
    }
    const timer = window.setInterval(() => void poll(), Math.max(1, rebindSession.poll_interval_seconds) * 1000)
    void poll()
    return () => { controller.abort(); window.clearInterval(timer) }
  }, [enterTimeline, rebindSession, screen])

  useEffect(() => {
    if (screen !== 'pairing' && screen !== 'bootstrap' && screen !== 'rebind') return
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [screen])

  useEffect(() => {
    if (retryAfterSeconds <= 0) return
    const timer = window.setInterval(() => {
      setRetryAfterSeconds((current) => Math.max(0, current - 1))
    }, 1000)
    return () => window.clearInterval(timer)
  }, [retryAfterSeconds > 0])

  const secondsRemaining = useMemo(() => {
    const expiry = screen === 'bootstrap' ? bootstrapSession?.expires_at : screen === 'rebind' ? rebindSession?.expires_at : session?.expires_at
    if (!expiry) return 0
    return Math.max(0, Math.ceil((new Date(expiry).getTime() - now) / 1000))
  }, [bootstrapSession, now, rebindSession, screen, session])

  useEffect(() => {
    if ((screen === 'pairing' || screen === 'bootstrap' || screen === 'rebind') && secondsRemaining === 0) setScreen('expired')
  }, [screen, secondsRemaining])

  const handleInvalidSession = useCallback(() => {
    setAuthSession(null)
    setScreen('replaced')
  }, [])

  if (screen === 'paired' && authSession) {
    return <TimelineApp authSession={authSession} onSessionInvalid={handleInvalidSession} onRebindPhone={() => void createRebindSession()} />
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <Brand />
        <span className={`status-pill status-pill--${screen}`}>
          <span className="status-dot" aria-hidden="true" />
          {screen === 'bootstrap' ? '等待手机绑定'
            : screen === 'pairing' ? '等待手机确认'
            : screen === 'rebind' ? '等待手机重绑定'
            : screen === 'replaced' ? '浏览器已被替换'
              : screen === 'insecure' ? '需要 HTTPS'
                : '安全连接'}
        </span>
      </header>

      <main className="content">
        {screen === 'loading' && <LoadingState />}
        {screen === 'pairing' && session && (
          <PairingCard session={session} secondsRemaining={secondsRemaining} />
        )}
        {screen === 'bootstrap' && bootstrapSession && (
          <BootstrapCard session={bootstrapSession} secondsRemaining={secondsRemaining} />
        )}
        {screen === 'rebind' && rebindSession && (
          <RebindCard
            session={rebindSession}
            secondsRemaining={secondsRemaining}
            onRefresh={() => void createRebindSession()}
            onCancel={() => { setRetryRebind(false); setRebindSession(null); setScreen('paired') }}
          />
        )}
        {(screen === 'expired' || screen === 'rejected' || screen === 'replaced' || screen === 'insecure' || screen === 'error') && (
          <RetryState
            title={
              screen === 'expired' ? '配对码已过期'
                : screen === 'rejected' ? '手机已拒绝配对'
                  : screen === 'replaced' ? '这台 Windows 已被替换'
                    : screen === 'insecure' ? '此地址无法安全配对'
                    : '暂时无法连接'
            }
            message={
              (screen === 'error' || screen === 'insecure') ? errorMessage
                : screen === 'replaced' ? 'Android Master 已授权另一台 Windows。重新配对会再次请求手机确认。'
                  : '生成新的二维码后，再用 Android Master 扫描确认。'
            }
            onRetry={() => void (retryBootstrap ? createBootstrapSession() : retryRebind ? createRebindSession() : createSession())}
            retryAfterSeconds={screen === 'error' ? retryAfterSeconds : 0}
            retryAllowed={screen !== 'insecure'}
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

function TimelineApp({ authSession, onSessionInvalid, onRebindPhone }: { authSession: AuthSession, onSessionInvalid: () => void, onRebindPhone: () => void }) {
  const [lanOpen, setLanOpen] = useState(false)
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
  const [deleteTargets, setDeleteTargets] = useState<TimelineMessage[]>([])
  const [deleting, setDeleting] = useState(false)
  const [actionNotice, setActionNotice] = useState('')
  const [attachmentOpen, setAttachmentOpen] = useState(false)
  const [pendingUploads, setPendingUploads] = useState<PendingUpload[]>([])
  const [pasteDuplicates, setPasteDuplicates] = useState<{ fresh: File[], repeated: File[] } | null>(null)
  const [downloadSelectionOpen, setDownloadSelectionOpen] = useState(false)
  const [selectedDownloadIDs, setSelectedDownloadIDs] = useState<Set<string>>(new Set())
  const [batchDownloading, setBatchDownloading] = useState(false)
  const [batchDownloadReport, setBatchDownloadReport] = useState<{ success: string[], failed: string[] } | null>(null)
  const [dragging, setDragging] = useState(false)
  const [viewer, setViewer] = useState<{ images: TimelineMessage[], index: number } | null>(null)
  const [isAtBottom, setIsAtBottom] = useState(true)
  const [unreadCount, setUnreadCount] = useState(0)
  const [scrollRequest, setScrollRequest] = useState<{ id: number, behavior: ScrollBehavior } | null>(null)
  const timelineListRef = useRef<HTMLElement | null>(null)
  const bottomRef = useRef<HTMLDivElement | null>(null)
  const photoInputRef = useRef<HTMLInputElement | null>(null)
  const fileInputRef = useRef<HTMLInputElement | null>(null)
  const synchronizingRef = useRef(false)
  const bufferedEventsRef = useRef<RealtimeEnvelope[]>([])
  const isAtBottomRef = useRef(true)
  const knownMessageIDsRef = useRef<Set<string>>(new Set())
  const initialSyncCompleteRef = useRef(false)
  const scrollRequestIDRef = useRef(0)
  const uploadedFileFingerprintCountsRef = useRef<Map<string, number>>(new Map())
  const batchDownloadAbortRef = useRef<AbortController | null>(null)

  useEffect(() => {
    if (!actionNotice) return
    const timer = window.setTimeout(() => setActionNotice(''), 1_800)
    return () => window.clearTimeout(timer)
  }, [actionNotice])

  const requestScrollToBottom = useCallback((behavior: ScrollBehavior = 'smooth') => {
    isAtBottomRef.current = true
    setIsAtBottom(true)
    setUnreadCount(0)
    scrollRequestIDRef.current += 1
    setScrollRequest({ id: scrollRequestIDRef.current, behavior })
  }, [])

  useEffect(() => {
    if (!scrollRequest) return
    const container = timelineListRef.current
    if (container) {
      container.scrollTo({ top: container.scrollHeight, behavior: scrollRequest.behavior })
    } else {
      bottomRef.current?.scrollIntoView({ behavior: scrollRequest.behavior })
    }
  }, [scrollRequest])

  const handleTimelineScroll = useCallback(() => {
    const container = timelineListRef.current
    if (!container) return
    const atBottom = container.scrollHeight - container.scrollTop - container.clientHeight <= 56
    isAtBottomRef.current = atBottom
    setIsAtBottom(atBottom)
    if (atBottom) setUnreadCount(0)
  }, [])

  const handleAuthError = useCallback((error: unknown) => {
    if (error instanceof ApiError && error.status === 401) {
      onSessionInvalid()
      return true
    }
    return false
  }, [onSessionInvalid])

  const acceptCreatedMessage = useCallback((createdMessage: TimelineMessage) => {
    if (knownMessageIDsRef.current.has(createdMessage.id)) return
    knownMessageIDsRef.current.add(createdMessage.id)
    setMessages((current) => mergeMessages(current, [createdMessage]))
    if (createdMessage.source_device_id === authSession.device_id || isAtBottomRef.current) {
      requestScrollToBottom()
    } else {
      setUnreadCount((current) => current + 1)
    }
  }, [authSession.device_id, requestScrollToBottom])

  const applyRealtimeEvent = useCallback((event: RealtimeEnvelope) => {
    if (event.type === 'message.created' && isTimelineMessage(event.data)) {
      acceptCreatedMessage(event.data)
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
  }, [acceptCreatedMessage, onSessionInvalid])

  const loadLatest = useCallback(async (signal?: AbortSignal) => {
    synchronizingRef.current = true
    bufferedEventsRef.current = []
    try {
      const page = await request<MessagePage>('/api/v1/messages?limit=50', { signal })
      const firstSync = !initialSyncCompleteRef.current
      const newMessages = page.messages.filter((message) => !knownMessageIDsRef.current.has(message.id))
      knownMessageIDsRef.current = new Set(page.messages.map((message) => message.id))
      setMessages(page.messages)
      setNextBefore(page.next_before || '')
      setErrorMessage('')
      initialSyncCompleteRef.current = true
      if (firstSync) {
        requestScrollToBottom('auto')
      } else if (newMessages.some((message) => message.source_device_id === authSession.device_id) || (newMessages.length > 0 && isAtBottomRef.current)) {
        requestScrollToBottom()
      } else {
        const incomingCount = newMessages.filter((message) => message.source_device_id !== authSession.device_id).length
        if (incomingCount > 0) setUnreadCount((current) => current + incomingCount)
      }
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
  }, [applyRealtimeEvent, authSession.device_id, handleAuthError, requestScrollToBottom])

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
      page.messages.forEach((message) => knownMessageIDsRef.current.add(message.id))
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
      acceptCreatedMessage(created)
      setDraft('')
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
    if (deleteTargets.length === 0 || deleting) return
    setDeleting(true)
    const targets = [...deleteTargets]
    try {
      const results = await Promise.allSettled(targets.map((target) =>
        request<void>(`/api/v1/messages/${encodeURIComponent(target.id)}`, { method: 'DELETE' }),
      ))
      const deletedIDs = new Set(targets.filter((_, index) => results[index].status === 'fulfilled').map((target) => target.id))
      const failedTargets = targets.filter((_, index) => results[index].status === 'rejected')
      if (deletedIDs.size > 0) {
        setMessages((current) => current.filter((message) => !deletedIDs.has(message.id)))
        setSearchResults((current) => current.filter((message) => !deletedIDs.has(message.id)))
      }
      if (failedTargets.length === 0) {
        setDeleteTargets([])
        setActionNotice(targets.length > 1 ? `已删除 ${targets.length} 条消息` : '消息已删除')
      } else {
        const firstFailure = results.find((result) => result.status === 'rejected')
        const reason = firstFailure?.status === 'rejected' ? firstFailure.reason : null
        if (!handleAuthError(reason)) {
          setDeleteTargets(failedTargets)
          setErrorMessage(reason instanceof Error ? reason.message : '部分消息删除失败，请重试。')
        }
      }
    } finally {
      setDeleting(false)
    }
  }

  const copyMessages = async (targets: TimelineMessage[]) => {
    const value = targets
      .map((message) => message.type === 'text' ? message.text_content || '' : message.file?.original_filename)
      .filter((item): item is string => Boolean(item))
      .join('\n')
    if (!value) return
    try {
      await copyTextToClipboard(value)
      setActionNotice(targets.length > 1 ? `已复制 ${targets.length} 个文件名` : '已复制')
    } catch {
      setErrorMessage('复制失败，请检查浏览器剪贴板权限。')
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
      knownMessageIDsRef.current = new Set(response.messages.map((message) => message.id))
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

  const registerFileFingerprint = useCallback((file: File) => {
    const fingerprint = fileFingerprint(file)
    const counts = uploadedFileFingerprintCountsRef.current
    counts.set(fingerprint, (counts.get(fingerprint) || 0) + 1)
  }, [])

  const unregisterFileFingerprint = useCallback((file: File) => {
    const fingerprint = fileFingerprint(file)
    const counts = uploadedFileFingerprintCountsRef.current
    const nextCount = (counts.get(fingerprint) || 0) - 1
    if (nextCount <= 0) counts.delete(fingerprint)
    else counts.set(fingerprint, nextCount)
  }, [])

  const performUpload = useCallback(async (pending: PendingUpload) => {
    if (pending.status === 'failed') registerFileFingerprint(pending.file)
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
      acceptCreatedMessage(created)
      updatePending(pending.id, { status: 'complete', progress: 100 })
      window.setTimeout(() => setPendingUploads((current) => current.filter((item) => item.id !== pending.id)), 650)
    } catch (error) {
      unregisterFileFingerprint(pending.file)
      if (handleAuthError(error)) return
      updatePending(pending.id, { status: 'failed', error: uploadErrorMessage(error) })
    }
  }, [acceptCreatedMessage, handleAuthError, registerFileFingerprint, unregisterFileFingerprint, updatePending])

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
    files.forEach(registerFileFingerprint)
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
      files.forEach(unregisterFileFingerprint)
      if (!handleAuthError(error)) setErrorMessage(uploadErrorMessage(error))
    }
  }, [handleAuthError, performUpload, registerFileFingerprint, unregisterFileFingerprint])

  const handleFileInput = (event: ChangeEvent<HTMLInputElement>) => {
    const selected = Array.from(event.target.files || [])
    event.target.value = ''
    void uploadSelectedFiles(selected)
  }

  const handlePaste = (event: ClipboardEvent<HTMLDivElement>) => {
    const itemFiles = Array.from(event.clipboardData.items)
      .filter((item) => item.kind === 'file')
      .map((item) => item.getAsFile())
      .filter((file): file is File => file !== null)
    const candidates = itemFiles.length > 0 ? itemFiles : Array.from(event.clipboardData.files)
    const partition = partitionRepeatedFiles(candidates, new Set(uploadedFileFingerprintCountsRef.current.keys()))
    if (partition.fresh.length === 0 && partition.repeated.length === 0) return
    event.preventDefault()
    if (partition.repeated.length > 0) {
      setPasteDuplicates(partition)
      return
    }
    void uploadSelectedFiles(partition.fresh)
  }

  const handleDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault()
    setDragging(false)
    const files = Array.from(event.dataTransfer.files)
    if (files.length > 0) void uploadSelectedFiles(files)
  }

  const downloadableMessages = useMemo(
    () => messages.filter((message) => message.file?.status === 'available'),
    [messages],
  )

  useEffect(() => {
    const availableIDs = new Set(downloadableMessages.map((message) => message.id))
    setSelectedDownloadIDs((current) => {
      const retained = new Set(Array.from(current).filter((id) => availableIDs.has(id)))
      return retained.size === current.size ? current : retained
    })
  }, [downloadableMessages])

  const toggleDownloadSelection = useCallback((messageIDs: string[]) => {
    setSelectedDownloadIDs((current) => {
      const next = new Set(current)
      const shouldSelect = messageIDs.some((id) => !next.has(id))
      messageIDs.forEach((id) => shouldSelect ? next.add(id) : next.delete(id))
      return next
    })
  }, [])

  const closeDownloadSelection = useCallback(() => {
    if (batchDownloading) return
    setDownloadSelectionOpen(false)
    setSelectedDownloadIDs(new Set())
  }, [batchDownloading])

  const startBatchDownload = useCallback(async () => {
    const selected = downloadableMessages.filter((message) => selectedDownloadIDs.has(message.id))
    if (selected.length === 0 || batchDownloading) return
    const picker = (window as Window & {
      showDirectoryPicker?: (options?: { id?: string, mode?: 'read' | 'readwrite' }) => Promise<unknown>
    }).showDirectoryPicker
    if (!picker || !window.isSecureContext) {
      setActionNotice('当前浏览器不支持目录保存，将逐个下载；请允许多文件下载。')
      selected.forEach((message, index) => {
        window.setTimeout(() => {
          const anchor = document.createElement('a')
          anchor.href = message.file?.download_url || ''
          anchor.download = message.file?.original_filename || 'download'
          anchor.click()
        }, index * 180)
      })
      closeDownloadSelection()
      return
    }
    try {
      const directory = await picker({ id: 'transdot-downloads', mode: 'readwrite' }) as DownloadDirectory
      const controller = new AbortController()
      batchDownloadAbortRef.current = controller
      setBatchDownloading(true)
      const results = await downloadFilesToDirectory(
        selected.map((message) => ({
          filename: message.file?.original_filename || 'download',
          url: message.file?.download_url || '',
        })),
        directory,
        fetch,
        controller.signal,
      )
      if (controller.signal.aborted) {
        setActionNotice('已取消批量下载。')
      } else {
        const successCount = results.filter((result) => result.status === 'fulfilled').length
        const failureCount = results.length - successCount
        setBatchDownloadReport({
          success: results.filter((result) => result.status === 'fulfilled').map((result) => result.filename),
          failed: results.filter((result) => result.status === 'rejected').map((result) => result.source.filename),
        })
        setActionNotice(failureCount === 0
          ? `已保存 ${successCount} 个文件。`
          : `已保存 ${successCount} 个，${failureCount} 个失败。`)
      }
      setDownloadSelectionOpen(false)
      setSelectedDownloadIDs(new Set())
    } catch (error) {
      if (!isAbort(error)) setErrorMessage(error instanceof Error ? error.message : '无法批量下载文件。')
    } finally {
      batchDownloadAbortRef.current = null
      setBatchDownloading(false)
    }
  }, [batchDownloading, closeDownloadSelection, downloadableMessages, selectedDownloadIDs])

  const groupedMessages = useMemo(() => groupTimelineMessages(messages), [messages])

  if (lanOpen) return <LanTransferView onBack={() => setLanOpen(false)} />

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
          <button className="batch-action" type="button" onClick={onRebindPhone}>重新绑定手机</button>
          <button className="batch-action lan-mode-command" type="button" onClick={() => setLanOpen(true)}>
            <LanTransferIcon /><span>局域网快传</span>
          </button>
          <span className={`connection-badge connection-badge--${connection}`}>
            <span />{connection === 'connected' ? '实时连接' : connection === 'connecting' ? '正在连接' : '等待重连'}
          </span>
          {downloadSelectionOpen ? (
            <>
              <button
                className="batch-action"
                type="button"
                disabled={batchDownloading || downloadableMessages.length === 0}
                onClick={() => setSelectedDownloadIDs((current) => current.size === downloadableMessages.length
                  ? new Set()
                  : new Set(downloadableMessages.map((message) => message.id)))}
              >
                {selectedDownloadIDs.size === downloadableMessages.length && downloadableMessages.length > 0 ? '取消全选' : '全选'}
              </button>
              <span className="selection-count">已选 {selectedDownloadIDs.size}</span>
              <button className="batch-action batch-action--primary" type="button" disabled={selectedDownloadIDs.size === 0 || batchDownloading} onClick={() => void startBatchDownload()}>
                {batchDownloading ? '保存中…' : '保存到文件夹'}
              </button>
              <button className="batch-action" type="button" onClick={batchDownloading ? () => batchDownloadAbortRef.current?.abort() : closeDownloadSelection}>
                {batchDownloading ? '取消下载' : '退出'}
              </button>
            </>
          ) : (
            <>
              <button className="batch-action" type="button" disabled={downloadableMessages.length === 0} onClick={() => setDownloadSelectionOpen(true)}>批量下载</button>
              <button className="icon-button" type="button" onClick={() => setSearchOpen(true)} aria-label="搜索消息">
                <SearchIcon />
              </button>
            </>
          )}
        </div>
      </header>

      <main className="timeline-main">
        <section ref={timelineListRef} className="timeline-list" aria-live="polite" onScroll={handleTimelineScroll}>
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
                    <div className="message-body-line">
                      {!downloadSelectionOpen && (
                        <MessageActions
                          onCopy={() => void copyMessages(group.messages)}
                          onDelete={() => setDeleteTargets(group.messages)}
                          copyLabel={group.messages.length > 1 ? '复制这组文件名' : '复制消息'}
                          deleteLabel={group.messages.length > 1 ? '删除这组消息' : '删除消息'}
                        />
                      )}
                      {group.kind === 'images' ? (
                        <ImageGrid
                          messages={group.messages}
                          onOpen={(index) => setViewer({ images: group.messages, index })}
                          selection={downloadSelectionOpen ? { selectedIDs: selectedDownloadIDs, onToggle: (id) => toggleDownloadSelection([id]) } : undefined}
                        />
                      ) : (
                        <MessageCard
                          message={message}
                          onOpenImage={() => setViewer({ images: [message], index: 0 })}
                          selection={downloadSelectionOpen ? { selected: selectedDownloadIDs.has(message.id), onToggle: () => toggleDownloadSelection([message.id]) } : undefined}
                        />
                      )}
                    </div>
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
        {actionNotice && <div className="action-notice" role="status" aria-live="polite">{actionNotice}</div>}
        {!isAtBottom && (
          <button
            className={`scroll-to-bottom ${unreadCount > 0 ? 'scroll-to-bottom--unread' : ''}`}
            type="button"
            onClick={() => requestScrollToBottom()}
            aria-label={unreadCount > 0 ? `有 ${unreadCount} 条新消息，滚动到底部` : '滚动到底部'}
          >
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 4v13M7 12l5 5 5-5" /></svg>
            {unreadCount > 0 && <span>{unreadCount > 99 ? '99+' : unreadCount} 条新消息</span>}
          </button>
        )}
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

      {pasteDuplicates && (
        <div className="sheet-backdrop sheet-backdrop--center" role="presentation">
          <div className="confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="duplicate-title">
            <h2 id="duplicate-title">检测到重复粘贴</h2>
            <p>
              以下 {pasteDuplicates.repeated.length} 个文件已在本次页面会话中排队或上传：
              <strong className="duplicate-files">{pasteDuplicates.repeated.map((file) => file.name).join('、')}</strong>
            </p>
            <div>
              <button type="button" onClick={() => setPasteDuplicates(null)}>取消</button>
              <button type="button" onClick={() => {
                const fresh = pasteDuplicates.fresh
                setPasteDuplicates(null)
                void uploadSelectedFiles(fresh)
              }} disabled={pasteDuplicates.fresh.length === 0}>只上传新文件</button>
              <button className="primary-button" type="button" onClick={() => {
                const files = [...pasteDuplicates.fresh, ...pasteDuplicates.repeated]
                setPasteDuplicates(null)
                void uploadSelectedFiles(files)
              }}>仍然上传全部</button>
            </div>
          </div>
        </div>
      )}

      {batchDownloadReport && (
        <div className="sheet-backdrop sheet-backdrop--center" role="presentation">
          <div className="confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="download-report-title">
            <h2 id="download-report-title">批量下载结果</h2>
            <p>
              成功 {batchDownloadReport.success.length} 个，失败 {batchDownloadReport.failed.length} 个。
              {batchDownloadReport.failed.length > 0 && <strong className="duplicate-files">失败：{batchDownloadReport.failed.join('、')}</strong>}
            </p>
            <div><button className="primary-button" type="button" onClick={() => setBatchDownloadReport(null)}>完成</button></div>
          </div>
        </div>
      )}

      {deleteTargets.length > 0 && (
        <div className="sheet-backdrop sheet-backdrop--center" role="presentation">
          <div className="confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="delete-title">
            <h2 id="delete-title">{deleteTargets.length > 1 ? `删除这组 ${deleteTargets.length} 条消息？` : '删除这条消息？'}</h2>
            <p>{deleteTargets.every((target) => target.type === 'text') ? '删除会同步到 Android，并从全文搜索索引中移除。' : '删除会同步移除消息、原文件和缩略图，此操作无法撤销。'}</p>
            <div>
              <button type="button" onClick={() => setDeleteTargets([])} disabled={deleting}>取消</button>
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

function MessageActions({ onCopy, onDelete, copyLabel, deleteLabel }: { onCopy: () => void, onDelete: () => void, copyLabel: string, deleteLabel: string }) {
  return (
    <div className="message-actions" aria-label="消息操作">
      <button type="button" onClick={onCopy} aria-label={copyLabel} title="复制"><CopyIcon /></button>
      <button className="message-action--delete" type="button" onClick={onDelete} aria-label={deleteLabel} title="删除"><TrashIcon /></button>
    </div>
  )
}

function MessageCard({
  message,
  onOpenImage,
  selection,
}: {
  message: TimelineMessage,
  onOpenImage: () => void,
  selection?: { selected: boolean, onToggle: () => void },
}) {
  if (message.type === 'text') {
    return <div className="message-bubble"><p>{message.text_content}</p></div>
  }
  if (message.type === 'image') {
    return <ImageGrid messages={[message]} onOpen={onOpenImage} selection={selection ? { selectedIDs: selection.selected ? new Set([message.id]) : new Set<string>(), onToggle: selection.onToggle } : undefined} />
  }
  const file = message.file
  return (
    <div className={`file-card ${selection?.selected ? 'download-selected' : ''}`}>
      {selection && file?.status === 'available' && (
        <label className="download-check" aria-label={`选择 ${file.original_filename}`}>
          <input type="checkbox" checked={selection.selected} onChange={selection.onToggle} />
          <span aria-hidden="true">✓</span>
        </label>
      )}
      <span className="file-icon">{fileExtension(file?.original_filename || '')}</span>
      <div className="file-copy"><strong title={file?.original_filename}>{file?.original_filename || '文件'}</strong><span>{formatBytes(file?.size_bytes || 0)} · {fileStatusLabel(file)}</span></div>
      {!selection && (file?.status === 'available' ? <a className="file-action" href={file.download_url} download>下载</a> : <span className="file-action file-action--disabled">已过期</span>)}
    </div>
  )
}

function ImageGrid({
  messages,
  onOpen,
  selection,
}: {
  messages: TimelineMessage[],
  onOpen: (index: number) => void,
  selection?: { selectedIDs: ReadonlySet<string>, onToggle: (id: string) => void },
}) {
  const visible = messages.slice(0, 6)
  return (
    <div className={`image-grid image-grid--${Math.min(messages.length, 5)}`}>
      {visible.map((message, index) => {
        const file = message.file
        const expired = file?.status !== 'available'
        return (
          <div className={`image-tile ${selection?.selectedIDs.has(message.id) ? 'download-selected' : ''}`} key={message.id}>
            <button type="button" onClick={() => onOpen(index)} disabled={!file?.thumbnail_url && expired}>
              {file?.thumbnail_url ? <img src={file.thumbnail_url} alt={file.original_filename} loading="lazy" /> : <span className="image-placeholder">IMG</span>}
              {expired && <span className="image-expired">原图已过期</span>}
              {index === 5 && messages.length > 6 && <span className="image-more">+{messages.length - 5}</span>}
            </button>
            {selection && !expired && (
              <label className="download-check" aria-label={`选择 ${file?.original_filename || '图片'}`}>
                <input type="checkbox" checked={selection.selectedIDs.has(message.id)} onChange={() => selection.onToggle(message.id)} />
                <span aria-hidden="true">✓</span>
              </label>
            )}
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
      <div className="viewer-stage" onClick={(event) => { if (event.target === event.currentTarget) onClose() }}>
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
      <span className="brand-copy">
        <strong>传输助手</strong>
        <small className="brand-instance">{instanceHostLabel(window.location.host)}</small>
      </span>
    </a>
  )
}

function SearchIcon() {
  return <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="10.8" cy="10.8" r="6.3" /><path d="m15.5 15.5 4.2 4.2" /></svg>
}

function LanTransferIcon() {
  return <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 8h14m-4-4 4 4-4 4M20 16H6m4-4-4 4 4 4" /></svg>
}

function CopyIcon() {
  return <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="8" y="8" width="10" height="11" rx="2" /><path d="M16 8V6a2 2 0 0 0-2-2H7a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h1" /></svg>
}

function TrashIcon() {
  return <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4.5 7h15M9 7V4.8h6V7M7 7l.8 12h8.4L17 7M10 10.5v5M14 10.5v5" /></svg>
}

async function copyTextToClipboard(value: string) {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(value)
    return
  }
  const textarea = document.createElement('textarea')
  textarea.value = value
  textarea.style.position = 'fixed'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)
  textarea.select()
  const copied = document.execCommand('copy')
  textarea.remove()
  if (!copied) throw new Error('Clipboard unavailable')
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

function BootstrapCard({ session, secondsRemaining }: { session: BootstrapSession, secondsRemaining: number }) {
  return (
    <section className="pairing-layout" aria-labelledby="bootstrap-title">
      <div className="pairing-copy">
        <p className="eyebrow">ANDROID BOOTSTRAP</p>
        <h1 id="bootstrap-title">用手机绑定这台服务器</h1>
        <p className="lead">打开传输助手，点击“扫码连接服务器”，扫描右侧二维码并确认服务器指纹。</p>
        <div className="privacy-note">二维码只包含一次性绑定凭据，不包含初始化密钥或长期 Token。</div>
      </div>
      <div className="qr-card">
        <div className="qr-frame" aria-label="Android 绑定二维码">
          <QRCodeSVG value={session.qr_payload} size={232} level="M" marginSize={2} bgColor="#ffffff" fgColor="#14171c" />
        </div>
        <div className="expiry" aria-live="polite">
          <span className="expiry-line"><span style={{ width: `${Math.min(100, secondsRemaining / 1.2)}%` }} /></span>
          <span>{secondsRemaining} 秒后失效</span>
        </div>
      </div>
    </section>
  )
}

function RebindCard({
  session,
  secondsRemaining,
  onRefresh,
  onCancel,
}: {
  session: RebindSession
  secondsRemaining: number
  onRefresh: () => void
  onCancel: () => void
}) {
  return (
    <section className="pairing-layout" aria-labelledby="rebind-title">
      <div className="pairing-copy">
        <p className="eyebrow">ANDROID REBIND</p>
        <h1 id="rebind-title">重新绑定手机</h1>
        <p className="lead">用新安装的传输助手扫描二维码并确认服务器指纹。完成后，旧手机会立即断开。</p>
        <div className="privacy-note">二维码为单次短时凭据，不包含长期 Master Token。</div>
        <div className="button-row">
          <button className="secondary-button" type="button" onClick={onRefresh}>刷新二维码</button>
          <button className="secondary-button" type="button" onClick={onCancel}>取消</button>
        </div>
      </div>
      <div className="qr-card">
        <div className="qr-frame" aria-label="Android 重绑定二维码">
          <QRCodeSVG value={session.qr_payload} size={232} level="M" marginSize={2} bgColor="#ffffff" fgColor="#14171c" />
        </div>
        <div className="expiry" aria-live="polite">
          <span className="expiry-line"><span style={{ width: `${Math.min(100, secondsRemaining / 1.2)}%` }} /></span>
          <span>{secondsRemaining} 秒后失效</span>
        </div>
      </div>
    </section>
  )
}

function RetryState({
  title,
  message,
  onRetry,
  retryAfterSeconds = 0,
  retryAllowed = true,
}: {
  title: string
  message: string
  onRetry: () => void
  retryAfterSeconds?: number
  retryAllowed?: boolean
}) {
  return (
    <section className="state-panel state-panel--center">
      <span className="retry-mark" aria-hidden="true">↻</span>
      <p className="eyebrow">SECURE SESSION</p>
      <h1>{title}</h1>
      <p>{message}</p>
      {retryAllowed && (
        <button className="primary-button" type="button" disabled={retryAfterSeconds > 0} onClick={onRetry}>
          {retryAfterSeconds > 0 ? `${retryAfterSeconds} 秒后可重试` : '生成新的配对码'}
        </button>
      )}
    </section>
  )
}

export default App
