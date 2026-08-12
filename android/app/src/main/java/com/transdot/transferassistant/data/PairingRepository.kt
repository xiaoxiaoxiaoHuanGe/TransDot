package com.transdot.transferassistant.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

sealed interface PairingCredential {
    data class QR(val sessionId: String, val secret: String) : PairingCredential
    data class Code(val value: String) : PairingCredential
}

sealed class PairingFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ReplacementRequired : PairingFailure("当前已有一台 Windows，继续将使旧浏览器立即失效。")
    class Invalid : PairingFailure("配对二维码或 6 位码无效。")
    class Expired : PairingFailure("配对会话已过期，请在电脑上生成新二维码。")
    class Unauthorized : PairingFailure("Android Master 凭据已失效。")
    class RateLimited : PairingFailure("尝试次数过多，请稍后再试。")
    class Network(cause: Throwable) : PairingFailure("无法连接服务器，请检查手机与服务器网络。", cause)
    class Server(message: String) : PairingFailure(message)
}

interface PairingRepository {
    suspend fun approve(session: StoredSession, credential: PairingCredential, replaceExisting: Boolean)
    suspend fun reject(session: StoredSession, credential: PairingCredential)
}

class NetworkPairingRepository(
    private val allowCleartext: Boolean,
) : PairingRepository {
    override suspend fun approve(
        session: StoredSession,
        credential: PairingCredential,
        replaceExisting: Boolean,
    ) = withContext(Dispatchers.IO) {
        val body = credentialBody(credential)
            .put("replace_existing", replaceExisting)
            .toString()
        val response = request(session, "/api/v1/pairing/approve", body)
        if (response.status !in 200..299) throw mapFailure(response)
        if (JSONObject(response.body).optString("status") != "approved") {
            throw PairingFailure.Server("服务器返回了无效的配对结果。")
        }
    }

    override suspend fun reject(session: StoredSession, credential: PairingCredential) = withContext(Dispatchers.IO) {
        val response = request(session, "/api/v1/pairing/reject", credentialBody(credential).toString())
        if (response.status !in 200..299) throw mapFailure(response)
    }

    private fun request(session: StoredSession, path: String, requestBody: String): Response {
        val serverAddress = ServerAddress.normalize(session.serverAddress, allowCleartext)
        try {
            val connection = (URL("$serverAddress$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                useCaches = false
                doOutput = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer ${session.masterToken}")
            }
            return try {
                connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                Response(status, stream?.use(::readLimited).orEmpty())
            } finally {
                connection.disconnect()
            }
        } catch (failure: PairingFailure) {
            throw failure
        } catch (failure: IOException) {
            throw PairingFailure.Network(failure)
        } catch (failure: RuntimeException) {
            throw PairingFailure.Server("服务器响应无法解析。")
        }
    }

    private fun credentialBody(credential: PairingCredential): JSONObject = when (credential) {
        is PairingCredential.QR -> JSONObject()
            .put("session_id", credential.sessionId)
            .put("qr_secret", credential.secret)
        is PairingCredential.Code -> JSONObject()
            .put("pairing_code", credential.value.filter(Char::isDigit))
    }

    private fun mapFailure(response: Response): PairingFailure {
        val error = runCatching { JSONObject(response.body).optJSONObject("error") }.getOrNull()
        return when (error?.optString("code")) {
            "WINDOWS_REPLACEMENT_REQUIRED" -> PairingFailure.ReplacementRequired()
            "PAIRING_INVALID" -> PairingFailure.Invalid()
            "PAIRING_EXPIRED" -> PairingFailure.Expired()
            "UNAUTHORIZED", "DEVICE_REVOKED" -> PairingFailure.Unauthorized()
            "RATE_LIMITED" -> PairingFailure.RateLimited()
            else -> PairingFailure.Server(error?.optString("message").orEmpty().ifBlank {
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
            if (total > MAX_RESPONSE_BYTES) throw PairingFailure.Server("服务器响应过大。")
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private data class Response(val status: Int, val body: String)

    private companion object {
        const val MAX_RESPONSE_BYTES = 64 * 1024
    }
}
