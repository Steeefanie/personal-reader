package top.steeefanie.leafreader

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import top.steeefanie.leafreader.storage.*
import top.steeefanie.leafreader.reader.ReadingEngine
import top.steeefanie.leafreader.reader.ReaderActivity
import top.steeefanie.leafreader.drive.*
import android.app.Activity
import android.os.Bundle
import top.steeefanie.leafreader.library.*

class ReaderApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database by lazy { Room.databaseBuilder(this, ReaderDatabase::class.java, "reader.db").addMigrations(DRIVE_MIGRATION_1_2, PRESENTATION_MIGRATION_2_3, BOOK_DELETION_MIGRATION_3_4, LOCAL_ONLY_MIGRATION_4_5).build() }
    val repository by lazy { Repository(this, database) }
    val presentation by lazy { BookPresentation(this) }
    val settings by lazy { Settings(this) }
    val fonts by lazy { top.steeefanie.leafreader.reader.fonts.SystemFontCatalog(this) }
    val engine by lazy { ReadingEngine(this, fonts) }
    val drive by lazy { DriveService(this, repository, applicationScope) }
    override fun onCreate() {
        super.onCreate()
        drive
        applicationScope.launch { runCatching { fonts.families() } }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            private var reading = 0
            override fun onActivityStarted(activity: Activity) {
                if (activity is ReaderActivity && reading++ == 0) drive.setReadingActive(true)
                if (started++ == 0) drive.foreground(true)
            }
            override fun onActivityStopped(activity: Activity) {
                if (activity is ReaderActivity && --reading == 0) drive.setReadingActive(false)
                if (--started == 0) drive.foreground(false)
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
