package com.blainemiller.scripturealone.ui.reader

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.MutablePreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blainemiller.scripturealone.data.assets.AssetLibrary
import com.blainemiller.scripturealone.data.assets.AssetPack
import com.blainemiller.scripturealone.data.BundledTranslations
import com.blainemiller.scripturealone.data.Canon
import com.blainemiller.scripturealone.data.Chapter
import com.blainemiller.scripturealone.data.ChapterVerse
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.listen.AutoScroll
import com.blainemiller.scripturealone.data.rights.TranslationRights
import com.blainemiller.scripturealone.data.share.AppLink
import com.blainemiller.scripturealone.data.share.ShareLinkPayload
import com.blainemiller.scripturealone.data.share.ShareVerse
import com.blainemiller.scripturealone.data.userdata.BundledUserDatabase
import com.blainemiller.scripturealone.data.userdata.HighlightColor
import com.blainemiller.scripturealone.data.userdata.Note
import com.blainemiller.scripturealone.data.userdata.Selection
import com.blainemiller.scripturealone.data.userdata.UserData
import com.blainemiller.scripturealone.data.userdata.UserDataStore
import java.io.File
import com.blainemiller.scripturealone.ui.share.ShareAlignment
import com.blainemiller.scripturealone.ui.share.ShareAspect
import com.blainemiller.scripturealone.ui.share.ShareSource
import com.blainemiller.scripturealone.ui.share.ShareStyle
import com.blainemiller.scripturealone.ui.share.ShareTemplate
import com.blainemiller.scripturealone.data.VerseNumbering
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookNames
import com.blainemiller.scripturealone.data.prefs.ReaderKeys
import com.blainemiller.scripturealone.data.prefs.ReaderPrefs
import com.blainemiller.scripturealone.data.prefs.ReaderSettings
import com.blainemiller.scripturealone.data.prefs.Recents
import com.blainemiller.scripturealone.data.prefs.readerDataStore
import com.blainemiller.scripturealone.data.reference.Passage
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.blainemiller.scripturealone.data.search.SearchHit
import com.blainemiller.scripturealone.data.search.VerseSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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

    /**
     * Where the reader left off; John 1 on a first launch, as on iOS. The saved position is a KJV key;
     * the first [load] moves this to the chapter the translation calls it.
     */
    var location by mutableStateOf(saved.position?.let { ChapterRef(it.book, it.chapter) } ?: ChapterRef(43, 1))
        private set

    /**
     * First launch: the Bible in the reader's own language, when the app has one (Simplified Chinese
     * only for the 和合本; none for English) — `AssetPack.bible(forPreferredLanguages:)`. It is an
     * on-demand pack, so the ASV opens at once and the banner says theirs is on its way; the reader
     * switches when it lands. Nothing waits on it — a launch that waited on a pack is what App Review
     * rejected (1.0.0 build 40).
     */
    private val firstLaunchBible: String? = if (saved.translation != null) {
        null
    } else {
        AssetPack.bible(deviceLanguages())?.translationId?.takeIf { it in BundledTranslations.ids }
    }

    var translationId by mutableStateOf(
        saved.translation?.takeIf { it in BundledTranslations.ids && isOnDevice(it) }
            ?: firstLaunchBible?.takeIf { isOnDevice(it) }
            ?: BundledTranslations.DEFAULT,
    )
        private set

    /**
     * How the translation being read numbers its verses against the KJV keys marks are stored under.
     * Everything stored, shared or looked up is a KJV key; [location], [chapter], [selection],
     * [topVerse] and [scrollTarget] are in the translation's own numbering. See [VerseNumbering].
     */
    var numbering by mutableStateOf(VerseNumbering.IDENTITY)
        private set

    /**
     * The language books are named in ([BookNames.current]), as observable state: a composable that
     * prints a book's name reads it, so the name changes when the Bible being read does.
     */
    var bookNamesLanguage by mutableStateOf(BookNames.current)
        private set

    /**
     * The bundled translation being fetched because the reader chose it, for the reader's banner. The
     * previous translation stays on screen until it arrives, then the reader switches — iOS's
     * `downloadingPack`.
     */
    var downloadingPack by mutableStateOf<AssetPack?>(null)
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
    var scrollTarget by mutableStateOf<Int?>(null)
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
    var fontFamily by persisted(ReaderFontFamily.fromRaw(saved.fontFamily) ?: ReaderFontFamily.DEFAULT) { p, v -> p[ReaderKeys.FONT_FAMILY] = v.raw }
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
        palette = palette, family = fontFamily,
    )

    // The verse-image designer's remembered choices — `ShareSettingsKey`, per device, iOS defaults.
    var shareTemplate by persisted(ShareTemplate.fromRaw(saved.shareTemplate) ?: ShareTemplate.PARCHMENT) { p, v -> p[ReaderKeys.SHARE_TEMPLATE] = v.raw }
    var shareAspect by persisted(ShareAspect.fromRaw(saved.shareAspect) ?: ShareAspect.SQUARE) { p, v -> p[ReaderKeys.SHARE_ASPECT] = v.raw }
    var shareFamily by persisted(ReaderFontFamily.fromRaw(saved.shareFontFamily) ?: ReaderFontFamily.DEFAULT) { p, v -> p[ReaderKeys.SHARE_FONT_FAMILY] = v.raw }
    var shareAlignment by persisted(ShareAlignment.fromRaw(saved.shareAlignment) ?: ShareAlignment.CENTER) { p, v -> p[ReaderKeys.SHARE_ALIGNMENT] = v.raw }
    var shareRedLetters by persisted(saved.shareRedLetters ?: true) { p, v -> p[ReaderKeys.SHARE_RED_LETTERS] = v }
    var shareVerseNumbers by persisted(saved.shareVerseNumbers ?: true) { p, v -> p[ReaderKeys.SHARE_VERSE_NUMBERS] = v }
    var shareWordmark by persisted(saved.shareWordmark ?: true) { p, v -> p[ReaderKeys.SHARE_WORDMARK] = v }

    val shareStyle: ShareStyle
        get() = ShareStyle(shareTemplate, shareAspect, shareFamily, shareAlignment, shareRedLetters, shareVerseNumbers, shareWordmark)

    private var loading: Job? = null

    init {
        // The saved position is a KJV key: the first load lands on the verse the translation calls it.
        load(anchor = saved.position?.key)
        firstLaunchBible?.let { preferred ->
            if (preferred == translationId) {
                // Here already (a debug build carries every pack): the choice is made, and kept.
                prefs.write { it[ReaderKeys.TRANSLATION] = preferred }
            } else {
                // Fetched while the ASV is read; the reader switches when it lands (or retries).
                selectTranslation(preferred)
            }
        }
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

    /**
     * Goes to a verse given by its **KJV key** — a search hit, a cross-reference, a note, a favorite, a
     * link — landing on the verse this translation calls it. (A [Passage] the reader typed is already
     * in their translation's numbering: see [go] with a passage.)
     */
    fun go(verse: VerseRef) {
        val native = numbering.native(verse.key)?.let(VerseRef::fromKey) ?: verse
        show(ChapterRef(native.book, native.chapter), native.verse)
    }

    /** A stored (KJV) range as the reader's translation numbers it, for showing a reference. */
    fun displayRange(range: VerseRange): VerseRange = numbering.nativeRange(range) ?: range

    /**
     * After a translation switch or at launch: stay on the verse [kjvKey] names, in the translation's
     * own numbering — which may be a different chapter (French Exodus 7:26 is English 8:1).
     */
    private fun land(kjvKey: Int, scroll: Boolean) {
        val native = numbering.native(kjvKey)?.let(VerseRef::fromKey) ?: return
        val chapter = ChapterRef(native.book, native.chapter)
        if (chapter != location) {
            selection = emptySet()
            location = chapter
        }
        if (scroll && native.verse > 1) scrollTarget = native.key
    }

    /** The development hook in MainActivity: a chapter and, optionally, a translation. */
    fun open(ref: ChapterRef, translation: String = translationId) {
        if (translation != translationId) selectTranslation(translation)
        show(ref)
    }

    fun next() = Canon.next(location)?.let { show(it) }
    fun previous() = Canon.previous(location)?.let { show(it) }

    /** ⌘+ / ⌘− on iOS: the text size a point at a time, within the Appearance slider's range. */
    fun stepFontSize(points: Int) {
        fontSize = (fontSize + points).coerceIn(ReaderStyle.SIZE_RANGE)
    }

    private val _commands = MutableSharedFlow<ReaderCommand>(extraBufferCapacity = 8)

    /** Keyboard shortcuts, as the activity receives them — carried out by the reader's screen. */
    val commands: SharedFlow<ReaderCommand> = _commands

    fun send(command: ReaderCommand) {
        _commands.tryEmit(command)
    }

    /** Switches translation and keeps the reader's place: the verse at the top stays at the top. */
    fun selectTranslation(id: String) {
        if (id !in BundledTranslations.ids) return
        val pack = AssetPack.forTranslation(id)
        // A choice made; any other translation's failed download is no longer what the reader wants.
        AssetLibrary.clearFailedTranslations(except = pack)
        if (id == translationId) return
        // The KJV and the big-8 Bibles are on-demand packs: listed from the start, fetched the first time
        // one is chosen.
        // Nothing changes on screen until the file is here — the reader keeps reading what they had,
        // with a banner — and then this runs again and switches.
        if (pack != null && !isOnDevice(id)) {
            if (downloadingPack != null) return
            downloadingPack = pack
            val reading = translationId
            viewModelScope.launch {
                val arrived = AssetLibrary.ensure(pack)
                downloadingPack = null
                // Unless the reader has since chosen another translation that was already here.
                if (arrived && translationId == reading) selectTranslation(id)
            }
            return
        }
        // The verse at the top as a KJV key, taken before the numbering changes: the new translation
        // opens on the same verse, whatever it calls it.
        val anchor = numbering.kjv(topVerse ?: VerseRef(location.book, location.chapter, 1).key)
        translationId = id
        prefs.write { it[ReaderKeys.TRANSLATION] = id }
        load(anchor = anchor)
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

    /** Saved as a KJV key, so the place survives a switch of translation (and numbering). */
    private fun savePosition(ref: VerseRef) {
        val key = numbering.kjv(ref.key)
        prefs.write { it[ReaderKeys.POSITION] = key }
    }

    private fun rememberChapter(ref: ChapterRef) {
        recent = Recents.chapters(recent, ref)
        val encoded = ReaderSettings.encodeRecent(recent)
        prefs.write { it[ReaderKeys.RECENT] = encoded }
    }

    // Search

    /**
     * Whether the translation being read can be searched — `ChapterTextSource.isSearchable`. Every
     * kind can: a plain store by its FTS5 index, the sealed ASV by its sealed index, an online
     * translation at its provider. Only a package built without an index can't, and the Go To sheet
     * says so rather than quietly searching another translation and presenting its words.
     */
    var isSearchable by mutableStateOf(true)
        private set

    /**
     * Full-text search of the current translation, off the main thread — `ReaderModel.search`, through
     * whatever the translation is: the same call reaches an FTS5 store, the sealed index, or (for an
     * online translation) the provider's own search, which costs one request. A failure finds nothing,
     * as on iOS.
     */
    suspend fun search(query: String): List<SearchHit> {
        val id = translationId
        return withContext(Dispatchers.IO) {
            runCatching {
                val source = BundledTranslations.source(getApplication(), id)
                if (!source.isSearchable) emptyList() else source.search(query, VerseSearch.DEFAULT_LIMIT)
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
    private fun load(anchor: Int? = null) {
        val id = translationId
        loading?.cancel()
        loading = viewModelScope.launch {
            // The source first — its numbering and language decide which chapter to read and what to
            // call its books — then the chapter.
            val opened = withContext(Dispatchers.IO) {
                runCatching {
                    val source = BundledTranslations.source(getApplication(), id)
                    Triple(source, source.numbering, source.info.language)
                }
            }
            if (id != translationId) return@launch
            opened.getOrNull()?.let { (_, sourceNumbering, language) ->
                numbering = sourceNumbering
                // Books are named in the language of the Bible being read — "Jean", "约翰福音" — so the
                // header, the picker and every reference agree with the text; a Bible without a
                // language of its own (the English ones, imports) in the device's language.
                BookNames.use(language ?: deviceLanguages().firstOrNull())
                bookNamesLanguage = BookNames.current
                if (anchor != null) land(anchor, scroll = true)
            }
            val ref = location
            var searchable = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val source = opened.getOrThrow().first
                    searchable = source.isSearchable
                    source.chapter(ref)
                }
            }
            if (ref != location || id != translationId) return@launch
            isSearchable = searchable
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

    /** The selection as KJV ranges — what a highlight, note, favorite or link stores. */
    val selectedRanges: List<VerseRange>
        get() {
            val native = Selection.ranges(selection) { verseCount(it) }
            val numbering = numbering
            return if (numbering.isIdentity) native else native.map(numbering::kjvRange)
        }

    /** The KJV keys the selection holds, one per KJV verse — what highlights are stored under. */
    val selectedKjvKeys: Set<Int>
        get() {
            val numbering = numbering
            return selection.flatMapTo(mutableSetOf()) { numbering.kjvKeyList(it) }
        }

    /** What the translation being read permits; public domain until its chapter has loaded. */
    val rights: TranslationRights get() = chapter?.translation?.rights ?: TranslationRights.PUBLIC_DOMAIN

    val translationAbbreviation: String get() = chapter?.translation?.abbreviation ?: translationId

    /**
     * Whether the selection may leave the device at all under this translation's terms. Selected keys
     * are always verses the text has, so their number is the quotation's verse count.
     */
    fun mayQuote(): Boolean = rights.mayQuote(selection.size)

    /**
     * The verses [ranges] — **KJV keys** — cover, in [translation], read chapter by chapter off the main
     * thread. The verses come back with the translation's own references, so a quotation prints the
     * numbers its reader knows.
     */
    suspend fun verses(ranges: List<VerseRange>, translation: String = translationId): List<ChapterVerse> {
        val id = translation
        val current = numbering.takeIf { id == translationId }
        val loaded = chapter
        return withContext(Dispatchers.IO) {
            val numbering = current ?: runCatching { BundledTranslations.source(getApplication(), id).numbering }
                .getOrDefault(VerseNumbering.IDENTITY)
            val native = ranges.mapNotNull { numbering.nativeRange(it) }
            val chapters = native.flatMap { range ->
                val first = ChapterRef(range.start.book, range.start.chapter)
                val last = ChapterRef(range.end.book, range.end.chapter)
                generateSequence(first) { if (it == last) null else Canon.next(it) }.take(200).toList()
            }.distinct()
            chapters.flatMap { ref ->
                val verses = if (loaded != null && loaded.ref == ref && loaded.translation.id == id) {
                    loaded.verses
                } else {
                    runCatching { BundledTranslations.source(getApplication(), id).chapter(ref).verses }.getOrDefault(emptyList())
                }
                if (id == translationId) verses.maxOfOrNull { it.ref.verse }?.let { verseCounts[ref] = it }
                verses.filter { v -> native.any { it.contains(v.ref.key) } }
            }
        }
    }

    /**
     * The quotation for the selection, or "" when the translation's terms don't allow it — the gate
     * lives where text becomes quotable, as on iOS.
     */
    suspend fun quotation(ranges: List<VerseRange> = selectedRanges): String {
        if (!rights.mayQuote(selection.size)) return ""
        return Selection.quotation(ranges.map(::displayRange), verses(ranges), translationAbbreviation)
    }

    /**
     * The share link for [ranges] with the designer's remembered template, typeface, aspect and red
     * letters — as `ShareMenu` makes it — or null when the passage is too long for one or the
     * translation's terms don't allow links (see [ShareSource.linksAllowed]).
     */
    suspend fun shareLink(ranges: List<VerseRange> = selectedRanges): String? = shareSource(ranges)?.link(shareStyle)

    /**
     * The passage as the designer and share links see it, read from [translation] — the reader's own
     * by default, a link's sender's when it is installed here — or null when there is nothing to read.
     */
    suspend fun shareSource(
        ranges: List<VerseRange> = selectedRanges,
        translation: String = translationId,
        linkStyle: ShareLinkPayload? = null,
    ): ShareSource? {
        val id = translation.takeIf { it in BundledTranslations.ids } ?: translationId
        val loaded = chapter?.translation?.takeIf { it.id == id }
        val info = loaded ?: withContext(Dispatchers.IO) {
            runCatching { BundledTranslations.source(getApplication(), id).info }.getOrNull()
        } ?: return null
        val verses = verses(ranges, id).map { ShareVerse.fromScalars(it.ref, it.text, it.red.map { r -> r.start to r.length }) }
        if (verses.isEmpty()) return null
        // The card prints the translation's own numbers; the link carries the KJV keys.
        val displayRanges = if (id == translationId) ranges.map(::displayRange) else ranges
        return ShareSource(
            ranges = ranges, verses = verses, translation = info.abbreviation,
            notice = TranslationRights.attributionNotice(info.license, info.copyright),
            rights = info.rights, verseCount = ::verseCount, linkStyle = linkStyle, displayRanges = displayRanges,
        )
    }

    /** The passage the verse-image designer is open on, or null when it is closed. */
    var designer by mutableStateOf<ShareSource?>(null)
        private set

    /** Share Image…: opens the designer on the selection, when the translation's terms allow an image. */
    fun openDesigner(ranges: List<VerseRange> = selectedRanges) {
        viewModelScope.launch { designer = shareSource(ranges)?.takeIf { it.imagesAllowed } }
    }

    /**
     * Opens the designer on a share link's passage, rebuilt from the sender's translation when it is
     * installed here, else the reader's own — and the link's template, typeface and aspect become the
     * designer's starting point (`applyLinkStyle`).
     */
    fun openDesigner(payload: ShareLinkPayload) {
        viewModelScope.launch {
            val source = shareSource(payload.ranges, payload.translation, payload)?.takeIf { it.imagesAllowed } ?: return@launch
            ShareTemplate.fromRaw(payload.template)?.let { shareTemplate = it }
            ShareAspect.fromRaw(payload.aspect)?.let { shareAspect = it }
            ReaderFontFamily.fromShareToken(payload.font)?.let { shareFamily = it }
            sharedPassage = null
            designer = source
        }
    }

    fun closeDesigner() {
        designer = null
    }

    /**
     * Goes to a passage a link named and selects it — `ShareSupport.reveal`. A link carries KJV keys;
     * the reader lands on, and selects, the verses as the translation being read numbers them. The
     * chapters' verse counts are read first, so a range crossing a chapter selects the right verses.
     */
    fun reveal(ranges: List<VerseRange>) {
        val first = ranges.firstOrNull() ?: return
        val opening = loading
        viewModelScope.launch {
            // A link that opened the app arrives before the translation's numbering is known.
            opening?.join()
            go(first.start)
            verses(ranges)
            val native = ranges.mapNotNull { numbering.nativeRange(it) }
            selection = native.flatMap { Selection.keys(it) { ref -> verseCount(ref) } }.toSet()
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

    /**
     * Typed passages ("Rom 8:1-17; Ps 23") as **KJV** ranges — what a note anchors to — whole chapters
     * resolved against real verse counts. The reader typed them in their own translation's numbering.
     */
    suspend fun resolvePassages(text: String): List<VerseRange> =
        resolveNativePassages(text).map(numbering::kjvRange)

    /** Typed passages as the translation being read numbers them. */
    suspend fun resolveNativePassages(text: String): List<VerseRange> =
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
        val link = AppLink.parse(url) ?: return false
        // A link that arrives while the designer is open goes to its own passage, as `reveal` does on iOS.
        designer = null
        when (link) {
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

    /**
     * Highlights are stored one per KJV verse ([selectedKjvKeys]), so they show in every translation
     * whatever it calls the verse.
     */
    fun highlightSelection(color: HighlightColor) {
        userData.highlight(selectedKjvKeys, color)
        clearSelection()
    }

    fun removeSelectedHighlights() {
        userData.removeHighlights(selectedKjvKeys)
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
        val chapter = VerseRange(VerseRef(location.book, location.chapter, 1), VerseRef(location.book, location.chapter, count))
        return userData.newNote(listOf(numbering.kjvRange(chapter)))
    }

    // Keepsakes, notes export and import (ui/keepsake/, ui/export/, ui/importnotes/).

    val legacy = com.blainemiller.scripturealone.ui.keepsake.LegacySession(application, viewModelScope)

    /** Reads someone else's Bible: their marks in place of the reader's own, read-only. */
    fun openKeepsake(keepsake: com.blainemiller.scripturealone.data.keepsake.Keepsake) {
        clearSelection()
        legacy.open(keepsake, translationId, BundledTranslations.ids, ::selectTranslation)
    }

    /** "My Bible": back to the reader's own marks and translation. */
    fun closeKeepsake() = legacy.close(translationId, ::selectTranslation)

    private companion object {
        /** The device's languages, most preferred first, as BCP 47 tags. */
        fun deviceLanguages(): List<String> {
            val list = android.os.LocaleList.getDefault()
            return (0 until list.size()).map { list[it].toLanguageTag() }
        }

        /**
         * Whether [id] opens without a download. A launch that restores a translation must only ever
         * pick one of these: silently fetching 15 MB because the reader's translation wasn't here
         * would be the app spending the reader's data on its own (iOS's `isOnDevice`).
         */
        fun isOnDevice(id: String): Boolean {
            val pack = AssetPack.forTranslation(id) ?: return true
            return !AssetLibrary.isAttached || AssetLibrary.isOnDevice(pack)
        }
    }
}
