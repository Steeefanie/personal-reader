package top.steeefanie.leafreader.storage

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RepositoryTest {
    @Test fun mergeKeepsNewerAndRollsBackConflictingBatch() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        try {
            val repository = Repository(context, db)
            val hash = "a".repeat(64)
            val locator = """{"href":"chapter.xhtml","type":"application/xhtml+xml"}"""
            val local = Progress(hash, "epub", locator, 200)
            db.dao().insert(Book(hash, "书籍", "epub", "$hash.epub", 1))
            db.dao().putProgress(local)
            repository.merge(Exchange(listOf(local.copy(updatedAt = 100)), emptyList()))
            assertEquals(200L, db.dao().progress(hash)!!.updatedAt)
            try {
                repository.merge(Exchange(listOf(local.copy(updatedAt = 300)), listOf(
                    Bookmark(hash, "one", "pdf", "冲突", locator, 300)
                )))
                fail("Conflicting batch must fail")
            } catch (_: IllegalArgumentException) { }
            assertEquals(200L, db.dao().progress(hash)!!.updatedAt)
            assertTrue(db.dao().allBookmarks().isEmpty())
        } finally { db.close() }
    }
}
