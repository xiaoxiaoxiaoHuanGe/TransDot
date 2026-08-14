export type FileIdentity = Pick<File, 'name' | 'size' | 'type' | 'lastModified'>

export function fileFingerprint(file: FileIdentity) {
  return `${file.name}\u0000${file.size}\u0000${file.type}\u0000${file.lastModified}`
}

export function partitionRepeatedFiles<T extends FileIdentity>(files: T[], known: ReadonlySet<string>) {
  const seen = new Set<string>()
  const fresh: T[] = []
  const repeated: T[] = []
  for (const file of files) {
    const fingerprint = fileFingerprint(file)
    if (seen.has(fingerprint)) continue
    seen.add(fingerprint)
    if (known.has(fingerprint)) repeated.push(file)
    else fresh.push(file)
  }
  return { fresh, repeated }
}

export function allocateFilename(requestedName: string, allocated: Set<string>) {
  const safeName = requestedName.replace(/[\\/:*?"<>|]/g, '_').trim() || 'download'
  if (!allocated.has(safeName)) {
    allocated.add(safeName)
    return safeName
  }
  const lastDot = safeName.lastIndexOf('.')
  const hasExtension = lastDot > 0
  const stem = hasExtension ? safeName.slice(0, lastDot) : safeName
  const extension = hasExtension ? safeName.slice(lastDot) : ''
  let suffix = 1
  while (allocated.has(`${stem} (${suffix})${extension}`)) suffix += 1
  const result = `${stem} (${suffix})${extension}`
  allocated.add(result)
  return result
}

export async function runWithConcurrency<T, R>(
  values: readonly T[],
  concurrency: number,
  operation: (value: T, index: number) => Promise<R>,
): Promise<PromiseSettledResult<R>[]> {
  const results = new Array<PromiseSettledResult<R>>(values.length)
  let nextIndex = 0
  const workers = Array.from({ length: Math.min(Math.max(1, concurrency), values.length) }, async () => {
    while (nextIndex < values.length) {
      const index = nextIndex
      nextIndex += 1
      try {
        results[index] = { status: 'fulfilled', value: await operation(values[index], index) }
      } catch (reason) {
        results[index] = { status: 'rejected', reason }
      }
    }
  })
  await Promise.all(workers)
  return results
}

export type DownloadSource = { filename: string, url: string }

export type WritableDownloadFile = {
  write(chunk: Uint8Array): Promise<void>
  close(): Promise<void>
  abort?(): Promise<void>
}

export type DownloadDirectory = {
  keys(): AsyncIterableIterator<string>
  getFileHandle(name: string, options: { create: true }): Promise<{
    createWritable(): Promise<WritableDownloadFile>
  }>
}

export type DownloadResult = {
  source: DownloadSource
  filename: string
  status: 'fulfilled' | 'rejected'
  reason?: unknown
}

export async function downloadFilesToDirectory(
  sources: readonly DownloadSource[],
  directory: DownloadDirectory,
  fetcher: (url: string, init: RequestInit) => Promise<Response> = fetch,
  signal?: AbortSignal,
): Promise<DownloadResult[]> {
  const allocated = new Set<string>()
  for await (const filename of directory.keys()) allocated.add(filename)
  const assigned = sources.map((source) => ({ source, filename: allocateFilename(source.filename, allocated) }))
  const settled = await runWithConcurrency(assigned, 3, async ({ source, filename }) => {
    const response = await fetcher(source.url, { credentials: 'same-origin', cache: 'no-store', signal })
    if (!response.ok) throw new Error(`下载失败（HTTP ${response.status}）`)
    const handle = await directory.getFileHandle(filename, { create: true })
    const writable = await handle.createWritable()
    try {
      if (response.body) {
        const reader = response.body.getReader()
        while (true) {
          const { done, value } = await reader.read()
          if (done) break
          await writable.write(value)
        }
      } else {
        await writable.write(new Uint8Array(await response.arrayBuffer()))
      }
      await writable.close()
      return filename
    } catch (error) {
      await writable.abort?.().catch(() => undefined)
      throw error
    }
  })
  return settled.map((result, index) => result.status === 'fulfilled'
    ? { source: sources[index], filename: result.value, status: 'fulfilled' }
    : { source: sources[index], filename: assigned[index].filename, status: 'rejected', reason: result.reason })
}
