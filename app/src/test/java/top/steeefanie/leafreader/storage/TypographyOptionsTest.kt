package top.steeefanie.leafreader.storage

import org.junit.Assert.*
import org.junit.Test

class TypographyOptionsTest {
    @Test fun requestedStopsAndMiddleDefaults(){
        assertEquals(listOf(16,17,18,19,20,22,24,27,30,33,36),TypographyOptions.sizes.map{it.toInt()})
        assertEquals(22.0,ReadingSettings().fontSize,0.0)
        assertEquals(7,TypographyOptions.lines.size);assertEquals(7,TypographyOptions.margins.size)
        assertEquals(ReadingSettings().lineHeight,TypographyOptions.lines[3],0.0)
        assertEquals(ReadingSettings().margins,TypographyOptions.margins[3],0.0)
        assertEquals(7,TypographyOptions.paragraphs.size)
        assertEquals(ReadingSettings().paragraphSpacing,TypographyOptions.paragraphs[3],0.0)
        assertEquals(400,(ReadingSettings().fontWeight*400).toInt())
        assertEquals("system-default",ReadingSettings().font)
        assertEquals(listOf(0.0,.125,.25,.5,.75,1.0,1.25),TypographyOptions.paragraphs)
        assertEquals(1.8,TypographyOptions.nearest(TypographyOptions.lines,Double.POSITIVE_INFINITY,1.8),0.0)
    }
}
