import { afterEach, describe, expect, it, vi } from 'vitest'
import { LanSignalingClient, lanWebSocketURL, type SocketLike } from './signaling'

class FakeSocket implements SocketLike {
  readyState = 0
  sent: string[] = []
  onopen: ((event: any) => unknown) | null = null
  onmessage: ((event: { data: string } & any) => unknown) | null = null
  onclose: ((event: any) => unknown) | null = null
  onerror: ((event: any) => unknown) | null = null
  send(data: string) { this.sent.push(data) }
  close() { this.readyState = 3; this.onclose?.({}) }
  open() { this.readyState = 1; this.onopen?.({}) }
  receive(value: unknown) { this.onmessage?.({ data: JSON.stringify(value) }) }
}

describe('LanSignalingClient', () => {
  afterEach(() => vi.useRealTimers())

  it('uses the authenticated existing-origin WebSocket endpoint', () => {
    expect(lanWebSocketURL({ protocol: 'https:', host: 'transfer.example' })).toBe('wss://transfer.example/ws')
    expect(lanWebSocketURL({ protocol: 'http:', host: '192.168.1.2:5757' })).toBe('ws://192.168.1.2:5757/ws')
  })

  it('announces readiness and accepts the server session', () => {
    const socket = new FakeSocket()
    const listener = vi.fn()
    const client = new LanSignalingClient('wss://example.test/ws', () => socket)
    client.subscribe(listener)
    socket.open()
    expect(JSON.parse(socket.sent[0])).toMatchObject({ type: 'lan.ready' })
    socket.receive({
      event_id: 'e1', type: 'lan.peer_online', timestamp: new Date().toISOString(),
      data: { session_id: 'session-1', data: null },
    })
    expect(client.sessionId).toBe('session-1')
    expect(listener).toHaveBeenCalledWith(expect.objectContaining({ type: 'lan.peer_online', sessionId: 'session-1' }))
  })

  it('sends only Host ICE candidates', () => {
    const socket = new FakeSocket()
    const client = new LanSignalingClient('wss://example.test/ws', () => socket)
    socket.open()
    socket.receive({ event_id: 'e1', type: 'lan.peer_online', timestamp: '', data: { session_id: 's1' } })
    expect(client.sendICE({
      candidate: 'candidate:1 1 UDP 1 host.local 5000 typ host', sdpMid: '0', sdpMLineIndex: 0,
    })).toBe(true)
    expect(client.sendICE({
      candidate: 'candidate:2 1 UDP 1 203.0.113.2 5001 typ srflx', sdpMid: '0', sdpMLineIndex: 0,
    })).toBe(false)
    expect(socket.sent).toHaveLength(2)
    expect(JSON.parse(socket.sent[1]).data).toEqual({
      candidate: 'candidate:1 1 UDP 1 host.local 5000 typ host', sdp_mid: '0', sdp_mline_index: 0,
    })
  })

  it('does not send an offer until the server creates a session', () => {
    const socket = new FakeSocket()
    const client = new LanSignalingClient('wss://example.test/ws', () => socket)
    socket.open()
    expect(client.sendOffer('too-early')).toBe(false)
    socket.receive({ event_id: 'e1', type: 'lan.peer_online', timestamp: '', data: { session_id: 's1' } })
    expect(client.sendOffer('web-offer')).toBe(true)
    expect(socket.sent.map((message) => JSON.parse(message).type)).toEqual(['lan.ready', 'lan.offer'])
  })

  it('invalidates the server session when the peer leaves', () => {
    const socket = new FakeSocket()
    const client = new LanSignalingClient('wss://example.test/ws', () => socket)
    socket.open()
    socket.receive({ event_id: 'e1', type: 'lan.peer_online', timestamp: '', data: { session_id: 's1' } })
    socket.receive({ event_id: 'e2', type: 'lan.peer_offline', timestamp: '', data: { session_id: 's1' } })
    expect(client.sessionId).toBe('')
    expect(client.sendOffer('stale-offer')).toBe(false)
  })

  it('does not let a stale offline event clear a replacement session', () => {
    const socket = new FakeSocket()
    const client = new LanSignalingClient('wss://example.test/ws', () => socket)
    socket.open()
    socket.receive({ event_id: 'e1', type: 'lan.peer_online', timestamp: '', data: { session_id: 'old' } })
    socket.receive({ event_id: 'e2', type: 'lan.peer_online', timestamp: '', data: { session_id: 'new' } })
    socket.receive({ event_id: 'e3', type: 'lan.peer_offline', timestamp: '', data: { session_id: 'old' } })
    expect(client.sessionId).toBe('new')
    expect(client.sendOffer('current-offer')).toBe(true)
  })

  it('ignores non-Host ICE received from the remote peer', () => {
    const socket = new FakeSocket()
    const listener = vi.fn()
    const client = new LanSignalingClient('wss://example.test/ws', () => socket)
    client.subscribe(listener)
    socket.open()
    socket.receive({ event_id: 'e1', type: 'lan.peer_online', timestamp: '', data: { session_id: 's1' } })
    socket.receive({ event_id: 'e2', type: 'lan.ice', timestamp: '', data: { session_id: 's1', data: { candidate: 'candidate:2 1 UDP 1 203.0.113.2 6 typ srflx' } } })
    socket.receive({ event_id: 'e3', type: 'lan.ice', timestamp: '', data: { session_id: 's1', data: {
      candidate: 'candidate:1 1 UDP 1 host.local 5 typ host', sdp_mid: '0', sdp_mline_index: 0,
    } } })
    expect(listener.mock.calls.filter(([event]) => event.type === 'lan.ice')).toHaveLength(1)
    expect(listener).toHaveBeenCalledWith(expect.objectContaining({ data: {
      candidate: 'candidate:1 1 UDP 1 host.local 5 typ host', sdpMid: '0', sdpMLineIndex: 0,
    } }))
  })

  it('returns to waiting and re-announces readiness when the socket reconnects', async () => {
    vi.useFakeTimers()
    const sockets: FakeSocket[] = []
    const listener = vi.fn()
    const client = new LanSignalingClient('wss://example.test/ws', () => {
      const socket = new FakeSocket()
      sockets.push(socket)
      return socket
    })
    client.subscribe(listener)
    const active = sockets[0]
    active.open()
    active.receive({ event_id: 'e1', type: 'lan.peer_online', timestamp: '', data: { session_id: 's1' } })
    active.close()
    expect(client.sessionId).toBe('')
    expect(listener).toHaveBeenCalledWith({ type: 'lan.waiting', sessionId: '' })
    await vi.advanceTimersByTimeAsync(800)
    const reconnected = sockets[1]
    expect(reconnected).not.toBe(active)
    reconnected.open()
    expect(JSON.parse(reconnected.sent[0])).toMatchObject({ type: 'lan.ready' })
  })

  it('leaves without putting file metadata on the signaling socket', () => {
    const socket = new FakeSocket()
    const client = new LanSignalingClient('wss://example.test/ws', () => socket)
    socket.open()
    socket.receive({ event_id: 'e1', type: 'lan.peer_online', timestamp: '', data: { session_id: 's1' } })
    client.close()
    expect(socket.sent.some((message) => JSON.parse(message).type === 'lan.leave')).toBe(true)
    expect(socket.sent.join('')).not.toContain('filename')
    expect(socket.sent.join('')).not.toContain('sha256')
  })
})
