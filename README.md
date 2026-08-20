# TransDot 传输助手 v1.1.0

TransDot 是一个自托管的 Android 与 Web 文件传输工具。服务端、SQLite 数据库、消息和上传文件都保存在你自己的 Docker 数据卷中，不需要注册账号或接入第三方云盘。

## 核心功能

- Android 与 Web 在同一条时间线中收发文字、图片和文件。
- Web 支持选择、拖放和粘贴文件，支持搜索、批量选择与批量保存。
- Android 支持多个服务器档案、默认保存目录、传输通知和自动接收。
- 首次部署通过 Web 二维码绑定 Android Master，无需手动输入密钥。
- APP 重装后可在 Web 点击“重新绑定手机”，新 APP 扫码后自动恢复连接；旧手机凭据立即失效。
- Android 与最新版 Chrome/Edge 位于同一局域网时，可使用 WebRTC 局域网快传，文件内容不经过云服务器。
- 普通代码升级保留服务器身份、设备绑定、消息和文件，不需要重新初始化。

## 运行条件

| 场景 | 要求 |
| --- | --- |
| 云服务器 | Linux、Docker、Docker Compose；推荐使用 1Panel 管理 HTTPS 反向代理。 |
| 本地调试 | Windows/Linux/macOS、Docker Desktop 或 Docker Engine。 |
| Android | Android 6.0 及以上。正式 APK 只连接 HTTPS 服务。 |
| Web | 最新版 Chrome 或 Edge；局域网快传的目录授权依赖 File System Access API。 |

正式 APK 从 [GitHub Releases](https://github.com/xiaoxiaoxiaoHuanGe/TransDot/releases) 下载。

## 1Panel 云服务器部署

推荐链路：

```text
手机/浏览器 -> HTTPS 域名或可信 IP 证书 -> 1Panel 反向代理 -> 127.0.0.1:5757 -> TransDot
```

### 1. 获取项目

```bash
cd /opt
git clone https://github.com/xiaoxiaoxiaoHuanGe/TransDot.git transdot
cd /opt/transdot
cp .env.example .env
nano .env
```

### 2. 配置 `.env`

公网部署至少设置：

```env
HOST_BIND=127.0.0.1
HOST_PORT=5757
PUBLIC_URL=https://transdot.example.com
GOPROXY=https://goproxy.cn,direct
OWNER_SETUP_TOKEN=
```

- `PUBLIC_URL` 必须是手机实际访问的 HTTPS Origin，不能包含路径、查询参数或业务路由。
- 使用可信 IP 证书时可以填写 `https://服务器公网IP`。
- `OWNER_SETUP_TOKEN` 是可选手动恢复密钥；设置时至少 32 位。二维码初始化不需要它。
- `.env` 包含私密配置，禁止提交到 Git。

### 3. 启动服务

```bash
docker compose -p transdot up -d --build
docker compose -p transdot ps
curl http://127.0.0.1:5757/healthz
```

正常响应：

```json
{"status":"ok"}
```

若构建卡在 Go 依赖下载，确认 `.env` 中存在：

```env
GOPROXY=https://goproxy.cn,direct
```

### 4. 配置 1Panel HTTPS

1. 进入 `网站 -> 创建网站 -> 反向代理`。
2. 代理目标填写 `http://127.0.0.1:5757`。
3. 开启 WebSocket。
4. 配置域名证书或可信 IP 证书，并开启强制 HTTPS。
5. 外部 HTTPS 地址必须与 `.env` 的 `PUBLIC_URL` 完全一致。

非标准 HTTPS 端口需要保留 Host 端口：

```nginx
proxy_set_header Host $http_host;
proxy_set_header Upgrade $http_upgrade;
proxy_set_header Connection "upgrade";
```

## 本地 Docker 部署

```powershell
git clone https://github.com/xiaoxiaoxiaoHuanGe/TransDot.git
cd TransDot
Copy-Item .env.example .env
docker compose -p transdot up -d --build
Invoke-RestMethod http://localhost:5757/healthz
```

浏览器访问 `http://localhost:5757`。正式 APK 不接受 HTTP；本地 HTTP 联调请使用 Debug APK，并在手机中填写电脑的局域网 IP，例如 `http://192.168.1.10:5757`，不要填写 `localhost`。

## 首次绑定

1. 浏览器打开服务器的 HTTPS 地址。
2. 未初始化服务器会显示一次性绑定二维码。
3. Android APP 点击“扫码连接服务器”，扫描二维码。
4. 核对服务器地址和实例指纹后确认。
5. APP 保存 Android Master 凭据，Web 自动进入时间线。

二维码有效期默认 120 秒、只能使用一次，不包含长期 Master Token。服务器初始化后不能再次执行首次绑定。

## APP 重装后重新绑定

只要已授权 Web 浏览器仍可访问服务器，就不需要 Reset：

1. 在 Web 时间线点击“重新绑定手机”。
2. 新安装的 APP 点击“扫码连接服务器”。
3. 扫描 Web 二维码并核对地址和实例指纹。
4. 确认后，新 APP 自动保存新凭据并进入时间线。
5. 旧 Android Master 凭据立即撤销，Web 端自动返回时间线。

点击“刷新二维码”会立即使旧二维码失效。二维码过期或已使用时，在 Web 重新生成即可。

## 浏览器配对

1. 未配对浏览器打开服务器地址，页面显示二维码和 6 位备用码。
2. Android APP 点击“配对 Windows”。
3. 扫描二维码或输入备用码。
4. 替换已有浏览器时，需要在 Android 端明确确认。

## 局域网快传

局域网快传使用服务器的 HTTPS/WSS 连接交换信令，文件名、大小、哈希和文件内容通过 WebRTC DataChannel 在 Android 与浏览器之间直接传输，不写入服务器数据库或文件卷。

使用步骤：

1. Android 与电脑连接同一个非隔离局域网，并登录同一个 TransDot 实例。
2. Web 使用最新版 Chrome/Edge，打开“局域网快传”。
3. Web 首次选择接收文件夹并授权，之后自动接收。
4. Android 首次选择默认接收文件夹，之后自动接收。
5. 任一端可多选文件，队列按顺序逐个传输。

限制：

- 每批最多 20 个文件，单文件最大 2 GiB。
- 使用 64 KiB 分块并在完成前校验 SHA-256。
- 不支持断点续传；取消、断线或校验失败会删除未完成文件。
- 8 秒内无法建立直连会明确失败，不会退回云端上传。
- 只使用 Host ICE，不依赖 STUN/TURN。访客 Wi-Fi、AP 隔离、VPN 或防火墙可能阻止直连。

## 云端时间线限制

| 配置 | 默认值 |
| --- | --- |
| `MAX_FILE_BYTES` | 300 MiB，单文件上限 |
| `MAX_BATCH_BYTES` | 500 MiB，单批总大小 |
| `MAX_BATCH_ITEMS` | 20，单批文件数 |
| `FILE_POOL_MAX_BYTES` | 1 GiB，文件池容量 |
| `FILE_TTL_HOURS` | 24，原文件保留时间 |
| `FILE_MESSAGE_TTL_DAYS` | 30，缩略图与文件消息保留时间 |
| `PAIRING_TTL_SECONDS` | 120，二维码/配对码有效期 |
| `UPLOAD_SESSION_TTL_MINUTES` | 30，未完成上传会话有效期 |

服务每 5 分钟清理过期上传、临时文件和过期内容。局域网快传不占用云端文件池。

## 更新、日志与备份

普通更新：

```bash
cd /opt/transdot
sh docker/update.sh
```

自定义 `HOST_PORT` 时，需要同步指定健康检查地址。例如端口 `3366`：

```bash
TRANSDOT_HEALTH_URL=http://127.0.0.1:3366/healthz sh docker/update.sh
```

查看状态和日志：

```bash
docker compose -p transdot ps
docker compose -p transdot logs --tail=100 transfer-assistant
```

正常更新不要执行 Reset，也不要运行 `docker compose down -v`。数据保存在 Docker 卷 `transfer-assistant-data`，升级和容器重建不会删除该卷。

只有明确要删除全部消息、文件、设备凭据和服务器身份时才执行：

```bash
cd /opt/transdot
sh docker/reset.sh RESET
```

备份或迁移时应备份完整 Docker 卷 `transfer-assistant-data`，仅备份 Git 仓库不能恢复运行数据。

## 开发与验证

Go：

```bash
cd server
go test ./...
```

Web：

```bash
cd web
npm ci
npm test
npm run build
```

Android Debug APK：

```powershell
cd android
.\gradlew.bat --no-daemon testDebugUnitTest assembleDebug
```

输出：`android/app/build/outputs/apk/debug/app-debug.apk`。

## 技术组成

- 后端：Go、SQLite、FTS5、WebSocket
- Web：React、TypeScript、Vite
- Android：Kotlin、Jetpack Compose、Material 3、WebRTC
- 部署：Docker 多阶段构建、Docker Compose、1Panel HTTPS 反向代理
- 数据：容器内 `/app/data`，持久化卷 `transfer-assistant-data`
