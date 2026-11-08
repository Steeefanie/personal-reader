package top.steeefanie.leafreader.drive

import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import top.steeefanie.leafreader.storage.Repository

fun contentHash(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream -> val buffer = ByteArray(65536); while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Range handling is deliberately transport-independent so interrupted/changed responses are testable. */
object DownloadBody {
    suspend fun receive(response: WireResponse, part: File, size: Long, version: String, guard: suspend () -> Unit, progress: (Long) -> Unit) {
        val offset = part.takeIf { it.exists() }?.length() ?: 0
        if (response.status == 416) {
            require(response.header("Content-Range") == "bytes */$size" && offset == size) { "下载范围失效，请重新下载" }
            response.header("ETag")?.let { require(it == "\"$version\"") { "书籍内容版本已变化" } }
            return
        }
        require(response.header("ETag") == "\"$version\"") { "书籍内容版本已变化，请刷新后重试" }
        val expected: Long
        val append: Boolean
        when (response.status) {
            200 -> { expected = size; append = false }
            206 -> {
                val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty()) ?: error("下载响应范围无效")
                val (start, end, total) = range.destructured
                require(start.toLong() == offset && total.toLong() == size && end.toLong() >= offset && end.toLong() < size) { "续传内容不匹配" }
                expected = end.toLong() - offset + 1; append = true
            }
            else -> error("无法处理的下载状态")
        }
        response.header("Content-Length")?.toLongOrNull()?.let { require(it == expected) { "下载长度与范围不匹配" } }
        var received = 0L; var lastNotification = 0L
        guard()
        FileOutputStream(part, append).use { output ->
            val buffer = ByteArray(65536)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = response.body.read(buffer); if (n < 0) break
                require(received + n <= expected) { "下载内容超过声明长度" }
                output.write(buffer, 0, n); received += n
                val now = System.currentTimeMillis()
                if (now - lastNotification >= 1000) { guard(); progress((if (append) offset else 0) + received); lastNotification = now }
            }
            output.fd.sync()
        }
        if (received != expected) throw java.io.IOException("下载中断，已保留分段，可稍后续传")
        progress(part.length())
    }
}

class BookDownloader(
    private val repository: Repository, private val directory: File,
    private val serialize: suspend (String, String, suspend () -> String) -> String = { _, _, action -> action() },
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    suspend fun download(client: DriveClient, connection: DriveConnection, fileId: String,
                         guard: suspend () -> Unit, validate: suspend (File) -> Pair<String, String>, progress: (Long, Long) -> Unit): String = withContext(Dispatchers.IO) {
        val owner = connection.ownerKey
        locks.getOrPut("$owner:$fileId") { Mutex() }.withLock {
            guard()
            val metadata = client.file(owner, fileId)
            require(metadata.format in setOf("epub", "pdf")) { "暂不支持此书籍格式" }
            serialize(owner, metadata.hash) {
                guard()
                repository.db.drive().putEntries(listOf(metadata))
                repository.dao.book(metadata.hash)?.let { cached ->
                    val file = repository.file(cached)
                    if (file.exists() && contentHash(file) == metadata.hash) {
                        guard()
                        if (repository.attachCached(metadata.hash, owner)) return@serialize metadata.hash
                    }
                }
                directory.mkdirs()
                val id = MessageDigest.getInstance("SHA-256").digest("$owner:$fileId".toByteArray()).joinToString("") { "%02x".format(it) }
                val partial = File(directory, "$id.part"); val sidecar = File(directory, "$id.json")
                val signature = JSONObject().put("version", metadata.version).put("hash", metadata.hash).put("size", metadata.size).toString()
                if (!sidecar.exists() || sidecar.readText() != signature || (partial.exists() && partial.length() > metadata.size)) {
                    partial.delete(); sidecar.writeText(signature)
                }
                if (!partial.exists()) partial.createNewFile()
                var attempts = 0
                while (partial.length() < metadata.size || attempts == 0) {
                    attempts++
                    guard(); val offset = partial.length()
                    client.content(fileId, offset, metadata.version).use { response ->
                        if (response.status !in setOf(200, 206, 416)) throw client.failure(response)
                        try { DownloadBody.receive(response, partial, metadata.size, metadata.version, guard) { progress(it, metadata.size) } }
                        catch (e: IllegalArgumentException) { partial.delete(); sidecar.delete(); throw e }
                    }
                    if (partial.length() == metadata.size) break
                    require(partial.length() > offset) { "续传没有推进，请重新下载" }
                }
                guard()
                if (contentHash(partial) != metadata.hash) { partial.delete(); sidecar.delete(); error("下载校验失败，文件未导入。请刷新书籍元数据后重试") }
                repository.importBook(Uri.fromFile(partial), validate, owner)
                guard(); partial.delete(); sidecar.delete()
                metadata.hash
            }
        }
    }
}
