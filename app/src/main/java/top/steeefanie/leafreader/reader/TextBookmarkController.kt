@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package top.steeefanie.leafreader.reader

import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.widget.PopupWindow
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import top.steeefanie.leafreader.reader.epub.NativeEpubNavigatorFragment
import top.steeefanie.leafreader.storage.Bookmark
import top.steeefanie.leafreader.ui.*

/** Native-canvas text selection handles plus a flat, shadow-free bookmark action near the selection. */
class TextBookmarkController(
    private val activity: AppCompatActivity,
    @Suppress("unused") private val scope: CoroutineScope,
    private val epub: () -> NativeEpubNavigatorFragment?,
    private val selectionStarted: () -> Unit,
    private val add: (Locator) -> Unit,
    private val remove: (Bookmark) -> Unit,
) {
    private var popup: PopupWindow? = null
    private var selection: JSONObject? = null
    private var showingSelection = false
    private var dismissedTapUntil = 0L
    val isSelecting get() = epub()?.hasSelection == true
    val hasPopup get() = popup?.isShowing == true

    fun attach() {
        val navigator = epub() ?: return
        navigator.onSelection = { json, rect ->
            selection = json
            if (json == null) { if (showingSelection) dismissPopup() }
            else {
                selectionStarted()
                showAction("添加书签", rect, true) { saveSelection() }
            }
        }
        navigator.onBookmarkTap = { mark, rect ->
            finishSelection(); selectionStarted()
            showAction("取消书签", rect, false) { remove(mark); dismissPopup() }
        }
    }

    fun render(bookmarks: List<Bookmark>) {
        epub()?.setBookmarks(bookmarks.filter { !it.deleted && TextBookmarks.isText(it) })
    }

    private fun saveSelection() {
        val navigator = epub() ?: return
        val json = selection ?: return
        try {
            val range = json.getJSONObject("readerTextRange")
            val base = navigator.currentLocator.value
            // Point the bookmark's own position at the selected text, not at the page start.
            val locations = base.locations.copy(otherLocations = base.locations.otherLocations + ("nativeBodyOffset" to range.getInt("bodyStart")))
            val captured = TextBookmarks.capture(base.copy(locations = locations).toJSON().toString(), json)
            add(checkNotNull(Locator.fromJSON(JSONObject(captured))))
            finishSelection()
        } catch (_: Exception) {
            activity.message("无法保存此文字范围，请重新选择，最多选择 16000 个字符")
        }
    }

    private fun showAction(title: String, rect: RectF?, isSelection: Boolean, action: () -> Unit) {
        dismissPopup(); showingSelection = isSelection
        val button = activity.button(title) { action() }.apply {
            background = activity.paperBackground(Color.WHITE, Paper.INK, 24)
            contentDescription = title
        }
        val window = PopupWindow(button, activity.dp(144), activity.dp(48), false).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); elevation = 0f
            animationStyle = 0; isOutsideTouchable = false; isClippingEnabled = true
        }
        popup = window
        window.setOnDismissListener {
            if (popup === window) {
                popup = null; showingSelection = false
                dismissedTapUntil = android.os.SystemClock.uptimeMillis() + 250
            }
        }
        val root = activity.window.decorView
        val position = IntArray(2); epub()?.publicationView?.getLocationOnScreen(position)
        val x = (position[0] + (rect?.centerX() ?: (root.width / 2f)) - activity.dp(72))
            .toInt().coerceIn(activity.dp(12), (root.width - activity.dp(156)).coerceAtLeast(activity.dp(12)))
        val top = position[1] + (rect?.top ?: activity.dp(96).toFloat())
        val bottom = position[1] + (rect?.bottom ?: activity.dp(96).toFloat())
        val y = (if (top - activity.dp(72) > position[1]) top - activity.dp(68) else bottom + activity.dp(20))
            .toInt().coerceIn(activity.dp(24), (root.height - activity.dp(64)).coerceAtLeast(activity.dp(24)))
        window.showAtLocation(root, Gravity.TOP or Gravity.START, x, y)
    }

    fun dismissPopup() {
        val current = popup; popup = null; showingSelection = false
        current?.dismiss()
    }

    fun consumesDismissalTap() = android.os.SystemClock.uptimeMillis() < dismissedTapUntil
    fun finishSelection() { dismissPopup(); selection = null; epub()?.clearSelection() }
    fun close() { finishSelection(); epub()?.let { it.onSelection = null; it.onBookmarkTap = null } }
    companion object { const val GROUP = "reader-text-bookmarks" }
}
