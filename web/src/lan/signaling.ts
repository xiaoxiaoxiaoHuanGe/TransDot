export interface SocketLike {
  readyState: number
  // Event payloads are transport-owned; the client reads only message.data.
  onopen: ((event: any) => unknown) | null
  onmessage: ((event: { data: string } & any) => unknown) | null
  onclose: ((event: any) => unknown) | null
  onerror: ((event: any) => unknown) | null
  send(data: string): void
  close(): void
}

export type LanSignalType =
  | 'lan.waiting'
  | 'lan.peer_online'
  | 'lan.peer_offline'
  | 'lan.offer'
  | 'lan.answer'
  | 'lan.ice'
  | 'lan.cancelled'
  | 'lan.error'

export type LanSignalEvent = {
  type: LanSignalType
  sessionId: string
  data?: unknown
}

export type LanIceCandidate = {
  candidate: string
  sdpMid: string | null
  sdpMLineIndex: number | null
}

type Listener = (event: LanSignalEvent) => void
type LocationLike = Pick<Location, 'protocol' | 'host'>

const serverSignalTypes = new Set<LanSignalType>([
  'lan.peer_online', 'lan.peer_offline', 'lan.offer', 'lan.answer', 'lan.ice', 'lan.cancelled', 'lan.error',
])

export function lanWebSocketURL(location: LocationLike) {
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${location.host}/ws`
}

export class LanSignalingClient {
  private socket!: SocketLike
  private readonly listeners = new Set<Listener>()
  private reconnectTimer?: ReturnType<typeof setTimeout>
  private disposed = false
  sessionId = ''

  constructor(
    private readonly url: string = lanWebSocketURL(window.location),
    private readonly socketFactory: (url: string) => SocketLike = (address) => new WebSocket(address),
    private readonly reconnectDelayMs = 800,
  ) {
    this.connect()
  }

  subscribe(listener: Listener) {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  sendOffer(sdp: string) { return this.send('lan.offer', { sdp }) }
  sendAnswer(sdp: string) { return this.send('lan.answer', { sdp }) }
  markConnected() { return this.send('lan.connected') }
  cancel() { return this.send('lan.cancel') }

  sendICE(ice: LanIceCandidate) {
    if (!isHostCandidate(ice.candidate) || ice.sdpMLineIndex === null) return false
    return this.send('lan.ice', {
      candidate: ice.candidate,
      sdp_mid: ice.sdpMid,
      sdp_mline_index: ice.sdpMLineIndex,
    })
  }

  close() {
    if (this.disposed) return
    if (this.socket.readyState === 1 && this.sessionId) this.send('lan.leave')
    this.disposed = true
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer)
    this.socket.close()
    this.listeners.clear()
  }

  private connect() {
    if (this.disposed) return
    const socket = this.socketFactory(this.url)
    this.socket = socket
    socket.onopen = () => {
      if (socket !== this.socket || this.disposed) return
      this.sendRaw({ type: 'lan.ready', timestamp: new Date().toISOString() })
    }
    socket.onmessage = (event) => { if (socket === this.socket && !this.disposed) this.receive(event.data) }
    socket.onerror = () => {
      if (socket === this.socket && !this.disposed) {
        this.emit({ type: 'lan.error', sessionId: this.sessionId, data: { code: 'LAN_SIGNAL_DISCONNECTED' } })
      }
    }
    socket.onclose = () => {
      if (socket !== this.socket || this.disposed) return
      this.sessionId = ''
      this.emit({ type: 'lan.waiting', sessionId: '' })
      this.reconnectTimer = setTimeout(() => this.connect(), this.reconnectDelayMs)
    }
  }

  private send(type: string, data?: unknown) {
    if (this.socket.readyState !== 1 || !this.sessionId) return false
    this.sendRaw({ type, session_id: this.sessionId, timestamp: new Date().toISOString(), ...(data === undefined ? {} : { data }) })
    return true
  }

  private sendRaw(value: unknown) { this.socket.send(JSON.stringify(value)) }

  private receive(encoded: string) {
    let envelope: unknown
    try { envelope = JSON.parse(encoded) } catch { return }
    if (!envelope || typeof envelope !== 'object') return
    const event = envelope as { type?: unknown, data?: unknown }
    if (typeof event.type !== 'string' || !serverSignalTypes.has(event.type as LanSignalType)) return
    const payload = event.data && typeof event.data === 'object'
      ? event.data as { session_id?: unknown, data?: unknown, code?: unknown }
      : {}
    const sessionId = typeof payload.session_id === 'string' ? payload.session_id : this.sessionId
    if (event.type === 'lan.peer_online' && sessionId) this.sessionId = sessionId
    const data = payload.data ?? (payload.code ? { code: payload.code } : undefined)
    if (event.type === 'lan.ice') {
      const ice = parseIceCandidate(data)
      if (!ice || !isHostCandidate(ice.candidate)) return
      this.emit({ type: event.type as LanSignalType, sessionId, data: ice })
      return
    }
    if ((event.type === 'lan.peer_offline' || event.type === 'lan.cancelled') && sessionId === this.sessionId) {
      this.sessionId = ''
    }
    this.emit({ type: event.type as LanSignalType, sessionId, data })
  }

  private emit(event: LanSignalEvent) { for (const listener of this.listeners) listener(event) }
}

function parseIceCandidate(data: unknown): LanIceCandidate | undefined {
  if (!data || typeof data !== 'object') return undefined
  const value = data as Record<string, unknown>
  const candidate = value.candidate
  const sdpMid = value.sdp_mid
  const sdpMLineIndex = value.sdp_mline_index
  if (typeof candidate !== 'string') return undefined
  if (sdpMid !== null && typeof sdpMid !== 'string') return undefined
  if (!Number.isInteger(sdpMLineIndex) || (sdpMLineIndex as number) < 0) return undefined
  return { candidate, sdpMid, sdpMLineIndex: sdpMLineIndex as number | null }
}

export function isHostCandidate(candidate: string) {
  const fields = candidate.toLowerCase().trim().split(/\s+/)
  const typeIndex = fields.indexOf('typ')
  return typeIndex >= 0 && fields[typeIndex + 1] === 'host'
}
