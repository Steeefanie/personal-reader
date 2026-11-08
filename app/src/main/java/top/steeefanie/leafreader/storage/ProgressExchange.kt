package top.steeefanie.leafreader.storage

import org.json.JSONArray
import org.json.JSONObject

data class Exchange(val progress: List<Progress>, val bookmarks: List<Bookmark>)

/** Transport-independent format shared with future clients. Parse completely before committing. */
object ProgressExchange {
    const val VERSION = 1
    const val MAX_BYTES = 8 * 1024 * 1024
    private val hashPattern = Regex("[a-f0-9]{64}")
    fun encode(data: Exchange, pretty: Boolean = true): String {
      val root = JSONObject().apply {
        put("schemaVersion", VERSION)
        put("progress", JSONArray().apply { data.progress.forEach { p -> put(JSONObject().apply {
            put("bookHash", p.hash); put("format", p.format); put("locator", JSONObject(p.locator))
            put("updatedAt", p.updatedAt); put("pdfOffset", p.pdfOffset); put("pdfZoom", p.pdfZoom)
        }) } })
        put("bookmarks", JSONArray().apply { data.bookmarks.forEach { b -> put(JSONObject().apply {
            put("bookHash", b.hash); put("id", b.id); put("format", b.format); put("label", b.label)
            put("locator", JSONObject(b.locator)); put("updatedAt", b.updatedAt); put("deleted", b.deleted)
        }) } })
      }
      val compact = root.toString()
      require(compact.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "配置文件超过 8 MB" }
      if (!pretty) return compact
      val formatted = root.toString(2)
      // Exports must remain readable by our own importer even near the size limit.
      return if (formatted.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) formatted else compact
    }

    fun decode(text: String): Exchange {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "配置文件超过 8 MB" }
        val root = JSONObject(text)
        require(root.getInt("schemaVersion") == VERSION) { "不支持的进度文件版本" }
        val pa = root.getJSONArray("progress"); val ba = root.getJSONArray("bookmarks")
        require(pa.length() <= 10000 && ba.length() <= 30000) { "记录数量过多" }
        fun JSONObject.hash(): String = getString("bookHash").also { require(hashPattern.matches(it)) { "书籍标识无效" } }
        fun JSONObject.format(): String = getString("format").also { require(it == "epub" || it == "pdf") { "格式无效" } }
        fun JSONObject.time(): Long = getLong("updatedAt").also { require(it > 0) { "时间无效" } }
        fun JSONObject.locator(): String = getJSONObject("locator").also {
            require(it.getString("href").isNotBlank() && it.getString("type").isNotBlank()) { "阅读位置无效" }
            require(it.toString().length < 65536) { "阅读位置过长" }
            val locations = it.optJSONObject("locations")
            for (key in listOf("progression", "totalProgression")) if (locations?.has(key) == true) {
                require(locations.getDouble(key).let { value -> value.isFinite() && value in 0.0..1.0 }) { "进度比例无效" }
            }
            if (locations?.has("position") == true) require(locations.getInt("position") > 0) { "页码无效" }
        }.toString()
        val progress = (0 until pa.length()).map { index -> pa.getJSONObject(index).let {
            val offset = it.optDouble("pdfOffset", 0.0).toFloat()
            val zoom = it.optDouble("pdfZoom", 1.0).toFloat()
            require(offset.isFinite() && offset in -1f..1f && zoom.isFinite() && zoom in 0.1f..20f) { "PDF 位置无效" }
            Progress(it.hash(), it.format(), it.locator(), it.time(), offset, zoom)
        } }
        val bookmarks = (0 until ba.length()).map { index -> ba.getJSONObject(index).let {
            val id = it.getString("id"); val label = it.getString("label")
            require(id.isNotBlank() && id.length <= 128 && label.length <= 1024) { "书签无效" }
            Bookmark(it.hash(), id, it.format(), label, it.locator(), it.time(), it.getBoolean("deleted"))
        } }
        require(progress.map { it.hash }.distinct().size == progress.size) { "存在重复进度" }
        require(bookmarks.map { it.hash to it.id }.distinct().size == bookmarks.size) { "存在重复书签" }
        val formats = (progress.map { it.hash to it.format } + bookmarks.map { it.hash to it.format }).groupBy({ it.first }, { it.second })
        require(formats.values.all { it.distinct().size == 1 }) { "同一书籍的格式不一致" }
        return Exchange(progress, bookmarks)
    }
}
