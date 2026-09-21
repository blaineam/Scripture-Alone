package com.blainemiller.scripturealone.ui.reader

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.MutablePreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blainemiller.scripturealone.data.BundledDatabase
import com.blainemiller.scripturealone.data.BundledTranslations
import com.blainemiller.scripturealone.data.Canon
import com.blainemiller.scripturealone.data.Chapter
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.prefs.ReaderKeys
import com.blainemiller.scripturealone.data.prefs.ReaderPrefs
import com.blainemiller.scripturealone.data.prefs.ReaderSettings
import com.blainemiller.scripturealone.data.prefs.Recents
import com.blainemiller.scripturealone.data.prefs.readerDataStore
import com.blainemiller.scripturealone.data.reference.Passage
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.blainemiller.scripturealone.data.search.SearchHit
import com.blainemiller.scripturealone.data.search.VerseSearch
import com.blainemiller.scripturealone.data.sql.BundledSqlSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Where the reader is and what it shows — `ReaderModel.swift`, as far as this stage goes. Reading
 * position, translation, appearance and the two recents lists persist through [ReaderPrefs] under the
 * iOS key names, and are restored before the first frame.
 */
class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = ReaderPrefs(application.readerDataStore)
    private val saved: ReaderSettings = prefs.load()

    /** Where the reader left off; John 1 on a first launch, as on iOS. */
    var location by mutableStateOf(saved.position?.let { ChapterRef(it.book, it.chapter) } ?: ChapterRef(43, 1))
        private set
    var translationId by mutableStateOf(saved.translation?.takeIf { it in BundledTranslations.ids } ?: BundledTranslations.DEFAULT)
        private set

    /** The loaded chapter. Only ever the one for [location] and [translationId] — see [load]. */
    var chapter by mutableStateOf<Chapter?>(null)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set

    /**
     * One-shot: the verse key the reader should bring to the top, then clear with [scrolledToTarget].
     * Set by a launch that restores a position past verse 1, a Go To with a verse, and a translation
     * switch — iOS's `scrollTarget`.
     */
    var scrollTarget by mutableStateOf(saved.position?.takeIf { it.verse > 1 }?.key)
        private set

    /** The verse currently at the top of the page, as last reported by the reader. */
    private var topVerse: Int? = null

    /** Chapters left behind, most recent first — at most 12. */
    var recent by mutableStateOf(saved.recent.take(Recents.LIMIT))
        private set
    /** Searches whose result was opened, most recent first — at most 12. */
    var recentSearches by mutableStateOf(saved.recentSearches.take(Recents.LIMIT))
        private set

    // Appearance. Defaults are the iOS `@AppStorage` defaults in `ReaderView.swift`.
    var theme by persisted(ReaderTheme.fromRaw(saved.theme) ?: ReaderTheme.SYSTEM) { p, v -> p[ReaderKeys.THEME] = v.raw }
    var accent by persisted(ReaderAccent.fromRaw(saved.accent) ?: ReaderAccent.SUNRISE) { p, v -> p[ReaderKeys.ACCENT] = v.raw }
    var layout by persisted(ReadingLayout.fromRaw(saved.layout) ?: ReadingLayout.PARAGRAPHS) { p, v -> p[ReaderKeys.LAYOUT] = v.raw }
    var fontSize by persisted(
        saved.fontSize?.toFloat()?.coerceIn(ReaderStyle.SIZE_RANGE) ?: ReaderStyle.DEFAULT_SIZE,
    ) { p, v -> p[ReaderKeys.FONT_SIZE] = v.toDouble() }
    var lineSpacing by persisted(
        saved.lineSpacing?.toFloat()?.coerceIn(ReaderStyle.LINE_SPACING_RANGE) ?: ReaderStyle.DEFAULT_LINE_SPACING,
    ) { p, v -> p[ReaderKeys.LINE_SPACING] = v.toDouble() }
    var redLetters by persisted(saved.redLetters ?: true) { p, v -> p[ReaderKeys.RED_LETTERS] = v }
    var verseNumbers by persisted(saved.verseNumbers ?: true) { p, v -> p[ReaderKeys.VERSE_NUMBERS] = v }
    var headings by persisted(saved.headings ?: true) { p, v -> p[ReaderKeys.HEADINGS] = v }
    var footnotes by persisted(saved.footnotes ?: true) { p, v -> p[ReaderKeys.FOOTNOTES] = v }

    fun style(palette: ReaderPalette) = ReaderStyle(
        size = fontSize, lineSpacing = lineSpacing, layout = layout,
        redLetters = redLetters, verseNumbers = verseNumbers, headings = headings, footnotes = footnotes,
        palette = palette,
    )

    private var loading: Job? = null

    init {
        load()
    }

    // Navigation — `show`, `go(to:)`, `next`, `previous` in ReaderModel.swift.

    /**
     * Opens [ref], scrolling to [verse] when it is past the first. Leaving a chapter files it under
     * Recent — the chapter left, not the one arrived at, as on iOS — and the position is saved at
     * once, before any scrolling reports a finer one.
     */
    fun show(ref: ChapterRef, verse: Int? = null) {
        if (ref != location) rememberChapter(location)
        location = ref
        topVerse = null
        scrollTarget = verse?.takeIf { it > 1 }?.let { VerseRef(ref.book, ref.chapter, it).key }
        load()
        savePosition(VerseRef(ref.book, ref.chapter, verse ?: 1))
    }

    /** A parsed passage, its chapters clamped to the book — the verse is clamped by the reader. */
    fun go(passage: Passage) {
        val p = passage.clamped
        show(ChapterRef(p.book.number, p.startChapter), p.startVerse)
    }

    fun go(verse: VerseRef) = show(ChapterRef(verse.book, verse.chapter), verse.verse)

    /** The development hook in MainActivity: a chapter and, optionally, a translation. */
    fun open(ref: ChapterRef, translation: String = translationId) {
        if (translation != translationId) selectTranslation(translation)
        show(ref)
    }

    fun next() = Canon.next(location)?.let { show(it) }
    fun previous() = Canon.previous(location)?.let { show(it) }

    /** Switches translation and keeps the reader's place: the verse at the top stays at the top. */
    fun selectTranslation(id: String) {
        if (id == translationId || id !in BundledTranslations.ids) return
        translationId = id
        prefs.write { it[ReaderKeys.TRANSLATION] = id }
        topVerse?.takeIf { VerseRef.fromKey(it).verse > 1 }?.let { scrollTarget = it }
        load()
    }

    fun scrolledToTarget() {
        scrollTarget = null
    }

    /** Called as the reader scrolls: the verse now at the top, saved as the reading position. */
    fun updateTopVerse(key: Int) {
        if (key == topVerse) return
        topVerse = key
        savePosition(VerseRef.fromKey(key))
    }

    private fun savePosition(ref: VerseRef) = prefs.write { it[ReaderKeys.POSITION] = ref.key }

    private fun rememberChapter(ref: ChapterRef) {
        recent = Recents.chapters(recent, ref)
        val encoded = ReaderSettings.encodeRecent(recent)
        prefs.write { it[ReaderKeys.RECENT] = encoded }
    }

    // Search

    /**
     * The sealed ASV carries its own encrypted search index, which isn't ported yet; the plain
     * stores carry FTS5. The Go To sheet says so for the ASV rather than quietly searching another
     * translation and presenting its words.
     */
    val isSearchable: Boolean get() = translationId !in SEALED

    /** Full-text search of the current translation, off the main thread. Empty when not searchable. */
    suspend fun search(query: String): List<SearchHit> {
        val id = translationId
        if (id in SEALED) return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                BundledDatabase.withConnection(getApplication(), "$id.sqlite") { db ->
                    VerseSearch(BundledSqlSource(db)).search(query)
                }
            }.getOrDefault(emptyList())
        }
    }

    /** Kept when a search is acted on — a result opened — so abandoned phrases don't fill the list. */
    fun rememberSearch(query: String) {
        recentSearches = Recents.searches(recentSearches, query)
        saveSearches()
    }

    fun forgetSearch(query: String) {
        recentSearches = recentSearches.filter { it != query }
        saveSearches()
    }

    fun clearRecentSearches() {
        recentSearches = emptyList()
        saveSearches()
    }

    private fun saveSearches() {
        val encoded = ReaderSettings.encodeSearches(recentSearches)
        prefs.write { it[ReaderKeys.RECENT_SEARCHES] = encoded }
    }

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

    /**
     * A Compose-observable setting that writes itself through to DataStore on every change — the
     * shape of an `@AppStorage` property.
     */
    private fun <T> persisted(initial: T, write: (MutablePreferences, T) -> Unit): ReadWriteProperty<Any?, T> =
        object : ReadWriteProperty<Any?, T> {
            var state by mutableStateOf(initial)
            override fun getValue(thisRef: Any?, property: KProperty<*>): T = state
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
                if (value == state) return
                state = value
                prefs.write { write(it, value) }
            }
        }

    private companion object {
        val SEALED = setOf("ASV")
    }
}
