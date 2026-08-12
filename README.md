# 私人文件传输助手

这是《私人文件传输助手 V1 — 技术设计冻结规范》的实现工程。当前已完成 Task 01 基线、Task 02 首次初始化与 Task 03 Windows 配对闭环：

- Go HTTP 服务
- SQLite migration 基础
- `GET /healthz`
- React + TypeScript + Vite 基础页面
- Go 同源静态文件托管与 SPA fallback
- 单容器 Docker 多阶段构建
- `/app/data` 统一持久化目录
- Android Kotlin + Jetpack Compose 基础应用
- 一次性 Android Master Claim
- Master Token 的服务端 SHA-256 存储与 Android Keystore 加密存储
- Windows 二维码/6 位码配对与 HttpOnly Cookie 鉴权
- 单一有效 Windows 及经 Android 确认的原子替换

消息、认证 WebSocket 与文件传输将在后续任务中实现。

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

调试 APK 输出到 `android/app/build/outputs/apk/debug/app-debug.apk`。调试包仅为局域网真机测试允许 API 的 HTTP；Release 构建仍强制 HTTPS。扫码使用 CameraX + ZXing Core，不依赖 Google Play 服务。

## 部署边界

容器只提供 `0.0.0.0:5757` 上的 HTTP/WS。公网 HTTPS、证书和反向代理由 1Panel 负责；容器内不包含 Nginx、Caddy 或 TLS 配置。
