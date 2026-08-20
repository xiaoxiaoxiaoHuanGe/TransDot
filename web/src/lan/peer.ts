import { sha256 } from '@noble/hashes/sha2.js'
import { bytesToHex } from '@noble/hashes/utils.js'
import {
  HIGH_WATER_BYTES,
  LAN_CHUNK_BYTES,
  LOW_WATER_BYTES,
  encodeControl,
  parseControl,
  sanitizeFilename,
  uniqueFilename,
  validateQueue,
} from './protocol'
import { isHostCandidate, type LanSignalEvent } from './signaling'
import type { DirectoryHandle, FileOffer, LanControlFrame } from './types'

export const BUFFERED_AMOUNT_HIGH = HIGH_WATER_BYTES
export const BUFFERED_AMOUNT_LOW = LOW_WATER_BYTES

export interface SignalTransport {
  subscribe(listener: (event: LanSignalEvent) => void): () => void
  sendOffer(sdp: string): boolean
  sendICE(candidate: string): boolean
  markConnected(): boolean
  cancel(): boolean
  close?(): void
}

export interface DataChannelLike {
  readyState: string
  bufferedAmount: number
  bufferedAmountLowThreshold: number
  binaryType: string
  onopen: (() => void) | null
  onclose: (() => void) | null
  onerror: (() => void) | null
  onmessage: ((event: { data: string | ArrayBuffer }) => void) | null
  onbufferedamountlow: (() => void) | null
  send(data: string | ArrayBuffer): void
  close(): void
}

export interface PeerConnectionLike {
  connectionState: string
  onicecandidate: ((event: { candidate: { candidate: string } | null }) => void) | null
  onconnectionstatechange: (() => void) | null
  createDataChannel(label: string, options: RTCDataChannelInit): DataChannelLike
  createOffer(): Promise<RTCSessionDescriptionInit>
  setLocalDescription(description: RTCSessionDescriptionInit): Promise<void>
  setRemoteDescription(description: RTCSessionDescriptionInit): Promise<void>
  addIceCandidate(candidate: RTCIceCandidateInit): Promise<void>
  close(): void
}

export type LanTransferItem = {
  id: string
  name: string
  size: number
  direction: 'sending' | 'receiving'
  status: 'queued' | 'transferring' | 'completed' | 'failed' | 'cancelled'
  transferredBytes: number
  progress: number
  speedBytesPerSecond: number
  error?: string
}

export type LanPeerState = {
  status: 'waiting' | 'connecting' | 'connected' | 'transferring' | 'failed' | 'closed'
  items: readonly LanTransferItem[]
  currentFileId?: string
  error?: string
}

export type LanPeerDependencies = {
  setTimeout(callback: () => void, delay: number): unknown
  clearTimeout(handle: unknown): void
  now(): number
  id(): string
}

type StateListener = (state: LanPeerState) => void
type WritableLike = {
  write(data: Uint8Array): Promise<void>
  close(): Promise<void>
  abort?(): Promise<void>
}
type FileHandleLike = { createWritable(): Promise<WritableLike> }
type SendRecord = LanTransferItem & { file: File, startedAt: number }
type ReceiveRecord = {
  offer: FileOffer
  name: string
  writable: WritableLike
  hash: ReturnType<typeof sha256.create>
  receivedBytes: number
  startedAt: number
}

const defaultDependencies: LanPeerDependencies = {
  setTimeout: (callback, delay) => globalThis.setTimeout(callback, delay),
  clearTimeout: (handle) => globalThis.clearTimeout(handle as ReturnType<typeof setTimeout>),
  now: () => Date.now(),
  id: () => crypto.randomUUID(),
}

export class LanPeer {
  state: LanPeerState = { status: 'waiting', items: [] }
  private readonly listeners = new Set<StateListener>()
  private readonly unsubscribe: () => void
  private readonly dependencies: LanPeerDependencies
  private connection?: PeerConnectionLike
  private channel?: DataChannelLike
  private timeout?: unknown
  private queue: SendRecord[] = []
  private current?: SendRecord
  private receiveDirectory?: DirectoryHandle
  private incoming?: ReceiveRecord
  private receivedNames = new Set<string>()
  private receiveChain = Promise.resolve()
  private transferGeneration = 0

  constructor(
    private readonly signaling: SignalTransport,
    private readonly peerFactory: (configuration: RTCConfiguration) => PeerConnectionLike =
      (configuration) => new RTCPeerConnection(configuration) as unknown as PeerConnectionLike,
    dependencies: Partial<LanPeerDependencies> = {},
  ) {
    this.dependencies = { ...defaultDependencies, ...dependencies }
    this.unsubscribe = signaling.subscribe((event) => { void this.handleSignal(event) })
  }

  subscribe(listener: StateListener) {
    this.listeners.add(listener)
    listener(this.state)
    return () => this.listeners.delete(listener)
  }

  setReceiveDirectory(directory?: DirectoryHandle) {
    this.receiveDirectory = directory
  }

  sendFiles(files: readonly File[]) {
    const pendingFiles = this.queue.filter((item) => item.status !== 'completed' && item.status !== 'cancelled')
    validateQueue([...pendingFiles, ...files])
    const records = files.map((file): SendRecord => ({
      id: this.dependencies.id(), file, name: sanitizeFilename(file.name), size: file.size,
      direction: 'sending', status: 'queued', transferredBytes: 0, progress: 0,
      speedBytesPerSecond: 0, startedAt: 0,
    }))
    this.queue.push(...records)
    this.publishItems()
    this.startNext()
    return records.map((record) => record.id)
  }

  retry(fileId: string) {
    const item = this.queue.find((candidate) => candidate.id === fileId)
    if (!item || (item.status !== 'failed' && item.status !== 'cancelled')) return false
    item.status = 'queued'
    item.error = undefined
    item.transferredBytes = 0
    item.progress = 0
    item.speedBytesPerSecond = 0
    this.publishItems()
    this.startNext()
    return true
  }

  cancelTransfer() {
    if (!this.current || !this.channel || this.channel.readyState !== 'open') return false
    const cancelled = this.current
    this.transferGeneration += 1
    this.sendControl({ type: 'transfer_cancel', file_id: cancelled.id })
    cancelled.status = 'cancelled'
    cancelled.error = 'TRANSFER_CANCELLED'
    this.current = undefined
    this.publishItems()
    this.startNext()
    return true
  }

  close() {
    if (this.state.status === 'closed') return
    this.transferGeneration += 1
    this.clearConnection()
    this.unsubscribe()
    this.signaling.close?.()
    this.setState({ ...this.state, status: 'closed', currentFileId: undefined, error: undefined })
  }

  private async handleSignal(event: LanSignalEvent) {
    if (event.type === 'lan.peer_online') {
      await this.connect()
      return
    }
    if (event.type === 'lan.answer') {
      const sdp = dataString(event.data, 'sdp')
      if (sdp && this.connection) await this.connection.setRemoteDescription({ type: 'answer', sdp })
      return
    }
    if (event.type === 'lan.ice') {
      const candidate = dataString(event.data, 'candidate')
      if (candidate && isHostCandidate(candidate) && this.connection) await this.connection.addIceCandidate({ candidate })
      return
    }
    if (event.type === 'lan.waiting' || event.type === 'lan.peer_offline' || event.type === 'lan.cancelled') {
      this.transferGeneration += 1
      this.clearConnection()
      this.setState({ ...this.state, status: 'waiting', currentFileId: undefined, error: undefined })
      return
    }
    if (event.type === 'lan.error') this.fail(dataString(event.data, 'code') || 'LAN_SIGNAL_ERROR')
  }

  private async connect() {
    if (this.state.status === 'connecting' || this.state.status === 'connected' || this.state.status === 'transferring') return
    this.clearConnection()
    this.setState({ ...this.state, status: 'connecting', error: undefined })
    const connection = this.peerFactory({ iceServers: [] })
    const channel = connection.createDataChannel('transdot-lan', { ordered: true })
    channel.binaryType = 'arraybuffer'
    channel.bufferedAmountLowThreshold = BUFFERED_AMOUNT_LOW
    this.connection = connection
    this.channel = channel
    channel.onopen = () => {
      this.clearConnectTimeout()
      this.signaling.markConnected()
      this.setState({ ...this.state, status: 'connected', error: undefined })
      this.startNext()
    }
    channel.onclose = () => { if (this.state.status !== 'closed' && this.state.status !== 'failed' && this.connection) this.fail('LAN_PEER_OFFLINE') }
    channel.onerror = () => this.fail('LAN_DATA_CHANNEL_ERROR')
    channel.onmessage = (event) => {
      this.receiveChain = this.receiveChain.then(() => this.receiveData(event.data)).catch(() => this.fail('LAN_PROTOCOL_ERROR'))
    }
    connection.onconnectionstatechange = () => {
      if (connection.connectionState === 'failed' || connection.connectionState === 'disconnected') this.fail('LAN_PEER_OFFLINE')
    }
    connection.onicecandidate = (event) => {
      if (event.candidate && isHostCandidate(event.candidate.candidate)) this.signaling.sendICE(event.candidate.candidate)
    }
    this.timeout = this.dependencies.setTimeout(() => {
      if (this.state.status === 'connecting') {
        this.signaling.cancel()
        this.fail('LAN_CONNECT_TIMEOUT')
      }
    }, 8_000)
    try {
      const offer = await connection.createOffer()
      await connection.setLocalDescription(offer)
      if (!offer.sdp || !this.signaling.sendOffer(offer.sdp)) this.fail('LAN_SIGNAL_DISCONNECTED')
    } catch {
      this.fail('LAN_NEGOTIATION_FAILED')
    }
  }

  private startNext() {
    if (this.current || this.incoming || this.channel?.readyState !== 'open') return
    const next = this.queue.find((item) => item.status === 'queued')
    if (!next) {
      if (this.state.status === 'transferring') {
        this.sendControl({ type: 'queue_complete' })
        this.setState({ ...this.state, status: 'connected', currentFileId: undefined })
      }
      return
    }
    next.status = 'transferring'
    next.startedAt = this.dependencies.now()
    this.current = next
    this.publishItems()
    this.sendControl({
      type: 'file_offer', file_id: next.id, name: next.name,
      mime: next.file.type || 'application/octet-stream', size: next.size,
    })
  }

  private async sendCurrent(record: SendRecord) {
    const generation = ++this.transferGeneration
    const hash = sha256.create()
    for (let offset = 0; offset < record.file.size; offset += LAN_CHUNK_BYTES) {
      if (generation !== this.transferGeneration || this.current !== record) return
      await this.waitForWritableChannel()
      const chunk = new Uint8Array(await record.file.slice(offset, Math.min(record.file.size, offset + LAN_CHUNK_BYTES)).arrayBuffer())
      hash.update(chunk)
      this.channel?.send(toArrayBuffer(chunk))
      record.transferredBytes += chunk.byteLength
      record.progress = record.size === 0 ? 1 : record.transferredBytes / record.size
      record.speedBytesPerSecond = transferSpeed(record.transferredBytes, record.startedAt, this.dependencies.now())
      this.publishItems()
    }
    if (generation !== this.transferGeneration || this.current !== record) return
    record.progress = record.size === 0 ? 1 : record.progress
    this.sendControl({ type: 'file_complete', file_id: record.id, sha256: bytesToHex(hash.digest()) })
    this.publishItems()
  }

  private waitForWritableChannel() {
    const channel = this.channel
    if (!channel || channel.readyState !== 'open') return Promise.reject(new Error('LAN_PEER_OFFLINE'))
    if (channel.bufferedAmount < BUFFERED_AMOUNT_HIGH) return Promise.resolve()
    channel.bufferedAmountLowThreshold = BUFFERED_AMOUNT_LOW
    return new Promise<void>((resolve, reject) => {
      const previousLow = channel.onbufferedamountlow
      const previousClose = channel.onclose
      channel.onbufferedamountlow = () => {
        channel.onbufferedamountlow = previousLow
        channel.onclose = previousClose
        previousLow?.()
        resolve()
      }
      channel.onclose = () => {
        channel.onbufferedamountlow = previousLow
        channel.onclose = previousClose
        previousClose?.()
        reject(new Error('LAN_PEER_OFFLINE'))
      }
    })
  }

  private async receiveData(data: string | ArrayBuffer) {
    if (typeof data !== 'string') {
      await this.receiveChunk(new Uint8Array(data))
      return
    }
    const frame = parseControl(data)
    const current = this.current
    if (frame.type === 'file_accept' && current && current.id === frame.file_id) {
      await this.sendCurrent(current)
      return
    }
    if (frame.type === 'file_verified' && current && current.id === frame.file_id) {
      current.status = 'completed'
      current.progress = 1
      current.transferredBytes = current.size
      this.current = undefined
      this.publishItems()
      this.startNext()
      return
    }
    if ((frame.type === 'file_reject' || frame.type === 'file_failed') && this.current?.id === frame.file_id) {
      this.failCurrent(frame.code)
      return
    }
    if (frame.type === 'file_offer') {
      await this.acceptIncoming(frame)
      return
    }
    if (frame.type === 'file_complete') {
      await this.completeIncoming(frame.file_id, frame.sha256)
      return
    }
    if (frame.type === 'transfer_cancel' && this.incoming?.offer.file_id === frame.file_id) await this.cancelIncoming('TRANSFER_CANCELLED')
  }

  private async acceptIncoming(offer: FileOffer) {
    if (!this.receiveDirectory || !this.receiveDirectory.getFileHandle || this.current || this.incoming) {
      this.sendControl({ type: 'file_reject', file_id: offer.file_id, code: 'DESTINATION_UNAVAILABLE' })
      return
    }
    try {
      const name = await availableName(this.receiveDirectory, sanitizeFilename(offer.name), this.receivedNames)
      const handle = await this.receiveDirectory.getFileHandle(name, { create: true }) as FileHandleLike
      const writable = await handle.createWritable()
      this.receivedNames.add(name)
      this.incoming = { offer, name, writable, hash: sha256.create(), receivedBytes: 0, startedAt: this.dependencies.now() }
      this.setState({
        ...this.state,
        status: 'transferring',
        currentFileId: offer.file_id,
        items: [...this.state.items, transferItem(offer.file_id, name, offer.size, 'receiving')],
      })
      this.sendControl({ type: 'file_accept', file_id: offer.file_id })
    } catch {
      this.sendControl({ type: 'file_reject', file_id: offer.file_id, code: 'DESTINATION_UNAVAILABLE' })
    }
  }

  private async receiveChunk(chunk: Uint8Array) {
    const incoming = this.incoming
    if (!incoming || incoming.receivedBytes + chunk.byteLength > incoming.offer.size) throw new Error('LAN_PROTOCOL_ERROR')
    await incoming.writable.write(chunk)
    incoming.hash.update(chunk)
    incoming.receivedBytes += chunk.byteLength
    this.updateIncoming(incoming)
  }

  private async completeIncoming(fileId: string, expectedHash: string) {
    const incoming = this.incoming
    if (!incoming || incoming.offer.file_id !== fileId) throw new Error('LAN_PROTOCOL_ERROR')
    const actualHash = bytesToHex(incoming.hash.digest())
    if (incoming.receivedBytes !== incoming.offer.size || actualHash !== expectedHash) {
      await this.cancelIncoming(actualHash !== expectedHash ? 'HASH_MISMATCH' : 'SIZE_MISMATCH')
      return
    }
    await incoming.writable.close()
    this.patchItem(fileId, { status: 'completed', progress: 1, transferredBytes: incoming.offer.size })
    this.incoming = undefined
    this.sendControl({ type: 'file_verified', file_id: fileId })
    this.setState({ ...this.state, status: 'connected', currentFileId: undefined })
    this.startNext()
  }

  private async cancelIncoming(code: string) {
    const incoming = this.incoming
    if (!incoming) return
    try { await incoming.writable.abort?.() } catch { /* Best-effort partial cleanup. */ }
    try { await this.receiveDirectory?.removeEntry?.(incoming.name) } catch { /* Best-effort partial cleanup. */ }
    this.patchItem(incoming.offer.file_id, { status: code === 'TRANSFER_CANCELLED' ? 'cancelled' : 'failed', error: code })
    this.sendControl({ type: 'file_failed', file_id: incoming.offer.file_id, code })
    this.incoming = undefined
    this.setState({ ...this.state, status: 'connected', currentFileId: undefined })
    this.startNext()
  }

  private updateIncoming(incoming: ReceiveRecord) {
    const progress = incoming.offer.size === 0 ? 1 : incoming.receivedBytes / incoming.offer.size
    this.patchItem(incoming.offer.file_id, {
      transferredBytes: incoming.receivedBytes,
      progress,
      speedBytesPerSecond: transferSpeed(incoming.receivedBytes, incoming.startedAt, this.dependencies.now()),
    })
  }

  private failCurrent(code: string) {
    if (!this.current) return
    this.transferGeneration += 1
    this.current.status = 'failed'
    this.current.error = code
    this.current = undefined
    this.publishItems()
    this.startNext()
  }

  private sendControl(frame: LanControlFrame) {
    if (!this.channel || this.channel.readyState !== 'open') return false
    this.channel.send(encodeControl(frame))
    return true
  }

  private publishItems() {
    const transferring = Boolean(this.current || this.incoming)
    this.setState({
      ...this.state,
      status: transferring ? 'transferring' : this.state.status,
      currentFileId: this.current?.id ?? this.incoming?.offer.file_id,
      items: this.queue.map(({ file: _file, startedAt: _startedAt, ...item }) => ({ ...item }))
        .concat(this.state.items.filter((item) => item.direction === 'receiving')),
    })
  }

  private patchItem(fileId: string, update: Partial<LanTransferItem>) {
    this.setState({
      ...this.state,
      items: this.state.items.map((item) => item.id === fileId ? { ...item, ...update } : item),
    })
  }

  private fail(error: string) {
    if (this.state.status === 'failed' || this.state.status === 'closed') return
    if (this.current) {
      this.current.status = 'failed'
      this.current.error = error
      this.current = undefined
    }
    this.transferGeneration += 1
    this.clearConnection()
    this.setState({ ...this.state, status: 'failed', currentFileId: undefined, error })
  }

  private clearConnectTimeout() {
    if (this.timeout !== undefined) this.dependencies.clearTimeout(this.timeout)
    this.timeout = undefined
  }

  private clearConnection() {
    this.clearConnectTimeout()
    const channel = this.channel
    const connection = this.connection
    this.channel = undefined
    this.connection = undefined
    if (channel) {
      channel.onopen = null
      channel.onclose = null
      channel.onerror = null
      channel.onmessage = null
      channel.onbufferedamountlow = null
      channel.close()
    }
    connection?.close()
  }

  private setState(state: LanPeerState) {
    this.state = state
    for (const listener of this.listeners) listener(state)
  }
}

function dataString(data: unknown, key: string) {
  if (!data || typeof data !== 'object') return ''
  const value = (data as Record<string, unknown>)[key]
  return typeof value === 'string' ? value : ''
}

function toArrayBuffer(chunk: Uint8Array) {
  return chunk.buffer.slice(chunk.byteOffset, chunk.byteOffset + chunk.byteLength) as ArrayBuffer
}

function transferSpeed(bytes: number, startedAt: number, now: number) {
  const elapsedMs = Math.max(1, now - startedAt)
  return Math.round(bytes * 1_000 / elapsedMs)
}

function transferItem(id: string, name: string, size: number, direction: LanTransferItem['direction']): LanTransferItem {
  return { id, name, size, direction, status: 'transferring', transferredBytes: 0, progress: 0, speedBytesPerSecond: 0 }
}

async function availableName(directory: DirectoryHandle, requested: string, reserved: ReadonlySet<string>) {
  const existing = new Set(reserved)
  if (directory.getFileHandle) {
    let candidate = uniqueFilename(requested, existing)
    for (;;) {
      try {
        await directory.getFileHandle(candidate)
        existing.add(candidate)
        candidate = uniqueFilename(requested, existing)
      } catch {
        return candidate
      }
    }
  }
  return uniqueFilename(requested, existing)
}
