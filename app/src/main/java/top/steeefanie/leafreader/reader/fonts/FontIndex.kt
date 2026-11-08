package top.steeefanie.leafreader.reader.fonts

import org.json.JSONObject
import java.util.Locale

/** Name metadata only; no fonts, download URLs or availability assumptions. */
class FontIndex(json:String) {
    data class Description(val name:String,val category:String,val aliases:List<String>)
    private val root=JSONObject(json).also { require(it.getInt("schemaVersion")==1) }
    private val entries=root.getJSONArray("families").let { array -> (0 until array.length()).map { index ->
        val item=array.getJSONObject(index);val aliases=item.getJSONArray("aliases")
        Description(item.getString("name"),item.getString("category"),(0 until aliases.length()).map { aliases.getString(it) })
    } }
    private val byAlias=entries.flatMap { entry -> (entry.aliases+entry.name).map { key(it) to entry } }.toMap()
    private val scripts=root.getJSONObject("scripts").let { objectValue -> objectValue.keys().asSequence().associate { key(it) to objectValue.getString(it) } }
    private val regions=mapOf("sc" to "简体中文","cn" to "简体中文","tc" to "繁体中文","hk" to "香港繁体","hc" to "香港繁体","jp" to "日文","j" to "日文","kr" to "韩文","k" to "韩文")
    private val regionalRoots=listOf("SourceHanSans","SourceHanSerif","NotoSansMonoCJK","NotoSansCJK","NotoSerifCJK","NotoSansMono","NotoSans","NotoSerif","PingFang")
    private val styles=mapOf("bolditalic" to "粗斜体","extralight" to "特细","ultralight" to "特细","semibold" to "半粗",
        "demibold" to "半粗","extrabold" to "特粗","ultrabold" to "特粗","regular" to "常规","normal" to "常规",
        "medium" to "中等","black" to "重","heavy" to "重","light" to "细","thin" to "纤细","bold" to "粗",
        "italic" to "斜体","oblique" to "倾斜体","ui" to "界面版")

    fun describe(family:String,localizedNames:List<String>):Description {
        val names=(localizedNames+family).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        // A font's own Chinese name wins, including when the same family has a known alias.
        names.firstOrNull(::hasChinese)?.let { name ->
            return Description(name,categoryOf(name),names)
        }
        val canonical=key(family)
        byAlias[canonical]?.let { return it }
        regionalRoots.forEach { prefix ->
            val suffix=canonical.removePrefix(key(prefix))
            val region=regions[suffix]
            val entry=byAlias[key(prefix)]
            if(canonical.startsWith(key(prefix)) && region!=null && entry!=null)
                return entry.copy(name="${entry.name}（$region）",aliases=entry.aliases+family+region)
        }
        listOf("NotoSansMono","NotoSans","NotoSerif").forEach { prefix ->
            val script=scripts[canonical.removePrefix(key(prefix))]
            val entry=byAlias[key(prefix)]
            if(canonical.startsWith(key(prefix)) && script!=null && entry!=null)
                return entry.copy(name="${entry.name}（$script）",aliases=entry.aliases+family+script)
        }
        styles.forEach { (suffix,label) ->
            if(canonical.endsWith(suffix) && canonical.length>suffix.length) {
                val base=describe(canonical.removeSuffix(suffix),emptyList())
                if(base.category.isNotEmpty())return base.copy(name="${base.name} · $label",aliases=base.aliases+family)
            }
        }
        return Description(family,categoryOf(family),names)
    }
    private fun categoryOf(name:String):String=when {
            "仿宋" in name -> "仿宋"
            "楷" in name -> "楷体"
            "宋" in name -> "宋体／衬线"
            "黑" in name -> "黑体／无衬线"
            "圆" in name -> "圆体"
            else -> ""
        }
    companion object {
        fun regionOrder(value:String)=when {
            key(value).endsWith("sc") || key(value).endsWith("cn") -> 0
            key(value).endsWith("tc") -> 1
            key(value).endsWith("hk") || key(value).endsWith("hc") -> 2
            key(value).endsWith("jp") -> 3
            key(value).endsWith("kr") -> 4
            else -> 0
        }
        fun key(value:String)=value.lowercase(Locale.ROOT).replace(Regex("[\\s_\\-]+"),"")
        fun hasChinese(value:String)=value.any { it.code in 0x3400..0x9fff }
        fun weightName(weight:Int)=mapOf(100 to "纤细",200 to "特细",300 to "细",400 to "常规",500 to "中等",600 to "半粗",700 to "粗",800 to "特粗",900 to "重",1000 to "超重")[weight].orEmpty()
    }
}
