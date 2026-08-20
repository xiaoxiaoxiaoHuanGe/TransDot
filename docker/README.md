# Docker 辅助目录

V1 的 `Dockerfile` 与 `docker-compose.yml` 位于仓库根目录。容器内只有 Go 应用、嵌入的 React 静态资源、SQLite 和本地文件存储；不会在此目录加入 Nginx、Caddy 或容器内 TLS。

`update.sh` 使用固定的 `transdot` Compose 项目更新同一个容器和数据卷。
`reset.sh RESET` 是破坏性操作，会删除固定卷 `transfer-assistant-data` 后创建全新实例。普通更新不要调用重置脚本。

局域网快传复用现有 HTTPS/WSS `/ws` 端点进行 WebRTC 信令，文件内容直接在 Android 与 Chrome/Edge 之间传输。Compose 不新增端口、环境变量、数据库表或持久卷。部署本功能只需普通更新：

```sh
cd /opt/transdot
sh docker/update.sh
```

不要为局域网快传执行 `reset.sh`。1Panel 仍只需将公开 HTTPS 网站反向代理到 `127.0.0.1:${HOST_PORT}` 并开启 WebSocket；无需暴露额外 TCP/UDP 端口。Host ICE 的设备间流量发生在手机与电脑所在局域网内，路由器 AP 隔离和终端防火墙不由 Docker 或 1Panel 绕过。
