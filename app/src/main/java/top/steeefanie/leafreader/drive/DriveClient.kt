package top.steeefanie.leafreader.drive

import java.io.*
import java.net.URI
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import org.json.JSONObject
import top.steeefanie.leafreader.storage.Exchange
import top.steeefanie.leafreader.storage.ProgressExchange

data class BindingRequest(val id: String, val deviceCode: String, val verificationUrl: String, val confirmationCode: String, val expiresIn: Int, val interval: Int)
data class DriveIdentity(val userId: String, val userName: String, val role: String, val deviceId: String, val deviceName: String, val rootId: String,
    val scopes: Set<String> = setOf("books:read", "reading-state:read", "reading-state:write"),
    val capabilities: Set<String> = emptySet(),
) {
    val canDeleteBooks get() = "books:delete" in scopes && "book-delete-v1" in capabilities
    val canUploadBooks get() = "books:write" in scopes && "book-upload-v1" in capabilities
    val canListBooks get() = "book-list-v1" in capabilities
}

data class BookDeletionReceipt(val hash: String, val fileId: String, val deletedAt: String)

data class BindingPoll(val status: String, val interval: Int = 5, val token: String? = null, val identity: DriveIdentity? = null)
data class EntryPage(val parentId: String, val parentName: String, val parentParentId: String?, val entries: List<RemoteEntry>, val next: String?)
data class ProgressIndexItem(val hash: String, val etag: String)
data class IndexPage(val items: List<ProgressIndexItem>, val next: String?)
data class RemoteState(val state: Exchange, val etag: String)

class DriveException(val status: Int, val code: String, val retryAfterMillis: Long = 0) : IOException(
    when (code) {
        "device_revoked" -> "设备授权已撤销，请重新绑定"
        "cursor_expired" -> "目录已变化，请重新刷新"
        "progress_not_found" -> "远端尚无该书进度"
        "book_deleted" -> "该书已从云端删除，停止同步；本机记录仍保留"
        "book_not_found" -> "云端没有找到这本书，本机副本已保留"
        "book_ambiguous" -> "云端书籍对应关系不唯一，未执行删除"
        "book_changed" -> "云端书籍已变化，请刷新后重试"
        "delete_not_supported" -> "云盘尚未支持书籍删除接口，本机副本已保留"
        "delete_forbidden" -> "此设备尚未获得云端删除权限，请在云盘重新授权"
        else -> "云盘请求失败（$status，${code.take(80)}）"
    }
)

class WireResponse(val status: Int, val headers: Map<String, String>, val body: InputStream, private val release: () -> Unit = {}) : Closeable {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
    fun bytes(limit: Int = ProgressExchange.MAX_BYTES): ByteArray {
        val result = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            val n = body.read(buffer); if (n < 0) break
            require(result.size() + n <= limit) { "云盘响应超过大小限制" }; result.write(buffer, 0, n)
        }
        return result.toByteArray()
    }
    override fun close() { try { body.close() } finally { release() } }
}

interface DriveTransport {
    suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray? = null): WireResponse
    /** Book uploads stream from disk; test transports may keep the simple in-memory fallback. */
    suspend fun upload(method: String, url: String, headers: Map<String, String>, file: File): WireResponse =
        request(method, url, headers, file.readBytes())
}

class HttpsDriveTransport : DriveTransport {
    override suspend fun upload(method: String, url: String, headers: Map<String, String>, file: File): WireResponse = suspendCancellableCoroutine { continuation ->
        val connection = URI(url).toURL().openConnection() as HttpsURLConnection
        continuation.invokeOnCancellation { connection.disconnect() }
        Dispatchers.IO.dispatch(continuation.context, Runnable { try {
            if (!continuation.isActive) { connection.disconnect(); return@Runnable }
            connection.instanceFollowRedirects = false
            // The server hashes the whole body before answering; allow time after the last byte.
            connection.connectTimeout = 8000; connection.readTimeout = 120000
            connection.useCaches = false; connection.requestMethod = method
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            connection.doOutput = true; connection.setFixedLengthStreamingMode(file.length())
            connection.outputStream.use { output -> file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    if (!continuation.isActive) throw CancellationException()
                    val n = input.read(buffer); if (n < 0) break
                    output.write(buffer, 0, n)
                }
            } }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream ?: ByteArrayInputStream(byteArrayOf()) else connection.inputStream
            val response = WireResponse(status, connection.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(",") }, stream) { connection.disconnect() }
            continuation.resume(response) { _, value, _ -> runCatching { value.close() } }
        } catch (e: Exception) { connection.disconnect(); if (continuation.isActive) continuation.resumeWithException(e) } })
    }
    override suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): WireResponse = suspendCancellableCoroutine { continuation ->
        val connection = URI(url).toURL().openConnection() as HttpsURLConnection
        continuation.invokeOnCancellation { connection.disconnect() }
        Dispatchers.IO.dispatch(continuation.context, Runnable { try {
            if (!continuation.isActive) { connection.disconnect(); return@Runnable }
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8000; connection.readTimeout = 15000
            connection.useCaches = false; connection.requestMethod = method
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (body != null) { connection.doOutput = true; connection.setFixedLengthStreamingMode(body.size); connection.outputStream.use { it.write(body) } }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream ?: ByteArrayInputStream(byteArrayOf()) else connection.inputStream
            val response = WireResponse(status, connection.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(",") }, stream) { connection.disconnect() }
            continuation.resume(response) { _, value, _ -> runCatching { value.close() } }
        } catch (e: Exception) { connection.disconnect(); if (continuation.isActive) continuation.resumeWithException(e) } })
    }
}

/** All authenticated URLs are generated from the validated origin; redirects are never followed. */
class DriveClient(baseUrl: String, private val token: String? = null, private val transport: DriveTransport = HttpsDriveTransport()) {
    val origin: String = normalizeOrigin(baseUrl)
    private val api = "$origin/api/reader/v1"
    companion object {
        fun normalizeOrigin(value: String): String {
            val raw = value.trim()
            val candidate = if (raw.contains("://")) raw else "https://$raw"
            val uri = URI(candidate)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.orEmpty().trim('/').isEmpty()) { "请填写 HTTPS 云盘根地址，不包含路径、凭证或参数" }
            return URI("https", null, uri.host.lowercase(), if (uri.port == 443) -1 else uri.port, null, null, null).toASCIIString().trimEnd('/')
        }
        private fun part(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace(".", "%2E")
        fun checkHash(hash: String) { require(hash.matches(Regex("[a-f0-9]{64}"))) { "书籍哈希无效" } }
    }
    private fun headers(extra: Map<String, String>): Map<String, String> = buildMap {
        put("Accept", "application/json"); put("Cache-Control", "no-store"); put("Accept-Encoding", "identity")
        token?.let { require(it.none { ch -> ch == '\r' || ch == '\n' }); put("Authorization", "Bearer $it") }
        putAll(extra)
    }
    suspend fun wire(method: String, path: String, extra: Map<String, String> = emptyMap(), json: String? = null): WireResponse = try {
        withTimeout(30000) { transport.request(method, api + path, headers(extra + if (json != null) mapOf("Content-Type" to "application/json") else emptyMap()), json?.toByteArray(Charsets.UTF_8)) }
    } catch (_: TimeoutCancellationException) { throw IOException("云盘请求超时") }

    fun failure(response: WireResponse): DriveException {
        val code = try { JSONObject(response.bytes(65536).toString(Charsets.UTF_8)).optJSONObject("error")?.optString("code") } catch (_: Exception) { null }
        val retry = response.header("Retry-After")?.let { header ->
            header.toLongOrNull()?.coerceAtLeast(0)?.coerceAtMost(86400)?.times(1000)
                ?: runCatching { ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis() }.getOrNull()
        }?.coerceIn(0, 86400000) ?: 0
        return DriveException(response.status, code?.takeIf { it.matches(Regex("[a-z0-9_]{1,80}")) } ?: "http_${response.status}", retry)
    }
    private suspend fun json(method: String, path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        wire(method, path, json = body?.toString()).use { response ->
            if (response.status !in 200..299) throw failure(response)
            JSONObject(response.bytes().toString(Charsets.UTF_8))
        }
    }
    private fun identity(json: JSONObject): DriveIdentity {
        val user = json.getJSONObject("user"); val device = json.getJSONObject("device")
        require(user.getString("role") in setOf("admin", "user")) { "不支持的用户角色" }
        val required = setOf("books:read", "reading-state:read", "reading-state:write")
        val scope = json.getJSONArray("scope")
        val granted = (0 until scope.length()).map { scope.getString(it) }.toSet()
        require(granted.containsAll(required) && granted.all { it in required || it == "books:delete" || it == "books:write" }) { "设备权限与阅读器约定不一致" }
        val advertised = json.optJSONArray("capabilities")
        val capabilities = if (advertised == null) emptySet() else (0 until advertised.length()).map { advertised.getString(it) }.toSet()
        return DriveIdentity(user.getString("id"), user.getString("name"), user.getString("role"), device.getString("id"), device.getString("name"), json.getString("books_root_id"), granted, capabilities).also {
            require(it.userId.isNotBlank() && it.deviceId.isNotBlank() && it.rootId.isNotBlank()) { "身份响应不完整" }
        }
    }
    suspend fun createBinding(version: String): BindingRequest = json("POST", "/bindings", JSONObject().put("client_name", "图书").put("client_version", version).put("device_hint", "Android 电子书阅读器")).let {
        val verification = URI(it.getString("verification_uri_complete"))
        require(verification.scheme == "https" && verification.host.equals(URI(origin).host, true) && (if (verification.port == 443) -1 else verification.port) == URI(origin).port && verification.userInfo == null && verification.fragment == null) { "授权页面必须属于同一 HTTPS 云盘" }
        val deviceCode = it.getString("device_code")
        require(deviceCode.isNotBlank() && !verification.toASCIIString().contains(deviceCode)) { "绑定响应不安全，临时设备凭证不能放入二维码" }
        BindingRequest(it.getString("binding_id"), it.getString("device_code"), verification.toASCIIString(), it.getString("confirmation_code"), it.getInt("expires_in").coerceIn(1, 900), it.getInt("interval").coerceIn(1, 300))
    }
    suspend fun poll(binding: BindingRequest): BindingPoll = json("POST", "/bindings/poll", JSONObject().put("binding_id", binding.id).put("device_code", binding.deviceCode)).let {
        val status = it.getString("status")
        require(status in setOf("approved", "pending", "slow_down", "denied", "expired", "consumed")) { "未知授权状态" }
        if (status == "approved") {
            require(it.getString("token_type") == "Bearer") { "不支持的凭证类型" }
            BindingPoll(status, token = it.getString("access_token"), identity = identity(it))
        } else BindingPoll(status, it.optInt("interval", binding.interval).coerceIn(1, 300))
    }
    suspend fun me(): DriveIdentity = json("GET", "/me").let {
        require(it.getInt("api_version") == 1) { "不支持的云盘接口版本" }
        val versions = it.getJSONArray("progress_schema_versions")
        require((0 until versions.length()).any { index -> versions.getInt(index) == 1 }) { "云盘不支持当前进度格式" }
        identity(it)
    }
    /** One request deletes exactly one book file and its JSON. A missing/partial receipt is not success. */
    suspend fun deleteBook(hash: String, requestId: String, entry: RemoteEntry? = null): BookDeletionReceipt = withContext(Dispatchers.IO) {
        checkHash(hash)
        require(runCatching { java.util.UUID.fromString(requestId).toString() == requestId }.getOrDefault(false))
        entry?.let { require(it.kind == "file" && it.hash == hash && it.id.isNotBlank() && it.version.isNotBlank()) }
        val query = entry?.let { "?file_id=${part(it.id)}" }.orEmpty()
        val extra = mapOf("Idempotency-Key" to requestId) + (entry?.let { mapOf("If-Match" to "\"${it.version}\"") } ?: emptyMap())
        wire("DELETE", "/books/$hash$query", extra).use { response ->
            if (response.status != 200) throw failure(response)
            val json = JSONObject(response.bytes(65536).toString(Charsets.UTF_8))
            require(json.getString("book_hash") == hash && json.getBoolean("book_file_deleted") && json.getBoolean("progress_file_deleted")) { "云盘未确认书籍及进度文件均已删除，本机副本已保留" }
            val fileId = json.getString("file_id")
            require(fileId.isNotBlank() && (entry == null || fileId == entry.id)) { "云盘删除回执与所选书籍不一致" }
            val deletedAt = json.getString("deleted_at")
            require(deletedAt.endsWith("Z") && runCatching { java.time.Instant.parse(deletedAt) }.isSuccess) { "云盘删除回执时间无效" }
            BookDeletionReceipt(hash, fileId, deletedAt)
        }
    }
    suspend fun revoke() = withContext(Dispatchers.IO) { wire("DELETE", "/me/device").use { if (it.status != 204) throw failure(it) } }
    private fun next(json: JSONObject): String? {
        require(json.has("next_cursor")) { "分页响应缺少 next_cursor" }
        return if (json.isNull("next_cursor")) null else json.getString("next_cursor").also { require(it.isNotBlank() && it.length <= 8192) }
    }
    fun entry(json: JSONObject, owner: String): RemoteEntry {
        val kind = json.getString("kind"); require(kind == "file" || kind == "directory")
        return RemoteEntry(owner, json.getString("id"), json.getString("parent_id"), kind, json.getString("name"),
            if (kind == "file") json.getLong("size").also { require(it >= 0) } else 0,
            json.optString("format"), json.optString("content_sha256"), json.optString("content_version"), json.getString("modified_at")).also {
            require(it.id.isNotBlank() && it.parentId.isNotBlank() && it.name.isNotBlank())
            if (kind == "file") { checkHash(it.hash); require(it.version.isNotBlank() && it.version.none { c -> c == '"' || c == '\r' || c == '\n' }) }
        }
    }
    suspend fun entries(owner: String, parent: String?, cursor: String?): EntryPage {
        val query = buildList { add("limit=100"); parent?.let { add("parent_id=${part(it)}") }; cursor?.let { add("cursor=${part(it)}") } }.joinToString("&")
        val json = json("GET", "/entries?$query"); val p = json.getJSONObject("parent"); val entries = json.getJSONArray("entries")
        require(entries.length() <= 100)
        return EntryPage(p.getString("id"), p.getString("name"), if (p.isNull("parent_id")) null else p.getString("parent_id"), (0 until entries.length()).map { entry(entries.getJSONObject(it), owner) }, next(json))
    }
    suspend fun file(owner: String, id: String): RemoteEntry = entry(json("GET", "/files/${part(id)}"), owner).also { require(it.id == id && it.kind == "file") }
    /** book-list-v1: every file under the authorised library root, flattened, with content hashes. */
    suspend fun books(owner: String, cursor: String?): Pair<List<RemoteEntry>, String?> {
        val json = json("GET", "/books?limit=200${cursor?.let { "&cursor=${part(it)}" }.orEmpty()}")
        val items = json.getJSONArray("items"); require(items.length() <= 200)
        return (0 until items.length()).map { entry(items.getJSONObject(it), owner).also { e -> require(e.kind == "file") } } to next(json)
    }
    /** book-upload-v1: the server verifies the body hash and returns the existing entry for duplicate content. */
    suspend fun uploadBook(owner: String, source: File, hash: String, name: String, format: String, requestId: String): RemoteEntry = withContext(Dispatchers.IO) {
        checkHash(hash)
        require(format == "epub" || format == "pdf") { "仅支持上传 EPUB 或 PDF" }
        require(runCatching { java.util.UUID.fromString(requestId).toString() == requestId }.getOrDefault(false))
        require(source.isFile && source.length() > 0) { "本机书籍文件缺失" }
        val extra = mapOf(
            "Content-Type" to if (format == "epub") "application/epub+zip" else "application/pdf",
            "X-Content-SHA256" to hash,
            "X-File-Name" to part(name),
            "Idempotency-Key" to requestId,
        )
        transport.upload("POST", "$api/books", headers(extra), source).use { response ->
            if (response.status != 200 && response.status != 201) throw failure(response)
            val result = entry(JSONObject(response.bytes(65536).toString(Charsets.UTF_8)), owner)
            require(result.kind == "file" && result.hash == hash) { "云盘上传回执与本机书籍不一致" }
            result
        }
    }
    suspend fun content(id: String, offset: Long, version: String): WireResponse = wire("GET", "/files/${part(id)}/content", if (offset > 0) mapOf("Range" to "bytes=$offset-", "If-Range" to "\"$version\"") else emptyMap())
    suspend fun index(cursor: String?): IndexPage {
        val json = json("GET", "/progress?limit=100${cursor?.let { "&cursor=${part(it)}" }.orEmpty()}")
        val items = json.getJSONArray("items"); require(items.length() <= 100)
        return IndexPage((0 until items.length()).map { items.getJSONObject(it).let { row -> ProgressIndexItem(row.getString("book_hash").also(::checkHash), row.getString("etag").also(::checkEtag)) } }, next(json))
    }
    private fun checkEtag(etag: String) { require(etag.length in 2..512 && etag.startsWith('"') && etag.endsWith('"') && etag.none { it == '\r' || it == '\n' }) { "云盘未提供有效的进度 ETag" } }
    suspend fun readState(hash: String): RemoteState? = withContext(Dispatchers.IO) {
        checkHash(hash)
        wire("GET", "/progress/$hash").use {
            if (it.status == 404) { val error = failure(it); if (error.code == "progress_not_found") return@withContext null else throw error }
            if (it.status != 200) throw failure(it)
            val etag = it.header("ETag") ?: error("进度响应缺少 ETag"); checkEtag(etag)
            RemoteState(BookState.decode(hash, it.bytes().toString(Charsets.UTF_8)), etag)
        }
    }
    suspend fun writeState(hash: String, state: Exchange, etag: String?): RemoteState = withContext(Dispatchers.IO) {
        checkHash(hash); val body = BookState.canonical(state); BookState.decode(hash, body)
        etag?.let(::checkEtag)
        wire("PUT", "/progress/$hash", if (etag == null) mapOf("If-None-Match" to "*") else mapOf("If-Match" to etag), body).use {
            if (it.status != 200 && it.status != 201) throw failure(it)
            val next = it.header("ETag") ?: error("保存响应缺少 ETag"); checkEtag(next)
            val bytes = it.bytes()
            RemoteState(if (bytes.isEmpty() && it.status == 201) state else BookState.decode(hash, bytes.toString(Charsets.UTF_8)), next)
        }
    }
}
