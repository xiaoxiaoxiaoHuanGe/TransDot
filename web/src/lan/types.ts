export type HandlePermission = 'granted' | 'denied' | 'prompt'

export interface DirectoryHandle {
  kind: 'directory'
  name: string
  queryPermission(options?: { mode: 'readwrite' }): Promise<HandlePermission>
  requestPermission(options?: { mode: 'readwrite' }): Promise<HandlePermission>
  getFileHandle?(name: string, options?: { create?: boolean }): Promise<unknown>
  removeEntry?(name: string): Promise<void>
}

export interface HandlePersistence {
  load(): Promise<DirectoryHandle | undefined>
  save(handle: DirectoryHandle): Promise<void>
  clear(): Promise<void>
}

export type FileOffer = {
  type: 'file_offer'
  file_id: string
  name: string
  mime: string
  size: number
}

export type FileResultFrame = {
  type: 'file_accept' | 'file_verified' | 'transfer_cancel' | 'queue_complete'
  file_id?: string
}

export type FileFailureFrame = {
  type: 'file_reject' | 'file_failed'
  file_id: string
  code: string
}

export type FileCompleteFrame = {
  type: 'file_complete'
  file_id: string
  sha256: string
}

export type LanControlFrame = FileOffer | FileResultFrame | FileFailureFrame | FileCompleteFrame
