package top.steeefanie.leafreader.ui

import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.roundToInt

/** Discrete, equally spaced stops. Repaint on interaction only; no animated slider. */
class DiscreteSettingBar(
    context:Context,private val title:String,private var values:List<Double>,initial:Double,
    private val leftLabel:String,private val rightLabel:String,private val format:(Double)->String,
    private val changed:(Double)->Unit
):View(context) {
    var index:Int=values.indices.minByOrNull{kotlin.math.abs(values[it]-initial)} ?: 0
        private set
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val box=RectF()
    private val textSize=PaperText.DETAIL*resources.displayMetrics.scaledDensity
    init{isFocusable=true;isClickable=true;updateDescription();importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES}
    private fun updateDescription(){contentDescription="$title，${format(values[index])}，第 ${index+1} 档，共 ${values.size} 档"}
    fun step(delta:Int)=select(index+delta)
    fun setOptions(options:List<Double>,target:Double){
        require(options.isNotEmpty())
        values=options;index=values.indices.minByOrNull{kotlin.math.abs(values[it]-target)} ?: 0
        isEnabled=values.size>1;updateDescription();invalidate()
    }
    fun setValue(target:Double){
        val next=values.indices.minByOrNull{kotlin.math.abs(values[it]-target)} ?: 0
        if(index==next)return
        index=next;updateDescription();invalidate()
    }
    private fun select(position:Int){
        val next=position.coerceIn(0,values.lastIndex)
        if(index==next)return
        index=next;updateDescription();invalidate();changed(values[index])
        sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SELECTED)
    }
    private fun radius()=minOf(context.dp(20).toFloat(),height*.40f)
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        val radius=radius();val y=height*.5f;val available=(width-2*radius).coerceAtLeast(1f)
        val x=radius+available*index/values.lastIndex.coerceAtLeast(1)
        box.set(0f,y-radius,width.toFloat(),y+radius)
        paint.style=Paint.Style.FILL;paint.color=Color.LTGRAY;canvas.drawRoundRect(box,radius,radius,paint)
        canvas.save();canvas.clipRect(0f,0f,x,height.toFloat());paint.color=Color.BLACK
        canvas.drawRoundRect(box,radius,radius,paint);canvas.restore()
        paint.strokeWidth=context.dp(1).coerceAtLeast(1).toFloat()
        for(i in 1 until values.lastIndex){
            val tick=radius+available*i/values.lastIndex
            paint.color=if(i<=index)Color.LTGRAY else Color.WHITE
            canvas.drawLine(tick,y-radius+context.dp(2),tick,y+radius-context.dp(2),paint)
        }
        paint.typeface=Typeface.DEFAULT_BOLD;paint.textSize=minOf(textSize,radius*1.05f);paint.textAlign=Paint.Align.CENTER
        val baseline=y-(paint.fontMetrics.ascent+paint.fontMetrics.descent)*.5f
        if(index>0){paint.color=Color.WHITE;canvas.drawText(leftLabel,radius,baseline,paint)}
        if(index<values.lastIndex){paint.color=Color.BLACK;canvas.drawText(rightLabel,width-radius,baseline,paint)}
        paint.color=Color.WHITE;canvas.drawCircle(x,y,radius-context.dp(1),paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=context.dp(2).toFloat();paint.color=Color.BLACK
        canvas.drawCircle(x,y,radius-context.dp(1),paint)
        paint.style=Paint.Style.FILL;paint.textSize=textSize
        val textWidth=paint.measureText(format(values[index]))
        if(textWidth>radius*1.6f)paint.textSize*=radius*1.6f/textWidth
        canvas.drawText(format(values[index]),x,y-(paint.fontMetrics.ascent+paint.fontMetrics.descent)*.5f,paint)
    }
    override fun onTouchEvent(event:MotionEvent):Boolean{
        if(!isEnabled)return false
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->return true
            MotionEvent.ACTION_UP->{
                val available=(width-2*radius()).coerceAtLeast(1f)
                select(((event.x-radius())/available*values.lastIndex).roundToInt());performClick();return true
            }
            MotionEvent.ACTION_CANCEL->return true
        };return true
    }
    override fun performClick():Boolean{super.performClick();return true}
    override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean=when(keyCode){
        KeyEvent.KEYCODE_DPAD_LEFT->{step(-1);true}
        KeyEvent.KEYCODE_DPAD_RIGHT->{step(1);true}
        else->super.onKeyDown(keyCode,event)
    }
    override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo){
        super.onInitializeAccessibilityNodeInfo(info);info.className="android.widget.SeekBar"
        info.rangeInfo=AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,0f,values.lastIndex.toFloat(),index.toFloat())
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
        if(index>0)info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        if(index<values.lastIndex)info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
    }
    override fun performAccessibilityAction(action:Int,arguments:Bundle?):Boolean=when(action){
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD->{step(1);true}
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD->{step(-1);true}
        android.R.id.accessibilityActionSetProgress->{select(arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)?.roundToInt() ?: index);true}
        else->super.performAccessibilityAction(action,arguments)
    }
}
