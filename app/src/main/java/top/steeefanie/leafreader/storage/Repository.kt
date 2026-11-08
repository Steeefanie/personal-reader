package top.steeefanie.leafreader.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import top.steeefanie.leafreader.drive.*

class Repository(private val context: Context, val db: ReaderDatabase) {
    private val mutations = Mutex()
    val dao = db.dao()
    val owner = MutableStateFlow("local")
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val books = owner.flatMapLatest { dao.observeOwnedBooks(it) }
    var onLocalChange: (String, String, Boolean) -> Unit = { _, _, _ -> }
    private val directory = File(context.filesDir, "books").apply { mkdirs() }
    fun file(book: Book) = File(directory, book.fileName)
    fun jsonFile(hash: String) = File(directory, "$hash.json")

    private fun writeBookJsonFile(hash: String, data: Exchange) {
        runCatching {
            val json = BookState.canonical(data)
            val target = jsonFile(hash)
            val tmp = File(directory, "$hash.json.tmp")
            tmp.writeText(json, Charsets.UTF_8)
            if (!tmp.renameTo(target)) { target.writeText(json, Charsets.UTF_8); tmp.delete() }
        }
    }

    suspend fun ensureBookState(hash: String, ownerKey: String = owner.value) = withContext(Dispatchers.IO) {
        if (!jsonFile(hash).exists()) {
            val current = Exchange(listOfNotNull(progress(hash, ownerKey)), bookmarks(hash, ownerKey))
            writeBookJsonFile(hash, current)
        }
    }

    suspend fun importBook(uri: Uri, validate: suspend (File) -> Pair<String, String>, ownerKey: String = owner.value): Boolean = withContext(Dispatchers.IO) {
        mutations.withLock {
            val temporary = File.createTempFile("import-", ".tmp", directory)
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                context.contentResolver.openInputStream(uri)?.use { input -> temporary.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        output.write(buffer, 0, count); digest.update(buffer, 0, count)
                    }
                } } ?: error("无法读取所选文件")
                require(temporary.length() > 0) { "文件为空" }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                val cached = dao.book(hash)
                if (cached != null && file(cached).exists()) {
                    db.presentation().forget(hash)
                    val already = db.drive().hasBook(ownerKey, hash) > 0
                    if (!already) db.drive().grant(BookAccess(ownerKey, hash))
                    ensureBookState(hash, ownerKey)
                    return@withLock !already
                }
                val (format, metadataTitle) = validate(temporary)
                val sourceName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "未命名书籍"
                val title = metadataTitle.ifBlank { sourceName.substringBeforeLast('.') }
                val destination = File(directory, "$hash.$format")
                withContext(NonCancellable) {
                    check(temporary.renameTo(destination)) { "无法保存书籍，请检查剩余空间" }
                    try { db.withTransaction {
                        dao.insert(Book(hash, title, format, destination.name, System.currentTimeMillis()))
                        db.drive().grant(BookAccess(ownerKey, hash))
                    } } catch (e: Exception) { if (cached == null) destination.delete(); throw e }
                    ensureBookState(hash, ownerKey)
                }
                true
            } finally { temporary.delete() }
        }
    }

    suspend fun remove(book: Book, ownerKey: String = owner.value) = withContext(Dispatchers.IO) { mutations.withLock {
        require(db.drive().hasBook(ownerKey, book.hash) > 0) { "此空间没有该书籍，请刷新书库" }
        // Keep progress and bookmark records so reimporting the same content restores them.
        val source = file(book)
        val trash = File(directory, "${book.hash}.trash")
        val lastReference = db.drive().references(book.hash) <= 1
        if (lastReference && source.exists()) check(source.renameTo(trash)) { "无法移除文件" }
        try { db.withTransaction {
            db.drive().removeAccess(ownerKey, book.hash)
            if (lastReference) dao.removeBook(book.hash)
        } }
        catch (e: Exception) { trash.renameTo(source); throw e }
        trash.delete()
    } }

    suspend fun book(hash: String, ownerKey: String = owner.value): Book? =
        if (db.drive().hasBook(ownerKey, hash) > 0) dao.book(hash) else null

    suspend fun attachCached(hash: String, ownerKey: String): Boolean = withContext(Dispatchers.IO) { mutations.withLock {
        val cached = dao.book(hash) ?: return@withLock false
        if (!file(cached).exists()) return@withLock false
        if (db.drive().hasBook(ownerKey, hash) == 0) db.drive().grant(BookAccess(ownerKey, hash))
        ensureBookState(hash, ownerKey)
        true
    } }

    suspend fun progress(hash: String, ownerKey: String = owner.value): Progress? =
        if (ownerKey == "local") dao.progress(hash) else cloudData(ownerKey, hash).progress.firstOrNull()
    suspend fun bookmarks(hash: String, ownerKey: String = owner.value): List<Bookmark> =
        if (ownerKey == "local") dao.bookmarks(hash) else cloudData(ownerKey, hash).bookmarks
    fun observeBookmarks(hash:String,ownerKey:String):Flow<List<Bookmark>> =
        (if(ownerKey=="local")dao.observeBookmarks(hash) else
            db.drive().observeStatePrefixes(ownerKey).map { bookmarks(hash,ownerKey) }).distinctUntilChanged()
    private suspend fun cloudData(ownerKey: String, hash: String) = db.drive().state(ownerKey, hash)?.let { BookState.decodeStored(hash, it.currentJson) } ?: BookState.empty

    suspend fun snapshot(ownerKey: String = owner.value): Exchange = db.withTransaction {
        if (ownerKey == "local") Exchange(dao.allProgress(), dao.allBookmarks()) else {
            val states = db.drive().states(ownerKey).map { BookState.decodeStored(it.hash, it.currentJson) }
            Exchange(states.flatMap { it.progress }, states.flatMap { it.bookmarks })
        }
    }
    private suspend fun changed(ownerKey: String, hash: String, data: Exchange) {
        val json = BookState.canonical(data)
        BookState.decode(hash, json) // Validate before committing; invalid data must roll back.
        val before = db.drive().state(ownerKey, hash)
        db.drive().putState((before ?: CloudState(ownerKey, hash, "")).copy(
            currentJson = json, revision = (before?.revision ?: 0L) + 1, dirty = db.drive().isDeleted(ownerKey, hash) == 0, error = "", retryAt = 0))
    }
    suspend fun saveProgress(value: Progress, ownerKey: String = owner.value, flushFile: Boolean = true) {
        var updated = false
        var latestState: Exchange? = null
        db.withTransaction {
            val before = progress(value.hash, ownerKey)
            if (before != null && (before.updatedAt > value.updatedAt || BookState.equivalent(Exchange(listOf(before.copy(updatedAt = value.updatedAt)), emptyList()), Exchange(listOf(value), emptyList())))) {
                if (flushFile) db.drive().touch(ownerKey, value.hash, value.updatedAt)
                return@withTransaction
            }
            val marks = bookmarks(value.hash, ownerKey)
            val state = Exchange(listOf(value), marks)
            if (ownerKey == "local") dao.putProgress(value)
            else changed(ownerKey, value.hash, state)
            if (flushFile) db.drive().touch(ownerKey, value.hash, value.updatedAt)
            latestState = state
            updated = true
        }
        if (flushFile) {
            val stateToWrite = latestState ?: Exchange(listOfNotNull(progress(value.hash, ownerKey)), bookmarks(value.hash, ownerKey))
            writeBookJsonFile(value.hash, stateToWrite)
        }
        if (updated) onLocalChange(ownerKey, value.hash, false)
    }
    suspend fun saveBookmark(value: Bookmark, ownerKey: String = owner.value) {
        var latestState: Exchange? = null
        db.withTransaction {
            if (ownerKey == "local") {
                dao.putBookmark(value)
                latestState = Exchange(listOfNotNull(dao.progress(value.hash)), dao.bookmarks(value.hash))
            } else {
                val data = cloudData(ownerKey, value.hash)
                val next = data.copy(bookmarks = data.bookmarks.filterNot { it.id == value.id } + value)
                changed(ownerKey, value.hash, next)
                latestState = next
            }
        }
        latestState?.let { writeBookJsonFile(value.hash, it) }
        onLocalChange(ownerKey, value.hash, false)
    }

    suspend fun merge(data: Exchange, ownerKey: String = owner.value) {
      if (ownerKey != "local") {
        val hashes = (data.progress.map { it.hash } + data.bookmarks.map { it.hash }).distinct()
        db.withTransaction { hashes.forEach { hash ->
            val current = cloudData(ownerKey, hash)
            val incoming = Exchange(data.progress.filter { it.hash == hash }, data.bookmarks.filter { it.hash == hash })
            val p = listOfNotNull(current.progress.firstOrNull(), incoming.progress.firstOrNull()).maxByOrNull { it.updatedAt }
            val marks = (current.bookmarks + incoming.bookmarks).groupBy { it.id }.values.map { group -> group.maxBy { it.updatedAt } }
            val merged = Exchange(listOfNotNull(p), marks)
            BookState.decode(hash, BookState.canonical(merged))
            changed(ownerKey, hash, merged)
        } }
        hashes.forEach { onLocalChange(ownerKey, it, true) }
        return
      }
      db.withTransaction {
        data.progress.forEach { incoming ->
            val local = dao.progress(incoming.hash)
            require(local == null || local.format == incoming.format) { "进度格式不一致" }
            require(dao.book(incoming.hash)?.format?.let { it == incoming.format } != false) { "进度与本地书籍格式不一致" }
            if (local == null || incoming.updatedAt > local.updatedAt) dao.putProgress(incoming)
        }
        val existing = dao.allBookmarks().associateBy { it.hash to it.id }
        data.bookmarks.forEach { incoming ->
            require(dao.book(incoming.hash)?.format?.let { it == incoming.format } != false) { "书签与本地书籍格式不一致" }
            val local = existing[incoming.hash to incoming.id]
            require(local == null || local.format == incoming.format) { "书签格式不一致" }
            if (local == null || incoming.updatedAt > local.updatedAt) dao.putBookmark(incoming)
        }
      }
    }

    /** Called only after the user explicitly accepts importing unowned local history. */
    suspend fun adoptLocal(ownerKey: String) {
        require(ownerKey != "local")
        mutations.withLock { db.withTransaction {
            dao.ownedBooks("local").forEach { db.drive().grant(BookAccess(ownerKey, it.hash, it.lastRead)) }
            merge(Exchange(dao.allProgress(), dao.allBookmarks()), ownerKey)
        } }
    }
}
