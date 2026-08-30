package com.transdot.transferassistant.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class BrowserDevice(
    val id: String,
    val displayName: String,
    val createdAt: String,
    val lastSeenAt: String?,
)

data class BrowserDeviceList(
    val devices: List<BrowserDevice>,
    val activeCount: Int,
    val maximumCount: Int,
)

sealed class BrowserDeviceFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unsupported : BrowserDeviceFailure("当前服务器版本不支持浏览器设备管理。")
    class Unauthorized : BrowserDeviceFailure("Android Master 凭据已失效。")
    class InvalidName : BrowserDeviceFailure("设备名称必须为 1 至 64 个字符，且不能包含控制字符。")
    class NotFound : BrowserDeviceFailure("浏览器设备不存在或已撤销。")
    class Network(cause: Throwable) : BrowserDeviceFailure("无法连接服务器，请检查网络。", cause)
    class Server(message: String) : BrowserDeviceFailure(message)
}

interface BrowserDeviceRepository {
    suspend fun list(session: StoredSession): BrowserDeviceList
    suspend fun rename(session: StoredSession, deviceId: String, displayName: String): BrowserDevice
    suspend fun revoke(session: StoredSession, deviceId: String)
}

class NetworkBrowserDeviceRepository(
    private val allowCleartext: Boolean,
    private val client: OkHttpClient = OkHttpClient(),
) : BrowserDeviceRepository {
    override suspend fun list(session: StoredSession): BrowserDeviceList = withContext(Dispatchers.IO) {
        parseBrowserDevicesResponseOrUnsupported(execute(session, "/api/v1/devices/browsers", "GET").body)
    }

    override suspend fun rename(session: StoredSession, deviceId: String, displayName: String): BrowserDevice = withContext(Dispatchers.IO) {
        val body = JSONObject().put("display_name", displayName).toString()
        parseBrowserDevice(execute(session, "/api/v1/devices/browsers/${urlPathSegment(deviceId)}", "PATCH", body).body)
    }

    override suspend fun revoke(session: StoredSession, deviceId: String) = withContext(Dispatchers.IO) {
        execute(session, "/api/v1/devices/browsers/${urlPathSegment(deviceId)}", "DELETE")
        Unit
    }

    private fun execute(session: StoredSession, path: String, method: String, jsonBody: String? = null): ResponseData {
        val address = ServerAddress.normalize(session.serverAddress, allowCleartext)
        val requestBody = jsonBody?.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$address$path")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer ${session.masterToken}")
            .method(method, requestBody)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) throw mapFailure(response.code, body)
                return ResponseData(response.code, body)
            }
        } catch (failure: BrowserDeviceFailure) {
            throw failure
        } catch (failure: IOException) {
            throw BrowserDeviceFailure.Network(failure)
        } catch (failure: RuntimeException) {
            throw BrowserDeviceFailure.Server("服务器响应无法解析。")
        }
    }

    private fun mapFailure(status: Int, body: String): BrowserDeviceFailure {
        val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        if (status == 404 && error == null) return BrowserDeviceFailure.Unsupported()
        return when (error?.optString("code")) {
            "INVALID_DEVICE_NAME" -> BrowserDeviceFailure.InvalidName()
            "DEVICE_NOT_FOUND" -> BrowserDeviceFailure.NotFound()
            "UNAUTHORIZED", "DEVICE_REVOKED" -> BrowserDeviceFailure.Unauthorized()
            "NOT_FOUND" -> BrowserDeviceFailure.Unsupported()
            else -> BrowserDeviceFailure.Server(error?.optString("message").orEmpty().ifBlank { "服务器请求失败（HTTP $status）。" })
        }
    }

    private data class ResponseData(val status: Int, val body: String)
    private companion object { val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType() }
}

internal fun parseBrowserDevicesResponse(rawValue: String): BrowserDeviceList {
    val json = JSONObject(rawValue)
    val array = json.getJSONArray("devices")
    val devices = buildList {
        for (index in 0 until array.length()) add(parseBrowserDevice(array.getJSONObject(index).toString()))
    }
    return BrowserDeviceList(devices, json.optInt("active_count", devices.size), json.optInt("maximum_count", 10))
}

internal fun parseBrowserDevicesResponseOrUnsupported(rawValue: String): BrowserDeviceList {
    if (!rawValue.trimStart().startsWith("{")) throw BrowserDeviceFailure.Unsupported()
    return try {
        val json = JSONObject(rawValue)
        if (!json.has("devices")) throw BrowserDeviceFailure.Unsupported()
        parseBrowserDevicesResponse(rawValue)
    } catch (failure: BrowserDeviceFailure.Unsupported) {
        throw failure
    } catch (_: RuntimeException) {
        throw BrowserDeviceFailure.Unsupported()
    }
}

private fun parseBrowserDevice(rawValue: String): BrowserDevice {
    val json = JSONObject(rawValue)
    return BrowserDevice(
        id = json.getString("id"),
        displayName = json.getString("display_name"),
        createdAt = json.getString("created_at"),
        lastSeenAt = if (json.isNull("last_seen_at")) null else json.optString("last_seen_at").takeIf(String::isNotBlank),
    )
}

internal fun browserLastSeenLabel(lastSeenAt: String?, now: String, zoneId: ZoneId = ZoneId.systemDefault()): String {
    if (lastSeenAt == null) return "刚刚授权"
    return runCatching {
        val last = Instant.parse(lastSeenAt).atZone(zoneId)
        val current = Instant.parse(now).atZone(zoneId)
        if (last.toLocalDate() == current.toLocalDate()) "今天 ${last.format(DateTimeFormatter.ofPattern("HH:mm"))}"
        else last.format(DateTimeFormatter.ISO_LOCAL_DATE)
    }.getOrElse { lastSeenAt.take(10) }
}

internal fun browserAuthorizedLabel(createdAt: String, zoneId: ZoneId = ZoneId.systemDefault()): String =
    runCatching {
        Instant.parse(createdAt).atZone(zoneId).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }.getOrElse { createdAt.take(16).replace('T', ' ') }

private fun urlPathSegment(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
