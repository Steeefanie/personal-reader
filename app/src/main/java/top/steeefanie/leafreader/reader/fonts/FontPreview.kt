package top.steeefanie.leafreader.reader.fonts

import android.graphics.Typeface
import android.os.Build
import android.util.LruCache
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A small in-memory cache; no font files or preview images are written to storage. */
class FontPreview {
    private val cache=LruCache<String,Typeface>(6)
    suspend fun load(family:DeviceFontFamily,desiredWeight:Double):Typeface?=withContext(Dispatchers.IO) {
        if(family.generic)return@withContext Typeface.DEFAULT
        val weight=family.nearest(desiredWeight*400)
        val key="${family.id}:$weight"
        cache[key]?.let { return@withContext it }
        val face=family.faces.filter { !it.italic }.ifEmpty { family.faces }.minBy { face ->
            if(face.weightRange?.contains(weight)==true)0 else kotlin.math.abs(face.weight-weight)
        }
        val typeface=runCatching {
            if(Build.VERSION.SDK_INT>=26)build(face,weight)
            // Legacy createFromFile cannot select another face from a TTC collection.
            else if(face.index==0)Typeface.createFromFile(face.file) else null
        }.getOrNull()
        if(typeface!=null)cache.put(key,typeface)
        typeface
    }
    @RequiresApi(26)
    private fun build(face:FontFace,weight:Int):Typeface?=Typeface.Builder(face.file)
        .setTtcIndex(face.index).setWeight(weight).setItalic(face.italic)
        .apply { if(face.weightRange!=null)setFontVariationSettings("'wght' $weight") }.build()
}
