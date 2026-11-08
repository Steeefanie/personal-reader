@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package top.steeefanie.leafreader

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.*
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import top.steeefanie.leafreader.reader.ReaderActivity
import top.steeefanie.leafreader.reader.epub.*
import top.steeefanie.leafreader.storage.*
import top.steeefanie.leafreader.ui.*
import java.io.File
import java.util.UUID

/** Exercise the shipping native Canvas navigator, never the retired WebView navigator. */
class ReadingLayoutTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val app get()=ins.targetContext.applicationContext as ReaderApplication
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    private suspend fun until(test:()->Boolean)=withTimeout(15000){while(!test())delay(50)}
    private suspend fun navigator(reader:ReaderActivity):NativeEpubNavigatorFragment {
        var nav:NativeEpubNavigatorFragment?=null
        until{ins.runOnMainSync{nav=reader.supportFragmentManager.findFragmentByTag("navigator") as? NativeEpubNavigatorFragment};nav?.pageCount?.let{it>0}==true}
        return checkNotNull(nav)
    }
    private fun tap(reader:ReaderActivity,fraction:Float){
        val now=SystemClock.uptimeMillis();val x=reader.window.decorView.width*fraction;val y=reader.window.decorView.height*.5f
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)){
            val e=MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,x,y,0);try{ins.sendPointerSync(e)}finally{e.recycle()}
        }
    }
    private suspend fun fixture(source:File,body:suspend (ReaderActivity,NativeEpubNavigatorFragment,Book,String)->Unit){
        val owner="native-layout-${UUID.randomUUID()}";val old=app.settings.read();var reader:ReaderActivity?=null;var book:Book?=null
        try{
            app.settings.save(ReadingSettings());app.repository.importBook(Uri.fromFile(source),app.engine::validate,owner)
            book=app.repository.dao.ownedBooks(owner).first()
            reader=ins.startActivitySync(Intent(app,ReaderActivity::class.java).putExtra("bookHash",book.hash).putExtra("ownerKey",owner).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderActivity
            body(reader,navigator(reader),book,owner)
        }finally{reader?.let{ins.runOnMainSync{it.finish()}};app.settings.save(old);book?.let{app.repository.remove(it,owner)}}
    }
    private fun source(asset:String)=File.createTempFile("native-layout-",".epub",app.cacheDir).also{file->ins.context.assets.open(asset).use{input->file.outputStream().use{input.copyTo(it)}}}
    @Test fun traditionalLanguageIsHorizontalAndChapterPageChangesWithTypography()=runBlocking {
        val input=source("traditional.epub")
        try{fixture(input){reader,nav,_,_->
            assertTrue(nav.publicationView is NativeEpubView)
            assertTrue(nav.pageCount>1)
            val view=nav.publicationView as NativeEpubView
            val lines=checkNotNull(view.currentPage()).lines.filter{it.charCount>5}
            assertTrue(lines.isNotEmpty());assertTrue(lines.all{line->(1 until line.charCount).all{line.charLeft[it]>=line.charLeft[it-1]}})
            val before=nav.pageIndex;tap(reader,.9f);until{nav.pageIndex==before+1};tap(reader,.1f);until{nav.pageIndex==before}
            val original=nav.pageCount;val layout=view.chapterLayout
            ins.runOnMainSync{nav.submit(nav.settings.copy(fontSize=36.0))}
            until{view.chapterLayout!==layout};assertTrue(nav.pageCount>original)
            val compact=view.chapterLayout
            ins.runOnMainSync{nav.submit(nav.settings.copy(fontSize=22.0,paragraphSpacing=0.0))};until{view.chapterLayout!==compact}
            val count=nav.pageCount;val close=view.chapterLayout
            ins.runOnMainSync{nav.submit(nav.settings.copy(paragraphSpacing=1.25))};until{view.chapterLayout!==close};assertTrue(nav.pageCount>=count)
            val panel=CompletableDeferred<PaperPanel>();ins.runOnMainSync{panel.complete(reader.showTypography(ReadingSettings()){})}
            val sheet=panel.await()
            ins.runOnMainSync{
                assertEquals(0f,sheet.window!!.attributes.dimAmount,0f);assertEquals(0f,sheet.window!!.decorView.elevation,0f)
                assertEquals(0,sheet.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                val bars=all(sheet.window!!.decorView).filterIsInstance<DiscreteSettingBar>()
                assertEquals(5,bars.size);assertFalse(bars.first().contentDescription.contains("%"))
                repeat(20){bars.first().step(1)};assertEquals(10,bars.first().index)
                repeat(20){bars.first().step(-1)};assertEquals(0,bars.first().index);sheet.dismiss()
            }
        }}finally{input.delete()}
    }
    @Test fun suppliedEpubUsesHorizontalTextAndCorrectPageDirection()=runBlocking {
        val path=InstrumentationRegistry.getArguments().getString("epubPath")
        assumeTrue("Optional user book; never bundled",path!=null)
        fixture(File(checkNotNull(path))){reader,nav,_,_->
            assertTrue(nav.publicationView is NativeEpubView)
            val before=nav.currentLocator.value;tap(reader,.9f);until{nav.currentLocator.value!=before};tap(reader,.1f)
        }
    }
    @Test fun readingDisplaySwitchesApplyToNativePages()=runBlocking {
        val input=source("sample.epub");val old=app.settings.read();val owner="native-display-${UUID.randomUUID()}";var book:Book?=null;var reader:ReaderActivity?=null
        try{
            app.repository.importBook(Uri.fromFile(input),app.engine::validate,owner);book=app.repository.dao.ownedBooks(owner).first()
            app.settings.save(ReadingSettings(showPageNumbers=false,showStatusBar=false))
            reader=ins.startActivitySync(Intent(app,ReaderActivity::class.java).putExtra("bookHash",book.hash).putExtra("ownerKey",owner).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderActivity
            navigator(reader);delay(500)
            ins.runOnMainSync{
                assertFalse(all(reader!!.window.decorView).first{it.contentDescription=="阅读页码"}.isShown)
                assertFalse(ViewCompat.getRootWindowInsets(reader!!.window.decorView)!!.isVisible(WindowInsetsCompat.Type.statusBars()))
            }
        }finally{reader?.let{ins.runOnMainSync{it.finish()}};app.settings.save(old);book?.let{app.repository.remove(it,owner)};input.delete()}
    }
    @Test fun stationaryReadingHasNoContinuousRendering()=runBlocking {
        val input=source("sample.epub")
        fun frames(reset:Boolean=false):String {
            val fd=ins.uiAutomation.executeShellCommand("dumpsys gfxinfo top.steeefanie.leafreader"+if(reset)" reset"else "")
            return android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use{it.readText()}
        }
        try{fixture(input){_,_,_,_->
            delay(3000);frames(true);delay(10000);val result=frames()
            File(app.cacheDir,"v1.0.0-idle-reading.log").writeText(result)
            val count=Regex("Total frames rendered: (\\d+)").find(result)?.groupValues?.get(1)?.toInt() ?: error("Missing frame metrics")
            assertTrue("Static reading rendered $count frames",count<=1)
        }}finally{input.delete()}
    }
}
