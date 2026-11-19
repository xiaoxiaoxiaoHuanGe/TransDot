function startBrowserDownload(url: string) {
  const link = document.createElement('a')
  link.href = url
  link.download = ''
  link.click()
}

export async function downloadFilesInBrowser(
  urls: string[],
  signal: AbortSignal,
  start = startBrowserDownload,
  wait = () => new Promise<void>((resolve) => window.setTimeout(resolve, 180)),
) {
  let started = 0
  for (const url of urls) {
    if (started > 0) await wait()
    if (signal.aborted) break
    start(url)
    started++
  }
  return started
}
