package top.steeefanie.leafreader.reader

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import top.steeefanie.leafreader.storage.*
import top.steeefanie.leafreader.drive.*

class TextBookmarksTest {
    private val hash="a".repeat(64)
    private val locator="""{"href":"one.xhtml","type":"application/xhtml+xml","title":"第一章","locations":{"progression":0.2}}"""
    private fun capture()=TextBookmarks.capture(locator,JSONObject("""{"cssSelector":"body > p:nth-of-type(2)","readerTextRange":{"version":1,"unit":"utf16","start":2,"end":5,"bodyStart":102,"bodyEnd":105},"text":{"highlight":"山川间","before":"走在","after":"。"}}"""))
    @Test fun rangeAndContextSurviveSingleBookWireRoundTrip() {
        val mark=Bookmark(hash,"text-mark","epub","山川间",capture(),1000)
        val restored=BookState.decode(hash,BookState.canonical(Exchange(emptyList(),listOf(mark)))).bookmarks.single()
        assertTrue(TextBookmarks.isText(restored));assertEquals("山川间",TextBookmarks.quote(restored))
        val json=JSONObject(restored.locator)
        assertEquals(102,json.getJSONObject("locations").getJSONObject("readerTextRange").getInt("bodyStart"))
        assertEquals("body > p:nth-of-type(2)",json.getJSONObject("locations").getString("cssSelector"))
        assertEquals("走在",json.getJSONObject("text").getString("before"))
        assertEquals("第一章：山川间",TextBookmarks.display(restored))
    }
    @Test fun legacyPositionBookmarkNeverBecomesTextUnderline() {
        val old=Bookmark(hash,"old","epub","旧位置",locator,1000)
        assertFalse(TextBookmarks.isText(old));assertEquals("第一章：旧位置",TextBookmarks.display(old))
    }
    @Test fun unionAndDeletionRetainRangeMetadata() {
        val mark=Bookmark(hash,"text","epub","山川间",capture(),1000)
        val old=Bookmark(hash,"legacy","epub","位置",locator,1000)
        val local=Exchange(emptyList(),listOf(mark))
        val merged=StateMerge.merge(BookState.empty,local,Exchange(emptyList(),listOf(old))).state
        assertEquals(setOf("text","legacy"),merged.bookmarks.map { it.id }.toSet())
        val deleted=mark.copy(deleted=true,updatedAt=2000)
        val result=StateMerge.merge(local,Exchange(emptyList(),listOf(deleted)),local).state.bookmarks.single()
        assertTrue(result.deleted);assertEquals(mark.locator,result.locator)
    }
    @Test(expected=IllegalArgumentException::class) fun backwardsRangeIsRejected() {
        TextBookmarks.capture(locator,JSONObject("""{"cssSelector":"body","readerTextRange":{"version":1,"unit":"utf16","start":5,"end":2,"bodyStart":10,"bodyEnd":12},"text":{"highlight":"测试"}}"""))
    }
}
