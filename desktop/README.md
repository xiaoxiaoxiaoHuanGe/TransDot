# TransDot Windows 桌面版

复用现有 Android APP、Go 服务端和 React 页面；不修改数据库或手机端传输协议。

Windows 端在后台保持 WebSocket，接收到新的文字时写入系统文本剪贴板，收到新图片时从授权的下载地址获取原图并写入图片剪贴板（而不是复制 URL）。可直接在支持相应类型的应用按 Ctrl+V 粘贴。

## 安装与运行

Windows 10/11、Node.js 22，运行：

    cd desktop
    npm install
    npm test
    npm start

Windows 安装版和便携版：

    npm run dist

生成在 desktop/dist。也可以从 GitHub Actions 的 Windows Desktop workflow 获取构建工件中的 .exe。**尚未配置代码签名**。

## 首次使用

1. 启动桌面程序，填写服务器 HTTPS 地址（如 https://transdot.example.com:9004）。
2. 按现有 Web 流程，用 Android APP 扫描二维码，为当前电脑配对。
3. 手机 APP 发送文字或图片后，到其他软件中直接 Ctrl+V。
4. 关闭窗口最小化到系统托盘；双击托盘恢复；右键托盘可暂停自动复制、开机启动或退出。

## 行为和安全性

- 仅处理实时 message.created 事件，**不自动复制打开客户端/断线重连时的历史消息**；掉线期间遗漏的事件需手动查看时间线。
- 不处理当前电脑自己发送的消息。其他授权设备发送的文字/图片，也会触发自动复制。
- 不处理普通文件；图片不会自动另存到下载文件夹。
- 支持普通文本和图片粘贴。图片原文件下载上限 12 MiB，最多 3200 万像素，图片太大时可能跳过。
- 仅 HTTPS（允许 localhost HTTP 本地调试），并限制受信任的服务器主页面、阻止新窗口和跨站跳转。
- Electron 启用沙箱、上下文隔离，网页无法直接访问 Node.js；只有受控桥接接口可写入剪贴板。
- 由于自动剪贴板会覆盖当前剪贴板内容，共享电脑使用时建议通过托盘暂时关闭该功能。
