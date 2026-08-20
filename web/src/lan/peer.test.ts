import { afterEach, describe, expect, it, vi } from 'vitest'
import { LAN_CHUNK_BYTES } from './protocol'
import {
  BUFFERED_AMOUNT_HIGH,
  BUFFERED_AMOUNT_LOW,
  LanPeer,
  type DataChannelLike,
  type PeerConnectionLike,
  type SignalTransport,
} from './peer'
import type { LanSignalEvent } from './signaling'

class FakeSignals implements SignalTransport {
  listener?: (event: LanSignalEvent) => void
  offers: string[] = []
  candidates: string[] = []
  connected = 0
  subscribe(listener: (event: LanSignalEvent) => void) { this.listener = listener; return () => { this.listener = undefined } }
  sendOffer(sdp: string) { this.offers.push(sdp); return true }
  sendICE(candidate: string) { this.candidates.push(candidate); return true }
  markConnected() { this.connected++; return true }
  cancel() { return true }
}

class FakeChannel implements DataChannelLike {
  readyState = 'connecting'
  bufferedAmount = 0
  bufferedAmountLowThreshold = 0
  binaryType = 'blob'
  onopen: (() => void) | null = null
  onclose: (() => void) | null = null
  onerror: (() => void) | null = null
  onmessage: ((event: { data: string | ArrayBuffer }) => void) | null = null
  onbufferedamountlow: (() => void) | null = null
  sent: (string | ArrayBuffer)[] = []
  onSend?: (data: string | ArrayBuffer) => void
  send(data: string | ArrayBuffer) { this.sent.push(data); this.onSend?.(data) }
  close() { this.readyState = 'closed'; this.onclose?.() }
  open() { this.readyState = 'open'; this.onopen?.() }
  receive(data: string | ArrayBuffer) { this.onmessage?.({ data }) }
}

class FakeConnection implements PeerConnectionLike {
  onicecandidate: ((event: { candidate: { candidate: string } | null }) => void) | null = null
  onconnectionstatechange: (() => void) | null = null
  connectionState = 'new'
  channel = new FakeChannel()
  remote: unknown
  channelOptions?: RTCDataChannelInit
  createDataChannel(_label: string, options: RTCDataChannelInit) { this.channelOptions = options; return this.channel }
  async createOffer() { return { type: 'offer' as RTCSdpType, sdp: 'web-offer' } }
  async setLocalDescription() {}
  async setRemoteDescription(description: RTCSessionDescriptionInit) { this.remote = description }
  async addIceCandidate() {}
  close() { this.connectionState = 'closed' }
}

describe('LanPeer', () => {
  afterEach(() => vi.useRealTimers())

  it('creates an empty-ICE ordered DataChannel and becomes connected', async () => {
    const signals = new FakeSignals()
    const connection = new FakeConnection()
    const factory = vi.fn((configuration: RTCConfiguration) => {
      expect(configuration.iceServers).toEqual([])
      return connection
    })
    const peer = new LanPeer(signals, factory)
    signals.listener?.({ type: 'lan.peer_online', sessionId: 's1' })
    await vi.waitFor(() => expect(signals.offers).toEqual(['web-offer']))
    expect(connection.channelOptions).toEqual({ ordered: true })
    connection.channel.open()
    expect(peer.state.status).toBe('connected')
    expect(signals.connected).toBe(1)
  })

  it('sends only Host candidates and applies the Android answer', async () => {
    const signals = new FakeSignals()
    const connection = new FakeConnection()
    new LanPeer(signals, () => connection)
    signals.listener?.({ type: 'lan.peer_online', sessionId: 's1' })
    await vi.waitFor(() => expect(signals.offers).toHaveLength(1))
    connection.onicecandidate?.({ candidate: { candidate: 'candidate:1 1 UDP 1 host.local 5 typ host' } })
    connection.onicecandidate?.({ candidate: { candidate: 'candidate:2 1 UDP 1 203.0.113.2 6 typ srflx' } })
    expect(signals.candidates).toHaveLength(1)
    signals.listener?.({ type: 'lan.answer', sessionId: 's1', data: { sdp: 'android-answer' } })
    await vi.waitFor(() => expect(connection.remote).toEqual({ type: 'answer', sdp: 'android-answer' }))
  })

  it('fails after eight seconds without silently falling back', async () => {
    const signals = new FakeSignals()
    let timeout: (() => void) | undefined
    const clearTimeout = vi.fn()
    const peer = new LanPeer(signals, () => new FakeConnection(), {
      setTimeout: (callback, delay) => {
        expect(delay).toBe(8_000)
        timeout = callback
        return 1
      },
      clearTimeout,
      now: () => 0,
      id: () => 'file-id',
    })
    signals.listener?.({ type: 'lan.peer_online', sessionId: 's1' })
    await vi.waitFor(() => expect(timeout).toBeTypeOf('function'))
    timeout?.()
    expect(peer.state).toMatchObject({ status: 'failed', error: 'LAN_CONNECT_TIMEOUT' })
    expect(clearTimeout).toHaveBeenCalled()
  })

  it('pauses at 4 MiB and resumes at the 1 MiB low-water mark', async () => {
    const signals = new FakeSignals()
    const connection = new FakeConnection()
    const peer = new LanPeer(signals, () => connection, {
      setTimeout, clearTimeout, now: () => 1_000, id: () => 'file-1',
    })
    signals.listener?.({ type: 'lan.peer_online', sessionId: 's1' })
    await vi.waitFor(() => expect(signals.offers).toHaveLength(1))
    connection.channel.open()
    let binaryCount = 0
    connection.channel.onSend = (data) => {
      if (data instanceof ArrayBuffer && ++binaryCount === 1) connection.channel.bufferedAmount = BUFFERED_AMOUNT_HIGH
    }
    peer.sendFiles([new File([new Uint8Array(LAN_CHUNK_BYTES + 1)], 'large.bin')])
    connection.channel.receive(JSON.stringify({ type: 'file_accept', file_id: 'file-1' }))
    await vi.waitFor(() => expect(binaryCount).toBe(1))
    expect(connection.channel.bufferedAmountLowThreshold).toBe(BUFFERED_AMOUNT_LOW)
    await Promise.resolve()
    expect(binaryCount).toBe(1)
    connection.channel.bufferedAmount = BUFFERED_AMOUNT_LOW
    connection.channel.onbufferedamountlow?.()
    await vi.waitFor(() => expect(binaryCount).toBe(2))
  })

  it('waits for hash verification before advancing a one-file-at-a-time queue', async () => {
    const signals = new FakeSignals()
    const connection = new FakeConnection()
    const ids = ['file-1', 'file-2']
    let now = 1_000
    const peer = new LanPeer(signals, () => connection, {
      setTimeout, clearTimeout, now: () => now, id: () => ids.shift()!,
    })
    signals.listener?.({ type: 'lan.peer_online', sessionId: 's1' })
    await vi.waitFor(() => expect(signals.offers).toHaveLength(1))
    connection.channel.open()
    peer.sendFiles([new File(['abc'], 'one.txt'), new File(['def'], 'two.txt')])
    expect(controlFrames(connection.channel)).toEqual([
      expect.objectContaining({ type: 'file_offer', file_id: 'file-1', name: 'one.txt' }),
    ])
    now = 2_000
    connection.channel.receive(JSON.stringify({ type: 'file_accept', file_id: 'file-1' }))
    await vi.waitFor(() => expect(controlFrames(connection.channel).some((frame) => frame.type === 'file_complete')).toBe(true))
    expect(controlFrames(connection.channel)).toContainEqual({
      type: 'file_complete', file_id: 'file-1',
      sha256: 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
    })
    expect(controlFrames(connection.channel).filter((frame) => frame.type === 'file_offer')).toHaveLength(1)
    connection.channel.receive(JSON.stringify({ type: 'file_verified', file_id: 'file-1' }))
    await vi.waitFor(() => expect(controlFrames(connection.channel).filter((frame) => frame.type === 'file_offer')).toHaveLength(2))
    expect(peer.state.items[0]).toMatchObject({ status: 'completed', progress: 1 })
    expect(peer.state).toMatchObject({ status: 'transferring', currentFileId: 'file-2' })
  })

  it('keeps failures local, supports retry and cancel, and never calls a cloud API', async () => {
    const originalFetch = globalThis.fetch
    const fetchSpy = vi.fn()
    globalThis.fetch = fetchSpy
    try {
      const signals = new FakeSignals()
      const connection = new FakeConnection()
      const peer = new LanPeer(signals, () => connection, {
        setTimeout, clearTimeout, now: () => 1_000, id: () => 'file-1',
      })
      signals.listener?.({ type: 'lan.peer_online', sessionId: 's1' })
      await vi.waitFor(() => expect(signals.offers).toHaveLength(1))
      connection.channel.open()
      peer.sendFiles([new File(['abc'], 'one.txt')])
      connection.channel.receive(JSON.stringify({ type: 'file_reject', file_id: 'file-1', code: 'DESTINATION_UNAVAILABLE' }))
      await vi.waitFor(() => expect(peer.state.items[0]).toMatchObject({ status: 'failed', error: 'DESTINATION_UNAVAILABLE' }))
      peer.retry('file-1')
      expect(peer.state.items[0].status).toBe('transferring')
      peer.cancelTransfer()
      expect(peer.state.items[0].status).toBe('cancelled')
      expect(controlFrames(connection.channel)).toContainEqual({ type: 'transfer_cancel', file_id: 'file-1' })
      expect(fetchSpy).not.toHaveBeenCalled()
    } finally {
      globalThis.fetch = originalFetch
    }
  })

  it('caps the accumulated queue at twenty files', () => {
    const signals = new FakeSignals()
    let index = 0
    const peer = new LanPeer(signals, () => new FakeConnection(), {
      setTimeout, clearTimeout, now: () => 0, id: () => `file-${++index}`,
    })
    peer.sendFiles(Array.from({ length: 20 }, (_, fileIndex) => new File(['x'], `${fileIndex}.txt`)))
    expect(() => peer.sendFiles([new File(['x'], 'overflow.txt')])).toThrowError('TOO_MANY_FILES')
  })
})

function controlFrames(channel: FakeChannel) {
  return channel.sent.filter((data): data is string => typeof data === 'string').map((data) => JSON.parse(data))
}
