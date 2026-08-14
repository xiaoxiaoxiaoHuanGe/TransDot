package com.transdot.transferassistant.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

data class FileAttachment(
    val id: String,
    val originalFilename: String,
    val mimeType: String,
    val sizeBytes: Long,
    val status: String,
    val expiresAt: String?,
    val expiredReason: String?,
    val downloadUrl: String,
    val thumbnailUrl: String?,
)

data class TimelineMessage(
    val id: String,
    val type: String,
    val batchId: String?,
    val sourceDeviceId: String,
    val sourceDeviceType: String,
    val textContent: String?,
    val createdAt: String,
    val metadataExpiresAt: String?,
    val file: FileAttachment? = null,
)

data class MessagePage(val messages: List<TimelineMessage>, val nextBefore: String?)
data class MessageContext(val targetMessageId: String, val messages: List<TimelineMessage>)
data class UploadProgress(
    val uploadId: String,
    val filename: String,
    val bytesSent: Long,
    val totalBytes: Long,
    val state: String,
    val error: String? = null,
    val sourceUri: Uri? = null,
    val sourceIndex: Int? = null,
)

sealed interface TimelineEvent {
    data class Created(val message: TimelineMessage) : TimelineEvent
    data class Deleted(val messageId: String) : TimelineEvent
    data class FileExpired(val fileId: String, val messageId: String) : TimelineEvent
    data object DeviceReplaced : TimelineEvent
    data object Unknown : TimelineEvent
}

sealed class TimelineFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unauthorized : TimelineFailure("Android Master 凭据已失效。")
    class Invalid(message: String) : TimelineFailure(message)
    class NotFound : TimelineFailure("消息或文件已不存在。")
    class Network(cause: Throwable) : TimelineFailure("无法连接服务器，请检查网络。", cause)
    class Server(message: String, cause: Throwable? = null) : TimelineFailure(message, cause)
}

interface RealtimeConnection { fun close() }
interface TimelineRealtimeListener {
    fun onOpen()
    fun onEvent(event: TimelineEvent)
    fun onClosed()
    fun onFailure(failure: TimelineFailure)
}

interface TimelineRepository {
    suspend fun list(session: StoredSession, before: String? = null): MessagePage
    suspend fun sendText(session: StoredSession, text: String): TimelineMessage
    suspend fun delete(session: StoredSession, messageId: String)
    suspend fun search(session: StoredSession, query: String): List<TimelineMessage>
    suspend fun context(session: StoredSession, messageId: String): MessageContext
    suspend fun upload(session: StoredSession, uris: List<Uri>, progress: (UploadProgress) -> Unit): List<TimelineMessage> =
        throw TimelineFailure.Invalid("当前环境不支持文件选择。")
    suspend fun download(session: StoredSession, message: TimelineMessage, destination: Uri, progress: (Long, Long) -> Unit): Unit =
        throw TimelineFailure.Invalid("当前环境不支持文件下载。")
    suspend fun loadImage(session: StoredSession, message: TimelineMessage, original: Boolean): Bitmap? = null
    fun connect(session: StoredSession, listener: TimelineRealtimeListener): RealtimeConnection
}

class NetworkTimelineRepository(
    private val allowCleartext: Boolean,
    private val context: Context? = null,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.MINUTES)
        .pingInterval(30, TimeUnit.SECONDS)
        .build(),
) : TimelineRepository {
    override suspend fun list(session: StoredSession, before: String?): MessagePage = withContext(Dispatchers.IO) {
        val suffix = before?.takeIf(String::isNotBlank)?.let { "&before=${urlEncode(it)}" }.orEmpty()
        val json = parseObject(execute(session, "/api/v1/messages?limit=50$suffix").body)
        MessagePage(parseMessages(json.optJSONArray("messages") ?: JSONArray()), json.optString("next_before").takeIf(String::isNotBlank))
    }

    override suspend fun sendText(session: StoredSession, text: String): TimelineMessage = withContext(Dispatchers.IO) {
        val body = JSONObject().put("text", text).toString()
        parseMessage(parseObject(execute(session, "/api/v1/messages/text", "POST", body).body))
    }

    override suspend fun delete(session: StoredSession, messageId: String) = withContext(Dispatchers.IO) {
        execute(session, "/api/v1/messages/${urlEncode(messageId)}", "DELETE")
        Unit
    }

    override suspend fun search(session: StoredSession, query: String): List<TimelineMessage> = withContext(Dispatchers.IO) {
        parseMessages(parseObject(execute(session, "/api/v1/search?q=${urlEncode(query)}").body).optJSONArray("results") ?: JSONArray())
    }

    override suspend fun context(session: StoredSession, messageId: String): MessageContext = withContext(Dispatchers.IO) {
        val json = parseObject(execute(session, "/api/v1/messages/${urlEncode(messageId)}/context").body)
        MessageContext(json.getString("target_message_id"), parseMessages(json.getJSONArray("messages")))
    }

    override suspend fun upload(session: StoredSession, uris: List<Uri>, progress: (UploadProgress) -> Unit): List<TimelineMessage> = withContext(Dispatchers.IO) {
        val resolver = requireContext().contentResolver
        if (uris.isEmpty()) return@withContext emptyList()
        if (uris.size > MAX_BATCH_ITEMS) throw TimelineFailure.Invalid("一次最多选择 20 个文件。")
        val sources = uris.map { describeSource(resolver, it) }
        if (sources.any { it.size > MAX_FILE_BYTES }) throw TimelineFailure.Invalid("单个文件不能超过 300 MB。")
        if (sources.sumOf(Source::size) > MAX_BATCH_BYTES) throw TimelineFailure.Invalid("单批文件不能超过 500 MB。")

        val items = JSONArray()
        sources.forEach { source ->
            items.put(JSONObject()
                .put("filename", source.filename)
                .put("mime_type", source.mimeType)
                .put("size_bytes", source.size)
                .put("kind", if (isPreviewableImage(source.mimeType)) "image" else "file"))
        }
        val batch = parseObject(execute(session, "/api/v1/upload-batches", "POST", JSONObject().put("items", items).toString()).body)
        val tickets = batch.getJSONArray("uploads")
        buildList {
            sources.forEachIndexed { index, source ->
                val ticket = tickets.getJSONObject(index)
                val uploadId = ticket.getString("upload_id")
                progress(UploadProgress(uploadId, source.filename, 0, source.size, "preparing", sourceUri = source.uri, sourceIndex = index))
                ticket.optString("thumbnail_upload_url").takeIf(String::isNotBlank)?.let { path ->
                    val thumbnail = createThumbnail(resolver, source.uri)
                    executeBinary(session, path, thumbnail.toRequestBody(JPEG_MEDIA_TYPE))
                }
                val requestBody = StreamingRequestBody(resolver, source) { sent ->
                    progress(UploadProgress(uploadId, source.filename, sent, source.size, "uploading", sourceUri = source.uri, sourceIndex = index))
                }
                try {
                    val response = executeBinary(session, ticket.getString("upload_url"), requestBody)
                    add(parseMessage(parseObject(response)))
                    progress(UploadProgress(uploadId, source.filename, source.size, source.size, "complete", sourceUri = source.uri, sourceIndex = index))
                } catch (failure: Throwable) {
                    progress(UploadProgress(uploadId, source.filename, 0, source.size, "failed", failure.message, source.uri, index))
                    throw failure
                }
            }
        }
    }

    override suspend fun download(session: StoredSession, message: TimelineMessage, destination: Uri, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val attachment = message.file ?: throw TimelineFailure.NotFound()
        val response = executeStreaming(session, attachment.downloadUrl)
        response.use {
            val total = it.body.contentLength().takeIf { value -> value >= 0 } ?: attachment.sizeBytes
            val output = requireNotNull(requireContext().contentResolver.openOutputStream(destination, "w")) { "无法打开目标文件。" }
            output.use { stream -> copyWithProgress(it.body.byteStream(), stream, total, progress) }
        }
    }

    override suspend fun loadImage(session: StoredSession, message: TimelineMessage, original: Boolean): Bitmap? = withContext(Dispatchers.IO) {
        val attachment = message.file ?: return@withContext null
        val path = if (original && attachment.status == "available") attachment.downloadUrl else attachment.thumbnailUrl
            ?: return@withContext null
        val cacheFile = File(requireContext().cacheDir, "transfer-preview-${attachment.id}-${if (original) "full" else "thumb"}")
        if (!cacheFile.exists() || cacheFile.length() == 0L) {
            executeStreaming(session, path).use { response ->
                FileOutputStream(cacheFile).use { output -> response.body.byteStream().copyTo(output, DEFAULT_BUFFER_SIZE) }
            }
        }
        decodeSampledBitmap(cacheFile, if (original) 2400 else 900)
    }

    override fun connect(session: StoredSession, listener: TimelineRealtimeListener): RealtimeConnection {
        val serverAddress = ServerAddress.normalize(session.serverAddress, allowCleartext)
        val websocketAddress = if (serverAddress.startsWith("https://")) "wss://${serverAddress.removePrefix("https://")}/ws"
        else "ws://${serverAddress.removePrefix("http://")}/ws"
        val request = Request.Builder().url(websocketAddress).header("Authorization", "Bearer ${session.masterToken}").build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
            override fun onMessage(webSocket: WebSocket, text: String) = listener.onEvent(runCatching { parseEvent(text) }.getOrDefault(TimelineEvent.Unknown))
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                listener.onFailure(if (response?.code == 401) TimelineFailure.Unauthorized() else TimelineFailure.Network(t))
        })
        return object : RealtimeConnection { override fun close() { if (!socket.close(NORMAL_CLOSURE, "app background")) socket.cancel() } }
    }

    private fun execute(session: StoredSession, path: String, method: String = "GET", requestBody: String? = null): HttpResponse {
        val builder = authorizedRequest(session, path)
        when (method) {
            "POST" -> builder.post(requireNotNull(requestBody).toRequestBody(JSON_MEDIA_TYPE))
            "DELETE" -> builder.delete()
        }
        try {
            client.newCall(builder.build()).execute().use { response ->
                val body = readLimited(response)
                if (!response.isSuccessful) throw mapFailure(response.code, body)
                return HttpResponse(response.code, body)
            }
        } catch (failure: TimelineFailure) { throw failure }
        catch (failure: IOException) { throw TimelineFailure.Network(failure) }
        catch (failure: RuntimeException) { throw TimelineFailure.Server("服务器响应无法解析。", failure) }
    }

    private fun executeBinary(session: StoredSession, path: String, body: RequestBody): String {
        try {
            client.newCall(authorizedRequest(session, path).put(body).build()).execute().use { response ->
                val contents = readLimited(response)
                if (!response.isSuccessful) throw mapFailure(response.code, contents)
                return contents
            }
        } catch (failure: TimelineFailure) { throw failure }
        catch (failure: IOException) { throw TimelineFailure.Network(failure) }
    }

    private fun executeStreaming(session: StoredSession, path: String): Response {
        try {
            val response = client.newCall(authorizedRequest(session, path).get().build()).execute()
            if (!response.isSuccessful) {
                val body = readLimited(response)
                response.close()
                throw mapFailure(response.code, body)
            }
            return response
        } catch (failure: TimelineFailure) { throw failure }
        catch (failure: IOException) { throw TimelineFailure.Network(failure) }
    }

    private fun authorizedRequest(session: StoredSession, path: String) = Request.Builder()
        .url("${ServerAddress.normalize(session.serverAddress, allowCleartext)}$path")
        .header("Accept", "application/json")
        .header("Authorization", "Bearer ${session.masterToken}")

    private fun readLimited(response: Response): String {
        val body = response.body
        if (body.contentLength() > MAX_RESPONSE_BYTES) throw TimelineFailure.Server("服务器响应过大。")
        val bytes = body.bytes()
        if (bytes.size > MAX_RESPONSE_BYTES) throw TimelineFailure.Server("服务器响应过大。")
        return bytes.toString(Charsets.UTF_8)
    }

    private fun mapFailure(status: Int, body: String): TimelineFailure {
        val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        return when (val code = error?.optString("code")) {
            "UNAUTHORIZED", "DEVICE_REVOKED" -> TimelineFailure.Unauthorized()
            "MESSAGE_NOT_FOUND", "FILE_NOT_FOUND", "FILE_EXPIRED" -> TimelineFailure.NotFound()
            "FILE_TOO_LARGE" -> TimelineFailure.Invalid("单个文件不能超过 300 MB。")
            "BATCH_TOO_LARGE" -> TimelineFailure.Invalid("单批文件不能超过 500 MB。")
            "TOO_MANY_FILES" -> TimelineFailure.Invalid("一次最多选择 20 个文件。")
            "INSUFFICIENT_STORAGE" -> TimelineFailure.Invalid("服务器临时文件池空间不足。")
            "TEXT_EMPTY", "TEXT_TOO_LARGE", "TEXT_INVALID_UTF8", "SEARCH_INVALID", "UPLOAD_INCOMPLETE", "UPLOAD_EXPIRED" ->
                TimelineFailure.Invalid(error.optString("message").ifBlank { code.orEmpty() })
            else -> TimelineFailure.Server(error?.optString("message").orEmpty().ifBlank { "服务器请求失败（HTTP $status）。" })
        }
    }

    private fun parseObject(value: String): JSONObject = try { JSONObject(value) }
    catch (failure: JSONException) { throw TimelineFailure.Server("服务器响应无法解析。", failure) }
    private fun parseMessages(array: JSONArray) = buildList { for (index in 0 until array.length()) add(parseMessage(array.getJSONObject(index))) }
    private fun parseMessage(json: JSONObject): TimelineMessage {
        val file = json.optJSONObject("file")?.let { value ->
            FileAttachment(
                value.getString("id"), value.getString("original_filename"), value.getString("mime_type"),
                value.getLong("size_bytes"), value.getString("status"), value.optNullableString("expires_at"),
                value.optNullableString("expired_reason"), value.getString("download_url"), value.optNullableString("thumbnail_url"),
            )
        }
        return TimelineMessage(
            json.getString("id"), json.getString("type"), json.optNullableString("batch_id"),
            json.getString("source_device_id"), json.getString("source_device_type"), json.optNullableString("text_content"),
            json.getString("created_at"), json.optNullableString("metadata_expires_at"), file,
        )
    }

    private fun parseEvent(rawValue: String): TimelineEvent {
        val json = JSONObject(rawValue)
        return when (json.optString("type")) {
            "message.created" -> TimelineEvent.Created(parseMessage(json.getJSONObject("data")))
            "message.deleted" -> TimelineEvent.Deleted(json.getJSONObject("data").getString("message_id"))
            "file.expired" -> TimelineEvent.FileExpired(json.getJSONObject("data").getString("file_id"), json.getJSONObject("data").getString("message_id"))
            "device.replaced" -> TimelineEvent.DeviceReplaced
            else -> TimelineEvent.Unknown
        }
    }

    private fun describeSource(resolver: ContentResolver, uri: Uri): Source {
        var filename = "file"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                filename = cursor.getString(0)?.takeIf(String::isNotBlank) ?: filename
                if (!cursor.isNull(1)) size = cursor.getLong(1)
            }
        }
        if (size < 0) size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
        if (size < 0) throw TimelineFailure.Invalid("无法读取文件大小。")
        return Source(uri, filename, resolver.getType(uri) ?: "application/octet-stream", size)
    }

    private fun createThumbnail(resolver: ContentResolver, uri: Uri): ByteArray {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(resolver, uri)
            val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val scale = minOf(1f, 720f / max(info.size.width, info.size.height))
                decoder.setTargetSize(
                    max(1, (info.size.width * scale).roundToInt()),
                    max(1, (info.size.height * scale).roundToInt()),
                )
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val output = ByteArrayOutputStream()
            decoded.compress(Bitmap.CompressFormat.JPEG, 84, output)
            decoded.recycle()
            return output.toByteArray()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw TimelineFailure.Invalid("无法读取所选图片。")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 1440) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw TimelineFailure.Invalid("无法读取所选图片。")
        val scale = minOf(1f, 720f / max(decoded.width, decoded.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).roundToInt(), (decoded.height * scale).roundToInt(), true) else decoded
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 84, output)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        return output.toByteArray()
    }

    private fun decodeSampledBitmap(file: File, maxDimension: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val scale = minOf(1f, maxDimension.toFloat() / max(info.size.width, info.size.height))
                decoder.setTargetSize(
                    max(1, (info.size.width * scale).roundToInt()),
                    max(1, (info.size.height * scale).roundToInt()),
                )
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxDimension * 2) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun copyWithProgress(input: InputStream, output: OutputStream, total: Long, progress: (Long, Long) -> Unit) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            copied += count
            progress(copied, total)
        }
        output.flush()
    }

    private fun requireContext() = context ?: throw TimelineFailure.Invalid("当前环境无法访问系统文件。")
    private fun isPreviewableImage(mimeType: String) = mimeType.lowercase() in setOf(
        "image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp", "image/heic", "image/heif",
    )
    private fun JSONObject.optNullableString(name: String): String? = if (isNull(name)) null else optString(name).takeIf(String::isNotBlank)
    private fun urlEncode(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
    private data class HttpResponse(val status: Int, val body: String)
    private data class Source(val uri: Uri, val filename: String, val mimeType: String, val size: Long)

    private inner class StreamingRequestBody(
        private val resolver: ContentResolver,
        private val source: Source,
        private val progress: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType() = source.mimeType.toMediaType()
        override fun contentLength() = source.size
        override fun writeTo(sink: BufferedSink) {
            val input = requireNotNull(resolver.openInputStream(source.uri)) { "无法读取所选文件。" }
            input.use {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var written = 0L
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    sink.write(buffer, 0, count)
                    written += count
                    progress(written)
                }
                if (written != source.size) throw IOException("selected file size changed")
            }
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
        const val MAX_BATCH_ITEMS = 20
        const val MAX_FILE_BYTES = 314_572_800L
        const val MAX_BATCH_BYTES = 524_288_000L
        const val NORMAL_CLOSURE = 1000
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val JPEG_MEDIA_TYPE = "image/jpeg".toMediaType()
    }
}
