@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package top.steeefanie.leafreader.reader

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.readium.r2.navigator.OverflowableNavigator
import top.steeefanie.leafreader.reader.epub.NativeEpubNavigatorFragment
import top.steeefanie.leafreader.reader.epub.NativeEpubView
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.positions
import top.steeefanie.leafreader.ReaderApplication
import top.steeefanie.leafreader.storage.*
import java.io.File
import java.util.UUID

class TextBookmarkReadingTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val app get()=ins.targetContext.applicationContext as ReaderApplication
    private fun all(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap { all(view.getChildAt(it)) }else emptyList()
    private fun nodes(node:AccessibilityNodeInfo):List<AccessibilityNodeInfo> = listOf(node)+(0 until node.childCount).mapNotNull(node::getChild).flatMap(::nodes)
    private suspend fun node(match:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo=withTimeout(10000) {
        var result:AccessibilityNodeInfo?=null
        while(result==null){
            result=ins.uiAutomation.windows.mapNotNull { it.root }.flatMap(::nodes).firstOrNull(match)
                ?: ins.uiAutomation.rootInActiveWindow?.let(::nodes)?.firstOrNull(match)
            if(result==null)delay(50)
        }
        checkNotNull(result)
    }
    private suspend fun clickNode(text:String) { assertTrue(node { it.text==text || it.contentDescription==text }.performAction(AccessibilityNodeInfo.ACTION_CLICK));ins.waitForIdleSync() }
    private fun click(reader:ReaderActivity,description:String) {
        ins.runOnMainSync { assertTrue(all(reader.window.decorView).first { it.contentDescription==description }.performClick()) };ins.waitForIdleSync()
    }
    private suspend fun until(test:()->Boolean) { withTimeout(10000){while(!test())delay(50)} }
    private fun visible(reader:ReaderActivity,description:String):Boolean { var shown=false;ins.runOnMainSync { shown=all(reader.window.decorView).any { it.contentDescription==description && it.isShown } };return shown }
    private fun touch(x:Float,y:Float,long:Boolean=false) {
        val start=SystemClock.uptimeMillis();ins.sendPointerSync(MotionEvent.obtain(start,start,MotionEvent.ACTION_DOWN,x,y,0))
        if(long)SystemClock.sleep(850)
        ins.sendPointerSync(MotionEvent.obtain(start,SystemClock.uptimeMillis()+50,MotionEvent.ACTION_UP,x,y,0))
    }
    private fun tap(reader:ReaderActivity,fraction:Float) { touch(reader.window.decorView.width*fraction,reader.window.decorView.height*.5f) }
    private fun screenshot(name:String) {
        val bitmap=checkNotNull(ins.uiAutomation.takeScreenshot())
        File(app.cacheDir,"text-bookmarks-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    private suspend fun fixture(asset:String,body:suspend (ReaderActivity,OverflowableNavigator,Book,String)->Unit) {
        val owner="bookmark-test-${UUID.randomUUID()}";val old=app.settings.read()
        val source=File(app.cacheDir,"$owner.${asset.substringAfterLast('.')}");var book:Book?=null;var reader:ReaderActivity?=null
        try {
            ins.uiAutomation.serviceInfo=ins.uiAutomation.serviceInfo.apply {
                flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            app.settings.save(ReadingSettings())
            ins.context.assets.open(asset).use { input -> source.outputStream().use { input.copyTo(it) } }
            app.repository.importBook(Uri.fromFile(source),app.engine::validate,owner)
            book=app.repository.dao.ownedBooks(owner).first()
            val publication=app.engine.open(source)
            val first=try { publication.positions().first() }finally { publication.close() }
            app.repository.saveProgress(Progress(book.hash,book.format,first.toJSON().toString(),System.currentTimeMillis()),owner)
            reader=ins.startActivitySync(Intent(app,ReaderActivity::class.java).putExtra("bookHash",book.hash).putExtra("ownerKey",owner).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderActivity
            var navigator:OverflowableNavigator?=null
            until { ins.runOnMainSync { navigator=reader.supportFragmentManager.findFragmentByTag("navigator") as? OverflowableNavigator };navigator!=null }
            delay(2200);body(reader,checkNotNull(navigator),book,owner)
        } catch(failure:Throwable) {
            screenshot("failure")
            File(app.cacheDir,"text-bookmarks-failure.txt").writeText(ins.uiAutomation.windows.mapNotNull { it.root }.flatMap(::nodes).joinToString("\n") { "${it.className} / ${it.text} / ${it.contentDescription}" })
            throw failure
        } finally {
            reader?.let { ins.runOnMainSync { it.finish() } };delay(200);app.settings.save(old)
            book?.let { app.repository.remove(it,owner) };source.delete()
            app.database.openHelper.writableDatabase.execSQL("DELETE FROM cloud_states WHERE ownerKey=?",arrayOf(owner))
        }
    }
    private fun point(epub:NativeEpubNavigatorFragment,offset:Int?=null):Pair<Float,Float> {
        var result=0f to 0f
        ins.runOnMainSync{
            val view=epub.publicationView as NativeEpubView;val page=checkNotNull(view.currentPage())
            val line=if(offset==null)page.lines.first{it.charCount>16} else page.lines.first{offset>=it.bodyStart && offset<it.bodyEnd}
            val index=if(offset==null)4 else (offset-line.bodyStart).coerceIn(0,line.charCount-1)
            val position=IntArray(2);view.getLocationOnScreen(position)
            result=(position[0]+(line.charLeft[index]+line.charRight[index])/2f) to (position[1]+(line.top+line.bottom)/2f)
        };return result
    }
    @Test fun longPressBookmarkReflowsAndTapCancelsWithoutTurning()=runBlocking {fixture("traditional.epub"){reader,navigator,book,owner->
        val epub=navigator as NativeEpubNavigatorFragment
        val p=point(epub);touch(p.first,p.second,true);node{it.contentDescription=="添加书签"}
        val before=epub.currentLocator.value
        ins.runOnMainSync{reader.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_PAGE_DOWN));reader.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_PAGE_DOWN))}
        delay(200);assertEquals(before,epub.currentLocator.value)
        clickNode("添加书签")
        var mark:Bookmark?=null
        withTimeout(10000){while(mark==null){mark=app.repository.bookmarks(book.hash,owner).firstOrNull{!it.deleted};if(mark==null)delay(50)}}
        val locator=JSONObject(mark!!.locator);val range=locator.getJSONObject("locations").getJSONObject("readerTextRange")
        assertEquals("native",range.getString("engine"));assertTrue(locator.getJSONObject("text").getString("highlight").isNotBlank())
        assertTrue(TextBookmarks.isText(mark!!))
        val view=epub.publicationView as NativeEpubView;val old=view.chapterLayout
        ins.runOnMainSync{epub.submit(epub.settings.copy(fontSize=30.0))};until{view.chapterLayout!==old}
        ins.runOnMainSync{epub.go(Locator.fromJSON(locator)!!,false)};delay(400)
        val q=point(epub,range.getInt("bodyStart"));val page=epub.currentLocator.value
        touch(q.first,q.second);node{it.contentDescription=="取消书签"};assertEquals(page,epub.currentLocator.value)
        screenshot("native-underline-action");clickNode("取消书签")
        withTimeout(10000){while(app.repository.bookmarks(book.hash,owner).none{it.id==mark!!.id && it.deleted})delay(50)}
        assertEquals(page,epub.currentLocator.value)
        val legacy=Bookmark(book.hash,"legacy","epub","原位置书签",before.toJSON().toString(),System.currentTimeMillis())
        app.repository.saveBookmark(legacy,owner);assertFalse(TextBookmarks.isText(legacy))
    }}
    @Test fun textSelectionBlocksSwipePageTurns()=runBlocking {fixture("traditional.epub"){reader,navigator,_,_->
        val epub=navigator as NativeEpubNavigatorFragment;val p=point(epub)
        touch(p.first,p.second,true);node{it.contentDescription=="添加书签"};val before=epub.currentLocator.value
        val start=SystemClock.uptimeMillis();val w=reader.window.decorView.width.toFloat();val y=reader.window.decorView.height*.8f
        fun event(action:Int,x:Float){val e=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,x,y,0);try{assertTrue(ins.uiAutomation.injectInputEvent(e,true))}finally{e.recycle()}}
        event(MotionEvent.ACTION_DOWN,w*.9f);repeat(8){delay(25);event(MotionEvent.ACTION_MOVE,w*(.9f-.08f*(it+1)))};event(MotionEvent.ACTION_UP,w*.26f)
        delay(250);assertEquals(before,epub.currentLocator.value)
    }}
    @Test fun jumpReturnSurvivesPanelsAndOnlyEffectiveManualTurnClearsIt()=runBlocking {fixture("traditional.epub"){reader,navigator,_,_->
        val epub=navigator as NativeEpubNavigatorFragment;val origin=epub.currentLocator.value
        tap(reader,.5f);click(reader,"目录");clickNode("第二章")
        until{epub.currentLocator.value.href.toString().contains("two.xhtml")};until{visible(reader,"返回原处")}
        val view=epub.publicationView as NativeEpubView;val old=view.chapterLayout
        ins.runOnMainSync{epub.submit(epub.settings.copy(fontSize=27.0))};until{view.chapterLayout!==old}
        assertTrue(visible(reader,"返回原处"));click(reader,"返回原处");until{epub.currentLocator.value.href==origin.href}
        assertFalse(visible(reader,"返回原处"))
        tap(reader,.5f);click(reader,"目录");clickNode("第二章");until{visible(reader,"返回原处")}
        tap(reader,.9f);until{!visible(reader,"返回原处")}
    }}
    @Test fun pdfKeepsTopRightPageBookmarkAndFailedTurnKeepsReturn()=runBlocking { fixture("sample.pdf") { reader,navigator,book,owner ->
        tap(reader,.5f);until { visible(reader,"添加本页书签") };click(reader,"添加本页书签")
        withTimeout(10000){while(app.repository.bookmarks(book.hash,owner).none { !it.deleted })delay(50)}
        assertFalse(TextBookmarks.isText(app.repository.bookmarks(book.hash,owner).first()))
        click(reader,"页码")
        val field=node { it.isEditable }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"3")
        }))
        clickNode("跳转")
        until { navigator.currentLocator.value.locations.position==3 };assertTrue(visible(reader,"返回原处"))
        tap(reader,.9f);delay(300);assertTrue(visible(reader,"返回原处"))
        tap(reader,.1f);until { navigator.currentLocator.value.locations.position==2 };until { !visible(reader,"返回原处") }
    } }

}
