package top.steeefanie.leafreader.storage

/** Native EPUB uses nominal sizes; legacy settings are converted from Readium multipliers. */
object TypographyOptions {
    val sizes = listOf(16.0,17.0,18.0,19.0,20.0,22.0,24.0,27.0,30.0,33.0,36.0)
    val lines = listOf(1.2,1.4,1.6,1.8,2.0,2.2,2.4)
    val paragraphs = listOf(0.0,.125,.25,.5,.75,1.0,1.25)
    /** Preserve the selected stop while tightening the previous seven-stop scale once. */
    fun legacyParagraphSpacing(value:Double):Double {
        if(!value.isFinite())return .5
        val old=listOf(0.0,.25,.5,.75,1.0,1.25,1.5)
        val index=old.indices.minByOrNull { kotlin.math.abs(old[it]-value) } ?: 3
        return paragraphs[index]
    }
    val margins = listOf(.25,.5,.75,1.0,1.25,1.5,1.75)
    fun nearest(values:List<Double>,value:Double,default:Double):Double =
        if(!value.isFinite())default else values.minByOrNull{kotlin.math.abs(it-value)} ?: default
    fun legacySize(multiplier:Double)=if(kotlin.math.abs(multiplier-1.25)<.000001)22.0 else nearest(sizes,multiplier*16.0,22.0)
}
