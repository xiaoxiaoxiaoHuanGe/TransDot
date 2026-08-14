# Android 保存位置打开方式兼容性修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将“查看保存位置”改为 APP 内的系统文件管理器/CX 文件管理器双入口，消除 Google、UU 等无关候选。

**Architecture:** `DownloadDestinationManager` 继续封装 Android Intent，并新增可在 JVM 中测试的目录打开策略描述；Compose 层只维护选择对话框状态并执行已解析的 Intent。标准入口使用 SAF `ACTION_OPEN_DOCUMENT_TREE`，CX 入口使用显式限定包名的 `ACTION_VIEW + resource/folder`。

**Tech Stack:** Kotlin 2.x、Jetpack Compose Material 3、Android Storage Access Framework、JUnit 4、Gradle。

## Global Constraints

- 最低 Android API 仍为 23，目标 API 仍为 36。
- 不依赖 ColorOS 或其他厂商私有 Activity 类名。
- CX 入口仅在包 `com.cxinventor.file.explorer` 可解析目录 Intent 时显示。
- “打开文件”、默认保存目录和下载流程保持不变。
- 无默认目录或 CX 不可用时，系统目录入口仍可使用。

---

### Task 1: 可测试的目录打开策略与 Intent 构造

**Files:**
- Create: `android/app/src/main/java/com/transdot/transferassistant/data/SaveLocationOpenPolicy.kt`
- Create: `android/app/src/test/java/com/transdot/transferassistant/data/SaveLocationOpenPolicyTest.kt`
- Modify: `android/app/src/main/java/com/transdot/transferassistant/data/DownloadDestinationManager.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: `SaveLocationIntentSpec(action: String, mimeType: String?, packageName: String?)`。
- Produces: `systemSaveLocationIntentSpec()` 和 `cxSaveLocationIntentSpec()`。
- Produces: `DownloadDestinationManager.systemFolderIntent(initialUri: Uri?): Intent?`。
- Produces: `DownloadDestinationManager.cxFolderIntent(): Intent?`，仅在 CX 可解析时返回。

- [ ] **Step 1: Write the failing policy tests**

```kotlin
class SaveLocationOpenPolicyTest {
    @Test fun systemFolderUsesDocumentTreePicker() {
        assertEquals("android.intent.action.OPEN_DOCUMENT_TREE", systemSaveLocationIntentSpec().action)
        assertNull(systemSaveLocationIntentSpec().mimeType)
        assertNull(systemSaveLocationIntentSpec().packageName)
    }

    @Test fun cxFolderUsesExplicitDirectoryViewIntent() {
        val spec = cxSaveLocationIntentSpec()
        assertEquals("android.intent.action.VIEW", spec.action)
        assertEquals("resource/folder", spec.mimeType)
        assertEquals("com.cxinventor.file.explorer", spec.packageName)
    }
}
```

- [ ] **Step 2: Run the policy test and verify RED**

Run: `.\gradlew.bat testDebugUnitTest --tests com.transdot.transferassistant.data.SaveLocationOpenPolicyTest`

Expected: compilation failure because `SaveLocationIntentSpec` and both factory functions do not exist.

- [ ] **Step 3: Implement the minimal policy and manager integration**

Create the pure policy:

```kotlin
internal const val CX_FILE_MANAGER_PACKAGE = "com.cxinventor.file.explorer"

internal data class SaveLocationIntentSpec(
    val action: String,
    val mimeType: String? = null,
    val packageName: String? = null,
)

internal fun systemSaveLocationIntentSpec() =
    SaveLocationIntentSpec(action = "android.intent.action.OPEN_DOCUMENT_TREE")

internal fun cxSaveLocationIntentSpec() = SaveLocationIntentSpec(
    action = "android.intent.action.VIEW",
    mimeType = "resource/folder",
    packageName = CX_FILE_MANAGER_PACKAGE,
)
```

In `DownloadDestinationManager`, retain `browseFolderIntent` as the standard SAF constructor, rename/expose it as `systemFolderIntent`, and add a CX constructor that uses the persisted tree document URI, `setDataAndType(..., "resource/folder")`, `setPackage(CX_FILE_MANAGER_PACKAGE)`, URI grant flags, and `resolveActivity(packageManager)`. Delete the untyped `openFolderIntent` path.

Add this manifest visibility declaration:

```xml
<queries>
    <package android:name="com.cxinventor.file.explorer" />
</queries>
```

- [ ] **Step 4: Run the policy test and verify GREEN**

Run: `.\gradlew.bat testDebugUnitTest --tests com.transdot.transferassistant.data.SaveLocationOpenPolicyTest`

Expected: `BUILD SUCCESSFUL` and both tests pass.

- [ ] **Step 5: Commit the policy task**

```bash
git add android/app/src/main/AndroidManifest.xml android/app/src/main/java/com/transdot/transferassistant/data/DownloadDestinationManager.kt android/app/src/main/java/com/transdot/transferassistant/data/SaveLocationOpenPolicy.kt android/app/src/test/java/com/transdot/transferassistant/data/SaveLocationOpenPolicyTest.kt
git commit -m "fix: target compatible folder browsers"
```

### Task 2: Compose 选择对话框与回归验证

**Files:**
- Modify: `android/app/src/main/java/com/transdot/transferassistant/ui/TimelineScreen.kt`
- Test: `android/app/src/test/java/com/transdot/transferassistant/data/SaveLocationOpenPolicyTest.kt`

**Interfaces:**
- Consumes: `DownloadDestinationManager.systemFolderIntent(initialUri)`。
- Consumes: `DownloadDestinationManager.cxFolderIntent()`。
- Produces: “系统文件管理器 / CX 文件管理器 / 取消”选择对话框。

- [ ] **Step 1: Add a failing choice-list rule test**

```kotlin
@Test fun cxChoiceIsShownOnlyWhenAvailable() {
    assertEquals(listOf(SaveLocationChoice.SYSTEM), availableSaveLocationChoices(cxAvailable = false))
    assertEquals(
        listOf(SaveLocationChoice.SYSTEM, SaveLocationChoice.CX),
        availableSaveLocationChoices(cxAvailable = true),
    )
}
```

- [ ] **Step 2: Run the test and verify RED**

Run: `.\gradlew.bat testDebugUnitTest --tests com.transdot.transferassistant.data.SaveLocationOpenPolicyTest.cxChoiceIsShownOnlyWhenAvailable`

Expected: compilation failure because `SaveLocationChoice` and `availableSaveLocationChoices` do not exist.

- [ ] **Step 3: Implement the minimal availability rule and dialog**

Add:

```kotlin
internal enum class SaveLocationChoice { SYSTEM, CX }

internal fun availableSaveLocationChoices(cxAvailable: Boolean) = buildList {
    add(SaveLocationChoice.SYSTEM)
    if (cxAvailable) add(SaveLocationChoice.CX)
}
```

In `TimelineScreen`, store the completed file URI in `pendingSaveLocationUri` before consuming the download result. Resolve `cxFolderIntent()` once per opened dialog, derive the buttons with `availableSaveLocationChoices(cxIntent != null)`, and render an `AlertDialog` while that state is non-null:

- “系统文件管理器” starts `systemFolderIntent(pendingSaveLocationUri)` and reports “当前系统不支持打开保存位置” on failure.
- “CX 文件管理器” is rendered only when `cxFolderIntent()` returns non-null; it starts that explicit Intent and reports “CX 文件管理器无法打开该目录” on failure.
- “取消” clears `pendingSaveLocationUri`.

- [ ] **Step 4: Run all Android tests and build the APK**

Run: `.\gradlew.bat testDebugUnitTest assembleDebug`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Install and verify on the Android 16 test device**

Run:

```powershell
E:\as-sdk\platform-tools\adb.exe -s adb-a0c3b2e7-BDJIOT._adb-tls-connect._tcp install -r android\app\build\outputs\apk\debug\app-debug.apk
```

Verify:

1. 下载文件后点击“查看保存位置”。
2. APP 对话框显示“系统文件管理器”和“CX 文件管理器”，不再显示 Google/UU。
3. 系统入口启动 DocumentsUI 或 ColorOS 文件管理器并定位到默认保存目录。
4. 返回 APP 后再次操作，CX 入口启动 `com.cxinventor.file.explorer`。
5. 返回 APP，确认服务器仍显示已连接，下载结果未被修改。

- [ ] **Step 6: Commit the UI task**

```bash
git add android/app/src/main/java/com/transdot/transferassistant/ui/TimelineScreen.kt android/app/src/main/java/com/transdot/transferassistant/data/SaveLocationOpenPolicy.kt android/app/src/test/java/com/transdot/transferassistant/data/SaveLocationOpenPolicyTest.kt
git commit -m "fix: choose a compatible save location browser"
```
