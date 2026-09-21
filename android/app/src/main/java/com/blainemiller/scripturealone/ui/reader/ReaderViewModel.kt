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
import com.blainemiller.scripturealone.data.ChapterVerse
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.listen.AutoScroll
import com.blainemiller.scripturealone.data.rights.TranslationRights
import com.blainemiller.scripturealone.data.share.AppLink
import com.blainemiller.scripturealone.data.share.SharePassageText
import com.blainemiller.scripturealone.data.share.ShareLinkPayload
import com.blainemiller.scripturealone.data.share.ShareVerse
import com.blainemiller.scripturealone.data.userdata.BundledUserDatabase
import com.blainemiller.scripturealone.data.userdata.HighlightColor
import com.blainemiller.scripturealone.data.userdata.Note
import com.blainemiller.scripturealone.data.userdata.Selection
import com.blainemiller.scripturealone.data.userdata.UserData
import com.blainemiller.scripturealone.data.userdata.UserDataStore
import java.io.File
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

    /** The verse currently at the top of the page, as last reported by the reader — where Listen starts. */
    var topVerse: Int? = null
        private set

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
    /** Auto-scroll's speed in points per second; 28 ("Relaxed") by default, as on iOS. */
    var autoScrollSpeed by persisted(AutoScroll.sanitize(saved.autoScrollSpeed)) { p, v -> p[ReaderKeys.AUTO_SCROLL_SPEED] = v }

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
        if (ref != location) {
            // A selection belongs to the chapter it was made in, as on iOS.
            selection = emptySet()
            rememberChapter(location)
        }
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
                it.verses.maxOfOrNull { v -> v.ref.verse }?.let { count -> verseCounts[ref] = count }
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

    // Selection — `ReaderModel.selection`, `toggle`, `selectedRanges`, `quotation(for:)`.

    /** Selected verse keys. Not saved: a selection lasts as long as the reader is looking at it. */
    var selection by mutableStateOf<Set<Int>>(emptySet())

    /** A tap: selects the verse, or deselects it. */
    fun toggle(key: Int) {
        selection = if (key in selection) selection - key else selection + key
    }

    /** A long press: selects everything from the nearest selected verse to this one. */
    fun extendSelection(key: Int) {
        selection = Selection.extend(selection, key)
    }

    fun clearSelection() {
        selection = emptySet()
    }

    /** Verse counts of chapters seen so far, for joining a selection across a chapter break. */
    private val verseCounts = java.util.concurrent.ConcurrentHashMap<ChapterRef, Int>()

    fun verseCount(ref: ChapterRef): Int = verseCounts[ref] ?: 0

    val selectedRanges: List<VerseRange> get() = Selection.ranges(selection) { verseCount(it) }

    /** What the translation being read permits; public domain until its chapter has loaded. */
    val rights: TranslationRights get() = chapter?.translation?.rights ?: TranslationRights.PUBLIC_DOMAIN

    val translationAbbreviation: String get() = chapter?.translation?.abbreviation ?: translationId

    /**
     * Whether the selection may leave the device at all under this translation's terms. Selected keys
     * are always verses the text has, so their number is the quotation's verse count.
     */
    fun mayQuote(): Boolean = rights.mayQuote(selection.size)

    /** The verses [ranges] cover, in the current translation, read chapter by chapter off the main thread. */
    suspend fun verses(ranges: List<VerseRange>): List<ChapterVerse> {
        val id = translationId
        val chapters = ranges.flatMap { range ->
            val first = ChapterRef(range.start.book, range.start.chapter)
            val last = ChapterRef(range.end.book, range.end.chapter)
            generateSequence(first) { if (it == last) null else Canon.next(it) }.take(200).toList()
        }.distinct()
        val loaded = chapter
        return withContext(Dispatchers.IO) {
            chapters.flatMap { ref ->
                val verses = if (loaded != null && loaded.ref == ref && loaded.translation.id == id) {
                    loaded.verses
                } else {
                    runCatching { BundledTranslations.source(getApplication(), id).chapter(ref).verses }.getOrDefault(emptyList())
                }
                verses.maxOfOrNull { it.ref.verse }?.let { verseCounts[ref] = it }
                verses.filter { v -> ranges.any { it.contains(v.ref.key) } }
            }
        }
    }

    /**
     * The quotation for the selection, or "" when the translation's terms don't allow it — the gate
     * lives where text becomes quotable, as on iOS.
     */
    suspend fun quotation(ranges: List<VerseRange> = selectedRanges): String {
        if (!rights.mayQuote(selection.size)) return ""
        return Selection.quotation(ranges, verses(ranges), translationAbbreviation)
    }

    /**
     * The share link for [ranges], or null when the passage is too long for one or the translation's
     * terms don't allow sharing. Carries no designer choices: the web card uses its defaults.
     */
    suspend fun shareLink(ranges: List<VerseRange> = selectedRanges): String? {
        if (!rights.permits(TranslationRights.Permission.SHARE) || !rights.mayQuote(selection.size)) return null
        val verses = verses(ranges).map { ShareVerse.fromScalars(it.ref, it.text, it.red.map { r -> r.start to r.length }) }
        if (verses.isEmpty()) return null
        val payload = ShareLinkPayload.of(
            ranges, ranges.joinToString(", ") { it.display }, translationAbbreviation, SharePassageText.of(verses),
        )
        return if (payload.fitsInLink) payload.webUrl() else null
    }

    /**
     * Goes to a passage a link named and selects it — `ShareSupport.reveal`. The chapters' verse
     * counts are read first, so a range crossing a chapter selects the right verses.
     */
    fun reveal(ranges: List<VerseRange>) {
        val first = ranges.firstOrNull() ?: return
        show(ChapterRef(first.start.book, first.start.chapter), first.start.verse)
        viewModelScope.launch {
            verses(ranges)
            selection = ranges.flatMap { Selection.keys(it) { ref -> verseCount(ref) } }.toSet()
        }
    }

    /** The verse count of [ref] in this translation, reading the chapter if it hasn't been seen. */
    suspend fun loadVerseCount(ref: ChapterRef): Int {
        verseCounts[ref]?.let { return it }
        val id = translationId
        return withContext(Dispatchers.IO) {
            val count = runCatching { BundledTranslations.source(getApplication(), id).chapter(ref).verses.maxOfOrNull { it.ref.verse } }
                .getOrNull() ?: 0
            if (count > 0) verseCounts[ref] = count
            count
        }
    }

    /** Typed passages ("Rom 8:1-17; Ps 23") as ranges, whole chapters resolved against real verse counts. */
    suspend fun resolvePassages(text: String): List<VerseRange> =
        com.blainemiller.scripturealone.data.reference.ReferenceParser.parseList(text).map { passage ->
            val clamped = passage.clamped
            val counts = HashMap<Int, Int>()
            for (chapter in clamped.startChapter..clamped.endChapter) {
                counts[chapter] = loadVerseCount(ChapterRef(clamped.book.number, chapter))
            }
            val (first, last) = clamped.range { _, chapter -> counts[chapter]?.takeIf { it > 0 } ?: 1 }
            VerseRange.of(VerseRef.fromKey(first), VerseRef.fromKey(last))
        }

    /** A passage a share link carried, shown as its card over the reader until dismissed. */
    var sharedPassage by mutableStateOf<ShareLinkPayload?>(null)
        private set

    /**
     * Opens a `scripturealone://open?ref=…` link or a share link (`…#s=…`, on the web page or the
     * custom scheme) — `ShareSupport.open`. Both go to the passage and select it; a share link also
     * shows the card it carries. Returns false for a URL that is neither.
     */
    fun openLink(url: String): Boolean {
        when (val link = AppLink.parse(url) ?: return false) {
            is AppLink.Open -> {
                sharedPassage = null
                reveal(link.ranges)
            }
            is AppLink.Share -> {
                reveal(link.payload.ranges)
                sharedPassage = link.payload
            }
        }
        return true
    }

    fun dismissSharedPassage() {
        sharedPassage = null
    }

    // The reader's own marks.

    val userData = UserData(viewModelScope) {
        UserDataStore(BundledUserDatabase(File(application.filesDir, "userdata.sqlite")))
    }

    fun highlightSelection(color: HighlightColor) {
        userData.highlight(selection, color)
        clearSelection()
    }

    fun removeSelectedHighlights() {
        userData.removeHighlights(selection)
        clearSelection()
    }

    fun toggleFavoriteSelection() = userData.toggleFavorite(selectedRanges)

    /** A note on the selection, opened at once — `createNoteFromSelection`. */
    fun newNoteFromSelection(): Note {
        val note = userData.newNote(selectedRanges)
        clearSelection()
        return note
    }

    /** "New Note" in the panel: on the selection, or the whole chapter with nothing selected. */
    fun newNote(): Note {
        if (selection.isNotEmpty()) return newNoteFromSelection()
        val count = verseCount(location).coerceAtLeast(1)
        return userData.newNote(listOf(VerseRange(VerseRef(location.book, location.chapter, 1), VerseRef(location.book, location.chapter, count))))
    }

    private companion object {
        val SEALED = setOf("ASV")
    }
}
