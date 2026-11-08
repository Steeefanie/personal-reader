@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package top.steeefanie.leafreader.reader

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import top.steeefanie.leafreader.ui.PaperPanel as AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.room.withTransaction
import com.github.barteksc.pdfviewer.PDFView
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONObject
import org.readium.adapter.pdfium.navigator.*
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.epub.*
import org.readium.r2.navigator.input.*
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.pdf.PdfNavigatorFactory
import org.readium.r2.navigator.pdf.PdfNavigatorFragment
import org.readium.r2.navigator.preferences.*
import org.readium.r2.shared.publication.*
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.publication.services.search.search
import top.steeefanie.leafreader.ReaderApplication
import top.steeefanie.leafreader.R
import top.steeefanie.leafreader.storage.*
import top.steeefanie.leafreader.ui.*
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import org.readium.r2.shared.publication.services.positionsByReadingOrder

class ReaderActivity : PaperActivity() {
    private val app get() = application as ReaderApplication
    private val state: ReaderState by viewModels()
    private val book get() = checkNotNull(state.book)
    private val publication get() = checkNotNull(state.publication)
    private var navigator: OverflowableNavigator? = null
    private lateinit var chrome: FrameLayout
    private lateinit var chromeTitle: TextView
    private lateinit var topBookmarkButton: ImageButton
    private lateinit var moreButton: ImageButton
    private lateinit var formatTool: LinearLayout
    private lateinit var toolbarDivider:View
    private lateinit var location: TextView
    private lateinit var footerPage: TextView
    private lateinit var bookmarksPanel: LinearLayout
    private lateinit var bookmarksList: LinearLayout
    private lateinit var readingRoot: FrameLayout
    private var chapterPage=0
    private var chapterPages=0
    private var pdfPages=0
    private var spineHrefs=emptyList<String>()
    private var spinePositions=emptyList<Int>()
    private val measuredChapterPages=mutableMapOf<Int,Int>()
    private var activeBookmark:Bookmark?=null
    private lateinit var container: FragmentContainerView
    private var searchJob: Job? = null
    private var openDialogs = 0
        set(value) { field=value.coerceAtLeast(0);updateToolbarDivider() }
    private val dialogVisible get() = openDialogs > 0
    private var restoringPdf = false
    private var readyToSave = false
    private var dragging = false
    private var selectionGesture=false
    private var pdfTouchX: Float? = null
    private var pdfTouchY = 0f
    private var ownerKey = "local"
    private var textBookmarks:TextBookmarkController?=null
    private var bookmarkSnapshot=emptyList<Bookmark>()
    private lateinit var returnToOrigin:Button
    private var ignoreJumpHistory=false
    private var manualTurnFrom:String?=null
    private fun positionKey(locator:Locator)=listOf(locator.href.toString(),locator.locations.progression,locator.locations.position).joinToString("|")


    override fun onCreate(savedInstanceState: Bundle?) {
        // Reader factories depend on an asynchronously opened publication. Rebuild the fragment
        // from persisted Locator instead of restoring a factory-less Fragment after process death.
        super.onCreate(null)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (textBookmarks?.isSelecting == true || textBookmarks?.hasPopup == true) {
                    textBookmarks?.finishSelection()
                    return
                }
                if (chrome.visibility == View.VISIBLE) {
                    hideChrome()
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
        ownerKey = intent.getStringExtra("ownerKey") ?: app.repository.owner.value
        savedInstanceState?.getString("jumpOrigin")?.let { json ->
            runCatching {
                val value=JSONObject(json)
                state.jumpOrigin=Progress(value.getString("hash"),value.getString("format"),value.getJSONObject("locator").toString(),
                    1L,value.optDouble("pdfOffset",0.0).toFloat(),value.optDouble("pdfZoom",1.0).toFloat())
            }
        }
        val root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.WHITE) };readingRoot=root
        container = FragmentContainerView(this).apply { id = R.id.reader_container }
        root.addView(container, FrameLayout.LayoutParams(-1, -1))
        footerPage=label("",PaperText.DETAIL).apply {
            gravity=Gravity.CENTER;setBackgroundColor(android.graphics.Color.WHITE);setTextColor(Paper.secondary)
            contentDescription="阅读页码";visibility=View.GONE
        }
        root.addView(footerPage,FrameLayout.LayoutParams(-1,dp(32),Gravity.BOTTOM))

        chrome=FrameLayout(this)
        val top=column()
        top.addView(row().apply{
            pad(16,6);minimumHeight=dp(60)
            chromeTitle=label("正在打开…",PaperText.BODY).strong().apply{contentDescription="阅读书名";maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
            addView(chromeTitle,LinearLayout.LayoutParams(-2,-2).apply{rightMargin=dp(16)})
            location=label("",PaperText.BODY).apply{contentDescription="阅读章节与进度";setTextColor(Paper.secondary);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
            addView(location,LinearLayout.LayoutParams(0,-2,1f).apply{rightMargin=dp(8)})
            topBookmarkButton=iconButton(Glyph.BOOKMARK,"添加书签"){toggleCurrentBookmark()}.apply{visibility=View.GONE}
            addView(topBookmarkButton)
            moreButton=iconButton(Glyph.MORE,"更多阅读操作"){more()}.apply{visibility=View.GONE}
            addView(moreButton)
            addOnLayoutChangeListener { view,left,_,right,_,_,_,_,_ ->
                val buttons=(if(topBookmarkButton.visibility==View.VISIBLE)dp(48) else 0)+(if(moreButton.visibility==View.VISIBLE)dp(48) else 0)
                val available=(right-left-view.paddingLeft-view.paddingRight-buttons-dp(24)).coerceAtLeast(0)
                val limit=available/2
                if(chromeTitle.maxWidth!=limit)chromeTitle.maxWidth=limit
            }
        });top.addView(rule(0))
        chrome.addView(top,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        val bottom=column()
        bookmarksList=column()
        bookmarksPanel=column().apply {
            visibility=View.GONE
            addView(rule(0))
            addView(row().apply {
                pad(16,10)
                addView(label("书签",PaperText.BODY).strong(),LinearLayout.LayoutParams(0,-2,1f))
            })
            addView(rule(0))
            val maxPanel=(resources.displayMetrics.heightPixels*.4f).toInt()
            val scroll=object:ScrollView(this@ReaderActivity){
                override fun onMeasure(w:Int,h:Int)=super.onMeasure(w,MeasureSpec.makeMeasureSpec(maxPanel,MeasureSpec.AT_MOST))
            }.apply{addView(bookmarksList)}
            addView(scroll,LinearLayout.LayoutParams(-1,-2))
        }
        bottom.addView(bookmarksPanel)
        bottom.addView(rule(0))
        bottom.addView(row().apply{
            pad(8,0)
            fun tool(glyph:Glyph,title:String,action:()->Unit){
                addView(column().apply{
                    if(glyph==Glyph.TYPE)formatTool=this
                    gravity=Gravity.CENTER;background=touchBackground();isFocusable=true;contentDescription=title
                    addView(ImageView(context).apply{setImageDrawable(PaperIcon(glyph))},LinearLayout.LayoutParams(dp(28),dp(28)))
                    setOnClickListener{action()}
                },LinearLayout.LayoutParams(0,dp(Paper.BOTTOM_BAR_HEIGHT),1f))
            }
            addView(readerBackButton("返回书库"){finish()})
            toolbarDivider=View(context).apply { setBackgroundColor(Paper.INK);contentDescription="阅读工具栏分隔线" }
            addView(toolbarDivider,LinearLayout.LayoutParams(dp(1),dp(22)))
            tool(Glyph.TOC,"目录"){bookmarksPanel.visibility=View.GONE;contents()}
            tool(Glyph.SEARCH,"搜索"){bookmarksPanel.visibility=View.GONE;searchPrompt()}
            tool(Glyph.TYPE,"排版"){bookmarksPanel.visibility=View.GONE;if(state.book?.format=="pdf")jumpPage()else settingsDialog()}
            tool(Glyph.BOOKMARK,"书签"){toggleBookmarksPanel()}
        },LinearLayout.LayoutParams(-1,dp(Paper.BOTTOM_BAR_HEIGHT)))
        chrome.addView(bottom,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        root.addView(chrome,FrameLayout.LayoutParams(-1,-1))
        returnToOrigin=button("返回原处") { returnFromJump() }.apply {
            textSize=PaperText.DETAIL;minHeight=dp(32);minimumHeight=dp(32);pad(12,4)
            background=paperBackground(android.graphics.Color.WHITE,Paper.INK,18)
            contentDescription="返回原处";visibility=if(state.jumpOrigin==null)View.GONE else View.VISIBLE
        }
        root.addView(returnToOrigin,FrameLayout.LayoutParams(-2,dp(36),Gravity.TOP or Gravity.START).apply {
            leftMargin=dp(16);topMargin=dp(68)
        })
        top.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ ->
            val params=returnToOrigin.layoutParams as FrameLayout.LayoutParams
            val margin=top.height+dp(8)
            if(params.topMargin!=margin){params.topMargin=margin;returnToOrigin.layoutParams=params}
        }
        installRoot(root)
        lifecycleScope.launch {
            try {
                val hash = intent.getStringExtra("bookHash") ?: error("未指定书籍")
                state.book = app.repository.book(hash, ownerKey) ?: error("此空间没有该书籍，请重新导入或下载")
                state.initialProgress = app.repository.progress(hash, ownerKey)
                state.preferences = app.settings.read()
                applyReadingDisplay()
                if (state.publication == null) state.publication = withContext(Dispatchers.IO) { app.engine.open(app.repository.file(book)) }
                spineHrefs = publication.readingOrder.map { it.href.toString() }
                createNavigator()
                lifecycleScope.launch {
                    spinePositions = withContext(Dispatchers.IO) { publication.positionsByReadingOrder().map { it.size.coerceAtLeast(1) } }
                    updatePageCounter(navigator?.currentLocator?.value)
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    app.repository.ensureBookState(hash, ownerKey)
                    app.database.drive().touch(ownerKey, hash, System.currentTimeMillis())
                }
                app.drive.bookOpened(ownerKey, hash)
                lifecycleScope.launch {
                    app.drive.remoteChanges.collect { change ->
                        if (change.owner == ownerKey && change.hash == hash && !dialogVisible && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                            val target = Locator.fromJSON(JSONObject(change.progress.locator)) ?: return@collect
                            if (navigator?.currentLocator?.value?.toJSON()?.toString() != target.toJSON().toString()) {
                                showDialog(AlertDialog.Builder(this@ReaderActivity).setTitle("云端阅读位置已更新")
                                    .setMessage("是否跳转到云端位置？当前页面保持不变，继续阅读会保存新的本地进度。")
                                    .setPositiveButton("跳转") { _, _ -> jumpTo(target) }
                                    .setNegativeButton("继续当前阅读") { _, _ -> saveProgress() })
                            }
                        }
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) {
                AlertDialog.Builder(this@ReaderActivity).setTitle("无法阅读")
                    .setMessage(e.message ?: "文件无法打开")
                    .setPositiveButton("返回书库") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
            }
        }
    }

    private fun createNavigator() {
        chromeTitle.text=book.title
        moreButton.visibility=if(book.format=="pdf")View.VISIBLE else View.GONE
        topBookmarkButton.visibility=if(book.format=="pdf")View.VISIBLE else View.GONE
        if(book.format=="epub")textBookmarks=TextBookmarkController(this,lifecycleScope,
            { navigator as? top.steeefanie.leafreader.reader.epub.NativeEpubNavigatorFragment },{ hideChrome() },::addTextBookmark,::removeTextBookmark)
        formatTool.contentDescription=if(book.format=="pdf")"页码" else "排版"
        val initial = state.initialProgress?.locator?.let { Locator.fromJSON(JSONObject(it)) }
        val fragment: Fragment = if (book.format == "epub") {
            top.steeefanie.leafreader.reader.epub.NativeEpubNavigatorFragment().apply {
                publication = this@ReaderActivity.publication
                initialLocator = initial
                submit(state.preferences)
                typefaceLoader = { settings -> readingTypeface(settings) }
                onPageLoaded = { readyToSave = true }
                onPageChanged = { pageIndex, totalPages, locator ->
                    chapterPage = pageIndex + 1; chapterPages = totalPages; updatePageCounter(locator)
                }
                onSwipeTurn = { forward -> if (state.preferences.touchTurnPage) turn(forward) }
                onInternalLink = {
                    // Only a real in-book link activation starts native history.
                    if (!ignoreJumpHistory && readyToSave) { manualTurnFrom = null; rememberJumpOrigin() }
                }
                onExternalLink = { url ->
                    val uri = android.net.Uri.parse(url)
                    if (uri.scheme !in setOf("http", "https")) message("仅支持打开网页链接")
                    else try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) }
                    catch (_: android.content.ActivityNotFoundException) { message("没有可打开链接的浏览器") }
                }
            }
        } else {
            val factory = PdfNavigatorFactory(publication, EinkPdfEngineProvider(app.repository.file(book), loaded = { pdf ->
                val progress = state.initialProgress
                pdf.post {
                    if (isDestroyed) return@post
                    if (progress != null) {
                        restoringPdf = true
                        pdf.zoomTo(progress.pdfZoom)
                        pdf.setPositionOffset(progress.pdfOffset.coerceIn(0f, 1f), false)
                        val expected = ((initial?.locations?.position ?: 1) - 1).coerceIn(0, (pdf.pageCount - 1).coerceAtLeast(0))
                        // The locator's page wins if viewport geometry changed since the last read.
                        if (pdf.currentPage != expected) pdf.jumpTo(expected, false)
                        restoringPdf = false
                    }
                    readyToSave = true;pdfPages=pdf.pageCount;updatePageCounter(navigator?.currentLocator?.value)
                }
            }, failed = { message("PDF 加载失败：${it.message.orEmpty()}") }))
                .createFragmentFactory(initial, PdfiumPreferences(fit = Fit.CONTAIN, pageSpacing = 0.0, scrollAxis = Axis.HORIZONTAL))
            supportFragmentManager.fragmentFactory = factory
            factory.instantiate(classLoader, PdfNavigatorFragment::class.java.name)
        }
        supportFragmentManager.beginTransaction().replace(container.id, fragment, "navigator").commitNow()
        navigator = fragment as OverflowableNavigator
        navigator?.addInputListener(input)
        textBookmarks?.attach()
        lifecycleScope.launch {
            navigator!!.currentLocator.collect { locator ->
                showLocation(locator)
                if(manualTurnFrom!=null && positionKey(locator)!=manualTurnFrom) {
                    clearJumpOrigin();manualTurnFrom=null
                }
                refreshBookmarkState(locator)
                state.saveJob?.cancel()
                state.saveJob = lifecycleScope.launch { delay(1200); saveProgress() }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.repository.observeBookmarks(book.hash,ownerKey).collectLatest { marks ->
                    bookmarkSnapshot=marks.filterNot { it.deleted }.sortedByDescending { it.updatedAt }
                    textBookmarks?.render(bookmarkSnapshot)
                    refreshBookmarkState()
                    if(bookmarksPanel.visibility==View.VISIBLE)fillBookmarks()
                }
            }
        }
        chrome.visibility = View.GONE
    }

    /** One font file renders both CJK and Latin, so both scripts share metrics and one baseline. */
    private suspend fun readingTypeface(settings: ReadingSettings): android.graphics.Typeface {
        app.fonts.families()
        val family = app.fonts.resolveForReading(settings.font)
        return app.fonts.preview(family, settings.fontWeight) ?: android.graphics.Typeface.DEFAULT
    }
    private fun applyEpubSettings() {
        val epub = navigator as? top.steeefanie.leafreader.reader.epub.NativeEpubNavigatorFragment ?: return
        manualTurnFrom = null
        // Typography changes every chapter's screen page count; drop old measurements.
        measuredChapterPages.clear()
        epub.submit(state.preferences)
    }

    private fun showLocation(locator:Locator){
        val progress=top.steeefanie.leafreader.library.ProgressLabels.describe(book.format,locator.toJSON().toString(),publication.metadata.numberOfPages ?: 0)
        location.text=if(book.format=="pdf")progress else listOfNotNull(locator.title?.takeIf{it.isNotBlank()},progress).joinToString(" · ")
        updatePageCounter(locator)
    }
    private fun chapterIndex(locator:Locator?):Int{
        val href=locator?.href?.toString() ?: return -1
        return spineHrefs.indexOfFirst{it==href}.takeIf{it>=0} ?: spineHrefs.indexOfFirst{href.substringBefore('#').endsWith(it.substringBefore('#'))}
    }
    /** Whole-book screen pages: measured chapters are exact; others are scaled from Readium positions. */
    private fun bookPages(locator:Locator?):Pair<Int,Int>?{
        val index=chapterIndex(locator)
        if(index<0 || chapterPages<=0 || spinePositions.size!=spineHrefs.size)return null
        measuredChapterPages[index]=chapterPages
        val ratio=chapterPages.toDouble()/spinePositions[index]
        fun pagesOf(i:Int)=measuredChapterPages[i] ?: (spinePositions[i]*ratio).roundToInt().coerceAtLeast(1)
        val before=(0 until index).sumOf{pagesOf(it)}
        val total=spineHrefs.indices.sumOf{pagesOf(it)}
        return (before+chapterPage).coerceIn(1,total) to total
    }
    private fun updatePageCounter(locator:Locator?){
        val value=if(state.book?.format=="pdf") {
            val page=locator?.locations?.position ?: 1
            val total=pdfPages.takeIf{it>0} ?: state.publication?.metadata?.numberOfPages ?: 0
            if(total>0)"$page / $total" else ""
        }else bookPages(locator)?.let{(page,total)->"$page / $total"} ?: ""
        if(footerPage.text.toString()!=value)footerPage.text=value
    }
    private fun applyReadingDisplay(){
        val settings=state.preferences
        val height=dp(32)
        footerPage.visibility=if(settings.showPageNumbers)View.VISIBLE else View.GONE
        footerPage.layoutParams=(footerPage.layoutParams as FrameLayout.LayoutParams).apply{this.height=height}
        container.layoutParams=(container.layoutParams as FrameLayout.LayoutParams).apply{
            bottomMargin=if(settings.showPageNumbers)height else dp(16)
            // Without the status bar the window reaches the screen edge; keep text off the top edge.
            topMargin=if(settings.showStatusBar)0 else dp(28)
        }
        val control=androidx.core.view.WindowInsetsControllerCompat(window,readingRoot)
        control.systemBarsBehavior=androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if(settings.showStatusBar)control.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        else control.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        androidx.core.view.ViewCompat.requestApplyInsets(readingRoot)
    }
    override fun onResume(){
        super.onResume()
        app.drive.setReadingActive(true)
        if(navigator!=null)lifecycleScope.launch {
            val latest=app.settings.read()
            if(latest!=state.preferences){state.preferences=latest;applyReadingDisplay();applyEpubSettings()}
        }
    }
    private fun updateToolbarDivider() {
        if(::toolbarDivider.isInitialized)toolbarDivider.visibility=
            if(dialogVisible || (::bookmarksPanel.isInitialized && bookmarksPanel.visibility==View.VISIBLE))View.INVISIBLE else View.VISIBLE
    }
    private fun hideChrome(){ chrome.visibility = View.GONE; bookmarksPanel.visibility = View.GONE;updateToolbarDivider() }
    private fun turn(forward: Boolean): Boolean {
        if(textBookmarks?.isSelecting==true)return false
        textBookmarks?.dismissPopup();hideChrome()
        val current=navigator?.currentLocator?.value ?: return false
        manualTurnFrom=positionKey(current)
        val accepted=if(forward)navigator?.goForward(false) ?: false else navigator?.goBackward(false) ?: false
        if(!accepted){manualTurnFrom=null}
        return accepted
    }
    private val input = object : InputListener {
        override fun onTap(event: TapEvent): Boolean {
            if (dialogVisible) return false
            if(selectionGesture || textBookmarks?.consumesDismissalTap()==true)return true
            if(textBookmarks?.isSelecting==true)return true
            if(textBookmarks?.hasPopup==true){textBookmarks?.dismissPopup();return true}
            val width = navigator?.publicationView?.width?.toFloat() ?: return false
            return when {
                event.point.x < width * .28f -> {
                    if (state.preferences.touchTurnPage) turn(false)
                    else { if (chrome.visibility == View.VISIBLE) hideChrome() else chrome.visibility = View.VISIBLE }
                    true
                }
                event.point.x > width * .72f -> {
                    if (state.preferences.touchTurnPage) turn(true)
                    else { if (chrome.visibility == View.VISIBLE) hideChrome() else chrome.visibility = View.VISIBLE }
                    true
                }
                else -> { if (chrome.visibility == View.VISIBLE) hideChrome() else chrome.visibility = View.VISIBLE; true }
            }
        }
        override fun onDrag(event: DragEvent): Boolean {
            if(selectionGesture || textBookmarks?.isSelecting==true){dragging=false;return true}
            // Readium emits End even for an unmoved tap. Consuming it suppresses the click.
            if (book.format != "epub" || (event.type == DragEvent.Type.End && !dragging)) return false
            if (event.type == DragEvent.Type.Start) dragging = true
            if (event.type == DragEvent.Type.End && dragging) {
                dragging = false
                if (state.preferences.touchTurnPage && abs(event.offset.x) > dp(48) && abs(event.offset.x) > abs(event.offset.y)) turn(event.offset.x < 0)
            }
            return true
        }
    }
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (!dialogVisible && textBookmarks?.isSelecting!=true && currentFocus !is EditText && navigator != null) {
            val forward = when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_PAGE_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> true
                android.view.KeyEvent.KEYCODE_PAGE_UP, android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_VOLUME_UP -> false
                else -> null
            }
            if (forward != null) { if (event.action == android.view.KeyEvent.ACTION_DOWN) turn(forward); return true }
        }
        return super.dispatchKeyEvent(event)
    }
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if(event.actionMasked==android.view.MotionEvent.ACTION_DOWN) {
            // Keep the entire gesture blocked even if Android ends selection midway through it.
            selectionGesture=textBookmarks?.isSelecting==true || textBookmarks?.hasPopup==true || textBookmarks?.consumesDismissalTap()==true
        }
        if (state.preferences.touchTurnPage && state.book?.format == "pdf" && !dialogVisible && chrome.visibility != View.VISIBLE) {
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    pdfTouchX = event.x; pdfTouchY = event.y
                }
                android.view.MotionEvent.ACTION_POINTER_DOWN, android.view.MotionEvent.ACTION_CANCEL -> pdfTouchX = null
                android.view.MotionEvent.ACTION_UP -> {
                    val start = pdfTouchX; pdfTouchX = null
                    val zoom = findPdf(container)?.zoom ?: 1f
                    if (start != null && zoom <= 1.01f && abs(event.x - start) > dp(48) && abs(event.x - start) > abs(event.y - pdfTouchY)) {
                        turn(event.x < start)
                        return true
                    }
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun currentPoint():Progress? {
        val locator=navigator?.currentLocator?.value ?: return null
        val pdf=findPdf(container)
        return Progress(book.hash,book.format,locator.toJSON().toString(),1L,pdf?.positionOffset ?: 0f,pdf?.zoom ?: 1f)
    }
    private fun rememberJumpOrigin() {
        currentPoint()?.let { state.jumpOrigin=it;returnToOrigin.visibility=View.VISIBLE }
    }
    private fun clearJumpOrigin() { state.jumpOrigin=null;returnToOrigin.visibility=View.GONE }
    private fun performJump(action:()->Boolean) {
        manualTurnFrom=null
        val origin=currentPoint() ?: return
        textBookmarks?.finishSelection();hideChrome()
        ignoreJumpHistory=true
        val accepted=try { action() } finally { ignoreJumpHistory=false }
        if(accepted){state.jumpOrigin=origin;returnToOrigin.visibility=View.VISIBLE}
    }
    private fun jumpTo(locator:Locator) = performJump { navigator?.go(locator,false) ?: false }
    private fun jumpTo(link:Link) = performJump { navigator?.go(link,false) ?: false }
    private fun returnFromJump() {
        val origin=state.jumpOrigin ?: return
        val locator=runCatching { Locator.fromJSON(JSONObject(origin.locator)) }.getOrNull() ?: return
        manualTurnFrom=null;ignoreJumpHistory=true
        val accepted=try { navigator?.go(locator,false)==true } finally { ignoreJumpHistory=false }
        if(accepted) {
            clearJumpOrigin()
            if(book.format=="pdf")findPdf(container)?.let { pdf -> pdf.post {
                pdf.zoomTo(origin.pdfZoom);pdf.setPositionOffset(origin.pdfOffset.coerceIn(0f,1f),false)
                val expected=(locator.locations.position ?: 1)-1
                if(pdf.currentPage!=expected)pdf.jumpTo(expected,false)
            } }
        }
    }
    override fun onSaveInstanceState(outState:Bundle) {
        state.jumpOrigin?.let { origin -> outState.putString("jumpOrigin",JSONObject().apply {
            put("hash",origin.hash);put("format",origin.format);put("locator",JSONObject(origin.locator))
            put("pdfOffset",origin.pdfOffset);put("pdfZoom",origin.pdfZoom)
        }.toString()) }
        super.onSaveInstanceState(outState)
    }
    private fun saveProgress(closing: Boolean = false) {
        if (!readyToSave || restoringPdf || state.book == null) {
            if (closing && state.book != null) app.drive.bookClosed(ownerKey)
            return
        }
        val locator = navigator?.currentLocator?.value ?: run {
            if (closing) app.drive.bookClosed(ownerKey)
            return
        }
        val pdf = findPdf(container)
        val progress = Progress(book.hash, book.format, locator.toJSON().toString(), System.currentTimeMillis(),
            pdf?.positionOffset ?: 0f, pdf?.zoom ?: 1f)
        // Application scope survives Activity destruction; database transaction serializes saves.
        app.applicationScope.launch {
            try {
                app.repository.saveProgress(progress, ownerKey, flushFile = closing)
                if (closing) app.drive.bookClosed(ownerKey)
            } catch (e: Exception) { withContext(Dispatchers.Main) { message("进度保存失败，请检查剩余空间") } }
        }
    }
    override fun onPause() { state.saveJob?.cancel(); saveProgress(closing = true); super.onPause() }
    override fun onDestroy() { searchJob?.cancel();textBookmarks?.close(); navigator?.removeInputListener(input); navigator = null; super.onDestroy() }

    private fun contents() {
        if (navigator == null) return
        val links = mutableListOf<Pair<String, Link>>()
        fun flatten(items: List<Link>, depth: Int) { items.forEach { link ->
            links += ("  ".repeat(depth.coerceAtMost(8)) + (link.title ?: "章节")) to link
            flatten(link.children, depth + 1)
        } }
        flatten(publication.tableOfContents, 0)
        if (links.isEmpty()) { message("本书没有目录"); return }
        showDialog(
            AlertDialog.Builder(this)
                .setTitle("目录")
                .fullScreen()
                .setItems(links.map { it.first }.toTypedArray()) { _, index ->
                    jumpTo(links[index].second)
                }
        )
    }
    private fun more() {
        if (navigator == null) return
        if (book.format == "epub") { message("暂无更多操作"); return }
        showDialog(AlertDialog.Builder(this).setTitle("阅读操作").setItems(arrayOf("跳转页码", "适合宽度", "整页显示")) { _, index ->
            when (index) {
                0 -> jumpPage()
                1 -> setPdfFit(true)
                2 -> setPdfFit(false)
            }
        })
    }
    private fun progressionOf(locator:String):Pair<String,Double?>? = runCatching {
        val json=JSONObject(locator)
        json.getString("href") to json.optJSONObject("locations")?.let{ if(it.has("progression")) it.getDouble("progression") else null }
    }.getOrNull()
    /** A bookmark belongs to the visible screen when it points inside the current page's progression span. */
    private fun isOnCurrentPage(mark:Bookmark, current:Locator):Boolean {
        if (book.format == "pdf") {
            val page = runCatching { JSONObject(mark.locator).optJSONObject("locations")?.optInt("position") }.getOrNull()
            return page != null && page == current.locations.position
        }
        val (href, p) = progressionOf(mark.locator) ?: return false
        if (href != current.href.toString()) return false
        val cp = current.locations.progression ?: return p == null
        val span = if (chapterPages > 0) 1.0 / chapterPages else 0.002
        return p != null && p >= cp - span * .5 && p < cp + span * .5
    }
    private fun refreshBookmarkState(locator:Locator? = navigator?.currentLocator?.value) {
        if(state.book?.format!="pdf")return
        val current=locator ?: return
        activeBookmark=bookmarkSnapshot.firstOrNull { isOnCurrentPage(it,current) }
        val filled=activeBookmark!=null
        topBookmarkButton.setImageDrawable(PaperIcon(Glyph.BOOKMARK,filled=filled))
        topBookmarkButton.contentDescription=if(filled)"取消本页书签" else "添加本页书签"
    }
    private fun addTextBookmark(locator:Locator) {
        val quote=locator.text.highlight?.trim()?.takeIf { it.isNotBlank() } ?: return
        lifecycleScope.launch {
            try {
                val mark=Bookmark(book.hash,UUID.randomUUID().toString(),book.format,quote.take(1024),locator.toJSON().toString(),System.currentTimeMillis())
                app.repository.saveBookmark(mark,ownerKey)
            } catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){message("书签保存失败，请检查剩余空间")}
        }
    }
    private fun removeTextBookmark(bookmark:Bookmark) {
        lifecycleScope.launch {
            try { app.repository.saveBookmark(bookmark.copy(deleted=true,updatedAt=System.currentTimeMillis()),ownerKey) }
            catch(cancel:CancellationException){throw cancel}
            catch(_:Exception){message("书签删除失败")}
        }
    }
    private fun toggleCurrentBookmark() {
        if(state.book?.format!="pdf")return
        val locator = navigator?.currentLocator?.value ?: return
        lifecycleScope.launch {
            try {
                val existing = activeBookmark
                if (existing != null) app.repository.saveBookmark(existing.copy(deleted = true, updatedAt = System.currentTimeMillis()), ownerKey)
                else app.repository.saveBookmark(Bookmark(book.hash, UUID.randomUUID().toString(), book.format,
                    locator.title ?: "本页书签", locator.toJSON().toString(), System.currentTimeMillis()), ownerKey)
                refreshBookmarkState(locator)
                if (bookmarksPanel.visibility == View.VISIBLE) fillBookmarks()
            } catch (e: Exception) { message("书签保存失败") }
        }
    }
    private fun toggleBookmarksPanel() {
        if (bookmarksPanel.visibility == View.VISIBLE) { bookmarksPanel.visibility = View.GONE;updateToolbarDivider();return }
        fillBookmarks(); bookmarksPanel.visibility = View.VISIBLE;updateToolbarDivider()
    }
    private fun fillBookmarks() {
        bookmarksList.removeAllViews()
        if(bookmarkSnapshot.isEmpty()) {
            bookmarksList.addView(label(if(book.format=="pdf")"暂无书签，点右上角添加本页书签" else "暂无书签，长按正文选字后添加",PaperText.DETAIL).apply { pad(16,16);setTextColor(Paper.secondary) })
            return
        }
        bookmarkSnapshot.forEach { bookmark ->
            val title=TextBookmarks.display(bookmark)
            bookmarksList.addView(row().apply {
                pad(20,12);minimumHeight=dp(52);background=touchBackground();isFocusable=true;contentDescription=title
                addView(label(title,PaperText.BODY).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END },LinearLayout.LayoutParams(0,-2,1f))
                setOnClickListener { Locator.fromJSON(JSONObject(bookmark.locator))?.let { jumpTo(it) } }
                setOnLongClickListener { bookmarkActions(bookmark);true }
            },LinearLayout.LayoutParams(-1,-2))
            bookmarksList.addView(rule())
        }
    }
    private fun bookmarkActions(bookmark: Bookmark) {
        showDialog(AlertDialog.Builder(this).setTitle(bookmark.label).setItems(arrayOf("修改名称", "删除书签")) { _, action ->
            if (action == 0) {
                val field = EditText(this).apply { textSize=PaperText.BODY;minHeight=dp(48);setText(bookmark.label); filters = arrayOf(android.text.InputFilter.LengthFilter(1024)) }
                showDialog(AlertDialog.Builder(this).setTitle("书签名称").setView(field.apply { pad(20, 12) })
                    .setPositiveButton("保存") { _, _ -> lifecycleScope.launch { app.repository.saveBookmark(bookmark.copy(label = field.text.toString().ifBlank { bookmark.label }, updatedAt = System.currentTimeMillis()), ownerKey); fillBookmarks() } }
                    .setNegativeButton("取消", null))
            } else lifecycleScope.launch { app.repository.saveBookmark(bookmark.copy(deleted = true, updatedAt = System.currentTimeMillis()), ownerKey); fillBookmarks(); refreshBookmarkState() }
        })
    }
    private fun jumpPage() {
        val field = EditText(this).apply { textSize=PaperText.BODY;minHeight=dp(48);inputType = android.text.InputType.TYPE_CLASS_NUMBER; hint = "输入页码" }
        showDialog(AlertDialog.Builder(this).setTitle("跳转页码").setView(field).setPositiveButton("跳转") { _, _ ->
            val page = field.text.toString().toIntOrNull()
            lifecycleScope.launch {
                val positions = publication.positions()
                if (page == null || page !in 1..positions.size) message("页码超出范围，共 ${positions.size} 页")
                else jumpTo(positions[page - 1])
            }
        }.setNegativeButton("取消", null))
    }
    private fun setPdfFit(width: Boolean) {
        val pdf = findPdf(container) ?: return
        if (width) pdf.fitToWidth(pdf.currentPage) else pdf.resetZoom()
        saveProgress()
    }


    private fun settingsDialog(){
        openDialogs++
        showTypography(state.preferences){value->
            state.preferences=value;applyReadingDisplay();applyEpubSettings()
            lifecycleScope.launch{app.settings.save(value)}
        }.setOnDismissListener{openDialogs--}
    }

    /** One full page: query field at top, results below, tapping a result jumps and closes. */
    private fun searchPrompt() {
        if (navigator == null) return
        val results = mutableListOf<Pair<String, Locator>>()
        val adapter = paperTextAdapter<String>()
        val status = label(if (state.lastSearch.isBlank()) "输入关键词后点右上角搜索" else "",PaperText.DETAIL).apply { pad(20, 8); setTextColor(Paper.secondary) }
        val field = EditText(this).apply {
            hint = "搜索正文"; isSingleLine = true; textSize=PaperText.BODY; minHeight = dp(44); pad(12, 6)
            background = paperBackground(android.graphics.Color.WHITE, android.graphics.Color.GRAY)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setText(state.lastSearch)
        }
        val list = ListView(this).apply { this.adapter = adapter; divider = contentDivider(); dividerHeight = dp(1) }
        val content = column().apply {
            addView(row().apply {
                pad(16, 10)
                addView(field, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
            })
            addView(status)
            addView(rule())
            addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        lateinit var dialog: AlertDialog
        fun run() {
            val query = field.text.toString().trim()
            if (query.isEmpty()) return
            state.lastSearch = query
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(field.windowToken, 0)
            field.clearFocus()
            results.clear(); adapter.clear()
            search(query, results, adapter, status)
        }
        val go = iconButton(Glyph.SEARCH, "开始搜索") { run() }
        dialog = AlertDialog.Builder(this).setTitle("搜索").fullScreen().hideHeaderRule().setHeaderAction(go).setView(content).create()
        field.setOnEditorActionListener { _, action, _ -> if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { run(); true } else false }
        list.setOnItemClickListener { _, _, index, _ -> results.getOrNull(index)?.let { jumpTo(it.second) }; dialog.dismiss(); hideChrome() }
        openDialogs++
        dialog.setOnDismissListener { openDialogs--; searchJob?.cancel() }
        dialog.show()
        if (state.lastSearch.isNotBlank()) run() else field.requestFocus()
    }
    private fun search(query: String, results: MutableList<Pair<String, Locator>>, adapter: ArrayAdapter<String>, status: TextView) {
        searchJob?.cancel()
        status.text = "正在搜索“$query”…"
        searchJob = lifecycleScope.launch {
            try {
                if (book.format == "pdf") {
                    withContext(Dispatchers.IO) {
                        PDFBoxResourceLoader.init(applicationContext)
                        val memory = MemoryUsageSetting.setupMixed(8L * 1024 * 1024).apply { tempDir = cacheDir }
                        PDDocument.load(app.repository.file(book), memory).use { document ->
                            val positions = publication.positions()
                            val stripper = PDFTextStripper()
                            for (page in 1..document.numberOfPages) {
                                ensureActive()
                                stripper.startPage = page; stripper.endPage = page
                                val text = stripper.getText(document)
                                val index = text.indexOf(query, ignoreCase = true)
                                if (index >= 0 && page <= positions.size) {
                                    val snippet = text.substring((index - 30).coerceAtLeast(0), (index + query.length + 80).coerceAtMost(text.length)).replace('\n', ' ').trim()
                                    withContext(Dispatchers.Main) { results += "第 $page 页 · $snippet" to positions[page - 1]; adapter.add(results.last().first) }
                                }
                            }
                        }
                    }
                } else {
                    val iterator = publication.search(query) ?: error("此书无法搜索正文")
                    try {
                        while (isActive) {
                            val batch = withContext(Dispatchers.IO) { iterator.next() }
                            if (batch.isFailure) error("读取章节失败，搜索未完成")
                            val locators = batch.getOrNull()?.locators ?: break
                            for (locator in locators) {
                                val snippet = (locator.text.before.orEmpty().takeLast(24) + locator.text.highlight.orEmpty() + locator.text.after.orEmpty().take(60))
                                    .replace(Regex("\\s+"), " ").trim()
                                val text = listOfNotNull(locator.title?.takeIf { it.isNotBlank() }, snippet.ifBlank { null }).joinToString(" · ").ifBlank { "搜索结果" }
                                results += text to locator; adapter.add(text)
                            }
                            status.text = "已找到 ${results.size} 条，继续搜索…"
                            if (results.size >= 500) break
                        }
                    } finally { iterator.close() }
                }
                status.text = if (results.isEmpty()) (if (book.format == "pdf") "未找到结果（扫描 PDF 没有文本层）" else "未找到“$query”")
                    else "共 ${results.size} 条${if (results.size >= 500) "（已达上限）" else ""}"
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { status.text = e.message ?: "搜索失败" }
        }
    }
    private fun showDialog(builder: AlertDialog.Builder) {
        openDialogs++
        builder.create().apply { setOnDismissListener { openDialogs-- }; show() }
    }
    private fun findPdf(view: View): PDFView? {
        if (view is PDFView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findPdf(view.getChildAt(i))?.let { return it }
        return null
    }
}
