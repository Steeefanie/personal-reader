@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class, org.readium.r2.shared.InternalReadiumApi::class)
package top.steeefanie.leafreader.reader.fonts

import android.os.Build
import android.util.LruCache
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.css.FontStyle
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.shared.publication.Href
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.CompositeContainer
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.file.FileResource
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.InMemoryResource
import org.readium.r2.shared.util.resource.Resource
import java.io.File
import java.security.MessageDigest
import java.util.Locale

const val DEFAULT_FONT = "system-default"

data class DeviceFontFamily(val id: String, val name: String, val faces: List<FontFace>, val category:String="", val aliases:List<String> = emptyList()) {
    val originalName get()=faces.firstOrNull()?.family ?: name
    fun matches(query:String):Boolean {
        val haystack=FontIndex.key((listOf(name,originalName,category)+aliases).joinToString(" "))
        return query.trim().split(Regex("\\s+")).all { FontIndex.key(it) in haystack }
    }
    val generic get() = faces.isEmpty()
    val chinese get() = faces.any { it.chinese }
    val weights: List<Int> = faces.filter { !it.italic }.ifEmpty { faces }.flatMap { face ->
        face.weightRange?.let { range -> (listOf(range.first, range.last) + (100..1000 step 100).filter { it in range }) }
            ?: listOf(face.weight)
    }.distinct().sorted().ifEmpty { listOf(400) }
    fun nearest(weight: Double): Int = weights.minBy { kotlin.math.abs(it - weight) }
}

/** Application-local inventory. Scans system fonts once and persists metadata & unpacked TTC faces on disk. */
class SystemFontCatalog(private val context:android.content.Context) {
    private val previewLoader=FontPreview()
    suspend fun preview(family:DeviceFontFamily,weight:Double)=previewLoader.load(family,weight)
    private val mutex = Mutex()
    @Volatile private var snapshot: List<DeviceFontFamily> = emptyList()
    private val default = DeviceFontFamily(DEFAULT_FONT, "跟随系统", emptyList())
    private val catalogCacheFile by lazy { File(context.filesDir, "system-fonts-cache-v1.json") }
    private val extractedFontsDir by lazy { File(context.cacheDir, "extracted-fonts").apply { mkdirs() } }
    private val standaloneMemoryCache = object : LruCache<Url, ByteArray>(32 * 1024 * 1024) {
        override fun sizeOf(key: Url, value: ByteArray) = value.size
    }

    suspend fun families(): List<DeviceFontFamily> = withContext(Dispatchers.IO) {
        if (snapshot.isNotEmpty()) return@withContext snapshot
        mutex.withLock {
            if (snapshot.isEmpty()) snapshot = discover()
            snapshot
        }
    }
    fun resolve(id: String): DeviceFontFamily {
        if (id == "source-han-serif") {
            return legacyFamily("Serif")
        }
        if (id == "source-han-sans") {
            return legacyFamily("Sans")
        }
        return snapshot.firstOrNull { it.id == id } ?: default
    }
    fun resolveForReading(id: String): DeviceFontFamily =
        resolve(id).let { if (it.generic) legacyFamily("Sans") else it }
    private fun legacyFamily(style:String):DeviceFontFamily {
        val candidates=snapshot.filter { it.chinese && it.faces.any { face -> face.family.contains(style,true) } }
        return candidates.firstOrNull { it.faces.any { face -> Regex("(?:SC|CN)(?:\\b|$)").containsMatchIn(face.family) } }
            ?: candidates.firstOrNull() ?: snapshot.firstOrNull { it.chinese } ?: default
    }
    private fun discover(): List<DeviceFontFamily> {
        val files = linkedSetOf<File>()
        if (Build.VERSION.SDK_INT >= 29) runCatching { files += systemFiles() }
        listOf("/system/fonts", "/product/fonts", "/system_ext/fonts", "/vendor/fonts").forEach { directory ->
            runCatching { File(directory).listFiles()?.filterTo(files) { it.isFile && it.extension.lowercase(Locale.ROOT) in setOf("ttf", "otf", "ttc", "otc") } }
        }
        val readableFiles = files.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }.distinct()
            .filter { it.canRead() && it.length() in 12..128L * 1024 * 1024 }
            .sortedBy { it.path }
        val fingerprint = digest(readableFiles.joinToString("\n") { "${it.path}:${it.length()}:${it.lastModified()}" })
        readCachedCatalog(fingerprint)?.let { cached ->
            return listOf(default) + cached
        }
        val faces = readableFiles
            .flatMap { runCatching { OpenTypeFont.read(it) }.getOrDefault(emptyList()) }
            .distinctBy { Triple(it.postScriptName, it.weightRange ?: it.weight..it.weight, it.italic) }
        val index=FontIndex(context.assets.open("font-index.json").bufferedReader().use { it.readText() })
        val families = faces.groupBy { it.family }.map { (name, members) ->
            val description=index.describe(name,members.map { it.displayName })
            DeviceFontFamily("device-" + digest(name), description.name, members,description.category,description.aliases)
        }.sortedWith(compareByDescending<DeviceFontFamily> { it.chinese }.thenBy { FontIndex.regionOrder(it.originalName) }.thenBy { it.name.lowercase(Locale.ROOT) })
        writeCachedCatalog(fingerprint, families)
        return listOf(default) + families
    }

    private fun readCachedCatalog(expectedFingerprint: String): List<DeviceFontFamily>? = runCatching {
        if (!catalogCacheFile.exists()) return null
        val root = JSONObject(catalogCacheFile.readText(Charsets.UTF_8))
        if (root.optString("fingerprint") != expectedFingerprint) return null
        val array = root.getJSONArray("families")
        List(array.length()) { i ->
            val item = array.getJSONObject(i)
            val facesArr = item.getJSONArray("faces")
            val faces = List(facesArr.length()) { j ->
                val f = facesArr.getJSONObject(j)
                val range = if (f.has("weightMin") && f.has("weightMax")) f.getInt("weightMin")..f.getInt("weightMax") else null
                FontFace(
                    file = File(f.getString("path")),
                    index = f.getInt("index"),
                    family = f.getString("family"),
                    displayName = f.getString("displayName"),
                    postScriptName = f.getString("postScriptName"),
                    weight = f.getInt("weight"),
                    italic = f.getBoolean("italic"),
                    weightRange = range,
                    chinese = f.getBoolean("chinese")
                )
            }
            val aliasesArr = item.optJSONArray("aliases")
            val aliases = if (aliasesArr != null) List(aliasesArr.length()) { k -> aliasesArr.getString(k) } else emptyList()
            DeviceFontFamily(
                id = item.getString("id"),
                name = item.getString("name"),
                faces = faces,
                category = item.optString("category", ""),
                aliases = aliases
            )
        }
    }.getOrNull()

    private fun writeCachedCatalog(fingerprint: String, families: List<DeviceFontFamily>) {
        runCatching {
            val root = JSONObject().apply {
                put("fingerprint", fingerprint)
                put("families", JSONArray().apply {
                    families.forEach { fam ->
                        put(JSONObject().apply {
                            put("id", fam.id)
                            put("name", fam.name)
                            put("category", fam.category)
                            put("aliases", JSONArray(fam.aliases))
                            put("faces", JSONArray().apply {
                                fam.faces.forEach { f ->
                                    put(JSONObject().apply {
                                        put("path", f.file.path)
                                        put("index", f.index)
                                        put("family", f.family)
                                        put("displayName", f.displayName)
                                        put("postScriptName", f.postScriptName)
                                        put("weight", f.weight)
                                        put("italic", f.italic)
                                        f.weightRange?.let { r ->
                                            put("weightMin", r.first)
                                            put("weightMax", r.last)
                                        }
                                        put("chinese", f.chinese)
                                    })
                                }
                            })
                        })
                    }
                })
            }
            val tmp = File(context.filesDir, "system-fonts-cache-v1.json.tmp")
            tmp.writeText(root.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(catalogCacheFile)) {
                catalogCacheFile.writeText(root.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    @RequiresApi(29)
    private fun systemFiles(): List<File> = android.graphics.fonts.SystemFonts.getAvailableFonts().mapNotNull { it.file }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .take(12).joinToString("") { "%02x".format(it) }

    fun configure(configuration: EpubNavigatorFragment.Configuration) {
        val cjkFallback = legacyFamily("Sans").takeUnless { it.generic }
        snapshot.filterNot { it.generic }.forEach { family ->
            val alternates = if (!family.chinese && cjkFallback != null && cjkFallback.id != family.id) {
                listOf(FontFamily(cjkFallback.id), FontFamily("sans-serif"))
            } else {
                listOf(FontFamily("sans-serif"))
            }
            configuration.addFontFamilyDeclaration(FontFamily(family.id), alternates) {
                family.faces.forEach { face ->
                    addFontFace {
                        addSource(AbsoluteUrl("https://readium/publication/" + resourcePath(face))!!)
                        setFontStyle(if (face.italic) FontStyle.ITALIC else FontStyle.NORMAL)
                        setFontWeight(face.weightRange ?: face.weight..face.weight)
                    }
                }
            }
        }
    }
    fun attach(builder: Publication.Builder) {
        val container = FontContainer(snapshot.flatMap { it.faces })
        builder.container = CompositeContainer(container, builder.container)
        builder.manifest = builder.manifest.copy(resources = builder.manifest.resources + container.links)
    }
    private fun faceDigest(face: FontFace) = digest("${face.file.path}:${face.index}:${face.file.length()}:${face.file.lastModified()}")
    private fun resourcePath(face: FontFace) = "__reader_system_fonts__/" + faceDigest(face) + ".otf"

    private inner class FontContainer(faces: List<FontFace>) : Container<Resource> {
        private val byUrl = faces.associateBy { checkNotNull(Url.fromDecodedPath(resourcePath(it))) }
        override val entries = byUrl.keys
        val links = byUrl.keys.map { Link(href = Href(it), mediaType = MediaType("font/otf")) }
        override fun get(url: Url): Resource? {
            val face = byUrl[url] ?: return null
            if (!face.file.canRead()) return null
            // TTF/OTF files can stream directly. TTC faces need their table offsets relocated once and cached on disk.
            if (face.file.extension.lowercase(Locale.ROOT) !in setOf("ttc", "otc")) return FileResource(face.file)
            val diskFile = File(extractedFontsDir, faceDigest(face) + ".otf")
            if (diskFile.exists() && diskFile.length() > 0L) return FileResource(diskFile)
            return runCatching {
                synchronized(standaloneMemoryCache) {
                    if (diskFile.exists() && diskFile.length() > 0L) return@synchronized FileResource(diskFile)
                    val data = standaloneMemoryCache[url] ?: OpenTypeFont.standalone(face).also { bytes ->
                        runCatching {
                            val tmp = File(extractedFontsDir, faceDigest(face) + ".otf.tmp")
                            tmp.writeBytes(bytes)
                            if (!tmp.renameTo(diskFile)) {
                                diskFile.writeBytes(bytes)
                                tmp.delete()
                            }
                        }
                        if (!diskFile.exists() || diskFile.length() == 0L) {
                            standaloneMemoryCache.put(url, bytes)
                        }
                    }
                    if (diskFile.exists() && diskFile.length() > 0L) FileResource(diskFile)
                    else InMemoryResource(data)
                }
            }.getOrNull()
        }
        override fun close() {}
    }
}
