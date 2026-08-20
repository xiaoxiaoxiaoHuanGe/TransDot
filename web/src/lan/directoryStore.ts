import type { DirectoryHandle, HandlePersistence } from './types'

const databaseName = 'transdot-lan'
const storeName = 'settings'
const directoryKey = 'receive-directory'

export type DirectoryPermissionResult = 'granted' | 'permission-required' | 'denied'

export class DirectoryHandleStore {
  constructor(private readonly persistence: HandlePersistence = new IndexedDBHandlePersistence()) {}
  load() { return this.persistence.load() }
  save(handle: DirectoryHandle) { return this.persistence.save(handle) }
  clear() { return this.persistence.clear() }
}

export async function ensureDirectoryPermission(handle: DirectoryHandle, userGesture: boolean): Promise<DirectoryPermissionResult> {
  const current = await handle.queryPermission({ mode: 'readwrite' })
  if (current === 'granted') return 'granted'
  if (current === 'denied') return 'denied'
  if (!userGesture) return 'permission-required'
  return (await handle.requestPermission({ mode: 'readwrite' })) === 'granted' ? 'granted' : 'denied'
}

class IndexedDBHandlePersistence implements HandlePersistence {
  async load(): Promise<DirectoryHandle | undefined> {
    return this.request<DirectoryHandle | undefined>('readonly', (store) => store.get(directoryKey))
  }
  async save(handle: DirectoryHandle): Promise<void> {
    await this.request('readwrite', (store) => store.put(handle, directoryKey))
  }
  async clear(): Promise<void> {
    await this.request('readwrite', (store) => store.delete(directoryKey))
  }
  private async request<T>(mode: IDBTransactionMode, operation: (store: IDBObjectStore) => IDBRequest): Promise<T> {
    const database = await openDatabase()
    try {
      return await new Promise<T>((resolve, reject) => {
        const request = operation(database.transaction(storeName, mode).objectStore(storeName))
        request.onsuccess = () => resolve(request.result as T)
        request.onerror = () => reject(request.error ?? new Error('DIRECTORY_STORE_FAILED'))
      })
    } finally { database.close() }
  }
}

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(databaseName, 1)
    request.onupgradeneeded = () => request.result.createObjectStore(storeName)
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error ?? new Error('DIRECTORY_STORE_FAILED'))
  })
}
