package top.steeefanie.leafreader.reader

import android.content.Context
import java.io.File
import org.readium.adapter.pdfium.document.PdfiumDocumentFactory
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser


class ReadingEngine(context: Context, private val fonts: top.steeefanie.leafreader.reader.fonts.SystemFontCatalog) {
    private val http = DefaultHttpClient()
    private val assets = AssetRetriever(context.contentResolver, http)
    private val opener = PublicationOpener(DefaultPublicationParser(context, httpClient = http, assetRetriever = assets, pdfFactory = PdfiumDocumentFactory(context)))
    suspend fun open(file: File): Publication {
        fonts.families()
        val asset = assets.retrieve(file.toUrl()).getOrNull() ?: error("无法识别文件，仅支持 EPUB 和 PDF")
        val publication = opener.open(asset, allowUserInteraction = false, onCreatePublication = {
            // The native Canvas engine reads chapter XHTML directly; no WebView script injection needed.
            fonts.attach(this)
        }).getOrNull() ?: run {
            asset.close(); error("无法打开文件，可能已损坏或受到加密保护")
        }
        if (publication.isRestricted || !(publication.conformsTo(Publication.Profile.EPUB) || publication.conformsTo(Publication.Profile.PDF))) {
            publication.close(); error("仅支持无 DRM 的 EPUB 和 PDF")
        }
        return publication
    }
    suspend fun validate(file: File): Pair<String, String> {
        val publication = open(file)
        try {
            return (if (publication.conformsTo(Publication.Profile.PDF)) "pdf" else "epub") to publication.metadata.title.orEmpty()
        } finally { publication.close() }
    }

}
