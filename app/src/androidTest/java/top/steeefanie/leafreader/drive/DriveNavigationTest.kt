package top.steeefanie.leafreader.drive

import android.app.Activity
import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import top.steeefanie.leafreader.ReaderApplication
import top.steeefanie.leafreader.ui.SettingsActivity
import top.steeefanie.leafreader.ui.dp
import java.util.UUID

/** Offline fixtures only: no credentials, authorization, downloads, or external server. */
class DriveNavigationTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val app get()=ins.targetContext.applicationContext as ReaderApplication
    private val opened=mutableListOf<Activity>()
    private fun all(view:View):List<View> = listOf(view)+if(view is ViewGroup)
        (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun start(type:Class<out Activity>)=ins.startActivitySync(
        Intent(app,type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).also { opened+=it }
    private suspend fun current(type:Class<out Activity>):Activity=withTimeout(10000) {
        var found:Activity?=null
        while(found==null) {
            ins.runOnMainSync { found=ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).firstOrNull { type.isInstance(it) } }
            if(found==null)delay(50)
        }
        checkNotNull(found).also { opened+=it }
    }
    private fun click(activity:Activity,description:String) {
        ins.runOnMainSync { assertTrue(all(activity.window.decorView).first {
            it.contentDescription?.toString()?.startsWith(description)==true }.performClick()) }
        ins.waitForIdleSync()
    }
    private suspend fun waitText(activity:Activity,text:String)=withTimeout(10000) {
        while(true) {
            var found=false
            ins.runOnMainSync { found=all(activity.window.decorView).filterIsInstance<TextView>()
                .any { it.text.toString().contains(text) } }
            if(found)break
            delay(50)
        }
    }
    private fun nodes(node:AccessibilityNodeInfo):List<AccessibilityNodeInfo> = listOf(node)+
        (0 until node.childCount).mapNotNull(node::getChild).flatMap(::nodes)
    private suspend fun activeNode(match:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo=withTimeout(10000) {
        var found:AccessibilityNodeInfo?=null
        while(found==null) {
            found=ins.uiAutomation.rootInActiveWindow?.let(::nodes)?.firstOrNull(match)
            if(found==null)delay(50)
        }
        checkNotNull(found)
    }
    private suspend fun childReturn() {
        assertTrue(activeNode { it.contentDescription=="返回" }
            .performAction(AccessibilityNodeInfo.ACTION_CLICK))
        ins.waitForIdleSync()
        activeNode { it.text=="连接与账号" }
    }
    private suspend fun finishOpened() {
        ins.runOnMainSync { opened.distinct().reversed().forEach { if(!it.isDestroyed)it.finish() } }
        ins.waitForIdleSync();opened.clear();delay(100)
    }
    private suspend fun fixture(body:suspend (DriveConnection)->Unit) {
        val previous=app.drive.connection()
        val id="navigation-test-${UUID.randomUUID()}"
        val connection=DriveConnection(id,"https://example.invalid","navigation-user","离线导航用户",
            "user","navigation-device","测试阅读器","root",status="unbound")
        try {
            app.database.drive().putConnection(connection);app.drive.select(connection)
            body(connection)
        } finally {
            finishOpened();app.drive.select(previous)
            app.database.openHelper.writableDatabase.execSQL("DELETE FROM remote_entries WHERE ownerKey=?",arrayOf(connection.ownerKey))
            app.database.openHelper.writableDatabase.execSQL("DELETE FROM drive_connections WHERE id=?",arrayOf(id))
        }
    }

    @Test fun settingsReturnsDirectlyToSettings()=runBlocking {
        val previous=app.drive.connection()
        try {
            app.drive.select(null)
            val settings=start(SettingsActivity::class.java)
            click(settings,"连接与账号")
            var accounts=current(DriveConnectionActivity::class.java)
            waitText(accounts,"扫码连接")
            assertNotEquals(0,accounts.intent.flags and Intent.FLAG_ACTIVITY_NO_ANIMATION)
            click(accounts,"返回")
            assertSame(settings,current(SettingsActivity::class.java))
            click(settings,"连接与账号")
            accounts=current(DriveConnectionActivity::class.java);waitText(accounts,"扫码连接")
            ins.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            assertSame(settings,current(SettingsActivity::class.java))
            ins.runOnMainSync { assertTrue(ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).none { it is DriveActivity }) }
        } finally { finishOpened();app.drive.select(previous) }
    }

    @Test fun cloudKeepsDirectoryScrollAndRestoresAfterRecreation()=runBlocking { fixture { connection ->
        val entries=listOf(RemoteEntry(connection.ownerKey,"folder","root","directory","目录测试"))+
            (0 until 80).map { RemoteEntry(connection.ownerKey,"book-$it","folder","file","本地目录 $it.epub",format="epub") }
        app.database.drive().putEntries(entries)
        var drive=start(DriveActivity::class.java)
        waitText(drive,"目录测试");click(drive,"打开 目录测试");waitText(drive,"我的书库 / 目录测试")
        lateinit var list:RecyclerView
        withTimeout(10000) {
            while(true) {
                var ready=false
                ins.runOnMainSync {
                    list=all(drive.window.decorView).filterIsInstance<RecyclerView>().first()
                    ready=list.adapter!!.itemCount==80
                }
                if(ready)break
                delay(50)
            }
        }
        ins.runOnMainSync { (list.layoutManager as GridLayoutManager).scrollToPositionWithOffset(40,-drive.dp(9)) }
        delay(300)
        var position=0;var offset=0
        ins.runOnMainSync {
            val manager=list.layoutManager as GridLayoutManager
            position=manager.findFirstVisibleItemPosition();offset=manager.findViewByPosition(position)!!.top
            assertTrue(position>=38)
        }
        click(drive,"连接与账号")
        val accounts=current(DriveConnectionActivity::class.java);waitText(accounts,"离线导航用户")
        click(accounts,"返回");assertSame(drive,current(DriveActivity::class.java));delay(300)
        fun assertLocation() {
            ins.runOnMainSync {
                list=all(drive.window.decorView).filterIsInstance<RecyclerView>().first()
                val manager=list.layoutManager as GridLayoutManager
                assertEquals(position,manager.findFirstVisibleItemPosition())
                assertEquals(offset,manager.findViewByPosition(position)!!.top)
            }
        }
        waitText(drive,"我的书库 / 目录测试");assertLocation()
        val old=drive
        ins.runOnMainSync { old.recreate() }
        withTimeout(10000) { do { drive=current(DriveActivity::class.java);if(drive===old)delay(50) } while(drive===old) }
        waitText(drive,"我的书库 / 目录测试");delay(400);assertLocation()
    } }

    @Test fun unbindConfirmationCancelKeepsAccountAndReturnsToCloud()=runBlocking { fixture { connection ->
        val drive=start(DriveActivity::class.java);waitText(drive,"离线导航用户")
        click(drive,"连接与账号")
        val accounts=current(DriveConnectionActivity::class.java);waitText(accounts,"离线导航用户")
        click(accounts,"解除设备绑定")
        activeNode {it.text=="解除当前设备绑定？"}
        assertTrue(activeNode {it.text=="取消"}.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        waitText(accounts,"离线导航用户");assertEquals(connection.id,app.drive.connection()!!.id)
        click(accounts,"返回");assertSame(drive,current(DriveActivity::class.java));waitText(drive,"离线导航用户")
    } }
}
