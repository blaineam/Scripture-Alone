package com.blainemiller.scripturealone.data.daily

import com.blainemiller.scripturealone.data.VerseRange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * One Verse of the Day passage with its text in every bundled translation, as
 * `Tools/build_companion_data.py` writes it into `DailyVerses.json`. A port of `DailyVerse` in
 * `ScriptureAloneCore/DailyVerses.swift`.
 */
data class DailyVerse(
    /** Verse range storage form, "43003016-43003016". */
    val ref: String,
    val theme: String,
    val text: Map<String, String>,
    /** Words of Christ per translation: [start, length] in Unicode scalars into [text]. */
    val red: Map<String, List<List<Int>>> = emptyMap(),
    /**
     * The theme in the big-8 languages, keyed "zh-Hans", "ja", "de", "fr", "es", "ko", "pt-BR", "it"
     * (`Tools/build_companion_data.py`). Empty in an older catalog.
     */
    val themes: Map<String, String> = emptyMap(),
) {
    val range: VerseRange? get() = VerseRange.parse(ref)

    /**
     * The theme for a BCP 47 language [tag] — `DailyVerse.theme(in:)`: an exact match ("pt-BR"), then
     * the language ("pt" for "pt-PT", "zh-Hans" for "zh-Hans-CN" or "zh-CN"), then English.
     * Traditional Chinese gets English, not the simplified theme.
     */
    fun theme(tag: String): String {
        if (themes.isEmpty()) return theme
        themes[tag]?.let { return it }
        val locale = Locale.forLanguageTag(tag)
        val code = locale.language
        if (code.isEmpty()) return theme
        if (code == "zh") {
            val traditional = locale.script == "Hant" || (locale.script.isEmpty() && locale.country in setOf("TW", "HK", "MO"))
            return if (traditional) theme else themes["zh-Hans"] ?: theme
        }
        return themes.entries.firstOrNull { it.key.substringBefore('-') == code }?.value ?: theme
    }

    /** The passage in [translation], falling back to the ASV, then any translation present. */
    fun text(translation: String): String =
        text[translation] ?: text[DailyVerseCatalog.FALLBACK_TRANSLATION] ?: text.values.sorted().firstOrNull() ?: ""

    /** Red-letter spans for [translation] as (start, length) in Unicode scalar offsets. */
    fun redRanges(translation: String): List<Pair<Int, Int>> {
        val key = if (text[translation] == null) DailyVerseCatalog.FALLBACK_TRANSLATION else translation
        return (red[key] ?: emptyList()).mapNotNull { pair ->
            if (pair.size != 2 || pair[0] < 0 || pair[1] <= 0) null else pair[0] to pair[1]
        }
    }
}

/**
 * The curated Verse of the Day list and the rule that picks a day's passage, ported from
 * `DailyVerseCatalog` in `ScriptureAloneCore/DailyVerses.swift`.
 *
 * The pick depends only on the local calendar date: every device — iPhone or Android — shows the same
 * passage on the same day, offline, with no server. Days are counted from 1 January 2000 and stepped
 * through the list by a stride coprime with its length, so each passage appears exactly once per
 * cycle and consecutive days jump around the canon. The arithmetic must stay identical to Swift's.
 */
data class DailyVerseCatalog(val version: Int, val translations: List<String>, val verses: List<DailyVerse>) {

    /** The passage for the local calendar day containing [instant] in [zone]. */
    fun verse(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): DailyVerse? =
        if (verses.isEmpty()) null else verses[index(instant, verses.size, zone)]

    companion object {
        const val FALLBACK_TRANSLATION = "ASV"
        const val ASSET_NAME = "DailyVerses.json"

        fun parse(json: String): DailyVerseCatalog {
            val root = Json.parseToJsonElement(json).jsonObject
            return DailyVerseCatalog(
                version = root["version"]?.jsonPrimitive?.intOrNull ?: 1,
                translations = root["translations"]!!.jsonArray.map { it.jsonPrimitive.content },
                verses = root["verses"]!!.jsonArray.map { element ->
                    val o = element.jsonObject
                    DailyVerse(
                        ref = o["ref"]!!.jsonPrimitive.content,
                        theme = o["theme"]!!.jsonPrimitive.content,
                        text = o["text"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
                        red = (o["red"] as? JsonObject)?.mapValues { (_, pairs) ->
                            (pairs as JsonArray).map { pair -> pair.jsonArray.map { it.jsonPrimitive.int } }
                        } ?: emptyMap(),
                        themes = (o["themes"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
                    )
                },
            )
        }

        /** Index into a list of [count] passages for the local calendar day containing [instant]. */
        fun index(instant: Instant, count: Int, zone: ZoneId = ZoneId.systemDefault()): Int {
            val date = instant.atZone(zone).toLocalDate()
            return index(dayNumber(date.year, date.monthValue, date.dayOfMonth), count)
        }

        /**
         * Index for a day number (days since 2000-01-01). A multiplicative step by a stride coprime
         * with [count] is a permutation of 0 until count.
         */
        fun index(day: Int, count: Int): Int {
            require(count > 0)
            val d = ((day % count) + count) % count
            return (d * stride(count)) % count
        }

        /**
         * The smallest step at or above ~0.382 × count (the golden-ratio complement, which scatters
         * neighbors well) that shares no factor with [count]. Swift's `rounded()` rounds half away from
         * zero; `Math.round` rounds half up — the same for these positive values.
         */
        fun stride(count: Int): Int {
            if (count <= 2) return 1
            var step = maxOf(1, Math.round(count.toDouble() * 0.382).toInt())
            while (gcd(step, count) != 1) step++
            return step
        }

        /**
         * Days from 2000-01-01 to a proleptic Gregorian date — pure arithmetic, so daylight saving and
         * time zones can't shift it (Howard Hinnant's days_from_civil, with Swift's truncating division).
         */
        fun dayNumber(year: Int, month: Int, day: Int): Int {
            val y = if (month <= 2) year - 1 else year
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val mp = (month + 9) % 12
            val doy = (153 * mp + 2) / 5 + day - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146_097 + doe - 719_468 - 10_957
        }

        /** The next local midnight strictly after [instant] — when widgets roll to the next passage. */
        fun nextMidnight(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): Instant {
            // atStartOfDay(zone), not midnight-at-zone: where a DST jump skips midnight, the day
            // starts at the first instant that exists, as Calendar.startOfDay has it.
            val today: LocalDate = instant.atZone(zone).toLocalDate()
            return today.plusDays(1).atStartOfDay(zone).toInstant()
        }

        private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    }
}
