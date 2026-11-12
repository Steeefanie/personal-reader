package top.steeefanie.leafreader.storage

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import top.steeefanie.leafreader.drive.*
import top.steeefanie.leafreader.library.*

@Entity(tableName = "books")
data class Book(
    @PrimaryKey val hash: String,
    val title: String,
    val format: String,
    val fileName: String,
    val importedAt: Long,
    val lastRead: Long = 0,
)

@Entity(tableName = "progress")
data class Progress(
    @PrimaryKey val hash: String,
    val format: String,
    val locator: String,
    val updatedAt: Long,
    val pdfOffset: Float = 0f,
    val pdfZoom: Float = 1f,
)

@Entity(tableName = "bookmarks", primaryKeys = ["hash", "id"])
data class Bookmark(
    val hash: String,
    val id: String,
    val format: String,
    val label: String,
    val locator: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Dao
interface ReaderDao {
    @Query("SELECT * FROM books ORDER BY lastRead DESC, importedAt DESC")
    fun observeBooks(): Flow<List<Book>>
    @Query("SELECT b.hash,b.title,b.format,b.fileName,b.importedAt,a.lastRead FROM books b INNER JOIN book_access a ON a.hash=b.hash WHERE a.ownerKey=:owner ORDER BY a.lastRead DESC,b.importedAt DESC")
    fun observeOwnedBooks(owner: String): Flow<List<Book>>
    @Query("SELECT b.hash,b.title,b.format,b.fileName,b.importedAt,a.lastRead FROM books b INNER JOIN book_access a ON a.hash=b.hash WHERE a.ownerKey=:owner ORDER BY a.lastRead DESC,b.importedAt DESC")
    suspend fun ownedBooks(owner: String): List<Book>
    @Query("SELECT * FROM books WHERE hash = :hash") suspend fun book(hash: String): Book?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(book: Book): Long
    @Query("UPDATE books SET lastRead = :time WHERE hash = :hash") suspend fun touch(hash: String, time: Long)
    @Query("DELETE FROM books WHERE hash = :hash") suspend fun removeBook(hash: String)
    @Query("SELECT * FROM progress WHERE hash = :hash") suspend fun progress(hash: String): Progress?
    @Query("SELECT * FROM progress") suspend fun allProgress(): List<Progress>
    @Query("SELECT * FROM progress") fun observeProgress(): Flow<List<Progress>>
    @Upsert suspend fun putProgress(progress: Progress)
    @Query("SELECT * FROM bookmarks WHERE hash = :hash ORDER BY updatedAt DESC") suspend fun bookmarks(hash: String): List<Bookmark>
    @Query("SELECT * FROM bookmarks WHERE hash = :hash ORDER BY updatedAt DESC") fun observeBookmarks(hash:String):Flow<List<Bookmark>>
    @Query("SELECT * FROM bookmarks") suspend fun allBookmarks(): List<Bookmark>
    @Upsert suspend fun putBookmark(bookmark: Bookmark)
}

@Database(entities = [Book::class, Progress::class, Bookmark::class, BookAccess::class, DriveConnection::class, CloudState::class, CloudConflict::class, RemoteEntry::class, BookMetadata::class, DeletedCloudBook::class, LocalOnlyBook::class], version = 1, exportSchema = false)
abstract class ReaderDatabase : RoomDatabase() {
    abstract fun dao(): ReaderDao
    abstract fun drive(): DriveDao
    abstract fun presentation(): PresentationDao
}
