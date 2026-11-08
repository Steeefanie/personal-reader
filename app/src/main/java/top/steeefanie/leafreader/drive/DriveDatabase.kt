package top.steeefanie.leafreader.drive

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "book_access", primaryKeys = ["ownerKey", "hash"])
data class BookAccess(val ownerKey: String, val hash: String, val lastRead: Long = 0)

@Entity(tableName = "drive_connections")
data class DriveConnection(
    @PrimaryKey val id: String,
    val baseUrl: String, val userId: String, val userName: String, val role: String,
    val deviceId: String, val deviceName: String, val rootId: String,
    val status: String = "connected", val lastSync: Long = 0,
) { val ownerKey get() = "$id:$userId" }

@Entity(tableName = "cloud_states", primaryKeys = ["ownerKey", "hash"])
data class CloudState(
    val ownerKey: String, val hash: String, val currentJson: String,
    val baseJson: String? = null, val etag: String? = null,
    val revision: Long = 0, val dirty: Boolean = false, val lastSyncedAt: Long = 0,
    val error: String = "", val retryAt: Long = 0,
)

@Entity(tableName = "cloud_conflicts")
data class CloudConflict(@PrimaryKey val id: String, val ownerKey: String, val hash: String, val json: String, val createdAt: Long)

@Entity(tableName = "remote_entries", primaryKeys = ["ownerKey", "id"])
data class RemoteEntry(
    val ownerKey: String, val id: String, val parentId: String, val kind: String, val name: String,
    val size: Long = 0, val format: String = "", val hash: String = "", val version: String = "",
    val modifiedAt: String = "", val deleted: Boolean = false,
)

/** A server deletion cannot be undone by a stale/offline progress upload. */
@Entity(tableName = "deleted_cloud_books", primaryKeys = ["ownerKey", "hash"])
data class DeletedCloudBook(val ownerKey: String, val hash: String, val confirmedAt: Long)

/** Per-book opt-out from uploading. Absent means the default: upload on the next manual cloud refresh. */
@Entity(tableName = "local_only_books", primaryKeys = ["ownerKey", "hash"])
data class LocalOnlyBook(val ownerKey: String, val hash: String)

@Dao
abstract class DriveDao {
    @Upsert abstract suspend fun putLocalOnly(value: LocalOnlyBook)
    @Query("DELETE FROM local_only_books WHERE ownerKey=:owner AND hash=:hash") abstract suspend fun removeLocalOnly(owner: String, hash: String)
    @Query("SELECT COUNT(*) FROM local_only_books WHERE ownerKey=:owner AND hash=:hash") abstract suspend fun isLocalOnly(owner: String, hash: String): Int
    @Query("SELECT hash FROM local_only_books WHERE ownerKey=:owner") abstract fun observeLocalOnly(owner: String): Flow<List<String>>
    @Query("SELECT hash FROM local_only_books WHERE ownerKey=:owner") abstract suspend fun localOnly(owner: String): List<String>
    /** Content hashes known to exist in the cloud library (from the full book list or browsed directories). */
    @Query("SELECT DISTINCT hash FROM remote_entries WHERE ownerKey=:owner AND kind='file' AND deleted=0 AND hash!=''") abstract fun observeCloudHashes(owner: String): Flow<List<String>>
    @Query("SELECT DISTINCT hash FROM remote_entries WHERE ownerKey=:owner AND kind='file' AND deleted=0 AND hash!=''") abstract suspend fun cloudHashes(owner: String): List<String>
    @Query("SELECT COUNT(*) FROM remote_entries WHERE ownerKey=:owner AND hash=:hash AND kind='file' AND deleted=0") abstract suspend fun isOnCloud(owner: String, hash: String): Int
    @Query("SELECT * FROM remote_entries WHERE ownerKey=:owner AND hash=:hash AND kind='file' AND deleted=0 LIMIT 1") abstract suspend fun cloudFile(owner: String, hash: String): RemoteEntry?
    @Query("UPDATE remote_entries SET deleted=1 WHERE ownerKey=:owner AND kind='file'") abstract suspend fun markAllFilesOld(owner: String)
    @Query("DELETE FROM deleted_cloud_books WHERE ownerKey=:owner AND hash=:hash") abstract suspend fun clearDeletion(owner: String, hash: String)
    @Query("UPDATE cloud_states SET dirty=1,error='',retryAt=0 WHERE ownerKey=:owner AND hash=:hash") abstract suspend fun markDirty(owner: String, hash: String)
    @Query("UPDATE cloud_states SET etag=NULL,baseJson=NULL WHERE ownerKey=:owner AND hash=:hash") abstract suspend fun forgetRemoteBase(owner: String, hash: String)
    @Upsert abstract suspend fun putDeletion(value: DeletedCloudBook)
    @Query("SELECT COUNT(*) FROM deleted_cloud_books WHERE ownerKey=:owner AND hash=:hash") abstract suspend fun isDeleted(owner: String, hash: String): Int
    @Query("UPDATE cloud_states SET dirty=0,error='',retryAt=0,etag=NULL,baseJson=NULL WHERE ownerKey=:owner AND hash=:hash") abstract suspend fun stopDeletedSync(owner: String, hash: String)
    @Query("DELETE FROM cloud_conflicts WHERE ownerKey=:owner AND hash=:hash") abstract suspend fun removeConflicts(owner: String, hash: String)
    @Query("UPDATE remote_entries SET deleted=1 WHERE ownerKey=:owner AND hash=:hash AND kind='file'") abstract suspend fun markBookDeleted(owner: String, hash: String)
    @Upsert abstract suspend fun grant(access: BookAccess)
    @Query("SELECT COUNT(*) FROM book_access WHERE ownerKey = :owner AND hash = :hash") abstract suspend fun hasBook(owner: String, hash: String): Int
    @Query("SELECT COUNT(*) FROM book_access WHERE hash = :hash") abstract suspend fun references(hash: String): Int
    @Query("DELETE FROM book_access WHERE ownerKey = :owner AND hash = :hash") abstract suspend fun removeAccess(owner: String, hash: String)
    @Query("UPDATE book_access SET lastRead = :time WHERE ownerKey = :owner AND hash = :hash") abstract suspend fun touch(owner: String, hash: String, time: Long)
    @Upsert abstract suspend fun putConnection(connection: DriveConnection)
    @Query("SELECT * FROM drive_connections WHERE id = :id") abstract suspend fun connection(id: String): DriveConnection?
    @Query("SELECT * FROM drive_connections") abstract suspend fun connections(): List<DriveConnection>
    @Query("UPDATE drive_connections SET status = :status WHERE id = :id") abstract suspend fun connectionStatus(id: String, status: String)
    @Upsert abstract suspend fun putState(state: CloudState)
    @Query("SELECT ownerKey,hash,substr(currentJson,1,65536) AS currentJson,substr(baseJson,1,65536) AS baseJson,etag,revision,dirty,lastSyncedAt,error,retryAt FROM cloud_states WHERE ownerKey = :owner") abstract fun observeStatePrefixes(owner: String): Flow<List<CloudState>>
    @Query("SELECT ownerKey,hash,substr(currentJson,1,65536) AS currentJson,substr(baseJson,1,65536) AS baseJson,etag,revision,dirty,lastSyncedAt,error,retryAt FROM cloud_states WHERE ownerKey = :owner AND hash = :hash") abstract suspend fun statePrefix(owner: String, hash: String): CloudState?
    @Query("SELECT ownerKey,hash,substr(currentJson,1,65536) AS currentJson,substr(baseJson,1,65536) AS baseJson,etag,revision,dirty,lastSyncedAt,error,retryAt FROM cloud_states WHERE ownerKey = :owner") abstract suspend fun statePrefixes(owner: String): List<CloudState>
    @Query("SELECT ownerKey,hash,substr(currentJson,1,65536) AS currentJson,substr(baseJson,1,65536) AS baseJson,etag,revision,dirty,lastSyncedAt,error,retryAt FROM cloud_states WHERE ownerKey = :owner AND dirty = 1") abstract suspend fun dirtyPrefixes(owner: String): List<CloudState>
    @Query("SELECT COUNT(*) FROM cloud_states WHERE ownerKey = :owner AND dirty = 1") abstract suspend fun pending(owner: String): Int
    @Upsert abstract suspend fun putConflict(conflict: CloudConflict)
    @Query("SELECT id,ownerKey,hash,substr(json,1,65536) AS json,createdAt FROM cloud_conflicts WHERE ownerKey = :owner ORDER BY createdAt DESC") abstract suspend fun conflictPrefixes(owner: String): List<CloudConflict>
    @Upsert abstract suspend fun putEntries(entries: List<RemoteEntry>)
    @Query("UPDATE remote_entries SET deleted = 1 WHERE ownerKey = :owner AND parentId = :parent") abstract suspend fun markDirectoryOld(owner: String, parent: String)
    @Query("SELECT * FROM remote_entries WHERE ownerKey = :owner AND parentId = :parent AND deleted = 0 ORDER BY kind, name") abstract suspend fun entries(owner: String, parent: String): List<RemoteEntry>
    @Query("SELECT * FROM remote_entries WHERE ownerKey = :owner AND id = :id") abstract suspend fun entry(owner: String, id: String): RemoteEntry?

    // CursorWindow is much smaller than the wire limit on some Android versions. Keep each
    // SQL cell bounded; assemble within a transaction so updates cannot mix document versions.
    @Query("SELECT substr(currentJson,:start,65536) FROM cloud_states WHERE ownerKey=:owner AND hash=:hash")
    abstract suspend fun currentChunk(owner: String, hash: String, start: Int): String?
    @Query("SELECT substr(baseJson,:start,65536) FROM cloud_states WHERE ownerKey=:owner AND hash=:hash")
    abstract suspend fun baseChunk(owner: String, hash: String, start: Int): String?
    @Query("SELECT substr(json,:start,65536) FROM cloud_conflicts WHERE ownerKey=:owner AND id=:id")
    abstract suspend fun conflictChunk(owner: String, id: String, start: Int): String?
    @Query("SELECT ownerKey,hash,'' AS currentJson,NULL AS baseJson,etag,revision,dirty,lastSyncedAt,error,retryAt FROM cloud_states WHERE ownerKey=:owner")
    abstract suspend fun stateSummaries(owner: String): List<CloudState>

    private suspend fun document(prefix: String, chunk: suspend (Int) -> String?): String {
        // SQLite substr positions count Unicode code points, not Kotlin UTF-16 code units.
        if (prefix.codePointCount(0, prefix.length) < 65536) return prefix
        val result = StringBuilder(prefix)
        var start = 65537
        while (true) {
            val next = chunk(start) ?: error("本地进度记录在读取期间丢失")
            require(result.length + next.length <= 4 * 8 * 1024 * 1024) { "本地进度记录过大" }
            result.append(next)
            if (next.codePointCount(0, next.length) < 65536) break
            start += 65536
        }
        return result.toString()
    }
    private suspend fun assemble(row: CloudState) = row.copy(
        currentJson = document(row.currentJson) { currentChunk(row.ownerKey, row.hash, it) },
        baseJson = row.baseJson?.let { prefix -> document(prefix) { baseChunk(row.ownerKey, row.hash, it) } })

    @Transaction
    open suspend fun state(owner: String, hash: String): CloudState? = statePrefix(owner, hash)?.let { assemble(it) }
    @Transaction
    open suspend fun states(owner: String): List<CloudState> = statePrefixes(owner).map { assemble(it) }
    @Transaction
    open suspend fun dirty(owner: String): List<CloudState> = dirtyPrefixes(owner).map { assemble(it) }
    fun observeStates(owner: String): Flow<List<CloudState>> = observeStatePrefixes(owner).map { states(owner) }
    @Transaction
    open suspend fun conflicts(owner: String): List<CloudConflict> = conflictPrefixes(owner).map { row ->
        row.copy(json = document(row.json) { conflictChunk(owner, row.id, it) })
    }
}

val DRIVE_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS book_access (ownerKey TEXT NOT NULL, hash TEXT NOT NULL, lastRead INTEGER NOT NULL, PRIMARY KEY(ownerKey,hash))")
        db.execSQL("INSERT INTO book_access(ownerKey,hash,lastRead) SELECT 'local',hash,lastRead FROM books")
        db.execSQL("CREATE TABLE IF NOT EXISTS drive_connections (id TEXT NOT NULL PRIMARY KEY, baseUrl TEXT NOT NULL, userId TEXT NOT NULL, userName TEXT NOT NULL, role TEXT NOT NULL, deviceId TEXT NOT NULL, deviceName TEXT NOT NULL, rootId TEXT NOT NULL, status TEXT NOT NULL, lastSync INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS cloud_states (ownerKey TEXT NOT NULL, hash TEXT NOT NULL, currentJson TEXT NOT NULL, baseJson TEXT, etag TEXT, revision INTEGER NOT NULL, dirty INTEGER NOT NULL, lastSyncedAt INTEGER NOT NULL, error TEXT NOT NULL, retryAt INTEGER NOT NULL, PRIMARY KEY(ownerKey,hash))")
        db.execSQL("CREATE TABLE IF NOT EXISTS cloud_conflicts (id TEXT NOT NULL PRIMARY KEY, ownerKey TEXT NOT NULL, hash TEXT NOT NULL, json TEXT NOT NULL, createdAt INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS remote_entries (ownerKey TEXT NOT NULL, id TEXT NOT NULL, parentId TEXT NOT NULL, kind TEXT NOT NULL, name TEXT NOT NULL, size INTEGER NOT NULL, format TEXT NOT NULL, hash TEXT NOT NULL, version TEXT NOT NULL, modifiedAt TEXT NOT NULL, deleted INTEGER NOT NULL, PRIMARY KEY(ownerKey,id))")
    }
}

val BOOK_DELETION_MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS deleted_cloud_books (ownerKey TEXT NOT NULL, hash TEXT NOT NULL, confirmedAt INTEGER NOT NULL, PRIMARY KEY(ownerKey,hash))")
    }
}

val LOCAL_ONLY_MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS local_only_books (ownerKey TEXT NOT NULL, hash TEXT NOT NULL, PRIMARY KEY(ownerKey,hash))")
    }
}
