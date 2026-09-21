package com.blainemiller.scripturealone.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.util.concurrent.Executors

/**
 * The key names, identical to the iOS app's `UserDefaults` keys — `SettingsKey` in
 * `ScriptureAlone/Reader/ReaderStyle.swift` for appearance, and the bare `position`, `translation`,
 * `recent` and `recentSearches` of `ReaderModel.swift` — so one model reads the same on both
 * platforms. The *values* of the enums are the Swift raw values too ("sepia", "sunrise",
 * "paragraphs").
 *
 * Two values differ in shape only, because Preferences has no ordered list type: `recent` is the
 * iOS `[Int]` of verse keys written as comma-separated decimal, and `recentSearches` the iOS
 * `[String]` written as a JSON array (a search can contain a comma).
 */
object ReaderKeys {
    val POSITION = intPreferencesKey("position")
    val TRANSLATION = stringPreferencesKey("translation")
    val RECENT = stringPreferencesKey("recent")
    val RECENT_SEARCHES = stringPreferencesKey("recentSearches")

    val THEME = stringPreferencesKey("reader.theme")
    val ACCENT = stringPreferencesKey("reader.accent")
    val FONT_SIZE = doublePreferencesKey("reader.fontSize")
    val LINE_SPACING = doublePreferencesKey("reader.lineSpacing")
    val LAYOUT = stringPreferencesKey("reader.layout")
    val RED_LETTERS = booleanPreferencesKey("reader.redLetters")
    val VERSE_NUMBERS = booleanPreferencesKey("reader.verseNumbers")
    val HEADINGS = booleanPreferencesKey("reader.headings")
    val FOOTNOTES = booleanPreferencesKey("reader.footnotes")
}

/**
 * Everything the reader restores at launch, decoded and validated. Appearance values stay as the
 * stored raw strings — the reader's enums live in the UI layer and map them — and are null when
 * never set, so the caller applies the iOS defaults in one place.
 *
 * A stored position or recent chapter that no longer names a real chapter (a corrupt file, a key
 * from some future build) is dropped rather than trusted: the fallback is John 1, as on iOS.
 */
data class ReaderSettings(
    val position: VerseRef?,
    val translation: String?,
    val recent: List<ChapterRef>,
    val recentSearches: List<String>,
    val theme: String?,
    val accent: String?,
    val fontSize: Double?,
    val lineSpacing: Double?,
    val layout: String?,
    val redLetters: Boolean?,
    val verseNumbers: Boolean?,
    val headings: Boolean?,
    val footnotes: Boolean?,
) {
    companion object {
        fun from(p: Preferences): ReaderSettings = ReaderSettings(
            position = p[ReaderKeys.POSITION]?.let(::validVerse),
            translation = p[ReaderKeys.TRANSLATION],
            recent = decodeRecent(p[ReaderKeys.RECENT]),
            recentSearches = decodeSearches(p[ReaderKeys.RECENT_SEARCHES]),
            theme = p[ReaderKeys.THEME],
            accent = p[ReaderKeys.ACCENT],
            fontSize = p[ReaderKeys.FONT_SIZE],
            lineSpacing = p[ReaderKeys.LINE_SPACING],
            layout = p[ReaderKeys.LAYOUT],
            redLetters = p[ReaderKeys.RED_LETTERS],
            verseNumbers = p[ReaderKeys.VERSE_NUMBERS],
            headings = p[ReaderKeys.HEADINGS],
            footnotes = p[ReaderKeys.FOOTNOTES],
        )

        /** A verse key naming a real book and chapter. Verse bounds vary by translation and aren't checked. */
        fun validVerse(key: Int): VerseRef? {
            val ref = VerseRef.fromKey(key)
            val book = BookID.of(ref.book) ?: return null
            if (ref.chapter !in 1..book.chapterCount || ref.verse !in 0..999) return null
            return ref
        }

        fun encodeRecent(chapters: List<ChapterRef>): String =
            chapters.joinToString(",") { VerseRef(it.book, it.chapter, 1).key.toString() }

        fun decodeRecent(raw: String?): List<ChapterRef> =
            raw.orEmpty().split(',').mapNotNull { piece ->
                piece.trim().toIntOrNull()?.let(::validVerse)?.let { ChapterRef(it.book, it.chapter) }
            }.distinct()

        fun encodeSearches(searches: List<String>): String = JsonArray(searches.map(::JsonPrimitive)).toString()

        fun decodeSearches(raw: String?): List<String> {
            if (raw.isNullOrBlank()) return emptyList()
            val array = try {
                Json.parseToJsonElement(raw) as? JsonArray
            } catch (e: IllegalArgumentException) {
                null
            } ?: return emptyList()
            return array.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        }
    }
}

/**
 * The list rules of `ReaderModel.swift`, kept apart from the model so they can be tested alone.
 */
object Recents {
    const val LIMIT = 12

    /** [chapter] moved to the front, without duplicates, at most [LIMIT]. */
    fun chapters(list: List<ChapterRef>, chapter: ChapterRef): List<ChapterRef> =
        (listOf(chapter) + list.filter { it != chapter }).take(LIMIT)

    /**
     * [query] trimmed and moved to the front. Duplicates are found ignoring case and diacritics, so
     * "Shepherd" replaces "shepherd" rather than sitting beside it. Fewer than two characters is not
     * worth keeping, and the list is returned unchanged.
     */
    fun searches(list: List<String>, query: String): List<String> {
        val text = query.trim()
        if (text.length < 2) return list
        val key = foldKey(text)
        return (listOf(text) + list.filter { foldKey(it) != key }).take(LIMIT)
    }

    private fun foldKey(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            .lowercase()
}

/** The one DataStore for the reader's settings. A property delegate, so the process has exactly one. */
val Context.readerDataStore: DataStore<Preferences> by preferencesDataStore(name = "reader")

/**
 * Reads and writes the reader's settings.
 *
 * [load] blocks, deliberately and once, at launch: the first frame should be the chapter the reader
 * left, not John 1 replaced by it a moment later, and launch extras must be applied *over* the
 * restored state rather than raced by it. The file is a few hundred bytes.
 *
 * Writes go through one single-threaded scope that outlives any screen, so they land in the order
 * they were made, and a write made just before the activity finishes isn't cancelled with it.
 */
class ReaderPrefs(private val store: DataStore<Preferences>) {

    fun load(): ReaderSettings = runBlocking {
        val prefs = try {
            store.data.first()
        } catch (e: IOException) {
            emptyPreferences()
        }
        ReaderSettings.from(prefs)
    }

    fun write(change: (MutablePreferences) -> Unit) {
        writes.launch {
            // A setting that fails to save (a full disk) is not worth crashing the reader over; it
            // is simply not restored next time.
            try {
                store.edit { change(it) }
            } catch (e: IOException) {
                Unit
            }
        }
    }

    private companion object {
        val writes = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    }
}
