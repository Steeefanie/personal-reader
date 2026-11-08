package top.steeefanie.leafreader.reader.epub

import org.junit.Assert.*
import org.junit.Test

class EpubChapterParserTest {

    @Test
    fun parsesStandardXhtmlWithXmlDeclarationAndDoctype() {
        val html = """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
            <html xmlns="http://www.w3.org/1999/xhtml">
            <head>
                <title>Chapter 1</title>
                <style type="text/css">p { color: red; }</style>
            </head>
            <body>
                <h1 id="c1">第一章 启程</h1>
                <p>这是第一段文字，包含了<b>粗体</b>和<i>斜体</i>。</p>
            </body>
            </html>
        """.trimIndent()

        val parsed = EpubChapterParser.parse(html, "默认标题")
        assertEquals("第一章 启程", parsed.title)
        assertEquals(2, parsed.blocks.size)

        val h1 = parsed.blocks[0]
        assertEquals(BlockKind.HEADING_1, h1.kind)
        assertEquals("第一章 启程", h1.text)
        assertEquals(listOf("c1"), h1.anchorIds)
        assertEquals(0, parsed.anchors["c1"])

        val p = parsed.blocks[1]
        assertEquals(BlockKind.PARAGRAPH, p.kind)
        assertEquals("这是第一段文字，包含了粗体和斜体。", p.text)
        assertEquals(2, p.spans.size)
        assertEquals(SpanKind.BOLD, p.spans[0].kind)
        assertEquals("粗体", p.text.substring(p.spans[0].start, p.spans[0].end))
        assertEquals(SpanKind.ITALIC, p.spans[1].kind)
        assertEquals("斜体", p.text.substring(p.spans[1].start, p.spans[1].end))
    }

    @Test
    fun handlesEntitiesAndStrayAmpersands() {
        val html = """
            <p>AT&T & Johnson &amp; Johnson &mdash; &ldquo;引言&rdquo; &alpha; &#160; &#x2014;</p>
        """.trimIndent()

        val parsed = EpubChapterParser.parse(html)
        val text = parsed.blocks.single().text
        assertTrue(text.contains("AT&T"))
        assertTrue(text.contains("Johnson & Johnson"))
        assertTrue(text.contains("—"))
        assertTrue(text.contains("“引言”"))
        assertTrue(text.contains("α"))
    }

    @Test
    fun handlesPoetrySingleBrVsParagraphDoubleBr() {
        val poemHtml = """
            <p>床前明月光<br/>疑是地上霜<br/>举头望明月<br/>低头思故乡</p>
        """.trimIndent()
        val parsedPoem = EpubChapterParser.parse(poemHtml)
        // Single <br> inside <p> should remain in a single block separated by \n
        assertEquals(1, parsedPoem.blocks.size)
        val poemLines = parsedPoem.blocks[0].text.split('\n')
        assertEquals(4, poemLines.size)
        assertEquals("床前明月光", poemLines[0])
        assertEquals("低头思故乡", poemLines[3])

        val webNovelHtml = """
            <div>
                第一段内容。<br/><br/>第二段内容。<br/>　　第三段内容。
            </div>
        """.trimIndent()
        val parsedNovel = EpubChapterParser.parse(webNovelHtml)
        // Consecutive <br><br> or <br> followed by \u3000 should split into separate paragraph blocks
        assertEquals(3, parsedNovel.blocks.size)
        assertEquals("第一段内容。", parsedNovel.blocks[0].text)
        assertEquals("第二段内容。", parsedNovel.blocks[1].text)
        assertEquals("第三段内容。", parsedNovel.blocks[2].text)
    }

    @Test
    fun imagesAndAnchorsAreCaptured() {
        val html = """
            <div>
                <p id="sec1">一段话<a id="note1"></a>。</p>
                <img id="img1" src="../Images/cover.jpg" />
                <hr />
            </div>
        """.trimIndent()
        val parsed = EpubChapterParser.parse(html)
        assertEquals(3, parsed.blocks.size)
        assertEquals(BlockKind.PARAGRAPH, parsed.blocks[0].kind)
        assertEquals(BlockKind.IMAGE, parsed.blocks[1].kind)
        assertEquals("../Images/cover.jpg", parsed.blocks[1].imageHref)
        assertEquals(BlockKind.RULE, parsed.blocks[2].kind)

        assertEquals(0, parsed.anchors["sec1"])
        assertEquals(0, parsed.anchors["note1"])
        assertEquals(1, parsed.anchors["img1"])
    }

    @Test
    fun handlesTablesAndCellSpacing() {
        val html = """
            <table>
                <tr><th>姓名</th><th>年龄</th></tr>
                <tr><td>张三</td><td>25</td></tr>
            </table>
        """.trimIndent()
        val parsed = EpubChapterParser.parse(html)
        assertTrue(parsed.blocks.isNotEmpty())
        val text = parsed.fullText
        assertTrue(text.contains("姓名  年龄") || text.contains("姓名 年龄"))
        assertTrue(text.contains("张三  25") || text.contains("张三 25"))
    }

    @Test
    fun handlesStyledSpansAndSemanticTags() {
        val html = """
            <p>
                <span style="font-weight: bold">粗体文本</span>
                <span class="italic">斜体文本</span>
                <ruby>汉<rt>hàn</rt></ruby>
                <kbd>Ctrl+C</kbd>
            </p>
        """.trimIndent()
        val parsed = EpubChapterParser.parse(html)
        val p = parsed.blocks.single()
        assertTrue(p.spans.any { it.kind == SpanKind.BOLD && p.text.substring(it.start, it.end) == "粗体文本" })
        assertTrue(p.spans.any { it.kind == SpanKind.ITALIC && p.text.substring(it.start, it.end) == "斜体文本" })
        assertTrue(p.spans.any { it.kind == SpanKind.SUP && p.text.substring(it.start, it.end) == "hàn" })
        assertTrue(p.spans.any { it.kind == SpanKind.CODE && p.text.substring(it.start, it.end) == "Ctrl+C" })
    }
}
