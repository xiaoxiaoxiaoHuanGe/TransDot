import { useEffect, useState } from 'react'

type ServiceState = 'checking' | 'ready' | 'unavailable'

function StatusIcon({ state }: { state: ServiceState }) {
  if (state === 'checking') {
    return <span className="status-dot status-dot--checking" aria-hidden="true" />
  }
  return (
    <span
      className={`status-dot status-dot--${state}`}
      aria-hidden="true"
    />
  )
}

function App() {
  const [serviceState, setServiceState] = useState<ServiceState>('checking')

  useEffect(() => {
    const controller = new AbortController()

    fetch('/healthz', {
      headers: { Accept: 'application/json' },
      signal: controller.signal,
    })
      .then((response) => {
        if (!response.ok) throw new Error('Health check failed')
        return response.json() as Promise<{ status?: string }>
      })
      .then((body) => setServiceState(body.status === 'ok' ? 'ready' : 'unavailable'))
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === 'AbortError') return
        setServiceState('unavailable')
      })

    return () => controller.abort()
  }, [])

  const statusText = {
    checking: '正在检查服务',
    ready: '基础服务已就绪',
    unavailable: '服务暂时不可用',
  }[serviceState]

  return (
    <div className="app-shell">
      <header className="topbar">
        <a className="brand" href="/" aria-label="私人文件传输助手首页">
          <span className="brand-mark" aria-hidden="true">
            <svg viewBox="0 0 24 24" role="img">
              <path d="M7.5 8.25 12 3.75l4.5 4.5M12 4.5v10.25M5.5 13.25v4.5A2.25 2.25 0 0 0 7.75 20h8.5a2.25 2.25 0 0 0 2.25-2.25v-4.5" />
            </svg>
          </span>
          <span>传输助手</span>
        </a>
        <div className={`service-pill service-pill--${serviceState}`}>
          <StatusIcon state={serviceState} />
          <span>{statusText}</span>
        </div>
      </header>

      <main className="content">
        <section className="hero" aria-labelledby="page-title">
          <p className="eyebrow">PRIVATE TRANSFER</p>
          <h1 id="page-title">在手机与电脑之间，<br />轻松传递。</h1>
          <p className="hero-copy">
            私人、自托管、同源访问。基础服务已经建立，安全配对与传输能力将在后续阶段开放。
          </p>
        </section>

        <section className="foundation" aria-labelledby="foundation-title">
          <div className="section-heading">
            <div>
              <p className="section-kicker">TASK 01</p>
              <h2 id="foundation-title">项目基础</h2>
            </div>
            <span className="foundation-badge">已建立</span>
          </div>

          <div className="foundation-grid">
            <article className="foundation-item">
              <span className="item-number">01</span>
              <div>
                <h3>单体服务</h3>
                <p>Go 同源托管 API 与 React 静态资源。</p>
              </div>
            </article>
            <article className="foundation-item">
              <span className="item-number">02</span>
              <div>
                <h3>本地持久化</h3>
                <p>SQLite 与文件统一保存在 /app/data。</p>
              </div>
            </article>
            <article className="foundation-item">
              <span className="item-number">03</span>
              <div>
                <h3>部署就绪</h3>
                <p>单容器监听 5757，由 1Panel 终止 HTTPS。</p>
              </div>
            </article>
          </div>
        </section>
      </main>

      <footer>
        <span>Transfer Assistant</span>
        <span>V1 Foundation</span>
      </footer>
    </div>
  )
}

export default App
