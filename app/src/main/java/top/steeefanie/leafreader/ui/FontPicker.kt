package top.steeefanie.leafreader.ui

import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import top.steeefanie.leafreader.ReaderApplication
import top.steeefanie.leafreader.reader.fonts.DeviceFontFamily
import top.steeefanie.leafreader.reader.fonts.FontIndex

/** Font previews load only for visible rows, using device files rather than bundled bitmaps. */
fun AppCompatActivity.showFontPicker(families:List<DeviceFontFamily>,selectedId:String,weight:Double,
                                   choose:(DeviceFontFamily)->Unit):PaperPanel {
    val catalog=(application as ReaderApplication).fonts
    val scope=CoroutineScope(lifecycleScope.coroutineContext+SupervisorJob(lifecycleScope.coroutineContext[Job]))
    val list=ListView(this).apply {
        divider=contentDivider();dividerHeight=dp(1)
        isVerticalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER
    }
    val content=column()
    val search=EditText(this).apply {
        hint="搜索中文名、英文名或别名";textSize=PaperText.BODY;setSingleLine(true);pad(20,12)
        inputType=android.text.InputType.TYPE_CLASS_TEXT
        imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        contentDescription="搜索本机字体"
    }
    val count=label("",PaperText.DETAIL).apply { pad(20,8);setTextColor(Paper.secondary) }
    var chineseOnly=true
    var visible=emptyList<DeviceFontFamily>()
    var refresh:()->Unit={}
    val chinese=button("中文字体") { chineseOnly=true;refresh() }
    val all=button("全部字体") { chineseOnly=false;refresh() }
    content.addView(search,LinearLayout.LayoutParams(-1,-2))
    content.addView(row().apply {
        pad(20,4)
        addView(chinese,LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin=dp(8) })
        addView(all,LinearLayout.LayoutParams(0,-2,1f))
    })
    content.addView(count)
    content.addView(rule())
    content.addView(list,LinearLayout.LayoutParams(-1,0,1f))
    val adapter=object:BaseAdapter() {
        override fun getCount()=visible.size
        override fun getItem(position:Int)=visible[position]
        override fun getItemId(position:Int)=position.toLong()
        override fun getView(position:Int,convertView:View?,parent:ViewGroup):View {
            val holder=(convertView?.tag as? PreviewRow) ?: PreviewRow(this@showFontPicker)
            val family=getItem(position)
            if(holder.boundFamily==family.id && (holder.previewLoaded || holder.job?.isActive==true))return holder.root
            holder.job?.cancel()
            holder.boundFamily=family.id;holder.previewLoaded=false
            holder.root.tag=holder
            holder.root.contentDescription="选择字体 ${family.name}"
            holder.title.text=family.name
            holder.selected.visibility=if(family.id==selectedId)View.VISIBLE else View.INVISIBLE
            holder.original.text=family.originalName
            holder.original.visibility=if(family.generic)View.GONE else View.VISIBLE
            holder.detail.text=listOfNotNull(
                family.category.takeIf { it.isNotBlank() },
                if(family.generic)"系统默认" else if(family.chinese)"含中文字形" else "中文使用系统补字",
                if(family.generic)null else "字重 "+family.weights.joinToString(" / ") { "$it ${FontIndex.weightName(it)}".trim() }
            ).joinToString(" · ")
            holder.sample.text="正在载入预览…";holder.sample.typeface=Typeface.DEFAULT
            holder.english.visibility=View.GONE
            holder.job=scope.launch {
                val typeface=catalog.preview(family,weight)
                ensureActive()
                if(typeface==null) {
                    holder.sample.text="此字体暂不能预览"
                } else {
                    holder.sample.typeface=typeface
                    holder.english.typeface=typeface
                    holder.sample.text="山川与日常，读书也读世界。"
                    holder.english.text="Reading Aa 0123456789"
                    holder.english.visibility=View.VISIBLE
                }
                holder.previewLoaded=true
            }
            return holder.root
        }
    }
    list.adapter=adapter
    list.setRecyclerListener { view -> (view.tag as? PreviewRow)?.job?.cancel() }
    refresh={
        visible=families.filter { family -> (!chineseOnly || family.generic || family.chinese) && family.matches(search.text.toString()) }
        listOf(chinese to chineseOnly,all to !chineseOnly).forEach { (button,active) ->
            button.isSelected=active
            button.setTextColor(if(active)android.graphics.Color.WHITE else Paper.INK)
            button.background=touchBackground(active)
        }
        count.text=if(visible.isEmpty())"没有匹配的字体，可切换“全部字体”" else "${visible.size} 项 · 按当前字重预览"
        adapter.notifyDataSetChanged();list.setSelection(0)
    }
    refresh()
    search.addTextChangedListener { refresh() }
    val panel=PaperPanel.Builder(this).setTitle("本机字体").fullScreen().setView(content).show()
    panel.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    list.setOnItemClickListener { _,_,position,_ -> choose(visible[position]);panel.dismiss() }
    panel.window?.decorView?.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view:View)=Unit
        override fun onViewDetachedFromWindow(view:View) {
            scope.cancel()
            list.adapter=null;view.removeOnAttachStateChangeListener(this)
        }
    })
    return panel
}

private class PreviewRow(activity:AppCompatActivity) {
    val root=activity.column().apply { pad(20,14);descendantFocusability=ViewGroup.FOCUS_BLOCK_DESCENDANTS }
    val title=activity.label("",PaperText.ITEM_TITLE).strong()
    val selected=activity.label("已选",PaperText.DETAIL).strong()
    val original=activity.label("",PaperText.DETAIL).apply { setTextColor(Paper.secondary);pad(0,4) }
    val detail=activity.label("",PaperText.DETAIL).apply { setTextColor(Paper.secondary);pad(0,4) }
    val sample=activity.label("",PaperText.FEATURE_TITLE).apply { contentDescription="中文字体预览";pad(0,6) }
    val english=activity.label("",PaperText.ITEM_TITLE).apply { contentDescription="英文字体预览" }
    var job:Job?=null
    var boundFamily:String?=null
    var previewLoaded=false
    init {
        root.addView(activity.row().apply {
            addView(title,LinearLayout.LayoutParams(0,-2,1f))
            addView(selected,LinearLayout.LayoutParams(-2,-2).apply { leftMargin=activity.dp(12) })
        })
        root.addView(original);root.addView(detail);root.addView(sample);root.addView(english)
    }
}
