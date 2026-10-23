# TransDot Desktop v2.0.3 — Windows 轻量版

经过用户 Windows 实机验收的 Tauri 2 / WebView2 桌面客户端。本次发布仅包含 Windows 版，不修改 Android APP 或 Go 服务端。

## 功能

- 保留现有 Android 扫码配对和服务器通信协议。
- 手机发送新文字、图片后，Windows 自动写入系统剪贴板，可在目标软件 Ctrl+V 粘贴。
- 支持关闭主窗口后留在系统托盘持续接收，启用/暂停状态清晰可见。
- 新消息 WebSocket 实时监听，并以已授权的 REST 消息校验补偿漏收；初次加载不会复制历史消息。
- 托盘菜单提供剪贴板自测、消息链路自测和不包含消息正文的接收诊断。
- 简体中文 / English 安装语言选择和 TransDot 蓝白专属图标。
- 基于 Windows 系统 WebView2 运行，安装包不附带完整 Chromium。

## 下载与安装

请从本 Release 下方 Assets 下载 TransDot_2.0.3_x64-setup.exe，适用于 Windows 10/11 x64。若已安装旧版本，请从托盘菜单完全退出，再运行本安装程序。打开后填写服务器 HTTPS 地址，按现有 Android APP 流程扫码配对。

Windows 缺少 WebView2 Runtime 时，安装程序可能需要联网获取运行环境。安装程序暂未进行代码签名，Windows SmartScreen 可能会显示未知发布者提醒；请仅从此仓库的官方 Release 获取安装程序。

安装包附带同名 .sha256 校验文件。在 PowerShell 中使用 Get-FileHash -Algorithm SHA256 核对下载文件完整性。

保留原 Electron 桌面版源码作为可用回退，不覆盖或删除旧程序。
