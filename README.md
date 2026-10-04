<h1 align="center">TransDot</h1>

<p align="center">在自己的服务器上，连接手机与浏览器的文字、图片和文件。</p>

<p align="center">
  <a href="https://github.com/xiaoxiaoxiaoHuanGe/TransDot/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/xiaoxiaoxiaoHuanGe/TransDot?style=flat-square"></a>
  <img alt="Android 6.0+" src="https://img.shields.io/badge/Android-6.0%2B-526273?style=flat-square">
  <img alt="Docker Compose" src="https://img.shields.io/badge/Docker-Compose-526273?style=flat-square">
</p>

<p align="center">
  <a href="https://github.com/xiaoxiaoxiaoHuanGe/TransDot/releases/latest">下载 Android APP</a> ·
  <a href="#快速开始">快速开始</a> ·
  <a href="docs/USAGE.md">部署与使用</a> ·
  <a href="https://github.com/xiaoxiaoxiaoHuanGe/TransDot/issues">反馈问题</a>
</p>

---

TransDot 是自托管的 **Android ↔ Web 文件传输工具**，由 Go 服务端、React 网页和原生 Android APP 组成。
消息、SQLite 数据库和上传文件保存在你的服务器；设备通过二维码配对，无需注册第三方账号。

## 可以做什么

| 功能 | 使用方式 |
| --- | --- |
| 💬 共享时间线 | 收发文字、图片和文件，全文搜索并定位上下文 |
| 📂 批量传输 | Web 支持选择、拖放和粘贴文件；Android 支持多文件及默认保存目录 |
| 🔗 设备配对 | 首次扫码初始化，授权多个浏览器，查看及撤销设备权限 |
| 📱 重绑手机 | 已授权 Web 生成重绑二维码，APP 重装后恢复连接并轮换旧凭据 |
| ⚡ 局域网快传 | Android 与最新版 Chrome/Edge 同网时，通过 WebRTC 直接传文件 |
| 💾 备份恢复 | Linux 宿主机脚本创建一致性备份，恢复前验证并保留安全备份 |

云端时间线使用服务器存储；局域网快传只经服务器交换信令，文件内容直接在设备之间传输。

## 快速开始

### 1. 部署服务

Linux 服务器需要 Docker 和 Docker Compose。克隆后编辑 `.env`：

```sh
git clone https://github.com/xiaoxiaoxiaoHuanGe/TransDot.git transdot
cd transdot
cp .env.example .env
```

为反向代理设置以下内容，将域名替换为自己的 HTTPS 入口：

```env
HOST_BIND=127.0.0.1
HOST_PORT=5757
PUBLIC_URL=https://transdot.example.com
OWNER_SETUP_TOKEN=
```

`PUBLIC_URL` 只填 HTTPS Origin，不带业务路径、查询参数或片段。
`OWNER_SETUP_TOKEN` 可留空，首次扫码不需要它；如设置手动恢复密钥，需至少 32 个随机字符。
需要 Go 下载代理时，可设置 `GOPROXY=https://goproxy.cn,direct`。

```sh
docker compose -p transdot up -d --build
curl --fail http://127.0.0.1:5757/healthz
```

健康检查应返回 `{"status":"ok"}`。将 1Panel 或其他反向代理指向 `http://127.0.0.1:5757`，
配置可信 HTTPS 证书和 WebSocket；公开地址需与 `PUBLIC_URL` 一致。
详细配置及非标准端口示例见 [部署与使用](docs/USAGE.md)。

### 2. 安装并配对

从 [Releases](https://github.com/xiaoxiaoxiaoHuanGe/TransDot/releases/latest) 下载 APK，系统要求 **Android 6.0+**。
`arm64-v8a` 包适合现代 ARM64 手机；不确定架构时选择 `universal` 包。正式 APK 要求 HTTPS。

1. 浏览器打开服务地址，在未初始化页面查看一次性二维码。
2. APP 点击“扫码连接服务器”，核对地址和实例指纹后确认。
3. APP 保存 Android Master 凭据，Web 进入时间线。
4. 添加其他浏览器时，在 APP 选择“添加浏览器”，扫码或输入浏览器显示的 6 位备用码。

默认最多同时授权 10 个浏览器；二维码默认有效期 120 秒，仅可使用一次。
APP 重装后，可从已授权 Web 点击“重新绑定手机”，详见 [重绑步骤](docs/USAGE.md#app-重装后重新绑定)。

## 存储与使用边界

> [!IMPORTANT]
> 普通更新使用 `sh docker/update.sh`。`docker compose down -v` 和 `sh docker/reset.sh RESET` 会删除数据卷，不能作为升级步骤。

- 数据卷为 `transfer-assistant-data`，对应容器 `/app/data`；更新保留数据与设备授权。
- 云端默认单文件 300 MiB、单批 500 MiB / 20 个文件，文件池 1 GiB；图片和附件默认保留 30 天。
- 局域网快传单文件最多 2 GiB、单批 20 个文件，完成后校验 SHA-256；不支持续传或云端回退。
- 快传使用 Host ICE，不使用 STUN/TURN；访客网络、AP 隔离、VPN 和防火墙可能阻止直连。
- Android 在后台关闭时间线 WebSocket，回到前台重新同步；不承诺 APP 关闭时实时接收消息。
- `.env`、设备凭据和备份含私有信息，不应上传公开仓库。备份包含消息、文件及部分 Token 哈希，需妥善保管。

<details>
<summary>本地 Docker 与开发检查</summary>

本地可使用 Docker Desktop / Engine。复制 `.env.example` 后，未设置 `PUBLIC_URL` 时用请求地址作为开发入口。
浏览器访问 `http://localhost:5757`；手机填写电脑的局域网 IP，HTTP 仅限 Debug APK。

```powershell
docker compose -p transdot up -d --build
Invoke-RestMethod http://localhost:5757/healthz
```

从仓库根目录分别进入以下目录执行：

| 组件 | 检查 |
| --- | --- |
| Go 服务端 | `cd server`，`go test ./...` |
| Web | `cd web`，`npm ci`，`npm test`，`npm run build` |
| Android | `cd android`，`.\gradlew.bat --no-daemon testDebugUnitTest assembleDebug` |

Android 构建与签名见 [开发说明](android/README.md)，Debug APK 输出到 `android/app/build/outputs/apk/debug/app-debug.apk`。

</details>

## 文档与许可

完整的云部署、本地部署、浏览器授权、局域网快传、参数、更新日志查看、备份与恢复命令见 [部署与使用](docs/USAGE.md)。
维护脚本说明见 [docker/README.md](docker/README.md)。

项目由 [xiaoxiaoxiaoHuanGe](https://github.com/xiaoxiaoxiaoHuanGe) 维护，第三方依赖遵循各自许可证。
当前仓库未提供源码许可证，公开可读不等于已授予任意使用、修改或分发的许可。
