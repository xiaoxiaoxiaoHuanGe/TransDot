# 部署更新、实例重置与二维码绑定设计

## 1. 背景与前提

当前有两个问题：

1. 1Panel 更新时旧容器可能占用端口，或新部署使用了不同数据卷，导致服务看起来需要重新初始化。
2. Android 首次连接必须手动输入服务器地址和 `OWNER_SETUP_TOKEN`，之后才能扫描 Web 配对二维码。

本设计以“旧数据不需要保留”为前提，但不把数据删除放进普通更新。普通更新保持当前实例身份；需要全新环境时，用户明确执行一次破坏性的 Reset，然后通过二维码重新绑定手机。

## 2. 目标

### 部署

- 1Panel 只维护一个固定的 `transdot` Compose 项目。
- 普通更新自动替换旧容器，不重新 Claim Android Master。
- 更新后通过 `/healthz` 健康检查。
- 提供单独的“重置实例”入口，删除数据后生成新服务器身份。
- 普通更新绝不隐式删除数据卷。

### 连接

- 首次部署或 Reset 后，Android 扫描 Web 二维码并确认一次即可连接。
- 二维码携带服务器地址和实例标识，用户不再手动输入地址。
- 二维码不携带永久 `OWNER_SETUP_TOKEN` 或 Master Token。
- 已初始化服务器继续使用短时、单次的 Pairing QR。
- APP 能发现“同一域名但实例已经重置”，不会误用旧 Token。

### 安全

- 服务端仍只保存 Master Token 哈希，Android 使用 Keystore 加密保存 Token。
- Bootstrap 凭据短时有效、单次消费、绑定实例。
- 公网连接必须使用 HTTPS。
- 首次绑定和浏览器替换都需要 APP 用户确认。
- `OWNER_SETUP_TOKEN` 保留为手动恢复入口，但不进入二维码。

## 3. 非目标

- 不实现多人账号、角色权限或云端账号。
- 不保证零停机更新，允许短暂容器切换。
- 不自动备份旧数据。
- 不提供公开的永久无密钥 Claim 接口。
- 不支持多个应用容器同时写入同一个生产 SQLite 数据目录。

## 4. 核心概念

### 4.1 Server Instance

实例是一个数据目录对应的逻辑身份，包含：

- `instance_id`：随机生成并持久化在数据卷中。
- `instance_fingerprint`：由实例 ID 派生的短指纹，用于人工确认。
- `initialized`：是否已绑定 Android Master。
- 当前设备、消息、文件和浏览器配对状态。

删除数据卷后生成新的 `instance_id`。域名不变也视为新服务器。

### 4.2 Bootstrap、Pairing、Reset

- **Bootstrap**：未初始化服务器首次绑定 Android Master 的流程。
- **Pairing**：已有 Master 后，把浏览器绑定到该 Master 的流程。
- **Reset**：明确删除数据卷并重新创建实例的破坏性流程，不属于普通更新。

## 5. 用户流程

### 5.1 首次部署

1. 在 1Panel 创建一次 `transdot` 编排，配置域名、HTTPS 和 `.env`。
2. 打开 `https://example.com`。
3. 未初始化时，Web 显示“绑定 Android”页面和 Bootstrap QR。
4. Android 点击“扫码连接服务器”。
5. APP 解析服务器地址、实例 ID、实例指纹和一次性 Bootstrap 凭据。
6. APP 校验协议版本、HTTPS 和主机格式，展示服务器信息。
7. 用户确认绑定。
8. APP 提交 Bootstrap 凭据；服务端在一个事务中校验凭据、创建 Master、消费会话，并可选地绑定当前浏览器。
9. APP 将 Master Token 写入 Keystore；Web 得到绑定结果后进入时间线。

用户操作只有：

```text
部署 → 打开网页 → 手机扫码 → 确认
```

### 5.2 普通版本更新

1. 1Panel 在固定项目目录执行 `git pull`。
2. 执行 `docker compose -p transdot up -d --build --remove-orphans`。
3. Compose 替换同一个服务容器，继续挂载同一个数据卷。
4. 启动时执行未完成数据库迁移，已完成迁移跳过。
5. 轮询 `/healthz`，成功后标记更新完成。
6. Android 继续使用已保存的 Master Token。
7. 浏览器 Cookie 有效时自动恢复；Cookie 丢失时只需扫描普通 Pairing QR。

普通更新不能执行 `docker compose down -v`，也不能删除 `transfer-assistant-data`。

### 5.3 显式 Reset

1. 1Panel 显示警告：删除消息、文件、配对关系和服务器身份，无法恢复。
2. 用户输入 `RESET` 并二次确认。
3. 本地脚本停止服务，删除明确指定的数据卷，再用同一个 Compose 项目启动。
4. 新实例生成新的 `instance_id`，Web 显示新的 Bootstrap QR。
5. APP 扫码发现同域名但实例 ID 变化，提示“服务器已重置，创建新的档案”。
6. 用户确认后完成新的 Bootstrap；旧档案不静默覆盖。

### 5.4 普通 Pairing

1. 浏览器打开已初始化服务器，Web 创建有效期约 2 分钟的 Pairing Session。
2. QR 携带服务器地址、实例 ID、`session_id` 和 `qr_secret`。
3. Android 校验来源和实例 ID，用户确认后使用已保存 Master Token 批准。
4. 已存在浏览器时继续显示现有的“替换旧浏览器”确认。

## 6. 二维码协议

### 6.1 Bootstrap QR

```json
{
  "v": 2,
  "kind": "bootstrap",
  "server_url": "https://example.com",
  "instance_id": "01J...",
  "instance_fingerprint": "7F3A-91C2",
  "bootstrap_session_id": "...",
  "bootstrap_secret": "...",
  "expires_at": "2026-08-20T12:00:00Z"
}
```

规则：

- 服务端只保存 `bootstrap_secret` 哈希。
- 默认有效期 120 秒，每个会话只能消费一次。
- 首次初始化成功后，所有未消费 Bootstrap 会话立即失效。
- 正式环境只允许 HTTPS；调试包按现有 localhost/局域网策略放宽。
- APP 不能因为 QR 中的 URL 就无提示地发送已有 Master Token。

### 6.2 Pairing QR

```json
{
  "v": 2,
  "kind": "pairing",
  "server_url": "https://example.com",
  "instance_id": "01J...",
  "session_id": "...",
  "qr_secret": "...",
  "expires_at": "2026-08-20T12:02:00Z"
}
```

现有 6 位备用码继续保留。服务器地址和实例 ID 只用于选择/校验档案，不是认证凭据。

### 6.3 兼容

- APP 过渡期继续解析当前 `v: 1` Pairing QR。
- 新 Web 只生成 `v: 2`。
- Bootstrap 只由新版 Web 和新版 Android 支持。
- 过期、重放、实例不匹配、来源不安全必须显示不同错误。

## 7. 服务端接口

### `GET /api/v1/instance/info`

返回实例公开信息：

```json
{
  "instance_id": "01J...",
  "instance_fingerprint": "7F3A-91C2",
  "initialized": false,
  "public_url": "https://example.com"
}
```

`public_url` 优先来自受信任配置；反向代理头不能直接作为认证依据。

### `POST /api/v1/bootstrap/sessions`

- 仅在 `initialized=false` 时允许。
- 创建短时会话和 QR 载荷。
- 按客户端 IP 和实例限流。
- 永不返回 `OWNER_SETUP_TOKEN`。

### `POST /api/v1/bootstrap/claim`

```json
{
  "bootstrap_session_id": "...",
  "bootstrap_secret": "..."
}
```

服务端在单个事务中完成凭据校验、Master 创建、会话消费和可选的首个浏览器绑定。并发请求只能有一个成功；其他请求返回 `ALREADY_INITIALIZED` 或 `BOOTSTRAP_CONSUMED`。

### Reset

Reset 不提供公网 HTTP 路由，只能由 1Panel 本地脚本执行。脚本必须固定校验目标卷名称，不能根据请求参数拼接删除路径。

## 8. 部署配置

Compose 约束：

- 固定项目名 `transdot`。
- 生产端口只允许一个 `transfer-assistant` 服务绑定。
- 保留健康检查和 `restart: unless-stopped`。
- 将宿主机端口与容器端口拆成独立变量，避免 `PORT` 同时承担两种含义。
- 数据卷名称固定；普通更新不删除，Reset 单独执行。
- HTTPS、WebSocket 和反向代理继续由 1Panel 管理。

普通更新入口：

```bash
set -e
cd /opt/transdot
git pull
docker compose -p transdot up -d --build --remove-orphans
curl --fail --retry 12 --retry-delay 2 http://127.0.0.1:5757/healthz
```

Reset 入口只允许通过 1Panel 明确按钮或服务器本地命令触发，删除卷前要求二次确认，并在启动后等待健康检查通过。

## 9. Android 行为

新增“扫码连接服务器”，与现有“配对 Windows”区分：

- Bootstrap QR：创建或更新服务器档案并完成 Master Claim。
- Pairing QR：使用当前档案批准浏览器。

档案保存：名称、`server_url`、`instance_id`、实例指纹、加密 Master Token 和状态。

二维码 URL 与当前档案不一致时，必须确认添加/切换档案；URL 相同但实例 ID 不同，必须提示服务器已重置。

## 10. Web 行为

根据实例状态显示：

- 未初始化：Bootstrap 页面、实例指纹和二维码。
- 已初始化未配对：Pairing 页面、二维码和 6 位码。
- 已配对：时间线。

Bootstrap 页面不显示永久密钥，也不提供匿名跳过绑定。二维码过期后生成新的会话，不延长旧会话。

## 11. 错误处理

至少区分：`BOOTSTRAP_EXPIRED`、`BOOTSTRAP_CONSUMED`、`ALREADY_INITIALIZED`、`INSTANCE_MISMATCH`、`UNSAFE_ORIGIN`、`RATE_LIMITED`、`DEVICE_REVOKED`。

错误页必须给出下一步操作，不允许通过重复点击绕过限流或会话状态。

## 12. 安全边界

不采用以下做法：

- 将 `OWNER_SETUP_TOKEN` 放进二维码。
- 公开无密钥自动 Claim 接口。
- APP 根据二维码 URL 静默切换并发送 Master Token。
- 将 Reset 做成公网 GET/POST。

必须保留：随机短时凭据、服务端哈希、APP 用户确认、HTTPS、Secure/HttpOnly/SameSite Cookie，以及初始化与 Master 创建的事务一致性。

Bootstrap QR 是“看到部署页面的人可以发起绑定”的凭据。自托管场景可把部署页面的可见性视为管理员边界；如果页面公开暴露，则额外要求 Web 端点击批准，或继续使用手动密钥。

## 13. 测试设计

### 服务端

- 首次启动生成稳定实例 ID，重启不变，删除数据后变化。
- Bootstrap 过期、重放、并发消费正确失败。
- 两个并发 Claim 只有一个成功。
- 已初始化实例不能创建新的 Bootstrap Master。
- Pairing QR 校验实例 ID 和凭据哈希。
- Reset 不存在公网路由。

### Web

- 未初始化显示 Bootstrap QR，已初始化显示 Pairing QR。
- QR 倒计时、过期和重新生成正常。
- HTTP 局域网来源被阻止，HTTPS 来源允许。
- 不同实例显示不同指纹。
- 限流响应正确显示 `Retry-After`。

### Android

- 正确解析两种 QR，拒绝错误版本、来源、过期和实例不匹配。
- 同 URL 新实例要求创建新档案确认。
- Bootstrap 成功后 Token 写入 Keystore。
- Bootstrap 失败不破坏原档案。
- Pairing QR 不覆盖 Master Token。

### 部署验收

- 同一 Compose 项目连续更新不会产生第二个生产容器。
- 普通更新后健康检查通过，Android 无需重新 Claim。
- Reset 后数据卷为空，Web 显示新的 Bootstrap QR。
- Reset 后旧 Token 无法访问新实例。
- 1Panel HTTPS 和 WebSocket 正常。

## 14. 发布与回滚

分两阶段发布：

1. 先固定 Compose 项目、更新脚本、实例标识和健康检查，解决部署稳定性。
2. 再发布 Bootstrap QR、Android 扫码初始化和 Web 状态页。

Bootstrap 协议启用前保留旧版手动地址和 `OWNER_SETUP_TOKEN` 流程。回滚代码不自动回滚数据卷；数据库迁移必须保持兼容，或明确标记不可逆迁移。

## 15. 验收标准

1. 普通更新不要求 Android 再次输入地址或初始化密钥。
2. 旧容器不会与新容器同时绑定生产端口。
3. 未初始化页面显示 Bootstrap QR，手机扫码可完成首次绑定。
4. Bootstrap QR 不包含 `OWNER_SETUP_TOKEN` 或 Master Token。
5. 已初始化页面显示 Pairing QR，扫码可完成浏览器配对。
6. 同域名 Reset 后，APP 能识别新实例并要求确认。
7. Reset 是显式、可见、不可误触发的数据删除操作。
8. HTTPS、Cookie 安全属性、Token 哈希存储和限流不被削弱。

## 16. 进入实现前需确认

1. Bootstrap 是否需要 Web 端再点击一次“批准此手机”，还是 APP 确认即可。
2. Reset 是由 1Panel 按钮执行，还是只提供服务器本地脚本。
3. 是否同时提供“更新并重置”快捷操作，还是将两者完全分开。
4. Bootstrap 成功后是否自动绑定当前浏览器，还是仍显示一次 Pairing QR。
