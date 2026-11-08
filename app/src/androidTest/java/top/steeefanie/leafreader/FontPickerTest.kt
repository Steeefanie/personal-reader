package top.steeefanie.leafreader

import android.content.Intent
import android.graphics.*
import android.os.SystemClock
import android.view.*
import android.widget.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import top.steeefanie.leafreader.reader.fonts.*
import top.steeefanie.leafreader.ui.*
import java.io.File

class FontPickerTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val app get()=ins.targetContext.applicationContext as ReaderApplication
    private fun all(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap { all(view.getChildAt(it)) }else emptyList()
    private suspend fun waitUntil(condition:()->Boolean)=withTimeout(15000) {
        while(true) { var ready=false;ins.runOnMainSync { ready=condition() };if(ready)break;delay(100) }
    }
    private fun screenshot(name:String) {
        ins.waitForIdleSync();SystemClock.sleep(100)
        val bitmap=checkNotNull(ins.uiAutomation.takeScreenshot())
        File(app.cacheDir,"v053-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    private fun dividerInk(button:View):Int {
        val capsule=button.parent as View
        val bitmap=Bitmap.createBitmap(capsule.width,capsule.height,Bitmap.Config.ARGB_8888)
        return try {
            capsule.draw(Canvas(bitmap));bitmap.getPixel(capsule.width/2,capsule.height/4)
        }finally { bitmap.recycle() }
    }
    @Test fun mutableResultsCanBeClearedAndReused(){
        ins.runOnMainSync {
            val original=listOf("旧结果")
            val existing=app.paperTextAdapter(original)
            existing.clear();existing.add("新结果");assertEquals("新结果",existing.getItem(0))
            assertEquals(listOf("旧结果"),original)
            val empty=app.paperTextAdapter<String>()
            empty.clear();empty.add("首次搜索");empty.clear();empty.add("再次搜索")
            assertEquals("再次搜索",empty.getItem(0))
        }
    }
    @Test fun namesSearchActualPreviewsSelectionAndBottomPaging()=runBlocking {
        val fonts=app.fonts.families()
        val sans=fonts.first { it.originalName=="Noto Sans CJK SC" }
        val serif=fonts.first { it.originalName=="Noto Serif CJK SC" }
        assertTrue(sans.name.contains("黑体"));assertTrue(serif.name.contains("宋体"))
        val sansFace=checkNotNull(app.fonts.preview(sans,1.0))
        val serifFace=checkNotNull(app.fonts.preview(serif,1.0))
        fun pixels(typeface:Typeface):IntArray {
            val bitmap=Bitmap.createBitmap(400,90,Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawText("山川阅读 Aa",0f,70f,Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface=typeface;textSize=52f;color=Color.BLACK })
            return IntArray(400*90).also { bitmap.getPixels(it,0,400,0,0,400,90);bitmap.recycle() }
        }
        assertFalse("Different device fonts must produce different preview glyphs",pixels(sansFace).contentEquals(pixels(serifFace)))
        val activity=ins.startActivitySync(Intent(app,SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
        var panel:PaperPanel?=null
        var chosen:DeviceFontFamily?=null
        try {
            ins.runOnMainSync { panel=activity.showFontPicker(fonts,sans.id,1.0) { chosen=it } }
            val picker=checkNotNull(panel)
            lateinit var list:ListView;lateinit var search:EditText
            ins.runOnMainSync {
                list=all(picker.window!!.decorView).filterIsInstance<ListView>().single()
                search=all(picker.window!!.decorView).filterIsInstance<EditText>().single()
                assertTrue(picker.isFullScreen)
                assertEquals(0,picker.window!!.attributes.windowAnimations)
                assertTrue((0 until list.count).all { (list.getItemAtPosition(it) as DeviceFontFamily).let { f->f.generic||f.chinese } })
            }
            waitUntil { all(list).filterIsInstance<TextView>().any { it.contentDescription=="英文字体预览" && it.isShown } }
            val up=all(picker.window!!.decorView).first { it.contentDescription=="向上翻屏" }
            val down=all(picker.window!!.decorView).first { it.contentDescription=="向下翻屏" }
            waitUntil { !up.isEnabled && down.isEnabled }
            ins.runOnMainSync { assertEquals(Color.BLACK,dividerInk(up)) }
            screenshot("font-index")
            ins.runOnMainSync {
                val before=list.firstVisiblePosition
                all(picker.window!!.decorView).first { it.contentDescription=="向下翻屏" }.performClick()
                // The observer updates availability after scroll/layout events.
                assertEquals(0,before)
            }
            waitUntil { list.firstVisiblePosition>0 && up.isEnabled }
            withTimeout(10000) {
                while(true) {
                    var canDown=false
                    ins.runOnMainSync { canDown=down.isEnabled;if(canDown)down.performClick() }
                    if(!canDown)break
                    delay(100)
                }
            }
            ins.runOnMainSync { assertTrue(up.isEnabled);assertFalse(down.isEnabled) }
            screenshot("font-bottom-disabled")
            ins.runOnMainSync { search.setText("Noto Serif CJK SC") }
            waitUntil {
                val text=all(list).filterIsInstance<TextView>()
                list.count==1 && text.any { it.text.toString()==serif.name } &&
                    text.any { it.contentDescription=="英文字体预览" && it.isShown }
            }
            ins.runOnMainSync {
                val family=list.getItemAtPosition(0) as DeviceFontFamily
                assertEquals(serif.id,family.id)
                val sample=all(list).filterIsInstance<TextView>().single { it.contentDescription=="中文字体预览" }
                assertTrue("Preview must render this font file and TTC face",pixels(serifFace).contentEquals(pixels(sample.typeface)))
            }
            waitUntil { !up.isEnabled && !down.isEnabled }
            ins.runOnMainSync { assertEquals(Color.rgb(170,170,170),dividerInk(up)) }
            screenshot("font-search")
            ins.runOnMainSync { search.setText("not-installed-font-with-no-matches") }
            waitUntil { list.count==0 && !up.isEnabled && !down.isEnabled }
            ins.runOnMainSync { search.setText("");all(picker.window!!.decorView).filterIsInstance<Button>().first { it.text=="全部字体" }.performClick();search.setText("Roboto") }
            waitUntil { list.count>0 && (list.getItemAtPosition(0) as DeviceFontFamily).originalName.contains("Roboto") }
            ins.runOnMainSync { search.setText("宋体 简体中文") }
            waitUntil { list.count>=1 && (list.getItemAtPosition(0) as DeviceFontFamily).id==serif.id }
            ins.runOnMainSync { assertTrue(list.performItemClick(list.getChildAt(0),0,list.getItemIdAtPosition(0))) }
            assertEquals(serif.id,chosen?.id);assertFalse(picker.isShowing)
        }finally { ins.runOnMainSync { panel?.dismiss();activity.finish() } }
    }
}
