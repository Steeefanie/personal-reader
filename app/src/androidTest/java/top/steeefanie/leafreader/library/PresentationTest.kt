@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package top.steeefanie.leafreader.library

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.view.*
import android.widget.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import top.steeefanie.leafreader.ReaderApplication
import top.steeefanie.leafreader.storage.*
import top.steeefanie.leafreader.ui.SettingsActivity
import top.steeefanie.leafreader.ui.Paper
import top.steeefanie.leafreader.ui.dp
import top.steeefanie.leafreader.drive.DriveActivity
import top.steeefanie.leafreader.drive.DriveConnectionActivity
import top.steeefanie.leafreader.reader.ReaderActivity
import java.io.*
import java.util.zip.*

/** Self-authored fixtures only; no user books, credentials or real network calls. */
class PresentationTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val app get()=ins.targetContext.applicationContext as ReaderApplication
    private val imported=mutableListOf<Book>()
    private fun fixture(title:String,author:String,cover:String="valid"):File {
        val file=File.createTempFile("presentation-",".epub",app.cacheDir)
        val bitmap=Bitmap.createBitmap(1200,1800,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.rgb(223,226,230))
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(24,48,67);strokeWidth=5f}
        canvas.drawLine(120f,190f,1080f,190f,paint);canvas.drawLine(120f,1500f,1080f,1500f,paint)
        paint.textSize=125f;paint.typeface=Typeface.create("serif",Typeface.BOLD)
        title.take(12).chunked(4).forEachIndexed{i,text->canvas.drawText(text,120f,480f+i*180f,paint)}
        paint.textSize=48f;canvas.drawText(author,120f,1430f,paint)
        val image=ByteArrayOutputStream().also{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray();bitmap.recycle()
        ZipOutputStream(file.outputStream()).use{zip->
            fun entry(name:String,bytes:ByteArray){zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry()}
            fun text(name:String,value:String)=entry(name,value.toByteArray(Charsets.UTF_8))
            val mime="application/epub+zip".toByteArray();val crc=CRC32().apply{update(mime)}
            zip.putNextEntry(ZipEntry("mimetype").apply{method=ZipEntry.STORED;size=mime.size.toLong();compressedSize=size;this.crc=crc.value});zip.write(mime);zip.closeEntry()
            text("META-INF/container.xml","""<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            val coverItem=if(cover=="none")"" else """<item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/>"""
            text("book.opf","""<?xml version="1.0" encoding="utf-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:test:$title:$cover</dc:identifier><dc:title>$title</dc:title><dc:creator>$author</dc:creator><dc:language>zh-CN</dc:language><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/>$coverItem</manifest><spine><itemref idref="one"/></spine></package>""")
            text("nav.xhtml","""<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>目录</title></head><body><nav epub:type="toc"><ol><li><a href="one.xhtml">第一章 山川</a></li></ol></nav></body></html>""")
            text("one.xhtml","""<html xmlns="http://www.w3.org/1999/xhtml"><head><title>山川与日常</title></head><body><h1>山川与日常</h1>${(1..120).joinToString(""){"<p>沿着山川慢慢行走，看树影落在纸上。阅读让日常多一份安静。这是阅读器界面验证用的自建文字，不是出版物。</p>"}}</body></html>""")
            if(cover!="none")entry("cover.png",if(cover=="valid")image else byteArrayOf(1,2,3,4))
        }
        return file
    }
    private suspend fun add(title:String,author:String,cover:String="valid"):Book {
        val input=fixture(title,author,cover)
        try{app.repository.importBook(Uri.fromFile(input),app.engine::validate,"local")}finally{input.delete()}
        return app.repository.dao.ownedBooks("local").first{it.title==title}.also{imported+=it;app.presentation.ensure(it)}
    }
    private suspend fun cleanup(){imported.forEach{book->runCatching{app.repository.remove(book,"local")};app.database.presentation().forget(book.hash);File(app.presentation.covers,"${book.hash}.png").delete()};imported.clear()}

    @Test fun boundedGrayscaleCoverAuthorAndCacheRecovery()=runBlocking {
        try {
            val book=add("封面提取测试","作者甲")
            var metadata=app.database.presentation().get(book.hash)!!
            assertEquals("作者甲",metadata.author);assertEquals("ready",metadata.coverState)
            val cached=File(app.presentation.covers,metadata.coverName)
            val image=BitmapFactory.decodeFile(cached.absolutePath)
            assertTrue(image.width<=320 && image.height<=480)
            val pixel=image.getPixel(0,0);assertTrue(kotlin.math.abs(Color.red(pixel)-Color.blue(pixel))<=8);image.recycle()
            assertTrue(cached.delete());app.presentation.ensure(book)
            metadata=app.database.presentation().get(book.hash)!!;assertTrue(File(app.presentation.covers,metadata.coverName).exists())
            val missing=add("无封面测试","作者乙","none")
            assertEquals("none",app.database.presentation().get(missing.hash)!!.coverState)
            val bad=add("坏封面测试","作者丙","broken")
            val fallback=app.database.presentation().get(bad.hash)!!
            assertNotEquals("ready",fallback.coverState);assertEquals("作者丙",fallback.author)
            assertNotNull(app.repository.book(bad.hash,"local"))
            val pdf=File(app.cacheDir,"presentation-pdf.pdf")
            ins.context.assets.open("sample.pdf").use{input->pdf.outputStream().use{input.copyTo(it)}}
            app.repository.importBook(Uri.fromFile(pdf),app.engine::validate,"presentation-test")
            val pdfBook=app.repository.dao.ownedBooks("presentation-test").first()
            try{app.presentation.ensure(pdfBook);val info=app.database.presentation().get(pdfBook.hash)!!;assertEquals(3,info.pageCount);assertEquals("ready",info.coverState)}finally{app.repository.remove(pdfBook,"presentation-test");pdf.delete()}
        } finally {cleanup()}
    }
    @Test fun bookMenuShowsFullTitleAndRightAlignedClose()=runBlocking {
        var activity:Activity?=null
        val prefs=app.getSharedPreferences("library-ui",0);val previous=prefs.getString("layout",null)
        val connection=app.drive.connection()
        try {
            prefs.edit().putString("layout","list").commit();app.drive.select(null)
            val book=add("一部需要完整显示书名的长篇阅读测试：从第一章到最后一章的故事与回忆","测试作者")
            activity=ins.startActivitySync(Intent(app,LibraryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitText(activity!!,book.title);delay(300)
            ins.runOnMainSync {
                val title=all(activity!!.window.decorView).filterIsInstance<TextView>().first { it.text.toString()==book.title }
                assertEquals(1,title.maxLines);assertEquals(android.text.TextUtils.TruncateAt.END,title.ellipsize)
            }
            click(activity!!,"${book.title}，更多操作")
            fun nodes(node:android.view.accessibility.AccessibilityNodeInfo):List<android.view.accessibility.AccessibilityNodeInfo> =
                listOf(node)+(0 until node.childCount).mapNotNull(node::getChild).flatMap(::nodes)
            val root=withTimeout(5000) {
                var node:android.view.accessibility.AccessibilityNodeInfo?=null
                while(node==null) {
                    node=ins.uiAutomation.rootInActiveWindow?.takeIf { nodes(it).any { item -> item.text=="删除" } }
                    if(node==null)delay(50)
                };checkNotNull(node)
            }
            val views=nodes(root)
            fun bounds(node:android.view.accessibility.AccessibilityNodeInfo)=android.graphics.Rect().also(node::getBoundsInScreen)
            val heading=views.first { it.text==book.title }
            val close=views.first { it.text=="关闭" }
            val screen=bounds(root);val closeRect=bounds(close)
            assertTrue("Menu heading should wrap",bounds(heading).height()>activity!!.dp(32))
            assertTrue("Close action should align right",screen.right-closeRect.right<=activity!!.dp(24))
            assertTrue(closeRect.left>screen.centerX())
            screenshot("book-menu-actions")
            assertTrue(close.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        } finally {
            activity?.let { ins.runOnMainSync { it.finish() } };cleanup();app.drive.select(connection)
            prefs.edit().apply { if(previous==null)remove("layout")else putString("layout",previous) }.commit()
        }
    }
    @Test fun cloudBookMenusOfferBothRemovalActionsAndCancelPreservesBook()=runBlocking {
        val connection=top.steeefanie.leafreader.drive.DriveConnection("ui-removal-test","https://example.invalid","menu-user","菜单测试","user","menu-device","测试阅读器","root",status="connected")
        val previous=app.drive.connection();val prefs=app.getSharedPreferences("library-ui",0);val layout=prefs.getString("layout",null)
        var activity:Activity?=null
        fun nodes(node:android.view.accessibility.AccessibilityNodeInfo):List<android.view.accessibility.AccessibilityNodeInfo> = listOf(node)+(0 until node.childCount).mapNotNull(node::getChild).flatMap(::nodes)
        suspend fun panel(text:String):List<android.view.accessibility.AccessibilityNodeInfo> = withTimeout(5000) {
            while(true){val root=ins.uiAutomation.rootInActiveWindow;val found=root?.let(::nodes).orEmpty();if(found.any{it.text==text})return@withTimeout found;delay(50)}
            @Suppress("UNREACHABLE_CODE") emptyList()
        }
        try {
            val book=add("云端删除菜单验证","自建作者")
            val dao=app.database.drive();dao.putConnection(connection);dao.grant(top.steeefanie.leafreader.drive.BookAccess(connection.ownerKey,book.hash))
            dao.putEntries(listOf(top.steeefanie.leafreader.drive.RemoteEntry(connection.ownerKey,"menu-book","root","file","${book.title}.epub",size=32000,format="epub",hash=book.hash,version="v1")))
            app.drive.select(connection);prefs.edit().putString("layout","list").commit()
            activity=ins.startActivitySync(Intent(app,LibraryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitText(activity!!,book.title);delay(200);click(activity!!,"${book.title}，更多操作")
            var items=panel("从本地和云端删除");assertTrue(items.any{it.text=="仅移除本地副本"});assertTrue(items.any{it.text=="仅移除云端副本"})
            screenshot("library-removal-menu")
            assertTrue(items.first{it.text=="从本地和云端删除"}.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            items=panel("从本地和云端删除？")
            val button=items.first{it.text=="删除"};val bounds=Rect().also(button::getBoundsInScreen)
            assertTrue(activity!!.resources.displayMetrics.widthPixels-bounds.right<=activity!!.dp(24))
            screenshot("cloud-removal-confirmation")
            assertTrue(items.first{it.text=="取消"}.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            assertNotNull(app.repository.book(book.hash,connection.ownerKey))
            ins.runOnMainSync{activity!!.finish()}
            activity=ins.startActivitySync(Intent(app,DriveActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitText(activity!!,book.title);delay(200)
            ins.runOnMainSync {
                val title=all(activity!!.window.decorView).filterIsInstance<TextView>().first{it.text==book.title}
                assertTrue(((title.parent as View).parent as View).performLongClick())
            }
            items=panel("从本地和云端删除");assertTrue(items.any{it.text=="仅移除本地副本"})
            screenshot("cloud-removal-menu")
            assertTrue(items.first{it.text=="关闭"}.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            assertNotNull(app.repository.book(book.hash,connection.ownerKey))
        }finally {
            activity?.let{ins.runOnMainSync{it.finish()}}
            imported.forEach {runCatching{app.repository.remove(it,connection.ownerKey)}};cleanup();app.drive.select(previous)
            prefs.edit().apply {if(layout==null)remove("layout")else putString("layout",layout)}.commit()
            app.database.openHelper.writableDatabase.execSQL("DELETE FROM remote_entries WHERE ownerKey=?",arrayOf(connection.ownerKey))
            app.database.openHelper.writableDatabase.execSQL("DELETE FROM drive_connections WHERE id=?",arrayOf(connection.id))
        }
    }
    @Test fun cachedCloudDirectoryOpensWithoutCredentials()=runBlocking {
        val connection=top.steeefanie.leafreader.drive.DriveConnection("ui-offline-test","https://example.invalid","test-user","界面测试","user","test-device","离线阅读器","root",status="unbound")
        var activity:Activity?=null
        try{
            val book=add("云端的纸页","林川")
            val dao=app.database.drive()
            dao.putConnection(connection);dao.grant(top.steeefanie.leafreader.drive.BookAccess(connection.ownerKey,book.hash))
            dao.putEntries(listOf(
                top.steeefanie.leafreader.drive.RemoteEntry(connection.ownerKey,"folder","root","directory","文学与随笔"),
                top.steeefanie.leafreader.drive.RemoteEntry(connection.ownerKey,"book","root","file",book.title,size=380000,format="epub",hash=book.hash),
                top.steeefanie.leafreader.drive.RemoteEntry(connection.ownerKey,"other","root","file","城市阅读札记.pdf",size=2800000,format="pdf",hash="f".repeat(64))
            ))
            app.drive.select(connection)
            activity=ins.startActivitySync(Intent(app,DriveActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitText(activity!!,book.title);delay(200);screenshot("cloud-directory")
            click(activity!!,"阅读 ${book.title}")
            activity=current(ReaderActivity::class.java)
            val reader=activity as ReaderActivity
            withTimeout(15000){while(true){var loaded=false;ins.runOnMainSync{loaded=reader.supportFragmentManager.findFragmentByTag("navigator")!=null};if(loaded)break;delay(100)}}
            // No credential was stored and the selected profile is unbound: network download cannot succeed.
            assertEquals("unbound",dao.connection(connection.id)!!.status)
        }finally{
            activity?.let{ins.runOnMainSync{it.finish()}};app.drive.select(null)
            imported.forEach{runCatching{app.repository.remove(it,connection.ownerKey)}}
            cleanup()
            app.database.openHelper.writableDatabase.execSQL("DELETE FROM remote_entries WHERE ownerKey=?",arrayOf(connection.ownerKey))
            app.database.openHelper.writableDatabase.execSQL("DELETE FROM drive_connections WHERE id=?",arrayOf(connection.id))
        }
    }
    @Test fun overallProgressNeverUsesChapterPercentage(){
        assertEquals("已开始阅读",ProgressLabels.describe("epub","""{"locations":{"progression":0.9}}"""))
        assertEquals("已读 37%",ProgressLabels.describe("epub","""{"locations":{"progression":0.9,"totalProgression":0.37}}"""))
        assertEquals("第 2 / 3 页",ProgressLabels.describe("pdf","""{"locations":{"position":2}}""",3))
        assertEquals("未读",ProgressLabels.describe("epub",null))
    }
    private fun all(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{all(view.getChildAt(it))}else emptyList()
    private fun assertCapsuleSize(activity:Activity){
        ins.runOnMainSync {
            val up=all(activity.window.decorView).first { it.contentDescription=="向上翻屏" }
            val capsule=up.parent as View
            InstrumentationRegistry.getArguments().getString("expectedDensity")?.toIntOrNull()?.let {
                assertEquals(it,activity.resources.displayMetrics.densityDpi)
            }
            assertEquals(activity.dp(Paper.PAGE_CONTROLS_WIDTH),capsule.width)
            assertEquals(activity.dp(Paper.PAGE_CONTROLS_HEIGHT),capsule.height)
        }
    }
    private fun click(activity:Activity,description:String){ins.runOnMainSync{all(activity.window.decorView).first{it.contentDescription==description}.performClick()};ins.waitForIdleSync()}
    private suspend fun current(type:Class<out Activity>):Activity = withTimeout(10000){var result:Activity?=null;while(result==null){ins.runOnMainSync{result=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull{type.isInstance(it)}};if(result==null)delay(50)};checkNotNull(result)}
    private fun screenshot(name:String){ins.waitForIdleSync();val bitmap=checkNotNull(ins.uiAutomation.takeScreenshot());File(app.cacheDir,"ui-$name${InstrumentationRegistry.getArguments().getString("previewSuffix","")}.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
    private suspend fun waitText(activity:Activity,text:String){withTimeout(10000){while(true){var found=false;ins.runOnMainSync{found=all(activity.window.decorView).filterIsInstance<TextView>().any{it.text.toString().contains(text)}};if(found)break;delay(100)}}}
    @Test fun fourColumnGridTwoColumnListAndPageButtons()=runBlocking {
        var activity:Activity?=null
        val prefs=app.getSharedPreferences("library-ui",0);val previous=prefs.getString("layout",null)
        try{
            prefs.edit().remove("layout").commit();app.drive.select(null)
            repeat(14){add("书架测试 ${it+1}","测试作者")}
            activity=ins.startActivitySync(Intent(app,LibraryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitText(activity!!,"全部书籍");delay(500)
            lateinit var recycler:androidx.recyclerview.widget.RecyclerView
            ins.runOnMainSync{
                recycler=all(activity!!.window.decorView).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().first()
                assertEquals(4,(recycler.layoutManager as androidx.recyclerview.widget.GridLayoutManager).spanCount)
                assertTrue(recycler.width>app.resources.displayMetrics.widthPixels*.9)
            }
            ins.runOnMainSync {
                val views=all(activity!!.window.decorView)
                assertTrue(views.first { it.contentDescription=="宫格视图" }.isSelected)
                assertFalse(views.first { it.contentDescription=="双栏列表视图" }.isSelected)
                val book=views.first { it.contentDescription?.toString()?.startsWith("阅读 书架测试")==true }
                assertEquals(1,all(book).filterIsInstance<TextView>().size)
                assertFalse(all(book).any { it is ImageButton })
                assertTrue(book.isLongClickable)
            }
            screenshot("grid-four")
            click(activity!!,"向下翻屏");delay(200)
            ins.runOnMainSync{assertTrue((recycler.layoutManager as androidx.recyclerview.widget.GridLayoutManager).findFirstVisibleItemPosition()>0)}
            click(activity!!,"向上翻屏");delay(200)
            click(activity!!,"双栏列表视图");delay(300)
            ins.runOnMainSync{assertEquals(2,(recycler.layoutManager as androidx.recyclerview.widget.GridLayoutManager).spanCount)}
            ins.runOnMainSync {
                val views=all(activity!!.window.decorView)
                assertFalse(views.first { it.contentDescription=="宫格视图" }.isSelected)
                assertTrue(views.first { it.contentDescription=="双栏列表视图" }.isSelected)
                val book=views.first { it.contentDescription?.toString()?.startsWith("阅读 书架测试")==true }
                assertTrue(all(book).filterIsInstance<TextView>().any { it.text.toString()=="测试作者" })
                assertTrue(all(book).any { it is ImageButton })
            }
            screenshot("list-two")
            ins.runOnMainSync{activity!!.finish()}
            activity=ins.startActivitySync(Intent(app,LibraryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitText(activity!!,"全部书籍")
            ins.runOnMainSync {
                assertTrue(all(activity!!.window.decorView).first { it.contentDescription=="双栏列表视图" }.isSelected)
            }
            ins.runOnMainSync{val list=all(activity!!.window.decorView).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().first();assertEquals(2,(list.layoutManager as androidx.recyclerview.widget.GridLayoutManager).spanCount)}
        }finally{activity?.let{ins.runOnMainSync{it.finish()}};cleanup();prefs.edit().apply{if(previous==null)remove("layout")else putString("layout",previous)}.commit()}
    }
    @Test fun libraryAuthorSearchNavigationAndScreenshots()=runBlocking {
        var activity:Activity?=null
        try{
            app.drive.select(null)
            val first=add("山海之间","林川")
            add("日常的秩序","周禾")
            add("时间留下的书页：关于城市、行走与生活的长篇阅读测试","陈默")
            add("纸上漫游","许知远")
            val position="""{"href":"one.xhtml","type":"application/xhtml+xml","locations":{"totalProgression":0.37,"progression":0.37}}"""
            app.repository.saveProgress(Progress(first.hash,"epub",position,System.currentTimeMillis()+1000),"local")
            activity=ins.startActivitySync(Intent(app,LibraryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val library=activity!!;waitText(library,"全部书籍");delay(700);screenshot("library")
            fun headerBounds(activity:Activity,text:String):Rect=Rect().also { result ->
                ins.runOnMainSync {
                    val view=all(activity.window.decorView).filterIsInstance<TextView>().first { it.text.toString()==text }
                    val xy=IntArray(2);view.getLocationOnScreen(xy)
                    result.set(xy[0],xy[1],xy[0]+view.width,xy[1]+view.height)
                }
            }
            val libraryHeader=headerBounds(library,"书库");assertCapsuleSize(library)
            click(library,"搜索书籍")
            ins.runOnMainSync{all(library.window.decorView).filterIsInstance<EditText>().first().setText("周禾")}
            waitText(library,"日常的秩序");delay(400)
            ins.runOnMainSync{val text=all(library.window.decorView).filterIsInstance<TextView>().map{it.text.toString()};assertFalse(text.contains("山海之间"))}
            screenshot("search");click(library,"关闭搜索")
            click(library,"设置");activity=current(SettingsActivity::class.java);assertTrue(activity is SettingsActivity);assertNotEquals(0,activity!!.intent.flags and Intent.FLAG_ACTIVITY_NO_ANIMATION);waitText(activity!!,"阅读时显示页码");screenshot("settings")
            val settingsHeader=headerBounds(activity!!,"设置");assertCapsuleSize(activity!!)
            assertEquals(libraryHeader.left,settingsHeader.left);assertEquals(libraryHeader.top,settingsHeader.top)
            ins.runOnMainSync{all(activity!!.window.decorView).first{it.contentDescription?.toString()?.startsWith("默认阅读排版")==true}.performClick()};delay(400);screenshot("typography")
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);click(activity!!,"云盘");activity=current(DriveActivity::class.java);assertTrue(activity is DriveActivity);assertNotEquals(0,activity!!.intent.flags and Intent.FLAG_ACTIVITY_NO_ANIMATION);waitText(activity!!,"连接云盘");screenshot("cloud")
            val cloudHeader=headerBounds(activity!!,"云盘");assertCapsuleSize(activity!!)
            assertEquals(libraryHeader.left,cloudHeader.left);assertEquals(libraryHeader.top,cloudHeader.top)
            if(app.resources.configuration.fontScale>=1.5f){ins.runOnMainSync{all(activity!!.window.decorView).filterIsInstance<ScrollView>().first().fullScroll(View.FOCUS_DOWN)};delay(150);screenshot("cloud-actions")}
            click(activity!!,"连接与账号");activity=current(DriveConnectionActivity::class.java);waitText(activity!!,"连接与账号");delay(400);screenshot("connection");click(activity!!,"返回");activity=current(DriveActivity::class.java)
            activity=ins.startActivitySync(Intent(app,ReaderActivity::class.java).putExtra("bookHash",first.hash).putExtra("ownerKey","local").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val reader=activity as ReaderActivity
            withTimeout(15000){while(true){var ready=false;ins.runOnMainSync{ready=reader.supportFragmentManager.findFragmentByTag("navigator")!=null};if(ready)break;delay(100)}}
            delay(3000)
            var x=0f;var y=0f;ins.runOnMainSync{x=reader.window.decorView.width/2f;y=reader.window.decorView.height/2f}
            val now=android.os.SystemClock.uptimeMillis()
            ins.sendPointerSync(MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0))
            ins.sendPointerSync(MotionEvent.obtain(now,now+80,MotionEvent.ACTION_UP,x,y,0))
            delay(500);ins.runOnMainSync{assertTrue(all(reader.window.decorView).first{it.contentDescription=="目录"}.isShown)}
            screenshot("reader-toolbar")
            val readerBack=android.graphics.Rect()
            ins.runOnMainSync {
                val view=all(reader.window.decorView).first { it.contentDescription=="返回书库" }
                val point=IntArray(2);view.getLocationOnScreen(point)
                readerBack.set(point[0],point[1],point[0]+view.width,point[1]+view.height)
                val title=all(reader.window.decorView).filterIsInstance<TextView>().first { it.contentDescription=="阅读书名" }
                val chapter=all(reader.window.decorView).filterIsInstance<TextView>().first { it.contentDescription=="阅读章节与进度" }
                assertEquals(title.textSize,chapter.textSize,.01f)
                assertTrue(all(reader.window.decorView).first { it.contentDescription=="阅读工具栏分隔线" }.isShown)
            }
            click(reader,"目录")
            fun nodes(node: android.view.accessibility.AccessibilityNodeInfo): List<android.view.accessibility.AccessibilityNodeInfo> =
                listOf(node) + (0 until node.childCount).mapNotNull(node::getChild).flatMap(::nodes)
            val contents = withTimeout(5000) {
                var tree: List<android.view.accessibility.AccessibilityNodeInfo>
                do {
                    tree = ins.uiAutomation.rootInActiveWindow?.let(::nodes).orEmpty()
                    if (tree.none { it.contentDescription == "向下翻屏" }) delay(50)
                } while (tree.none { it.contentDescription == "向下翻屏" })
                tree
            }
            val back = contents.single { it.contentDescription == "返回" }
            val down = contents.single { it.contentDescription == "向下翻屏" }
            val title = contents.first { it.text == "目录" }
            fun bounds(node: android.view.accessibility.AccessibilityNodeInfo) =
                android.graphics.Rect().also(node::getBoundsInScreen)
            assertFalse(contents.any { it.text == "取消" })
            assertTrue(kotlin.math.abs(reader.dp(Paper.PAGE_CONTROLS_WIDTH)/2-bounds(down).width())<=1)
            assertEquals(reader.dp(Paper.PAGE_CONTROLS_HEIGHT),bounds(down).height())
            assertTrue("TOC back moved horizontally",kotlin.math.abs(bounds(back).centerX()-readerBack.centerX())<=2)
            assertTrue("TOC back moved vertically",kotlin.math.abs(bounds(back).centerY()-readerBack.centerY())<=2)
            ins.runOnMainSync { assertFalse(all(reader.window.decorView).first { it.contentDescription=="阅读工具栏分隔线" }.isShown) }
            assertTrue(bounds(back).top > bounds(title).bottom)
            assertTrue(bounds(back).left < bounds(down).left)
            assertTrue(kotlin.math.abs(bounds(back).centerY() - bounds(down).centerY()) <= 2)
            screenshot("reader-toc")
            assertTrue(back.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            ins.waitForIdleSync()
            click(reader,"搜索");delay(200)
            suspend fun searchBook(query:String,expected:(String)->Boolean) {
                val tree=nodes(ins.uiAutomation.rootInActiveWindow)
                val field=tree.first { it.isEditable }
                assertTrue(field.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                    android.os.Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,query) }))
                assertTrue(nodes(ins.uiAutomation.rootInActiveWindow).first { it.contentDescription=="开始搜索" }
                    .performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                withTimeout(15000) {
                    while(nodes(ins.uiAutomation.rootInActiveWindow).none { expected(it.text?.toString().orEmpty()) })delay(100)
                }
                assertFalse(reader.isFinishing)
            }
            searchBook("山川") { it.startsWith("共 ") && it.contains("条") }
            searchBook("没有这个测试词语") { it.startsWith("未找到") }
            assertTrue(nodes(ins.uiAutomation.rootInActiveWindow).single { it.contentDescription=="返回" }
                .performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            ins.waitForIdleSync()
            click(reader,"排版");delay(300);screenshot("reader-typography")
            val typographyBack=nodes(ins.uiAutomation.rootInActiveWindow).single { it.contentDescription=="返回" }
            assertTrue("Typography back moved horizontally",kotlin.math.abs(bounds(typographyBack).centerX()-readerBack.centerX())<=2)
            assertTrue("Typography back moved vertically",kotlin.math.abs(bounds(typographyBack).centerY()-readerBack.centerY())<=2)
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            val nav=reader.supportFragmentManager.findFragmentByTag("navigator") as org.readium.r2.navigator.OverflowableNavigator
            fun tap(px:Float){val time=android.os.SystemClock.uptimeMillis();listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP).forEachIndexed{i,action->val event=MotionEvent.obtain(time,time+i*80L,action,px,y,0);try{ins.sendPointerSync(event)}finally{event.recycle()}}}
            tap(x);delay(200) // Hide the toolbar, then use the actual right reading zone.
            var before="";ins.runOnMainSync{before=nav.currentLocator.value.toJSON().toString()}
            tap(x*1.8f)
            withTimeout(10000){while(true){var changed=false;ins.runOnMainSync{changed=nav.currentLocator.value.toJSON().toString()!=before};if(changed)break;delay(50)}}
            ins.runOnMainSync{assertFalse(all(reader.window.decorView).first{it.contentDescription=="目录"}.isShown)}
        }finally{activity?.let{ins.runOnMainSync{it.finish()}};cleanup();app.drive.select(null)}
    }
}
