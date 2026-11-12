package top.steeefanie.leafreader.drive

import androidx.room.withTransaction
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.steeefanie.leafreader.storage.ReaderDatabase

/** [listed] tells whether the server's complete book list is cached for an owner; only then can
 *  "file not in the cloud" be trusted to hold back progress JSON for local-only / not-yet-uploaded books. */
class ProgressSync(private val db: ReaderDatabase, private val clock: () -> Long = System::currentTimeMillis,
                   private val listed: (String) -> Boolean = { false }) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    suspend fun <T> withBookLock(owner: String, hash: String, action: suspend () -> T): T = withContext(Dispatchers.IO) {
        locks.getOrPut("$owner:$hash") { Mutex() }.withLock { action() }
    }
    private suspend fun heldBack(owner: String, hash: String): Boolean {
        if (db.drive().isLocalOnly(owner, hash) > 0) return true
        // A local book whose file is not in the cloud waits for its upload; dirty state is kept for later.
        return listed(owner) && db.drive().hasBook(owner, hash) > 0 && db.drive().isOnCloud(owner, hash) == 0
    }
    suspend fun sync(connection: DriveConnection, client: DriveClient, hash: String, inIndex: Boolean = false, guard: suspend () -> Unit) {
        val owner = connection.ownerKey
        withBookLock(owner, hash) {
            if (db.drive().isDeleted(owner, hash) > 0) return@withBookLock
            if (!inIndex && heldBack(owner, hash)) return@withBookLock
            var lastError: Exception? = null
            repeat(3) { attempt ->
                guard()
                val snapshot = db.drive().state(owner, hash)
                if ((snapshot?.retryAt ?: 0) > clock()) return@withBookLock
                val local = snapshot?.let { BookState.decode(hash, it.currentJson) } ?: BookState.empty
                try {
                    val remote = client.readState(hash)
                    val knownFormat = db.dao().book(hash)?.format ?: local.progress.firstOrNull()?.format ?: local.bookmarks.firstOrNull()?.format
                    require(knownFormat == null || remote == null || (remote.state.progress.map { it.format } + remote.state.bookmarks.map { it.format }).all { it == knownFormat }) { "远端进度格式与书籍不一致" }
                    val baseline = if (remote == null) BookState.empty else snapshot?.baseJson?.let { BookState.decode(hash, it) } ?: BookState.empty
                    val result = StateMerge.merge(baseline, local, remote?.state ?: BookState.empty)
                    val accepted = when {
                        remote != null && BookState.equivalent(result.state, remote.state) -> remote
                        remote == null && BookState.equivalent(result.state, BookState.empty) -> null
                        heldBack(owner, hash) -> remote
                        else -> client.writeState(hash, result.state, remote?.etag)
                    }
                    guard()
                    db.withTransaction {
                        if (db.drive().isDeleted(owner, hash) > 0) return@withTransaction
                        val latest = db.drive().state(owner, hash)
                        val acceptedState = accepted?.state ?: BookState.empty
                        val finalMerge = if (latest == null || latest.revision == (snapshot?.revision ?: 0L)) MergeResult(acceptedState)
                            else StateMerge.merge(local, BookState.decode(hash, latest.currentJson), acceptedState)
                        listOfNotNull(result.localConflict, finalMerge.localConflict).forEach {
                            db.drive().putConflict(CloudConflict(UUID.randomUUID().toString(), owner, hash, BookState.canonical(it), clock()))
                        }
                        val finalJson = BookState.canonical(finalMerge.state)
                        // A union of two individually valid documents may exceed record/byte limits.
                        BookState.decode(hash, finalJson)
                        db.drive().putState(CloudState(owner, hash, finalJson,
                            BookState.canonical(acceptedState), accepted?.etag, latest?.revision ?: 0,
                            !BookState.equivalent(finalMerge.state, acceptedState), clock()))
                    }
                    return@withBookLock
                } catch (cancel: CancellationException) { throw cancel }
                catch (e: Exception) {
                    if (e is DriveException && e.status == 410 && e.code == "book_deleted") {
                        guard()
                        recordBookDeletion(db, owner, hash, clock())
                        return@withBookLock
                    }
                    lastError = e
                    val transient = e is IOException && (e !is DriveException || e.status == 412 || e.status >= 500)
                    if (transient && attempt < 2) delay(250L * (attempt + 1))
                    else {
                        val now = clock()
                        val retry = if (e is DriveException && e.retryAfterMillis > 0) e.retryAfterMillis else if (e is DriveException && e.status in setOf(403, 413, 422, 428)) 300000 else 30000
                        db.withTransaction {
                            val current = db.drive().state(owner, hash) ?: CloudState(owner, hash, BookState.canonical(local))
                            db.drive().putState(current.copy(error = when (e) {
                                is DriveException -> e.message.orEmpty()
                                is IllegalArgumentException, is IllegalStateException -> e.message ?: "进度校验失败，已保留本地数据"
                                else -> "网络或响应错误，已保留本地数据"
                            }, retryAt = now + retry))
                        }
                        throw e
                    }
                }
            }
            throw lastError ?: IOException("同步未完成")
        }
    }
}
