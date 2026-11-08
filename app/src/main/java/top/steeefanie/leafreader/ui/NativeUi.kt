package top.steeefanie.leafreader.ui

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.PathParser
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import top.steeefanie.leafreader.library.LibraryActivity
import top.steeefanie.leafreader.drive.DriveActivity

object Paper {
    const val INK = Color.BLACK
    const val PAGE_HEADER_HEIGHT = 72
    const val BOTTOM_BAR_HEIGHT = 64
    const val CONTENT_INSET = 20
    const val PAGE_CONTROLS_WIDTH = 144
    const val PAGE_CONTROLS_HEIGHT = 48
    val secondary = Color.rgb(85,85,85)
    val line = Color.rgb(212,212,212)
    val surface = Color.rgb(245,245,245)
}
/** Shared sizes in sp; UI text follows Android's font scaling independently of EPUB text. */
object PaperText {
    const val DETAIL=16f
    const val BODY=18f
    const val ITEM_TITLE=20f
    const val PANEL_TITLE=22f
    const val FEATURE_TITLE=24f
    const val PAGE_TITLE=30f
}

enum class Glyph { BOOK, TOC, CLOUD, SETTINGS, PLUS, MINUS, UP, DOWN, GRID, LIST, SEARCH, BACK, MORE, NEXT, SORT, SYNC, FOLDER, DOWNLOAD, CHECK, BOOKMARK, TYPE, CLOSE }
private val iconPaths = mapOf(
    Glyph.BOOK to "M12,18.5 C9.5,16.5 6.5,16.5 3,17 L3,5 C6.5,4.5 9.5,5 12,7 C14.5,5 17.5,4.5 21,5 L21,17 C17.5,16.5 14.5,16.5 12,18.5 Z M12,7 L12,18.5",
    Glyph.TOC to "M4,6 L20,6 M4,12 L20,12 M4,18 L20,18",
    Glyph.CLOUD to "M6,18 C0,18 1,10 6,10 C6,2 18,2 18,10 C24,10 24,18 18,18 Z",
    Glyph.SETTINGS to "M4,6 L20,6 M4,12 L20,12 M4,18 L20,18 M8,3 L8,9 M16,9 L16,15 M10,15 L10,21",
    Glyph.PLUS to "M12,4 L12,20 M4,12 L20,12",
    Glyph.MINUS to "M4,12 L20,12",
    Glyph.UP to "M5,15 L12,8 L19,15",
    Glyph.DOWN to "M5,9 L12,16 L19,9",
    Glyph.GRID to "M5,5 L10,5 L10,10 L5,10 Z M14,5 L19,5 L19,10 L14,10 Z M5,14 L10,14 L10,19 L5,19 Z M14,14 L19,14 L19,19 L14,19 Z",
    Glyph.LIST to "M5,5 L10,5 L10,10 L5,10 Z M14,6 L19,6 M14,9 L19,9 M5,14 L10,14 L10,19 L5,19 Z M14,15 L19,15 M14,18 L19,18",
    Glyph.SEARCH to "M16.5,10 A6.5,6.5 0,1 1,3.5 10 A6.5,6.5 0,1 1,16.5 10 M15,15 L21,21",
    Glyph.BACK to "M14,5 L7,12 L14,19 M7,12 L21,12",
    Glyph.MORE to "M5,11 L5,13 M12,11 L12,13 M19,11 L19,13",
    Glyph.NEXT to "M9,6 L15,12 L9,18",
    Glyph.SORT to "M4,6 L20,6 M4,12 L15,12 M4,18 L10,18",
    Glyph.SYNC to "M20,9 C17,1 5,3 4,10 M4,5 L4,10 L9,10 M4,15 C7,23 19,21 20,14 M15,14 L20,14 L20,19",
    Glyph.FOLDER to "M3,6 L10,6 L12,8 L21,8 L21,20 L3,20 Z M3,11 L21,11",
    Glyph.DOWNLOAD to "M12,3 L12,16 M7,11 L12,16 L17,11 M4,18 L4,21 L20,21 L20,18",
    Glyph.CHECK to "M4,12 L9,17 L20,6",
    Glyph.BOOKMARK to "M6,3 L18,3 L18,21 L12,17 L6,21 Z",
    Glyph.TYPE to "M2.5,19 L7.5,5 L12.5,19 M4.2,14.5 L10.8,14.5 M15.5,11.2 C15.8,9.6 17,9 18.5,9 C20.2,9 21.2,9.8 21.2,11.8 L21.2,19 M21.2,13.8 C19.2,13.8 15,14.2 15,16.7 C15,18.2 16.2,19 17.8,19 C19.3,19 20.5,18.3 21.2,17.3",
    Glyph.CLOSE to "M6,6 L18,18 M18,6 L6,18"
)
class PaperIcon(private val glyph: Glyph, private val ink: Int = Paper.INK, private val filled: Boolean = false) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style=if(filled)Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE; strokeWidth=1.65f; color=ink; strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND }
    private val path = PathParser.createPathFromPathData(iconPaths.getValue(glyph))!!
    override fun draw(canvas: Canvas) { canvas.save(); canvas.translate(bounds.left.toFloat(),bounds.top.toFloat()); canvas.scale(bounds.width()/24f,bounds.height()/24f); canvas.drawPath(path,paint); canvas.restore() }
    override fun setAlpha(alpha: Int) { paint.alpha=alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter=filter }
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
fun Context.column() = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
fun Context.row() = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
fun Context.label(value: String, size: Float=PaperText.BODY) = TextView(this).apply { text=value; textSize=size; setTextColor(Paper.INK); includeFontPadding=false; setLineSpacing(dp(3).toFloat(),1f) }
fun View.pad(horizontal: Int=20, vertical: Int=0) { setPadding(context.dp(horizontal),context.dp(vertical),context.dp(horizontal),context.dp(vertical)) }
fun TextView.strong() = apply { setTypeface(typeface,Typeface.BOLD) }
/** Insets are drawn inside the divider, so parent layout parameters cannot discard them. */
fun Context.contentDivider(inset:Int=Paper.CONTENT_INSET):Drawable =
    InsetDrawable(ColorDrawable(Paper.line),dp(inset),0,dp(inset),0)
fun Context.rule(inset:Int=Paper.CONTENT_INSET)=View(this).apply {
    background=contentDivider(inset);layoutParams=LinearLayout.LayoutParams(-1,dp(1))
}
fun Context.gap(height: Int)=View(this).apply { layoutParams=LinearLayout.LayoutParams(-1,dp(height)) }
fun Context.paperBackground(fill: Int=Color.WHITE, stroke: Int=Color.TRANSPARENT, radius: Int=8) = GradientDrawable().apply { setColor(fill); cornerRadius=dp(radius).toFloat(); if(stroke!=Color.TRANSPARENT)setStroke(dp(1),stroke) }
fun Context.touchBackground(primary: Boolean=false): Drawable = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_pressed),paperBackground(if(primary) Color.DKGRAY else Paper.surface))
    addState(intArrayOf(android.R.attr.state_focused),paperBackground(if(primary) Color.DKGRAY else Paper.surface,Color.GRAY))
    addState(intArrayOf(),paperBackground(if(primary) Color.BLACK else Color.WHITE))
}
fun Context.button(value: String, primary: Boolean=false, action: ()->Unit) = androidx.appcompat.widget.AppCompatButton(this).apply {
    text=value; textSize=PaperText.BODY; isAllCaps=false; minHeight=dp(48); minimumHeight=dp(48); setTextColor(if(primary) Color.WHITE else Color.BLACK)
    background=touchBackground(primary); stateListAnimator=null; pad(16,10); setOnClickListener { action() }
}
fun Context.iconButton(glyph: Glyph, description: String, action: ()->Unit)=androidx.appcompat.widget.AppCompatImageButton(this).apply {
    setImageDrawable(PaperIcon(glyph)); contentDescription=description; background=touchBackground(); scaleType=ImageView.ScaleType.CENTER_INSIDE
    setPadding(dp(13),dp(13),dp(13),dp(13)); minimumWidth=dp(48); minimumHeight=dp(48); stateListAnimator=null
    layoutParams=LinearLayout.LayoutParams(dp(48),dp(48)); setOnClickListener { action() }
}
/** Match the reading toolbar's first of five slots in full pages and bottom panels. */
fun Context.readerBackButton(description:String,action:()->Unit):View=object:FrameLayout(this) {
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        val available=View.MeasureSpec.getSize(widthMeasureSpec)
        val width=if(View.MeasureSpec.getMode(widthMeasureSpec)==View.MeasureSpec.UNSPECIFIED)dp(64)
            else (available/5).coerceAtLeast(dp(48)).coerceAtMost(available)
        super.onMeasure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),heightMeasureSpec)
    }
}.apply {
    layoutParams=LinearLayout.LayoutParams(-2,dp(Paper.BOTTOM_BAR_HEIGHT))
    background=touchBackground();isFocusable=true;contentDescription=description;setOnClickListener { action() }
    addView(ImageView(context).apply { setImageDrawable(PaperIcon(Glyph.BACK)) },
        FrameLayout.LayoutParams(dp(28),dp(28),Gravity.CENTER))
}
fun Context.pageHeader(title: String, subtitle: String="", actions: List<View> = emptyList()): View = row().apply {
    pad(20,12);minimumHeight=dp(Paper.PAGE_HEADER_HEIGHT)
    addView(column().apply {
        if(subtitle.isNotBlank()) addView(label(subtitle,PaperText.DETAIL).apply { setTextColor(Paper.secondary); setPadding(0,0,0,dp(8)) })
        addView(label(title,PaperText.PAGE_TITLE).strong())
    },LinearLayout.LayoutParams(0,-2,1f))
    actions.forEach { addView(it) }
}
fun Context.caption(text: String)=label(text,PaperText.DETAIL).apply { setTextColor(Paper.secondary); letterSpacing=.06f; pad(20,12) }
fun Context.settingRow(glyph: Glyph, title: String, detail: String="", action: ()->Unit): View = row().apply {
    pad(20,16); minimumHeight=dp(64); background=touchBackground(); isFocusable=true; contentDescription=listOf(title,detail).filter{it.isNotBlank()}.joinToString("，")
    addView(ImageView(context).apply { setImageDrawable(PaperIcon(glyph)) },LinearLayout.LayoutParams(dp(22),dp(22)).apply{rightMargin=dp(16)})
    addView(column().apply { addView(label(title,PaperText.BODY)); if(detail.isNotBlank()) addView(label(detail,PaperText.DETAIL).apply { setTextColor(Paper.secondary); setPadding(0,dp(6),0,0) }) },LinearLayout.LayoutParams(0,-2,1f))
    addView(ImageView(context).apply { setImageDrawable(PaperIcon(Glyph.NEXT,Paper.secondary)) },LinearLayout.LayoutParams(dp(18),dp(18)).apply{leftMargin=dp(12)})
    setOnClickListener { action() }
}
fun Context.emptyState(title: String, description: String, glyph: Glyph=Glyph.BOOK): View = column().apply {
    gravity=Gravity.CENTER; pad(24,36)
    addView(ImageView(context).apply{setImageDrawable(PaperIcon(glyph,Paper.secondary))},LinearLayout.LayoutParams(dp(36),dp(36)))
    addView(gap(20)); addView(label(title,PaperText.PANEL_TITLE).strong().apply { gravity=Gravity.CENTER })
    addView(gap(12)); addView(label(description,PaperText.DETAIL).apply { gravity=Gravity.CENTER; setTextColor(Paper.secondary) })
}
fun AppCompatActivity.navigation(selected: String, scrollTarget:()->View? = { null }): View = column().apply {
    addView(rule(0))
    addView(row().apply {
        pad(8,0)
        addView(View(context),LinearLayout.LayoutParams(0,1,.14f))
        listOf(Triple("书库",Glyph.BOOK,LibraryActivity::class.java), Triple("云盘",Glyph.CLOUD,DriveActivity::class.java), Triple("设置",Glyph.SETTINGS,SettingsActivity::class.java)).forEach { (name,glyph,target) ->
            addView(column().apply {
                gravity=Gravity.CENTER; background=touchBackground(); isFocusable=true; contentDescription=name; isSelected=name==selected
                addView(ImageView(context).apply {
                    scaleType=ImageView.ScaleType.FIT_CENTER
                    setImageDrawable(NavigationIcon(glyph,name==selected))
                    importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },LinearLayout.LayoutParams(dp(36),dp(36)))
                setOnClickListener {
                    if(name!=selected) { startActivity(Intent(this@navigation,target).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)); if(this@navigation !is LibraryActivity) finish() }
                }
            },LinearLayout.LayoutParams(0,dp(Paper.BOTTOM_BAR_HEIGHT),.72f))
        }
        addView(View(context),LinearLayout.LayoutParams(0,1,.70f))
        addView(FrameLayout(context).apply {
            addView(pageControls(scrollTarget),FrameLayout.LayoutParams(dp(Paper.PAGE_CONTROLS_WIDTH),dp(Paper.PAGE_CONTROLS_HEIGHT),Gravity.END or Gravity.CENTER_VERTICAL))
        },LinearLayout.LayoutParams(dp(Paper.PAGE_CONTROLS_WIDTH),dp(Paper.BOTTOM_BAR_HEIGHT)))
    },LinearLayout.LayoutParams(-1,dp(Paper.BOTTOM_BAR_HEIGHT)))
}
fun <T> Context.paperTextAdapter(items:List<T> = emptyList(),singleChoice:Boolean=false,alignEnd:Boolean=false):ArrayAdapter<T> =
    object:ArrayAdapter<T>(this,if(singleChoice)android.R.layout.simple_list_item_single_choice else android.R.layout.simple_list_item_1,ArrayList(items)) {
        override fun getView(position:Int,convertView:View?,parent:ViewGroup):View =
            super.getView(position,convertView,parent).apply {
                val label=this as TextView
                label.textSize=PaperText.ITEM_TITLE
                label.setTextColor(Paper.INK);label.minHeight=context.dp(64);label.pad(20,14)
                label.gravity=if(alignEnd)Gravity.END or Gravity.CENTER_VERTICAL else Gravity.START or Gravity.CENTER_VERTICAL
                label.textAlignment=if(alignEnd)View.TEXT_ALIGNMENT_VIEW_END else View.TEXT_ALIGNMENT_VIEW_START
            }
    }
fun Context.message(value: String)=Toast.makeText(this,value,Toast.LENGTH_LONG).show()
fun AppCompatActivity.installRoot(root: View) {
    setContentView(root)
    ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets ->
        val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
    }
}
