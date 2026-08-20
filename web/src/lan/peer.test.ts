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
import type { DirectoryHandle } from './types'

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

class FakeWritable {
  write = vi.fn(async (_data: Uint8Array) => {})
  close = vi.fn(async () => {})
  abort = vi.fn(async () => {})
}

class FakeDirectory implements DirectoryHandle {
  kind = 'directory' as const
  name = 'Downloads'
  writable = new FakeWritable()
  removeEntry = vi.fn(async (_name: string) => {})
  queryPermission = vi.fn(async () => 'granted' as const)
  requestPermission = vi.fn(async () => 'granted' as const)
  getFileHandle = vi.fn(async (_name: string, options?: { create?: boolean }) => {
    if (!options?.create) throw new DOMException('Not found', 'NotFoundError')
    return { createWritable: async () => this.writable }
  })
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

  it('does not send a cancelled chunk after the next file offer', async () => {
    const signals = new FakeSignals()
    const connection = new FakeConnection()
    const ids = ['file-1', 'file-2']
    const peer = new LanPeer(signals, () => connection, {
      setTimeout, clearTimeout, now: () => 1_000, id: () => ids.shift()!,
    })
    signals.listener?.({ type: 'lan.peer_online', sessionId: 's1' })
    await vi.waitFor(() => expect(signals.offers).toHaveLength(1))
    connection.channel.open()
    connection.channel.bufferedAmount = BUFFERED_AMOUNT_HIGH
    peer.sendFiles([new File(['first'], 'one.txt'), new File(['second'], 'two.txt')])
    connection.channel.receive(JSON.stringify({ type: 'file_accept', file_id: 'file-1' }))
    await vi.waitFor(() => expect(connection.channel.onbufferedamountlow).toBeTypeOf('function'))

    expect(peer.cancelTransfer()).toBe(true)
    expect(controlFrames(connection.channel).filter((frame) => frame.type === 'file_offer')).toHaveLength(2)
    connection.channel.bufferedAmount = BUFFERED_AMOUNT_LOW
    connection.channel.onbufferedamountlow?.()
    await new Promise((resolve) => setTimeout(resolve, 10))

    expect(connection.channel.sent.filter((frame) => frame instanceof ArrayBuffer)).toHaveLength(0)
    expect(peer.state).toMatchObject({ status: 'transferring', currentFileId: 'file-2' })
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

  it('makes an outgoing transfer retryable after peer-offline and reconnect', async () => {
    const signals = new FakeSignals()
    const connections = [new FakeConnection(), new FakeConnection()]
    let connectionIndex = 0
    const peer = new LanPeer(signals, () => connections[connectionIndex++], {
      setTimeout, clearTimeout, now: () => 0, id: () => 'file-1',
    })
    signals.listener?.({ type: 'lan.peer_online', sessionId: 's1' })
    await vi.waitFor(() => expect(signals.offers).toHaveLength(1))
    connections[0].channel.open()
    peer.sendFiles([new File(['abc'], 'one.txt')])

    signals.listener?.({ type: 'lan.peer_offline', sessionId: 's1' })
    expect(peer.state.items[0]).toMatchObject({ status: 'failed', error: 'LAN_PEER_OFFLINE' })
    expect(peer.retry('file-1')).toBe(true)
    expect(peer.state.items[0].status).toBe('queued')

    signals.listener?.({ type: 'lan.peer_online', sessionId: 's2' })
    await vi.waitFor(() => expect(signals.offers).toHaveLength(2))
    connections[1].channel.open()
    expect(controlFrames(connections[1].channel)).toContainEqual(expect.objectContaining({ type: 'file_offer', file_id: 'file-1' }))
  })

  it.each([
    ['peer-offline', 'waiting', 'LAN_PEER_OFFLINE'],
    ['channel failure', 'failed', 'LAN_DATA_CHANNEL_ERROR'],
  ] as const)('cleans an incoming partial on %s', async (scenario, expectedStatus, expectedError) => {
    const signals = new FakeSignals()
    const connection = new FakeConnection()
    const directory = new FakeDirectory()
    const peer = new LanPeer(signals, () => connection, {
      setTimeout, clearTimeout, now: () => 0, id: () => 'unused',
    })
    peer.setReceiveDirectory(directory)
    signals.listener?.({ type: 'lan.peer_online', sessionId: 's1' })
    await vi.waitFor(() => expect(signals.offers).toHaveLength(1))
    connection.channel.open()
    connection.channel.receive(JSON.stringify({
      type: 'file_offer', file_id: 'incoming-1', name: 'incoming.txt', mime: 'text/plain', size: 3,
    }))
    await vi.waitFor(() => expect(controlFrames(connection.channel)).toContainEqual({ type: 'file_accept', file_id: 'incoming-1' }))
    connection.channel.receive(new Uint8Array([1]).buffer)
    await vi.waitFor(() => expect(directory.writable.write).toHaveBeenCalledOnce())

    if (scenario === 'peer-offline') signals.listener?.({ type: 'lan.peer_offline', sessionId: 's1' })
    else connection.channel.onerror?.()

    await vi.waitFor(() => expect(directory.writable.abort).toHaveBeenCalledOnce())
    expect(directory.removeEntry).toHaveBeenCalledOnce()
    expect(peer.state).toMatchObject({ status: expectedStatus, currentFileId: undefined })
    expect(peer.state.items[0]).toMatchObject({ status: 'failed', error: expectedError })
  })

  it('does not activate an incoming offer after peer-offline during file setup', async () => {
    const signals = new FakeSignals()
    const connection = new FakeConnection()
    const directory = new FakeDirectory()
    let finishWritableSetup: ((writable: FakeWritable) => void) | undefined
    const writableSetup = new Promise<FakeWritable>((resolve) => { finishWritableSetup = resolve })
    const createWritable = vi.fn(() => writableSetup)
    directory.getFileHandle = vi.fn(async (_name: string, options?: { create?: boolean }) => {
      if (!options?.create) throw new DOMException('Not found', 'NotFoundError')
      return { createWritable }
    })
    const peer = new LanPeer(signals, () => connection, {
      setTimeout, clearTimeout, now: () => 0, id: () => 'unused',
    })
    peer.setReceiveDirectory(directory)
    signals.listener?.({ type: 'lan.peer_online', sessionId: 's1' })
    await vi.waitFor(() => expect(signals.offers).toHaveLength(1))
    connection.channel.open()
    connection.channel.receive(JSON.stringify({
      type: 'file_offer', file_id: 'incoming-1', name: 'incoming.txt', mime: 'text/plain', size: 3,
    }))
    await vi.waitFor(() => expect(createWritable).toHaveBeenCalledOnce())

    signals.listener?.({ type: 'lan.peer_offline', sessionId: 's1' })
    finishWritableSetup?.(directory.writable)

    await vi.waitFor(() => expect(directory.writable.abort).toHaveBeenCalledOnce())
    expect(directory.removeEntry).toHaveBeenCalledWith('incoming.txt')
    expect(controlFrames(connection.channel)).not.toContainEqual({ type: 'file_accept', file_id: 'incoming-1' })
    expect(peer.state).toMatchObject({ status: 'waiting', currentFileId: undefined, items: [] })
  })

  it('does not reject an old incoming offer over a reconnected channel', async () => {
    const signals = new FakeSignals()
    const connections = [new FakeConnection(), new FakeConnection()]
    const directory = new FakeDirectory()
    let failWritableSetup: ((error: Error) => void) | undefined
    const writableSetup = new Promise<FakeWritable>((_resolve, reject) => { failWritableSetup = reject })
    const createWritable = vi.fn(() => writableSetup)
    directory.getFileHandle = vi.fn(async (_name: string, options?: { create?: boolean }) => {
      if (!options?.create) throw new DOMException('Not found', 'NotFoundError')
      return { createWritable }
    })
    let connectionIndex = 0
    const peer = new LanPeer(signals, () => connections[connectionIndex++], {
      setTimeout, clearTimeout, now: () => 0, id: () => 'unused',
    })
    peer.setReceiveDirectory(directory)
    signals.listener?.({ type: 'lan.peer_online', sessionId: 's1' })
    await vi.waitFor(() => expect(signals.offers).toHaveLength(1))
    connections[0].channel.open()
    connections[0].channel.receive(JSON.stringify({
      type: 'file_offer', file_id: 'incoming-old', name: 'old.txt', mime: 'text/plain', size: 3,
    }))
    await vi.waitFor(() => expect(createWritable).toHaveBeenCalledOnce())

    signals.listener?.({ type: 'lan.peer_offline', sessionId: 's1' })
    signals.listener?.({ type: 'lan.peer_online', sessionId: 's2' })
    await vi.waitFor(() => expect(signals.offers).toHaveLength(2))
    connections[1].channel.open()
    failWritableSetup?.(new Error('setup failed'))
    await new Promise((resolve) => setTimeout(resolve, 10))

    expect(controlFrames(connections[1].channel)).not.toContainEqual({
      type: 'file_reject', file_id: 'incoming-old', code: 'DESTINATION_UNAVAILABLE',
    })
    expect(peer.state.status).toBe('connected')
  })

  it('cleans an incoming partial when the peer is closed', async () => {
    const signals = new FakeSignals()
    const connection = new FakeConnection()
    const directory = new FakeDirectory()
    const peer = new LanPeer(signals, () => connection, {
      setTimeout, clearTimeout, now: () => 0, id: () => 'unused',
    })
    peer.setReceiveDirectory(directory)
    signals.listener?.({ type: 'lan.peer_online', sessionId: 's1' })
    await vi.waitFor(() => expect(signals.offers).toHaveLength(1))
    connection.channel.open()
    connection.channel.receive(JSON.stringify({
      type: 'file_offer', file_id: 'incoming-1', name: 'incoming.txt', mime: 'text/plain', size: 3,
    }))
    await vi.waitFor(() => expect(controlFrames(connection.channel)).toContainEqual({
      type: 'file_accept', file_id: 'incoming-1',
    }))

    peer.close()

    await vi.waitFor(() => expect(directory.writable.abort).toHaveBeenCalledOnce())
    expect(directory.removeEntry).toHaveBeenCalledWith('incoming.txt')
    expect(peer.state).toMatchObject({ status: 'closed', currentFileId: undefined })
  })

  it('cancels and removes an incoming partial while notifying the peer', async () => {
    const signals = new FakeSignals()
    const connection = new FakeConnection()
    const directory = new FakeDirectory()
    const peer = new LanPeer(signals, () => connection, {
      setTimeout, clearTimeout, now: () => 0, id: () => 'unused',
    })
    peer.setReceiveDirectory(directory)
    signals.listener?.({ type: 'lan.peer_online', sessionId: 's1' })
    await vi.waitFor(() => expect(signals.offers).toHaveLength(1))
    connection.channel.open()
    connection.channel.receive(JSON.stringify({
      type: 'file_offer', file_id: 'incoming-1', name: 'incoming.txt', mime: 'text/plain', size: 3,
    }))
    await vi.waitFor(() => expect(controlFrames(connection.channel)).toContainEqual({ type: 'file_accept', file_id: 'incoming-1' }))

    expect(peer.cancelTransfer()).toBe(true)
    expect(controlFrames(connection.channel)).toContainEqual({ type: 'transfer_cancel', file_id: 'incoming-1' })
    await vi.waitFor(() => expect(directory.writable.abort).toHaveBeenCalledOnce())
    expect(directory.removeEntry).toHaveBeenCalledWith('incoming.txt')
    expect(peer.state).toMatchObject({ status: 'connected', currentFileId: undefined })
    expect(peer.state.items[0]).toMatchObject({ status: 'cancelled', error: 'TRANSFER_CANCELLED' })
    expect(controlFrames(connection.channel).filter((frame) => frame.type === 'file_failed')).toHaveLength(0)
  })

  it('rejects the twenty-first incoming offer until queue-complete resets the boundary', async () => {
    const signals = new FakeSignals()
    const connection = new FakeConnection()
    const directory = new FakeDirectory()
    const peer = new LanPeer(signals, () => connection, {
      setTimeout, clearTimeout, now: () => 0, id: () => 'unused',
    })
    peer.setReceiveDirectory(directory)
    signals.listener?.({ type: 'lan.peer_online', sessionId: 's1' })
    await vi.waitFor(() => expect(signals.offers).toHaveLength(1))
    connection.channel.open()

    for (let index = 1; index <= 20; index += 1) {
      const fileId = `incoming-${index}`
      connection.channel.receive(JSON.stringify({
        type: 'file_offer', file_id: fileId, name: `${index}.txt`, mime: 'text/plain', size: 0,
      }))
      await vi.waitFor(() => expect(controlFrames(connection.channel)).toContainEqual({ type: 'file_accept', file_id: fileId }))
      connection.channel.receive(JSON.stringify({
        type: 'file_complete', file_id: fileId,
        sha256: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
      }))
      await vi.waitFor(() => expect(controlFrames(connection.channel)).toContainEqual({ type: 'file_verified', file_id: fileId }))
    }

    connection.channel.receive(JSON.stringify({
      type: 'file_offer', file_id: 'incoming-21', name: '21.txt', mime: 'text/plain', size: 0,
    }))
    await vi.waitFor(() => expect(controlFrames(connection.channel)).toContainEqual({
      type: 'file_reject', file_id: 'incoming-21', code: 'TOO_MANY_FILES',
    }))
    expect(peer.state.items.filter((item) => item.direction === 'receiving')).toHaveLength(20)

    connection.channel.receive(JSON.stringify({ type: 'queue_complete' }))
    connection.channel.receive(JSON.stringify({
      type: 'file_offer', file_id: 'incoming-22', name: '22.txt', mime: 'text/plain', size: 0,
    }))
    await vi.waitFor(() => expect(controlFrames(connection.channel)).toContainEqual({ type: 'file_accept', file_id: 'incoming-22' }))
  })
})

function controlFrames(channel: FakeChannel) {
  return channel.sent.filter((data): data is string => typeof data === 'string').map((data) => JSON.parse(data))
}
