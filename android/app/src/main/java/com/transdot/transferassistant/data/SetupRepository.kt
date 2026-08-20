package com.transdot.transferassistant.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class ClaimedSession(
    val serverAddress: String,
    val deviceId: String,
    val masterToken: String,
    val instanceId: String = "",
    val instanceFingerprint: String = "",
)

sealed class SetupFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class AlreadyInitialized : SetupFailure("这台服务器已经完成初始化，不能再次 Claim。")
    class InvalidToken : SetupFailure("初始化密钥不正确，请检查后重试。")
    class RateLimited : SetupFailure("尝试次数过多，请稍后再试。")
    class Network(cause: Throwable) : SetupFailure("无法连接服务器，请检查地址和网络。", cause)
    class SecureStorage(cause: Throwable) : SetupFailure("无法安全保存 Master Token，请保持应用开启并重试。", cause)
    class Server(message: String) : SetupFailure(message)
}

interface SetupRepository {
    suspend fun isInitialized(serverAddress: String): Boolean
    suspend fun claim(serverAddress: String, setupToken: String): ClaimedSession
}

class NetworkSetupRepository(
    private val allowCleartext: Boolean,
) : SetupRepository {
    override suspend fun isInitialized(serverAddress: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = ServerAddress.normalize(serverAddress, allowCleartext)
        val response = request("$normalized/api/v1/setup/status", "GET")
        if (response.status !in 200..299) throw mapFailure(response)
        val initialized = JSONObject(response.body).opt("initialized")
        if (initialized !is Boolean) throw SetupFailure.Server("服务器返回了无效的初始化状态。")
        initialized
    }

    override suspend fun claim(serverAddress: String, setupToken: String): ClaimedSession = withContext(Dispatchers.IO) {
        val normalized = ServerAddress.normalize(serverAddress, allowCleartext)
        val body = JSONObject().put("setup_token", setupToken.trim()).toString()
        val response = request("$normalized/api/v1/setup/claim", "POST", body)
        if (response.status !in 200..299) throw mapFailure(response)

        val json = JSONObject(response.body)
        val deviceId = json.optString("device_id")
        val masterToken = json.optString("master_token")
        if (deviceId.isBlank() || masterToken.isBlank()) {
            throw SetupFailure.Server("服务器返回了无效的初始化结果。")
        }
        ClaimedSession(normalized, deviceId, masterToken)
    }

    private fun request(url: String, method: String, requestBody: String? = null): Response {
        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Accept", "application/json")
                if (requestBody != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            return try {
                if (requestBody != null) {
                    connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                Response(status, stream?.use(::readLimited).orEmpty())
            } finally {
                connection.disconnect()
            }
        } catch (failure: SetupFailure) {
            throw failure
        } catch (failure: IOException) {
            throw SetupFailure.Network(failure)
        } catch (failure: RuntimeException) {
            throw SetupFailure.Server("服务器响应无法解析。")
        }
    }

    private fun mapFailure(response: Response): SetupFailure {
        val error = runCatching { JSONObject(response.body).optJSONObject("error") }.getOrNull()
        return when (error?.optString("code")) {
            "ALREADY_INITIALIZED" -> SetupFailure.AlreadyInitialized()
            "SETUP_TOKEN_INVALID" -> SetupFailure.InvalidToken()
            "RATE_LIMITED" -> SetupFailure.RateLimited()
            else -> SetupFailure.Server(error?.optString("message").orEmpty().ifBlank {
                "服务器请求失败（HTTP ${response.status}）。"
            })
        }
    }

    private fun readLimited(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_RESPONSE_BYTES) throw SetupFailure.Server("服务器响应过大。")
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private data class Response(val status: Int, val body: String)

    private companion object {
        const val MAX_RESPONSE_BYTES = 64 * 1024
    }
}
