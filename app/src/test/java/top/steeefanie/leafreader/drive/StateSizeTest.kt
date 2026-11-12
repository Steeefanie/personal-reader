package top.steeefanie.leafreader.drive

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import top.steeefanie.leafreader.storage.*

class StateSizeTest {
    private val hash = "e".repeat(64)
    private val locator = """{"href":"one.xhtml","type":"application/xhtml+xml","locations":{"position":1}}"""

    @Test fun validLargeDocumentRemainsWithinLimitAcrossWireStorageAndExport() {
        val data = Exchange(emptyList(), (0 until 23000).map {
            Bookmark(hash, "mark-$it", "epub", "界面联调书签", locator, 1760000000000L)
        })
        val compact = BookState.canonical(data)
        assertTrue(compact.toByteArray().size < ProgressExchange.MAX_BYTES)
        assertFalse(compact.contains('\n'))
        val restored = BookState.decode(hash, compact)
        assertEquals(23000, restored.bookmarks.size)
        assertEquals(compact, BookState.canonical(restored))

        val exported = ProgressExchange.encode(data)
        assertTrue(exported.toByteArray().size <= ProgressExchange.MAX_BYTES)
        assertEquals(23000, ProgressExchange.decode(exported).bookmarks.size)

        val oversized = data.copy(bookmarks = data.bookmarks.map { it.copy(label = "x".repeat(512)) })
        assertThrows(IllegalArgumentException::class.java) { BookState.canonical(oversized) }
    }
    @Test fun decodeValidatesHashAndRecords() {
        val invalid = """{"schemaVersion":999,"progress":[],"bookmarks":[]}"""
        assertThrows(IllegalArgumentException::class.java) { BookState.decode(hash, invalid) }
        val wrongBook = Exchange(emptyList(), listOf(Bookmark("a".repeat(64), "one", "epub", "标签", locator, 10)))
        assertThrows(IllegalArgumentException::class.java) { BookState.decode(hash, BookState.canonical(wrongBook)) }
    }
}
