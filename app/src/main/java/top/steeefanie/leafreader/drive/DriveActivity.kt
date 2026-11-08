package top.steeefanie.leafreader.drive

import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import top.steeefanie.leafreader.ReaderApplication
import top.steeefanie.leafreader.reader.ReaderActivity
import top.steeefanie.leafreader.ui.*

class DriveActivity : PaperActivity() {
    private val app get() = application as ReaderApplication
    private lateinit var account: TextView
    private lateinit var state: TextView
    private lateinit var directory: androidx.recyclerview.widget.RecyclerView
    private lateinit var path: TextView
    private val parents = mutableListOf<Pair<String, String>>()
    private var operation: Job? = null
    private var displayedOwner: String? = null
    private var restoredDirectoryState:Parcelable?=null
    private fun stateName(value: String) = when (value) {
        "connected" -> "已授权"; "revoked" -> "已撤销"; "unbound" -> "已解绑"; else -> "需要重新绑定"
    }


    private lateinit var directoryAdapter:DirectoryAdapter
    private lateinit var unbound:View
    private lateinit var breadcrumb:View
    private val downloading=mutableSetOf<String>()
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        savedInstanceState?.let { saved ->
            val ids=saved.getStringArrayList("directoryIds").orEmpty()
            val names=saved.getStringArrayList("directoryNames").orEmpty()
            if(ids.size==names.size)parents.addAll(ids.zip(names))
            displayedOwner=saved.getString("directoryOwner")
            @Suppress("DEPRECATION")
            restoredDirectoryState=saved.getParcelable("directoryScroll")
        }
        val root=column()
        root.addView(pageHeader("云盘",actions=listOf(
            iconButton(Glyph.SYNC,"同步并刷新"){task{app.drive.synchronize();app.drive.uploadPending();refreshDirectory()}},
            iconButton(Glyph.SETTINGS,"连接与账号"){connectionSettings()}
        )))
        account=label("",PaperText.DETAIL).apply{pad(20,8);setTextColor(Paper.secondary)};root.addView(account)
        state=label("",PaperText.DETAIL).apply{pad(20,8);setTextColor(Paper.secondary)};root.addView(state)
        path=label("我的书库",PaperText.BODY).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.MIDDLE}
        breadcrumb=row().apply{pad(8,4)
            addView(iconButton(Glyph.BACK,"返回上级目录"){if(parents.size>1){parents.removeAt(parents.lastIndex);task{refreshDirectory()}}})
            addView(path,LinearLayout.LayoutParams(0,-2,1f))
        };root.addView(breadcrumb);root.addView(rule())
        val stage=android.widget.FrameLayout(this)
        directoryAdapter=DirectoryAdapter(select={entry,offline->
            if(entry.kind=="directory") {parents+=entry.id to entry.name;task{render(app.drive.cachedDirectory(entry.id));refreshDirectory()}}
            else if(offline && entry.ownerKey==app.repository.owner.value) startActivity(Intent(this,ReaderActivity::class.java).putExtra("bookHash",entry.hash).putExtra("ownerKey",entry.ownerKey))
            else download(entry)
        },menu=::bookMenu)
        directory=androidx.recyclerview.widget.RecyclerView(this).apply{layoutManager=androidx.recyclerview.widget.GridLayoutManager(this@DriveActivity,2);adapter=directoryAdapter;itemAnimator=null;setPadding(dp(8),0,dp(8),dp(20));clipToPadding=false}
        stage.addView(directory,android.widget.FrameLayout.LayoutParams(-1,-1))
        unbound=ScrollView(this).apply{isFillViewport=true;addView(column().apply{
            gravity=android.view.Gravity.CENTER;pad(28,24)
            addView(emptyState("把书库带在身边","连接你的私人云盘，\n选择书籍下载后，即可离线阅读。",Glyph.CLOUD))
            addView(button("连接云盘",true){connectionSettings()},LinearLayout.LayoutParams(dp(240),-2).apply{gravity=android.view.Gravity.CENTER_HORIZONTAL})
        })};stage.addView(unbound,android.widget.FrameLayout.LayoutParams(-1,-1))
        root.addView(stage,LinearLayout.LayoutParams(-1,0,1f));root.addView(navigation("云盘"){if(directory.visibility==View.VISIBLE)directory else unbound});installRoot(root)
        lifecycleScope.launch{app.drive.status.collect{state.text=if(it=="未连接云盘")"本地书籍不受连接状态影响" else it}}
    }
    override fun onSaveInstanceState(outState:Bundle) {
        outState.putStringArrayList("directoryIds",ArrayList(parents.map { it.first }))
        outState.putStringArrayList("directoryNames",ArrayList(parents.map { it.second }))
        outState.putString("directoryOwner",displayedOwner)
        outState.putParcelable("directoryScroll",directory.layoutManager?.onSaveInstanceState())
        super.onSaveInstanceState(outState)
    }
    private fun connectionSettings() {
        startActivity(Intent(this,DriveConnectionActivity::class.java))
    }
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { showConnection() }
    }
    private suspend fun showConnection(){
        val connection=app.drive.connection()
        account.text=if(connection==null)"尚未连接" else "${connection.userName} · ${connection.deviceName} · ${stateName(connection.status)}"
        unbound.visibility=if(connection==null)View.VISIBLE else View.GONE
        directory.visibility=if(connection==null)View.GONE else View.VISIBLE;breadcrumb.visibility=directory.visibility
        if(displayedOwner!=connection?.ownerKey || (connection!=null && parents.firstOrNull()?.first!=connection.rootId)){
            displayedOwner=connection?.ownerKey;parents.clear();restoredDirectoryState=null;directoryAdapter.submitList(emptyList())
            if(connection!=null)parents+=connection.rootId to "我的书库"
        }
        if(connection!=null)render(app.drive.cachedDirectory(parents.last().first))
    }
    private suspend fun refreshDirectory(){
        val result=withContext(Dispatchers.IO){app.drive.refreshDirectory(parents.lastOrNull()?.first)}
        if(parents.isEmpty())parents+=result.parentId to "我的书库"
        render(result.entries)
    }
    private suspend fun render(entries:List<RemoteEntry>){
        path.text=parents.joinToString(" / "){it.second}
        val cached=app.repository.dao.ownedBooks(app.repository.owner.value).map{it.hash}.toSet()
        val visible=entries.filter{it.name!=".reader" && !it.name.endsWith(".json",true) && !it.format.equals("json",true)}
        directoryAdapter.submitList(visible.map{DirectoryItem(it,it.kind=="file" && it.hash in cached,it.id in downloading)}) {
            restoredDirectoryState?.let { saved ->
                directory.layoutManager?.onRestoreInstanceState(saved)
                restoredDirectoryState=null
            }
        }
        if(visible.isEmpty())state.text="当前目录暂无书籍，点刷新获取最新内容"
    }
    private fun bookMenu(entry:RemoteEntry,offline:Boolean) {
        if(entry.kind!="file" || entry.id in downloading)return
        if(entry.ownerKey!=app.repository.owner.value){message("账号已切换，请刷新目录");return}
        PaperPanel.Builder(this).setTitle(entry.name).alignItemsEnd().setItems(
            if(offline)arrayOf(BookRemoval.LOCAL,BookRemoval.CLOUD_ONLY,BookRemoval.CLOUD) else arrayOf(BookRemoval.DELETE)){_,index->
            val local={task{app.repository.book(entry.hash,entry.ownerKey)?.let { app.repository.remove(it,entry.ownerKey) }}}
            when{
                !offline->BookRemoval.confirmCloud(this,entry.name){task{app.drive.deleteBook(entry.hash,entry.ownerKey,entry)}}
                index==0->BookRemoval.confirmLocal(this,entry.name,local)
                index==1->BookRemoval.confirmCloudOnly(this,entry.name){task{app.drive.deleteBook(entry.hash,entry.ownerKey,entry,keepLocal=true)}}
                else->BookRemoval.confirmCloud(this,entry.name){task{app.drive.deleteBook(entry.hash,entry.ownerKey,entry)}}
            }
        }.setNegativeButton("关闭",null).show()
    }
    private fun download(entry: RemoteEntry) {
        if (operation?.isActive == true) { message("请先完成当前操作"); return }
        if (entry.ownerKey != app.repository.owner.value) { message("账号已切换，请刷新目录"); return }
        downloading += entry.id
        lifecycleScope.launch { showConnection() }
        task {
            try {
                withContext(Dispatchers.IO) {
                    app.drive.download(entry.id, app.engine::validate) { bytes, total ->
                        runOnUiThread { state.text = "正在下载 ${entry.name} · ${bytes / 1024} / ${total / 1024} KB" }
                    }
                }
                state.text = "${entry.name} 已下载完成"
            } finally {
                downloading -= entry.id
                showConnection()
            }
        }
    }
    private fun task(action: suspend () -> Unit) {
        if (operation?.isActive == true) { message("正在处理，请稍候"); return }
        operation = lifecycleScope.launch {
            try { action(); showConnection() }
            catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { state.text = safeError(e); message(safeError(e)) }
        }
    }
    private fun safeError(e: Exception): String = when (e) {
        is DriveException -> if (e.status == 404 && app.repository.owner.value == "local") "云盘尚未提供阅读器接口，或地址不正确" else e.message.orEmpty()
        is IllegalArgumentException, is IllegalStateException -> e.message ?: "云盘响应校验失败"
        else -> "连接失败，请检查网络和接口状态；本地数据与同步队列已保留"
    }

}


private data class DirectoryItem(val entry:RemoteEntry,val offline:Boolean,val downloading:Boolean=false)
private class DirectoryAdapter(val select:(RemoteEntry,Boolean)->Unit,val menu:(RemoteEntry,Boolean)->Unit):androidx.recyclerview.widget.ListAdapter<DirectoryItem,DirectoryAdapter.Holder>(object:androidx.recyclerview.widget.DiffUtil.ItemCallback<DirectoryItem>(){
    override fun areItemsTheSame(a:DirectoryItem,b:DirectoryItem)=a.entry.ownerKey==b.entry.ownerKey && a.entry.id==b.entry.id
    override fun areContentsTheSame(a:DirectoryItem,b:DirectoryItem)=a==b
}){
    class Holder(val root:LinearLayout,val icon:ImageView,val title:TextView,val detail:TextView,val status:TextView,val action:ImageButton,val spinner:ProgressBar):androidx.recyclerview.widget.RecyclerView.ViewHolder(root)
    override fun onCreateViewHolder(parent:android.view.ViewGroup,type:Int):Holder{
        val c=parent.context;val root=c.column().apply{pad(8,0)};val row=c.row().apply{pad(12,14);minimumHeight=c.dp(80);background=c.touchBackground();isFocusable=true}
        val icon=ImageView(c);row.addView(icon,LinearLayout.LayoutParams(c.dp(26),c.dp(30)).apply{rightMargin=c.dp(12)})
        val title=c.label("",PaperText.BODY).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END}
        val detail=c.label("",PaperText.DETAIL).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;setTextColor(Paper.secondary);setPadding(0,c.dp(6),0,0)}
        val status=c.label("",PaperText.DETAIL).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;setTextColor(Paper.secondary);setPadding(0,c.dp(4),0,0)}
        row.addView(c.column().apply{addView(title);addView(detail);addView(status)},LinearLayout.LayoutParams(0,-2,1f))
        val action=c.iconButton(Glyph.NEXT,"打开"){}
        val spinner=ProgressBar(c).apply{
            isIndeterminate=true
            indeterminateTintList=android.content.res.ColorStateList.valueOf(Paper.INK)
            visibility=View.GONE
        }
        val rightBox=android.widget.FrameLayout(c).apply{
            addView(action,android.widget.FrameLayout.LayoutParams(c.dp(48),c.dp(48),android.view.Gravity.CENTER))
            addView(spinner,android.widget.FrameLayout.LayoutParams(c.dp(24),c.dp(24),android.view.Gravity.CENTER))
        }
        row.addView(rightBox,LinearLayout.LayoutParams(c.dp(48),c.dp(48)))
        root.addView(row);root.addView(c.rule(Paper.CONTENT_INSET-8))
        return Holder(root,icon,title,detail,status,action,spinner)
    }
    override fun onBindViewHolder(holder:Holder,position:Int){
        val item=getItem(position);val entry=item.entry;val folder=entry.kind=="directory"
        holder.icon.setImageDrawable(PaperIcon(if(folder)Glyph.FOLDER else Glyph.BOOK))
        holder.title.maxLines=if(folder)2 else 1
        holder.title.text=if(folder)entry.name else entry.name.replace(Regex("(?i)\\.(epub|pdf)$"),"")
        val size=if(entry.size>=1024*1024)String.format(java.util.Locale.ROOT,"%.1f MB",entry.size/1048576.0) else "${entry.size/1024} KB"
        val format=entry.format.ifBlank { entry.name.substringAfterLast('.',"") }.uppercase(java.util.Locale.ROOT)
        holder.detail.text=if(folder)"文件夹" else "${format.ifBlank { "文件" }} · $size"
        holder.status.visibility=if(folder)View.GONE else View.VISIBLE
        holder.status.text=if(item.downloading)"正在下载…" else if(item.offline)"已下载" else "未下载"
        holder.spinner.visibility=if(item.downloading)View.VISIBLE else View.GONE
        holder.action.visibility=if(item.downloading)View.GONE else View.VISIBLE
        holder.action.setImageDrawable(PaperIcon(if(folder)Glyph.NEXT else if(item.offline)Glyph.CHECK else Glyph.DOWNLOAD))
        holder.action.contentDescription=if(folder)"打开 ${entry.name}" else if(item.offline)"阅读 ${entry.name}" else "下载 ${entry.name}"
        holder.root.getChildAt(0).setOnLongClickListener{if(folder || item.downloading)false else {menu(entry,item.offline);true}}
        holder.action.setOnLongClickListener{if(folder || item.downloading)false else {menu(entry,item.offline);true}}
        holder.action.setOnClickListener{if(!item.downloading)select(entry,item.offline)};(holder.root.getChildAt(0)).setOnClickListener{if(!item.downloading)select(entry,item.offline)}
    }
}
