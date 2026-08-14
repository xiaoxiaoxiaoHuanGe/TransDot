import { describe, expect, it } from 'vitest'
import {
  allocateFilename,
  downloadFilesToDirectory,
  fileFingerprint,
  partitionRepeatedFiles,
  runWithConcurrency,
} from './transferTools'

const file = (name: string, size = 12, type = 'text/plain', lastModified = 123) => ({
  name,
  size,
  type,
  lastModified,
})

describe('paste duplicate detection', () => {
  it('recognizes a file already uploaded in the current page session', () => {
    const uploaded = file('notes.txt')
    const result = partitionRepeatedFiles([uploaded], new Set([fileFingerprint(uploaded)]))

    expect(result.fresh).toEqual([])
    expect(result.repeated).toEqual([uploaded])
  })

  it('deduplicates clipboard representations while keeping new files', () => {
    const repeated = file('photo.png', 20, 'image/png')
    const fresh = file('report.pdf', 30, 'application/pdf')
    const result = partitionRepeatedFiles([repeated, repeated, fresh], new Set([fileFingerprint(repeated)]))

    expect(result.repeated).toEqual([repeated])
    expect(result.fresh).toEqual([fresh])
  })
})

describe('batch download helpers', () => {
  it('adds a numeric suffix when a filename is already allocated', () => {
    const allocated = new Set(['report.pdf', 'report (1).pdf', 'archive'])

    expect(allocateFilename('report.pdf', allocated)).toBe('report (2).pdf')
    expect(allocateFilename('archive', allocated)).toBe('archive (1)')
  })

  it('runs at most three downloads concurrently and retains individual failures', async () => {
    let active = 0
    let peak = 0
    const result = await runWithConcurrency([1, 2, 3, 4, 5], 3, async (value) => {
      active += 1
      peak = Math.max(peak, active)
      await Promise.resolve()
      active -= 1
      if (value === 3) throw new Error('unavailable')
      return value * 2
    })

    expect(peak).toBe(3)
    expect(result.map((item) => item.status)).toEqual(['fulfilled', 'fulfilled', 'rejected', 'fulfilled', 'fulfilled'])
  })

  it('streams available files into a directory and continues after a failed response', async () => {
    const written = new Map<string, Uint8Array[]>()
    const directory = {
      async *keys() { yield 'report.pdf' },
      async getFileHandle(name: string) {
        return {
          async createWritable() {
            const chunks: Uint8Array[] = []
            written.set(name, chunks)
            return {
              async write(chunk: Uint8Array) { chunks.push(chunk) },
              async close() {},
              async abort() {},
            }
          },
        }
      },
    }
    const fetcher = async (url: string) => url.endsWith('/missing')
      ? new Response('', { status: 404 })
      : new Response('saved')

    const results = await downloadFilesToDirectory([
      { filename: 'report.pdf', url: '/ok' },
      { filename: 'missing.txt', url: '/missing' },
    ], directory, fetcher)

    expect(results.map((item) => item.status)).toEqual(['fulfilled', 'rejected'])
    expect(results[0].filename).toBe('report (1).pdf')
    expect(new TextDecoder().decode(written.get('report (1).pdf')?.[0])).toBe('saved')
  })
})
