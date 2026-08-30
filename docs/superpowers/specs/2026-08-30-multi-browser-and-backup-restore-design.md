# TransDot 多浏览器设备管理与备份恢复设计

## 1. 文档状态

- 目标版本：`v1.2.0`
- 状态：进入实现前设计冻结稿
- 适用范围：Go 服务端、React Web、Android/Jetpack Compose、Docker 部署脚本
- 依赖基线：`v1.1.0`，数据库迁移最高版本为 `010_rebind_sessions.sql`

本文把“多浏览器设备管理”和“备份与恢复工具”作为同一版本的两个独立功能域。两者共享设备身份、服务器实例和数据卷安全边界，但不共享业务接口，也不能互相阻塞发布。

## 2. 背景与现状

### 2.1 浏览器设备

当前 `devices` 表通过 `devices_one_active_per_type` 唯一索引限制每种设备类型只能有一个活动设备。新增浏览器配对时，Android 必须确认替换旧浏览器，服务端随后撤销全部活动 `windows_browser`。

现有模型导致：

- 家庭电脑、办公电脑和临时浏览器不能同时使用。
- 用户看不到已授权浏览器清单、名称和最近活动时间。
- 浏览器凭据丢失、设备淘汰后，只能通过“替换当前浏览器”间接清理授权。
- 时间线只能显示消息来自“Windows”，不能区分是哪一台浏览器。

### 2.2 备份恢复

当前持久数据全部位于 Docker 命名卷 `transfer-assistant-data` 的 `/app/data`，其中包含：

- `database/transfer.db`：服务器身份、设备 Token 哈希、配对状态、消息与文件元数据。
- `files/`：仍在保留期内的原始文件。
- `thumbs/`：图片缩略图。
- `tmp/`：未完成上传等临时内容。

README 只建议人工备份整个 Docker 卷，没有一致性快照、校验、恢复前兼容性检查或失败回滚工具。SQLite 使用 WAL 模式，业务文件又与数据库分别落盘，因此运行中直接压缩卷可能得到彼此不一致的副本。

## 3. 总体目标

### 3.1 多浏览器设备管理

1. 一个服务器实例允许最多 10 个活动浏览器同时存在。
2. Android Master 可以查看、重命名和撤销所有浏览器设备。
3. 浏览器可以查看并修改自己的设备名称，不能管理其他设备。
4. 撤销只影响目标浏览器，其 HTTP 请求立即失败，WebSocket 立即断开。
5. 时间线能够显示具体浏览器名称。
6. 现有浏览器 Cookie、Android Master Token、消息和配对二维码继续兼容。

### 3.2 备份与恢复

1. 提供服务器本地 `backup.sh` 和 `restore.sh`，不提供公网备份/恢复 API。
2. 备份覆盖服务器实例、设备授权、消息、原始文件和缩略图。
3. 通过短暂停止应用获得 SQLite 与文件的一致性快照。
4. 每份备份包含版本清单和 SHA-256 校验文件。
5. 恢复前必须验证归档结构、校验和、SQLite 完整性和数据库版本兼容性。
6. 覆盖当前数据前自动创建一份恢复前安全备份。
7. 恢复启动失败时自动尝试回滚到恢复前安全备份。

## 4. 非目标

- 不实现多人账号、组织、角色或细粒度文件权限。
- 不允许浏览器签发、查看或撤销其他浏览器的凭据。
- 不改变“只有一个活动 Android Master”的约束。
- 不把 `windows_browser` 数据库类型重命名为 `browser`；该名称作为兼容性标识保留。
- 不同步浏览器本地设置、下载目录授权或 File System Access API 句柄。
- 不做零停机备份；一致性优先于持续可用。
- 不备份 `.env`、反向代理证书、Docker 镜像或 Git 工作区。
- 不把备份上传到第三方云存储。
- 不从 Web 或 Android 远程触发宿主机恢复。
- 不支持把较新版本数据库恢复到不认识该数据库版本的旧程序。

## 5. 核心决策

### 5.1 保持个人设备模型

系统仍然只有一个 Android Master，所有浏览器共享同一条时间线。新增浏览器是增加一个受信任终端，不产生独立用户、私有空间或所有权规则。

### 5.2 Android Master 是授权控制面

浏览器配对仍需 Android 明确批准。设备列表、跨设备重命名和撤销只接受 Android Master Bearer Token。浏览器只允许修改自己的显示名称。

### 5.3 浏览器配对默认“添加”而非“替换”

只要活动浏览器数量未达到上限，新的配对直接增加设备。旧版 Android 发送的 `replace_existing` 字段继续被接受，但新版服务端忽略该字段，不再因此撤销其他浏览器。

达到上限时，配对批准返回 `BROWSER_LIMIT_REACHED`。用户必须先在 Android 设备管理中撤销一个浏览器，再重新批准；服务端不能自行淘汰最旧设备。

### 5.4 备份使用停机快照

备份时先优雅停止 `transfer-assistant` 服务，再读取数据卷。这样 SQLite 会关闭连接并完成 WAL 收尾，业务文件也不会继续创建、删除或过期。备份结束后无论成功或失败都尝试恢复服务，并执行健康检查。

### 5.5 恢复使用暂存卷

归档先解压到一次性 Docker 暂存卷并完成只读校验。只有校验全部通过，才停止生产服务、创建安全备份并覆盖固定生产卷。禁止把未验证归档直接解压进 `transfer-assistant-data`。

## 6. 功能一：多浏览器设备管理

### 6.1 用户流程

#### 6.1.1 新浏览器配对

1. 未授权浏览器打开 TransDot，Web 生成默认名称，例如“Chrome · Windows”。
2. Web 使用设备名称创建配对会话并展示二维码及 6 位备用码。
3. Android 扫描二维码时显示服务器、浏览器名称和“添加浏览器”确认页。
4. 用户确认后，服务端批准会话。
5. 浏览器轮询消费会话，获得只出现一次的浏览器 Token，并写入现有安全 Cookie。
6. 服务端新增浏览器设备，不撤销其他浏览器。
7. Android 设备列表和所有在线客户端收到 `device.created` 事件并刷新显示。

手动输入 6 位码时，Android 可以在批准响应前显示通用名称“浏览器设备”；服务端仍使用创建会话时保存的名称。

#### 6.1.2 Android 管理设备

1. 用户从时间线设置页进入“已授权浏览器”。
2. 页面加载活动浏览器列表，按最近活动时间降序排列。
3. 每项显示名称、授权时间、最近活动状态。
4. 用户可以重命名设备。
5. 用户撤销设备时必须在确认框中看到目标名称及后果。
6. 撤销成功后目标 WebSocket 立即关闭，目标浏览器下一次请求返回 `DEVICE_REVOKED`。

#### 6.1.3 浏览器管理自己

Web 设置菜单显示当前设备名称。浏览器可以重命名自己；修改结果同步到 Android 设备列表和后续时间线来源标签。浏览器不能撤销自己，也不能查询其他浏览器。

### 6.2 数据库迁移

新增迁移 `011_multi_browser_devices.sql`：

```sql
ALTER TABLE devices
ADD COLUMN display_name TEXT NOT NULL DEFAULT '浏览器设备'
    CHECK (length(display_name) BETWEEN 1 AND 64);

ALTER TABLE pairing_sessions
ADD COLUMN requested_device_name TEXT NOT NULL DEFAULT '浏览器设备'
    CHECK (length(requested_device_name) BETWEEN 1 AND 64);

UPDATE devices
SET display_name = 'Android Master'
WHERE device_type = 'android_master';

DROP INDEX devices_one_active_per_type;

CREATE UNIQUE INDEX devices_one_active_android_master
    ON devices (device_type)
    WHERE device_type = 'android_master' AND revoked_at IS NULL;

CREATE INDEX devices_active_browsers_recent
    ON devices (last_seen_at DESC, created_at DESC)
    WHERE device_type = 'windows_browser' AND revoked_at IS NULL;
```

迁移规则：

- 已有活动浏览器保留 Token、ID、创建时间和授权状态，默认名称为“浏览器设备”。
- Android Master 的 `display_name` 写入“Android Master”，但本版本不开放修改。
- 不删除已撤销设备，因为历史消息仍通过 `source_device_id` 引用它们。
- 不重建 `devices` 表，避免不必要的数据复制和外键风险。

### 6.3 配置

新增环境变量：

```env
MAX_BROWSER_DEVICES=10
```

约束：

- 必须为 `1..50` 的整数，默认 10。
- 只限制活动 `windows_browser` 数量，不包含已撤销设备和 Android Master。
- 配置降低到当前活动数量以下时，不自动撤销设备；只阻止新增配对，直到数量回到限制内。

### 6.4 设备名称规则

- 去除首尾空白，按 Unicode 字符计数 1 至 64 个字符。
- 拒绝控制字符、换行、制表符和空字符串。
- 服务端是最终校验方，不能信任 Web 生成的名称。
- 默认名由 Web 根据 `navigator.userAgentData` 或保守 User-Agent 摘要生成；拿不到时使用“浏览器设备”。
- 不保存完整 User-Agent、IP 地址或硬件指纹。
- 名称不要求唯一；设备 ID 才是稳定身份。

### 6.5 服务端领域模型

新增 `internal/devices` 包，负责：

- `ListActiveBrowsers(ctx)`
- `RenameBrowser(ctx, deviceID, displayName)`
- `RenameSelf(ctx, deviceID, displayName)`
- `RevokeBrowser(ctx, deviceID)`
- `CountActiveBrowsers(ctx)`

返回模型：

```go
type BrowserDevice struct {
    ID          string     `json:"id"`
    DisplayName string     `json:"display_name"`
    CreatedAt   time.Time  `json:"created_at"`
    LastSeenAt  *time.Time `json:"last_seen_at"`
}
```

撤销事务必须：

1. 验证目标是 `windows_browser`。
2. 仅为活动目标写入 `revoked_at`；已撤销目标按幂等成功处理。
3. 提交事务后调用 `hub.RevokeDevices([]string{id})`。
4. 发布不含敏感凭据的 `device.revoked` 事件。

数据库提交前不能断开 WebSocket，避免事务失败后客户端却被错误踢出。

### 6.6 配对服务改造

`pairing.Session` 增加 `DeviceName`。创建会话时把规范化名称写入 `requested_device_name`。

消费已批准会话时，在同一事务中：

1. 再次验证会话状态、有效期和 browser token 哈希。
2. 查询当前活动浏览器数量。
3. 若数量已达到 `MAX_BROWSER_DEVICES`，把会话标记为 `rejected`，返回 `BROWSER_LIMIT_REACHED`。
4. 插入新的 `windows_browser`，把 `display_name` 复制自配对会话。
5. 把会话标记为 `consumed` 并提交。

数量检查与插入必须位于同一事务。当前 SQLite 单写连接可以序列化竞争，但测试仍需覆盖两个并发消费只允许剩余容量数量的请求成功。

`replacement_allowed` 数据列暂时保留，避免重建历史表；新逻辑不再读取它。旧的 `REPLACEMENT_REQUIRED` 错误码保留一个版本但不再由正常路径产生。

### 6.7 HTTP API

#### `POST /api/v1/pairing/sessions`

请求体改为可选 JSON：

```json
{
  "device_name": "Chrome · Windows"
}
```

- 空请求体兼容旧 Web，使用“浏览器设备”。
- 响应在现有字段上增加 `device_name`。
- QR v2 payload 可选增加 `device_name`；旧 Android 忽略未知字段。

#### `GET /api/v1/devices/browsers`

- 认证：仅 Android Master Bearer Token。
- 响应：

```json
{
  "devices": [
    {
      "id": "...",
      "display_name": "Chrome · Windows",
      "created_at": "2026-08-30T10:00:00Z",
      "last_seen_at": "2026-08-30T11:20:00Z"
    }
  ],
  "active_count": 2,
  "maximum_count": 10
}
```

#### `PATCH /api/v1/devices/browsers/{id}`

- 认证：仅 Android Master。
- 请求：`{"display_name":"书房电脑"}`。
- 成功返回更新后的设备。
- 目标不存在或不是浏览器返回 `DEVICE_NOT_FOUND`。

#### `DELETE /api/v1/devices/browsers/{id}`

- 认证：仅 Android Master。
- 成功或已撤销返回 `204 No Content`。
- 从未存在或不是浏览器返回 `DEVICE_NOT_FOUND`。
- 不接受批量 ID，避免误撤销所有设备。

#### `PATCH /api/v1/devices/self`

- 认证：浏览器安全 Cookie。
- 只修改 Cookie 对应设备的 `display_name`。
- 返回更新后的当前设备。

#### `GET /api/v1/auth/session`

现有响应增加：

```json
{
  "authenticated": true,
  "device_id": "...",
  "device_type": "windows_browser",
  "display_name": "Chrome · Windows"
}
```

### 6.8 HTTP 状态和错误码

| 场景 | HTTP | 错误码 |
| --- | ---: | --- |
| 名称非法 | 400 | `INVALID_DEVICE_NAME` |
| 达到浏览器上限 | 409 | `BROWSER_LIMIT_REACHED` |
| 设备不存在或类型错误 | 404 | `DEVICE_NOT_FOUND` |
| 浏览器试图管理其他设备 | 403 | `MASTER_REQUIRED` |
| 目标凭据已撤销 | 401 | `DEVICE_REVOKED` |
| 数据库失败 | 500 | `INTERNAL_ERROR` |

错误响应不得包含 Token、Token 哈希、Cookie、完整 User-Agent 或数据库细节。

### 6.9 实时事件

新增事件：

```json
{"type":"device.created","data":{"id":"...","display_name":"Chrome · Windows"}}
{"type":"device.updated","data":{"id":"...","display_name":"书房电脑"}}
{"type":"device.revoked","data":{"id":"..."}}
```

- Android 收到事件后刷新设备列表，不直接把事件数据当作完整列表。
- 目标浏览器先收到或直接经历 WebSocket 关闭，随后认证检查进入“授权已撤销”页面。
- Web 继续兼容旧 `device.replaced` 事件，并使用同一个失效页面。
- 事件不携带 Token、IP、Cookie 或历史认证信息。

### 6.10 时间线来源名称

消息查询在现有设备类型之外增加 `source_device_name`：

```json
{
  "source_device_id": "...",
  "source_device_type": "windows_browser",
  "source_device_name": "书房电脑"
}
```

查询通过 `messages.source_device_id = devices.id` 获取当前显示名称。重命名后历史消息标签随之更新，这是预期行为；不为每条消息复制名称快照。

客户端兼容规则：字段缺失时 Android 显示“浏览器”，Web 显示“Windows”。

### 6.11 Android 数据与状态设计

新增：

- `data/BrowserDeviceRepository.kt`
- `ui/BrowserDevicesViewModel.kt`
- `ui/BrowserDevicesSheet.kt`

ViewModel 使用单一 `StateFlow<BrowserDevicesUiState>`：

```kotlin
data class BrowserDevicesUiState(
    val devices: List<BrowserDevice> = emptyList(),
    val maximumCount: Int = 10,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val actionDeviceId: String? = null,
    val errorMessage: String? = null,
)
```

规则：

- 网络和领域状态放在 ViewModel；重命名输入框、确认弹窗开关等短生命周期 UI 状态保留在 Composable。
- Screen 层收集 `StateFlow`，内容 Composable 只接收状态和回调，不向子组件传 ViewModel。
- 列表使用 `LazyColumn`，以设备 ID 作为稳定 key。
- 操作期间只禁用目标设备，不阻塞查看其他设备。
- 撤销采用服务端成功后移除；失败时保留设备并展示错误。
- 设置页只增加“已授权浏览器 N/10”入口，完整列表放在独立 Bottom Sheet，避免继续扩大已有设置表单。

设备项显示：

- 第一行：设备名称。
- 第二行：`最近使用：刚刚/今天 HH:mm/yyyy-MM-dd`；从未认证时显示“刚刚授权”。
- 第三行：授权日期。
- 操作：重命名、撤销。

可访问性要求：

- 图标按钮有包含设备名称的 `contentDescription`。
- 点击目标至少 48dp。
- 撤销确认框焦点顺序为标题、说明、取消、撤销。
- 加载完成、撤销成功和错误使用合适的语义或 Snackbar 提示。
- 增加浅色、深色、大字体和窄屏 Preview/截图测试。

### 6.12 Web 设计

#### 未配对页面

- 创建配对会话时发送默认设备名称。
- 二维码卡片显示“设备名称”，提供修改入口。
- 修改名称会取消当前展示状态并创建新会话，确保二维码与服务端保存名称一致。
- 名称保存在当前 Origin 的 `localStorage`，不能跨服务器自动复用敏感状态；这里只保存显示名称，不保存凭据。

#### 已配对页面

- 顶栏设置菜单增加“本设备名称”。
- 修改时调用 `PATCH /api/v1/devices/self`。
- `device.revoked` 或认证返回 `DEVICE_REVOKED` 时清理仅内存状态并进入授权失效页；HttpOnly Cookie 由服务端通过过期 `Set-Cookie` 清除。
- 时间线来源使用 `source_device_name`，当前设备仍可使用“我 · <设备名>”样式。

### 6.13 Cookie 清理

认证检测到已撤销浏览器时，响应除 `401 DEVICE_REVOKED` 外还必须写入同名、同 Path 的过期 Cookie。Web JavaScript 无法直接删除 HttpOnly Cookie，不能只依赖前端清理。

### 6.14 兼容性

- 旧浏览器 Cookie 对应设备行不变，升级后继续有效。
- 旧 Web 创建空请求体时获得默认名称。
- 旧 Android 首次批准新浏览器即可成功，不再收到替换确认。
- 新 Android 连接旧服务端时，设备管理入口根据 `404` 或能力信息隐藏，并保留原配对流程。
- 推荐在 `GET /api/v1/instance/info` 增加 `capabilities: ["multi_browser_v1"]`，客户端按能力展示入口，避免把普通 404 当作服务器故障。

## 7. 功能二：备份与恢复工具

### 7.1 工具入口

新增：

```text
docker/backup.sh
docker/restore.sh
```

两者只面向 Linux/1Panel 宿主机，固定使用：

- Compose 项目名：`transdot`
- 服务名：`transfer-assistant`
- 数据卷：`transfer-assistant-data`
- 默认项目目录：`/opt/transdot`

可以通过现有 `TRANSDOT_PROJECT_DIR` 和 `TRANSDOT_HEALTH_URL` 覆盖项目目录与健康检查地址，但不能通过参数覆盖生产卷名。固定卷名是破坏性操作的安全边界。

### 7.2 备份归档格式

文件名：

```text
transdot-backup-20260830T112233Z-7F3A91C2.tar.gz
transdot-backup-20260830T112233Z-7F3A91C2.tar.gz.sha256
```

归档内部结构：

```text
manifest.json
data/
  database/transfer.db
  database/transfer.db-wal    # 若存在则保留
  database/transfer.db-shm    # 若存在则保留
  files/
  thumbs/
```

不包含 `data/tmp/`。停止服务后仍残留的 `.part` 或临时文件也不进入备份。

`manifest.json`：

```json
{
  "format_version": 1,
  "created_at": "2026-08-30T11:22:33Z",
  "app_version": "1.2.0",
  "git_commit": "...",
  "instance_id": "...",
  "instance_fingerprint": "7F3A-91C2",
  "schema_version": 11,
  "data_layout": "transdot-data-v1",
  "includes_environment": false
}
```

清单不包含 `.env`、Master Token 明文、浏览器 Cookie 或任何生成型密钥。数据库内仍含不可逆 Token 哈希和私人消息，因此整个归档按敏感数据处理。

### 7.3 维护检查程序

新增只读维护命令，可作为独立二进制 `/app/transdot-maintenance` 打入运行镜像：

```text
transdot-maintenance inspect --data-dir /app/data --json
transdot-maintenance verify --data-dir /app/data --max-schema 11 --json
```

实现放在 `server/internal/maintenance`，不得复用会自动执行迁移的 `database.Open`。

`inspect` 输出生成 manifest 所需的实例和 schema 信息。`verify` 至少检查：

1. `database/transfer.db` 存在且是普通文件。
2. SQLite 可以只读打开。
3. `PRAGMA integrity_check` 返回 `ok`。
4. `PRAGMA foreign_key_check` 无结果。
5. `schema_migrations` 最大版本不超过当前程序支持版本。
6. `app_state` 和 `server_instance` 各有且只有一条合法记录。
7. 数据目录中不存在符号链接。
8. 数据库中未删除且未过期的文件记录所指向文件存在，大小与元数据相符。
9. 缩略图缺失只产生警告，不阻止恢复；客户端可显示无预览状态。

维护程序只读，不创建目录、不执行迁移、不清理文件。

### 7.4 备份命令

标准用法：

```bash
cd /opt/transdot
sh docker/backup.sh /opt/transdot-backups
```

流程：

1. 校验项目目录、Compose 文件、目标输出目录和固定卷。
2. 创建目标目录，要求它不是数据卷挂载路径或项目源码目录的子目录。
3. 获取互斥锁，防止 backup、restore、reset、update 同时运行。
4. 确认服务当前状态并记录，以便结束时恢复原状态。
5. `docker compose -p transdot stop -t 30 transfer-assistant`。
6. 使用当前运行镜像挂载数据卷为只读，运行 `transdot-maintenance verify`。
7. 生成 manifest 到权限为 `0700` 的 `mktemp -d` 临时目录。
8. 使用固定镜像、`--user 0` 和只读源卷创建 `.tar.gz.tmp`。
9. 对临时归档计算 SHA-256，完成后原子改名为最终文件。
10. 将备份文件和校验文件权限设置为 `0600`。
11. 按备份前状态重新启动服务，并等待健康检查。
12. 清理临时目录和互斥锁。

任何失败都必须：

- 保留完整错误码和简洁错误说明。
- 删除未完成的 `.tmp` 归档。
- 尝试恢复备份前的服务状态。
- 不删除已有备份。

### 7.5 备份加密

基础版本生成权限为 `0600` 的本地压缩包，并在完成信息中明确提示其包含私人数据。

可选支持环境变量：

```env
TRANSDOT_BACKUP_AGE_RECIPIENT=age1...
```

配置后若宿主机存在 `age`，输出 `.tar.gz.age` 并只保留加密文件和密文校验。脚本不接受命令行明文密码，也不在日志中输出收件人私钥。未安装 `age` 时必须失败，不能悄悄降级为明文。

加密支持可以在基础备份恢复稳定后作为同版本的后续提交完成，但归档格式版本保持为 1。

### 7.6 恢复命令

标准用法：

```bash
cd /opt/transdot
sh docker/restore.sh /opt/transdot-backups/transdot-backup-20260830T112233Z-7F3A91C2.tar.gz RESTORE
```

`RESTORE` 必须是独立的最后一个参数。脚本不得提供从 Web 传入的任意路径入口。

恢复流程：

1. 校验项目目录、固定生产卷、归档普通文件属性及相邻 `.sha256`。
2. 获取与 backup/update/reset 共享的互斥锁。
3. 列出归档条目并拒绝：绝对路径、`..` 路径穿越、符号链接、设备文件和不属于 `manifest.json`/`data/` 的顶层内容。
4. 只解出 `manifest.json`，检查 `format_version`、`data_layout` 和 `schema_version`。
5. 若备份 schema 高于当前程序支持版本，返回 `BACKUP_SCHEMA_TOO_NEW`，生产服务不停止。
6. 创建唯一暂存卷 `transdot-restore-stage-<随机值>`。
7. 把 `data/` 解压到暂存卷，修正所有权为运行时 UID/GID `10001:10001`。
8. 在暂存卷上运行 `transdot-maintenance verify`，并核对实例 ID、指纹和 schema 与 manifest 一致。
9. 调用备份内部流程生成 `pre-restore-<时间>.tar.gz`；该安全备份不可跳过。
10. 停止生产服务并记录其原始状态。
11. 再次确认固定生产卷解析结果严格等于 `transfer-assistant-data`。
12. 清空生产卷内容，把已验证暂存卷内容复制到生产卷。
13. 启动当前版本应用；启动过程负责按顺序执行必要的旧版本数据库迁移。
14. 等待健康检查，并调用只读维护检查确认迁移后数据库完整。
15. 成功后删除暂存卷，打印恢复实例指纹和安全备份路径。

若第 12 步以后失败：

1. 停止失败实例。
2. 清空生产卷。
3. 从本次 `pre-restore` 安全备份恢复原数据。
4. 启动并健康检查原实例。
5. 明确输出“目标恢复失败，已回滚”或“目标恢复失败且自动回滚失败”。

自动回滚失败时保留安全备份和暂存卷，不继续进行清理，便于人工救援。

### 7.7 服务状态语义

- 备份前服务正在运行：备份后必须重新启动并健康检查。
- 备份前服务已停止：备份后保持停止，不把停机环境意外启动。
- 恢复成功：服务默认恢复到执行恢复前的运行状态；若原本运行则启动并检查，原本停止则完成验证后保持停止。
- `update.sh`、`reset.sh`、`backup.sh`、`restore.sh` 使用同一个宿主机锁目录，例如 `/var/lock/transdot-maintenance.lock`。

### 7.8 数据一致性与恢复语义

- 恢复的是备份时间点的完整服务器实例，而不是把消息合并进当前实例。
- 恢复后 `instance_id` 和指纹回到备份值。
- 备份时仍有效且浏览器仍持有对应 Cookie Token 的设备可以继续认证。
- 备份之后新配对或重绑定的设备在恢复后不存在，其凭据失效。
- 恢复之后发生的消息、文件和撤销操作会被覆盖，但已保存在 `pre-restore` 安全备份中。
- `.env` 不恢复；当前 `PUBLIC_URL`、端口和容量限制继续生效。
- 当前限制小于备份内已有数据时，不删除数据；仅影响后续上传或新增浏览器。

### 7.9 安全约束

- backup/restore/reset 不提供 HTTP 路由。
- 所有 shell 变量必须加引号，禁止通过 `eval` 拼接命令。
- 删除或清空前必须解析并比较固定卷名，不能接受通配符。
- 临时目录使用 `mktemp -d`，并通过 `trap` 清理。
- 归档内容必须防路径穿越和符号链接逃逸。
- 日志不能打印 `.env`、Token、Cookie、数据库内容或文件名清单。
- 默认输出目录不可位于 Git 工作区，避免误提交私人备份。
- 归档权限为 `0600`，目录建议为 `0700`。
- 备份文件包含私人数据；README 必须明确建议复制到加密磁盘或启用 `age`。

### 7.10 定时备份

本版本不内置常驻调度器，只提供可用于 cron/systemd timer 的幂等脚本。例如：

```cron
30 3 * * * cd /opt/transdot && sh docker/backup.sh /opt/transdot-backups >>/var/log/transdot-backup.log 2>&1
```

自动删除旧备份不是默认行为。可增加显式参数 `--keep 7`，只允许删除由脚本生成、文件名符合固定模式且位于已解析备份目录中的旧备份；实现前必须补充路径边界测试。没有传入时永不自动删除。

## 8. 代码改动清单

### 8.1 服务端

- `server/migrations/011_multi_browser_devices.sql`
- `server/internal/devices/service.go`
- `server/internal/devices/service_test.go`
- `server/internal/pairing/service.go`
- `server/internal/pairing/service_test.go`
- `server/internal/deviceauth/service.go`
- `server/internal/httpserver/devices.go`
- `server/internal/httpserver/auth.go`
- `server/internal/httpserver/pairing.go`
- `server/internal/httpserver/server.go`
- `server/internal/messages/service.go`
- `server/internal/config/config.go`
- `server/internal/maintenance/*`
- `server/cmd/transdot-maintenance/main.go`
- `server/cmd/transfer-assistant/main.go`

### 8.2 Web

- `web/src/App.tsx`：设备名称、认证模型、来源标签、撤销状态。
- 建议拆分 `web/src/deviceManagement.ts` 和对应测试，避免继续扩大单文件状态机。
- `web/src/styles.css`：设备名称编辑、失效页和响应式布局。

### 8.3 Android

- `android/.../data/BrowserDeviceRepository.kt`
- `android/.../ui/BrowserDevicesViewModel.kt`
- `android/.../ui/BrowserDevicesSheet.kt`
- `TimelineAuxiliarySurfaces.kt`：增加设备管理入口，不直接承载网络逻辑。
- `PairingViewModel.kt` / `PairingFlow.kt`：新增“添加浏览器”语义和设备名称显示。
- 时间线消息模型与组件：显示 `source_device_name`。

### 8.4 Docker 与文档

- `Dockerfile`：构建并复制 `transdot-maintenance`。
- `docker/backup.sh`
- `docker/restore.sh`
- `docker/update.sh`、`docker/reset.sh`：接入共享维护锁。
- `.env.example`：增加 `MAX_BROWSER_DEVICES` 和可选备份说明。
- `README.md`：增加多浏览器、备份、验证、恢复和灾难回滚步骤。

## 9. 实现顺序

### 阶段 A：多浏览器服务端

1. 先写迁移和迁移兼容测试。
2. 新增设备领域服务及测试。
3. 修改配对消费事务和容量限制测试。
4. 新增设备 API、Cookie 清理和实时撤销测试。
5. 增加消息来源名称和兼容序列化。

### 阶段 B：客户端设备管理

1. Web 创建配对时提交设备名称并允许重命名自己。
2. Android 新增 Repository、ViewModel 和设备列表 Sheet。
3. 修改配对文案，从“替换浏览器”改为“添加浏览器”。
4. 两端显示时间线来源名称。
5. 完成单元、UI 和截图测试。

### 阶段 C：备份基础设施

1. 实现只读 `transdot-maintenance inspect/verify`。
2. Docker 镜像加入维护二进制。
3. 实现停机备份、manifest、校验和与服务状态恢复。
4. 使用临时卷执行备份还原演练。

### 阶段 D：安全恢复

1. 实现归档结构预检和 schema 兼容判断。
2. 实现暂存卷解压与只读校验。
3. 实现强制 pre-restore 备份、生产卷覆盖和自动回滚。
4. 接入共享维护锁并验证与 update/reset 互斥。
5. 最后补充可选 age 加密和 `--keep`。

## 10. 测试设计

### 10.1 Go 单元与集成测试

- 迁移后 Android Master 仍保持唯一，浏览器可存在多个。
- 旧活动浏览器升级后仍能认证。
- 设备名称的空白、长度、控制字符和 Unicode 边界。
- Android Master 可以列出、重命名、撤销浏览器。
- 浏览器只能重命名自己，不能访问管理接口。
- 撤销已撤销设备幂等，错误类型目标不会被修改。
- 撤销事务失败时不调用 Hub 断开。
- 达到上限时新增配对失败且不撤销已有设备。
- 并发消费不会突破最大数量。
- `GET /auth/session` 返回名称，撤销后清除 Cookie。
- 消息返回来源名称，历史撤销设备仍可 JOIN。
- 维护检查能识别完整数据库、损坏数据库、新版本 schema、缺失原文件和缺失缩略图。

### 10.2 Web 测试

- 默认设备名生成与规范化。
- 配对创建请求包含设备名称，旧响应缺字段时兼容。
- 修改配对名称会创建新会话并废弃旧倒计时状态。
- 浏览器可以修改自身名称。
- 时间线显示来源设备名称。
- `DEVICE_REVOKED` 和 `device.revoked` 进入授权失效页。
- `BROWSER_LIMIT_REACHED` 显示先去 Android 清理设备的操作指引。

### 10.3 Android 测试

- Repository 正确解析设备列表、空 `last_seen_at` 和错误码。
- ViewModel 首次加载、刷新、重命名、撤销成功和失败状态。
- 撤销失败不从列表乐观删除。
- 设备列表稳定 key、排序和时间文案纯函数测试。
- Pairing 不再触发旧替换确认。
- Compose 截图覆盖空列表、10/10 满额、加载、错误、浅色、深色和大字体。
- 可访问性测试验证操作按钮说明和最小点击区域。

### 10.4 Shell/Docker 测试

使用专用测试卷，绝不操作 `transfer-assistant-data`：

- 运行中备份：服务被停止、归档成功、服务恢复健康。
- 停止状态备份：结束后仍保持停止。
- 备份失败：临时文件删除、服务状态恢复。
- 归档包含 manifest/database/files/thumbs，不包含 tmp 和 `.env`。
- SHA-256 不匹配时恢复在停止生产服务前失败。
- 路径穿越、符号链接和设备文件归档被拒绝。
- 较新 schema 被拒绝且生产卷未修改。
- 暂存卷验证失败时生产卷未修改。
- 成功恢复后实例 ID、消息数量、文件哈希和设备授权与备份一致。
- 恢复后当前程序可以自动迁移旧 schema。
- 目标启动失败时自动恢复 pre-restore 数据。
- update/reset/backup/restore 同时执行时只有一个获得维护锁。

## 11. 验收标准

### 11.1 多浏览器

1. 现有已配对浏览器在升级后无需重新配对。
2. 同一实例至少可以同时授权 3 个浏览器并正常收发消息。
3. 新配对不会使任何旧浏览器退出。
4. Android 可以看到每个浏览器的名称、授权时间和最近使用时间。
5. Android 撤销一个浏览器后，只有该浏览器立即失效，其他浏览器不受影响。
6. 浏览器可以重命名自己，但不能查看或修改其他设备。
7. 达到 10 个活动浏览器时，第 11 个明确失败且不会自动替换设备。
8. 时间线能区分不同浏览器来源。

### 11.2 备份恢复

1. 一条命令可以生成带 manifest 和 SHA-256 的完整数据备份。
2. 备份期间服务短暂停止，结束后恢复原运行状态。
3. 备份不包含 `.env` 和临时上传目录。
4. 损坏、被篡改、路径不安全或版本过新的归档不会修改生产卷。
5. 恢复覆盖前始终创建 pre-restore 安全备份。
6. 恢复后实例指纹、消息、文件和设备授权回到备份时间点。
7. 恢复失败时能够自动回滚并明确报告结果。
8. 普通更新仍不删除卷，Reset 与 Restore 的用途和危险性在文档中清楚区分。

## 12. 发布与回滚

### 12.1 发布顺序

1. 先发布服务端迁移和向后兼容 API。
2. 再发布 Web；旧 Android 此时仍可配对和传输。
3. 发布 Android 设备管理界面。
4. 最后开放 backup/restore 工具，并在测试卷完成至少一次完整灾难恢复演练。

### 12.2 代码回滚

迁移 011 删除了“单活动浏览器”唯一索引，旧代码可能在新增配对时撤销全部活动浏览器。因此数据库升级并产生多个活动浏览器后，不允许直接回滚到 `v1.1.0` 运行。

如必须回滚：

1. 先使用新版本 Android 手动只保留一个活动浏览器。
2. 创建完整备份。
3. 使用明确的兼容迁移恢复旧唯一索引。
4. 再运行旧程序。

备份格式 v1 必须保持可读；未来格式升级时，维护程序至少保留读取前一个格式版本的能力。

## 13. 完成定义

只有满足以下条件才视为本设计完成：

- 所有新增数据库迁移、API、领域服务和客户端状态均有自动化测试。
- Go `go test ./...` 通过。
- Web `npm test`、`npm run build` 通过。
- Android `testDebugUnitTest`、`assembleDebug` 和相关截图测试通过。
- 在隔离测试卷完成“创建数据 → 备份 → 修改数据 → 恢复 → 校验哈希和设备授权”的完整演练。
- README 的备份恢复命令经过逐条复制执行验证。
- 未在 Git、日志、manifest 或测试夹具中泄露真实 `.env`、Token、Cookie 或私人备份内容。
