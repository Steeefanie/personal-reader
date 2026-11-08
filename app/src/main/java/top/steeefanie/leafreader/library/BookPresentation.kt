package top.steeefanie.leafreader.library

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.*
import android.widget.*
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.*
import java.net.URI
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.readium.r2.shared.publication.firstWithRel
import top.steeefanie.leafreader.ReaderApplication
import top.steeefanie.leafreader.storage.*
import top.steeefanie.leafreader.ui.*

@Entity(tableName="book_metadata")
data class BookMetadata(@PrimaryKey val hash:String, val author:String="", val coverName:String="", val coverState:String="none", val pageCount:Int=0)
@Dao interface PresentationDao {
    @Query("SELECT * FROM book_metadata") fun observe():Flow<List<BookMetadata>>
    @Query("SELECT * FROM book_metadata WHERE hash=:hash") suspend fun get(hash:String):BookMetadata?
    @Upsert suspend fun put(metadata:BookMetadata)
    @Query("DELETE FROM book_metadata WHERE hash=:hash") suspend fun forget(hash:String)
}
val PRESENTATION_MIGRATION_2_3=object:Migration(2,3) {
    override fun migrate(db:SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS book_metadata (hash TEXT NOT NULL PRIMARY KEY, author TEXT NOT NULL, coverName TEXT NOT NULL, coverState TEXT NOT NULL, pageCount INTEGER NOT NULL)")
    }
}
/** NONE: local only (or no cloud connected); PENDING: will upload on the next cloud refresh; SYNCED: file is in the cloud. */
enum class CloudMark { NONE, PENDING, SYNCED }
data class LibraryItem(val book:Book,val metadata:BookMetadata?,val progress:Progress?,val cloud:CloudMark=CloudMark.NONE) {
    val progressLabel:String get()=ProgressLabels.describe(book.format,progress?.locator,metadata?.pageCount ?: 0)
}
data class LibrarySnapshot(val owner:String,val items:List<LibraryItem>)
object ProgressLabels {
    fun describe(format:String, locator:String?, pages:Int=0):String {
        if(locator==null)return "未读"
        return runCatching {
            val locations=JSONObject(locator).optJSONObject("locations")
            if(format=="pdf") {
                val page=locations?.optInt("position",0) ?: 0
                if(page>0) { if(pages>0) "第 $page / $pages 页" else "第 $page 页" } else "已开始阅读"
            } else {
                val total=locations?.optDouble("totalProgression",Double.NaN) ?: Double.NaN
                if(total.isFinite() && total in 0.0..1.0) "已读 ${(total*100).toInt()}%" else "已开始阅读"
            }
        }.getOrDefault("已开始阅读")
    }
}
class BookPresentation(private val app:ReaderApplication) {
    val covers=File(app.cacheDir,"covers").apply{mkdirs()}
    private val extraction=Mutex()
    @OptIn(ExperimentalCoroutinesApi::class)
    val library:Flow<LibrarySnapshot> = app.repository.owner.flatMapLatest { owner ->
        val progress:Flow<List<Progress>> = if(owner=="local") app.database.dao().observeProgress()
            else app.database.drive().observeStates(owner).map { states -> withContext(Dispatchers.Default) { states.mapNotNull { row -> runCatching { top.steeefanie.leafreader.drive.BookState.decodeStored(row.hash, row.currentJson).progress.firstOrNull() }.getOrNull() } } }
        val cloud:Flow<List<String>> = if(owner=="local") flowOf(emptyList()) else app.database.drive().observeCloudHashes(owner)
        val localOnly:Flow<List<String>> = if(owner=="local") flowOf(emptyList()) else app.database.drive().observeLocalOnly(owner)
        combine(app.database.dao().observeOwnedBooks(owner),app.database.presentation().observe(),progress,cloud,localOnly) { books,metadata,positions,remote,kept ->
            val byHash=metadata.associateBy{it.hash}; val byPosition=positions.associateBy{it.hash}
            val onCloud=remote.toHashSet(); val keep=kept.toHashSet()
            LibrarySnapshot(owner,books.map{
                val mark=when { owner=="local" -> CloudMark.NONE; it.hash in onCloud -> CloudMark.SYNCED; it.hash in keep -> CloudMark.NONE; else -> CloudMark.PENDING }
                LibraryItem(it,byHash[it.hash],byPosition[it.hash],mark)
            })
        }.flowOn(Dispatchers.Default)
    }
    suspend fun ensure(book:Book) = withContext(Dispatchers.IO) { extraction.withLock {
        val old=app.database.presentation().get(book.hash)
        if(old!=null && (old.coverState!="ready" || File(covers,old.coverName).exists()))return@withLock
        var author=""; var pages=0; var coverName=""; var status="none"
        var bitmap:Bitmap?=null
        try {
            val file=app.repository.file(book)
            val publication=app.engine.open(file)
            val coverPath:String?
            try {
                author=publication.metadata.authors.joinToString(" / "){it.name}.take(512)
                pages=publication.metadata.numberOfPages ?: 0
                coverPath=(publication.resources+publication.readingOrder).firstWithRel("cover")?.url()?.toString()
            } finally { publication.close() }
            currentCoroutineContext().ensureActive()
            if(book.format=="pdf") {
                ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        pages=renderer.pageCount
                        if(pages>0) renderer.openPage(0).use { page ->
                            val scale=minOf(320f/page.width.coerceAtLeast(1),480f/page.height.coerceAtLeast(1))
                            bitmap=Bitmap.createBitmap((page.width*scale).toInt().coerceIn(1,320),(page.height*scale).toInt().coerceIn(1,480),Bitmap.Config.ARGB_8888)
                            Canvas(bitmap!!).drawColor(Color.WHITE)
                            page.render(bitmap!!,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            } else if(coverPath!=null) {
                val uri=URI(coverPath)
                if(!uri.isAbsolute) ZipFile(file).use { zip ->
                    val entry=zip.getEntry(uri.path.trimStart('/'))
                    if(entry!=null && !entry.isDirectory && entry.size<=12*1024*1024) {
                        val output=ByteArrayOutputStream()
                        zip.getInputStream(entry).use { input ->
                            val buffer=ByteArray(8192)
                            while(true){ currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break;require(output.size()+n<=12*1024*1024);output.write(buffer,0,n) }
                        }
                        val bytes=output.toByteArray()
                        val options=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                        BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
                        if(options.outWidth>0 && options.outHeight>0) {
                            var sample=1
                            while(options.outWidth/sample>640 || options.outHeight/sample>960)sample*=2
                            bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.RGB_565})
                        }
                    }
                }
            }
            bitmap?.let { source ->
                val scale=minOf(1f,320f/source.width,480f/source.height)
                val gray=Bitmap.createBitmap((source.width*scale).toInt().coerceAtLeast(1),(source.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.RGB_565)
                try {
                    val canvas=Canvas(gray);canvas.drawColor(Color.WHITE)
                    canvas.drawBitmap(source,null,Rect(0,0,gray.width,gray.height),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply{colorFilter=ColorMatrixColorFilter(ColorMatrix().apply{setSaturation(0f)})})
                    val temp=File(covers,"${book.hash}.tmp")
                    try { temp.outputStream().use{check(gray.compress(Bitmap.CompressFormat.PNG,100,it))};check(temp.renameTo(File(covers,"${book.hash}.png"))) }
                    finally {temp.delete()}
                    coverName="${book.hash}.png";status="ready"
                } finally {gray.recycle()}
            }
        } catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){status="failed"}
        catch(_:OutOfMemoryError){status="failed"}
        finally {bitmap?.recycle()}
        app.database.presentation().put(BookMetadata(book.hash,author,coverName,status,pages))
    } }
}

private object CoverMemory {
    val cache=object:LruCache<String,Bitmap>(8*1024*1024){override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount}
}
class CoverView(context:Context):androidx.appcompat.widget.AppCompatImageView(context) {
    private var load:Job?=null
    init{scaleType=ImageView.ScaleType.FIT_CENTER;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO;background=context.paperBackground(Color.WHITE,Paper.line,0);setPadding(context.dp(1),context.dp(1),context.dp(1),context.dp(1))}
    fun bind(item:LibraryItem,app:ReaderApplication,scope:CoroutineScope) {
        load?.cancel();tag=item.book.hash
        val metadata=item.metadata
        if(metadata==null || metadata.coverState!="ready"){
            setImageDrawable(BookPlaceholder(item.book.title,item.book.format))
            return
        }
        val key="${item.book.hash}:${metadata.coverName}"
        CoverMemory.cache.get(key)?.let{setImageBitmap(it);return}
        setImageDrawable(BookPlaceholder(item.book.title,item.book.format))
        val file=File(app.presentation.covers,metadata.coverName)
        load=scope.launch {
            val image=withContext(Dispatchers.IO){
                if(file.exists())BitmapFactory.decodeFile(file.absolutePath,BitmapFactory.Options().apply{inPreferredConfig=Bitmap.Config.RGB_565}) else null
            }
            if(image!=null){CoverMemory.cache.put(key,image);if(tag==item.book.hash)setImageBitmap(image)}
        }
    }
    fun release(){load?.cancel();load=null;tag=null;setImageDrawable(null)}
}
private class BookPlaceholder(val title:String,val format:String):android.graphics.drawable.Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas:Canvas){
        val w=bounds.width().toFloat();val h=bounds.height().toFloat();canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat())
        paint.color=Color.rgb(240,240,240);paint.style=Paint.Style.FILL;canvas.drawRect(0f,0f,w,h,paint)
        paint.color=Color.LTGRAY;paint.strokeWidth=1f;paint.style=Paint.Style.STROKE;canvas.drawRect(.5f,.5f,w-.5f,h-.5f,paint);canvas.drawLine(w*.1f,0f,w*.1f,h,paint)
        paint.style=Paint.Style.FILL;paint.color=Color.BLACK;paint.textAlign=Paint.Align.CENTER;paint.typeface=Typeface.create("sans-serif",Typeface.BOLD);paint.textSize=w*.21f
        val text=title.filterNot{it.isWhitespace()};val rows=text.take(9).chunked(3)
        rows.forEachIndexed{i,s->canvas.drawText(s,w*.55f,h*.26f+i*w*.27f,paint)}
        paint.typeface=Typeface.DEFAULT;paint.textSize=w*.12f;paint.color=Color.DKGRAY;canvas.drawText(format.uppercase(),w*.55f,h*.88f,paint);canvas.restore()
    }
    override fun setAlpha(alpha:Int){paint.alpha=alpha}
    override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter}
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.OPAQUE
}
