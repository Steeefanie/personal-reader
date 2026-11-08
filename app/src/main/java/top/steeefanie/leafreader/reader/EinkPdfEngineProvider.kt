@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class, org.readium.r2.shared.InternalReadiumApi::class)

package top.steeefanie.leafreader.reader

import android.graphics.PointF
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.github.barteksc.pdfviewer.PDFView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.readium.adapter.pdfium.navigator.*
import org.readium.r2.navigator.pdf.*
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.Fit
import org.readium.r2.navigator.util.SingleFragmentFactory
import org.readium.r2.navigator.util.createFragmentFactory
import java.io.File

/**
 * Use Readium's PDFium settings and document parser, with a strictly zero-based navigator.
 * In Readium 3.1.2, the stock document fragment reports a one-based page to a zero-based
 * navigator. Keeping this boundary explicit prevents both skipped pages and restore drift.
 */
class EinkPdfEngineProvider(
    private val file: File,
    private val loaded: (PDFView) -> Unit,
    private val failed: (Throwable) -> Unit,
) : PdfEngineProvider<PdfiumSettings, PdfiumPreferences, PdfiumPreferencesEditor> by PdfiumEngineProvider() {
    override fun createDocumentFragmentFactory(input: PdfDocumentFragmentInput<PdfiumSettings>): SingleFragmentFactory<*> =
        createFragmentFactory { EinkPdfDocumentFragment(file, input, loaded, failed) }
}

class EinkPdfDocumentFragment(
    private val file: File,
    private val input: PdfDocumentFragmentInput<PdfiumSettings>,
    private val loaded: (PDFView) -> Unit,
    private val failed: (Throwable) -> Unit,
) : PdfDocumentFragment<PdfiumSettings>() {
    private var pdf: PDFView? = null
    private val current = MutableStateFlow(input.pageIndex)
    override val pageIndex: StateFlow<Int> = current.asStateFlow()
    private var settings = input.settings

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        PDFView(inflater.context, null).also { pdf = it }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        load(current.value)
    }
    private fun load(index: Int) {
        val view = pdf ?: return
        view.recycle()
        view.fromFile(file).defaultPage(index.coerceAtLeast(0))
            .enableSwipe(false).swipeHorizontal(true).pageSnap(false).pageFling(false)
            .autoSpacing(false).spacing(0).fitEachPage(true).enableDoubletap(true)
            .onLoad {
                if (settings.fit == Fit.WIDTH) view.fitToWidth(index)
                loaded(view)
            }
            .onPageChange { page, _ -> current.value = page }
            .onTap { event -> input.inputListener?.onTap(TapEvent(PointF(event.x, event.y))) ?: false }
            .onError(failed)
            .load()
    }
    override fun goToPageIndex(index: Int, animated: Boolean): Boolean {
        val view = pdf ?: return false
        if (index !in 0 until view.pageCount) return false
        view.jumpTo(index, false)
        return true
    }
    override fun applySettings(settings: PdfiumSettings) {
        if (this.settings == settings) return
        this.settings = settings
        load(current.value)
    }
    override fun onDestroyView() {
        pdf?.recycle(); pdf = null
        super.onDestroyView()
    }
}
