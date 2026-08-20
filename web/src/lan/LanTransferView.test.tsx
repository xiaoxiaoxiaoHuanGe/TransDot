import { Children, isValidElement, type ReactElement, type ReactNode } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it, vi } from 'vitest'
import type { LanPeerState } from './peer'
import {
  LanTransferPanel,
  restoreReceiveDirectory,
  type LanTransferPanelProps,
} from './LanTransferView'
import type { DirectoryHandle } from './types'

const waitingState: LanPeerState = { status: 'waiting', items: [] }

describe('LanTransferView interactions', () => {
  it('offers a first-time receive folder selection and recovers revoked permission', async () => {
    await expect(restoreReceiveDirectory({ load: async () => undefined })).resolves.toEqual({ status: 'required' })
    const revoked = fakeDirectory('prompt')
    await expect(restoreReceiveDirectory({ load: async () => revoked })).resolves.toEqual({
      status: 'permission-required', handle: revoked,
    })

    const choose = vi.fn()
    const firstTime = LanTransferPanel(baseProps({ directoryStatus: 'required', onChooseDirectory: choose }))
    expect(renderToStaticMarkup(firstTime)).toContain('选择接收文件夹')
    clickButton(firstTime, '选择接收文件夹')
    expect(choose).toHaveBeenCalledOnce()

    const recovery = LanTransferPanel(baseProps({ directoryStatus: 'permission-required', onChooseDirectory: choose }))
    expect(renderToStaticMarkup(recovery)).toContain('重新授权接收文件夹')
    clickButton(recovery, '重新授权接收文件夹')
    expect(choose).toHaveBeenCalledTimes(2)
  })

  it('shows waiting, eight-second connecting, and connected LAN status', () => {
    expect(renderToStaticMarkup(LanTransferPanel(baseProps()))).toContain('等待 Android 进入快传')
    expect(renderToStaticMarkup(LanTransferPanel(baseProps({
      peerState: { status: 'connecting', items: [] }, connectSecondsRemaining: 8,
    })))).toContain('正在建立局域网连接 · 8 秒')
    expect(renderToStaticMarkup(LanTransferPanel(baseProps({
      peerState: { status: 'connected', items: [] },
    })))).toContain('局域网直连已建立')
  })

  it('selects multiple files and exposes current progress, speed, retry, and cancel', () => {
    const onSelectFiles = vi.fn()
    const onRetry = vi.fn()
    const onCancel = vi.fn()
    const files = [new File(['abc'], 'one.txt'), new File(['def'], 'two.txt')]
    const state: LanPeerState = {
      status: 'transferring', currentFileId: 'one',
      items: [
        { id: 'one', name: 'a-very-long-file-name-that-must-wrap-safely.txt', size: 10, direction: 'sending', status: 'transferring', transferredBytes: 5, progress: .5, speedBytesPerSecond: 1024 * 1024 },
        { id: 'two', name: 'two.txt', size: 3, direction: 'sending', status: 'failed', transferredBytes: 0, progress: 0, speedBytesPerSecond: 0, error: 'DESTINATION_UNAVAILABLE' },
      ],
    }
    const panel = LanTransferPanel(baseProps({ peerState: state, onSelectFiles, onRetry, onCancel }))
    changeFiles(panel, files)
    expect(onSelectFiles).toHaveBeenCalledWith(files)
    const markup = renderToStaticMarkup(panel)
    expect(markup).toContain('50%')
    expect(markup).toContain('1.0 MB/s')
    clickButton(panel, '重试 two.txt')
    clickButton(panel, '取消当前传输')
    expect(onRetry).toHaveBeenCalledWith('two')
    expect(onCancel).toHaveBeenCalledOnce()
    expect(markup).not.toContain('云端')
    expect(markup).not.toContain('回退')
  })

  it('shows queue validation errors next to file selection', () => {
    const props = baseProps({ selectionError: '一次最多选择 20 个文件。' } as Partial<LanTransferPanelProps> & { selectionError: string })
    expect(renderToStaticMarkup(LanTransferPanel(props))).toContain('一次最多选择 20 个文件。')
  })
})

function baseProps(overrides: Partial<LanTransferPanelProps> = {}): LanTransferPanelProps {
  return {
    peerState: waitingState,
    directoryStatus: 'ready',
    directoryName: 'Downloads',
    connectSecondsRemaining: 8,
    onBack: vi.fn(),
    onChooseDirectory: vi.fn(),
    onSelectFiles: vi.fn(),
    onRetry: vi.fn(),
    onCancel: vi.fn(),
    ...overrides,
  }
}

function fakeDirectory(permission: 'granted' | 'prompt' | 'denied'): DirectoryHandle {
  return {
    kind: 'directory', name: 'Downloads',
    queryPermission: vi.fn(async () => permission),
    requestPermission: vi.fn(async () => permission),
  }
}

function clickButton(root: ReactNode, accessibleName: string) {
  const button = elements(root).find((element) => element.type === 'button'
    && (element.props as { 'aria-label'?: string })['aria-label'] === accessibleName)
  expect(button, `button ${accessibleName}`).toBeDefined()
  ;(button!.props as { onClick(): void }).onClick()
}

function changeFiles(root: ReactNode, files: File[]) {
  const input = elements(root).find((element) => element.type === 'input'
    && (element.props as { type?: string }).type === 'file')
  expect(input).toBeDefined()
  ;(input!.props as { onChange(event: { target: { files: File[] } }): void })
    .onChange({ target: { files } })
}

function elements(node: ReactNode): ReactElement[] {
  if (!isValidElement(node)) return []
  const children = Children.toArray((node.props as { children?: ReactNode }).children)
  return [node, ...children.flatMap(elements)]
}
