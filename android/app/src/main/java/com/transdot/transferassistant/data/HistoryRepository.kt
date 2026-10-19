package com.transdot.transferassistant.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class HistoryAccess(val deviceId: String, val displayName: String, val createdAt: String, val allowed: Boolean, val status: String, val requestedAt: String?)

class HistoryRepository {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    suspend fun list(session: StoredSession): List<HistoryAccess> = withContext(Dispatchers.IO) {
        val json = request(session, "/api/v1/history/browsers")
        val items = json.getJSONArray("devices")
        (0 until items.length()).map { index -> items.getJSONObject(index).let { value ->
            HistoryAccess(value.getString("device_id"), value.getString("display_name"), value.getString("created_at"), value.getBoolean("allowed"), value.getString("status"), value.optString("requested_at").takeUnless { it.isBlank() || it == "null" })
        } }
    }
    suspend fun decide(session: StoredSession, id: String, decision: String): Unit = withContext(Dispatchers.IO) {
        val body = JSONObject().put("decision", decision).toString().toRequestBody("application/json".toMediaType())
        request(session, "/api/v1/history/browsers/${java.net.URLEncoder.encode(id,"UTF-8")}", body)
        Unit
    }
    private fun request(session: StoredSession, path: String, body: okhttp3.RequestBody? = null): JSONObject {
        val builder = Request.Builder().url(session.serverAddress.trimEnd('/') + path).header("Authorization", "Bearer ${session.masterToken}")
        if (body != null) builder.patch(body)
        return client.newCall(builder.build()).execute().use { response ->
            check(response.isSuccessful) { if (response.code == 404) "当前服务器不支持历史权限，请更新服务器。" else "历史权限操作失败（HTTP ${response.code}）。" }
            JSONObject(response.body.string())
        }
    }
}
