import { describe, expect, it, vi } from 'vitest'
import { downloadFilesInBrowser } from './browserDownloads'

describe('browser batch downloads', () => {
  it('starts each file in order, waiting between downloads', async () => {
    const first = '/files/one'
    const second = '/files/two'
    const events: string[] = []
    const start = vi.fn((url: string) => { events.push(url) })
    const wait = async () => { events.push('wait') }
    await expect(downloadFilesInBrowser([first, second], new AbortController().signal, start, wait))
      .resolves.toBe(2)
    expect(events).toEqual([first, 'wait', second])
  })

  it('stops submitting files when cancelled between downloads', async () => {
    const controller = new AbortController()
    const start = vi.fn()
    await expect(downloadFilesInBrowser(['/files/one', '/files/two'], controller.signal, start, async () => {
      controller.abort()
    })).resolves.toBe(1)
    expect(start).toHaveBeenCalledExactlyOnceWith('/files/one')
  })
})
