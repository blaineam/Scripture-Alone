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
data class VerseOfDayEntry(val verse: DailyVerse, val translation: String) {
    val range: VerseRange? get() = verse.range
    val reference: String get() = range?.display.orEmpty()
    val shortReference: String get() = range?.abbreviatedDisplay.orEmpty()
    val text: String get() = verse.text(translation)
    val red: List<Pair<Int, Int>> get() = verse.redRanges(translation)

    companion object {
        fun at(catalog: DailyVerseCatalog?, instant: Instant, readerTranslation: String, zone: ZoneId = ZoneId.systemDefault()): VerseOfDayEntry {
            val verse = catalog?.verse(instant, zone) ?: DailyVerseLibrary.placeholder
            val shown = if (verse.text.containsKey(readerTranslation)) readerTranslation else DailyVerseCatalog.FALLBACK_TRANSLATION
            return VerseOfDayEntry(verse, shown)
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
        if (previous != null && previous.translation == snapshot.translation && previous.items == snapshot.items) return false
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
        val translation = readerTranslation.takeIf { it in BundledTranslations.ids } ?: BundledTranslations.DEFAULT
        val source = BundledTranslations.source(context, translation)
        val chapters = HashMap<ChapterRef, List<ChapterVerse>>()
        fun verses(book: Int, chapter: Int): List<ChapterVerse> = chapters.getOrPut(ChapterRef(book, chapter)) {
            val ref = ChapterRef(book, chapter)
            if (source.contains(ref)) source.chapter(ref).verses else emptyList()
        }
        return VerseSnapshot.build(
            favorites = library.favorites,
            highlights = library.highlights,
            notes = library.notes,
            translation = translation,
            generatedAt = now,
            verseCount = { book, chapter -> verses(book, chapter).maxOfOrNull { it.ref.verse } ?: 0 },
            text = { range -> textOf(range, ::verses) },
        )
    }

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
