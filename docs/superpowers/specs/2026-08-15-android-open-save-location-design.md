# Android 保存位置打开方式兼容性修复设计

## 背景与根因

APP 当前使用不带 MIME 类型的 `ACTION_VIEW` 打开 Storage Access Framework 目录 URI。在测试设备上，该 Intent 只匹配到 Google 和 UU；系统 DocumentsUI、ColorOS 文件管理器和 CX 文件管理器均未进入候选列表。因为系统仍能启动选择器，现有“启动失败后改用 `ACTION_OPEN_DOCUMENT_TREE`”的异常兜底不会执行。

## 目标

- 点击“查看保存位置”时，不再展示与目录浏览无关的应用。
- 始终提供标准系统目录入口，并使用 `EXTRA_INITIAL_URI` 尽量定位到实际保存目录。
- 当设备安装且支持 CX 文件管理器时，额外提供“CX 文件管理器”入口。
- 不依赖 ColorOS 等厂商私有组件名；CX 入口仅在系统确认其可处理目录 Intent 时出现。
- CX 不可用或启动失败时，仍可通过系统目录入口访问保存位置。

## 交互设计

点击“查看保存位置”后，APP 显示自己的轻量选择对话框：

- **系统文件管理器**：发送 `ACTION_OPEN_DOCUMENT_TREE`，附带保存目录的 `EXTRA_INITIAL_URI`。由 Android 在 DocumentsUI 和厂商目录选择器之间解析。
- **CX 文件管理器**：仅当包 `com.cxinventor.file.explorer` 已安装，且能处理带目录 MIME 的 `ACTION_VIEW` 时显示。Intent 显式限定到 CX，避免百度网盘、邮箱等无关应用进入候选。
- **取消**：关闭对话框，不改变下载结果和默认保存位置。

系统目录入口本质上是 SAF 目录界面：它会尽量定位到保存目录，但不会修改默认保存位置，也不读取新的选择结果。不同厂商对精确定位的支持可能不同。

## 代码边界

- `DownloadDestinationManager` 负责构造标准目录 Intent、探测 CX 支持并构造显式 CX Intent。
- `TimelineScreen` 只管理选择对话框的显示和点击动作，不硬编码包解析逻辑。
- 保留“打开文件”行为不变。
- 移除当前会误匹配 Google/UU 的无类型目录 `ACTION_VIEW` 路径。

## 错误处理

- 系统目录入口无法启动：提示“当前系统不支持打开保存位置”。
- CX 在探测后被卸载或启动失败：提示“CX 文件管理器无法打开该目录”，系统入口仍可继续使用。
- 没有持久化默认目录时：使用本次下载文件 URI 推导的初始位置；若无法推导，则提示无法定位。

## 测试与验收

- 单元测试验证系统目录 Intent 的 action、flags 和 `EXTRA_INITIAL_URI`。
- 单元测试验证 CX 目录 Intent 使用 `resource/folder` 且显式限定 CX 包。
- 真机验证点击后只出现 APP 自己的“系统文件管理器 / CX 文件管理器”选择，不再出现 Google 和 UU。
- 分别验证系统入口和 CX 入口可启动；完成后返回 APP，下载和连接状态不受影响。

