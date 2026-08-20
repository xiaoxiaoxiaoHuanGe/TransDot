import { useCallback, useEffect, useState, type ChangeEvent, type ReactElement } from 'react'
import { DirectoryHandleStore, ensureDirectoryPermission } from './directoryStore'
import { LanPeer, type LanPeerState } from './peer'
import { LanSignalingClient } from './signaling'
import type { DirectoryHandle } from './types'

export type DirectoryStatus = 'loading' | 'required' | 'permission-required' | 'ready' | 'denied'

type DirectoryStoreLike = {
  load(): Promise<DirectoryHandle | undefined>
  save?(handle: DirectoryHandle): Promise<void>
  clear?(): Promise<void>
}

type LanPeerLike = Pick<LanPeer, 'state' | 'subscribe' | 'setReceiveDirectory' | 'sendFiles' | 'retry' | 'cancelTransfer' | 'close'>

export type LanTransferPanelProps = {
  peerState: LanPeerState
  directoryStatus: DirectoryStatus
  directoryName?: string
  selectionError?: string
  connectSecondsRemaining: number
  onBack(): void
  onChooseDirectory(): void
  onSelectFiles(files: File[]): void
  onRetry(fileId: string): void
  onCancel(): void
}

export type LanTransferViewProps = {
  onBack(): void
  createPeer?: () => LanPeerLike
  directoryStore?: DirectoryStoreLike
  chooseDirectory?: () => Promise<DirectoryHandle>
}

type RestoredDirectory =
  | { status: 'required' }
  | { status: 'permission-required' | 'ready' | 'denied', handle: DirectoryHandle }

const defaultDirectoryStore = new DirectoryHandleStore()

export async function restoreReceiveDirectory(store: DirectoryStoreLike): Promise<RestoredDirectory> {
  const handle = await store.load()
  if (!handle) return { status: 'required' }
  const permission = await ensureDirectoryPermission(handle, false)
  if (permission === 'granted') return { status: 'ready', handle }
  return { status: permission === 'denied' ? 'denied' : 'permission-required', handle }
}

export function LanTransferView({
  onBack,
  createPeer = createDefaultPeer,
  directoryStore = defaultDirectoryStore,
  chooseDirectory = defaultChooseDirectory,
}: LanTransferViewProps) {
  const [peer, setPeer] = useState<LanPeerLike | null>(null)
  const [peerState, setPeerState] = useState<LanPeerState>({ status: 'waiting', items: [] })
  const [directoryStatus, setDirectoryStatus] = useState<DirectoryStatus>('loading')
  const [directoryHandle, setDirectoryHandle] = useState<DirectoryHandle>()
  const [connectSecondsRemaining, setConnectSecondsRemaining] = useState(8)
  const [selectionError, setSelectionError] = useState('')

  useEffect(() => {
    const ownedPeer = createPeer()
    setPeer(ownedPeer)
    const unsubscribe = ownedPeer.subscribe(setPeerState)
    return () => {
      unsubscribe()
      ownedPeer.close()
    }
  }, [createPeer])

  useEffect(() => {
    let active = true
    restoreReceiveDirectory(directoryStore).then((restored) => {
      if (!active) return
      setDirectoryStatus(restored.status)
      if ('handle' in restored) setDirectoryHandle(restored.handle)
    }).catch(() => { if (active) setDirectoryStatus('required') })
    return () => { active = false }
  }, [directoryStore])

  useEffect(() => {
    if (!peer) return
    peer.setReceiveDirectory(directoryStatus === 'ready' ? directoryHandle : undefined)
  }, [directoryHandle, directoryStatus, peer])

  useEffect(() => {
    if (peerState.status !== 'connecting') {
      setConnectSecondsRemaining(8)
      return
    }
    setConnectSecondsRemaining(8)
    const timer = window.setInterval(() => setConnectSecondsRemaining((seconds) => Math.max(0, seconds - 1)), 1_000)
    return () => window.clearInterval(timer)
  }, [peerState.status])

  const chooseReceiveDirectory = useCallback(async () => {
    try {
      if (directoryHandle && directoryStatus === 'permission-required') {
        const permission = await ensureDirectoryPermission(directoryHandle, true)
        if (permission === 'granted') {
          setDirectoryStatus('ready')
          return
        }
        setDirectoryStatus('denied')
        await directoryStore.clear?.()
        return
      }
      const selected = await chooseDirectory()
      const permission = await ensureDirectoryPermission(selected, true)
      if (permission !== 'granted') {
        setDirectoryHandle(selected)
        setDirectoryStatus('denied')
        return
      }
      await directoryStore.save?.(selected)
      setDirectoryHandle(selected)
      setDirectoryStatus('ready')
    } catch (error) {
      if (!(error instanceof DOMException && error.name === 'AbortError')) setDirectoryStatus('denied')
    }
  }, [chooseDirectory, directoryHandle, directoryStatus, directoryStore])

  const leave = useCallback(() => {
    peer?.close()
    onBack()
  }, [onBack, peer])

  return <LanTransferPanel
    peerState={peerState}
    directoryStatus={directoryStatus}
    directoryName={directoryHandle?.name}
    selectionError={selectionError}
    connectSecondsRemaining={connectSecondsRemaining}
    onBack={leave}
    onChooseDirectory={() => { void chooseReceiveDirectory() }}
    onSelectFiles={(files) => {
      try {
        peer?.sendFiles(files)
        setSelectionError('')
      } catch (error) {
        setSelectionError(selectionErrorMessage(error))
      }
    }}
    onRetry={(fileId) => { peer?.retry(fileId) }}
    onCancel={() => { peer?.cancelTransfer() }}
  />
}

export function LanTransferPanel({
  peerState,
  directoryStatus,
  directoryName,
  selectionError,
  connectSecondsRemaining,
  onBack,
  onChooseDirectory,
  onSelectFiles,
  onRetry,
  onCancel,
}: LanTransferPanelProps): ReactElement {
  const current = peerState.items.find((item) => item.id === peerState.currentFileId)
  const completed = peerState.items.filter((item) => item.status === 'completed').length
  const canSend = peerState.status === 'connected' || peerState.status === 'transferring'

  return (
    <div className="lan-shell">
      <header className="timeline-topbar lan-topbar">
        <div className="timeline-heading">
          <button className="lan-back" type="button" onClick={onBack} aria-label="返回时间线">
            <BackIcon /><span>返回时间线</span>
          </button>
          <div>
            <h1>局域网快传</h1>
            <p>Android Master ↔ Windows</p>
          </div>
        </div>
        <span className={`connection-badge connection-badge--${peerState.status}`} role="status">
          <span aria-hidden="true" />{connectionLabel(peerState, connectSecondsRemaining)}
        </span>
      </header>

      <main className="lan-main">
        <section className="lan-section lan-receive" aria-labelledby="lan-receive-title">
          <div className="lan-section-heading">
            <div>
              <p className="eyebrow">接收位置</p>
              <h2 id="lan-receive-title">{directoryStatus === 'ready' ? directoryName || '已授权文件夹' : '允许自动接收文件'}</h2>
            </div>
            {directoryStatus !== 'ready' && directoryStatus !== 'loading' && (
              <button className="batch-action batch-action--primary" type="button" onClick={onChooseDirectory}
                aria-label={directoryActionLabel(directoryStatus)}>
                <FolderIcon />{directoryActionLabel(directoryStatus)}
              </button>
            )}
          </div>
          <p className="lan-supporting">
            {directoryStatus === 'loading' ? '正在检查接收权限…'
              : directoryStatus === 'ready' ? '权限有效时，来自 Android 的文件会自动保存到这里。'
                : directoryStatus === 'permission-required' ? '浏览器权限已失效，需要重新授权后才能自动接收。'
                  : directoryStatus === 'denied' ? '当前文件夹不可用，请选择其他接收文件夹。'
                    : '首次使用需要选择一个本机文件夹。'}
          </p>
        </section>

        <section className="lan-section lan-send" aria-labelledby="lan-send-title">
          <div className="lan-section-heading">
            <div>
              <p className="eyebrow">发送队列</p>
              <h2 id="lan-send-title">选择要发送的文件</h2>
            </div>
            <label className={`lan-file-command ${canSend ? '' : 'lan-file-command--disabled'}`}>
              <UploadIcon />选择文件
              <input type="file" multiple disabled={!canSend} onChange={(event: ChangeEvent<HTMLInputElement>) => {
                const files = Array.from(event.target.files || [])
                if (files.length > 0) onSelectFiles(files)
              }} />
            </label>
          </div>
          <p className="lan-supporting">最多 20 个文件，单个文件不超过 2 GB；文件会依次直连传输。</p>
          {selectionError && <p className="lan-selection-error" role="alert">{selectionError}</p>}
        </section>

        <section className="lan-section lan-queue" aria-labelledby="lan-queue-title">
          <div className="lan-queue-summary">
            <h2 id="lan-queue-title">传输记录</h2>
            <span>{completed}/{peerState.items.length} 已完成</span>
          </div>
          {peerState.items.length === 0 ? (
            <div className="lan-empty">
              <TransferIcon />
              <p>{peerState.status === 'waiting' ? '等待 Android 进入快传' : '连接后可直接发送或接收文件'}</p>
            </div>
          ) : (
            <div className="lan-transfer-list" aria-live="polite">
              {peerState.items.map((item) => (
                <div className={`lan-transfer-row lan-transfer-row--${item.status}`} key={item.id}>
                  <span className="lan-direction" aria-label={item.direction === 'sending' ? '发送' : '接收'}>
                    {item.direction === 'sending' ? <UploadIcon /> : <DownloadIcon />}
                  </span>
                  <div className="lan-file-detail">
                    <strong>{item.name}</strong>
                    <div className="lan-progress" role="progressbar" aria-label={`${item.name} 进度`}
                      aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(item.progress * 100)}>
                      <span style={{ width: `${Math.round(item.progress * 100)}%` }} />
                    </div>
                    <small>{fileStatusLabel(item.status, item.error)}</small>
                  </div>
                  <div className="lan-transfer-metrics">
                    <strong>{Math.round(item.progress * 100)}%</strong>
                    <span>{formatSpeed(item.speedBytesPerSecond)}</span>
                  </div>
                  {item.direction === 'sending' && (item.status === 'failed' || item.status === 'cancelled') ? (
                    <button className="icon-button" type="button" onClick={() => onRetry(item.id)} aria-label={`重试 ${item.name}`} title="重试">
                      <RetryIcon />
                    </button>
                  ) : <span className="lan-row-action-space" />}
                </div>
              ))}
            </div>
          )}
          {current && (
            <div className="lan-current-summary">
              <span>当前：{current.name}</span>
              <span>{formatBytes(current.transferredBytes)} / {formatBytes(current.size)} · {formatSpeed(current.speedBytesPerSecond)}</span>
              <button className="batch-action" type="button" onClick={onCancel} aria-label="取消当前传输">取消</button>
            </div>
          )}
        </section>
      </main>
    </div>
  )
}

function createDefaultPeer() {
  return new LanPeer(new LanSignalingClient())
}

function defaultChooseDirectory() {
  const picker = (window as Window & { showDirectoryPicker?: (options: { id: string, mode: 'readwrite' }) => Promise<DirectoryHandle> }).showDirectoryPicker
  if (!picker) return Promise.reject(new Error('DIRECTORY_PICKER_UNAVAILABLE'))
  return picker({ id: 'transdot-lan-receive', mode: 'readwrite' })
}

function directoryActionLabel(status: DirectoryStatus) {
  if (status === 'permission-required') return '重新授权接收文件夹'
  if (status === 'denied') return '选择其他接收文件夹'
  return '选择接收文件夹'
}

function connectionLabel(state: LanPeerState, seconds: number) {
  if (state.status === 'waiting') return '等待 Android 进入快传'
  if (state.status === 'connecting') return `正在建立局域网连接 · ${seconds} 秒`
  if (state.status === 'connected') return '局域网直连已建立'
  if (state.status === 'transferring') return '局域网直连 · 传输中'
  if (state.status === 'failed') return errorLabel(state.error)
  return '快传已关闭'
}

function fileStatusLabel(status: LanPeerState['items'][number]['status'], error?: string) {
  if (status === 'queued') return '等待传输'
  if (status === 'transferring') return '正在传输'
  if (status === 'completed') return '校验完成'
  if (status === 'cancelled') return '已取消'
  return errorLabel(error)
}

function errorLabel(error?: string) {
  const labels: Record<string, string> = {
    LAN_CONNECT_TIMEOUT: '8 秒内无法建立局域网直连',
    LAN_PEER_OFFLINE: 'Android 已离线',
    LAN_SESSION_BUSY: '另一组快传正在进行',
    DESTINATION_UNAVAILABLE: '接收位置不可用',
    HASH_MISMATCH: '文件校验失败',
    FILE_TOO_LARGE: '文件超过 2 GB',
  }
  return error && labels[error] ? labels[error] : '直连不可用'
}

function selectionErrorMessage(error: unknown) {
  if (error instanceof Error && error.message === 'TOO_MANY_FILES') return '一次最多选择 20 个文件。'
  if (error instanceof Error && error.message === 'FILE_TOO_LARGE') return '单个文件不能超过 2 GB。'
  return '无法加入发送队列。'
}

function formatSpeed(bytesPerSecond: number) {
  if (!bytesPerSecond) return '0 B/s'
  if (bytesPerSecond >= 1024 * 1024) return `${(bytesPerSecond / (1024 * 1024)).toFixed(1)} MB/s`
  if (bytesPerSecond >= 1024) return `${(bytesPerSecond / 1024).toFixed(1)} KB/s`
  return `${bytesPerSecond} B/s`
}

function formatBytes(bytes: number) {
  if (bytes >= 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024 * 1024)).toFixed(1)} GB`
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${bytes} B`
}

function BackIcon() { return <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 18-6-6 6-6" /></svg> }
function FolderIcon() { return <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 7.5h7l2-2h9v13H3z" /></svg> }
function UploadIcon() { return <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 16V4m-5 5 5-5 5 5M5 20h14" /></svg> }
function DownloadIcon() { return <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 4v12m-5-5 5 5 5-5M5 20h14" /></svg> }
function TransferIcon() { return <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 8h14m-4-4 4 4-4 4M20 16H6m4-4-4 4 4 4" /></svg> }
function RetryIcon() { return <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7" /></svg> }
