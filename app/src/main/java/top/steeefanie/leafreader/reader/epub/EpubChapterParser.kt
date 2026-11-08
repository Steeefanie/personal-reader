package top.steeefanie.leafreader.reader.epub

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.Locale

enum class BlockKind {
    HEADING_1, HEADING_2, HEADING_3, HEADING_4, HEADING_5, HEADING_6,
    PARAGRAPH, QUOTE, LIST_ITEM, PRE, IMAGE, RULE
}

enum class BlockAlign { START, CENTER, END }

enum class SpanKind { BOLD, ITALIC, SUP, SUB, CODE, LINK }

data class InlineSpan(
    val kind: SpanKind,
    val start: Int,
    val end: Int,
    val href: String? = null
)

data class EpubBlock(
    val index: Int,
    val kind: BlockKind,
    val text: String,
    val bodyStart: Int,
    val bodyEnd: Int,
    val cssSelector: String,
    val align: BlockAlign = BlockAlign.START,
    val spans: List<InlineSpan> = emptyList(),
    val imageHref: String? = null,
    val anchorIds: List<String> = emptyList()
)

data class ParsedChapter(
    val title: String,
    val fullText: String,
    val blocks: List<EpubBlock>,
    val anchors: Map<String, Int>
)

object EpubChapterParser {
    private val BLOCK_TAGS = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6",
        "blockquote", "li", "pre", "hr", "section", "article",
        "header", "footer", "aside", "figcaption", "tr", "dt", "dd"
    )
    private val LEAF_BLOCK_TAGS = setOf(
        "p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "pre", "figcaption", "dt", "dd", "tr"
    )
    private val IGNORED_SUBTREES = setOf("head", "style", "script", "noscript", "title", "meta", "link")
    private val WHITESPACE_CHARS = charArrayOf(
        ' ', '\t', '\n', '\r', '\u000b', '\u000c', '\u00a0', '\u1680',
        '\u2000', '\u2001', '\u2002', '\u2003', '\u2004', '\u2005',
        '\u2006', '\u2007', '\u2008', '\u2009', '\u200a', '\u200b',
        '\u2028', '\u2029', '\u202f', '\u205f', '\u3000', '\ufeff'
    )

    fun isSpaceChar(c: Char): Boolean = c.isWhitespace() || c in WHITESPACE_CHARS

    fun parse(rawHtml: String, fallbackTitle: String = ""): ParsedChapter {
        val cleaned = preprocessHtml(rawHtml)
        val blocks = mutableListOf<EpubBlock>()
        val anchors = mutableMapOf<String, Int>()
        val fullTextBuilder = StringBuilder()
        var chapterTitle: String? = null

        // Active block builder state
        var currentKind = BlockKind.PARAGRAPH
        var currentAlign = BlockAlign.START
        var currentTag = "p"
        val currentText = StringBuilder()
        val currentSpans = mutableListOf<InlineSpan>()
        val pendingAnchors = mutableListOf<String>()
        val tagCounters = mutableMapOf<String, Int>()
        var consecutiveBrs = 0
        var inPre = false
        var inQuote = 0
        var ignoredDepth = 0

        data class OpenSpan(val kind: SpanKind, val startOffset: Int, val href: String? = null)
        val openSpans = mutableListOf<OpenSpan>()

        fun nextSelector(tag: String): String {
            val t = tag.lowercase(Locale.ROOT)
            val count = (tagCounters[t] ?: 0) + 1
            tagCounters[t] = count
            return "body > $t:nth-of-type($count)"
        }

        fun trimBlockText(raw: String, preservePre: Boolean): Pair<Int, Int> {
            if (preservePre) {
                var s = 0
                var e = raw.length
                while (s < e && (raw[s] == '\n' || raw[s] == '\r')) s++
                while (e > s && (raw[e - 1] == '\n' || raw[e - 1] == '\r')) e--
                return s to e
            }
            var s = 0
            var e = raw.length
            while (s < e && isSpaceChar(raw[s])) s++
            while (e > s && isSpaceChar(raw[e - 1])) e--
            return s to e
        }

        fun flushBlock(selectorOverride: String? = null) {
            consecutiveBrs = 0
            if (currentText.isEmpty()) return
            val raw = currentText.toString()
            val (trimStart, trimEnd) = trimBlockText(raw, currentKind == BlockKind.PRE)
            if (trimEnd <= trimStart) {
                currentText.setLength(0)
                currentSpans.clear()
                return
            }
            val finalText = raw.substring(trimStart, trimEnd)
            val length = finalText.length

            // Adjust active & closed spans to the trimmed range
            val adjustedSpans = currentSpans.mapNotNull { span ->
                val s = (span.start - trimStart).coerceIn(0, length)
                val e = (span.end - trimStart).coerceIn(0, length)
                if (e > s) span.copy(start = s, end = e) else null
            }.toMutableList()

            // Re-open any spans that are still active across a <br><br> split
            val activeCopies = openSpans.map { it.copy() }
            openSpans.clear()
            activeCopies.forEach { open ->
                val s = (open.startOffset - trimStart).coerceIn(0, length)
                if (length > s) {
                    adjustedSpans.add(InlineSpan(open.kind, s, length, open.href))
                }
                openSpans.add(open.copy(startOffset = 0))
            }

            if (fullTextBuilder.isNotEmpty()) fullTextBuilder.append('\n')
            val bodyStart = fullTextBuilder.length
            fullTextBuilder.append(finalText)
            val bodyEnd = fullTextBuilder.length

            val blockIndex = blocks.size
            val anchorsForBlock = pendingAnchors.toList()
            pendingAnchors.clear()
            anchorsForBlock.forEach { id ->
                if (id.isNotBlank() && id !in anchors) anchors[id] = blockIndex
            }

            val effectiveKind = if (currentKind == BlockKind.PARAGRAPH && inQuote > 0) BlockKind.QUOTE else currentKind
            if (chapterTitle == null && effectiveKind in setOf(BlockKind.HEADING_1, BlockKind.HEADING_2, BlockKind.HEADING_3)) {
                chapterTitle = finalText.take(120)
            }

            blocks += EpubBlock(
                index = blockIndex,
                kind = effectiveKind,
                text = finalText,
                bodyStart = bodyStart,
                bodyEnd = bodyEnd,
                cssSelector = selectorOverride ?: nextSelector(currentTag),
                align = currentAlign,
                spans = adjustedSpans,
                anchorIds = anchorsForBlock
            )
            currentText.setLength(0)
            currentSpans.clear()
        }

        fun emitImage(src: String) {
            if (src.isBlank()) return
            flushBlock()
            val blockIndex = blocks.size
            val anchorsForBlock = pendingAnchors.toList()
            pendingAnchors.clear()
            anchorsForBlock.forEach { id ->
                if (id.isNotBlank() && id !in anchors) anchors[id] = blockIndex
            }
            val bodyPos = fullTextBuilder.length
            blocks += EpubBlock(
                index = blockIndex,
                kind = BlockKind.IMAGE,
                text = "",
                bodyStart = bodyPos,
                bodyEnd = bodyPos,
                cssSelector = nextSelector("img"),
                align = BlockAlign.CENTER,
                imageHref = src.trim(),
                anchorIds = anchorsForBlock
            )
        }

        fun emitRule() {
            flushBlock()
            val blockIndex = blocks.size
            val bodyPos = fullTextBuilder.length
            blocks += EpubBlock(
                index = blockIndex,
                kind = BlockKind.RULE,
                text = "",
                bodyStart = bodyPos,
                bodyEnd = bodyPos,
                cssSelector = nextSelector("hr"),
                align = BlockAlign.CENTER
            )
        }

        fun appendLogicalText(chunk: String) {
            if (chunk.isEmpty()) return
            if (inPre) {
                currentText.append(chunk)
                return
            }
            // If we had <br> tags: 2 or more <br> (or <br> followed by CJK full-width indent)
            // represents a paragraph separation; a single <br> represents a line break (\n) within the block.
            if (consecutiveBrs > 0) {
                val hasVisibleText = chunk.any { !isSpaceChar(it) }
                if (hasVisibleText) {
                    val trimmed = chunk.trim { it <= ' ' }
                    val startsWithIndent = trimmed.startsWith("\u3000") || trimmed.startsWith("　") || trimmed.startsWith("&emsp;")
                    val isNewParagraph = consecutiveBrs >= 2 || startsWithIndent
                    if (isNewParagraph && currentText.any { !isSpaceChar(it) }) {
                        flushBlock()
                    } else if (currentText.isNotEmpty()) {
                        currentText.append('\n')
                        consecutiveBrs = 0
                    } else {
                        currentText.setLength(0)
                        consecutiveBrs = 0
                    }
                }
            }
            var i = 0
            while (i < chunk.length) {
                val c = chunk[i]
                if (isSpaceChar(c)) {
                    // Collapse consecutive whitespace into a single ASCII space, except skip leading spaces in a block
                    if (currentText.isNotEmpty() && currentText[currentText.length - 1] != ' ') {
                        currentText.append(' ')
                    }
                } else {
                    currentText.append(c)
                }
                i++
            }
        }

        try {
            val parser = createPullParser()
            runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
            parser.setInput(StringReader(cleaned))

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val rawName = parser.name ?: ""
                        val tag = rawName.substringAfterLast(':').lowercase(Locale.ROOT)
                        if (ignoredDepth > 0) {
                            if (tag in IGNORED_SUBTREES) ignoredDepth++
                        } else if (tag in IGNORED_SUBTREES) {
                            ignoredDepth = 1
                        } else {
                            // Capture anchor IDs
                            val idAttr = attr(parser, "id") ?: attr(parser, "name")
                            if (!idAttr.isNullOrBlank()) pendingAnchors += idAttr

                            val alignAttr = parseAlign(attr(parser, "align"), attr(parser, "style"), attr(parser, "class"))

                            when (tag) {
                                "h1" -> { flushBlock(); currentKind = BlockKind.HEADING_1; currentTag = "h1"; currentAlign = alignAttr ?: BlockAlign.START }
                                "h2" -> { flushBlock(); currentKind = BlockKind.HEADING_2; currentTag = "h2"; currentAlign = alignAttr ?: BlockAlign.START }
                                "h3" -> { flushBlock(); currentKind = BlockKind.HEADING_3; currentTag = "h3"; currentAlign = alignAttr ?: BlockAlign.START }
                                "h4" -> { flushBlock(); currentKind = BlockKind.HEADING_4; currentTag = "h4"; currentAlign = alignAttr ?: BlockAlign.START }
                                "h5" -> { flushBlock(); currentKind = BlockKind.HEADING_5; currentTag = "h5"; currentAlign = alignAttr ?: BlockAlign.START }
                                "h6" -> { flushBlock(); currentKind = BlockKind.HEADING_6; currentTag = "h6"; currentAlign = alignAttr ?: BlockAlign.START }
                                "p" -> { flushBlock(); currentKind = BlockKind.PARAGRAPH; currentTag = "p"; currentAlign = alignAttr ?: BlockAlign.START }
                                "blockquote" -> { flushBlock(); inQuote++; currentKind = BlockKind.QUOTE; currentTag = "blockquote"; currentAlign = alignAttr ?: BlockAlign.START }
                                "li" -> { flushBlock(); currentKind = BlockKind.LIST_ITEM; currentTag = "li"; currentAlign = alignAttr ?: BlockAlign.START }
                                "pre" -> { flushBlock(); inPre = true; currentKind = BlockKind.PRE; currentTag = "pre"; currentAlign = BlockAlign.START }
                                "div", "section", "article", "header", "footer", "aside", "figcaption", "tr", "dt", "dd" -> {
                                    flushBlock()
                                    currentKind = if (inQuote > 0) BlockKind.QUOTE else BlockKind.PARAGRAPH
                                    currentTag = if (tag in LEAF_BLOCK_TAGS) tag else "p"
                                    if (alignAttr != null) currentAlign = alignAttr
                                }
                                "br" -> {
                                    if (inPre) currentText.append('\n')
                                    else consecutiveBrs++
                                }
                                "hr" -> emitRule()
                                "td", "th" -> {
                                    if (currentText.isNotEmpty() && currentText[currentText.length - 1] != ' ') {
                                        currentText.append("  ")
                                    }
                                }
                                "img", "image" -> {
                                    val src = attr(parser, "src")
                                        ?: attr(parser, "xlink:href")
                                        ?: attr(parser, "href")
                                    if (!src.isNullOrBlank()) emitImage(src)
                                }
                                "b", "strong" -> openSpans.add(OpenSpan(SpanKind.BOLD, currentText.length))
                                "i", "em", "cite", "dfn", "var" -> openSpans.add(OpenSpan(SpanKind.ITALIC, currentText.length))
                                "sup", "rt" -> openSpans.add(OpenSpan(SpanKind.SUP, currentText.length))
                                "sub" -> openSpans.add(OpenSpan(SpanKind.SUB, currentText.length))
                                "code", "tt", "kbd", "samp" -> openSpans.add(OpenSpan(SpanKind.CODE, currentText.length))
                                "span" -> {
                                    val style = attr(parser, "style")?.lowercase(Locale.ROOT).orEmpty()
                                    val cls = attr(parser, "class")?.lowercase(Locale.ROOT).orEmpty()
                                    if (style.contains("bold") || cls.contains("bold")) {
                                        openSpans.add(OpenSpan(SpanKind.BOLD, currentText.length))
                                    }
                                    if (style.contains("italic") || cls.contains("italic") || style.contains("oblique")) {
                                        openSpans.add(OpenSpan(SpanKind.ITALIC, currentText.length))
                                    }
                                }
                                "a" -> {
                                    val href = attr(parser, "href") ?: attr(parser, "xlink:href")
                                    if (!href.isNullOrBlank()) openSpans.add(OpenSpan(SpanKind.LINK, currentText.length, href.trim()))
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                        if (ignoredDepth == 0) {
                            val text = parser.text
                            if (!text.isNullOrEmpty()) appendLogicalText(text)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val rawName = parser.name ?: ""
                        val tag = rawName.substringAfterLast(':').lowercase(Locale.ROOT)
                        if (ignoredDepth > 0) {
                            if (tag in IGNORED_SUBTREES) ignoredDepth--
                        } else {
                            fun closeSpan(kind: SpanKind) {
                                val idx = openSpans.indexOfLast { it.kind == kind }
                                if (idx >= 0) {
                                    val open = openSpans.removeAt(idx)
                                    if (currentText.length > open.startOffset) {
                                        currentSpans.add(InlineSpan(open.kind, open.startOffset, currentText.length, open.href))
                                    }
                                }
                            }
                            when (tag) {
                                "b", "strong" -> closeSpan(SpanKind.BOLD)
                                "i", "em", "cite", "dfn", "var" -> closeSpan(SpanKind.ITALIC)
                                "sup", "rt" -> closeSpan(SpanKind.SUP)
                                "sub" -> closeSpan(SpanKind.SUB)
                                "code", "tt", "kbd", "samp" -> closeSpan(SpanKind.CODE)
                                "span" -> {
                                    val lastBold = openSpans.indexOfLast { it.kind == SpanKind.BOLD }
                                    val lastItalic = openSpans.indexOfLast { it.kind == SpanKind.ITALIC }
                                    if (lastBold >= 0 && (lastItalic < 0 || lastBold > lastItalic)) closeSpan(SpanKind.BOLD)
                                    else if (lastItalic >= 0) closeSpan(SpanKind.ITALIC)
                                }
                                "a" -> closeSpan(SpanKind.LINK)
                                "pre" -> { flushBlock(); inPre = false; currentKind = BlockKind.PARAGRAPH; currentAlign = BlockAlign.START }
                                "blockquote" -> { flushBlock(); inQuote = (inQuote - 1).coerceAtLeast(0); currentKind = BlockKind.PARAGRAPH; currentAlign = BlockAlign.START }
                                in BLOCK_TAGS -> {
                                    flushBlock()
                                    currentKind = if (inQuote > 0) BlockKind.QUOTE else BlockKind.PARAGRAPH
                                    currentAlign = BlockAlign.START
                                }
                            }
                        }
                    }
                }
                event = try {
                    parser.next()
                } catch (_: Exception) {
                    // Recover gracefully from malformed XML tokens if any
                    try { parser.nextToken() } catch (_: Exception) { XmlPullParser.END_DOCUMENT }
                }
            }
            flushBlock()
        } catch (_: Exception) {
            // Fallback regex strip if the XHTML is severely broken non-XML HTML
            if (blocks.isEmpty()) {
                return fallbackParse(cleaned, fallbackTitle)
            }
        }

        // Attach any trailing anchors to the last block
        if (pendingAnchors.isNotEmpty() && blocks.isNotEmpty()) {
            val lastIdx = blocks.lastIndex
            pendingAnchors.forEach { id -> if (id.isNotBlank() && id !in anchors) anchors[id] = lastIdx }
        }

        return ParsedChapter(
            title = chapterTitle ?: fallbackTitle,
            fullText = fullTextBuilder.toString(),
            blocks = blocks,
            anchors = anchors
        )
    }

    private fun createPullParser(): XmlPullParser {
        // 1. Try XmlPullParserFactory (standard on Android runtime)
        try {
            val factory = XmlPullParserFactory.newInstance()
            if (factory != null) {
                factory.isNamespaceAware = false
                factory.isValidating = false
                val parser = factory.newPullParser()
                if (parser != null) return parser
            }
        } catch (_: Throwable) {}

        // 2. Try instantiating KXmlParser directly (available in unit test classpath via kxml2)
        try {
            val kxmlClass = Class.forName("org.kxml2.io.KXmlParser")
            val parser = kxmlClass.getDeclaredConstructor().newInstance() as? XmlPullParser
            if (parser != null) return parser
        } catch (_: Throwable) {}

        // 3. Try android.util.Xml
        try {
            val xmlClass = Class.forName("android.util.Xml")
            val method = xmlClass.getMethod("newPullParser")
            val parser = method.invoke(null) as? XmlPullParser
            if (parser != null) return parser
        } catch (_: Throwable) {}

        error("No XmlPullParser implementation available in classpath")
    }

    private fun attr(parser: XmlPullParser, name: String): String? {
        for (i in 0 until parser.attributeCount) {
            val attrName = parser.getAttributeName(i) ?: continue
            if (attrName.equals(name, ignoreCase = true) || attrName.substringAfterLast(':').equals(name, ignoreCase = true)) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }

    private fun parseAlign(align: String?, style: String?, cls: String?): BlockAlign? {
        if (align != null) {
            when (align.trim().lowercase(Locale.ROOT)) {
                "center", "middle" -> return BlockAlign.CENTER
                "right", "end" -> return BlockAlign.END
                "left", "start", "justify" -> return BlockAlign.START
            }
        }
        if (style != null) {
            val m = Regex("text-align\\s*:\\s*(center|right|end|left|start|justify)", RegexOption.IGNORE_CASE).find(style)
            if (m != null) {
                when (m.groupValues[1].lowercase(Locale.ROOT)) {
                    "center" -> return BlockAlign.CENTER
                    "right", "end" -> return BlockAlign.END
                    else -> return BlockAlign.START
                }
            }
        }
        if (cls != null && Regex("\\b(center|title-center|text-center)\\b", RegexOption.IGNORE_CASE).containsMatchIn(cls)) {
            return BlockAlign.CENTER
        }
        return null
    }

    private fun fallbackParse(html: String, fallbackTitle: String): ParsedChapter {
        val withoutHead = html.replace(Regex("<(head|style|script)[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), "")
        val split = withoutHead.replace(Regex("<(br|p|/p|div|/div|h[1-6]|/h[1-6]|li|/li|blockquote|/blockquote)[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
        val lines = split.lines().map { it.trim { c -> isSpaceChar(c) } }.filter { it.isNotEmpty() }
        val blocks = mutableListOf<EpubBlock>()
        val full = StringBuilder()
        lines.forEachIndexed { idx, line ->
            if (full.isNotEmpty()) full.append('\n')
            val start = full.length
            full.append(line)
            val end = full.length
            blocks += EpubBlock(
                index = idx,
                kind = BlockKind.PARAGRAPH,
                text = line,
                bodyStart = start,
                bodyEnd = end,
                cssSelector = "body > p:nth-of-type(${idx + 1})"
            )
        }
        return ParsedChapter(fallbackTitle, full.toString(), blocks, emptyMap())
    }

    /** Replaces named HTML entities and strips DOCTYPE declarations so XmlPullParser parses cleanly. */
    private fun preprocessHtml(raw: String): String {
        var s = raw
        if (s.startsWith("\uFEFF")) s = s.substring(1)
        // Strip XML declaration (e.g. <?xml ... ?>) so wrapping in <root> does not create an invalid processing instruction
        s = s.replace(Regex("<\\?xml[^>]*\\?>", RegexOption.IGNORE_CASE), "")
        // Strip <!DOCTYPE ...> which may reference external DTDs or custom entities
        s = s.replace(Regex("<!DOCTYPE[^>]*(\\[[\\s\\S]*?\\])?\\s*>", RegexOption.IGNORE_CASE), "")
        // Strip <style> and <script> subtrees so CSS/JS syntax is never parsed as XML
        s = s.replace(Regex("<(style|script)[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), "")
        // Replace common HTML named entities not built into XML 1.0
        if (s.contains('&')) {
            s = ENTITY_REGEX.replace(s) { match ->
                val name = match.groupValues[1]
                when {
                    name.startsWith("#x", ignoreCase = true) -> {
                        val code = name.substring(2).toIntOrNull(16)
                        if (code != null && code > 0) String(Character.toChars(code)) else ""
                    }
                    name.startsWith("#") -> {
                        val code = name.substring(1).toIntOrNull(10)
                        if (code != null && code > 0) String(Character.toChars(code)) else ""
                    }
                    name in XML_BUILTINS -> match.value
                    else -> HTML_ENTITIES[name] ?: HTML_ENTITIES[name.lowercase(Locale.ROOT)] ?: "&amp;$name;"
                }
            }
            // Escape any remaining stray ampersands not part of built-in XML entities
            s = STRAY_AMP_REGEX.replace(s, "&amp;")
        }
        return "<root>$s</root>"
    }

    private val STRAY_AMP_REGEX = Regex("&(?!(lt|gt|amp|quot|apos);)")

    private val ENTITY_REGEX = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]+);")
    private val XML_BUILTINS = setOf("lt", "gt", "amp", "quot", "apos")
    private val HTML_ENTITIES = mapOf(
        "nbsp" to "\u00a0",
        "ensp" to "\u2002",
        "emsp" to "\u2003",
        "thinsp" to "\u2009",
        "zwnj" to "\u200c",
        "zwj" to "\u200d",
        "ldquo" to "\u201c",
        "rdquo" to "\u201d",
        "lsquo" to "\u2018",
        "rsquo" to "\u2019",
        "sbquo" to "\u201a",
        "bdquo" to "\u201e",
        "laquo" to "\u00ab",
        "raquo" to "\u00bb",
        "lsaquo" to "\u2039",
        "rsaquo" to "\u203a",
        "mdash" to "\u2014",
        "ndash" to "\u2013",
        "hellip" to "\u2026",
        "middot" to "\u00b7",
        "bull" to "\u2022",
        "copy" to "\u00a9",
        "reg" to "\u00ae",
        "trade" to "\u2122",
        "times" to "\u00d7",
        "divide" to "\u00f7",
        "plusmn" to "\u00b1",
        "deg" to "\u00b0",
        "sect" to "\u00a7",
        "para" to "\u00b6",
        "middot" to "\u00b7",
        "cent" to "\u00a2",
        "pound" to "\u00a3",
        "yen" to "\u00a5",
        "euro" to "\u20ac",
        "rarr" to "\u2192",
        "larr" to "\u2190",
        "uarr" to "\u2191",
        "darr" to "\u2193",
        "harr" to "\u2194",
        "minus" to "\u2212",
        "ne" to "\u2260",
        "le" to "\u2264",
        "ge" to "\u2265",
        "infin" to "\u221e",
        "hearts" to "\u2665",
        "diams" to "\u2666",
        "clubs" to "\u2663",
        "spades" to "\u2660",
        "alpha" to "α",
        "beta" to "β",
        "gamma" to "γ",
        "delta" to "δ",
        "pi" to "π",
        "omega" to "ω",
        "prime" to "′",
        "Prime" to "″",
        "sum" to "∑",
        "prod" to "∏",
        "radic" to "√",
        "asymp" to "≈",
        "approx" to "≈",
        "equiv" to "≡",
        "sub" to "⊂",
        "sup" to "⊃",
        "sube" to "⊆",
        "supe" to "⊇",
        "bull" to "•",
        "dagger" to "†",
        "Dagger" to "‡",
        "permil" to "‰",
        "frasl" to "⁄"
    )
}
