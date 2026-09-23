package com.blainemiller.scripturealone.data.context

import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.blainemiller.scripturealone.data.sql.SqlRow
import com.blainemiller.scripturealone.data.sql.SqlSource
import com.blainemiller.scripturealone.text.AppLanguage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Read-only access to the bundled Study context database (`Context.sqlite`, built by
 * `Tools/build_context.py`): places, eras, events, charts and map labels. A port of
 * `ScriptureAloneCore/ContextStore.swift`, query for query.
 *
 * Reads go through [SqlSource], so the app runs this over the bundled SQLite driver and the tests
 * run the very same code over JDBC against the file the iOS app ships. The source serialises its
 * own connection, so one store can be used from any thread.
 *
 * **In the reader's language** (docs/localization.md): the `translations` table
 * (`Tools/translate_context.py`, one row per language, kind and English source) gives places, eras,
 * chapter notes, events, chart titles and bodies and map labels in [requestedLanguage] — the app's
 * language by default ([AppLanguage.studyLanguage]: null in English). A row that is missing leaves
 * the English, and a database without the table (built before the translations) is English
 * throughout.
 */
class ContextStore(private val db: SqlSource, val requestedLanguage: String? = AppLanguage.studyLanguage) {

    @Volatile private var cachedEras: List<Era>? = null

    // ---- Translation ----------------------------------------------------------------------------

    /**
     * The table's language for [requestedLanguage]: an exact match ("pt-BR"), else the language alone
     * ("fr" for "fr-CA") — but never Simplified Chinese rows for Traditional. Null in English, for an
     * untranslated language, or without the table.
     */
    val language: String? by lazy {
        val wanted = requestedLanguage ?: return@lazy null
        if (!hasTranslations) return@lazy null
        val available = db.query("SELECT DISTINCT lang FROM translations") { it.text(0) }
        val code = wanted.substringBefore('-')
        available.firstOrNull { it == wanted }
            ?: available.firstOrNull { it.substringBefore('-') == code && !(code == "zh" && "Hant" in wanted) }
    }

    private val hasTranslations: Boolean by lazy {
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'translations'") { it.text(0) }.isNotEmpty()
    }

    /** The rows for [language], by kind (`place`, `person`, `modern`, `string`), keyed by source. */
    private val translated: Map<String, Map<String, String>> by lazy {
        val lang = language ?: return@lazy emptyMap()
        val result = mutableMapOf<String, MutableMap<String, String>>()
        db.query("SELECT kind, source, text FROM translations WHERE lang = ?1", lang) { r ->
            Triple(r.text(0), r.text(1), r.text(2))
        }.forEach { (kind, source, text) -> result.getOrPut(kind) { mutableMapOf() }[source] = text }
        result
    }

    /** Prose in the reader's language, or the English it was given. */
    private fun tr(english: String): String = translated["string"]?.get(english) ?: english

    /** A person's name as the reader's Bible spells it (chart kings, tribes, prophets…). */
    private fun person(english: String): String = translated["person"]?.get(english) ?: tr(english)

    private fun localized(place: Place): Place {
        if (language == null) return place
        return place.copy(
            name = translated["place"]?.get(place.openBibleId) ?: place.name,
            modernName = if (place.modernName.isEmpty()) "" else translated["modern"]?.get(place.modernName) ?: place.modernName,
        )
    }

    /**
     * A chart's JSON body with its reader-facing text translated — the fields the translator takes
     * (`Tools/translate_context.py`), as `ContextStore.localizedBody` on iOS. Feasts get a
     * language-neutral `seasonGroup` first, since the chart groups them by season and the season text
     * is about to stop being English; and outside English their English New Testament quotation
     * (`ntText`) is dropped, so the chart shows that verse from the reader's own Bible instead.
     */
    internal fun localizedBody(body: String): String {
        val root = try {
            Json.parseToJsonElement(body)
        } catch (_: IllegalArgumentException) {
            return body
        }
        return walk(root, null, null).toString()
    }

    private fun walk(node: JsonElement, field: String?, siblings: Map<String, JsonElement>?): JsonElement {
        if (node is JsonObject) {
            val dict = node.toMutableMap()
            (dict["season"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { season ->
                val group = when {
                    season.startsWith("March") || season.startsWith("May") -> "spring"
                    season.startsWith("September") -> "autumn"
                    else -> null
                }
                if (group != null) dict["seasonGroup"] = JsonPrimitive(group) else dict.remove("seasonGroup")
            }
            if (language != null) dict.remove("ntText")
            val snapshot = dict.toMap()
            for ((key, value) in snapshot) if (key != "seasonGroup") dict[key] = walk(value, key, snapshot)
            return JsonObject(dict)
        }
        if (node is JsonArray) return JsonArray(node.map { walk(it, field, siblings) })
        val primitive = node as? JsonPrimitive
        if (primitive == null || !primitive.isString || field == null || language == null) return node
        val text = primitive.content
        val isPerson = field in PERSON_FIELDS ||
            (field == "name" && siblings != null && ("reign" in siblings || "birth" in siblings || "mother" in siblings))
        return when {
            isPerson -> JsonPrimitive(person(text))
            field in PROSE_FIELDS -> JsonPrimitive(tr(text))
            else -> node
        }
    }

    // ---- Places ---------------------------------------------------------------------------------

    /** Places the chapter mentions, in order of first mention, each with its verse keys. */
    fun places(chapter: ChapterRef): List<PlaceMention> {
        val keys = chapter.keyRange
        val order = mutableListOf<Int>()
        val places = mutableMapOf<Int, Place>()
        val verses = mutableMapOf<Int, MutableList<Int>>()
        db.query(
            "SELECT v.verse, $PLACE_COLUMNS FROM place_verses v JOIN places p ON p.id = v.place " +
                "WHERE v.verse BETWEEN ?1 AND ?2 ORDER BY v.verse, p.mentions DESC",
            keys.first, keys.last,
        ) { row -> row.long(0).toInt() to localized(place(row, 1)) }.forEach { (verse, place) ->
            if (place.id !in places) {
                places[place.id] = place
                order += place.id
            }
            verses.getOrPut(place.id) { mutableListOf() } += verse
        }
        return order.mapNotNull { id -> places[id]?.let { PlaceMention(it, verses[id].orEmpty()) } }
    }

    /** Every verse (key) that mentions the place, in canonical order. */
    fun versesMentioning(placeId: Int): List<Int> =
        db.query("SELECT verse FROM place_verses WHERE place = ?1 ORDER BY verse", placeId) { it.long(0).toInt() }

    fun place(id: Int): Place? =
        db.query("SELECT $PLACE_COLUMNS FROM places p WHERE p.id = ?1", id) { localized(place(it, 0)) }.firstOrNull()

    /** The most-mentioned places, for the overview map. */
    fun prominentPlaces(limit: Int = 400): List<Place> =
        db.query("SELECT $PLACE_COLUMNS FROM places p ORDER BY p.mentions DESC, p.name LIMIT ?1", limit) {
            localized(place(it, 0))
        }

    /**
     * Places whose name or modern name contains the text, most-mentioned first. In another language the
     * reader types the name their Bible uses ("Jérusalem", "耶路撒冷"), so the translated names match too.
     */
    fun searchPlaces(text: String, limit: Int = 60): List<Place> {
        val term = text.trim()
        if (term.isEmpty()) return emptyList()
        val pattern = "%" + term.replace("%", "").replace("_", "") + "%"
        val lang = language
        val translatedMatch = if (lang == null) "" else
            " OR p.obid IN (SELECT source FROM translations WHERE lang = ?4 AND kind = 'place' AND text LIKE ?1)"
        val args: Array<Any> = if (lang == null) arrayOf(pattern, "$term%", limit) else arrayOf(pattern, "$term%", limit, lang)
        return db.query(
            "SELECT $PLACE_COLUMNS FROM places p WHERE p.name LIKE ?1 OR p.modern LIKE ?1$translatedMatch " +
                "ORDER BY (p.name LIKE ?2) DESC, p.mentions DESC LIMIT ?3",
            *args,
        ) { localized(place(it, 0)) }
    }

    // ---- Timeline -------------------------------------------------------------------------------

    fun eras(): List<Era> = cachedEras ?: db.query(
        "SELECT id, ord, name, short, start, end, dates, color, summary, debate FROM eras ORDER BY ord",
    ) { r ->
        Era(
            id = r.text(0), order = r.long(1).toInt(), name = tr(r.text(2)), shortName = tr(r.text(3)),
            start = r.optionalInt(4), end = r.optionalInt(5), dates = tr(r.text(6)), color = r.text(7),
            summary = tr(r.text(8)), debate = tr(r.text(9)),
        )
    }.also { cachedEras = it }

    fun time(chapter: ChapterRef): ChapterTime? {
        val all = eras()
        data class Row(val era: String, val year: Int?, val basis: String, val note: String?)
        val rows = db.query(
            "SELECT era, year, basis, note FROM chapter_eras WHERE book = ?1 AND chapter = ?2 ORDER BY ord",
            chapter.book, chapter.chapter,
        ) { r -> Row(r.text(0), r.optionalInt(1), r.text(2), if (r.isNull(3)) null else tr(r.text(3))) }
        val first = rows.firstOrNull() ?: return null
        val eras = rows.mapNotNull { row -> all.firstOrNull { it.id == row.era } }
        if (eras.isEmpty()) return null
        return ChapterTime(eras, first.year, ChapterTime.Basis.of(first.basis) ?: ChapterTime.Basis.EVENTS, first.note)
    }

    /** All events in timeline order. */
    fun events(): List<TimelineEvent> = events("1 = 1")

    /** Events whose passage overlaps the chapter. */
    fun events(chapter: ChapterRef): List<TimelineEvent> {
        val keys = chapter.keyRange
        return events("start_key <= ?2 AND end_key >= ?1", keys.first, keys.last)
    }

    private fun events(clause: String, vararg args: Any): List<TimelineEvent> {
        val order = eras().associate { it.id to it.order }
        val events = db.query(
            "SELECT id, era, ord, name, year, date, debated, start_key, end_key FROM events WHERE $clause ORDER BY ord",
            *args,
        ) { r ->
            val start = r.optionalInt(7)?.let(VerseRange::ref)
            val end = r.optionalInt(8)?.let(VerseRange::ref)
            TimelineEvent(
                id = r.long(0).toInt(), eraId = r.text(1), order = r.long(2).toInt(), name = tr(r.text(3)),
                year = r.optionalInt(4), date = if (r.isNull(5)) null else tr(r.text(5)),
                debated = r.long(6) != 0L,
                range = if (start != null && end != null) VerseRange.of(start, end) else null,
            )
        }
        return events.sortedWith(compareBy({ order[it.eraId] ?: 0 }, { it.order }))
    }

    // ---- Charts and labels ----------------------------------------------------------------------

    fun charts(): List<ChartInfo> =
        db.query("SELECT id, ord, kind, title, subtitle, sources, scope, body FROM charts ORDER BY ord") { r ->
            val kind = ChartKind.of(r.text(2)) ?: return@query null
            ChartInfo(
                id = r.text(0), order = r.long(1).toInt(), kind = kind, title = tr(r.text(3)), subtitle = tr(r.text(4)),
                sources = tr(r.text(5)), scope = decodeScope(r.text(6)), body = localizedBody(r.text(7)),
            )
        }.filterNotNull()

    /** Charts suggested while reading this book. */
    fun charts(book: Int): List<ChartInfo> = charts().filter { book in it.scope }

    fun labels(): List<MapLabel> =
        db.query("SELECT text, sub, lon, lat, min_scale, kind, angle FROM labels") { r ->
            MapLabel(
                text = tr(r.text(0)), subtitle = if (r.isNull(1)) null else tr(r.text(1)),
                longitude = r.double(2), latitude = r.double(3), minimumScale = r.double(4),
                kind = MapLabel.Kind.of(r.text(5)), angle = r.double(6),
            )
        }

    companion object {
        /** Chart-body fields holding prose the translator takes — `localizedBody`'s `prose`. */
        private val PROSE_FIELDS = setOf(
            "date", "dates", "debate", "meaning", "note", "reign", "season", "short", "sources",
            "sub", "subtitle", "summary", "text", "title", "years", "name",
        )
        /** Fields holding people's names, translated as their Bible spells them. */
        private val PERSON_FIELDS = setOf("mother", "prophets")

        private const val PLACE_COLUMNS =
            "p.id, p.obid, p.name, p.modern, p.kind, p.type, p.lon, p.lat, p.confidence, p.precision, " +
                "p.alternatives, p.mentions, p.osm"

        private fun place(r: SqlRow, offset: Int) = Place(
            id = r.long(offset).toInt(),
            openBibleId = r.text(offset + 1),
            name = r.text(offset + 2),
            modernName = r.text(offset + 3),
            kind = PlaceKind.of(r.text(offset + 4)),
            type = r.text(offset + 5),
            longitude = r.double(offset + 6),
            latitude = r.double(offset + 7),
            confidence = r.long(offset + 8).toInt(),
            precision = r.text(offset + 9),
            alternatives = r.long(offset + 10).toInt(),
            mentions = r.long(offset + 11).toInt(),
            fromOpenStreetMap = r.long(offset + 12) != 0L,
        )

        private fun SqlRow.optionalInt(column: Int): Int? = if (isNull(column)) null else long(column).toInt()

        /** `[9, 10, 11]` — book numbers. A scope that doesn't decode suggests the chart nowhere, as in Swift. */
        internal fun decodeScope(json: String): List<Int> = try {
            (Json.parseToJsonElement(json) as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                ?.filter { it in 1..66 }
                .orEmpty()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }
}
