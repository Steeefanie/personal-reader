package top.steeefanie.leafreader.reader

import org.json.JSONObject
import top.steeefanie.leafreader.storage.Bookmark

/** Extra text anchors live inside Locator, preserving the v1 wire and database schema. */
object TextBookmarks {
    fun capture(locator:String, selection:JSONObject):String {
        val text=selection.getJSONObject("text")
        require(text.getString("highlight").isNotBlank() && text.getString("highlight").length<=16000)
        val range=selection.getJSONObject("readerTextRange")
        require(range.getInt("version")==1 && range.getString("unit")=="utf16")
        require(range.getInt("start")>=0 && range.getInt("end")>range.getInt("start"))
        require(range.getInt("bodyStart")>=0 && range.getInt("bodyEnd")>range.getInt("bodyStart"))
        val root=JSONObject(locator)
        val locations=root.optJSONObject("locations") ?: JSONObject().also { root.put("locations",it) }
        locations.put("cssSelector",selection.getString("cssSelector"))
        locations.put("readerTextRange",range)
        root.put("text",text)
        return root.toString().also { require(it.length<65536) }
    }
    fun isText(bookmark:Bookmark):Boolean=bookmark.format=="epub" && runCatching {
        val root=JSONObject(bookmark.locator)
        root.optJSONObject("locations")?.optJSONObject("readerTextRange")!=null &&
            !root.optJSONObject("text")?.optString("highlight").isNullOrBlank()
    }.getOrDefault(false)
    fun quote(bookmark:Bookmark):String=runCatching {
        JSONObject(bookmark.locator).optJSONObject("text")?.optString("highlight").orEmpty()
    }.getOrDefault("")
    fun display(bookmark:Bookmark):String {
        val root=runCatching { JSONObject(bookmark.locator) }.getOrNull()
        val chapter=root?.optString("title")?.takeIf { it.isNotBlank() } ?:
            if(bookmark.format=="pdf")"第 ${root?.optJSONObject("locations")?.optInt("position",1) ?: 1} 页" else "位置书签"
        val summary=bookmark.label.ifBlank { if(isText(bookmark))quote(bookmark).take(1024) else "位置书签" }
        return "$chapter：${summary.replace(Regex("\\s+")," ").trim()}"
    }
}
