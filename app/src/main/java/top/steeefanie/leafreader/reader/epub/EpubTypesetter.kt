package top.steeefanie.leafreader.reader.epub

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import top.steeefanie.leafreader.storage.ReadingSettings
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class LaidOutLine(
    val blockIndex: Int,
    val blockStartOffset: Int,
    val blockEndOffset: Int,
    val bodyStart: Int,
    val bodyEnd: Int,
    val text: String,
    val top: Float,
    val bottom: Float,
    val baseline: Float,
    val charLeft: FloatArray,
    val charRight: FloatArray,
    val charFlags: IntArray,
    val textSizePx: Float,
    val isRule: Boolean = false,
    val imageHref: String? = null,
    val imageRect: RectF? = null,
    val isListItemBullet: Boolean = false
) {
    val charCount: Int get() = text.length
}

data class LaidOutPage(
    val pageIndex: Int,
    val lines: List<LaidOutLine>,
    val bodyStart: Int,
    val bodyEnd: Int,
    val firstBlockIndex: Int
)

data class ChapterLayout(
    val chapter: ParsedChapter,
    val pages: List<LaidOutPage>,
    val width: Int,
    val height: Int,
    val marginHorizontal: Float,
    val marginTop: Float,
    val marginBottom: Float,
    val baseTextSizePx: Float
)

object EpubTypesetter {
    const val FLAG_BOLD = 1
    const val FLAG_ITALIC = 2
    const val FLAG_SUP = 4
    const val FLAG_SUB = 8
    const val FLAG_CODE = 16
    const val FLAG_LINK = 32
    const val FLAG_WHITESPACE = 64

    private const val NO_START_PUNCT = "，。、；：？！）》」』】〉》”’—…·.,:;?!)]}%"
    private const val NO_END_PUNCT = "（《「『【〈《“‘([{"

    fun layout(
        chapter: ParsedChapter,
        width: Int,
        height: Int,
        density: Float,
        settings: ReadingSettings,
        typeface: Typeface,
        imageSizeProvider: (String) -> Pair<Int, Int>? = { null }
    ): ChapterLayout {
        val safeWidth = max(width, 200)
        val safeHeight = max(height, 200)
        val baseFontSizePx = (settings.fontSize * density).toFloat().coerceAtLeast(12f)
        val marginH = (24f * density * settings.margins.toFloat()).coerceIn(12f * density, safeWidth * 0.28f)
        val marginTop = 18f * density
        val marginBottom = 18f * density

        val contentLeft = marginH
        val contentRight = (safeWidth - marginH).coerceAtLeast(contentLeft + 80f)
        val contentWidth = contentRight - contentLeft
        val contentTop = marginTop
        val contentBottom = (safeHeight - marginBottom).coerceAtLeast(contentTop + baseFontSizePx * 2f)
        val maxPageHeight = contentBottom - contentTop

        val baseLineHeightPx = baseFontSizePx * settings.lineHeight.toFloat()
        val paraExtraGapPx = baseFontSizePx * settings.paragraphSpacing.toFloat()

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.typeface = typeface
            this.textSize = baseFontSizePx
            this.fontFeatureSettings = "lnum, pnum"
        }

        val pages = mutableListOf<LaidOutPage>()
        val currentLines = mutableListOf<LaidOutLine>()
        var y = contentTop

        fun flushPage() {
            if (currentLines.isEmpty()) return
            val firstTextLine = currentLines.firstOrNull { it.bodyEnd > it.bodyStart } ?: currentLines.first()
            val lastTextLine = currentLines.lastOrNull { it.bodyEnd > it.bodyStart } ?: currentLines.last()
            pages += LaidOutPage(
                pageIndex = pages.size,
                lines = currentLines.toList(),
                bodyStart = firstTextLine.bodyStart,
                bodyEnd = max(firstTextLine.bodyStart, lastTextLine.bodyEnd),
                firstBlockIndex = currentLines.first().blockIndex
            )
            currentLines.clear()
            y = contentTop
        }

        if (chapter.blocks.isEmpty()) {
            val emptyLine = LaidOutLine(
                blockIndex = 0,
                blockStartOffset = 0,
                blockEndOffset = 0,
                bodyStart = 0,
                bodyEnd = 0,
                text = "",
                top = contentTop,
                bottom = contentTop + baseLineHeightPx,
                baseline = contentTop + baseFontSizePx * 0.85f,
                charLeft = FloatArray(0),
                charRight = FloatArray(0),
                charFlags = IntArray(0),
                textSizePx = baseFontSizePx
            )
            pages += LaidOutPage(0, listOf(emptyLine), 0, 0, 0)
            return ChapterLayout(chapter, pages, safeWidth, safeHeight, marginH, marginTop, marginBottom, baseFontSizePx)
        }

        for ((bIdx, block) in chapter.blocks.withIndex()) {
            when (block.kind) {
                BlockKind.RULE -> {
                    val ruleHeight = baseFontSizePx * 1.2f
                    if (y + ruleHeight > contentBottom && currentLines.isNotEmpty()) {
                        flushPage()
                    }
                    val topY = y
                    val bottomY = y + ruleHeight
                    currentLines += LaidOutLine(
                        blockIndex = bIdx,
                        blockStartOffset = 0,
                        blockEndOffset = 0,
                        bodyStart = block.bodyStart,
                        bodyEnd = block.bodyEnd,
                        text = "",
                        top = topY,
                        bottom = bottomY,
                        baseline = (topY + bottomY) * 0.5f,
                        charLeft = floatArrayOf(contentLeft + contentWidth * 0.2f),
                        charRight = floatArrayOf(contentRight - contentWidth * 0.2f),
                        charFlags = IntArray(0),
                        textSizePx = baseFontSizePx,
                        isRule = true
                    )
                    y = bottomY + paraExtraGapPx
                }

                BlockKind.IMAGE -> {
                    val href = block.imageHref.orEmpty()
                    val dim = imageSizeProvider(href)
                    val intrinsicW = (dim?.first ?: 480).coerceAtLeast(1)
                    val intrinsicH = (dim?.second ?: 640).coerceAtLeast(1)
                    val maxImgW = contentWidth
                    val maxImgH = (maxPageHeight * 0.82f).coerceAtLeast(baseFontSizePx * 3f)
                    val scale = min(maxImgW / intrinsicW.toFloat(), maxImgH / intrinsicH.toFloat()).coerceAtMost(2.5f)
                    val drawW = intrinsicW * scale
                    val drawH = intrinsicH * scale
                    if (y + drawH > contentBottom && currentLines.isNotEmpty()) {
                        flushPage()
                    }
                    val left = contentLeft + (contentWidth - drawW) * 0.5f
                    val topY = y + 4f * density
                    val bottomY = topY + drawH
                    val rect = RectF(left, topY, left + drawW, bottomY)
                    currentLines += LaidOutLine(
                        blockIndex = bIdx,
                        blockStartOffset = 0,
                        blockEndOffset = 0,
                        bodyStart = block.bodyStart,
                        bodyEnd = block.bodyEnd,
                        text = "",
                        top = topY,
                        bottom = bottomY,
                        baseline = bottomY,
                        charLeft = FloatArray(0),
                        charRight = FloatArray(0),
                        charFlags = IntArray(0),
                        textSizePx = baseFontSizePx,
                        imageHref = href,
                        imageRect = rect
                    )
                    y = bottomY + 4f * density + paraExtraGapPx
                }

                else -> {
                    val scale = when (block.kind) {
                        BlockKind.HEADING_1 -> 1.45f
                        BlockKind.HEADING_2 -> 1.30f
                        BlockKind.HEADING_3 -> 1.18f
                        BlockKind.HEADING_4 -> 1.08f
                        BlockKind.HEADING_5, BlockKind.HEADING_6 -> 1.0f
                        BlockKind.PRE -> 0.9f
                        else -> 1.0f
                    }
                    val isHeading = block.kind in setOf(
                        BlockKind.HEADING_1, BlockKind.HEADING_2, BlockKind.HEADING_3,
                        BlockKind.HEADING_4, BlockKind.HEADING_5, BlockKind.HEADING_6
                    )
                    val blockFontSizePx = baseFontSizePx * scale
                    val blockLineHeightPx = blockFontSizePx * settings.lineHeight.toFloat()

                    if (isHeading && currentLines.isNotEmpty()) {
                        y += baseFontSizePx * 0.35f
                    }

                    val text = block.text
                    val n = text.length
                    if (n == 0) continue

                    // Build per-character flags and widths in one pass
                    val flags = IntArray(n)
                    for (i in 0 until n) {
                        if (EpubChapterParser.isSpaceChar(text[i])) {
                            flags[i] = flags[i] or FLAG_WHITESPACE
                        }
                        if (isHeading) {
                            flags[i] = flags[i] or FLAG_BOLD
                        }
                    }
                    for (span in block.spans) {
                        val s = span.start.coerceIn(0, n)
                        val e = span.end.coerceIn(0, n)
                        val bit = when (span.kind) {
                            SpanKind.BOLD -> FLAG_BOLD
                            SpanKind.ITALIC -> FLAG_ITALIC
                            SpanKind.SUP -> FLAG_SUP
                            SpanKind.SUB -> FLAG_SUB
                            SpanKind.CODE -> FLAG_CODE
                            SpanKind.LINK -> FLAG_LINK
                        }
                        for (i in s until e) {
                            flags[i] = flags[i] or bit
                        }
                    }

                    // Measure character advances using Paint.getTextWidths
                    paint.textSize = blockFontSizePx
                    paint.isFakeBoldText = isHeading
                    val widths = FloatArray(n)
                    paint.getTextWidths(text, 0, n, widths)
                    if (!isHeading && block.spans.any { it.kind == SpanKind.BOLD }) {
                        paint.isFakeBoldText = true
                        val boldWidths = FloatArray(n)
                        paint.getTextWidths(text, 0, n, boldWidths)
                        for (i in 0 until n) {
                            if ((flags[i] and FLAG_BOLD) != 0) {
                                widths[i] = boldWidths[i]
                            }
                        }
                        paint.isFakeBoldText = false
                    }
                    for (i in 0 until n) {
                        if ((flags[i] and (FLAG_SUP or FLAG_SUB)) != 0) {
                            widths[i] *= 0.72f
                        }
                    }

                    // Compute font metrics for vertical baseline positioning inside the line box
                    val fm = paint.fontMetrics
                    val glyphHeight = fm.descent - fm.ascent
                    val halfLeading = (blockLineHeightPx - glyphHeight) * 0.5f
                    val baselineOffsetFromTop = halfLeading - fm.ascent

                    val blockIndentLeft = when (block.kind) {
                        BlockKind.QUOTE -> contentLeft + baseFontSizePx * 1.5f
                        BlockKind.LIST_ITEM -> contentLeft + baseFontSizePx * 1.0f
                        else -> contentLeft
                    }
                    val blockMarginRight = if (block.kind == BlockKind.QUOTE) baseFontSizePx * 1.2f else 0f
                    val firstLineExtraIndent = if (
                        block.kind == BlockKind.PARAGRAPH &&
                        block.align == BlockAlign.START &&
                        !startsWithFullWidthIndent(text)
                    ) {
                        blockFontSizePx * 2.0f
                    } else {
                        0f
                    }

                    // Break block into lines
                    var lineStart = 0
                    var isFirstLine = true
                    while (lineStart < n) {
                        // Skip leading ASCII spaces at the start of a wrapped continuation line
                        if (!isFirstLine && block.kind != BlockKind.PRE) {
                            while (lineStart < n && text[lineStart] == ' ') lineStart++
                            if (lineStart >= n) break
                        }

                        val lineLeft = if (isFirstLine) blockIndentLeft + firstLineExtraIndent else blockIndentLeft
                        val availableWidth = (contentRight - blockMarginRight - lineLeft).coerceAtLeast(blockFontSizePx * 2f)

                        var lineEnd = lineStart
                        var measuredW = 0f
                        var lastWordBreak = -1

                        while (lineEnd < n) {
                            val ch = text[lineEnd]
                            if (ch == '\n') {
                                break
                            }
                            val cw = widths[lineEnd]
                            if (measuredW + cw > availableWidth && lineEnd > lineStart) {
                                // Check if we are in the middle of an ASCII word/number
                                if (isAsciiWordChar(ch) && isAsciiWordChar(text[lineEnd - 1]) && lastWordBreak > lineStart) {
                                    lineEnd = lastWordBreak
                                } else {
                                    // Kinsoku Shori (避头尾):
                                    // 1. Do not let NO_START_PUNCT start the next line; look ahead across consecutive punctuation
                                    if (ch in NO_START_PUNCT && lineEnd - lineStart > 1) {
                                        var pCount = 0
                                        var pWidth = 0f
                                        while (lineEnd + pCount < n && text[lineEnd + pCount] in NO_START_PUNCT) {
                                            pWidth += widths[lineEnd + pCount]
                                            pCount++
                                        }
                                        if (measuredW + pWidth <= availableWidth + blockFontSizePx * 1.15f) {
                                            measuredW += pWidth
                                            lineEnd += pCount
                                        } else {
                                            while (lineEnd - 1 > lineStart && text[lineEnd - 1] in NO_START_PUNCT) lineEnd--
                                            if (lineEnd - 1 > lineStart) lineEnd--
                                        }
                                    }
                                    // 2. Do not leave NO_END_PUNCT at the end of the current line
                                    while (lineEnd - 1 > lineStart && text[lineEnd - 1] in NO_END_PUNCT) {
                                        lineEnd--
                                    }
                                }
                                break
                            }
                            measuredW += cw
                            lineEnd++
                            if (!isAsciiWordChar(ch)) {
                                lastWordBreak = lineEnd
                            }
                        }

                        val endedWithNewline = lineEnd < n && text[lineEnd] == '\n'
                        val isLastLineOfBlock = (lineEnd >= n) || endedWithNewline

                        // Trim trailing space on wrapped lines for clean right justification
                        var visibleEnd = lineEnd
                        if (!isLastLineOfBlock && block.kind != BlockKind.PRE) {
                            while (visibleEnd > lineStart && text[visibleEnd - 1] == ' ') {
                                visibleEnd--
                            }
                        }

                        // Check if we need to turn to a new page before placing this line
                        if (y + blockLineHeightPx > contentBottom && currentLines.isNotEmpty()) {
                            flushPage()
                        }

                        val lineCharCount = visibleEnd - lineStart
                        if (lineCharCount <= 0 && !endedWithNewline) {
                            lineStart = lineEnd
                            continue
                        }
                        val charLeft = FloatArray(lineCharCount)
                        val charRight = FloatArray(lineCharCount)
                        val lineFlags = IntArray(lineCharCount)

                        var actualLineWidth = 0f
                        for (i in 0 until lineCharCount) {
                            actualLineWidth += widths[lineStart + i]
                            lineFlags[i] = flags[lineStart + i]
                        }

                        fun canExpandAfter(idx: Int): Boolean {
                            if (idx >= n - 1) return false
                            val c1 = text[idx]; val c2 = text[idx + 1]
                            return when {
                                c1 == ' ' || c2 == ' ' -> true
                                isAsciiWordChar(c1) && isAsciiWordChar(c2) -> false
                                else -> true
                            }
                        }

                        val slack = (availableWidth - actualLineWidth).coerceAtLeast(0f)
                        val shouldJustify = !isLastLineOfBlock &&
                            block.align == BlockAlign.START &&
                            block.kind != BlockKind.PRE &&
                            lineCharCount > 1 &&
                            slack <= blockFontSizePx * 2.5f

                        var startX = when {
                            block.align == BlockAlign.CENTER -> lineLeft + slack * 0.5f
                            block.align == BlockAlign.END -> lineLeft + slack
                            else -> lineLeft
                        }
                        val expandableGaps = if (shouldJustify) (0 until lineCharCount - 1).count { canExpandAfter(lineStart + it) } else 0
                        val extraPerExpandableGap = if (expandableGaps > 0) slack / expandableGaps else 0f

                        var cursorX = startX
                        for (i in 0 until lineCharCount) {
                            val w = widths[lineStart + i]
                            charLeft[i] = cursorX
                            charRight[i] = cursorX + w
                            val extra = if (shouldJustify && i < lineCharCount - 1 && canExpandAfter(lineStart + i)) extraPerExpandableGap else 0f
                            cursorX += w + extra
                        }

                        val rawStart = lineStart.coerceIn(0, block.text.length)
                        val rawEnd = visibleEnd.coerceIn(rawStart, block.text.length)
                        val bodyStart = (block.bodyStart + rawStart).coerceAtMost(block.bodyEnd)
                        val bodyEnd = (block.bodyStart + rawEnd).coerceAtMost(block.bodyEnd)

                        val topY = y
                        val bottomY = y + blockLineHeightPx
                        val baselineY = topY + baselineOffsetFromTop

                        currentLines += LaidOutLine(
                            blockIndex = bIdx,
                            blockStartOffset = rawStart,
                            blockEndOffset = rawEnd,
                            bodyStart = bodyStart,
                            bodyEnd = bodyEnd,
                            text = text.substring(lineStart, visibleEnd),
                            top = topY,
                            bottom = bottomY,
                            baseline = baselineY,
                            charLeft = charLeft,
                            charRight = charRight,
                            charFlags = lineFlags,
                            textSizePx = blockFontSizePx,
                            isListItemBullet = isFirstLine && block.kind == BlockKind.LIST_ITEM
                        )

                        y = bottomY
                        isFirstLine = false
                        lineStart = if (endedWithNewline) lineEnd + 1 else lineEnd
                    }

                    // Add paragraph spacing after the block
                    y += if (isHeading) max(paraExtraGapPx, baseFontSizePx * 0.25f) else paraExtraGapPx
                }
            }
        }

        flushPage()
        if (pages.isEmpty()) {
            val emptyLine = LaidOutLine(
                blockIndex = 0,
                blockStartOffset = 0,
                blockEndOffset = 0,
                bodyStart = 0,
                bodyEnd = 0,
                text = "",
                top = contentTop,
                bottom = contentTop + baseLineHeightPx,
                baseline = contentTop + baseFontSizePx * 0.85f,
                charLeft = FloatArray(0),
                charRight = FloatArray(0),
                charFlags = IntArray(0),
                textSizePx = baseFontSizePx
            )
            pages += LaidOutPage(0, listOf(emptyLine), 0, 0, 0)
        }
        return ChapterLayout(chapter, pages, safeWidth, safeHeight, marginH, marginTop, marginBottom, baseFontSizePx)
    }

    private fun startsWithFullWidthIndent(text: String): Boolean =
        text.isNotEmpty() && text[0] == '\u3000'

    private fun isAsciiWordChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-' || c == '\''
}
