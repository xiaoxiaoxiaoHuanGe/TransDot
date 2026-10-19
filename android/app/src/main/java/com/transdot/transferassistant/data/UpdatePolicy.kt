package com.transdot.transferassistant.data

import org.json.JSONObject
import java.net.URI

const val UPDATE_REPOSITORY = "xiaoxiaoxiaoHuanGe/TransDot"
data class UpdateAsset(val abi: String, val url: String, val size: Long, val sha256: String, val minSdk: Int)
data class AppUpdate(val versionCode: Long, val versionName: String, val notes: String, val asset: UpdateAsset, val manifest: String)

fun trustedUpdateUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme == "https" && uri.host == "github.com" && (uri.port == -1 || uri.port == 443) && uri.userInfo == null &&
        uri.path.startsWith("/$UPDATE_REPOSITORY/releases/download/") && !uri.path.contains("/../")
}.getOrDefault(false)

fun parseUpdateManifest(raw: String, abis: List<String>, sdk: Int, installed: Long): AppUpdate? {
    val json = JSONObject(raw)
    val version = json.getLong("versionCode")
    if (version <= installed) return null
    val array = json.getJSONArray("assets")
    val assets = (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        UpdateAsset(item.getString("abi"), item.getString("url"), item.getLong("size"), item.getString("sha256").lowercase(), item.getInt("minSdk"))
    }
    val asset = (abis + "universal").firstNotNullOfOrNull { abi -> assets.firstOrNull { it.abi == abi && it.minSdk <= sdk } } ?: return null
    require(trustedUpdateUrl(asset.url)) { "更新地址不属于项目正式发布源。" }
    require(asset.size in 1..(500L * 1024 * 1024)) { "更新包大小无效。" }
    require(asset.sha256.matches(Regex("[a-f0-9]{64}"))) { "更新包缺少有效校验值。" }
    return AppUpdate(version, json.getString("versionName"), json.optString("notes"), asset, raw)
}
