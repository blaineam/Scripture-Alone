package com.blainemiller.scripturealone.wear

import com.blainemiller.scripturealone.companion.VerseSnapshot
import com.blainemiller.scripturealone.companion.VerseText
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.daily.DailyVerse
import com.blainemiller.scripturealone.data.daily.DailyVerseCatalog
import java.time.Instant
import java.time.ZoneId

/**
 * Today's passage as the watch shows it — in the app, the tile and the complication. In the watch's
 * translation when the list has it (the Apple Watch's complication always uses the ASV; here all three
 * follow the translation the reader reads on the watch), else the ASV.
 */
data class WatchVerseOfDay(val verse: DailyVerse, val translation: String) {
    val range: VerseRange? get() = verse.range
    val reference: String get() = range?.display.orEmpty()
    val shortReference: String get() = range?.abbreviatedDisplay.orEmpty()
    val text: String get() = verse.text(translation)

    /** The circular complication's two lines: "Ps" over "23:1" — `VerseAccessoryView.circular`. */
    val shortTextLines: Pair<String, String> get() = VerseText.split(shortReference)

    /** "Ps 23:1 · Jehovah is my shepherd; I…" — `accessoryInline`. */
    val inline: String get() = "$shortReference · ${VerseText.openingWords(text, maxWords = 4)}"

    companion object {
        fun at(catalog: DailyVerseCatalog?, instant: Instant, translation: String, zone: ZoneId = ZoneId.systemDefault()): WatchVerseOfDay? {
            val verse = catalog?.verse(instant, zone) ?: return null
            val shown = if (verse.text.containsKey(translation)) translation else DailyVerseCatalog.FALLBACK_TRANSLATION
            return WatchVerseOfDay(verse, shown)
        }

        /**
         * Today and the next [days] local days, each valid from one local midnight to the next — the
         * complication's timeline, as `VerseOfDayProvider.entries` gives WidgetKit a week.
         */
        fun week(
            catalog: DailyVerseCatalog?, from: Instant, translation: String, days: Int = 7, zone: ZoneId = ZoneId.systemDefault(),
        ): List<Triple<Instant, Instant, WatchVerseOfDay>> {
            val result = mutableListOf<Triple<Instant, Instant, WatchVerseOfDay>>()
            var start = from
            repeat(days) {
                val end = DailyVerseCatalog.nextMidnight(start, zone)
                at(catalog, start, translation, zone)?.let { result += Triple(start, end, it) }
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
 * Highlight colors by verse key for the verses in [range] — the chapter view's tints. The snapshot
 * holds merged ranges; the newest wins where two overlap, as on the phone.
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
