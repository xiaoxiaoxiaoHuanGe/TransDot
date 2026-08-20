# TransDot 局域网快传详细设计

## 1. 目标

在现有 Android Master 与已授权 Web 浏览器之间增加独立的“局域网快传”模式。当双方位于可直接互通的局域网并同时在线时，文件内容通过 WebRTC DataChannel 点对点传输，不进入云服务器、SQLite、文件池或时间线。

云服务器只承担现有身份认证和短期 WebRTC 信令转发。无法建立局域网直连或传输失败时，系统只显示失败并允许重试，不得自动调用现有云端上传接口。

## 2. 首版范围

- 支持 Android 与最新版 Chrome/Edge 双向传输。
- 使用独立的局域网快传页面，不改变现有云端时间线行为。
- 双方必须同时在线并进入局域网快传模式。
- 一次最多选择 20 个文件，按队列逐个传输。
- 单文件最大 2 GiB；空文件允许传输。
- Web 首次选择接收目录并授权，权限有效期间自动接收。
- Android 自动写入已配置的下载目录。
- 不保存云端消息、文件记录或传输历史。
- 不支持文件夹、断点续传、后台离线投递、STUN 或 TURN。
- 直连失败时不自动也不询问是否切换云端；用户可离开快传页面后自行使用现有云端传输。

## 3. 不在首版范围内

- Safari、Firefox 或非 Chromium WebView。
- 跨 NAT、公网点对点连接或 TURN 中继。
- 设备间长期发现、多人或多设备房间。
- 文件夹目录结构、断点恢复、差量同步和传输历史。
- 云端保存局域网快传的文件名、哈希或内容。

## 4. 总体架构

```text
Android APP <======= WebRTC DataChannel =======> Chrome / Edge
     |                                              |
     +---------- authenticated WSS signaling -------+
                            |
                      TransDot server
```

### 4.1 服务端

现有 `/ws` 从服务端单向事件通道扩展为受限的双向信令通道。新增内存信令代理负责：

- 将连接与已认证的设备 ID、设备类型和服务器实例 ID 绑定。
- 维护 Android Master 与当前授权 Windows Browser 的快传就绪状态。
- 定向转发 SDP、ICE Candidate、取消和离开消息。
- 管理短期协商会话、超时、消息大小和频率限制。

服务端不得接收 DataChannel 内容，不持久化信令会话，也不新增数据库迁移。

### 4.2 Web

Web 使用浏览器原生 `RTCPeerConnection`、`RTCDataChannel`、File API、File System Access API 和 IndexedDB。Web 固定作为 WebRTC Offer 发起方，以消除双方同时发起造成的协商冲突。

### 4.3 Android

Android 使用固定版本、可复现构建的成熟 libwebrtc Android 发行包。应用负责 PeerConnection 生命周期、DataChannel 协议、ContentResolver 流式读写和传输期间的前台服务通知，不自行实现 WebRTC 传输栈。

## 5. 局域网判断

系统不比较公网 IP、私网网段或 Wi-Fi 名称，而以实际连通性作为判断依据：

1. 双方进入快传页面并通过 WSS 报告就绪。
2. Web 创建 `iceServers: []` 的 PeerConnection 和可靠有序 DataChannel。
3. 双方只交换 Host ICE Candidate，不配置 STUN/TURN。
4. 从 Web 发出 Offer 开始计时，DataChannel 在 8 秒内打开即判定局域网直连成功。
5. 超时、ICE failed 或 DataChannel 关闭均判定直连不可用。

该方式自然覆盖访客网络隔离、客户端隔离、VPN、多网卡和同公网不同内网等情况。客户端不得仅凭地址形式显示“已直连”。

## 6. 信令协议

### 6.1 客户端到服务端

- `lan.ready`：设备进入快传模式。
- `lan.offer`：Web 发送 SDP Offer。
- `lan.answer`：Android 发送 SDP Answer。
- `lan.ice`：任一端发送 Host ICE Candidate。
- `lan.connected`：DataChannel 已打开，协商会话进入已连接状态。
- `lan.cancel`：取消当前协商或传输。
- `lan.leave`：离开快传模式。

### 6.2 服务端到客户端

- `lan.peer_online`：对应设备已就绪，并携带服务端创建的 `session_id`。
- `lan.peer_offline`：对应设备离开或断开。
- `lan.offer`、`lan.answer`、`lan.ice`：定向转发的协商消息。
- `lan.cancelled`：对端取消。
- `lan.error`：协议、鉴权、限流或会话错误。

### 6.3 信令约束

- 所有消息使用包含 `type`、`session_id`、`timestamp` 和类型化 `data` 的 JSON Envelope。
- Android 和 Web 都就绪后，服务端创建随机 UUID 作为 `session_id`，绑定实例 ID 和这一对设备，再向双方发送 `lan.peer_online`；客户端不得自行选择会话 ID。
- 未连接的协商会话创建 2 分钟后失效。收到双方的 `lan.connected` 后，会话保持到任一端 `lan.leave`、WebSocket 断开或最长 12 小时，足以覆盖 2 GiB 长传输。
- 单条消息最大 64 KiB，只接受白名单字段和消息类型。
- 服务端解析 ICE Candidate 并拒绝 `typ` 不是 `host` 的候选；客户端同样忽略非 Host Candidate。
- 客户端不得提供任意目标设备 ID；服务端根据已认证设备类型选择唯一对端。
- 每个实例同时只允许一组首版快传协商，重复发起返回结构化 `LAN_SESSION_BUSY`。
- SDP、ICE 和文件相关数据不得写入应用日志。

## 7. DataChannel 文件协议

建立一个可靠、有序的 DataChannel。控制帧为 UTF-8 JSON 字符串，数据帧为二进制块。首版同一时刻只传一个文件。

### 7.1 控制帧

- `file_offer`：`file_id`、清理后的文件名、MIME、字节数。
- `file_accept`：接收端已创建输出并可以接收。
- `file_reject`：包含稳定错误码，不包含本地绝对路径。
- `file_complete`：发送端声明字节发送完成并附带 SHA-256。
- `file_verified`：接收端大小和哈希验证成功。
- `file_failed`：接收端验证或写盘失败。
- `queue_complete`：当前选择的队列处理完毕。
- `transfer_cancel`：用户或运行时取消当前文件。

### 7.2 数据和流量控制

- 文件按 64 KiB 块顺序发送；空文件不发送二进制块。
- 双端边传输边计算 SHA-256，不将完整文件载入内存。
- Web DataChannel `bufferedAmount` 达到 4 MiB 时暂停读取，降至 1 MiB 后恢复。
- Android 根据 libwebrtc 发送缓冲变化实施等价的高低水位控制。
- 发送端只有收到 `file_verified` 后才把当前项标记成功并开始下一项。
- 当前文件失败不自动断开健康的 DataChannel；用户可重试该项或继续队列。

### 7.3 接收文件处理

- 接收前再次检查文件数、单文件 2 GiB 上限、名称和目标目录权限。
- 文件名移除路径分隔符、控制字符、尾随点/空格并限制到目标文件系统可接受长度。
- 同名文件使用 `name (1).ext`、`name (2).ext` 递增命名，不覆盖已有文件。
- 写入期间保留当前输出句柄；取消、断线、大小错误或哈希错误时关闭并删除不完整文件。
- 验证成功后才在 UI 中显示完成。

## 8. Web 端设计

### 8.1 目录授权

- 首次进入快传页面时通过用户点击调用 `showDirectoryPicker()`。
- `FileSystemDirectoryHandle` 保存到 IndexedDB，不保存目录路径文本到服务器。
- 每次进入先调用 `queryPermission()`；权限不是 `granted` 时，必须由用户手势重新请求或选择目录。
- 授权有效且快传页面在线时，收到合法 `file_offer` 后自动创建文件并回复 `file_accept`。

### 8.2 发送和接收

- 发送使用 `<input type="file" multiple>` 和 `File.stream()`。
- 接收使用 `FileSystemWritableFileStream` 边收边写。
- 增量 SHA-256 使用小型、维护活跃且支持流式输入的固定版本库，避免 Web Crypto 整文件缓冲。
- 页面刷新、关闭或退出快传模式时发送 `lan.leave`，关闭文件句柄、DataChannel 和 PeerConnection。

### 8.3 界面状态

- 未授权接收目录。
- 等待 Android 上线。
- 正在建立局域网连接，显示 8 秒倒计时。
- “局域网直连”已建立。
- 队列传输中：当前文件、方向、进度、实时速度、已完成数量。
- 当前文件失败、设备离线或直连不可用。

## 9. Android 端设计

- 新增独立局域网快传页面和状态持有层，避免把 PeerConnection 生命周期塞入现有时间线 ViewModel。
- 多选文件使用 Storage Access Framework，并通过 ContentResolver 获取名称、MIME、长度和 InputStream。
- 接收沿用应用当前配置的下载目录；无法写入时以 `DESTINATION_UNAVAILABLE` 拒绝文件。
- 传输开始后启动仅在传输期间存在的前台服务和进度通知，完成或取消后立即停止。
- 大小统一使用 `Long`；读取、哈希和写入都在 IO 调度器执行。
- 离开页面且没有活动传输时发送 `lan.leave` 并释放 PeerConnection；活动传输期间允许锁屏但不承诺进程被用户强制终止后恢复。

## 10. 错误模型

稳定错误码至少包括：

- `LAN_PEER_OFFLINE`
- `LAN_CONNECT_TIMEOUT`
- `LAN_SESSION_BUSY`
- `LAN_PROTOCOL_ERROR`
- `FILE_TOO_LARGE`
- `TOO_MANY_FILES`
- `DESTINATION_PERMISSION_REQUIRED`
- `DESTINATION_UNAVAILABLE`
- `INSUFFICIENT_STORAGE`
- `FILE_WRITE_FAILED`
- `FILE_HASH_MISMATCH`
- `TRANSFER_CANCELLED`

连接在 8 秒内未建立、网络切换、ICE failed、对端离线或 DataChannel 关闭时，当前文件失败并清理不完整输出。任何错误路径都不得调用 `/api/v1/upload-batches` 或文件上传接口。

## 11. 安全与隐私

- 信令只接受现有 Bearer Token 或 HttpOnly Browser Cookie 验证过的设备。
- 会话绑定服务器实例 ID、Android Master 和当前 Windows Browser，拒绝跨实例或跨设备重放。
- WebRTC DataChannel 使用 DTLS 加密；服务端不掌握文件内容密钥。
- 文件名、MIME、大小和 SHA-256 只在 DataChannel 内传递，不经过云端信令。
- 服务端日志只记录匿名会话 ID、设备类型、状态迁移、持续时间和错误码。
- 限制信令消息大小、频率、会话数和状态转换，非法消息关闭对应快传会话但不吊销正常设备凭据。

## 12. 组件边界

### 12.1 服务端

- `lan signaling broker`：会话、就绪状态和定向路由。
- `websocket protocol adapter`：解析受限客户端 Envelope，并把服务端事件写回连接。
- `realtime hub`：保留现有时间线广播语义，增加明确的定向发送能力，不把信令伪装成全局事件。

### 12.2 Web

- `lan signaling client`：WSS Envelope 和重连。
- `lan peer`：PeerConnection、ICE 和 DataChannel 生命周期。
- `lan transfer protocol`：控制帧、分块、哈希和背压。
- `receive directory store`：IndexedDB 句柄和权限。
- `lan transfer view`：状态和用户命令，不直接实现网络协议。

### 12.3 Android

- `LanSignalingClient`：复用认证信息建立 WSS 信令。
- `LanPeerEngine`：封装 libwebrtc。
- `LanTransferProtocol`：平台无关的控制状态机和队列。
- `LanFileStore`：ContentResolver 读写、命名和清理。
- `LanTransferViewModel`：UI 状态与命令。
- `LanTransferService`：仅活动传输期间的前台服务。

## 13. 测试策略

### 13.1 自动测试

- 服务端：鉴权、角色路由、会话占用、TTL、离线清理、限流、消息白名单、跨实例拒绝。
- Web：协议状态机、64 KiB 分块、4/1 MiB 背压、增量哈希、队列、2 GiB 边界、权限恢复、同名命名和部分文件清理。
- Android：协议编解码、Long 大小、队列、哈希、ContentResolver 失败、同名命名、取消清理和前台服务生命周期。
- 共享协议测试向量：控制帧 JSON、空文件、中文名、哈希成功/失败和非法状态转换。
- 回归保护：局域网快传模块不得调用云端上传接口；现有 Go、Web、Android 测试保持通过。

### 13.2 集成和实机验收

- Android 到 Chrome、Chrome 到 Android。
- 单文件、20 文件队列、空文件、中文名、同名文件和无扩展名文件。
- 100 MiB、1 GiB、接近 2 GiB，以及超过 2 GiB 的明确拒绝。
- 传输中锁屏、切换 Wi-Fi、关闭页面、撤销目录权限和磁盘空间不足。
- 同一局域网成功直连；访客隔离或不同网络在 8 秒内失败且不上传云端。
- 传输前后服务器数据卷不因文件内容增长，服务端访问日志不存在文件上传请求。

## 14. 发布和运维

- 不新增数据库迁移，不需要 Reset。
- 不新增服务端监听端口；信令继续使用现有 HTTPS/WSS 入口。
- WebRTC 媒体连接使用客户端之间的临时 UDP/TCP 候选，不要求在云服务器安全组开放端口。
- 发布包含服务端、Web 和 Android 新版本，旧客户端继续使用现有云端功能。
- Android libwebrtc 与 Web 哈希库必须固定版本并记录许可证及来源。

## 15. 实施顺序

1. 固化 WSS 信令 Envelope、DataChannel 控制帧和跨平台测试向量。
2. 实现服务端内存信令代理、定向 Hub 和 WebSocket 客户端消息解析。
3. 实现 Web 信令客户端、目录授权、WebRTC Peer 和流式文件协议。
4. 实现 Android libwebrtc 封装、文件存储、协议状态机和前台服务。
5. 接入双方独立快传 UI、队列、进度、速度和错误状态。
6. 完成自动测试、双向实机测试、2 GiB 稳定性测试和隐私验收。
7. 更新 README、Android 文档和部署说明。

## 16. 完成标准

- 同一可互通局域网内，Android 和 Chrome/Edge 可双向逐文件传输最多 20 个文件。
- 单文件最大 2 GiB，传输过程不整体缓存文件，完成后大小和 SHA-256 一致。
- Web 首次授权目录后可自动接收，Android 可自动写入配置目录。
- 直连不可用或中断时不产生云端文件、消息或上传请求。
- 服务端仅处理受认证的短期信令，会话超时和断开清理有效。
- 现有云端时间线、配对、Bootstrap、上传和下载行为无回归。
