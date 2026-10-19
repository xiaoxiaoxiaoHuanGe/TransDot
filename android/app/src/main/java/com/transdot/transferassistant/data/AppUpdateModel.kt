package com.transdot.transferassistant.data

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.transdot.transferassistant.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class AppUpdateState(val update: AppUpdate? = null, val checking: Boolean = false, val downloading: Boolean = false, val progress: Float = 0f, val ready: Boolean = false, val visible: Boolean = false, val error: String? = null, val notice: String? = null)

class AppUpdateModel(application: Application) : AndroidViewModel(application) {
    var transferBusy: Boolean = false
    private val app = application
    private val prefs = app.getSharedPreferences("online_update", Context.MODE_PRIVATE)
    private val downloads = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    private val state = MutableStateFlow(AppUpdateState())
    val uiState = state.asStateFlow()
    private var monitor: Job? = null
    private val apk get() = File(app.getExternalFilesDir(null), "updates/update.apk")

    init {
        val raw = prefs.getString("manifest", null)
        val update = raw?.let { runCatching { parseUpdateManifest(it, Build.SUPPORTED_ABIS.toList(), Build.VERSION.SDK_INT, BuildConfig.VERSION_CODE.toLong()) }.getOrNull() }
        if (update != null) {
            state.value = AppUpdateState(update = update)
            if (prefs.getLong("download_id", -1) != -1L) observeDownload()
        } else {
            prefs.getLong("download_id", -1).takeIf { it != -1L }?.let { downloads.remove(it) }
            apk.delete(); prefs.edit().remove("manifest").remove("download_id").apply()
        }
    }

    fun check(manual: Boolean = false) {
        if (state.value.checking || state.value.downloading || state.value.ready) { if (manual) show(); return }
        if (!manual && System.currentTimeMillis() - prefs.getLong("last_check", 0) < 24 * 60 * 60 * 1000L) return
        prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
        state.value = state.value.copy(checking = true, error = null, notice = null, visible = manual)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) {
                val release = JSONObject(getJson("https://api.github.com/repos/$UPDATE_REPOSITORY/releases/latest"))
                require(!release.optBoolean("draft") && !release.optBoolean("prerelease")) { "尚无正式更新。" }
                val assets = release.getJSONArray("assets")
                val manifestAsset = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name") == "update.json" }
                    ?: throw IllegalStateException("当前正式版尚未提供在线更新清单。")
                val url = manifestAsset.getString("browser_download_url")
                require(trustedUpdateUrl(url)) { "更新清单地址无效。" }
                val raw = getJson(url)
                parseUpdateManifest(raw, Build.SUPPORTED_ABIS.toList(), Build.VERSION.SDK_INT, BuildConfig.VERSION_CODE.toLong())
            } }.onSuccess { update ->
                prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
                state.value = state.value.copy(checking = false, update = update, visible = manual || update != null, notice = if (update == null) "当前已是最新版，或暂无兼容的更新包。" else null)
            }.onFailure { error -> state.value = state.value.copy(checking = false, error = "检查更新失败：${error.message ?: "无法连接 GitHub，请重试。"}", visible = manual) }
        }
    }

    private fun getJson(url: String): String {
        val request = Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", "TransDot/${BuildConfig.VERSION_NAME}").build()
        return http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "GitHub 返回 HTTP ${response.code}，请稍后重试。" }
            val bytes = response.body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n); require(output.size() <= 512 * 1024) { "更新信息过大。" } }
                output.toByteArray()
            }
            require(bytes.size <= 512 * 1024) { "更新信息过大。" }
            bytes.toString(Charsets.UTF_8)
        }
    }

    fun download() {
        val update = state.value.update ?: return
        if (state.value.downloading) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    prefs.getLong("download_id", -1).takeIf { it != -1L }?.let { downloads.remove(it) }
                    apk.parentFile?.mkdirs(); apk.delete()
                    val request = DownloadManager.Request(Uri.parse(update.asset.url)).setTitle("TransDot ${update.versionName}")
                        .setDescription("下载应用更新").setMimeType("application/vnd.android.package-archive")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                        .setDestinationUri(Uri.fromFile(apk))
                    val id = downloads.enqueue(request)
                    prefs.edit().putString("manifest", update.manifest).putLong("download_id", id).commit()
                }
            }.onSuccess { observeDownload() }.onFailure { state.value = state.value.copy(error = "无法开始下载：${it.message}") }
        }
    }

    private fun observeDownload() {
        monitor?.cancel()
        state.value = state.value.copy(downloading = true, ready = false, error = null)
        monitor = viewModelScope.launch {
            try {
                val id = prefs.getLong("download_id", -1)
                while (true) {
                    val complete = withContext(Dispatchers.IO) {
                        downloads.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                            check(cursor.moveToFirst()) { "下载记录已失效，请重新下载。" }
                            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            check(status != DownloadManager.STATUS_FAILED) { "下载失败，请检查网络后重试。" }
                            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                            val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                            state.value = state.value.copy(progress = if (total > 0) (bytes.toFloat() / total).coerceIn(0f,1f) else 0f)
                            status == DownloadManager.STATUS_SUCCESSFUL
                        }
                    }
                    if (complete) {
                        withContext(Dispatchers.IO) { verifyApk(requireNotNull(state.value.update)) }
                        state.value = state.value.copy(downloading = false, ready = true, progress = 1f)
                        break
                    }
                    delay(700)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { state.value = state.value.copy(downloading = false, ready = false, error = error.message ?: "更新包校验失败。") }
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(update: AppUpdate) {
        require(apk.isFile && apk.length() == update.asset.size) { "更新包大小不符，请重新下载。" }
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        require(digest.digest().joinToString("") { "%02x".format(it) } == update.asset.sha256) { "更新包校验失败，请重新下载。" }
        val archive = requireNotNull(app.packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)) { "更新包无法解析。" }
        val installed = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNATURES)
        require(archive.packageName == app.packageName) { "更新包应用标识不匹配。" }
        val version = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        require(version == update.versionCode && version > BuildConfig.VERSION_CODE) { "更新包版本无效。" }
        val oldSignatures = installed.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        val newSignatures = archive.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        require(oldSignatures.isNotEmpty() && oldSignatures == newSignatures) { "更新包签名与当前安装版本不一致，无法覆盖升级。" }
    }

    fun install(context: Context, transferBusy: Boolean) {
        if (!state.value.ready) return
        if (transferBusy) { state.value = state.value.copy(error = "请等待当前传输完成后再安装更新。"); return }
        runCatching {
            if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                state.value = state.value.copy(notice = "请允许安装此来源的应用，返回后点击继续安装。")
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            } else {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }.onFailure { state.value = state.value.copy(error = "无法打开安装界面：${it.message}") }
    }
    fun cancelDownload() { monitor?.cancel(); prefs.getLong("download_id", -1).takeIf { it != -1L }?.let { downloads.remove(it) }; apk.delete(); prefs.edit().remove("download_id").apply(); state.value = state.value.copy(downloading = false, ready = false, progress = 0f) }
    fun show() { state.value = state.value.copy(visible = true) }
    fun dismiss() { state.value = state.value.copy(visible = false) }
}
