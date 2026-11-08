@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
package top.steeefanie.leafreader.reader.epub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.Axis
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import top.steeefanie.leafreader.storage.Bookmark
import top.steeefanie.leafreader.storage.ReadingSettings

/** Left-to-right horizontal pagination; our own type instead of Readium's internal SimpleOverflow. */
private object PagedOverflow : OverflowableNavigator.Overflow {
    override val readingProgression: ReadingProgression get() = ReadingProgression.LTR
    override val scroll: Boolean get() = false
    override val axis: Axis get() = Axis.HORIZONTAL
}

/** Native Canvas EPUB navigator with limited HTML/CSS support and Readium-compatible locators. */
class NativeEpubNavigatorFragment : Fragment(), OverflowableNavigator {
    lateinit var publication: Publication
    var initialLocator: Locator? = null
    var settings: ReadingSettings = ReadingSettings()
        private set
    var typefaceLoader: suspend (ReadingSettings) -> Typeface = { Typeface.DEFAULT }
    var onPageChanged: ((Int, Int, Locator) -> Unit)? = null
    var onPageLoaded: (() -> Unit)? = null
    var onSwipeTurn: ((Boolean) -> Unit)? = null
    var onInternalLink: (() -> Unit)? = null
    var onExternalLink: ((String) -> Unit)? = null
    var onSelection: ((JSONObject?, RectF?) -> Unit)? = null
    var onBookmarkTap: ((Bookmark, RectF) -> Unit)? = null

    private sealed class Target {
        object First : Target()
        object Last : Target()
        data class Offset(val value: Int) : Target()
        data class Progression(val value: Double) : Target()
        data class Anchor(val id: String) : Target()
        data class Highlight(val text: String, val before: String?) : Target()
    }

    private lateinit var pageView: NativeEpubView
    private val listeners = mutableListOf<InputListener>()
    private val locatorFlow by lazy { MutableStateFlow(initialLocator ?: placeholder()) }
    private val overflowFlow = MutableStateFlow<OverflowableNavigator.Overflow>(PagedOverflow)
    private val chapters = LruCache<Int, ParsedChapter>(4)
    private val images = HashMap<String, Bitmap>()
    private var chapterIndex = -1
    private var layout: ChapterLayout? = null
    private var page = 0
    private var typeface: Typeface = Typeface.DEFAULT
    private var positionCounts: List<Int> = emptyList()
    private var marks: List<Bookmark> = emptyList()
    private var resolved: List<ResolvedBookmarkRange> = emptyList()
    private var job: Job? = null
    private var started = false
    private var loadedOnce = false
    private var laidW = 0
    private var laidH = 0
    private val tocTitles by lazy {
        val map = HashMap<String, String>()
        fun walk(items: List<Link>) { items.forEach { l -> l.title?.takeIf { it.isNotBlank() }?.let { map.putIfAbsent(stripFragment(l.href.toString()), it) }; walk(l.children) } }
        walk(publication.tableOfContents); map
    }

    override val currentLocator: StateFlow<Locator> get() = locatorFlow
    override val overflow: StateFlow<OverflowableNavigator.Overflow> get() = overflowFlow
    override val publicationView: View get() = pageView
    val pageCount: Int get() = layout?.pages?.size ?: 0
    val pageIndex: Int get() = page

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        pageView = NativeEpubView(requireContext()).apply {
            onTapPoint = { p -> listeners.any { it.onTap(TapEvent(p)) } }
            onSwipeTurn = { forward -> this@NativeEpubNavigatorFragment.onSwipeTurn?.invoke(forward) }
            onLinkActivated = ::activateLink
            onSelectionChanged = { json, rect -> onSelection?.invoke(json?.also { j -> j.optJSONObject("readerTextRange")?.put("engine", "native") }, rect) }
            onBookmarkActivated = { mark, rect -> onBookmarkTap?.invoke(mark, rect) }
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                if (v.width <= 0 || v.height <= 0) return@addOnLayoutChangeListener
                if (!started) { started = true; start() }
                else if ((v.width != laidW || v.height != laidH) && chapterIndex >= 0) relayout()
            }
        }
        return pageView
    }

    private fun start() {
        val initial = initialLocator
        viewLifecycleOwner.lifecycleScope.launch {
            typeface = typefaceLoader(settings)
            if (initial == null || !go(initial, false)) show(0, Target.First)
            launch(Dispatchers.IO) {
                val counts = runCatching { publication.positionsByReadingOrder().map { it.size.coerceAtLeast(1) } }.getOrNull()
                if (counts != null) withContext(Dispatchers.Main) { positionCounts = counts; if (layout != null) emit() }
            }
        }
    }

    fun submit(value: ReadingSettings) {
        settings = value
        if (!started) return
        viewLifecycleOwner.lifecycleScope.launch { typeface = typefaceLoader(value); relayout() }
    }

    fun setBookmarks(value: List<Bookmark>) { marks = value; resolveMarks(); if (::pageView.isInitialized) pageView.updateBookmarks(resolved) }
    fun clearSelection() { if (::pageView.isInitialized) pageView.clearSelection(true) }
    val hasSelection: Boolean get() = ::pageView.isInitialized && pageView.hasSelection

    private fun relayout() {
        val current = layout?.pages?.getOrNull(page)
        show(chapterIndex.coerceAtLeast(0), current?.let { Target.Offset(it.bodyStart) } ?: Target.First, force = true)
    }

    override fun go(locator: Locator, animated: Boolean): Boolean {
        val href = locator.href.toString()
        val index = indexOf(href)
        if (index < 0) return false
        val loc = locator.locations
        val offset = (loc.otherLocations["nativeBodyOffset"] as? Number)?.toInt()
        val fragment = href.substringAfter('#', "").ifBlank { loc.fragments.firstOrNull().orEmpty() }
        val highlight = locator.text.highlight?.takeIf { it.isNotBlank() }
        val target = when {
            offset != null -> Target.Offset(offset)
            highlight != null -> Target.Highlight(highlight, locator.text.before)
            fragment.isNotBlank() -> Target.Anchor(fragment)
            loc.progression != null -> Target.Progression(loc.progression!!)
            else -> Target.First
        }
        show(index, target); return true
    }

    override fun go(link: Link, animated: Boolean): Boolean {
        val href = link.href.toString()
        val index = indexOf(href)
        if (index < 0) return false
        val fragment = href.substringAfter('#', "")
        show(index, if (fragment.isBlank()) Target.First else Target.Anchor(fragment)); return true
    }

    override fun goForward(animated: Boolean): Boolean {
        val current = layout ?: return false
        if (page < current.pages.size - 1) { page++; render(); emit(); return true }
        if (chapterIndex < publication.readingOrder.size - 1) { show(chapterIndex + 1, Target.First); return true }
        return false
    }

    override fun goBackward(animated: Boolean): Boolean {
        if (layout == null) return false
        if (page > 0) { page--; render(); emit(); return true }
        if (chapterIndex > 0) { show(chapterIndex - 1, Target.Last); return true }
        return false
    }

    override suspend fun firstVisibleElementLocator(): Locator? = currentLocator.value
    override fun addInputListener(listener: InputListener) { listeners += listener }
    override fun removeInputListener(listener: InputListener) { listeners -= listener }

    private fun show(index: Int, target: Target, force: Boolean = false) {
        if (!started || !::pageView.isInitialized) { initialLocator = null; return }
        job?.cancel()
        job = viewLifecycleOwner.lifecycleScope.launch {
            val w = pageView.width; val h = pageView.height
            if (w <= 0 || h <= 0) return@launch
            val needLayout = force || index != chapterIndex || layout == null || w != laidW || h != laidH
            if (needLayout) {
                val chapter = withContext(Dispatchers.IO) { load(index) }
                val density = resources.displayMetrics.density
                val face = typeface; val s = settings
                val result = withContext(Dispatchers.Default) {
                    EpubTypesetter.layout(chapter, w, h, density, s, face) { href -> images[imageKey(index, href)]?.let { it.width to it.height } }
                }
                if (index != chapterIndex) pageView.clearSelection(true)
                chapterIndex = index; layout = result; laidW = w; laidH = h
                resolveMarks()
            }
            page = pageFor(layout!!, target)
            render(); emit()
            if (!loadedOnce) { loadedOnce = true; onPageLoaded?.invoke() }
        }
    }

    private suspend fun load(index: Int): ParsedChapter {
        chapters.get(index)?.let { return it }
        val link = publication.readingOrder[index]
        val bytes = publication.get(link)?.read()?.getOrNull() ?: ByteArray(0)
        val parsed = EpubChapterParser.parse(decode(bytes), titleOf(index))
        // Images are decoded once per chapter, grayscale-friendly RGB_565 and bounded to screen size.
        val base = link.url()
        val maxSide = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        parsed.blocks.filter { it.kind == BlockKind.IMAGE }.forEach { block ->
            val href = block.imageHref ?: return@forEach
            val key = imageKey(index, href)
            if (images.containsKey(key)) return@forEach
            runCatching {
                val cleanHref = href.substringBefore('#').substringBefore('?')
                val url = (Url(cleanHref) ?: Url(cleanHref.replace(" ", "%20")))?.let { base.resolve(it) } ?: return@runCatching
                val data = publication.get(url)?.read()?.getOrNull() ?: return@runCatching
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
                BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 })
                    ?.let { synchronized(images) { images[key] = it } }
            }
        }
        // Keep only images of cached chapters to bound memory and recycle evicted bitmaps.
        synchronized(images) {
            val evicted = images.keys.filter { k ->
                k.substringBefore('|').toIntOrNull()?.let { it != index && chapters.get(it) == null } == true
            }
            evicted.forEach { k -> images.remove(k)?.let { if (!it.isRecycled) it.recycle() } }
        }
        chapters.put(index, parsed)
        return parsed
    }

    private fun decode(bytes: ByteArray): String {
        val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.ISO_8859_1)
        val charset = Regex("encoding=[\"']([A-Za-z0-9_-]+)[\"']").find(head)?.groupValues?.get(1)
            ?: Regex("charset=[\"']?([A-Za-z0-9_-]+)").find(head)?.groupValues?.get(1)
        return runCatching { String(bytes, charset(charset ?: "UTF-8")) }.getOrElse { String(bytes, Charsets.UTF_8) }
    }

    private fun imageKey(index: Int, href: String) = "$index|$href"
    private fun render() { layout?.let { pageView.updateContent(it, page, typeface, resolved) { key -> images[imageKey(chapterIndex, key)] } } }

    private fun pageFor(layout: ChapterLayout, target: Target): Int {
        val pages = layout.pages
        val last = (pages.size - 1).coerceAtLeast(0)
        fun byOffset(offset: Int): Int = pages.indexOfLast { it.bodyStart <= offset }.coerceIn(0, last)
        return when (target) {
            Target.First -> 0
            Target.Last -> last
            is Target.Offset -> byOffset(target.value)
            is Target.Progression -> (target.value.coerceIn(0.0, 1.0) * pages.size).toInt().coerceIn(0, last)
            is Target.Anchor -> {
                val block = layout.chapter.anchors[target.id]
                    ?: runCatching { java.net.URLDecoder.decode(target.id, "UTF-8") }.getOrNull()?.let { layout.chapter.anchors[it] }
                block?.let { b -> pages.indexOfFirst { p -> p.lines.any { it.blockIndex >= b } }.takeIf { it >= 0 } } ?: 0
            }
            is Target.Highlight -> findText(layout.chapter.fullText, target.text, target.before)?.let(::byOffset) ?: 0
        }
    }

    private fun findText(full: String, text: String, before: String?, near: Int = -1): Int? {
        val needle = text.trim()
        if (needle.isEmpty()) return null
        fun search(ignoreCase: Boolean): Int? {
            var best: Int? = null; var bestScore = Int.MIN_VALUE
            var at = full.indexOf(needle, ignoreCase = ignoreCase)
            while (at >= 0) {
                val prefix = before.orEmpty().trimEnd()
                var score = 0
                if (prefix.isNotEmpty()) {
                    var i = 0
                    while (i < prefix.length && at - 1 - i >= 0 && full[at - 1 - i].equals(prefix[prefix.length - 1 - i], ignoreCase = ignoreCase)) i++
                    score += i * 10
                }
                if (near >= 0) score -= kotlin.math.abs(at - near) / 50
                if (score > bestScore) { bestScore = score; best = at }
                at = full.indexOf(needle, at + 1, ignoreCase = ignoreCase)
            }
            return best
        }
        return search(ignoreCase = false) ?: search(ignoreCase = true)
    }

    private fun resolveMarks() {
        val current = layout?.chapter ?: run { resolved = emptyList(); return }
        val href = stripFragment(publication.readingOrder.getOrNull(chapterIndex)?.url()?.toString().orEmpty())
        resolved = marks.mapNotNull { mark ->
            runCatching {
                val root = JSONObject(mark.locator)
                if (!sameHref(stripFragment(root.optString("href")), href)) return@runCatching null
                val text = root.optJSONObject("text") ?: return@runCatching null
                val quote = text.optString("highlight").trim().ifBlank { return@runCatching null }
                val range = root.optJSONObject("locations")?.optJSONObject("readerTextRange") ?: return@runCatching null
                val s = range.optInt("bodyStart", -1); val e = range.optInt("bodyEnd", -1)
                val full = current.fullText
                val start = if (range.optString("engine") == "native" && s >= 0 && e <= full.length && e > s && full.substring(s, e).trim() == quote) s + full.substring(s, e).indexOf(quote)
                    else findText(full, quote, text.optString("before"), s) ?: return@runCatching null
                ResolvedBookmarkRange(mark, start, start + quote.length)
            }.getOrNull()
        }
    }

    private fun emit() {
        val current = layout ?: return
        val link = publication.readingOrder[chapterIndex]
        val pages = current.pages.size
        val p = current.pages.getOrNull(page) ?: current.pages.firstOrNull() ?: return
        val progression = if (pages <= 1) 0.0 else page.toDouble() / pages
        var position: Int? = null; var total: Double? = null
        if (positionCounts.size == publication.readingOrder.size) {
            val before = positionCounts.take(chapterIndex).sum(); val count = positionCounts[chapterIndex]
            position = before + (progression * count).toInt().coerceIn(0, count - 1) + 1
            total = ((before + progression * count) / positionCounts.sum()).coerceIn(0.0, 1.0)
        }
        val full = current.chapter.fullText
        val snippet = full.substring(p.bodyStart.coerceIn(0, full.length), (p.bodyStart + 60).coerceIn(0, full.length)).takeIf { it.isNotBlank() }
        val locator = Locator(
            href = link.url(),
            mediaType = link.mediaType ?: MediaType.XHTML,
            title = titleOf(chapterIndex).ifBlank { null },
            locations = Locator.Locations(progression = progression, position = position, totalProgression = total,
                otherLocations = mapOf("nativeBodyOffset" to p.bodyStart)),
            text = Locator.Text(highlight = snippet)
        )
        locatorFlow.value = locator
        onPageChanged?.invoke(page, pages, locator)
    }

    private fun activateLink(href: String) {
        val lower = href.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:")) { onExternalLink?.invoke(href); return }
        val base = publication.readingOrder.getOrNull(chapterIndex)?.url() ?: return
        val target = if (href.startsWith("#")) base.toString().substringBefore('#') + href else (Url(href)?.let { base.resolve(it) }?.toString() ?: return)
        val index = indexOf(target)
        if (index < 0) return
        onInternalLink?.invoke()
        val fragment = target.substringAfter('#', "")
        show(index, if (fragment.isBlank()) Target.First else Target.Anchor(fragment))
    }

    private fun indexOf(href: String): Int {
        val wanted = stripFragment(href)
        val order = publication.readingOrder
        order.indexOfFirst { sameHref(stripFragment(it.url().toString()), wanted) }.takeIf { it >= 0 }?.let { return it }
        return order.indexOfFirst { stripFragment(it.href.toString()).let { h -> sameHref(h, wanted) } }
    }

    private fun sameHref(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val x = a.trimStart('/'); val y = b.trimStart('/')
        return x == y || x.endsWith("/$y") || y.endsWith("/$x")
    }

    private fun stripFragment(href: String) = href.substringBefore('#')
    private fun titleOf(index: Int): String {
        val link = publication.readingOrder.getOrNull(index) ?: return ""
        return link.title?.takeIf { it.isNotBlank() } ?: tocTitles[stripFragment(link.href.toString())]
            ?: tocTitles.entries.firstOrNull { sameHref(it.key, stripFragment(link.href.toString())) }?.value
            ?: chapters.get(index)?.title.orEmpty()
    }

    private fun placeholder(): Locator {
        val link = publication.readingOrder.first()
        return Locator(href = link.url(), mediaType = link.mediaType ?: MediaType.XHTML, locations = Locator.Locations(progression = 0.0))
    }

    override fun onDestroyView() {
        job?.cancel()
        started = false
        loadedOnce = false
        laidW = 0
        laidH = 0
        layout = null
        synchronized(images) { images.values.forEach { if (!it.isRecycled) it.recycle() }; images.clear() }
        super.onDestroyView()
    }
}
