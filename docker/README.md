# Docker 辅助目录

V1 的 `Dockerfile` 与 `docker-compose.yml` 位于仓库根目录。容器内只有 Go 应用、嵌入的 React 静态资源、SQLite 和本地文件存储；不会在此目录加入 Nginx、Caddy 或容器内 TLS。

`update.sh` 使用固定的 `transdot` Compose 项目更新同一个容器和数据卷。
`reset.sh RESET` 是破坏性操作，会删除固定卷 `transfer-assistant-data` 后创建全新实例。普通更新不要调用重置脚本。
