package top.steeefanie.leafreader.reader

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.Job
import org.readium.r2.shared.publication.Publication
import top.steeefanie.leafreader.storage.*

class ReaderState : ViewModel() {
    var book: Book? = null
    var publication: Publication? = null
    var initialProgress: Progress? = null
    var preferences = ReadingSettings()
    var saveJob: Job? = null
    var lastSearch = ""
    var jumpOrigin:Progress?=null
    override fun onCleared() { publication?.close() }
}
