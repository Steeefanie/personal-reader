@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package top.steeefanie.leafreader

import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.readium.r2.navigator.OverflowableNavigator
import top.steeefanie.leafreader.reader.ReaderActivity
import java.io.File
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.publication.services.positions
import top.steeefanie.leafreader.storage.Progress

class ReaderSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as ReaderApplication

    @Test fun epubImportRenderTurnAndRestore() = exercise("sample.epub")
    @Test fun pdfImportRenderTurnAndRestore() = exercise("sample.pdf")

    @Test fun epubSearchFindsChineseText() = runBlocking {
        val publication = app.engine.open(fixture("sample.epub"))
        try {
            val iterator = publication.search("山川") ?: error("No search service")
            try { assertTrue(iterator.next().getOrNull()!!.locators.isNotEmpty()) }
            finally { iterator.close() }
        } finally { publication.close() }
    }

    @Test fun pdfTextLayerCanBeSearched() {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(app)
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(fixture("sample.pdf")).use { document ->
            val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
            stripper.startPage = 2; stripper.endPage = 2
            assertTrue(stripper.getText(document).contains("mountain"))
        }
    }

    @Test fun fontSettingsPersist() = runBlocking {
        val original = app.settings.read()
        try {
            val value = original.copy(fontSize = 24.0, lineHeight = 2.0, paragraphSpacing = 1.25, margins = .5, font = "source-han-serif", fontWeight = 1.5, showPageNumbers = false, showStatusBar = false)
            app.settings.save(value)
            assertEquals(value, app.settings.read())
        } finally { app.settings.save(original) }
    }

    @Test fun damagedFileDoesNotEnterLibrary() = runBlocking {
        val file = fixture("broken.epub")
        val count = app.repository.books.first().size
        try {
            app.repository.importBook(Uri.fromFile(file), app.engine::validate)
            fail("Damaged input must fail")
        } catch (_: IllegalStateException) { }
        assertEquals(count, app.repository.books.first().size)
    }

    private fun fixture(name: String): File = File(app.cacheDir, name).apply {
        instrumentation.context.assets.open(name).use { input -> outputStream().use { input.copyTo(it) } }
    }

    private fun exercise(name: String) = runBlocking {
        val file = fixture(name)
        app.repository.importBook(Uri.fromFile(file), app.engine::validate)
        assertFalse(app.repository.importBook(Uri.fromFile(file), app.engine::validate))
        val book = app.repository.books.first().first { it.format == name.substringAfterLast('.') }
        // Each run starts at page one, independent of progress left by earlier test runs.
        val publication = app.engine.open(app.repository.file(book))
        try {
            val first = publication.positions().first()
            app.repository.dao.putProgress(Progress(book.hash, book.format, first.toJSON().toString(), System.currentTimeMillis()))
        } finally { publication.close() }
        val intent = Intent(app, ReaderActivity::class.java).putExtra("bookHash", book.hash).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as ReaderActivity
        try {
            var navigator: OverflowableNavigator? = null
            withTimeout(45000) {
                while (navigator == null) {
                    instrumentation.runOnMainSync {
                        navigator = activity.supportFragmentManager.findFragmentByTag("navigator") as? OverflowableNavigator
                    }
                    delay(100)
                }
            }
            // Wait for asynchronous EPUB/PDF page rendering, then turn via the real navigator.
            delay(2500)
            var before = ""
            instrumentation.runOnMainSync { before = navigator!!.currentLocator.value.toJSON().toString(); navigator!!.goForward(false) }
            var after = before
            withTimeout(15000) {
                while (after == before) {
                    delay(100)
                    instrumentation.runOnMainSync { after = navigator!!.currentLocator.value.toJSON().toString() }
                }
            }
            assertNotEquals(before, after)
            delay(500)
            instrumentation.runOnMainSync { after = navigator!!.currentLocator.value.toJSON().toString() }
            instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                File(app.cacheDir, "${book.format}-reading.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            }
            instrumentation.runOnMainSync { activity.finish() }
            val targetPosition = org.json.JSONObject(after).getJSONObject("locations").optInt("position")
            var saved = app.repository.dao.progress(book.hash)
            withTimeout(10000) {
                while (saved == null || org.json.JSONObject(saved!!.locator).getJSONObject("locations").optInt("position") != targetPosition) {
                    delay(100)
                    saved = app.repository.dao.progress(book.hash)
                }
            }
            val persisted = saved!!
            assertTrue(persisted.locator.isNotEmpty())
            val reopened = instrumentation.startActivitySync(intent) as ReaderActivity
            try {
                var restored: String? = null
                withTimeout(45000) {
                    while (restored == null) {
                        delay(100)
                        instrumentation.runOnMainSync {
                            val nav = reopened.supportFragmentManager.findFragmentByTag("navigator") as? OverflowableNavigator
                            restored = nav?.currentLocator?.value?.href?.toString()
                        }
                    }
                }
                assertEquals(org.json.JSONObject(persisted.locator).getString("href"), restored)
                instrumentation.runOnMainSync {
                    val nav = reopened.supportFragmentManager.findFragmentByTag("navigator") as OverflowableNavigator
                    val expected = org.readium.r2.shared.publication.Locator.fromJSON(org.json.JSONObject(persisted.locator))!!
                    assertEquals(expected.locations.position, nav.currentLocator.value.locations.position)
                }
            } finally { instrumentation.runOnMainSync { reopened.finish() } }
        } finally { instrumentation.runOnMainSync { if (!activity.isFinishing) activity.finish() } }
    }
}
