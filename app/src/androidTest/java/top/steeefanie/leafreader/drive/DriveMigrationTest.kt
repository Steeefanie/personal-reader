package top.steeefanie.leafreader.drive

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.steeefanie.leafreader.storage.ReaderDatabase
import top.steeefanie.leafreader.library.PRESENTATION_MIGRATION_2_3

class DriveMigrationTest {
    @Test fun versionThreeUpgradeAndDeletionMarkerSurviveReopen() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="migration-v3-test.db";context.deleteDatabase(name)
        val owner="account:user";val hash="d".repeat(64)
        val original=Room.databaseBuilder(context,ReaderDatabase::class.java,name).build()
        original.drive().putState(CloudState(owner,hash,BookState.canonical(BookState.empty),dirty=true))
        original.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use {db ->
            db.execSQL("DROP TABLE local_only_books");db.execSQL("DROP TABLE deleted_cloud_books");db.execSQL("DROP TABLE room_master_table");db.version=3
        }
        var db=Room.databaseBuilder(context,ReaderDatabase::class.java,name).addMigrations(BOOK_DELETION_MIGRATION_3_4, LOCAL_ONLY_MIGRATION_4_5).build()
        try {
            assertTrue(db.drive().state(owner,hash)!!.dirty)
            recordBookDeletion(db,owner,hash,123);db.close()
            db=Room.databaseBuilder(context,ReaderDatabase::class.java,name).build()
            assertEquals(1,db.drive().isDeleted(owner,hash));assertFalse(db.drive().state(owner,hash)!!.dirty)
            assertEquals(0,db.drive().isDeleted("other-account",hash))
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun versionTwoUpgradeKeepsAccountQueueAndProgress() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="migration-v2-test.db";context.deleteDatabase(name)
        val original=Room.databaseBuilder(context,ReaderDatabase::class.java,name).build()
        val hash="c".repeat(64);val json=BookState.canonical(BookState.empty)
        original.drive().putState(CloudState("account:user",hash,json,json,"\"v9\"",9,true,error="离线"))
        original.drive().grant(BookAccess("account:user",hash,123))
        original.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use{db->
            db.execSQL("DROP TABLE local_only_books");db.execSQL("DROP TABLE deleted_cloud_books");db.execSQL("DROP TABLE book_metadata");db.execSQL("DROP TABLE room_master_table");db.version=2
        }
        val upgraded=Room.databaseBuilder(context,ReaderDatabase::class.java,name).addMigrations(PRESENTATION_MIGRATION_2_3, BOOK_DELETION_MIGRATION_3_4, LOCAL_ONLY_MIGRATION_4_5).build()
        try{
            val row=upgraded.drive().state("account:user",hash)!!
            assertTrue(row.dirty);assertEquals(9L,row.revision);assertEquals(json,row.baseJson);assertEquals("离线",row.error)
            assertEquals(1,upgraded.drive().hasBook("account:user",hash));assertNull(upgraded.presentation().get(hash))
            upgraded.presentation().put(top.steeefanie.leafreader.library.BookMetadata(hash,author="迁移后作者"))
            assertEquals("迁移后作者",upgraded.presentation().get(hash)!!.author)
        }finally{upgraded.close();context.deleteDatabase(name)}
    }

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
    @Test fun versionOneUpgradePreservesUnownedBooksAndProgress() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-test.db"; context.deleteDatabase(name)
        val hash = "a".repeat(64)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            db.execSQL("CREATE TABLE books (hash TEXT NOT NULL PRIMARY KEY,title TEXT NOT NULL,format TEXT NOT NULL,fileName TEXT NOT NULL,importedAt INTEGER NOT NULL,lastRead INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE progress (hash TEXT NOT NULL PRIMARY KEY,format TEXT NOT NULL,locator TEXT NOT NULL,updatedAt INTEGER NOT NULL,pdfOffset REAL NOT NULL,pdfZoom REAL NOT NULL)")
            db.execSQL("CREATE TABLE bookmarks (hash TEXT NOT NULL,id TEXT NOT NULL,format TEXT NOT NULL,label TEXT NOT NULL,locator TEXT NOT NULL,updatedAt INTEGER NOT NULL,deleted INTEGER NOT NULL,PRIMARY KEY(hash,id))")
            db.execSQL("INSERT INTO books VALUES(?, '旧版书籍','epub','cached.epub',1,2)", arrayOf(hash))
            db.execSQL("INSERT INTO progress VALUES(?, 'epub', ?, 100,0,1)", arrayOf(hash, """{"href":"c.xhtml","type":"application/xhtml+xml"}"""))
            db.version = 1
        }
        val db = Room.databaseBuilder(context, ReaderDatabase::class.java, name).addMigrations(DRIVE_MIGRATION_1_2, PRESENTATION_MIGRATION_2_3, BOOK_DELETION_MIGRATION_3_4, LOCAL_ONLY_MIGRATION_4_5).build()
        try {
            assertEquals("旧版书籍", db.dao().book(hash)!!.title)
            assertEquals(100L, db.dao().progress(hash)!!.updatedAt)
            assertEquals(1, db.drive().hasBook("local", hash))
            assertTrue(db.drive().connections().isEmpty()); assertTrue(db.drive().states("local").isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
