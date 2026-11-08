package top.steeefanie.leafreader.drive

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Durable per-connection work; re-reading the index skips already accepted ETags after a restart. */
internal class FullSyncQueue(private val preferences: SharedPreferences) {
    fun pending(id: String) = preferences.getBoolean("full_pending_$id", false)
    fun due(id: String, now: Long) = pending(id) && now >= preferences.getLong("full_retry_$id", 0L)

    suspend fun request(id: String, retryAt: Long = 0L) = withContext(Dispatchers.IO) {
        check(preferences.edit().putBoolean("full_pending_$id", true)
            .putLong("full_retry_$id", retryAt).commit()) { "同步待办保存失败" }
    }

    suspend fun complete(id: String) = withContext(Dispatchers.IO) {
        check(preferences.edit().remove("full_pending_$id").remove("full_retry_$id").commit()) {
            "同步待办保存失败"
        }
    }
}
