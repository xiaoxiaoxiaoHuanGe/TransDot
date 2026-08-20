# 手机重绑定二维码与 APP 顶部信息设计

## 1. 范围

本设计只在 `codex/d` 分支实现，`codex/c` 保持不变。功能包含：

- 已初始化服务器为新安装的 Android APP 提供一次性重绑定二维码。
- Web 端从已授权时间线进入重绑定页面，并轮询结果。
- Android 扫码、校验服务器身份、明确确认后替换旧 Master 凭据。
- APP 顶部把标题、服务器地址、连接状态渲染为独立的三行。

普通部署更新、首次 Bootstrap 和已有手机配对流程继续兼容，不改变其接口语义。

## 2. 重绑定用户流程

1. 用户在已登录 Web 的时间线点击“重新绑定手机”。
2. Web 调用 `POST /api/v1/rebind/sessions`，服务端创建有效期 120 秒的会话并返回 QR payload。
3. Web 显示二维码和倒计时，同时轮询 `/api/v1/rebind/sessions/{id}/status`。
4. 新安装 APP 在连接入口选择“扫码连接服务器”，识别 `kind: rebind` 的二维码。
5. APP 显示服务器地址、实例指纹和“这会替换当前手机连接”提示；用户确认后提交凭据。
6. 服务端在一个事务中校验会话、确认实例已初始化、撤销所有活动 `android_master` 设备并创建新的 Master 设备。
7. APP 将新 Token 加密写入 Keystore，使用新档案进入时间线；Web 收到 `consumed` 后恢复时间线。
8. 旧 APP 的请求立即返回 `DEVICE_REVOKED`，不能继续发送或读取数据。

重绑定二维码不能在未初始化服务器上创建，也不能被未授权 Web 请求创建。二维码过期、重复消费、实例不匹配和非 HTTPS 来源都必须返回明确错误。

## 3. 服务端设计

### 3.1 数据库

新增迁移 `010_rebind_sessions.sql`；`009_server_instance_compat.sql` 继续负责兼容旧的迁移编号冲突：

```sql
CREATE TABLE rebind_sessions (
    id TEXT PRIMARY KEY,
    instance_id TEXT NOT NULL,
    secret_hash BLOB NOT NULL CHECK (length(secret_hash) = 32),
    browser_device_id TEXT NOT NULL REFERENCES devices(id),
    status TEXT NOT NULL CHECK (status IN ('pending', 'expired', 'consumed')),
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    consumed_at TEXT
);
CREATE INDEX rebind_sessions_expiry ON rebind_sessions(status, expires_at);
```

会话只保存 secret 的 SHA-256 哈希，不保存二维码明文凭据。

### 3.2 接口

`POST /api/v1/rebind/sessions`

- 仅接受有效 `transfer_browser_v1` Cookie。
- 要求 `app_state.initialized = 1`。
- 返回 `session_id`、`qr_payload`、`expires_at` 和轮询间隔。
- 同一浏览器创建新会话时，原有待处理会话立即标记为 `expired`，保证刷新二维码等同于轮换凭据。
- 反向代理部署必须配置 HTTPS `PUBLIC_URL`；未配置时只接受服务进程直接终止 TLS 的请求。
- QR payload 使用版本 2：

```json
{
  "v": 2,
  "kind": "rebind",
  "server_url": "https://example.com",
  "instance_id": "01J...",
  "instance_fingerprint": "7F3A-91C2",
  "rebind_session_id": "...",
  "rebind_secret": "...",
  "expires_at": "2026-08-20T12:02:00Z"
}
```

`GET /api/v1/rebind/sessions/{id}/status`

- 仅允许创建会话的浏览器 Cookie 查询。
- 返回 `pending`、`consumed` 或 `expired`。
- 不返回 Master Token。

`POST /api/v1/rebind/claim`

- 只接受 APP 明确确认后的 `rebind_session_id`、`rebind_secret`。
- 原子执行：校验会话和实例、生成新 Android Master Token、撤销旧活动 Master、插入新设备、标记会话 `consumed`。
- 返回新 `device_id`、新 Token 和实例信息；Token 只在该响应中出现一次。

### 3.3 错误语义

- `REBINDS_NOT_ALLOWED`：服务器未初始化。
- `REBINDS_EXPIRED`：会话过期。
- `REBINDS_CONSUMED`：会话已使用。
- `REBINDS_INVALID`：secret 或实例不匹配。
- `DEVICE_REVOKED`：旧 APP 使用了已撤销 Token。

## 4. Web 设计

`App` 保留现有 Bootstrap/Pairing 状态机，新增 `rebind` 状态和 `createRebindSession`。已认证的 `TimelineApp` 接收 `onRebindPhone` 回调；点击后返回二维码页，成功后重新读取 `/api/v1/auth/session`。

重绑定页复用现有二维码卡片和倒计时组件，但文案明确写为“重新绑定手机”，并显示“将替换当前手机连接”。页面只提供取消、刷新二维码和等待状态，不提供 Token 或密钥输入框。

## 5. Android 设计

`PairingPayload` 增加 `kind: rebind` 解析结果和 `RebindPayload` 类型。扫码后先展示服务器地址、实例指纹和替换警告；确认后调用新的 `NetworkRebindRepository`，成功后通过现有 `SessionStore` 原子保存新会话，再切换到时间线。失败时保留当前页面和可重试状态，不清除旧档案。

新安装 APP 没有旧档案时从扫码入口进入；已有 APP 也可从设置中触发相同扫码流程。服务器地址和实例 ID 不匹配时不得覆盖已有档案。

## 6. APP 顶部三行

新增纯函数 `activeServerStatusLines(name, status): List<String>`，返回地址和状态两项。`AppTopBar` 增加可选 `subtitleLines` 参数并保留原 `subtitle` 兼容调用方。`TimelineTopBar` 使用：

```text
传输助手
117.72.97.238
已连接
```

地址行 `maxLines = 1`、`TextOverflow.Ellipsis`；状态行 `maxLines = 1`，不允许被地址挤压或折行。其它页面继续使用原单行 subtitle。

## 7. 测试与验收

- Go：重绑定会话创建、未初始化拒绝、过期/重放、实例不匹配、旧 Master 撤销、新 Master 唯一性和事务回滚。
- Web：重绑定页面状态轮询、成功/过期/错误状态和入口回调。
- Android：QR 解析、HTTPS/实例校验、确认后保存新 Token、失败不覆盖旧会话。
- Android 单元测试验证 `activeServerStatusLines` 返回独立地址和状态。
- 构建验证：Web 全量测试、Go 全量测试、Android `testDebugUnitTest` 与 `assembleDebug`。

## 8. 安全与回退

所有会话凭据使用随机值和哈希存储，默认 TTL 120 秒且只能消费一次。任何实现失败都不修改 `codex/c`；D 分支可整体废弃。只有 D 分支验收通过后，才允许合并到主分支。
