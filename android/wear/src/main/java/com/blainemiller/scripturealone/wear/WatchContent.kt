package com.blainemiller.scripturealone.wear

import com.blainemiller.scripturealone.companion.LocaleBible
import com.blainemiller.scripturealone.companion.VerseSnapshot
import com.blainemiller.scripturealone.companion.VerseText
import com.blainemiller.scripturealone.data.VerseNumbering
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.daily.DailyVerse
import com.blainemiller.scripturealone.data.daily.DailyVerseCatalog
import java.time.Instant
import java.time.ZoneId

/**
 * Today's passage as the watch shows it — in the app, the tile and the complication. In the translation
 * [translation] picks when the list has it (the Apple Watch's complication shows only the reference;
 * here all three follow the translation the reader reads on the watch), else the ASV.
 *
 * [range] is in KJV keys, as the list stores it and routes carry it; the reference is drawn in
 * [numbering] — the shown Bible's own verse numbers when the watch holds its edition.
 */
data class WatchVerseOfDay(
    val verse: DailyVerse,
    val translation: String,
    val numbering: VerseNumbering = VerseNumbering.IDENTITY,
) {
    val range: VerseRange? get() = verse.range
    private val nativeRange: VerseRange? get() = range?.let { numbering.nativeRange(it) ?: it }
    val reference: String get() = nativeRange?.display.orEmpty()
    val shortReference: String get() = nativeRange?.abbreviatedDisplay.orEmpty()
    val text: String get() = verse.text(translation)

    /** The circular complication's two lines: "Ps" over "23:1" — `VerseAccessoryView.circular`. */
    val shortTextLines: Pair<String, String> get() = VerseText.split(shortReference)

    /** "Ps 23:1 · Jehovah is my shepherd; I…" — `accessoryInline`. */
    val inline: String get() = "$shortReference · ${VerseText.openingWords(text, maxWords = 4)}"

    companion object {
        fun at(
            catalog: DailyVerseCatalog?, instant: Instant, translation: String, zone: ZoneId = ZoneId.systemDefault(),
            numbering: VerseNumbering = VerseNumbering.IDENTITY,
        ): WatchVerseOfDay? {
            val verse = catalog?.verse(instant, zone) ?: return null
            val shown = if (verse.text.containsKey(translation)) translation else DailyVerseCatalog.FALLBACK_TRANSLATION
            return WatchVerseOfDay(verse, shown, if (shown == translation) numbering else VerseNumbering.IDENTITY)
        }

        /**
         * Which translation today's passage is shown in — the owner's rule that a reader in one of the
         * big-8 languages sees no English (docs/localization.md). The one being read ([current]) when the
         * list has it and it is not English where the device's [languages] have a Bible of their own;
         * otherwise that Bible ([LocaleBible]), which the list carries for all eight even before the phone
         * has sent its edition; otherwise [current].
         */
        fun translation(current: String, available: List<String>, languages: List<String>): String {
            if (current in available && LocaleBible.isLocaleBible(current)) return current
            val local = LocaleBible.forPreferredLanguages(languages)?.takeIf { it in available }
            return local ?: current
        }

        /**
         * Today and the next [days] local days, each valid from one local midnight to the next — the
         * complication's timeline, as `VerseOfDayProvider.entries` gives WidgetKit a week.
         */
        fun week(
            catalog: DailyVerseCatalog?, from: Instant, translation: String, days: Int = 7, zone: ZoneId = ZoneId.systemDefault(),
            numbering: VerseNumbering = VerseNumbering.IDENTITY,
        ): List<Triple<Instant, Instant, WatchVerseOfDay>> {
            val result = mutableListOf<Triple<Instant, Instant, WatchVerseOfDay>>()
            var start = from
            repeat(days) {
                val end = DailyVerseCatalog.nextMidnight(start, zone)
                at(catalog, start, translation, zone, numbering)?.let { result += Triple(start, end, it) }
                start = end
            }
            return result
        }
    }
}

/** The snapshot's notes that touch [range] — the notes a verse screen lists, as `WatchVerseView`. */
fun VerseSnapshot?.notesOn(range: VerseRange): List<VerseSnapshot.Item> =
    this?.items(setOf(VerseSnapshot.Kind.NOTE)).orEmpty()
        .filter { it.startKey <= range.end.key && range.start.key <= it.endKey }

/**
 * Highlight colors by **native** verse key for [verses] of one chapter — the chapter view's tints.
 * The snapshot is in KJV keys; each verse takes the color of the KJV verses it holds (the newest
 * highlight wins, as on the phone) — `highlightColors` in `WatchChapterView`.
 */
fun VerseSnapshot?.highlightColors(verses: List<WatchVerse>, numbering: VerseNumbering): Map<Int, String> {
    if (verses.isEmpty()) return emptyMap()
    val low = verses.minOf { numbering.kjvKeys(it.ref.key).first }
    val high = verses.maxOf { numbering.kjvKeys(it.ref.key).last }
    val kjv = highlightColors(VerseRange(VerseRef.fromKey(low), VerseRef.fromKey(high)))
    if (kjv.isEmpty()) return emptyMap()
    val result = HashMap<Int, String>()
    for (verse in verses) {
        numbering.kjvKeys(verse.ref.key).firstNotNullOfOrNull { kjv[it] }?.let { result[verse.ref.key] = it }
    }
    return result
}

/**
 * Highlight colors by KJV verse key for the verses in [range]. The snapshot holds merged ranges; the
 * newest wins where two overlap, as on the phone.
 */
fun VerseSnapshot?.highlightColors(range: VerseRange): Map<Int, String> {
    val result = HashMap<Int, String>()
    val items = this?.items(setOf(VerseSnapshot.Kind.HIGHLIGHT)).orEmpty().sortedBy { it.date }
    for (item in items) {
        val color = item.color ?: continue
        if (item.endKey < range.start.key || item.startKey > range.end.key) continue
        for (key in maxOf(item.startKey, range.start.key)..minOf(item.endKey, range.end.key)) result[key] = color
    }
    return result
}
