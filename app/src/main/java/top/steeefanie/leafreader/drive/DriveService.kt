package top.steeefanie.leafreader.drive

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import androidx.room.withTransaction
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.steeefanie.leafreader.storage.Repository
import top.steeefanie.leafreader.storage.Progress

data class RemoteProgressChange(val owner: String, val hash: String, val progress: Progress)
data class DriveSession(val connection: DriveConnection, val client: DriveClient, val generation: Long)

class DriveService(
    private val context: Context, private val repository: Repository,
    private val scope: CoroutineScope, private val credentials: Credentials = SecureCredentials(context),
    private val transport: DriveTransport = HttpsDriveTransport(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val db = repository.db
    private val preferences = context.getSharedPreferences("drive-selection", Context.MODE_PRIVATE)
    private val selected = MutableStateFlow(preferences.getString("active_id", null))
    private val sync = ProgressSync(db, clock) { owner -> preferences.getBoolean(listKey(owner.substringBefore(':')), false) }
    private val fullSync = FullSyncQueue(preferences)
    private val downloader = BookDownloader(repository, File(context.filesDir, "cloud-downloads")) { owner, hash, action ->
        sync.withBookLock(owner, hash) {
            check(db.drive().isDeleted(owner, hash) == 0) { "该书已从云端删除，请刷新目录" }
            action()
        }
    }
    private val refreshLock = Mutex()
    private var loop: Job? = null
    @Volatile private var generation = 0L
    @Volatile private var foreground = false
    @Volatile private var readingActive = false
    private var bookmarkJob: Job? = null
    @Volatile private var backoffUntil = preferences.getLong("backoff_${selected.value}", 0L)
    val status = MutableStateFlow("未连接云盘")
    val remoteChanges = MutableSharedFlow<RemoteProgressChange>(extraBufferCapacity = 16)
    private val connectivity get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { if (foreground && !readingActive && selected.value != null) scope.launch { quietly { synchronize() } } }
    }

    init {
        repository.owner.value = preferences.getString("active_owner", "local") ?: "local"
        repository.onLocalChange = { owner, hash, urgent ->
            if (urgent && foreground && !readingActive && owner == repository.owner.value && owner != "local") {
                bookmarkJob?.cancel()
                bookmarkJob = scope.launch { delay(1500); quietly { syncPending() } }
            }
        }
        connectivity.registerDefaultNetworkCallback(networkCallback)
    }
    fun close() { loop?.cancel(); bookmarkJob?.cancel(); connectivity.unregisterNetworkCallback(networkCallback) }
    suspend fun connection(): DriveConnection? = selected.value?.let { db.drive().connection(it) }
    suspend fun profiles(): List<DriveConnection> = db.drive().connections()
    suspend fun pending(): Int = connection()?.let { db.drive().pending(it.ownerKey) + if (fullSync.pending(it.id)) 1 else 0 } ?: 0
    suspend fun conflicts(): List<CloudConflict> = connection()?.let { db.drive().conflicts(it.ownerKey) } ?: emptyList()

    suspend fun bind(baseUrl: String, poll: BindingPoll): DriveConnection {
        require(poll.status == "approved")
        val identity = requireNotNull(poll.identity)
        val url = DriveClient.normalizeOrigin(baseUrl)
        val old = db.drive().connections().firstOrNull { it.baseUrl == url && it.userId == identity.userId }
        val connection = DriveConnection(old?.id ?: UUID.randomUUID().toString(), url, identity.userId, identity.userName,
            identity.role, identity.deviceId, identity.deviceName, identity.rootId)
        // Credentials must reach durable encrypted storage before the connection is activated.
        credentials.write(connection.id, requireNotNull(poll.token))
        try { db.drive().putConnection(connection) }
        catch (e: Exception) { credentials.clear(connection.id); throw e }
        select(connection)
        return connection
    }
    suspend fun select(connection: DriveConnection?) {
        generation++
        bookmarkJob?.cancel()
        loop?.cancel(); loop = null
        val owner = connection?.ownerKey ?: "local"
        check(preferences.edit().putString("active_id", connection?.id).putString("active_owner", owner).commit()) { "连接配置保存失败" }
        selected.value = connection?.id; repository.owner.value = owner
        backoffUntil = preferences.getLong("backoff_${connection?.id}", 0L)
        status.value = if (connection == null) "本地阅读模式" else "${connection.userName} · ${connection.deviceName}"
        if (foreground && connection != null && loop?.isActive != true) foreground(true)
    }
    private suspend fun session(): DriveSession {
        val id = selected.value ?: error("请先连接云盘")
        val c = db.drive().connection(id) ?: error("连接配置不存在")
        if (c.status != "connected") error("设备需要重新绑定；离线书籍和待同步记录仍保留")
        val token = try { credentials.read(c.id) } catch (e: Exception) {
            db.drive().connectionStatus(c.id, "needs_rebind"); error("无法解密设备凭证，请重新绑定")
        } ?: error("缺少设备凭证，请重新绑定")
        return DriveSession(c, DriveClient(c.baseUrl, token, transport), generation)
    }
    suspend fun guard(s: DriveSession) {
        currentCoroutineContext().ensureActive()
        check(selected.value == s.connection.id && generation == s.generation && db.drive().connection(s.connection.id)?.status == "connected") { "连接已切换或失效，操作已停止" }
        check(clock() >= backoffUntil) { "云盘限流中，请按 Retry-After 等待后重试" }
    }
    private suspend fun validate(s: DriveSession): DriveIdentity {
        guard(s)
        val identity = s.client.me()
        if (identity.userId != s.connection.userId || identity.deviceId != s.connection.deviceId) {
            db.drive().connectionStatus(s.connection.id, "needs_rebind")
            error("云盘身份与绑定记录不一致，已停止同步")
        }
        guard(s)
        db.withTransaction {
            guard(s)
            val current = db.drive().connection(s.connection.id) ?: error("连接已移除")
            db.drive().putConnection(current.copy(userName = identity.userName, role = identity.role, deviceName = identity.deviceName, rootId = identity.rootId))
        }
        return identity
    }
    /** Called only after an explicit per-book confirmation, never by the refresh/sync loop.
     *  [keepLocal] removes only the cloud copy; the local book then stays local-only so refresh won't re-upload it. */
    suspend fun deleteBook(hash: String, owner: String, entry: RemoteEntry? = null, keepLocal: Boolean = false) {
        DriveClient.checkHash(hash)
        val s = session()
        check(s.connection.ownerKey == owner && (entry == null || entry.ownerKey == owner)) { "账号已切换，请重新打开书籍菜单" }
        handled(s) {
            val identity = validate(s)
            check("book-delete-v1" in identity.capabilities) { "云盘尚未支持书籍删除接口，本机副本已保留" }
            check(identity.canDeleteBooks) { "此设备尚未获得云端删除权限，请在云盘重新授权" }
            sync.withBookLock(owner, hash) {
                guard(s)
                // Repeating after a lost response targets the same immutable book content.
                val requestId = UUID.nameUUIDFromBytes("book-delete-v1:$owner:$hash:${entry?.id.orEmpty()}:${entry?.version.orEmpty()}".toByteArray(Charsets.UTF_8)).toString()
                val receipt = s.client.deleteBook(hash, requestId, entry)
                // A committed remote deletion must be recorded even if the screen/account changes.
                withContext(NonCancellable) {
                    recordBookDeletion(db, owner, hash, java.time.Instant.parse(receipt.deletedAt).toEpochMilli())
                    if (keepLocal) db.drive().putLocalOnly(LocalOnlyBook(owner, hash))
                    else repository.book(hash, owner)?.let { book ->
                        try { repository.remove(book, owner) }
                        catch (e: Exception) { throw java.io.IOException("云端书籍及进度已删除，本机副本未移除，请选择“仅移除本地副本”重试", e) }
                    }
                }
                if (selected.value == s.connection.id) status.value = if (keepLocal) "已移除云端副本，本地副本保留" else "已删除本地副本、云端书籍及对应进度文件"
            }
        }
    }
    /** Menu setting only; the actual upload happens on the next manual refresh of the cloud page. */
    suspend fun setLocalOnly(owner: String, hash: String, localOnly: Boolean) {
        DriveClient.checkHash(hash)
        if (localOnly) db.drive().putLocalOnly(LocalOnlyBook(owner, hash)) else db.drive().removeLocalOnly(owner, hash)
    }
    private fun listKey(id: String) = "book_list_${id}"
    private suspend fun refreshCloudBooks(s: DriveSession, identity: DriveIdentity) {
        if (!identity.canListBooks) { preferences.edit().putBoolean(listKey(s.connection.id), false).apply(); return }
        var restart = 0
        while (true) {
            try {
                val all = mutableListOf<RemoteEntry>(); val cursors = mutableSetOf<String>(); var cursor: String? = null
                do {
                    guard(s)
                    val (items, next) = s.client.books(s.connection.ownerKey, cursor)
                    all += items.filter { it.name != ".reader" && !it.name.endsWith(".json", true) && !it.format.equals("json", true) }
                    require(all.size <= 100000) { "云端书库过大" }
                    cursor = next
                    if (cursor != null) require(cursors.add(cursor)) { "书籍列表游标重复" }
                } while (cursor != null)
                guard(s)
                db.withTransaction {
                    db.drive().markAllFilesOld(s.connection.ownerKey)
                    db.drive().putEntries(all)
                }
                preferences.edit().putBoolean(listKey(s.connection.id), true).apply()
                return
            } catch (e: DriveException) {
                if (e.status == 409 && e.code == "cursor_expired" && restart++ == 0) continue
                throw e
            }
        }
    }
    /** Uploads local books that are not in the cloud and not marked local-only. Triggered by the cloud page refresh. */
    suspend fun uploadPending() {
        val s = session()
        handled(s) {
            val identity = validate(s)
            refreshCloudBooks(s, identity)
            val owner = s.connection.ownerKey
            val onCloud = db.drive().cloudHashes(owner).toSet()
            val skip = db.drive().localOnly(owner).toSet()
            val pending = repository.dao.ownedBooks(owner).filter { it.hash !in onCloud && it.hash !in skip }
            if (pending.isEmpty()) return@handled
            if (!identity.canUploadBooks) { status.value = "云盘尚未支持上传，${pending.size} 本书等待上传"; return@handled }
            var done = 0; val failed = mutableListOf<String>()
            for ((index, book) in pending.withIndex()) {
                guard(s)
                status.value = "正在上传 ${index + 1}/${pending.size}：${book.title}"
                try {
                    val source = repository.file(book)
                    val name = book.title.replace(Regex("[\\x00-\\x1f\\x7f/\\\\]"), "_").trim().take(200).ifBlank { "未命名书籍" } + "." + book.format
                    val requestId = UUID.nameUUIDFromBytes("book-upload-v1:$owner:${book.hash}".toByteArray(Charsets.UTF_8)).toString()
                    val entry = s.client.uploadBook(owner, source, book.hash, name, book.format, requestId)
                    withContext(NonCancellable) {
                        db.withTransaction {
                            db.drive().putEntries(listOf(entry))
                            if (db.drive().isDeleted(owner, book.hash) > 0) { db.drive().clearDeletion(owner, book.hash); db.drive().forgetRemoteBase(owner, book.hash) }
                            db.drive().markDirty(owner, book.hash)
                        }
                    }
                    done++
                    runCatching { syncOne(s, book.hash) }
                } catch (cancel: CancellationException) { throw cancel }
                catch (e: DriveException) { if (e.status == 401 || e.status == 429) throw e; failed += book.title }
                catch (_: Exception) { failed += book.title }
            }
            status.value = if (failed.isEmpty()) "已上传 $done 本书" else "已上传 $done 本，${failed.size} 本失败：${failed.take(3).joinToString("、")}"
        }
    }
    suspend fun revoke(localOnly: Boolean = false) {
        val current = connection() ?: return
        if (!localOnly && current.status == "connected") {
            val s = session()
            try { s.client.revoke() }
            catch (e: DriveException) { if (e.status != 401) throw e }
        }
        // Local data remains isolated under this profile; no reassignment to another user.
        generation++; credentials.clear(current.id)
        db.drive().connectionStatus(current.id, "unbound")
        select(null)
    }
    suspend fun refreshDirectory(parent: String? = null): EntryPage {
        val s = session()
        return handled(s) {
            validate(s)
            var restart = 0
            while (true) {
                try {
                    val all = mutableListOf<RemoteEntry>(); val cursors = mutableSetOf<String>()
                    var cursor: String? = null; var first: EntryPage? = null
                    do {
                        guard(s)
                        val page = s.client.entries(s.connection.ownerKey, parent, cursor)
                        if (first == null) first = page
                        require(page.parentId == first.parentId && (parent == null || page.parentId == parent) && page.entries.all { it.parentId == page.parentId }) { "目录响应不匹配" }
                        all += page.entries.filter { it.name != ".reader" && !it.name.endsWith(".json", true) && !it.format.equals("json", true) }
                        require(all.size <= 20000 && all.map { it.id }.distinct().size == all.size) { "目录过大或包含重复条目" }
                        cursor = page.next
                        if (cursor != null) require(cursors.add(cursor)) { "目录分页游标重复" }
                    } while (cursor != null)
                    guard(s)
                    val result = requireNotNull(first).copy(entries = all, next = null)
                    db.withTransaction {
                        db.drive().markDirectoryOld(s.connection.ownerKey, result.parentId)
                        db.drive().putEntries(all)
                    }
                    return@handled result
                } catch (e: DriveException) {
                    if (e.status == 409 && e.code == "cursor_expired" && restart++ == 0) continue
                    throw e
                }
            }
            @Suppress("UNREACHABLE_CODE") error("无法刷新目录")
        }
    }
    suspend fun cachedDirectory(parent: String): List<RemoteEntry> = connection()?.let {
        db.drive().entries(it.ownerKey, parent).filter { entry -> entry.name != ".reader" && !entry.name.endsWith(".json", true) && !entry.format.equals("json", true) }
    } ?: emptyList()
    suspend fun download(id: String, validateBook: suspend (File) -> Pair<String, String>, progress: (Long, Long) -> Unit): String {
        val s = session()
        return handled(s) {
            validate(s)
            val hash = downloader.download(s.client, s.connection, id, { guard(s) }, validateBook, progress)
            try { syncOne(s, hash) }
            catch (cancel: CancellationException) { throw cancel }
            catch (e: DriveException) { if (e.status == 401) throw e else status.value = "书籍已下载，进度同步未完成" }
            catch (_: Exception) { status.value = "书籍已下载，进度同步未完成" }
            hash
        }
    }
    private suspend fun syncOne(s: DriveSession, hash: String, inIndex: Boolean = false) {
        val before = repository.progress(hash, s.connection.ownerKey)
        sync.sync(s.connection, s.client, hash, inIndex) { guard(s) }
        val after = repository.progress(hash, s.connection.ownerKey)
        if (after != null && before?.copy(updatedAt = after.updatedAt) != after) remoteChanges.emit(RemoteProgressChange(s.connection.ownerKey, hash, after))
    }
    suspend fun synchronize() = synchronize(resumeOnly = false)

    private suspend fun synchronize(resumeOnly: Boolean) = refreshLock.withLock {
        val s = session()
        if (resumeOnly && !fullSync.due(s.connection.id, clock())) return@withLock
        // Persist before the first request, including index fetching. A killed process can resume safely.
        fullSync.request(s.connection.id)
        try {
            handled(s) {
                guard(s)
                status.value = "正在校验设备和同步进度…"
                val identity = validate(s)
                runCatching { refreshCloudBooks(s, identity) }.onFailure { if (it is CancellationException) throw it }
                val hashes = linkedSetOf<String>(); val cursors = mutableSetOf<String>()
                var cursor: String? = null; var restarted = false
                while (true) {
                    try {
                        guard(s)
                        val page = s.client.index(cursor)
                        page.items.forEach { item ->
                            val local = db.drive().state(s.connection.ownerKey, item.hash)
                            if (local == null || local.etag != item.etag || local.dirty || local.error.isNotEmpty()) hashes += item.hash
                        }
                        require(hashes.size <= 100000) { "进度索引过大" }
                        cursor = page.next
                        if (cursor == null) break
                        require(cursors.add(cursor)) { "进度索引游标重复" }
                    } catch (e: DriveException) {
                        if (e.status == 409 && e.code == "cursor_expired" && !restarted) {
                            cursor = null; hashes.clear(); cursors.clear(); restarted = true; continue
                        }
                        throw e
                    }
                }
                val indexHashes = hashes.toSet()
                hashes += db.drive().dirty(s.connection.ownerKey).map { it.hash }
                for (hash in hashes) { guard(s); syncOne(s, hash, hash in indexHashes) }
                guard(s)
                val states = db.drive().stateSummaries(s.connection.ownerKey)
                val pullsToRetry = states.filter { !it.dirty && it.error.isNotBlank() }
                if (pullsToRetry.isEmpty()) fullSync.complete(s.connection.id)
                else fullSync.request(s.connection.id, maxOf(clock() + 30000, pullsToRetry.minOf { it.retryAt }))
                val current = db.drive().connection(s.connection.id) ?: return@handled
                if (states.none { it.error.isNotBlank() }) db.drive().putConnection(current.copy(lastSync = clock()))
                status.value = if (pullsToRetry.isNotEmpty()) "远端进度未拉完，将在前台自动重试；本地记录已保留"
                    else "同步结束 · 待同步 ${db.drive().pending(s.connection.ownerKey)} 项 · 错误 ${states.count { it.error.isNotBlank() }} 项"
            }
        } catch (cancel: CancellationException) {
            throw cancel // Keep the durable request; foreground restoration will re-read the index.
        } catch (e: Exception) {
            // Only this connection's request is postponed; account switches never reassign its work.
            withContext(NonCancellable) {
                fullSync.request(s.connection.id, maxOf(clock() + 30000,
                    preferences.getLong("backoff_${s.connection.id}", 0L)))
            }
            throw e
        }
    }
    internal suspend fun syncPending() {
        val s = session()
        if (clock() < backoffUntil) return
        if (fullSync.due(s.connection.id, clock())) {
            synchronize(resumeOnly = true)
            return
        }
        handled(s) {
            val pending = db.drive().dirty(s.connection.ownerKey).filter { it.retryAt <= clock() }
            if (pending.isEmpty()) return@handled
            validate(s)
            pending.forEach { syncOne(s, it.hash) }
            status.value = "待同步 ${db.drive().pending(s.connection.ownerKey)} 项"
        }
    }
    fun setReadingActive(active: Boolean) {
        readingActive = active
        if (active) bookmarkJob?.cancel()
    }
    fun bookOpened(owner: String, hash: String) {
        readingActive = true
        if (owner == "local") return
        scope.launch { quietly {
            val s = session()
            if (s.connection.ownerKey == owner) handled(s) { validate(s); syncOne(s, hash) }
        } }
    }
    fun bookClosed(owner: String) {
        readingActive = false
        if (owner != "local") scope.launch { quietly { syncPending() } }
    }
    fun foreground(started: Boolean) {
        foreground = started
        if (started && selected.value != null && loop?.isActive != true) loop = scope.launch {
            if (selected.value != null && !readingActive) {
                quietly { synchronize() }
                quietly { refreshDirectory() }
            }
            while (isActive) { delay(30000); if (selected.value != null && !readingActive) quietly { syncPending() } }
        } else if (!started) { loop?.cancel(); loop = null; bookmarkJob?.cancel() }
    }
    private suspend fun <T> handled(s: DriveSession, action: suspend () -> T): T {
        try { return action() }
        catch (cancel: CancellationException) { throw cancel }
        catch (e: DriveException) {
            if (e.status == 401) {
                db.drive().connectionStatus(s.connection.id, "revoked")
                credentials.clear(s.connection.id)
                if (selected.value == s.connection.id) { generation++; status.value = "设备授权失效，请重新绑定；离线记录已保留" }
            } else if (e.status == 429) {
                val until = clock() + e.retryAfterMillis.coerceAtLeast(5000)
                preferences.edit().putLong("backoff_${s.connection.id}", until).commit()
                if (selected.value == s.connection.id) backoffUntil = until
            }
            throw e
        }
    }
    private suspend fun quietly(action: suspend () -> Unit) {
        try { action() }
        catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) { status.value = when (e) {
            is DriveException -> e.message.orEmpty()
            is IllegalStateException, is IllegalArgumentException -> e.message ?: "响应校验失败，已保留本地数据"
            else -> "暂时无法连接云盘，离线数据与同步队列已保留"
        } }
    }
}
