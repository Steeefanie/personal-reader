package top.steeefanie.leafreader.ui

import android.graphics.*
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser

private data class NavigationShape(val outline:String,val detail:String,val cutout:String,val mirroredBook:Boolean=false)
private val navigationShapes=mapOf(
    Glyph.BOOK to NavigationShape("M7.8,4.5 L30,4.5 C26.5,7.3 26.5,11.2 30,14 L7.8,14 C3.9,14 1.6,12 1.6,9.25 C1.6,6.5 3.9,4.5 7.8,4.5 Z","","",true),
    Glyph.CLOUD to NavigationShape("M8.3,25 C4.6,25 2.4,22.8 2.4,19.7 C2.4,16.8 4.4,14.5 7.2,14.1 C7.4,8.9 10.7,5.8 15.3,5.8 C19.4,5.8 22.5,8.4 23.3,12.3 C27.2,12.4 29.7,14.8 29.7,18.6 C29.7,22.4 27.1,25 23.5,25 Z","",""),
    Glyph.SETTINGS to NavigationShape("M12.935,5.580 L13.541,3.431 Q13.690,2.902 14.240,2.902 L17.760,2.902 Q18.310,2.902 18.459,3.431 L19.065,5.580 Q19.214,6.109 19.666,6.296 L20.269,6.546 Q20.722,6.734 21.201,6.465 L23.149,5.374 Q23.629,5.105 24.017,5.494 L26.506,7.983 Q26.895,8.371 26.626,8.851 L25.535,10.799 Q25.266,11.278 25.454,11.731 L25.704,12.334 Q25.891,12.786 26.420,12.935 L28.569,13.541 Q29.098,13.690 29.098,14.240 L29.098,17.760 Q29.098,18.310 28.569,18.459 L26.420,19.065 Q25.891,19.214 25.704,19.666 L25.454,20.269 Q25.266,20.722 25.535,21.201 L26.626,23.149 Q26.895,23.629 26.506,24.017 L24.017,26.506 Q23.629,26.895 23.149,26.626 L21.201,25.535 Q20.722,25.266 20.269,25.454 L19.666,25.704 Q19.214,25.891 19.065,26.420 L18.459,28.569 Q18.310,29.098 17.760,29.098 L14.240,29.098 Q13.690,29.098 13.541,28.569 L12.935,26.420 Q12.786,25.891 12.334,25.704 L11.731,25.454 Q11.278,25.266 10.799,25.535 L8.851,26.626 Q8.371,26.895 7.983,26.506 L5.494,24.017 Q5.105,23.629 5.374,23.149 L6.465,21.201 Q6.734,20.722 6.546,20.269 L6.296,19.666 Q6.109,19.214 5.580,19.065 L3.431,18.459 Q2.902,18.310 2.902,17.760 L2.902,14.240 Q2.902,13.690 3.431,13.541 L5.580,12.935 Q6.109,12.786 6.296,12.334 L6.546,11.731 Q6.734,11.278 6.465,10.799 L5.374,8.851 Q5.105,8.371 5.494,7.983 L7.983,5.494 Q8.371,5.105 8.851,5.374 L10.799,6.465 Q11.278,6.734 11.731,6.546 L12.334,6.296 Q12.786,6.109 12.935,5.580 Z","","M21.2,16 A5.2,5.2 0 1 1 10.8,16 A5.2,5.2 0 1 1 21.2,16 Z")
)

/** Selection changes the glyph itself: line engraving versus a solid silhouette with white cuts. */
class NavigationIcon(glyph:Glyph,private val selected:Boolean):Drawable() {
    private val geometry=checkNotNull(navigationShapes[glyph])
    private val outline=checkNotNull(PathParser.createPathFromPathData(geometry.outline)).also { upper ->
        if(geometry.mirroredBook) {
            val lower=Path()
            upper.transform(Matrix().apply { setRotate(180f,16f,16f) },lower)
            upper.addPath(lower)
        }
        val bounds=RectF().also { upper.computeBounds(it,true) }
        val dy=16f-bounds.centerY()
        if(kotlin.math.abs(dy)>0.05f) upper.offset(0f,dy)
    }
    // Stroke inside the silhouette: pointed ends stay within the straight top/bottom edges.
    private val innerOutline=if(geometry.mirroredBook)Path().also { ring ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style=Paint.Style.STROKE;strokeWidth=3.5f;strokeJoin=Paint.Join.ROUND
        }.getFillPath(outline,ring)
        ring.op(outline,Path.Op.INTERSECT)
    } else null
    private val detail=geometry.detail.takeIf { it.isNotEmpty() }?.let { PathParser.createPathFromPathData(it) }
    private val cutout=geometry.cutout.takeIf { it.isNotEmpty() }?.let { PathParser.createPathFromPathData(it) }
    private var iconAlpha=255
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin=Paint.Join.ROUND;strokeCap=Paint.Cap.ROUND }
    private fun ink(color:Int,style:Paint.Style,width:Float) {
        paint.color=color;paint.alpha=iconAlpha;paint.style=style;paint.strokeWidth=width
    }
    override fun draw(canvas:Canvas) {
        canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat())
        canvas.scale(bounds.width()/32f,bounds.height()/32f)
        if(geometry.mirroredBook) {
            ink(Paper.INK,Paint.Style.FILL,1.75f)
            canvas.drawPath(if(selected)outline else checkNotNull(innerOutline),paint)
        } else {
            ink(Paper.INK,if(selected)Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE,1.75f)
            canvas.drawPath(outline,paint)
        }
        detail?.let {
            ink(if(selected)Color.WHITE else Paper.INK,Paint.Style.STROKE,1.45f)
            canvas.drawPath(it,paint)
        }
        cutout?.let {
            ink(if(selected)Color.WHITE else Paper.INK,if(selected)Paint.Style.FILL else Paint.Style.STROKE,1.75f)
            canvas.drawPath(it,paint)
        }
        canvas.restore()
    }
    override fun getIntrinsicWidth()=32
    override fun getIntrinsicHeight()=32
    override fun setAlpha(alpha:Int) { iconAlpha=alpha;invalidateSelf() }
    override fun setColorFilter(filter:ColorFilter?) { paint.colorFilter=filter;invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
