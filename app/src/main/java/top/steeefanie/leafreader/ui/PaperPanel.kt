package top.steeefanie.leafreader.ui

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.RecyclerView

/** Edge-to-edge pages and content-sized bottom panels. No floating card, scrim, shadow or animation. */
class PaperPanel private constructor(context: Context, private val spec: Builder): Dialog(context) {
    private lateinit var heading: TextView
    private val buttons = mutableMapOf<Int,Button>()
    val isFullScreen get() = spec.full

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root=context.column()
        root.addView(context.rule(0))
        root.addView(context.row().apply {
            pad(20,4)
            minimumHeight = context.dp(56)
            heading = context.label(spec.title,PaperText.PANEL_TITLE).strong()
            addView(heading, LinearLayout.LayoutParams(0,-2,1f))
            spec.headerAction?.let { addView(it) }
        })
        if(spec.headerRule)root.addView(context.rule())
        val body:View=when {
            spec.items!=null -> ListView(context).apply {
                divider=context.contentDivider();dividerHeight=context.dp(1)
                adapter=context.paperTextAdapter(spec.items!!.toList(),singleChoice=spec.checked>=0,alignEnd=spec.itemsEnd)
                if(spec.checked>=0){choiceMode=ListView.CHOICE_MODE_SINGLE;setItemChecked(spec.checked,true)}
                setOnItemClickListener{_,_,index,_->
                    spec.selection?.onClick(this@PaperPanel,index)
                    if(spec.checked<0)dismiss()
                }
            }
            spec.view!=null -> spec.view!!.let { supplied ->
                (supplied.parent as? ViewGroup)?.removeView(supplied)
                if(supplied is ScrollView || supplied is ListView || supplied is RecyclerView || spec.full)supplied
                else ScrollView(context).apply{isFillViewport=spec.full;addView(supplied)}
            }
            else -> ScrollView(context).apply {
                addView(context.label(spec.message,PaperText.BODY).apply{pad(24,20);setTextIsSelectable(true)})
            }
        }
        if(spec.full) {
            root.addView(body,LinearLayout.LayoutParams(-1,0,1f))
        } else {
            val maxBodyHeight=(context.resources.displayMetrics.heightPixels*.65f).toInt()
            val wrapper=object:FrameLayout(context){
                override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
                    val capped=MeasureSpec.makeMeasureSpec(maxBodyHeight,MeasureSpec.AT_MOST)
                    super.onMeasure(widthMeasureSpec,capped)
                }
            }.apply{addView(body,FrameLayout.LayoutParams(-1,-2))}
            root.addView(wrapper,LinearLayout.LayoutParams(-1,-2))
        }
        root.addView(context.rule(0))
        root.addView(context.row().apply {
            pad(8,0)
            if (spec.actions.isEmpty()) {
                addView(context.readerBackButton("返回") { cancel() })
                addView(Space(context), LinearLayout.LayoutParams(0, 1, 1f))
            } else {
                val actions = context.row().apply { gravity=Gravity.END or Gravity.CENTER_VERTICAL }
                val actionKeys = listOf(BUTTON_NEGATIVE, BUTTON_NEUTRAL, BUTTON_POSITIVE).filter { it in spec.actions }
                actionKeys.forEachIndexed { index, which ->
                    val (label, callback) = spec.actions.getValue(which)
                    val isLast = index == actionKeys.lastIndex
                    val button = context.button(label, which == BUTTON_POSITIVE) {
                        callback?.onClick(this@PaperPanel, which)
                        if (spec.dismissOnAction[which] != false) dismiss()
                    }.apply {
                        if (spec.itemsEnd && which != BUTTON_POSITIVE) {
                            textSize = PaperText.ITEM_TITLE
                            minWidth = 0; minimumWidth = 0
                            gravity = Gravity.END or Gravity.CENTER_VERTICAL
                            pad(12, 10)
                        }
                    }
                    buttons[which] = button
                    actions.addView(button, LinearLayout.LayoutParams(-2, -2).apply {
                        rightMargin = context.dp(if (spec.itemsEnd && isLast && which != BUTTON_POSITIVE) 0 else 8)
                    })
                }
                val actionStrip = HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    isFillViewport = true
                    addView(actions,FrameLayout.LayoutParams(-2,-2))
                }
                addView(actionStrip, LinearLayout.LayoutParams(0, -2, 1f))
            }
            if (spec.full) addView(context.pageControls { body })
        }, LinearLayout.LayoutParams(-1, context.dp(Paper.BOTTOM_BAR_HEIGHT)))
        setContentView(root)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.WHITE))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes=attributes.apply{dimAmount=0f;windowAnimations=0}
            decorView.elevation=0f
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            statusBarColor=Color.WHITE
            WindowInsetsControllerCompat(this,decorView).isAppearanceLightStatusBars=true
        }
        ViewCompat.setOnApplyWindowInsetsListener(root){view,insets->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left,if(spec.full)bars.top else 0,bars.right,bars.bottom);insets
        }
    }
    override fun onStart(){
        super.onStart()
        window?.setGravity(Gravity.BOTTOM)
        window?.setLayout(-1,if(spec.full)-1 else WindowManager.LayoutParams.WRAP_CONTENT)
    }
    override fun setTitle(title:CharSequence?){super.setTitle(title);if(::heading.isInitialized)heading.text=title}
    fun getButton(which:Int):Button=checkNotNull(buttons[which])

    class Builder(private val context:Context) {
        internal var title=""
        internal var message=""
        internal var view:View?=null
        internal var headerAction:View?=null
        internal var items:Array<out String>?=null
        internal var checked=-1
        internal var full=false
        internal var headerRule=true
        internal var itemsEnd=false
        internal var selection:DialogInterface.OnClickListener?=null
        internal val actions=linkedMapOf<Int,Pair<String,DialogInterface.OnClickListener?>>()
        internal val dismissOnAction=mutableMapOf<Int,Boolean>()
        private var cancel:DialogInterface.OnCancelListener?=null
        fun setTitle(value:String)=apply{title=value}
        fun setMessage(value:String)=apply{message=value}
        fun setView(value:View)=apply{view=value}
        fun setHeaderAction(value:View)=apply{headerAction=value}
        fun hideHeaderRule()=apply{headerRule=false}
        fun alignItemsEnd()=apply{itemsEnd=true}
        fun fullScreen()=apply{full=true}
        fun setItems(values:Array<out String>,listener:DialogInterface.OnClickListener)=apply{items=values;selection=listener}
        fun setSingleChoiceItems(values:Array<out String>,current:Int,listener:DialogInterface.OnClickListener)=apply{items=values;checked=current;selection=listener}
        fun setPositiveButton(value:String,listener:DialogInterface.OnClickListener?)=apply{actions[BUTTON_POSITIVE]=value to listener}
        fun setNegativeButton(value:String,listener:DialogInterface.OnClickListener?)=apply{actions[BUTTON_NEGATIVE]=value to listener}
        fun setNeutralButton(value:String,dismiss:Boolean=true,listener:DialogInterface.OnClickListener?)=apply{actions[BUTTON_NEUTRAL]=value to listener;dismissOnAction[BUTTON_NEUTRAL]=dismiss}
        fun setOnCancelListener(listener:DialogInterface.OnCancelListener)=apply{cancel=listener}
        fun create()=PaperPanel(context,this).also{it.setOnCancelListener(cancel)}
        fun show()=create().also{it.show()}
    }
    companion object {
        const val BUTTON_POSITIVE=DialogInterface.BUTTON_POSITIVE
        const val BUTTON_NEGATIVE=DialogInterface.BUTTON_NEGATIVE
        const val BUTTON_NEUTRAL=DialogInterface.BUTTON_NEUTRAL
    }
}

private fun pageScrollTarget(view:View?):View? {
    if(view==null || view.visibility!=View.VISIBLE)return null
    return when(view) {
        is RecyclerView,is ScrollView,is ListView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { pageScrollTarget(view.getChildAt(it)) }
        else -> null
    }
}
fun pageScroll(view:View,down:Boolean){
    val target=pageScrollTarget(view) ?: return
    val direction=if(down)1 else -1
    if(!target.canScrollVertically(direction))return
    val distance=((target.height-target.paddingTop-target.paddingBottom)*.9f).toInt().coerceAtLeast(1)*direction
    when(target) {
        is ListView -> target.scrollListBy(distance)
        else -> target.scrollBy(0,distance)
    }
}
/** Draw the frame, divider and arrows together with shared bounds and availability state. */
private class PageControlDrawable(private val density:Float):Drawable() {
    private val borderWidth=1.25f*density
    private val arrowWidth=2f*density
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin=Paint.Join.ROUND }
    private val frame=RectF()
    private val arrow=Path()
    private val disabledInk=Color.rgb(170,170,170)
    private var upAvailable=false
    private var downAvailable=false
    fun setAvailability(up:Boolean,down:Boolean) {
        if(upAvailable==up && downAvailable==down)return
        upAvailable=up;downAvailable=down;invalidateSelf()
    }
    override fun draw(canvas:Canvas) {
        if(bounds.width()<=0 || bounds.height()<=0)return
        paint.strokeWidth=borderWidth
        val inset=borderWidth/2f
        frame.set(bounds.left+inset,bounds.top+inset,bounds.right-inset,bounds.bottom-inset)
        val radius=frame.height()/2f
        paint.style=Paint.Style.FILL;paint.color=Color.WHITE
        canvas.drawRoundRect(frame,radius,radius,paint)
        paint.style=Paint.Style.STROKE;paint.strokeCap=Paint.Cap.BUTT
        for(down in listOf(false,true)) {
            paint.color=if(if(down)downAvailable else upAvailable)Paper.INK else disabledInk
            canvas.save()
            canvas.clipRect(if(down)frame.centerX() else bounds.left.toFloat(),bounds.top.toFloat(),
                if(down)bounds.right.toFloat() else frame.centerX(),bounds.bottom.toFloat())
            canvas.drawRoundRect(frame,radius,radius,paint)
            canvas.restore()
        }
        // With no available direction the entire capsule is gray, including its divider.
        paint.color=if(upAvailable || downAvailable)Paper.INK else disabledInk
        canvas.drawLine(frame.centerX(),frame.top,frame.centerX(),frame.bottom,paint)
        paint.strokeCap=Paint.Cap.ROUND;paint.strokeWidth=arrowWidth
        for(down in listOf(false,true)) {
            paint.color=if(if(down)downAvailable else upAvailable)Paper.INK else disabledInk
            val x=bounds.left+bounds.width()*(if(down).75f else .25f)
            val y=frame.centerY()
            val direction=if(down)-1f else 1f
            val halfWidth=9f*density;val halfHeight=4.5f*density
            arrow.reset();arrow.moveTo(x-halfWidth,y+direction*halfHeight)
            arrow.lineTo(x,y-direction*halfHeight);arrow.lineTo(x+halfWidth,y+direction*halfHeight)
            canvas.drawPath(arrow,paint)
        }
    }
    override fun setAlpha(alpha:Int) { paint.alpha=alpha;invalidateSelf() }
    override fun setColorFilter(filter:ColorFilter?) { paint.colorFilter=filter;invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
fun Context.pageControls(scrollTarget:()->View?):View=row().apply {
    layoutParams=LinearLayout.LayoutParams(dp(Paper.PAGE_CONTROLS_WIDTH),dp(Paper.PAGE_CONTROLS_HEIGHT))
    val frame=PageControlDrawable(resources.displayMetrics.density)
    background=frame
    val up=iconButton(Glyph.UP,"向上翻屏") { scrollTarget()?.let { pageScroll(it,false) } }
    val down=iconButton(Glyph.DOWN,"向下翻屏") { scrollTarget()?.let { pageScroll(it,true) } }
    listOf(up,down).forEach { button ->
        button.setImageDrawable(null);button.background=null;button.setPadding(0,0,0,0)
        button.minimumWidth=0;button.minimumHeight=0;button.isEnabled=false
        addView(button,LinearLayout.LayoutParams(0,-1,1f))
    }
    fun updateAvailability() {
        val target=pageScrollTarget(scrollTarget())
        val canUp=target?.canScrollVertically(-1)==true
        val canDown=target?.canScrollVertically(1)==true
        if(up.isEnabled!=canUp)up.isEnabled=canUp
        if(down.isEnabled!=canDown)down.isEnabled=canDown
        frame.setAvailability(canUp,canDown)
    }
    // Update on actual scroll/layout changes only; remove observers when this footer leaves the window.
    val layout=ViewTreeObserver.OnGlobalLayoutListener { updateAvailability() }
    val scroll=ViewTreeObserver.OnScrollChangedListener { updateAvailability() }
    var observer:ViewTreeObserver?=null
    addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view:View) {
            observer=view.viewTreeObserver.also {
                it.addOnGlobalLayoutListener(layout);it.addOnScrollChangedListener(scroll)
            }
            updateAvailability()
        }
        override fun onViewDetachedFromWindow(view:View) {
            observer?.takeIf { it.isAlive }?.let {
                it.removeOnGlobalLayoutListener(layout);it.removeOnScrollChangedListener(scroll)
            }
            observer=null
        }
    })
}
