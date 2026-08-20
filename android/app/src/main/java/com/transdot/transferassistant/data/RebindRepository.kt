package com.transdot.transferassistant.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

data class RebindPayload(
    val serverAddress: String,
    val instanceId: String,
    val instanceFingerprint: String,
    val sessionId: String,
    val secret: String,
    val expiresAt: Instant,
) {
    companion object {
        private val tokenPattern = Regex("^[A-Za-z0-9_-]{43}$")
        fun parse(rawValue: String, allowCleartext: Boolean): RebindPayload {
            val json = runCatching { JSONObject(rawValue.trim()) }.getOrElse { throw IllegalArgumentException("这不是手机重绑定二维码。") }
            require(json.optInt("v", -1) == 2 && json.optString("kind") == "rebind") { "二维码类型不正确。" }
            val address = ServerAddress.normalize(json.optString("server_url"), allowCleartext)
            val instanceId = json.optString("instance_id").trim()
            val fingerprint = json.optString("instance_fingerprint").trim()
            val sessionId = json.optString("rebind_session_id").trim()
            val secret = json.optString("rebind_secret").trim()
            val expiresAt = runCatching { Instant.parse(json.optString("expires_at")) }.getOrElse { throw IllegalArgumentException("二维码缺少有效期限。") }
            require(instanceId.isNotBlank() && fingerprint.matches(Regex("^[0-9a-fA-F]{4}-[0-9a-fA-F]{4}$"))) { "二维码缺少服务器身份。" }
            require(sessionId.matches(Regex("^[0-9a-fA-F-]{36}$")) && tokenPattern.matches(secret)) { "二维码缺少安全凭据。" }
            require(Instant.now().isBefore(expiresAt)) { "重绑定二维码已过期，请在网页上刷新。" }
            return RebindPayload(address, instanceId, fingerprint, sessionId, secret, expiresAt)
        }
    }
}

interface RebindRepository { suspend fun claim(payload: RebindPayload): ClaimedSession }

sealed class RebindFailure(message: String) : Exception(message) {
    class Expired : RebindFailure("重绑定二维码已过期，请在网页上刷新。")
    class Consumed : RebindFailure("重绑定二维码已被使用，请在网页上刷新。")
    class Invalid : RebindFailure("重绑定二维码无效，请在网页上重新生成。")
    class RateLimited : RebindFailure("尝试次数过多，请稍后再试。")
    class Server(message: String) : RebindFailure(message)
}

class NetworkRebindRepository(private val allowCleartext: Boolean) : RebindRepository {
    override suspend fun claim(payload: RebindPayload): ClaimedSession = withContext(Dispatchers.IO) {
        val address = ServerAddress.normalize(payload.serverAddress, allowCleartext)
        val connection = (URL("$address/api/v1/rebind/claim").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 10_000; readTimeout = 15_000; doOutput = true; instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json"); setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            val body = JSONObject().put("rebind_session_id", payload.sessionId).put("rebind_secret", payload.secret).put("instance_id", payload.instanceId).toString()
            connection.outputStream.use { it.write(body.toByteArray()) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (connection.responseCode !in 200..299) {
                val error = runCatching { JSONObject(response).optJSONObject("error") }.getOrNull()
                throw when (error?.optString("code")) {
                    "REBINDS_EXPIRED" -> RebindFailure.Expired()
                    "REBINDS_CONSUMED" -> RebindFailure.Consumed()
                    "REBINDS_INVALID" -> RebindFailure.Invalid()
                    "RATE_LIMITED" -> RebindFailure.RateLimited()
                    else -> RebindFailure.Server(error?.optString("message").orEmpty().ifBlank { "重绑定失败（HTTP ${connection.responseCode}）。" })
                }
            }
            parseRebindClaimResponse(response, payload).copy(serverAddress = address)
        } finally { connection.disconnect() }
    }
}

internal fun parseRebindClaimResponse(response: String, payload: RebindPayload): ClaimedSession {
    val json = runCatching { JSONObject(response) }.getOrElse { throw RebindFailure.Invalid() }
    val deviceId = json.optString("device_id").trim()
    val masterToken = json.optString("master_token").trim()
    val instanceId = json.optString("instance_id").trim()
    val instanceFingerprint = json.optString("instance_fingerprint").trim()
    if (
        deviceId.isBlank() || masterToken.isBlank() ||
        instanceId != payload.instanceId ||
        !instanceFingerprint.equals(payload.instanceFingerprint, ignoreCase = true)
    ) {
        throw RebindFailure.Invalid()
    }
    return ClaimedSession(
        payload.serverAddress,
        deviceId,
        masterToken,
        instanceId,
        instanceFingerprint,
    )
}
