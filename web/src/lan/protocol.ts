import { sha256 } from '@noble/hashes/sha2.js'
import { bytesToHex } from '@noble/hashes/utils.js'
import type { LanControlFrame } from './types'

export const MAX_LAN_FILES = 20
export const MAX_LAN_FILE_BYTES = 2 * 1024 * 1024 * 1024
export const LAN_CHUNK_BYTES = 64 * 1024
export const HIGH_WATER_BYTES = 4 * 1024 * 1024
export const LOW_WATER_BYTES = 1024 * 1024

const frameKeys: Record<LanControlFrame['type'], readonly string[]> = {
  file_offer: ['type', 'file_id', 'name', 'mime', 'size'],
  file_accept: ['type', 'file_id'],
  file_reject: ['type', 'file_id', 'code'],
  file_complete: ['type', 'file_id', 'sha256'],
  file_verified: ['type', 'file_id'],
  file_failed: ['type', 'file_id', 'code'],
  queue_complete: ['type'],
  transfer_cancel: ['type', 'file_id'],
}

export function parseControl(encoded: string): LanControlFrame {
  let value: unknown
  try { value = JSON.parse(encoded) } catch { throw new Error('LAN_PROTOCOL_ERROR') }
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('LAN_PROTOCOL_ERROR')
  const candidate = value as Record<string, unknown>
  if (typeof candidate.type !== 'string' || !(candidate.type in frameKeys)) throw new Error('LAN_PROTOCOL_ERROR')
  const type = candidate.type as LanControlFrame['type']
  const allowed = frameKeys[type]
  if (Object.keys(candidate).some((key) => !allowed.includes(key))) throw new Error('LAN_PROTOCOL_ERROR')
  if (type === 'queue_complete') return { type }
  if (typeof candidate.file_id !== 'string' || candidate.file_id.length === 0) throw new Error('LAN_PROTOCOL_ERROR')
  if (type === 'file_offer') {
    if (typeof candidate.name !== 'string' || typeof candidate.mime !== 'string'
      || typeof candidate.size !== 'number' || !Number.isSafeInteger(candidate.size) || candidate.size < 0) {
      throw new Error('LAN_PROTOCOL_ERROR')
    }
    if (candidate.size > MAX_LAN_FILE_BYTES) throw new Error('FILE_TOO_LARGE')
    return { type, file_id: candidate.file_id, name: sanitizeFilename(candidate.name), mime: candidate.mime, size: candidate.size }
  }
  if (type === 'file_complete') {
    if (typeof candidate.sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(candidate.sha256)) throw new Error('LAN_PROTOCOL_ERROR')
    return { type, file_id: candidate.file_id, sha256: candidate.sha256 }
  }
  if (type === 'file_reject' || type === 'file_failed') {
    if (typeof candidate.code !== 'string' || candidate.code.length === 0) throw new Error('LAN_PROTOCOL_ERROR')
    return { type, file_id: candidate.file_id, code: candidate.code }
  }
  return { type, file_id: candidate.file_id }
}

export function encodeControl(frame: LanControlFrame): string {
  return JSON.stringify(frame)
}

export function validateQueue(files: readonly Pick<File, 'size'>[]) {
  if (files.length > MAX_LAN_FILES) throw new Error('TOO_MANY_FILES')
  for (const file of files) if (file.size > MAX_LAN_FILE_BYTES) throw new Error('FILE_TOO_LARGE')
}

export function sanitizeFilename(input: string): string {
  const withoutTraversal = input.replace(/^(\.\.[/\\])+/, '')
  const cleaned = withoutTraversal.replace(/[/\\:*?"<>|\u0000-\u001f]/g, '_').trim().replace(/[. ]+$/g, '')
  return (cleaned || 'unnamed').slice(0, 240)
}

export function uniqueFilename(filename: string, existing: ReadonlySet<string>): string {
  if (!existing.has(filename)) return filename
  const dot = filename.lastIndexOf('.')
  const hasExtension = dot > 0
  const stem = hasExtension ? filename.slice(0, dot) : filename
  const extension = hasExtension ? filename.slice(dot) : ''
  for (let index = 1; ; index++) {
    const candidate = `${stem} (${index})${extension}`
    if (!existing.has(candidate)) return candidate
  }
}

export async function streamBlob(blob: Blob, write: (chunk: Uint8Array) => Promise<void>): Promise<void> {
  for (let offset = 0; offset < blob.size; offset += LAN_CHUNK_BYTES) {
    const buffer = await blob.slice(offset, Math.min(blob.size, offset + LAN_CHUNK_BYTES)).arrayBuffer()
    await write(new Uint8Array(buffer))
  }
}

export async function hashBlob(blob: Blob): Promise<string> {
  const hash = sha256.create()
  await streamBlob(blob, async (chunk) => { hash.update(chunk) })
  return bytesToHex(hash.digest())
}
