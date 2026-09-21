package com.blainemiller.scripturealone.ui.reader

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blainemiller.scripturealone.data.BundledTranslations
import com.blainemiller.scripturealone.data.Canon
import com.blainemiller.scripturealone.data.Chapter
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where the reader is and what it shows. Reading position and appearance are held in memory only
 * for now; persisting them (DataStore, the iOS `reader.*` keys) is a separate ledger item.
 */
class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    /** John 1 on a first launch, as on iOS. */
    var location by mutableStateOf(ChapterRef(43, 1))
        private set
    var translationId by mutableStateOf(BundledTranslations.DEFAULT)
        private set

    /** The loaded chapter. Only ever the one for [location] and [translationId] — see [load]. */
    var chapter by mutableStateOf<Chapter?>(null)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set

    // Dark by default for this build of the reader; iOS defaults to Auto.
    var theme by mutableStateOf(ReaderTheme.DARK)
    var accent by mutableStateOf(ReaderAccent.SUNRISE)
    var layout by mutableStateOf(ReadingLayout.PARAGRAPHS)

    private var loading: Job? = null

    init {
        load()
    }

    fun open(ref: ChapterRef, translation: String = translationId) {
        location = ref
        translationId = translation
        load()
    }

    fun next() = Canon.next(location)?.let { open(it) }
    fun previous() = Canon.previous(location)?.let { open(it) }
    fun selectTranslation(id: String) = open(location, id)

    /**
     * Cancels any load in flight before starting the next, and publishes a result only if it is
     * still for the current location and translation: a slow decrypt of the chapter the reader just
     * paged past must never land under the new chapter's title.
     */
    private fun load() {
        val ref = location
        val id = translationId
        loading?.cancel()
        loading = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { BundledTranslations.source(getApplication(), id).chapter(ref) }
            }
            if (ref != location || id != translationId) return@launch
            result.onSuccess {
                chapter = it
                loadError = null
            }.onFailure {
                chapter = null
                loadError = it.message ?: it.javaClass.simpleName
            }
        }
    }
}
