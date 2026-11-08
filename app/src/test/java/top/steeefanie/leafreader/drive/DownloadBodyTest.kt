package top.steeefanie.leafreader.drive

import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DownloadBodyTest {
    private fun response(status: Int, text: String, extra: Map<String, String> = emptyMap()) = WireResponse(status, mapOf("ETag" to "\"v1\"") + extra, ByteArrayInputStream(text.toByteArray()))
    private fun fixture() = File.createTempFile("reader-range-", ".part")
    @Test fun fullResponseReplacesExistingPartial() = runBlocking {
        val file = fixture()
        try {
            file.writeText("old")
            response(200, "new-book").use { DownloadBody.receive(it, file, 8, "v1", {}, {}) }
            assertEquals("new-book", file.readText())
        } finally { file.delete() }
    }
    @Test fun partialResponseAppendsOnlyMatchingRange() = runBlocking {
        val file = fixture()
        try {
            file.writeText("abc")
            response(206, "def", mapOf("Content-Range" to "bytes 3-5/6")).use { DownloadBody.receive(it, file, 6, "v1", {}, {}) }
            assertEquals("abcdef", file.readText())
        } finally { file.delete() }
    }
    @Test fun wrongContentVersionNeverAppends() = runBlocking {
        val file = fixture()
        try {
            file.writeText("abc")
            try { response(206, "def", mapOf("ETag" to "\"v2\"", "Content-Range" to "bytes 3-5/6")).use { DownloadBody.receive(it, file, 6, "v1", {}, {}) }; fail() }
            catch (_: IllegalArgumentException) { assertEquals("abc", file.readText()) }
        } finally { file.delete() }
    }
    @Test fun interruptedBodyRetainsPartialForNextAttempt() = runBlocking {
        val file = fixture()
        try {
            try { response(200, "abc").use { DownloadBody.receive(it, file, 6, "v1", {}, {}) }; fail() }
            catch (_: java.io.IOException) { assertEquals("abc", file.readText()) }
        } finally { file.delete() }
    }
    @Test fun range416AcceptsOnlyAlreadyCompletePartial() = runBlocking {
        val file = fixture()
        try {
            file.writeText("abc")
            response(416, "", mapOf("Content-Range" to "bytes */3")).use { DownloadBody.receive(it, file, 3, "v1", {}, {}) }
            assertEquals("abc", file.readText())
        } finally { file.delete() }
    }
    @Test fun rangeWithWrongStartNeverChangesPartial() = runBlocking {
        val file = fixture()
        try {
            file.writeText("abc")
            try { response(206, "def", mapOf("Content-Range" to "bytes 2-4/6")).use { DownloadBody.receive(it, file, 6, "v1", {}, {}) }; fail() }
            catch (_: IllegalArgumentException) { assertEquals("abc", file.readText()) }
        } finally { file.delete() }
    }
}
