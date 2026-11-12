package top.steeefanie.leafreader.drive

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.steeefanie.leafreader.storage.ReaderDatabase

class DriveMigrationTest {
    @Test fun pendingQueueAndVersionBaselineSurviveDatabaseReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "queue-reopen-test.db"; context.deleteDatabase(name)
        val hash = "b".repeat(64)
        val json = BookState.canonical(BookState.empty)
        val first = Room.databaseBuilder(context, ReaderDatabase::class.java, name).build()
        try { first.drive().putState(CloudState("connection:user", hash, json, json, "\"v7\"", 7, true, error = "网络中断")) }
        finally { first.close() }
        val reopened = Room.databaseBuilder(context, ReaderDatabase::class.java, name).build()
        try {
            val record = reopened.drive().state("connection:user", hash)!!
            assertTrue(record.dirty); assertEquals(7L, record.revision); assertEquals("\"v7\"", record.etag)
            assertEquals("网络中断", record.error); assertEquals(json, record.baseJson)
        } finally { reopened.close(); context.deleteDatabase(name) }
    }
}
