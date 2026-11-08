package top.steeefanie.leafreader.reader.fonts

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FontIndexTest {
    private val json=File("src/main/assets/font-index.json").readText()
    private val index=FontIndex(json)
    @Test fun commonAndRegionalNamesAreChineseWithoutChangingIdentity() {
        assertEquals("Noto 黑体（简体中文）",index.describe("Noto Sans CJK SC",emptyList()).name)
        assertEquals("Noto 宋体（繁体中文）",index.describe("NotoSerifCJKTC",emptyList()).name)
        assertEquals("思源黑体（简体中文）",index.describe("Source Han Sans SC",emptyList()).name)
        assertEquals("思源宋体（简体中文）",index.describe("SourceHanSerifCN",emptyList()).name)
        assertEquals("Noto 黑体（阿拉伯文）",index.describe("NotoSansArabic",emptyList()).name)
    }
    @Test fun leafAliasesAreBasedOnFontNamesRatherThanFileNames() {
        assertEquals("方正兰亭准黑",index.describe("FZLanTingHei-M-GB18030",emptyList()).name)
        assertEquals("方正楷体",index.describe("FZKai-Z03",emptyList()).name)
        assertEquals("方正准圆",index.describe("FZZhunYuan_GB18030",emptyList()).name)
        assertEquals("思源黑体（简体中文） · 中等",index.describe("Source Han Sans SC Medium",emptyList()).name)
        assertEquals("Roboto 无衬线 · 粗",index.describe("Roboto-Bold",emptyList()).name)
    }
    @Test fun embeddedChineseNameTakesPriorityOverKnownAlias() {
        val description=index.describe("Noto Sans CJK SC",listOf("", "Noto Sans CJK SC", "设备提供的中文黑体名"))
        assertEquals("设备提供的中文黑体名",description.name)
        assertTrue(description.aliases.contains("Noto Sans CJK SC"))
        assertEquals("自带中文楷体",index.describe("自带中文楷体",emptyList()).name)
    }
    @Test fun unknownNamesRetainTheirOwnMetadata() {
        assertEquals("设备自带书体",index.describe("Unknown OEM Font",listOf("设备自带书体")).name)
        assertEquals("Unknown OEM Font",index.describe("Unknown OEM Font",emptyList()).name)
    }
    @Test fun searchMatchesChineseEnglishAliasesAndCategory() {
        val description=index.describe("LXGW WenKai Lite",emptyList())
        val face=FontFace(File("not-opened.ttf"),0,"LXGW WenKai Lite","LXGW WenKai Lite","sample",400,false,null,true)
        val family=DeviceFontFamily("unchanged-id",description.name,listOf(face),description.category,description.aliases)
        listOf("霞鹜","WenKai Lite","lxgw","楷体").forEach { assertTrue(it,family.matches(it)) }
        assertFalse(family.matches("思源宋体"))
    }
    @Test fun indexRemainsSmallAndDoesNotHaveConflictingAliases() {
        assertTrue(json.toByteArray().size<32*1024)
        val families=JSONObject(json).getJSONArray("families")
        assertTrue(families.length()>=60)
        val aliases=mutableMapOf<String,String>()
        for(i in 0 until families.length()) {
            val family=families.getJSONObject(i);val names=family.getJSONArray("aliases")
            for(j in 0 until names.length()) {
                val key=FontIndex.key(names.getString(j));val previous=aliases.put(key,family.getString("name"))
                if(previous!=null)assertEquals(previous,family.getString("name"))
            }
        }
    }
}
