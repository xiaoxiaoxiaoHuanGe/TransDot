import { useCallback, useEffect, useMemo, useState } from 'react'
import { QRCodeSVG } from 'qrcode.react'

type PairingStatus = 'pending' | 'approved' | 'rejected' | 'expired' | 'consumed'
type ScreenState = 'loading' | 'pairing' | 'paired' | 'rejected' | 'expired' | 'error'

type PairingSession = {
  session_id: string
  pairing_code: string
  qr_payload: string
  expires_at: string
  poll_interval_seconds: number
}

type ErrorEnvelope = {
  error?: {
    code?: string
    message?: string
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
    const error = new Error(body.error?.message || `请求失败（HTTP ${response.status}）`)
    Object.assign(error, { status: response.status, code: body.error?.code })
    throw error
  }
  return response.json() as Promise<T>
}

function formatPairingCode(code: string) {
  return `${code.slice(0, 3)} ${code.slice(3)}`
}

function App() {
  const [screen, setScreen] = useState<ScreenState>('loading')
  const [session, setSession] = useState<PairingSession | null>(null)
  const [errorMessage, setErrorMessage] = useState('')
  const [now, setNow] = useState(() => Date.now())

  const createSession = useCallback(async (signal?: AbortSignal) => {
    setScreen('loading')
    setErrorMessage('')
    try {
      const nextSession = await request<PairingSession>('/api/v1/pairing/sessions', {
        method: 'POST',
        signal,
      })
      setSession(nextSession)
      setNow(Date.now())
      setScreen('pairing')
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') return
      setErrorMessage(error instanceof Error ? error.message : '无法创建配对会话。')
      setScreen('error')
    }
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    request<{ authenticated: boolean }>('/api/v1/auth/session', { signal: controller.signal })
      .then(() => setScreen('paired'))
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return
        const status = (error as { status?: number }).status
        if (status === 401) {
          void createSession(controller.signal)
          return
        }
        setErrorMessage(error instanceof Error ? error.message : '无法检查浏览器状态。')
        setScreen('error')
      })
    return () => controller.abort()
  }, [createSession])

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
          setScreen('paired')
        } else if (result.status === 'rejected') {
          setScreen('rejected')
        } else if (result.status === 'expired') {
          setScreen('expired')
        }
      } catch (error) {
        if (error instanceof DOMException && error.name === 'AbortError') return
        const code = (error as { code?: string }).code
        if (code === 'PAIRING_EXPIRED' || code === 'PAIRING_INVALID') {
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
  }, [screen, session])

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

  return (
    <div className="app-shell">
      <header className="topbar">
        <a className="brand" href="/" aria-label="私人文件传输助手首页">
          <span className="brand-mark" aria-hidden="true">
            <svg viewBox="0 0 24 24">
              <path d="M7.5 8.25 12 3.75l4.5 4.5M12 4.5v10.25M5.5 13.25v4.5A2.25 2.25 0 0 0 7.75 20h8.5a2.25 2.25 0 0 0 2.25-2.25v-4.5" />
            </svg>
          </span>
          <span>传输助手</span>
        </a>
        <span className={`status-pill status-pill--${screen}`}>
          <span className="status-dot" aria-hidden="true" />
          {screen === 'paired' ? '浏览器已配对' : screen === 'pairing' ? '等待手机确认' : '安全连接'}
        </span>
      </header>

      <main className="content">
        {screen === 'loading' && <LoadingState />}
        {screen === 'pairing' && session && (
          <PairingCard session={session} secondsRemaining={secondsRemaining} />
        )}
        {screen === 'paired' && <PairedState />}
        {(screen === 'expired' || screen === 'rejected' || screen === 'error') && (
          <RetryState
            title={screen === 'expired' ? '配对码已过期' : screen === 'rejected' ? '手机已拒绝配对' : '暂时无法配对'}
            message={screen === 'error' ? errorMessage : '生成新的二维码后，再用 Android Master 扫描确认。'}
            onRetry={() => void createSession()}
          />
        )}
      </main>

      <footer>
        <span>Private · Self-hosted</span>
        <span>V1 Pairing</span>
      </footer>
    </div>
  )
}

function LoadingState() {
  return (
    <section className="state-panel state-panel--center" aria-live="polite">
      <span className="spinner" aria-hidden="true" />
      <p className="eyebrow">SECURE PAIRING</p>
      <h1>正在准备安全连接</h1>
      <p>检查浏览器凭据并创建一次性配对会话。</p>
    </section>
  )
}

function PairingCard({ session, secondsRemaining }: { session: PairingSession, secondsRemaining: number }) {
  return (
    <section className="pairing-layout" aria-labelledby="pairing-title">
      <div className="pairing-copy">
        <p className="eyebrow">WINDOWS PAIRING</p>
        <h1 id="pairing-title">用手机确认这台电脑</h1>
        <p className="lead">
          打开 Android Master，点击“配对 Windows”并扫描二维码。二维码只在本次会话中有效。
        </p>
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
          <QRCodeSVG
            value={session.qr_payload}
            size={232}
            level="M"
            marginSize={2}
            bgColor="#ffffff"
            fgColor="#14171c"
          />
        </div>
        <div className="manual-code">
          <span>无法扫码？输入 6 位码</span>
          <strong aria-label={`配对码 ${session.pairing_code.split('').join(' ')}`}>
            {formatPairingCode(session.pairing_code)}
          </strong>
        </div>
        <div className="expiry" aria-live="polite">
          <span className="expiry-line"><span style={{ width: `${Math.min(100, secondsRemaining / 1.2)}%` }} /></span>
          <span>{secondsRemaining} 秒后失效</span>
        </div>
      </div>
    </section>
  )
}

function PairedState() {
  return (
    <section className="state-panel state-panel--success">
      <span className="success-mark" aria-hidden="true">✓</span>
      <p className="eyebrow">PAIRING COMPLETE</p>
      <h1>Windows 已安全配对</h1>
      <p>HttpOnly Browser Token 已建立。消息时间线将在下一阶段开放。</p>
      <div className="paired-meta">
        <span>同源 Cookie 鉴权</span>
        <span>唯一有效 Windows</span>
      </div>
    </section>
  )
}

function RetryState({ title, message, onRetry }: { title: string, message: string, onRetry: () => void }) {
  return (
    <section className="state-panel state-panel--center">
      <span className="retry-mark" aria-hidden="true">↻</span>
      <p className="eyebrow">PAIRING SESSION</p>
      <h1>{title}</h1>
      <p>{message}</p>
      <button className="primary-button" type="button" onClick={onRetry}>生成新的配对码</button>
    </section>
  )
}

export default App
