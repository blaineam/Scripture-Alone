package com.blainemiller.scripturealone.ui.widget

import android.content.Context
import com.blainemiller.scripturealone.companion.VerseSnapshot
import com.blainemiller.scripturealone.data.BundledTranslations
import com.blainemiller.scripturealone.data.ChapterVerse
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.daily.DailyVerse
import com.blainemiller.scripturealone.data.daily.DailyVerseCatalog
import com.blainemiller.scripturealone.data.prefs.ReaderKeys
import com.blainemiller.scripturealone.data.prefs.readerDataStore
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.blainemiller.scripturealone.data.translations.TranslationLibrary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import androidx.datastore.preferences.core.emptyPreferences
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

/**
 * The two reader settings the widgets honor, read from the reader's own DataStore (the iOS app's
 * `translation` and `reader.redLetters` keys) — where iOS mirrors them into the App Group.
 */
data class WidgetReaderSettings(val translation: String, val redLetters: Boolean) {
    companion object {
        fun flow(context: Context): Flow<WidgetReaderSettings> =
            context.applicationContext.readerDataStore.data
                .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
                .map { p ->
                    WidgetReaderSettings(
                        translation = p[ReaderKeys.TRANSLATION] ?: BundledTranslations.DEFAULT,
                        redLetters = p[ReaderKeys.RED_LETTERS] ?: true,
                    )
                }
                .distinctUntilChanged()

        suspend fun current(context: Context): WidgetReaderSettings = flow(context).first()
    }
}

/** Small widget-only state that isn't the reader's: plain SharedPreferences, private to the app. */
object WidgetPrefs {
    private const val FILE = "widgets"

    /** The Favorites widget's "Next" nudge — `widget.favorites.nudge` in the iOS App Group. */
    private const val NUDGE = "widget.favorites.nudge"
    private const val DEMO = "widget.demoLibrary"

    /** The translation last seen, and when the reader switched to it — `watch.translationChangedAt`. */
    private const val LAST_TRANSLATION = "watch.translation"
    private const val CHANGED_AT = "watch.translationChangedAt"
    private const val PUBLISHED = "watch.published"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun nudge(context: Context): Int = prefs(context).getInt(NUDGE, 0)
    fun advanceNudge(context: Context) = prefs(context).edit().putInt(NUDGE, nudge(context) + 1).apply()

    fun demoLibrary(context: Context): Boolean = prefs(context).getBoolean(DEMO, false)
    fun setDemoLibrary(context: Context, on: Boolean) = prefs(context).edit().putBoolean(DEMO, on).commit()

    /**
     * Records [translation] as the reader's current one and returns when they switched to it, in
     * seconds since 1970. A new value is a switch, stamped now; the same value again keeps its stamp,
     * so a launch that re-reports an old choice can't override a newer pick made on the watch.
     */
    fun translationChangedAt(context: Context, translation: String, now: Instant = Instant.now()): Double {
        val p = prefs(context)
        val last = p.getString(LAST_TRANSLATION, null)
        // Stored as text: SharedPreferences has no double, and a float can't hold epoch seconds exactly.
        if (last == translation) return p.getString(CHANGED_AT, null)?.toDoubleOrNull() ?: 0.0
        // The very first value ever seen is not a switch: as on iOS before any switch, it carries 0.
        val at = if (last == null) 0.0 else now.toEpochMilli() / 1000.0
        p.edit().putString(LAST_TRANSLATION, translation).putString(CHANGED_AT, at.toString()).apply()
        return at
    }

    /** What was last sent to the watch, so an unchanged value isn't re-sent on every process start. */
    fun published(context: Context, what: String): String? = prefs(context).getString("$PUBLISHED.$what", null)
    fun setPublished(context: Context, what: String, value: String) =
        prefs(context).edit().putString("$PUBLISHED.$what", value).apply()
}

/** The bundled Verse of the Day list, decoded once per process — `DailyVerseLibrary.swift`. */
object DailyVerseLibrary {
    @Volatile private var cached: DailyVerseCatalog? = null

    fun catalog(context: Context): DailyVerseCatalog? = cached ?: synchronized(this) {
        cached ?: try {
            context.assets.open(DailyVerseCatalog.ASSET_NAME).use { it.readBytes().decodeToString() }
                .let(DailyVerseCatalog::parse).also { cached = it }
        } catch (e: IOException) {
            null
        }
    }

    /** A stand-in for previews if the resource is somehow missing. */
    val placeholder = DailyVerse(
        ref = "19023001-19023001", theme = "The LORD is my shepherd",
        text = mapOf(
            "ASV" to "Jehovah is my shepherd; I shall not want.",
            "BSB" to "The LORD is my shepherd; I shall not want.",
            "KJV" to "The LORD is my shepherd; I shall not want.",
        ),
    )
}

/**
 * One day's Verse of the Day as a widget draws it — `VerseEntry` on iOS.
 *
 * [translation] is the translation the text is actually in: the reader's own when the list has it,
 * else the ASV. (The iOS widget labels the text with the reader's translation even when it has fallen
 * back; here the label always names what is shown.)
 */
data class VerseOfDayEntry(
    val verse: DailyVerse,
    val translation: String,
    /** The translation's label ("CSB"); its id can be an import's file name. */
    val label: String = translation,
    /** Today's passage in a translation the daily list doesn't carry, from the app ([VerseSnapshot.daily]). */
    val own: VerseSnapshot.DailyText? = null,
) {
    val range: VerseRange? get() = verse.range
    val reference: String get() = range?.display.orEmpty()
    val shortReference: String get() = range?.abbreviatedDisplay.orEmpty()
    val text: String get() = own?.text ?: verse.text(translation)
    val red: List<Pair<Int, Int>> get() = own?.redRanges ?: verse.redRanges(translation)

    companion object {
        /**
         * The day's entry in the reader's translation: from the daily list when it carries it, else from
         * the passages the app wrote ahead into [snapshot] (an import), else the ASV.
         */
        fun at(
            catalog: DailyVerseCatalog?, instant: Instant, readerTranslation: String,
            zone: ZoneId = ZoneId.systemDefault(), snapshot: VerseSnapshot? = null,
        ): VerseOfDayEntry {
            val verse = catalog?.verse(instant, zone) ?: DailyVerseLibrary.placeholder
            val mine = snapshot?.takeIf { it.translation == readerTranslation }
            val own = if (verse.text.containsKey(readerTranslation)) null else mine?.daily?.get(verse.ref)
            if (own == null && !verse.text.containsKey(readerTranslation)) {
                return VerseOfDayEntry(verse, DailyVerseCatalog.FALLBACK_TRANSLATION)
            }
            return VerseOfDayEntry(verse, readerTranslation, label = mine?.abbreviation ?: readerTranslation, own = own)
        }
    }
}

/**
 * The snapshot the Favorites widget reads — the App Group's `VerseSnapshot.json` on iOS — kept in the
 * app's private files. Written by [WidgetSync]; read by the widget, which never builds one itself
 * unless the file is missing.
 */
object WidgetSnapshots {
    private fun file(context: Context) = File(File(context.filesDir, "widgets").apply { mkdirs() }, VerseSnapshot.FILE_NAME)

    fun read(context: Context): VerseSnapshot? = try {
        file(context).takeIf { it.exists() }?.readText()?.let(VerseSnapshot::decode)
    } catch (e: IOException) {
        null
    }

    /** Writes atomically; false when the content (all but the generation time) is unchanged. */
    fun write(context: Context, snapshot: VerseSnapshot): Boolean {
        val previous = read(context)
        if (previous != null && previous.translation == snapshot.translation && previous.items == snapshot.items &&
            previous.abbreviation == snapshot.abbreviation && previous.daily == snapshot.daily
        ) {
            return false
        }
        val target = file(context)
        val partial = File(target.parentFile, "${target.name}.partial")
        return try {
            partial.writeText(snapshot.encoded())
            partial.renameTo(target) || run { target.delete(); partial.renameTo(target) }
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Builds a snapshot of [library] with its text in [readerTranslation] — or in the ASV when that
     * translation isn't one the app can read offline (an online translation's text may not be stored).
     * Blocks on disk and decryption; call off the main thread.
     */
    fun build(context: Context, library: WidgetLibrary, readerTranslation: String, now: Instant = Instant.now()): VerseSnapshot {
        val translation = readerTranslation.takeIf { it in offlineTranslations() } ?: BundledTranslations.DEFAULT
        val source = BundledTranslations.source(context, translation)
        val numbering = source.numbering
        val chapters = HashMap<ChapterRef, List<ChapterVerse>>()
        fun verses(book: Int, chapter: Int): List<ChapterVerse> = chapters.getOrPut(ChapterRef(book, chapter)) {
            val ref = ChapterRef(book, chapter)
            if (source.contains(ref)) source.chapter(ref).verses else emptyList()
        }
        val built = VerseSnapshot.build(
            favorites = library.favorites,
            highlights = library.highlights,
            notes = library.notes,
            translation = translation,
            generatedAt = now,
            verseCount = { book, chapter -> verses(book, chapter).maxOfOrNull { it.ref.verse } ?: 0 },
            // Marks are KJV ranges; the text is read from the verses the translation calls them.
            text = { range -> numbering.nativeRange(range)?.let { textOf(it, ::verses) }.orEmpty() },
        )
        return built.copy(
            abbreviation = source.info.abbreviation,
            daily = dailyTexts(context, source, translation, now, ::verses),
        )
    }

    /** How many days of Verse of the Day [dailyTexts] writes ahead — `WidgetSnapshotSync.dailyTexts`. */
    const val DAILY_DAYS = 14

    /**
     * The next two weeks' Verse of the Day in [source], for a translation the widget doesn't carry (the
     * daily list has the bundled Bibles' text already): an import, and only one whose terms let its
     * text be stored. Red letters as the widget draws them, in Unicode scalars of the joined text.
     */
    fun dailyTexts(
        context: Context,
        source: com.blainemiller.scripturealone.data.ChapterSource,
        translation: String,
        now: Instant,
        verses: (book: Int, chapter: Int) -> List<ChapterVerse>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Map<String, VerseSnapshot.DailyText>? {
        val catalog = DailyVerseLibrary.catalog(context) ?: return null
        if (!source.info.rights.allowOfflineStorage || translation in catalog.translations) return null
        val result = sortedMapOf<String, VerseSnapshot.DailyText>()
        var date = now
        repeat(DAILY_DAYS) {
            val verse = catalog.verse(date, zone)
            val range = verse?.range?.let(source.numbering::nativeRange)
            if (verse != null && range != null) {
                dailyText(range, verses)?.let { result[verse.ref] = it }
            }
            date = DailyVerseCatalog.nextMidnight(date, zone)
        }
        return result.takeIf { it.isNotEmpty() }
    }

    /**
     * [range]'s verses joined as the widget shows them, the pilcrow at a paragraph start dropped and
     * each verse's words of Christ moved to where it lands in the joined text. Null when the
     * translation lacks the passage.
     */
    fun dailyText(range: VerseRange, verses: (book: Int, chapter: Int) -> List<ChapterVerse>): VerseSnapshot.DailyText? {
        val rows = mutableListOf<ChapterVerse>()
        if (range.start.book == range.end.book) {
            for (chapter in range.start.chapter..range.end.chapter) {
                verses(range.start.book, chapter).filterTo(rows) { it.ref.key in range.start.key..range.end.key && it.ref.verse > 0 }
            }
        }
        if (rows.isEmpty()) return null
        val text = StringBuilder()
        val red = mutableListOf<List<Int>>()
        for (row in rows) {
            val shift = if (row.text.startsWith("¶ ")) 2 else 0
            val words = row.text.substring(shift)
            if (text.isNotEmpty()) text.append(' ')
            val base = text.codePointCount(0, text.length)
            val scalars = row.text.codePointCount(0, row.text.length)
            for (span in row.red) {
                if (span.start < shift || span.length <= 0 || span.start + span.length > scalars) continue
                red += listOf(base + span.start - shift, span.length)
            }
            text.append(words)
        }
        return VerseSnapshot.DailyText(text.toString(), red)
    }

    /**
     * Translations whose text may be kept in a snapshot: the bundled ones and the reader's imports. An
     * online translation's terms don't allow storing its text, so its snapshot is in the ASV instead.
     */
    private fun offlineTranslations(): List<String> =
        BundledTranslations.bundled +
            if (TranslationLibrary.isAttached) TranslationLibrary.state.value.imported.map { it.id } else emptyList()

    /**
     * The text of [range], its first chapter only and at most 13 verses — a widget shows the opening of
     * a long note passage, as `WidgetSnapshotSync.swift` caps it. The pilcrow the store keeps at a
     * paragraph start is dropped, as there.
     */
    fun textOf(range: VerseRange, verses: (book: Int, chapter: Int) -> List<ChapterVerse>): String {
        val start = range.start
        val lastVerse = if (range.end.book == start.book && range.end.chapter == start.chapter) {
            minOf(range.end.verse, start.verse + 12)
        } else {
            start.verse + 12
        }
        return verses(start.book, start.chapter)
            .filter { it.ref.verse in start.verse..lastVerse && it.ref.verse > 0 }
            .joinToString(" ") { it.text }
            .replace("¶ ", "")
    }
}
