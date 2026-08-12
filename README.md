# 私人文件传输助手

这是《私人文件传输助手 V1 — 技术设计冻结规范》的完整 V1 实现：一个单用户、自托管的 Android ↔ Windows 文字、图片与文件传输助手。

- Go HTTP 服务
- SQLite migration 与 FTS5
- `GET /healthz`
- React + TypeScript + Vite Windows Web
- Go 同源静态文件托管与 SPA fallback
- 单容器 Docker 多阶段构建
- `/app/data` 统一持久化目录
- Android Kotlin + Jetpack Compose / Material 3 应用
- 一次性 Android Master Claim
- Master Token 的服务端 SHA-256 存储与 Android Keystore 加密存储
- Windows 二维码/6 位码配对与 HttpOnly Cookie 鉴权
- 单一有效 Windows 及经 Android 确认的原子替换
- SQLite 文字时间线与稳定游标分页（单页最多 50 条）
- Android Bearer Token / Web HttpOnly Cookie 双端消息鉴权
- 认证 WebSocket 实时创建、删除与设备替换事件
- SQLite FTS5 全文搜索与目标消息前后各 20 条上下文定位
- Web 与 Android 文字发送、删除、搜索、定位及断线 REST 重同步 UI
- 上传批次、空间预留、流式文件落盘和原子重命名
- 单文件 300 MB、单批 500 MB/20 项、1 GB 临时文件池
- 认证文件下载与下载中清理保护
- 原文件 24 小时、缩略图及文件消息 30 天生命周期
- Android Photo Picker、系统文件选择、上传进度、失败重试和系统下载保存
- Web 文件选择、拖放、剪贴板图片上传和上传进度
- 720px JPEG 缩略图、相邻同批图片宫格和全屏图片 Viewer
- Android System/Light/Dark 主题及响应式 Web 深浅色界面

## 在另一台电脑运行

新电脑需要安装 Git 与 Docker Desktop（包含 Docker Compose），不需要安装 Go、Node.js 或 Gradle。克隆后先从示例创建本地环境文件：

```powershell
git clone https://github.com/xiaoxiaoxiaoHuanGe/TransDot.git TransDot
cd TransDot
Copy-Item .env.example .env
notepad .env
```

将 `.env` 中的 `OWNER_SETUP_TOKEN` 替换为至少 32 个随机字符，然后构建并启动：

```powershell
docker compose up --build -d
docker compose ps
Invoke-RestMethod http://localhost:5757/healthz
```

浏览器访问 <http://localhost:5757>。同一局域网内的 Android 手机应使用新电脑的局域网 IP，例如 `http://192.168.1.10:5757`；同时确认 Windows 防火墙允许 TCP 5757 入站连接。

仓库不会提交 `.env`、APK、SQLite 数据库或 Docker 命名卷。直接克隆会创建一个全新的服务实例；若要把现有 Master、设备关系和消息一起迁移，还必须单独备份并恢复 Docker 卷 `transfer-assistant-data`，不能只复制 Git 仓库。

## 启动

无需在 Windows 安装 Go：

```powershell
docker compose up --build -d
```

构建默认从 AWS ECR Public 的 Docker Official Images 分发端点获取 Node、Go 与 Alpine 官方镜像。如果部署环境可直接访问 Docker Hub，可在 `.env` 中设置 `OFFICIAL_IMAGE_REGISTRY=docker.io/library`。

打开：

- Web：<http://localhost:5757>
- Health：<http://localhost:5757/healthz>
- Setup 状态：<http://localhost:5757/api/v1/setup/status>

停止服务：

```powershell
docker compose down
```

命名卷 `transfer-data` 挂载到容器内 `/app/data`。执行 `docker compose down` 不会删除数据；只有显式添加 `--volumes` 才会删除该卷。

首次启动 Android 调试包后，填写电脑局域网地址（例如 `http://192.168.1.10:5757`）及本地 `.env` 中的 `OWNER_SETUP_TOKEN`。Claim 只允许成功一次；卸载应用丢失 Master Token 后，V1 不提供账号恢复。

初始化完成后，在 Windows 打开 Web 页面即可看到两分钟有效的二维码和 6 位备用码。Android 点击“配对 Windows”扫码或手输确认；若已有 Windows，必须在手机上明确确认替换。Browser Token 只写入 `HttpOnly + Secure + SameSite=Strict` Cookie。

配对完成后 Web 与 Android 均进入统一时间线。WebSocket 只负责实时通知；首次进入、重新连接和 App 回到前台时均以 `GET /api/v1/messages` 重新同步 SQLite 状态。

文件上传采用先创建 Batch/Ticket、再用 `PUT` 流式上传的模型。发送端显示本地进度；完整落盘后另一端才收到 `message.created`。图片原图不压缩，客户端另行生成最长边 720px 的 JPEG 缩略图。

文件池会在创建 Batch 前同步检查容量，并每 5 分钟清理过期上传、`.part` 文件、24 小时原文件以及 30 天文件消息。正在下载的文件本轮不会删除。

## 本地 Web 开发

```powershell
cd web
npm.cmd ci
npm.cmd run dev
```

生产环境不运行 Vite 开发服务器。Web 构建产物会嵌入 Go 二进制，由 Go 在同一端口提供。

## Android 构建

Android 项目位于 `android/`，使用仓库内 Gradle Wrapper：

```powershell
cd android
./gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

调试 APK 输出到 `android/app/build/outputs/apk/debug/app-debug.apk`。调试包仅为局域网真机测试允许 API 的 HTTP；Release 构建仍强制 HTTPS。扫码使用 CameraX + ZXing Core，不依赖 Google Play 服务。仓库的 `artifacts/` 被忽略，发布 APK 通过 GitHub Release 分发。

## 部署边界

容器只提供 `0.0.0.0:5757` 上的 HTTP/WS。公网 HTTPS、证书和反向代理由 1Panel 负责；容器内不包含 Nginx、Caddy 或 TLS 配置。
