package top.steeefanie.leafreader.drive

import org.json.JSONObject
import org.json.JSONArray
import top.steeefanie.leafreader.storage.*

/** Single-book wire documents use the v1 exchange format. */
object BookState {
    val empty = Exchange(emptyList(), emptyList())
    fun decode(hash: String, json: String): Exchange = normalized(ProgressExchange.decode(json)).also {
        require(hash.matches(Regex("[a-f0-9]{64}"))) { "无效的内容哈希" }
        require(it.progress.size <= 1 && (it.progress.map { p -> p.hash } + it.bookmarks.map { b -> b.hash }).all { h -> h == hash }) { "单书进度与路径不匹配" }
    }
    fun encode(data: Exchange) = ProgressExchange.encode(data, pretty = false)
    private fun sorted(value: Any?): Any? = when (value) {
        is JSONObject -> JSONObject().apply { value.keys().asSequence().toList().sorted().forEach { key -> put(key, sorted(value.get(key))) } }
        is JSONArray -> JSONArray().apply { (0 until value.length()).forEach { put(sorted(value.get(it))) } }
        else -> value
    }
    private fun normalized(data: Exchange) = data.copy(
        progress = data.progress.map { it.copy(locator = sorted(JSONObject(it.locator)).toString()) },
        bookmarks = data.bookmarks.map { it.copy(locator = sorted(JSONObject(it.locator)).toString()) })
    fun canonical(data: Exchange): String = encode(normalized(data).let { it.copy(bookmarks = it.bookmarks.sortedBy { b -> b.id }) })
    fun equivalent(a: Exchange, b: Exchange): Boolean =
        normalized(a).let { x -> normalized(b).let { y -> x.progress == y.progress && x.bookmarks.sortedBy { it.id } == y.bookmarks.sortedBy { it.id } } }
}

data class MergeResult(val state: Exchange, val localConflict: Exchange? = null)

object StateMerge {
    fun merge(base: Exchange, local: Exchange, remote: Exchange): MergeResult {
        var conflict = false
        fun progress(b: Progress?, l: Progress?, r: Progress?): Progress? = when {
            l == b -> r
            r == b || l == r -> l
            l == null -> r
            r == null -> l
            l.updatedAt > r.updatedAt -> l
            else -> { if (l.updatedAt == r.updatedAt) conflict = true; r }
        }
        val bp = base.bookmarks.associateBy { it.id }
        val lp = local.bookmarks.associateBy { it.id }
        val rp = remote.bookmarks.associateBy { it.id }
        val marks = (bp.keys + lp.keys + rp.keys).mapNotNull { id ->
            val b = bp[id]; val l = lp[id]; val r = rp[id]
            when {
                l == b -> r
                r == b || l == r -> l
                l == null -> r
                r == null -> l
                l.updatedAt > r.updatedAt -> l
                r.updatedAt > l.updatedAt -> r
                l.deleted != r.deleted -> if (l.deleted) l else r
                else -> { conflict = true; r }
            }
        }.sortedBy { it.id }
        return MergeResult(Exchange(listOfNotNull(progress(base.progress.firstOrNull(), local.progress.firstOrNull(), remote.progress.firstOrNull())), marks), if (conflict) local else null)
    }
}
