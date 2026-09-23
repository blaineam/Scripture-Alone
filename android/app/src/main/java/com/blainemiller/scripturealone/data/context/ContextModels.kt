package com.blainemiller.scripturealone.data.context

import androidx.annotation.StringRes
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import com.blainemiller.scripturealone.text.AppText

// Value types for Study mode's context data (places, eras, events, charts), ported from
// `ScriptureAloneCore/ContextModels.swift`. Built by Tools/build_context.py; see
// docs/context-sources.md for where every value comes from.

enum class PlaceKind(val raw: String) {
    SETTLEMENT("settlement"), REGION("region"), ISLAND("island"), WATER("water"), MOUNTAIN("mountain"), SITE("site");

    companion object {
        /** As Swift's `PlaceKind(rawValue:) ?? .site`. */
        fun of(raw: String): PlaceKind = entries.firstOrNull { it.raw == raw } ?: SITE
        fun orNull(raw: String): PlaceKind? = entries.firstOrNull { it.raw == raw }
    }
}

/** A biblical place, geocoded by OpenBible.info. */
data class Place(
    val id: Int,
    /** OpenBible.info's ancient-place id (e.g. "a15257a" for Jerusalem). */
    val openBibleId: String,
    val name: String,
    /** The modern site it is identified with, when that differs from the name ("Konya" for Iconium). */
    val modernName: String,
    val kind: PlaceKind,
    /** OpenBible's type ("settlement", "river", "people group"…). */
    val type: String,
    val longitude: Double,
    val latitude: Double,
    /** Current scholarly confidence in the identification, 0–1000. */
    val confidence: Int,
    /** "point", "representative point", "center" or "settlement". */
    val precision: String,
    /** How many other identifications have been proposed. */
    val alternatives: Int,
    /** Verses that mention this place. */
    val mentions: Int,
    /** Coordinates derived from OpenStreetMap (attribution required, ODbL). */
    val fromOpenStreetMap: Boolean,
) {
    enum class Confidence(@StringRes private val titleRes: Int) {
        UNCERTAIN(R.string.context_confidence_uncertain), LIKELY(R.string.context_confidence_likely), IDENTIFIED(R.string.context_confidence_identified);

        val title: String get() = AppText.get(titleRes)
    }

    val confidenceLevel: Confidence
        get() = when {
            confidence >= 900 -> Confidence.IDENTIFIED
            confidence >= 500 -> Confidence.LIKELY
            else -> Confidence.UNCERTAIN
        }

    /** Regions, and places OpenBible can only pin to an area rather than a site. */
    val isArea: Boolean get() = kind == PlaceKind.REGION || precision == "representative point" || precision == "center"

    /** OpenBible.info's page for this place. */
    val sourceUrl: String get() = "https://www.openbible.info/geo/ancient/$openBibleId"
}

/** A place and the verses of one chapter that mention it. */
data class PlaceMention(val place: Place, val verses: List<Int>) {
    val id: Int get() = place.id
}

data class Era(
    val id: String,
    val order: Int,
    val name: String,
    val shortName: String,
    /** Negative years are BC. Null for the undated primeval era. */
    val start: Int?,
    val end: Int?,
    val dates: String,
    /** "#RRGGBB" */
    val color: String,
    val summary: String,
    /** Where the dates are approximate or disputed. */
    val debate: String,
)

/** When a chapter sits in the Bible's story. */
data class ChapterTime(
    /** Primary era first. */
    val eras: List<Era>,
    val year: Int?,
    val basis: Basis,
    val note: String?,
) {
    enum class Basis(val raw: String) {
        /** The year is when the chapter's events took place. */
        EVENTS("events"),
        /** The year is roughly when the book (or psalm, or letter) was written. */
        WRITTEN("written");

        companion object {
            fun of(raw: String): Basis? = entries.firstOrNull { it.raw == raw }
        }
    }

    val era: Era get() = eras[0]
    val yearLabel: String? get() = year?.let { ContextYear.label(it) }
}

object ContextYear {
    /** -1446 -> "c. 1446 BC", 30 -> "c. AD 30". */
    fun label(year: Int, approximate: Boolean = true): String = when {
        year < 0 && approximate -> AppText.get(R.string.context_year_approx_bc, -year)
        year < 0 -> AppText.get(R.string.context_year_bc, -year)
        approximate -> AppText.get(R.string.context_year_approx_ad, year)
        else -> AppText.get(R.string.context_year_ad, year)
    }
}

data class TimelineEvent(
    val id: Int,
    val eraId: String,
    val order: Int,
    val name: String,
    val year: Int?,
    /** Display date ("c. 1446 BC (or c. 1260 BC)"); null when undated. */
    val date: String?,
    val debated: Boolean,
    val range: VerseRange?,
)

enum class ChartKind(val raw: String) {
    KINGS("kings"), JOURNEYS("journeys"), TRIBES("tribes"), FEASTS("feasts");

    companion object {
        fun of(raw: String): ChartKind? = entries.firstOrNull { it.raw == raw }
    }
}

/** A chart's metadata and its undecoded body. Decode with [kings], [journeys], [tribes] or [feasts]. */
class ChartInfo(
    val id: String,
    val order: Int,
    val kind: ChartKind,
    val title: String,
    val subtitle: String,
    /** How the chart was compiled and what it cites. */
    val sources: String,
    /** Book numbers where the chart is suggested. */
    val scope: List<Int>,
    private val body: String,
) {
    fun kings(): KingsChart = ChartDecoding.kings(body)
    fun journeys(): JourneysChart = ChartDecoding.journeys(body)
    fun tribes(): TribesChart = ChartDecoding.tribes(body)
    fun feasts(): FeastsChart = ChartDecoding.feasts(body)
}

/** An authored water or landscape label on the base map. */
data class MapLabel(
    val text: String,
    val subtitle: String?,
    val longitude: Double,
    val latitude: Double,
    /** Hidden below this map scale (points per degree of latitude). */
    val minimumScale: Double,
    val kind: Kind,
    /** Degrees, counter-clockwise. */
    val angle: Double,
) {
    enum class Kind(val raw: String) {
        SEA("sea"), LAKE("lake"), RIVER("river"), LAND("land");

        companion object {
            fun of(raw: String): Kind = entries.firstOrNull { it.raw == raw } ?: LAND
        }
    }
}

// ---- Chart bodies ---------------------------------------------------------------------------------

data class KingsChart(val united: List<King>, val israel: List<King>, val judah: List<King>) {
    enum class Verdict(val raw: String) { GOOD("good"), EVIL("evil"), MIXED("mixed") }

    data class King(
        val name: String,
        val reign: String,
        val years: String,
        val verdict: Verdict,
        val ref: VerseRange,
        val note: String?,
        val prophets: List<String>?,
    ) {
        val id: String get() = name + reign
    }
}

data class JourneysChart(val journeys: List<Journey>) {
    data class Stop(
        val id: Int,
        val name: String,
        val lon: Double,
        val lat: Double,
        val kind: PlaceKind,
        val ref: VerseRange,
        val note: String?,
    )

    data class Journey(
        val id: String,
        val name: String,
        val dates: String,
        val refs: VerseRange,
        val color: String,
        val stops: List<Stop>,
    )
}

data class TribesChart(val tribes: List<Tribe>) {
    data class Tribe(
        val name: String,
        val order: Int,
        val mother: String,
        val birth: VerseRange,
        val jacob: VerseRange,
        val moses: VerseRange?,
        val allotment: VerseRange,
        val note: String?,
        val lon: Double?,
        val lat: Double?,
        val lon2: Double?,
        val lat2: Double?,
    )
}

data class FeastsChart(val feasts: List<Feast>) {
    data class Feast(
        val name: String,
        val hebrew: String,
        val date: String,
        val season: String,
        val refs: VerseRange,
        val also: VerseRange?,
        val meaning: String,
        val nt: VerseRange?,
        val ntText: String?,
        val pilgrim: Boolean?,
        val interpretive: Boolean?,
        val later: Boolean?,
        /**
         * "spring" or "autumn" for the feasts the chart groups by season, whatever language the season
         * text is in (`ContextStore.localizedBody`).
         */
        val seasonGroup: String? = null,
    )
}

/** Thrown where Swift's `JSONDecoder` would throw: a missing field, a wrong type, a bad reference. */
class ChartDecodingException(message: String) : Exception(message)

/**
 * The chart bodies, decoded as `JSONDecoder` decodes the Swift `Codable` structs: every non-optional
 * field required, a reference exactly `[startKey, endKey]` of real verses (in either order, as
 * `VerseRange(_:_:)` orders them), an unknown enum value an error.
 */
internal object ChartDecoding {
    fun kings(body: String): KingsChart {
        val root = obj(Json.parseToJsonElement(body), "kings chart")
        fun list(key: String) = array(root, key).map { element ->
            val king = obj(element, "king")
            val verdict = string(king, "verdict").let { raw ->
                KingsChart.Verdict.entries.firstOrNull { it.raw == raw } ?: fail("unknown verdict $raw")
            }
            KingsChart.King(
                name = string(king, "name"), reign = string(king, "reign"), years = string(king, "years"),
                verdict = verdict, ref = reference(king, "ref"), note = optionalString(king, "note"),
                prophets = (king["prophets"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: fail("prophet") },
            )
        }
        return KingsChart(list("united"), list("israel"), list("judah"))
    }

    fun journeys(body: String): JourneysChart {
        val root = obj(Json.parseToJsonElement(body), "journeys chart")
        return JourneysChart(array(root, "journeys").map { element ->
            val journey = obj(element, "journey")
            JourneysChart.Journey(
                id = string(journey, "id"), name = string(journey, "name"), dates = string(journey, "dates"),
                refs = reference(journey, "refs"), color = string(journey, "color"),
                stops = array(journey, "stops").map { s ->
                    val stop = obj(s, "stop")
                    JourneysChart.Stop(
                        id = int(stop, "id"), name = string(stop, "name"),
                        lon = double(stop, "lon"), lat = double(stop, "lat"),
                        kind = string(stop, "kind").let { PlaceKind.orNull(it) ?: fail("unknown kind $it") },
                        ref = reference(stop, "ref"), note = optionalString(stop, "note"),
                    )
                },
            )
        })
    }

    fun tribes(body: String): TribesChart {
        val root = obj(Json.parseToJsonElement(body), "tribes chart")
        return TribesChart(array(root, "tribes").map { element ->
            val tribe = obj(element, "tribe")
            TribesChart.Tribe(
                name = string(tribe, "name"), order = int(tribe, "order"), mother = string(tribe, "mother"),
                birth = reference(tribe, "birth"), jacob = reference(tribe, "jacob"),
                moses = optionalReference(tribe, "moses"), allotment = reference(tribe, "allotment"),
                note = optionalString(tribe, "note"),
                lon = optionalDouble(tribe, "lon"), lat = optionalDouble(tribe, "lat"),
                lon2 = optionalDouble(tribe, "lon2"), lat2 = optionalDouble(tribe, "lat2"),
            )
        })
    }

    fun feasts(body: String): FeastsChart {
        val root = obj(Json.parseToJsonElement(body), "feasts chart")
        return FeastsChart(array(root, "feasts").map { element ->
            val feast = obj(element, "feast")
            FeastsChart.Feast(
                name = string(feast, "name"), hebrew = string(feast, "hebrew"), date = string(feast, "date"),
                season = string(feast, "season"), refs = reference(feast, "refs"),
                also = optionalReference(feast, "also"), meaning = string(feast, "meaning"),
                nt = optionalReference(feast, "nt"), ntText = optionalString(feast, "ntText"),
                pilgrim = optionalBool(feast, "pilgrim"), interpretive = optionalBool(feast, "interpretive"),
                later = optionalBool(feast, "later"), seasonGroup = optionalString(feast, "seasonGroup"),
            )
        })
    }

    private fun fail(what: String): Nothing = throw ChartDecodingException(what)

    private fun obj(element: JsonElement?, what: String): JsonObject = element as? JsonObject ?: fail("$what is not an object")
    private fun array(o: JsonObject, key: String): JsonArray = o[key] as? JsonArray ?: fail("$key is missing")

    private fun string(o: JsonObject, key: String): String =
        (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail("$key is missing")

    private fun optionalString(o: JsonObject, key: String): String? = when (val v = o[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (v.isString) v.content else fail("$key is not a string")
        else -> fail("$key is not a string")
    }

    private fun int(o: JsonObject, key: String): Int =
        (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: fail("$key is missing")

    private fun double(o: JsonObject, key: String): Double =
        (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: fail("$key is missing")

    private fun optionalDouble(o: JsonObject, key: String): Double? = when (val v = o[key]) {
        null, JsonNull -> null
        else -> (v as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: fail("$key is not a number")
    }

    private fun optionalBool(o: JsonObject, key: String): Boolean? = when (val v = o[key]) {
        null, JsonNull -> null
        else -> (v as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: fail("$key is not a boolean")
    }

    /** `[startKey, endKey]`; both must name real verses. */
    private fun reference(o: JsonObject, key: String): VerseRange = optionalReference(o, key) ?: fail("$key is missing")

    private fun optionalReference(o: JsonObject, key: String): VerseRange? {
        val value = o[key]
        if (value == null || value == JsonNull) return null
        val keys = (value as? JsonArray)?.map { (it as? JsonPrimitive)?.intOrNull ?: fail("bad reference") } ?: fail("bad reference")
        if (keys.size != 2) fail("bad reference")
        val start = VerseRange.ref(keys[0]) ?: fail("bad reference")
        val end = VerseRange.ref(keys[1]) ?: fail("bad reference")
        return VerseRange.of(start, end)
    }
}

/** `ChapterRef.keyRange` in Swift: the whole chapter's keys, verse 0 included. */
val ChapterRef.keyRange: IntRange get() = VerseRef.chapterRange(book, chapter)

/** Whether a verse range touches the chapter. */
fun VerseRange.overlaps(chapter: ChapterRef): Boolean {
    val keys = chapter.keyRange
    return start.key <= keys.last && end.key >= keys.first
}
