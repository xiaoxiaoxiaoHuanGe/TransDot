# Docker 辅助目录

V1 的 `Dockerfile` 与 `docker-compose.yml` 位于仓库根目录。容器内只有 Go 应用、嵌入的 React 静态资源、SQLite 和本地文件存储；不会在此目录加入 Nginx、Caddy 或容器内 TLS。
