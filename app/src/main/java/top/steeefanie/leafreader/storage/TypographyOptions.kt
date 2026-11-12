package top.steeefanie.leafreader.storage

/** Discrete typography stops for native EPUB layout. */
object TypographyOptions {
    val sizes = listOf(16.0,17.0,18.0,19.0,20.0,22.0,24.0,27.0,30.0,33.0,36.0)
    val lines = listOf(1.2,1.4,1.6,1.8,2.0,2.2,2.4)
    val paragraphs = listOf(0.0,.125,.25,.5,.75,1.0,1.25)
    val margins = listOf(.25,.5,.75,1.0,1.25,1.5,1.75)
    fun nearest(values:List<Double>,value:Double,default:Double):Double =
        if(!value.isFinite())default else values.minByOrNull{kotlin.math.abs(it-value)} ?: default
}
