import { describe, expect, it, vi } from 'vitest'
import { DirectoryHandleStore, ensureDirectoryPermission } from './directoryStore'
import type { DirectoryHandle, HandlePersistence } from './types'

function fakeHandle(permission: 'granted' | 'prompt' | 'denied'): DirectoryHandle {
  return {
    kind: 'directory', name: 'Downloads',
    queryPermission: vi.fn(async () => permission),
    requestPermission: vi.fn(async () => permission === 'prompt' ? 'granted' : permission),
  } as unknown as DirectoryHandle
}

describe('LAN receive directory store', () => {
  it('persists and reloads the selected handle', async () => {
    let saved: DirectoryHandle | undefined
    const persistence: HandlePersistence = {
      load: async () => saved,
      save: async (handle) => { saved = handle },
      clear: async () => { saved = undefined },
    }
    const store = new DirectoryHandleStore(persistence)
    const handle = fakeHandle('granted')
    await store.save(handle)
    await expect(store.load()).resolves.toBe(handle)
  })

  it('reports prompt without requesting permission outside a user gesture', async () => {
    const handle = fakeHandle('prompt')
    await expect(ensureDirectoryPermission(handle, false)).resolves.toBe('permission-required')
    expect(handle.requestPermission).not.toHaveBeenCalled()
    await expect(ensureDirectoryPermission(handle, true)).resolves.toBe('granted')
  })

  it('reports denied permission as unavailable', async () => {
    await expect(ensureDirectoryPermission(fakeHandle('denied'), true)).resolves.toBe('denied')
  })
})
