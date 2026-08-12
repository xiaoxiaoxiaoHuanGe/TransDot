# 私人文件传输助手

这是《私人文件传输助手 V1 — 技术设计冻结规范》的 Task 01 基础工程。当前只包含：

- Go HTTP 服务
- SQLite migration 基础
- `GET /healthz`
- React + TypeScript + Vite 基础页面
- Go 同源静态文件托管与 SPA fallback
- 单容器 Docker 多阶段构建
- `/app/data` 统一持久化目录

认证、配对、消息、WebSocket、文件传输与 Android 应用将在后续任务中实现。

## 启动

无需在 Windows 安装 Go：

```powershell
docker compose up --build -d
```

构建默认从 AWS ECR Public 的 Docker Official Images 分发端点获取 Node、Go 与 Alpine 官方镜像。如果部署环境可直接访问 Docker Hub，可在 `.env` 中设置 `OFFICIAL_IMAGE_REGISTRY=docker.io/library`。

打开：

- Web：<http://localhost:5757>
- Health：<http://localhost:5757/healthz>

停止服务：

```powershell
docker compose down
```

命名卷 `transfer-data` 挂载到容器内 `/app/data`。执行 `docker compose down` 不会删除数据；只有显式添加 `--volumes` 才会删除该卷。

## 本地 Web 开发

```powershell
cd web
npm.cmd ci
npm.cmd run dev
```

生产环境不运行 Vite 开发服务器。Web 构建产物会嵌入 Go 二进制，由 Go 在同一端口提供。

## 部署边界

容器只提供 `0.0.0.0:5757` 上的 HTTP/WS。公网 HTTPS、证书和反向代理由 1Panel 负责；容器内不包含 Nginx、Caddy 或 TLS 配置。
