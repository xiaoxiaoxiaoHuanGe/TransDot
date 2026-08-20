import { describe, expect, it, vi } from 'vitest'
import {
  LAN_CHUNK_BYTES,
  MAX_LAN_FILE_BYTES,
  MAX_LAN_FILES,
  hashBlob,
  parseControl,
  sanitizeFilename,
  streamBlob,
  uniqueFilename,
  validateQueue,
} from './protocol'

describe('LAN file protocol', () => {
  it('accepts exactly 2 GiB and rejects a larger offer', () => {
    const accepted = parseControl(JSON.stringify({
      type: 'file_offer', file_id: crypto.randomUUID(), name: 'large.bin',
      mime: 'application/octet-stream', size: MAX_LAN_FILE_BYTES,
    }))
    expect(accepted.type).toBe('file_offer')
    expect(() => parseControl(JSON.stringify({
      type: 'file_offer', file_id: crypto.randomUUID(), name: 'too-large.bin',
      mime: 'application/octet-stream', size: MAX_LAN_FILE_BYTES + 1,
    }))).toThrowError('FILE_TOO_LARGE')
  })

  it('rejects queues longer than 20 files', () => {
    expect(MAX_LAN_FILES).toBe(20)
    expect(() => validateQueue(Array.from({ length: 21 }, (_, index) => new File(['x'], `${index}.txt`))))
      .toThrowError('TOO_MANY_FILES')
  })

  it('sanitizes traversal while preserving usable names', () => {
    expect(sanitizeFilename('../a\\b?.txt')).toBe('a_b_.txt')
    expect(sanitizeFilename('  报告  .pdf')).toBe('报告  .pdf')
    expect(uniqueFilename('报告.pdf', new Set(['报告.pdf', '报告 (1).pdf']))).toBe('报告 (2).pdf')
  })

  it('streams fixed-size chunks without buffering the whole blob', async () => {
    const blob = new Blob([new Uint8Array(LAN_CHUNK_BYTES * 2 + 3)])
    const chunks: number[] = []
    await streamBlob(blob, async (chunk) => { chunks.push(chunk.byteLength) })
    expect(chunks).toEqual([LAN_CHUNK_BYTES, LAN_CHUNK_BYTES, 3])
  })

  it('computes the shared SHA-256 test vector', async () => {
    await expect(hashBlob(new Blob(['abc']))).resolves.toBe(
      'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
    )
  })

  it('rejects unknown control fields and invalid transitions', () => {
    expect(() => parseControl('{"type":"queue_complete","extra":true}')).toThrowError('LAN_PROTOCOL_ERROR')
    expect(() => parseControl('{"type":"unknown"}')).toThrowError('LAN_PROTOCOL_ERROR')
  })

  it('does not invoke a chunk writer for an empty file', async () => {
    const writer = vi.fn()
    await streamBlob(new Blob([]), writer)
    expect(writer).not.toHaveBeenCalled()
  })
})
