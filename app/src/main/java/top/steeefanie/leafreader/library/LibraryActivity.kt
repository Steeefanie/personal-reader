package top.steeefanie.leafreader.library

import android.content.Intent
import android.os.Bundle
import android.text.*
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import top.steeefanie.leafreader.ui.PaperPanel as AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.*
import kotlinx.coroutines.*
import top.steeefanie.leafreader.ReaderApplication
import top.steeefanie.leafreader.reader.ReaderActivity
import top.steeefanie.leafreader.storage.*
import top.steeefanie.leafreader.ui.*

class LibraryActivity:PaperActivity() {
    private val app get()=application as ReaderApplication
    private var snapshot=LibrarySnapshot("local",emptyList())
    private var query=""
    private var busy=false
    private lateinit var status:TextView
    private lateinit var adapter:BooksAdapter
    private lateinit var header:HeaderAdapter
    private lateinit var empty:EmptyAdapter
    private lateinit var list:RecyclerView
    private var shown=emptyList<LibraryItem>()
    private var sort=0
    private var grid=true
    private var coverJob:Job?=null
    private var coverKey=""
    private val importer = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) runTask {
            var imported = 0; var duplicate = 0
            val failures = mutableListOf<String>()
            uris.forEachIndexed { index, uri ->
                status.text = "正在导入 ${index + 1}/${uris.size}"
                try { if (app.repository.importBook(uri, app.engine::validate)) imported++ else duplicate++ }
                catch (cancel: CancellationException) { throw cancel }
                catch (e: Exception) { failures += "第 ${index + 1} 个文件：${e.message}" }
            }
            AlertDialog.Builder(this).setTitle("导入完成")
                .setMessage("新增 $imported 本，重复 $duplicate 本，失败 ${failures.size} 本。\n${failures.take(8).joinToString("\n")}")
                .setPositiveButton("确定", null).show()
        }
    }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        sort=getSharedPreferences("library-ui",0).getInt("sort",0)
        grid=getSharedPreferences("library-ui",0).getString("layout","grid")!="list"
        val root=column()
        val search=row().apply {visibility=View.INVISIBLE}
        val input=EditText(this).apply {
            hint="搜索书名或作者";textSize=PaperText.BODY;isSingleLine=true;minHeight=dp(48);pad(10,6)
            background=paperBackground(android.graphics.Color.WHITE,android.graphics.Color.GRAY)
            addTextChangedListener(object:TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                override fun afterTextChanged(s:Editable?){}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){query=s.toString();refresh()}
            })
        }
        fun closeSearch(){
            input.setText("");search.visibility=View.INVISIBLE;input.clearFocus()
            (getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(input.windowToken,0)
        }
        search.addView(input,LinearLayout.LayoutParams(0,dp(48),1f))
        search.addView(iconButton(Glyph.CLOSE,"关闭搜索"){closeSearch()})
        root.addView(row().apply {
            pad(20,12);minimumHeight=dp(Paper.PAGE_HEADER_HEIGHT)
            addView(label("书库",PaperText.PAGE_TITLE).strong())
            addView(search,LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(24);rightMargin=dp(4)})
            addView(iconButton(Glyph.SEARCH,"搜索书籍"){
                if(search.visibility==View.VISIBLE)closeSearch()
                else{search.visibility=View.VISIBLE;input.requestFocus()}
            })
            addView(iconButton(Glyph.PLUS,"导入书籍"){if(!busy)importer.launch(arrayOf("application/epub+zip","application/pdf","application/octet-stream"))})
        })
        status=label("",PaperText.DETAIL).apply{pad(20,8);visibility=View.GONE};root.addView(status)
        adapter=BooksAdapter(app,lifecycleScope,grid,::open,::menu)
        header=HeaderAdapter{buildHeader()};empty=EmptyAdapter(this)
        list=RecyclerView(this).apply {
            layoutManager=GridLayoutManager(this@LibraryActivity,if(grid)4 else 2).apply {
                spanSizeLookup=object:GridLayoutManager.SpanSizeLookup(){
                    override fun getSpanSize(position:Int)=if(position==0 || position>this@LibraryActivity.adapter.itemCount)spanCount else 1
                }
            };adapter=ConcatAdapter(header,this@LibraryActivity.adapter,empty)
            itemAnimator=null;clipToPadding=false;setPadding(dp(8),0,dp(8),dp(16))
            addOnScrollListener(object:RecyclerView.OnScrollListener(){override fun onScrolled(view:RecyclerView,dx:Int,dy:Int){scheduleMetadata()}})
        }
        root.addView(list,LinearLayout.LayoutParams(-1,0,1f));root.addView(navigation("书库"){list});installRoot(root)
        lifecycleScope.launch {repeatOnLifecycle(Lifecycle.State.STARTED){
            app.presentation.library.collect {value->if(value.owner==app.repository.owner.value){snapshot=value;refresh()}}
        }}
    }
    override fun onStart(){super.onStart();coverKey=""}
    override fun onStop(){coverJob?.cancel();super.onStop()}
    private fun refresh(){
        if(!::adapter.isInitialized)return
        val matches=snapshot.items.filter{it.book.title.contains(query,true) || it.metadata?.author?.contains(query,true)==true}
        shown=when(sort){1->matches.sortedBy{it.book.title.lowercase()};2->matches.sortedByDescending{it.book.importedAt};else->matches.sortedByDescending{it.book.lastRead}.let{rows->rows.sortedWith(compareByDescending<LibraryItem>{it.book.lastRead}.thenByDescending{it.book.importedAt})}}
        adapter.submitList(shown);header.notifyItemChanged(0);empty.show=shown.isEmpty()
        empty.search=query.isNotBlank();list.post{scheduleMetadata()}
    }
    private fun buildHeader():View=column().apply {
        addView(row().apply {
            pad(12,6)
            addView(label("${if(query.isBlank())"全部书籍" else "搜索结果"} · ${shown.size} 本",PaperText.BODY).strong().apply{gravity=Gravity.CENTER_VERTICAL;setLineSpacing(0f,1f)},LinearLayout.LayoutParams(0,-2,1f).apply{gravity=Gravity.CENTER_VERTICAL})
            fun viewChoice(glyph:Glyph,title:String,useGrid:Boolean) {
                addView(iconButton(glyph,title){setLayout(useGrid)}.apply {
                    isSelected=grid==useGrid
                    setImageDrawable(PaperIcon(glyph,filled=isSelected))
                })
            }
            viewChoice(Glyph.GRID,"宫格视图",true)
            viewChoice(Glyph.LIST,"双栏列表视图",false)
            addView(iconButton(Glyph.SORT,"书籍排序"){sortMenu()})
        })
    }
    private fun setLayout(useGrid:Boolean){
        if(grid==useGrid)return
        val manager=list.layoutManager as GridLayoutManager
        val first=manager.findFirstVisibleItemPosition().coerceAtLeast(0)
        grid=useGrid;getSharedPreferences("library-ui",0).edit().putString("layout",if(grid)"grid" else "list").apply()
        adapter.grid=grid;manager.spanCount=if(grid)4 else 2
        list.recycledViewPool.clear();adapter.notifyDataSetChanged();header.notifyItemChanged(0);list.scrollToPosition(first)
        coverKey="";list.post{scheduleMetadata()}
    }
    private fun sortMenu(){
        AlertDialog.Builder(this).setTitle("书籍排序").setSingleChoiceItems(arrayOf("最近阅读","书名","最近导入"),sort){dialog,index->
            sort=index;getSharedPreferences("library-ui",0).edit().putInt("sort",index).apply();refresh();list.scrollToPosition(0);dialog.dismiss()
        }.setNegativeButton("取消",null).show()
    }
    private fun scheduleMetadata(){
        if(!::list.isInitialized)return
        val first=((list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()-1).coerceAtLeast(0)
        val batch=shown.drop(first).take(16)
        val key=snapshot.owner+batch.joinToString{it.book.hash}
        if(key==coverKey)return
        coverKey=key;coverJob?.cancel()
        coverJob=lifecycleScope.launch{batch.forEach{app.presentation.ensure(it.book)}}
    }
    private fun open(item:LibraryItem){if(!busy)startActivity(Intent(this,ReaderActivity::class.java).putExtra("bookHash",item.book.hash).putExtra("ownerKey",snapshot.owner))}
    private fun menu(item:LibraryItem){
        if(busy)return
        val owner=snapshot.owner
        lifecycleScope.launch {
            try {
                val connection=app.drive.connection()
                if(owner!=snapshot.owner || owner!=app.repository.owner.value)return@launch
                val connected=connection!=null && connection.ownerKey==owner && connection.status=="connected"
                val book=item.book
                val removeLocal={runTask{app.repository.remove(book,owner)}}
                val actions:List<Pair<String,()->Unit>> = when {
                    !connected -> listOf(BookRemoval.DELETE to {BookRemoval.confirmDelete(this@LibraryActivity,book.title,removeLocal)})
                    item.cloud==CloudMark.SYNCED -> listOf(
                        BookRemoval.LOCAL to {BookRemoval.confirmLocal(this@LibraryActivity,book.title,removeLocal)},
                        BookRemoval.CLOUD_ONLY to {BookRemoval.confirmCloudOnly(this@LibraryActivity,book.title){runTask{app.drive.deleteBook(book.hash,owner,keepLocal=true)}}},
                        BookRemoval.CLOUD to {BookRemoval.confirmCloud(this@LibraryActivity,book.title){runTask{app.drive.deleteBook(book.hash,owner)}}})
                    item.cloud==CloudMark.PENDING -> listOf(
                        BookRemoval.KEEP_LOCAL to {lifecycleScope.launch{app.drive.setLocalOnly(owner,book.hash,true)};Unit},
                        BookRemoval.DELETE to {BookRemoval.confirmDelete(this@LibraryActivity,book.title,removeLocal)})
                    else -> listOf(
                        BookRemoval.UPLOAD to {lifecycleScope.launch{app.drive.setLocalOnly(owner,book.hash,false);message("将在云盘页点刷新时上传")};Unit},
                        BookRemoval.DELETE to {BookRemoval.confirmDelete(this@LibraryActivity,book.title,removeLocal)})
                }
                AlertDialog.Builder(this@LibraryActivity).setTitle(book.title).alignItemsEnd().setItems(actions.map{it.first}.toTypedArray()){_,index->
                    actions[index].second()
                }.setNegativeButton("关闭",null).show()
            }catch(cancel:CancellationException){throw cancel}catch(e:Exception){message(e.message ?: "无法打开书籍菜单")}
        }
    }
    private fun runTask(block:suspend()->Unit){
        if(busy)return;busy=true;status.visibility=View.VISIBLE;status.text="正在处理…"
        lifecycleScope.launch{try{block()}catch(cancel:CancellationException){throw cancel}catch(e:Exception){message(e.message ?: "操作失败")}finally{busy=false;status.visibility=View.GONE;refresh()}}
    }
}

/** Solid = in the cloud, outline = waiting for the next manual upload. Reuses the bottom-bar cloud. */
private const val CLOUD_ICON=18

/**
 * Single-line bookshelf text view that enforces a uniform line box height regardless of script
 * (preventing Roboto vs NotoSansCJK height differences) and aligns both CJK and Latin glyph runs
 * along the exact vertical centerline (中线对齐) of the view.
 */
private class CenterLineTextView(context:android.content.Context,sizeSp:Float):androidx.appcompat.widget.AppCompatTextView(context) {
    private val boundsRect=android.graphics.Rect()
    init {
        textSize=sizeSp;setTextColor(Paper.INK);includeFontPadding=false
        maxLines=1;ellipsize=TextUtils.TruncateAt.END
    }
    override fun getBaseline():Int=-1
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
        val contentHeight=kotlin.math.ceil(paint.textSize*1.42f).toInt()
        val fixedHeight=(paddingTop+paddingBottom+contentHeight).coerceAtLeast(suggestedMinimumHeight)
        setMeasuredDimension(measuredWidth,resolveSize(fixedHeight,heightMeasureSpec))
    }
    override fun onDraw(canvas:android.graphics.Canvas) {
        val raw=text?.toString().orEmpty()
        if(raw.isEmpty())return
        val p=paint
        p.color=currentTextColor;p.drawableState=drawableState
        val avail=(width-paddingLeft-paddingRight).toFloat().coerceAtLeast(0f)
        if(avail<=0f)return
        val shown=TextUtils.ellipsize(raw,p,avail,TextUtils.TruncateAt.END).toString()
        if(shown.isEmpty())return
        val centerY=paddingTop+(height-paddingTop-paddingBottom)*0.5f
        p.getTextBounds("中",0,1,boundsRect)
        val cjkMid=(boundsRect.top+boundsRect.bottom)*0.5f
        p.getTextBounds("H",0,1,boundsRect)
        val capMid=(boundsRect.top+boundsRect.bottom)*0.5f
        p.getTextBounds("x",0,1,boundsRect)
        val xMid=(boundsRect.top+boundsRect.bottom)*0.5f
        var x=paddingLeft.toFloat()
        var i=0;val n=shown.length
        while(i<n) {
            val isCjk=shown[i].code>=0x2E80
            var j=i+1
            while(j<n && (shown[j].code>=0x2E80)==isCjk)j++
            val baseline=if(isCjk) {
                centerY-cjkMid
            } else {
                val hasTall=(i until j).any { idx ->
                    val ch=shown[idx]
                    ch in 'A'..'Z' || ch in '0'..'9' || ch=='b' || ch=='d' || ch=='f' || ch=='h' || ch=='k' || ch=='l' || ch=='t'
                }
                val hasLower=(i until j).any { idx -> shown[idx] in 'a'..'z' }
                centerY-when {
                    hasTall -> capMid
                    hasLower -> xMid
                    else -> cjkMid
                }
            }
            canvas.drawText(shown,i,j,x,baseline,p)
            x+=p.measureText(shown,i,j)
            i=j
        }
    }
}

private class HeaderAdapter(val create:()->View):RecyclerView.Adapter<HeaderAdapter.Holder>() {
    class Holder(val root:LinearLayout):RecyclerView.ViewHolder(root)
    override fun getItemCount()=1
    override fun onCreateViewHolder(parent:ViewGroup,type:Int)=Holder(parent.context.column().apply {
        layoutParams=RecyclerView.LayoutParams(-1,-2)
    })
    override fun onBindViewHolder(holder:Holder,position:Int){holder.root.removeAllViews();holder.root.addView(create(),LinearLayout.LayoutParams(-1,-2))}
}
private class EmptyAdapter(val context:android.content.Context):RecyclerView.Adapter<HeaderAdapter.Holder>() {
    var search=false
    var show=true
        set(value){if(field!=value){field=value;if(value)notifyItemInserted(0)else notifyItemRemoved(0)}else if(value)notifyItemChanged(0)}
    override fun getItemCount()=if(show)1 else 0
    override fun onCreateViewHolder(parent:ViewGroup,type:Int)=HeaderAdapter.Holder(context.column().apply {
        layoutParams=RecyclerView.LayoutParams(-1,-2);minimumHeight=context.dp(280);gravity=Gravity.CENTER
    })
    override fun onBindViewHolder(holder:HeaderAdapter.Holder,position:Int){
        holder.root.removeAllViews()
        holder.root.addView(context.emptyState(if(search)"没有找到书籍" else "从一本书开始",if(search)"换一个书名或作者试试。" else "点击右上角 ＋ 导入 EPUB 或 PDF"),LinearLayout.LayoutParams(-1,-2))
    }
}
private class CoverFrame(context:android.content.Context):FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
        val width=View.MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec,View.MeasureSpec.makeMeasureSpec((width*1.45f).toInt(),View.MeasureSpec.EXACTLY))
    }
}
private class BooksAdapter(val app:ReaderApplication,val scope:CoroutineScope,var grid:Boolean,val open:(LibraryItem)->Unit,val menu:(LibraryItem)->Unit):androidx.recyclerview.widget.ListAdapter<LibraryItem,BooksAdapter.Holder>(object:DiffUtil.ItemCallback<LibraryItem>() {
    override fun areItemsTheSame(a:LibraryItem,b:LibraryItem)=a.book.hash==b.book.hash
    override fun areContentsTheSame(a:LibraryItem,b:LibraryItem)=a==b
}) {
    class Holder(val root:LinearLayout,val cover:CoverView,val title:TextView,val author:TextView?,val progress:TextView?,val more:ImageButton?,val cloud:ImageView):RecyclerView.ViewHolder(root)
    override fun getItemViewType(position:Int)=if(grid)0 else 1
    override fun onCreateViewHolder(parent:ViewGroup,type:Int):Holder {
        val c=parent.context
        val root=c.column().apply{layoutParams=RecyclerView.LayoutParams(-1,-2);pad(12,12);background=c.touchBackground();isFocusable=true}
        val cover=CoverView(c)
        val title=CenterLineTextView(c,if(type==0)PaperText.BODY else PaperText.ITEM_TITLE).strong()
        val author=if(type==0)null else CenterLineTextView(c,PaperText.DETAIL).apply{setTextColor(Paper.secondary);setPadding(0,c.dp(6),0,0)}
        val progress=if(type==0)null else c.label("",PaperText.DETAIL).apply{maxLines=2;ellipsize=TextUtils.TruncateAt.END;setTextColor(Paper.secondary)}
        val more=if(type==0)null else c.iconButton(Glyph.MORE,"书籍操作"){}
        val cloudSize=c.dp(CLOUD_ICON)
        val cloud=ImageView(c).apply{scaleType=ImageView.ScaleType.FIT_CENTER;visibility=View.GONE}
        if(type==0){
            root.addView(CoverFrame(c).apply{addView(cover,FrameLayout.LayoutParams(-1,-1))},LinearLayout.LayoutParams(-1,-2))
            root.addView(c.gap(10))
            root.addView(c.row().apply{
                isBaselineAligned=false;gravity=Gravity.CENTER_VERTICAL
                addView(title,LinearLayout.LayoutParams(0,-2,1f).apply{gravity=Gravity.CENTER_VERTICAL})
                addView(cloud,LinearLayout.LayoutParams(cloudSize,cloudSize).apply{leftMargin=c.dp(6);gravity=Gravity.CENTER_VERTICAL})
            },LinearLayout.LayoutParams(-1,-2))
        }else{
            val content=c.row().apply{gravity=Gravity.TOP}
            val width=minOf(c.dp(88),((parent.width.takeIf{it>0} ?: c.resources.displayMetrics.widthPixels)/2*.36f).toInt())
            val coverHeight=(width*1.45f).toInt();val gutter=c.dp(14)
            content.addView(cover,LinearLayout.LayoutParams(width,coverHeight).apply{rightMargin=gutter})
            content.addView(c.column().apply{
                addView(title,LinearLayout.LayoutParams(-1,-2));addView(checkNotNull(author),LinearLayout.LayoutParams(-1,-2));addView(c.gap(10));addView(checkNotNull(progress),LinearLayout.LayoutParams(-1,-2))
                addView(c.row().apply{gravity=Gravity.END;addView(checkNotNull(more))},LinearLayout.LayoutParams(-1,-2))
            },LinearLayout.LayoutParams(0,-2,1f))
            // Cloud mark: bottom flush with the cover bottom, left flush with the text column.
            root.addView(FrameLayout(c).apply{
                addView(content,FrameLayout.LayoutParams(-1,-2))
                addView(cloud,FrameLayout.LayoutParams(cloudSize,cloudSize).apply{leftMargin=width+gutter;topMargin=coverHeight-cloudSize})
            },LinearLayout.LayoutParams(-1,-2))
            root.addView(c.rule(Paper.CONTENT_INSET-12),LinearLayout.LayoutParams(-1,c.dp(1)).apply{topMargin=c.dp(12)})
        }
        return Holder(root,cover,title,author,progress,more,cloud)
    }
    override fun onBindViewHolder(holder:Holder,position:Int){
        val item=getItem(position)
        holder.cover.bind(item,app,scope);holder.title.text=item.book.title
        holder.author?.apply { text=item.metadata?.author.orEmpty();visibility=if(text.isBlank())View.GONE else View.VISIBLE }
        holder.progress?.text="${item.book.format.uppercase()} · ${item.progressLabel}"
        holder.cloud.apply {
            visibility=if(item.cloud==CloudMark.NONE)View.GONE else View.VISIBLE
            if(item.cloud!=CloudMark.NONE)setImageDrawable(NavigationIcon(Glyph.CLOUD,item.cloud==CloudMark.SYNCED))
            contentDescription=when(item.cloud){CloudMark.SYNCED->"已同步至云端";CloudMark.PENDING->"等待上传至云端";else->null}
        }
        holder.root.contentDescription="阅读 ${item.book.title}";holder.root.setOnClickListener{open(item)}
        holder.more?.apply { contentDescription="${item.book.title}，更多操作";setOnClickListener{menu(item)} }
        holder.root.setOnLongClickListener{menu(item);true}
    }
    override fun onViewRecycled(holder:Holder){holder.cover.release();super.onViewRecycled(holder)}
}
