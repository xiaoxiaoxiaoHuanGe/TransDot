# Android

Kotlin、Jetpack Compose、Material 3 单 Activity 应用，使用 `compileSdk = 36`、`targetSdk = 36`。

## 本地配置

仓库不提交 `local.properties`。本机 SDK 路径为：

```properties
sdk.dir=E\:\\Android\\sdk
```

项目使用 Gradle Wrapper，不依赖全局 Gradle。Android Studio 内置 JDK 可用于构建。

## 构建调试 APK

```powershell
cd android
./gradlew.bat assembleDebug
```

调试包允许局域网 HTTP，便于 realme GT5 Pro 直接连接 Windows 主机的 `:5757`；Release 构建只允许 HTTPS。

Master Token 使用 Android Keystore AES-256-GCM 加密后保存在应用私有存储，应用备份已禁用。

首次启动时填写电脑的局域网地址（例如 `http://192.168.1.10:5757`）及部署环境中的 `OWNER_SETUP_TOKEN`。成功 Claim 后服务器只保存 Master Token 的 SHA-256，应用不再显示初始化密钥。

## Windows 配对

Android Master 首页点击“配对 Windows”，可使用 CameraX + ZXing Core 扫描 Web 二维码，或输入 6 位备用码。扫码完全在设备本地完成，不依赖 Google Play 服务。若服务器已有有效 Windows，只有在手机确认“替换”后，服务端才会在同一个 SQLite 事务中撤销旧设备并启用新设备。
