-keep class com.shockwave.pdfium.** { *; }
-keep class com.tom_roush.** { *; }
# PDFBox is used only for text extraction; optional JPEG2000 image codecs are not bundled.
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.gemalto.jp2.JP2Encoder
