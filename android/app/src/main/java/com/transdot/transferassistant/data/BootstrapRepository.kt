package com.transdot.transferassistant.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

data class BootstrapPayload(
    val serverAddress: String,
    val instanceId: String,
    val instanceFingerprint: String,
    val sessionId: String,
    val secret: String,
    val expiresAt: Instant,
) {
    companion object {
        private val tokenPattern = Regex("^[A-Za-z0-9_-]{43}$")
        fun parse(rawValue: String, allowCleartext: Boolean): BootstrapPayload {
            val json = runCatching { JSONObject(rawValue.trim()) }.getOrElse { throw IllegalArgumentException("这不是服务器绑定二维码。") }
            require(json.optInt("v", -1) == 2 && json.optString("kind") == "bootstrap") { "二维码类型不正确。" }
            val address = ServerAddress.normalize(json.optString("server_url"), allowCleartext)
            val instanceId = json.optString("instance_id").trim()
            val fingerprint = json.optString("instance_fingerprint").trim()
            val sessionId = json.optString("bootstrap_session_id").trim()
            val secret = json.optString("bootstrap_secret").trim()
            val expiresAt = runCatching { Instant.parse(json.optString("expires_at")) }.getOrElse { throw IllegalArgumentException("二维码缺少有效期限。") }
            require(instanceId.isNotBlank() && fingerprint.matches(Regex("^[0-9a-fA-F]{4}-[0-9a-fA-F]{4}$"))) { "二维码缺少服务器身份。" }
            require(sessionId.matches(Regex("^[0-9a-fA-F-]{36}$")) && tokenPattern.matches(secret)) { "二维码缺少安全凭据。" }
            require(Instant.now().isBefore(expiresAt)) { "绑定二维码已过期，请在网页上刷新。" }
            return BootstrapPayload(address, instanceId, fingerprint, sessionId, secret, expiresAt)
        }
    }
}

interface BootstrapRepository { suspend fun claim(payload: BootstrapPayload): ClaimedSession }

sealed class BootstrapFailure(message: String) : Exception(message) {
    class Expired : BootstrapFailure("绑定二维码已过期，请在网页上刷新。")
    class Consumed : BootstrapFailure("绑定二维码已被使用，请在网页上刷新。")
    class Initialized : BootstrapFailure("服务器已经绑定，请扫描普通配对二维码。")
    class RateLimited : BootstrapFailure("尝试次数过多，请稍后再试。")
    class Server(message: String) : BootstrapFailure(message)
}

class NetworkBootstrapRepository(private val allowCleartext: Boolean) : BootstrapRepository {
    override suspend fun claim(payload: BootstrapPayload): ClaimedSession = withContext(Dispatchers.IO) {
        val address = ServerAddress.normalize(payload.serverAddress, allowCleartext)
        val connection = (URL("$address/api/v1/bootstrap/claim").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 10_000; readTimeout = 15_000; doOutput = true; instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json"); setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            val body = JSONObject().put("bootstrap_session_id", payload.sessionId).put("bootstrap_secret", payload.secret).toString()
            connection.outputStream.use { it.write(body.toByteArray()) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (connection.responseCode !in 200..299) {
                val error = runCatching { JSONObject(response).optJSONObject("error") }.getOrNull()
                throw when (error?.optString("code")) {
                    "BOOTSTRAP_EXPIRED" -> BootstrapFailure.Expired()
                    "BOOTSTRAP_CONSUMED" -> BootstrapFailure.Consumed()
                    "ALREADY_INITIALIZED" -> BootstrapFailure.Initialized()
                    "RATE_LIMITED" -> BootstrapFailure.RateLimited()
                    else -> BootstrapFailure.Server(error?.optString("message").orEmpty().ifBlank { "绑定失败（HTTP ${connection.responseCode}）。" })
                }
            }
            val json = JSONObject(response)
            ClaimedSession(address, json.getString("device_id"), json.getString("master_token"), payload.instanceId, payload.instanceFingerprint)
        } finally { connection.disconnect() }
    }
}
