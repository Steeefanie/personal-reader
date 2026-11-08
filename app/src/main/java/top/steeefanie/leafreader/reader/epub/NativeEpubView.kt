package top.steeefanie.leafreader.reader.epub

import android.content.Context
import android.graphics.*
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import org.json.JSONObject
import top.steeefanie.leafreader.storage.Bookmark
import top.steeefanie.leafreader.ui.Paper
import top.steeefanie.leafreader.ui.dp
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class ResolvedBookmarkRange(
    val bookmark: Bookmark,
    val bodyStart: Int,
    val bodyEnd: Int
)

class NativeEpubView(context: Context) : View(context) {
    var chapterLayout: ChapterLayout? = null
        private set
    var pageIndex: Int = 0
        private set
    var typeface: Typeface = Typeface.DEFAULT
        private set
    private var bookmarks: List<ResolvedBookmarkRange> = emptyList()
    private var imageProvider: (String) -> Bitmap? = { null }

    // Selection state (chapter-wide bodyStart..bodyEnd offsets)
    var selectionStart: Int = -1
        private set
    var selectionEnd: Int = -1
        private set
    val hasSelection: Boolean get() = selectionStart >= 0 && selectionEnd > selectionStart

    // Callbacks
    var onTapPoint: ((PointF) -> Unit)? = null
    var onSwipeTurn: ((Boolean) -> Unit)? = null
    var onLinkActivated: ((String) -> Unit)? = null
    var onSelectionChanged: ((JSONObject?, RectF?) -> Unit)? = null
    var onBookmarkActivated: ((Bookmark, RectF) -> Unit)? = null

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.BLACK
        fontFeatureSettings = "lnum, pnum"
    }
    private val underlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
    }
    private val selectionFillPaint = Paint().apply {
        color = Color.rgb(216, 216, 216)
        style = Paint.Style.FILL
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val rulePaint = Paint().apply {
        color = Paper.line
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1).toFloat()
    }
    private val quoteBarPaint = Paint().apply {
        color = Color.DKGRAY
        style = Paint.Style.FILL
    }
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val charBuffer = CharArray(1)

    // Handle positions on screen for touch dragging
    private val startHandlePoint = PointF()
    private val endHandlePoint = PointF()
    private var hasHandlePoints = false

    // Touch gesture tracking
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var draggingHandle = 0 // 0 = none, 1 = start handle, 2 = end handle
    private var movedBeyondSlop = false
    private var longPressTriggered = false
    private var selectionWasActiveOnDown = false
    private val longPressRunnable = Runnable {
        if (!movedBeyondSlop && draggingHandle == 0) {
            if (startSelectionAt(downX, downY)) {
                longPressTriggered = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
        }
    }

    init {
        setBackgroundColor(Color.WHITE)
        isClickable = true
        isFocusable = true
    }

    fun updateContent(
        layout: ChapterLayout?,
        page: Int,
        face: Typeface,
        marks: List<ResolvedBookmarkRange>,
        images: (String) -> Bitmap?
    ) {
        val layoutChanged = chapterLayout !== layout || pageIndex != page
        chapterLayout = layout
        pageIndex = page
        typeface = face
        bookmarks = marks
        imageProvider = images
        if (layoutChanged && hasSelection) {
            clearSelection(notify = true)
        }
        invalidate()
    }

    fun updateBookmarks(marks: List<ResolvedBookmarkRange>) {
        bookmarks = marks
        invalidate()
    }

    fun clearSelection(notify: Boolean = true) {
        if (selectionStart == -1 && selectionEnd == -1) return
        selectionStart = -1
        selectionEnd = -1
        hasHandlePoints = false
        draggingHandle = 0
        invalidate()
        if (notify) onSelectionChanged?.invoke(null, null)
    }

    fun currentPage(): LaidOutPage? {
        val layout = chapterLayout ?: return null
        return layout.pages.getOrNull(pageIndex.coerceIn(0, (layout.pages.size - 1).coerceAtLeast(0)))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val layout = chapterLayout ?: return
        val page = currentPage() ?: return
        val density = resources.displayMetrics.density
        underlinePaint.strokeWidth = max(2f, 1.6f * density)

        hasHandlePoints = false
        var firstSelLeft = 0f
        var firstSelTop = 0f
        var firstSelBottom = 0f
        var lastSelRight = 0f
        var lastSelTop = 0f
        var lastSelBottom = 0f
        var foundSelectionOnPage = false

        // 1. Draw selection highlights behind text
        if (hasSelection) {
            for (line in page.lines) {
                if (line.charCount == 0 || line.bodyEnd <= selectionStart || line.bodyStart >= selectionEnd) continue
                val startIdx = (selectionStart - line.bodyStart).coerceIn(0, line.charCount - 1)
                val endIdx = (selectionEnd - line.bodyStart - 1).coerceIn(startIdx, line.charCount - 1)
                val left = line.charLeft[startIdx]
                val right = line.charRight[endIdx]
                if (right > left) {
                    canvas.drawRect(left, line.top + density, right, line.bottom - density, selectionFillPaint)
                    if (!foundSelectionOnPage) {
                        firstSelLeft = left
                        firstSelTop = line.top
                        firstSelBottom = line.bottom
                        foundSelectionOnPage = true
                    }
                    lastSelRight = right
                    lastSelTop = line.top
                    lastSelBottom = line.bottom
                }
            }
        }

        // 2. Draw lines, images, rules, and bookmark underlines
        textPaint.typeface = typeface
        for (line in page.lines) {
            if (line.isRule) {
                val x0 = line.charLeft.firstOrNull() ?: layout.marginHorizontal
                val x1 = line.charRight.firstOrNull() ?: (width - layout.marginHorizontal)
                canvas.drawLine(x0, line.baseline, x1, line.baseline, rulePaint)
                continue
            }
            if (line.imageHref != null && line.imageRect != null) {
                val bmp = imageProvider(line.imageHref)
                if (bmp != null && !bmp.isRecycled) {
                    canvas.drawBitmap(bmp, null, line.imageRect, imagePaint)
                } else {
                    canvas.drawRect(line.imageRect, rulePaint)
                    textPaint.textSize = 12f * density
                    textPaint.isFakeBoldText = false
                    textPaint.textSkewX = 0f
                    val label = "[图片]"
                    val lw = textPaint.measureText(label)
                    canvas.drawText(label, line.imageRect.centerX() - lw * 0.5f, line.imageRect.centerY() + 4f * density, textPaint)
                }
                continue
            }

            val block = layout.chapter.blocks.getOrNull(line.blockIndex)
            if (block?.kind == BlockKind.QUOTE) {
                val barX = layout.marginHorizontal + layout.baseTextSizePx * 0.45f
                canvas.drawRect(barX, line.top + 2f * density, barX + 2.5f * density, line.bottom - 2f * density, quoteBarPaint)
            }
            if (line.isListItemBullet) {
                val bulletX = layout.marginHorizontal + layout.baseTextSizePx * 0.25f
                textPaint.textSize = line.textSizePx
                textPaint.isFakeBoldText = false
                textPaint.textSkewX = 0f
                charBuffer[0] = '•'
                canvas.drawText(charBuffer, 0, 1, bulletX, line.baseline, textPaint)
            }

            val count = line.charCount
            if (count == 0) continue

            for (i in 0 until count) {
                val ch = line.text[i]
                val flag = line.charFlags[i]
                if ((flag and EpubTypesetter.FLAG_WHITESPACE) != 0) continue

                val isSup = (flag and EpubTypesetter.FLAG_SUP) != 0
                val isSub = (flag and EpubTypesetter.FLAG_SUB) != 0
                textPaint.textSize = if (isSup || isSub) line.textSizePx * 0.72f else line.textSizePx
                textPaint.isFakeBoldText = (flag and EpubTypesetter.FLAG_BOLD) != 0
                textPaint.textSkewX = if ((flag and EpubTypesetter.FLAG_ITALIC) != 0) -0.20f else 0f

                val drawY = when {
                    isSup -> line.baseline - line.textSizePx * 0.32f
                    isSub -> line.baseline + line.textSizePx * 0.15f
                    else -> line.baseline
                }
                charBuffer[0] = ch
                canvas.drawText(charBuffer, 0, 1, line.charLeft[i], drawY, textPaint)

                if ((flag and EpubTypesetter.FLAG_LINK) != 0) {
                    val linkY = line.baseline + 3f * density
                    canvas.drawLine(line.charLeft[i], linkY, line.charRight[i], linkY, rulePaint)
                }
            }

            // 3. Draw bookmark underlines strictly under non-whitespace characters
            if (bookmarks.isNotEmpty() && line.bodyEnd > line.bodyStart) {
                val underlineY = min(line.bottom - density, line.baseline + 4f * density)
                for (mark in bookmarks) {
                    if (mark.bodyEnd <= line.bodyStart || mark.bodyStart >= line.bodyEnd) continue
                    val sIdx = (mark.bodyStart - line.bodyStart).coerceIn(0, count - 1)
                    val eIdx = (mark.bodyEnd - line.bodyStart - 1).coerceIn(sIdx, count - 1)
                    var runStart = -1
                    for (i in sIdx..eIdx) {
                        val isSpace = (line.charFlags[i] and EpubTypesetter.FLAG_WHITESPACE) != 0
                        if (!isSpace) {
                            if (runStart == -1) runStart = i
                        } else if (runStart != -1) {
                            canvas.drawLine(line.charLeft[runStart], underlineY, line.charRight[i - 1], underlineY, underlinePaint)
                            runStart = -1
                        }
                    }
                    if (runStart != -1) {
                        canvas.drawLine(line.charLeft[runStart], underlineY, line.charRight[eIdx], underlineY, underlinePaint)
                    }
                }
            }
        }

        // 4. Draw selection handles on top of text
        if (foundSelectionOnPage) {
            val radius = 5.5f * density
            val stemWidth = 2f * density
            // Start handle: stem + knob at top
            canvas.drawRect(firstSelLeft - stemWidth * 0.5f, firstSelTop, firstSelLeft + stemWidth * 0.5f, firstSelBottom, handlePaint)
            canvas.drawCircle(firstSelLeft, firstSelTop - radius * 0.6f, radius, handlePaint)
            // End handle: stem + knob at bottom
            canvas.drawRect(lastSelRight - stemWidth * 0.5f, lastSelTop, lastSelRight + stemWidth * 0.5f, lastSelBottom, handlePaint)
            canvas.drawCircle(lastSelRight, lastSelBottom + radius * 0.6f, radius, handlePaint)

            startHandlePoint.set(firstSelLeft, firstSelTop)
            endHandlePoint.set(lastSelRight, lastSelBottom)
            hasHandlePoints = true
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                movedBeyondSlop = false
                longPressTriggered = false
                selectionWasActiveOnDown = hasSelection
                draggingHandle = hitTestHandle(downX, downY)
                if (draggingHandle != 0) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    onSelectionChanged?.invoke(null, null)
                } else {
                    postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!movedBeyondSlop && hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                    movedBeyondSlop = true
                    removeCallbacks(longPressRunnable)
                }
                if (draggingHandle != 0) {
                    updateHandleDrag(event.x, event.y)
                    return true
                }
                if (longPressTriggered) {
                    updateDragSelectionFromLongPress(event.x, event.y)
                    return true
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                if (draggingHandle != 0 || longPressTriggered) {
                    draggingHandle = 0
                    notifyCurrentSelection()
                    return true
                }
                if (movedBeyondSlop) {
                    // Block swipe page turns if text selection was active
                    if (selectionWasActiveOnDown || hasSelection) return true
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (abs(dx) > context.dp(48) && abs(dx) > abs(dy) * 1.2f) {
                        onSwipeTurn?.invoke(dx < 0)
                    }
                    return true
                }
                // Single tap
                if (hasSelection) {
                    clearSelection(notify = true)
                    return true
                }
                // Check if tap hit an underlined bookmark
                val hitMark = findBookmarkAt(event.x, event.y)
                if (hitMark != null) {
                    onBookmarkActivated?.invoke(hitMark.first, hitMark.second)
                    return true
                }
                // Check if tap hit a link
                val hitLink = findLinkAt(event.x, event.y)
                if (hitLink != null) {
                    onLinkActivated?.invoke(hitLink)
                    return true
                }
                onTapPoint?.invoke(PointF(event.x, event.y))
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                draggingHandle = 0
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitTestHandle(x: Float, y: Float): Int {
        if (!hasSelection || !hasHandlePoints) return 0
        val hitRadius = context.dp(28).toFloat()
        val dStart = hypot((x - startHandlePoint.x).toDouble(), (y - startHandlePoint.y).toDouble()).toFloat()
        val dEnd = hypot((x - endHandlePoint.x).toDouble(), (y - endHandlePoint.y).toDouble()).toFloat()
        return when {
            dStart <= hitRadius && dStart <= dEnd -> 1
            dEnd <= hitRadius -> 2
            else -> 0
        }
    }

    private fun startSelectionAt(x: Float, y: Float): Boolean {
        val layout = chapterLayout ?: return false
        val page = currentPage() ?: return false
        val hitOffset = charOffsetAt(page, x, y, strictVertical = false) ?: return false
        val full = layout.chapter.fullText
        if (full.isEmpty() || hitOffset !in full.indices) return false

        // Expand slightly around the touched character (avoiding whitespace)
        var start = hitOffset
        var end = (hitOffset + 1).coerceAtMost(full.length)
        while (start < full.length && EpubChapterParser.isSpaceChar(full[start])) start++
        if (start >= full.length) return false
        end = (start + 1).coerceAtMost(full.length)

        // If on an ASCII word, select the whole word; if on CJK, select up to 2 non-space/non-punct chars in the same block
        val block = layout.chapter.blocks.firstOrNull { start in it.bodyStart until it.bodyEnd }
        val minBound = block?.bodyStart ?: page.bodyStart
        val maxBound = block?.bodyEnd ?: page.bodyEnd
        if (full[start].code < 128 && Character.isLetterOrDigit(full[start])) {
            while (start > minBound && full[start - 1].code < 128 && Character.isLetterOrDigit(full[start - 1])) start--
            while (end < maxBound && full[end].code < 128 && Character.isLetterOrDigit(full[end])) end++
        } else {
            while (end < min(start + 2, maxBound) && !EpubChapterParser.isSpaceChar(full[end])) end++
        }

        selectionStart = start
        selectionEnd = end
        invalidate()
        notifyCurrentSelection()
        return true
    }

    private fun updateHandleDrag(x: Float, y: Float) {
        val page = currentPage() ?: return
        val offset = charOffsetAt(page, x, y, strictVertical = false) ?: return
        if (draggingHandle == 1) {
            val nextStart = offset.coerceIn(page.bodyStart, (selectionEnd - 1).coerceAtLeast(page.bodyStart))
            if (nextStart != selectionStart) {
                selectionStart = nextStart
                invalidate()
            }
        } else if (draggingHandle == 2) {
            val nextEnd = (offset + 1).coerceIn((selectionStart + 1).coerceAtMost(page.bodyEnd), page.bodyEnd)
            if (nextEnd != selectionEnd) {
                selectionEnd = nextEnd
                invalidate()
            }
        }
    }

    private fun updateDragSelectionFromLongPress(x: Float, y: Float) {
        val page = currentPage() ?: return
        val offset = charOffsetAt(page, x, y, strictVertical = false) ?: return
        if (offset >= selectionStart) {
            val nextEnd = (offset + 1).coerceAtMost(page.bodyEnd)
            if (nextEnd > selectionStart && nextEnd != selectionEnd) {
                selectionEnd = nextEnd
                invalidate()
            }
        }
    }

    private fun charOffsetAt(page: LaidOutPage, x: Float, y: Float, strictVertical: Boolean): Int? {
        val textLines = page.lines.filter { it.charCount > 0 && it.bodyEnd > it.bodyStart }
        if (textLines.isEmpty()) return null
        val line = if (strictVertical) {
            val pad = context.dp(6).toFloat()
            textLines.firstOrNull { y >= it.top - pad && y <= it.bottom + pad } ?: return null
        } else {
            textLines.minByOrNull { l ->
                when {
                    y < l.top -> l.top - y
                    y > l.bottom -> y - l.bottom
                    else -> 0f
                }
            } ?: return null
        }
        var bestIdx = 0
        var bestDist = Float.MAX_VALUE
        for (i in 0 until line.charCount) {
            val center = (line.charLeft[i] + line.charRight[i]) * 0.5f
            val dist = abs(x - center)
            if (dist < bestDist) {
                bestDist = dist
                bestIdx = i
            }
        }
        return (line.bodyStart + bestIdx).coerceIn(line.bodyStart, line.bodyEnd - 1)
    }

    private fun findBookmarkAt(x: Float, y: Float): Pair<Bookmark, RectF>? {
        if (bookmarks.isEmpty()) return null
        val page = currentPage() ?: return null
        val pad = context.dp(8).toFloat()
        val line = page.lines.firstOrNull { it.charCount > 0 && y >= it.top - pad && y <= it.bottom + pad } ?: return null
        for (i in 0 until line.charCount) {
            if ((line.charFlags[i] and EpubTypesetter.FLAG_WHITESPACE) != 0) continue
            if (x >= line.charLeft[i] - pad && x <= line.charRight[i] + pad) {
                val charBodyOffset = line.bodyStart + i
                val hit = bookmarks.firstOrNull { charBodyOffset in it.bodyStart until it.bodyEnd }
                if (hit != null) {
                    val rect = computeRangeBoundsOnPage(page, hit.bodyStart, hit.bodyEnd) ?: RectF(line.charLeft[i], line.top, line.charRight[i], line.bottom)
                    return hit.bookmark to rect
                }
            }
        }
        return null
    }

    private fun findLinkAt(x: Float, y: Float): String? {
        val layout = chapterLayout ?: return null
        val page = currentPage() ?: return null
        val pad = context.dp(6).toFloat()
        val line = page.lines.firstOrNull { it.charCount > 0 && y >= it.top - pad && y <= it.bottom + pad } ?: return null
        val block = layout.chapter.blocks.getOrNull(line.blockIndex) ?: return null
        for (i in 0 until line.charCount) {
            if ((line.charFlags[i] and EpubTypesetter.FLAG_LINK) == 0) continue
            if (x >= line.charLeft[i] - pad && x <= line.charRight[i] + pad) {
                val blockCharOffset = line.blockStartOffset + i
                val span = block.spans.firstOrNull { it.kind == SpanKind.LINK && blockCharOffset in it.start until it.end }
                if (!span?.href.isNullOrBlank()) return span?.href
            }
        }
        return null
    }

    fun computeRangeBoundsOnPage(page: LaidOutPage, bodyStart: Int, bodyEnd: Int): RectF? {
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (line in page.lines) {
            if (line.charCount == 0 || line.bodyEnd <= bodyStart || line.bodyStart >= bodyEnd) continue
            val sIdx = (bodyStart - line.bodyStart).coerceIn(0, line.charCount - 1)
            val eIdx = (bodyEnd - line.bodyStart - 1).coerceIn(sIdx, line.charCount - 1)
            left = min(left, line.charLeft[sIdx])
            top = min(top, line.top)
            right = max(right, line.charRight[eIdx])
            bottom = max(bottom, line.bottom)
        }
        return if (right > left && bottom > top) RectF(left, top, right, bottom) else null
    }

    /** Returns the screen coordinates (x, y) of a character offset on the current page, for testing or popups. */
    fun charCenterOnPage(bodyOffset: Int): PointF? {
        val page = currentPage() ?: return null
        for (line in page.lines) {
            if (line.charCount > 0 && bodyOffset in line.bodyStart until line.bodyEnd) {
                val idx = (bodyOffset - line.bodyStart).coerceIn(0, line.charCount - 1)
                return PointF((line.charLeft[idx] + line.charRight[idx]) * 0.5f, (line.top + line.bottom) * 0.5f)
            }
        }
        return null
    }

    private fun notifyCurrentSelection() {
        val layout = chapterLayout ?: return
        val page = currentPage() ?: return
        if (!hasSelection) {
            onSelectionChanged?.invoke(null, null)
            return
        }
        val full = layout.chapter.fullText
        var s = selectionStart.coerceIn(0, full.length)
        var e = selectionEnd.coerceIn(s, full.length)
        while (s < e && EpubChapterParser.isSpaceChar(full[s])) s++
        while (e > s && EpubChapterParser.isSpaceChar(full[e - 1])) e--
        if (e <= s) {
            clearSelection(notify = true)
            return
        }
        val quote = full.substring(s, e)
        if (quote.isBlank() || quote.length > 16000) {
            onSelectionChanged?.invoke(null, null)
            return
        }
        val block = layout.chapter.blocks.firstOrNull { s in it.bodyStart..it.bodyEnd }
            ?: layout.chapter.blocks.firstOrNull()
        val cssSelector = if (block != null && e <= block.bodyEnd) block.cssSelector else "body"
        val localStart = if (block != null && e <= block.bodyEnd) (s - block.bodyStart).coerceAtLeast(0) else s
        val localEnd = if (block != null && e <= block.bodyEnd) (e - block.bodyStart).coerceAtLeast(localStart + 1) else e

        val json = JSONObject().apply {
            put("cssSelector", cssSelector)
            put("readerTextRange", JSONObject().apply {
                put("version", 1)
                put("unit", "utf16")
                put("start", localStart)
                put("end", localEnd)
                put("bodyStart", s)
                put("bodyEnd", e)
            })
            put("text", JSONObject().apply {
                put("highlight", quote)
                put("before", full.substring((s - 32).coerceAtLeast(0), s))
                put("after", full.substring(e, (e + 32).coerceAtMost(full.length)))
            })
        }
        val rect = computeRangeBoundsOnPage(page, s, e)
        onSelectionChanged?.invoke(json, rect)
    }
}
