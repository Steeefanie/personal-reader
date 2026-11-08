package top.steeefanie.leafreader.storage

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProgressExchangeTest {
    private val hash = "a".repeat(64)
    private val locator = """{"href":"chapter.xhtml","type":"application/xhtml+xml","locations":{"progression":0.4,"position":3}}"""
    private fun sample() = Exchange(listOf(Progress(hash, "epub", locator, 100)), listOf(
        Bookmark(hash, "bookmark-1", "epub", "第一章", locator, 101),
        Bookmark(hash, "bookmark-2", "epub", "已移除", locator, 102, true)
    ))
    @Test fun roundTripRetainsPositionAndTombstones() {
        val restored = ProgressExchange.decode(ProgressExchange.encode(sample()))
        assertEquals(hash, restored.progress.single().hash)
        assertEquals(3, JSONObject(restored.progress.single().locator).getJSONObject("locations").getInt("position"))
        assertTrue(restored.bookmarks.last().deleted)
        assertEquals("第一章", restored.bookmarks.first().label)
    }
    @Test fun rejectsUnknownVersion() = rejects { put("schemaVersion", 999) }
    @Test fun rejectsInvalidHash() = rejects { getJSONArray("progress").getJSONObject(0).put("bookHash", "../book") }
    @Test fun rejectsInvalidPosition() = rejects {
        getJSONArray("progress").getJSONObject(0).getJSONObject("locator").getJSONObject("locations").put("progression", 1.5)
    }
    @Test fun rejectsDuplicateBookProgress() = rejects {
        getJSONArray("progress").put(getJSONArray("progress").getJSONObject(0))
    }
    @Test fun rejectsContradictoryFormat() = rejects {
        getJSONArray("bookmarks").getJSONObject(0).put("format", "pdf")
    }
    @Test fun rejectsInvalidPdfZoom() = rejects {
        getJSONArray("progress").getJSONObject(0).put("pdfZoom", 0)
    }
    @Test fun retainsUnknownBookForLaterImport() {
        val decoded = ProgressExchange.decode(ProgressExchange.encode(sample()))
        assertEquals(hash, decoded.progress.single().hash)
    }
    private fun rejects(change: JSONObject.() -> Unit) {
        val text = JSONObject(ProgressExchange.encode(sample())).apply(change).toString()
        assertThrows(IllegalArgumentException::class.java) { ProgressExchange.decode(text) }
    }
}
