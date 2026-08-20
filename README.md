# 私人文件传输助手 V1.0.3

一个由你自己部署、自己掌控数据的 Android ↔ Web 文件传输助手。它让一台 Android 手机与一台浏览器设备安全配对后，在同一条时间线中收发文字、图片和文件。

项目不需要注册账号，不依赖第三方云盘；服务、SQLite 数据库、上传文件和缩略图都保存在你自己的 Docker 数据卷中。

> 这是 V1 冻结版本。它设计为**单个 Android Master + 单个已配对浏览器设备**使用，不是多人网盘或公开分享站。

## 你可以用它做什么

- 在手机和电脑浏览器之间发送文字、图片与文件。
- Android 端使用系统照片/文件选择器；Web 端可选择文件、拖放文件或直接粘贴任意剪贴板文件。
- Web 重复粘贴会先确认；可从顶栏多选当前时间线中的图片和文件，批量保存到一个目录。
- 查看上传进度、失败后重试、下载文件、全屏预览相邻批次的图片。
- Android 可设置默认保存目录、查看保存位置、开启传输结果通知，并保存多个服务器档案进行切换。
- 搜索文字消息和文件名；新消息会实时同步，离开底部阅读历史时可通过悬浮按钮一键返回最新消息。
- 使用二维码或 6 位备用码配对浏览器。
- 自托管：Go 服务、React 页面、SQLite、文件和缩略图都在一个 Docker 容器与一个持久化数据卷中。

## 开始前：你需要准备什么

| 使用场景 | 需要准备 |
| --- | --- |
| 本地电脑/局域网体验 | Git、Docker Desktop（含 Docker Compose）、Android 手机和同一局域网。 |
| 云服务器公网使用 | 一台已安装 Docker 与 1Panel 的 Linux 服务器、一个已解析到服务器 IP 的域名。 |
| Android 客户端 | 从 [GitHub Releases](https://github.com/xiaoxiaoxiaoHuanGe/TransDot/releases) 下载 APK；或自行构建。 |

不需要在部署电脑安装 Go、Node.js、npm 或 Android Studio。

## 重要概念与安全提醒

- `.env` 是你的私密部署配置，**绝不能上传到 GitHub**或发给别人。
- `OWNER_SETUP_TOKEN` 是可选的手动恢复密钥；扫码初始化不需要它。配置时至少使用 32 个随机字符。
- Master Claim 只能成功一次。V1 没有账号找回功能；如果卸载 Android 应用且丢失了 Master Token，可能无法恢复控制权。
- 公网部署必须使用 HTTPS 域名。不要把 `5757` 端口直接暴露给公网。
- 备份数据时要备份 Docker 卷 `transfer-assistant-data`；只备份 Git 仓库不会保留消息、配对关系和已上传文件。

生成随机初始化密码的示例：

```bash
openssl rand -base64 48
```

Windows PowerShell 也可以使用：

```powershell
-join ((48..57) + (65..90) + (97..122) | Get-Random -Count 48 | ForEach-Object {[char]$_})
```

## 方案 A：在本地电脑或局域网部署

适合先在自己的 Windows 电脑上体验。Android 手机和电脑需要连接同一个 Wi-Fi/局域网。

### 1. 安装 Docker Desktop 和 Git

- 安装 [Docker Desktop](https://www.docker.com/products/docker-desktop/)，启动它并确认 Docker 状态为 Running。
- 安装 [Git](https://git-scm.com/downloads)。

打开 PowerShell，确认二者可用：

```powershell
git --version
docker --version
docker compose version
```

### 2. 下载项目并创建私密配置

```powershell
git clone https://github.com/xiaoxiaoxiaoHuanGe/TransDot.git TransDot
cd TransDot
Copy-Item .env.example .env
notepad .env
```

扫码初始化时不需要修改 `OWNER_SETUP_TOKEN`。需要保留手动恢复入口时，可设置随机密钥：

```env
OWNER_SETUP_TOKEN=替换成至少32位随机字符
```

本地使用时保留 `HOST_PORT=5757` 即可。首次部署不必修改其他项目。

### 3. 构建并启动

```powershell
docker compose -p transdot up --build -d
docker compose ps
Invoke-RestMethod http://localhost:5757/healthz
```

最后一条命令应返回：

```json
{"status":"ok"}
```

在部署电脑浏览器打开 <http://localhost:5757>。

### 4. 让手机访问电脑

在 PowerShell 中查看电脑的局域网 IPv4 地址：

```powershell
ipconfig
```

找到类似 `192.168.1.10` 的 IPv4 地址。在 Android 应用中填写：

```text
http://192.168.1.10:5757
```

将地址中的 IP 换成你自己的电脑 IP。若手机无法连接，请在 Windows 防火墙中允许 Docker/端口 TCP 5757 的局域网入站访问。不要填写 `localhost`：在手机上它指向手机自己，不是电脑。

## 方案 B：在云服务器 + 1Panel 部署（推荐公网使用）

最终访问链路是：

```text
手机或浏览器 → https://你的域名 → 1Panel HTTPS / 反向代理 → Docker 127.0.0.1:5757
```

容器内没有 Nginx、Caddy 或 HTTPS 配置；证书与 HTTPS 由 1Panel 管理。

### 1. 准备域名和服务器

1. 在域名服务商处新增 A 记录，例如 `transdot.example.com`，指向你的服务器公网 IP。
2. 在云防火墙/安全组放行 TCP `80` 和 `443`。
3. **不要**放行 TCP `5757` 到公网。
4. 确认 1Panel 已安装 Docker，并可在 1Panel 终端或 SSH 中执行 `docker compose version`。

### 2. 获取项目并配置 `.env`

在 1Panel 的“终端”中执行：

```bash
cd /opt
git clone https://github.com/xiaoxiaoxiaoHuanGe/TransDot.git transdot
cd /opt/transdot
cp .env.example .env
nano .env
```

如果服务器无法直接访问 GitHub，可在本机下载源码压缩包后，通过 1Panel 文件管理上传并解压到 `/opt/transdot`。不要上传本机的 `.env`、Docker 数据卷或数据库。

编辑 `.env`，设置以下两项：

```env
# 只允许服务器本机访问容器端口，公网访问交给 1Panel 反向代理
HOST_BIND=127.0.0.1
HOST_PORT=5757
PUBLIC_URL=https://transdot.example.com

# 可选：手动恢复时使用，至少32位
OWNER_SETUP_TOKEN=
```

其余限制项可以先保留默认值：单文件 300 MB、单批 500 MB/20 项、文件池 1 GB、原文件 24 小时。

### 3. 创建并启动 Compose 应用

两种方式任选其一。

**方式一：在 1Panel 图形界面操作**

1. 进入 `容器 → 编排 → 创建编排`。
2. 名称填写 `transdot`。
3. 工作目录选择 `/opt/transdot`。
4. 使用该目录内的 `docker-compose.yml` 创建编排。
5. 创建后点击“启动”，等待状态变为运行中。

**方式二：在终端操作**

```bash
cd /opt/transdot
docker compose -p transdot up --build -d
docker compose ps
curl http://127.0.0.1:5757/healthz
```

健康检查返回 `{"status":"ok"}` 代表服务正常。

> 若服务器拉取构建镜像很慢或失败，可在 `.env` 中将 `OFFICIAL_IMAGE_REGISTRY` 改为你的服务器可访问的 Docker Official Images 镜像地址，再重新执行构建。
>
> 若构建停在 `go mod download`，可在中国大陆服务器的 `.env` 中设置 `GOPROXY=https://goproxy.cn,direct`。该值只在镜像构建阶段生效，不会改变容器运行时的网络配置。

### 4. 在 1Panel 创建反向代理与 HTTPS

1. 进入 `网站 → 网站 → 创建网站`。
2. 选择“反向代理”。
3. 填写域名，例如 `transdot.example.com`。
4. 代理地址填写：`http://127.0.0.1:5757`。
5. 开启 WebSocket 支持。
6. 在“HTTPS”中申请 Let's Encrypt 证书，并开启“强制 HTTPS”。

如果网站使用 `https://域名:非标准端口`，请在 1Panel 的反向代理高级配置中确保 Host 请求头保留端口：

```nginx
proxy_set_header Host $http_host;
proxy_set_header Upgrade $http_upgrade;
proxy_set_header Connection "upgrade";
```

不要使用 `proxy_set_header Host $host;`，它会丢失非标准端口，造成 WebSocket 的 Host 与 Origin 不一致。

完成后访问：

```text
https://transdot.example.com/healthz
```

应返回：

```json
{"status":"ok"}
```

现在在浏览器和 Android 应用中都使用：

```text
https://transdot.example.com
```

不要填写 `:5757`，也不要填写 `http://`。

## 第一次怎么使用

### 1. 扫码绑定 Android Master

1. 在浏览器打开部署后的 HTTPS 地址，未初始化页面会显示绑定二维码。
2. 在 Android 手机上安装 APK，点击“扫码连接服务器”。
3. 扫描网页二维码，核对域名和实例指纹后确认。
4. APP 自动保存服务器地址和 Master Token，当前浏览器也会自动完成绑定。

二维码使用两分钟有效、只能消费一次的 Bootstrap 凭据，不包含 `OWNER_SETUP_TOKEN`。手动输入地址和初始化密钥的方式继续保留为应急入口。

成功后，Android 就是此服务的唯一 Master。Token 会由应用安全保存，服务器不会明文保存它。

### 2. 配对浏览器设备

1. 在电脑浏览器打开服务地址。
2. 页面会显示有效期两分钟的二维码和 6 位备用码。
3. 在 Android 应用点击“配对 Windows”，扫描二维码；不能扫码时输入备用码。
4. 如果之前已配对另一台浏览器，需在 Android 端明确确认替换。

配对成功后，手机与 Web 页面将显示同一条时间线。

### 3. 发送和接收内容

- **文字**：在底部输入框输入内容并发送。
- **图片**：Android 选择“照片”；Web 点击图片按钮、拖入图片或直接粘贴剪贴板图片。
- **其他文件**：Android 选择“文件”；Web 点击附件按钮、拖放文件，或从资源管理器复制后粘贴到页面。
- **下载**：点击文件卡片的下载按钮；Android 会调用系统保存位置。
- **查看图片**：点击缩略图打开全屏查看器，可左右浏览同一批图片。
- **搜索**：在搜索框中搜索文字内容或文件名。

文件原图不会被压缩；图片预览使用最长边 720px 的 JPEG 缩略图。上传完成并安全写入磁盘后，另一端才会看到新消息。

## 默认文件限制与自动清理

| 项目 | 默认值 |
| --- | --- |
| 单个文件 | 300 MB |
| 单次批量上传 | 最多 20 个文件、合计 500 MB |
| 文件池总容量 | 1 GB |
| 原始文件保留时间 | 24 小时 |
| 缩略图及文件消息保留时间 | 30 天 |
| 配对码有效期 | 2 分钟 |

服务每 5 分钟清理已过期上传、临时 `.part` 文件与过期内容；正在下载的文件不会在当前清理轮次被删除。

可在 `.env` 中调整容量和保留时间。修改后执行：

```bash
docker compose up -d
```

## 日常维护、升级与备份

查看运行状态和日志：

```bash
cd /opt/transdot
docker compose ps
docker compose logs -f
```

升级到仓库最新版本：

```bash
cd /opt/transdot
sh docker/update.sh
```

停止服务（保留数据）：

```bash
docker compose down
```

> 不要使用 `docker compose down -v`，它会删除包含 SQLite、消息和上传文件的 Docker 数据卷。

需要明确丢弃全部数据、配对关系和服务器身份时，执行：

```bash
cd /opt/transdot
sh docker/reset.sh RESET
```

Reset 后打开 Web 页面并重新扫码绑定。普通更新与 Reset 完全分开。

需要迁移或备份时，请备份 Docker 卷 `transfer-assistant-data`。先停止服务，再由熟悉 Docker 卷备份的人员进行归档；恢复时必须恢复整个数据卷，而不仅是 Git 仓库。

## 开发与自行构建

Web 开发：

```powershell
cd web
npm.cmd ci
npm.cmd run dev
```

Android Debug APK：

```powershell
cd android
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

输出路径为 `android/app/build/outputs/apk/debug/app-debug.apk`。调试包允许局域网 HTTP 以便实机测试；正式 Release 构建要求 HTTPS。

## 技术组成

- 后端：Go、SQLite、FTS5、WebSocket
- Web：React、TypeScript、Vite（构建后由 Go 同源托管）
- Android：Kotlin、Jetpack Compose、Material 3
- 部署：Docker 多阶段构建、Docker Compose
- 数据目录：容器内统一为 `/app/data`

## 常见问题

**手机访问不了本地电脑？**

确认手机和电脑在同一网络；使用电脑的局域网 IPv4 地址，不要使用 `localhost`；检查 Windows 防火墙是否允许 5757 入站访问。

**公网打开域名后 WebSocket 或配对失败？**

确认 1Panel 的反向代理目标是 `http://127.0.0.1:5757`，并且已开启 WebSocket 与 HTTPS。若公网地址包含非标准 HTTPS 端口，确认反向代理使用 `proxy_set_header Host $http_host;`，不要使用 `$host`。

**服务重启后消息不见了？**

检查是否误用了 `docker compose down -v`，或在 1Panel 中删除了卷 `transfer-assistant-data`。正常 `docker compose down` 与升级重建不会删除数据。

**首次 Claim 失败或忘记初始化密码？**

确认使用的是 `.env` 内同一个 `OWNER_SETUP_TOKEN`。如果 Master 已成功初始化，不能再次 Claim；V1 不提供账户恢复流程。
