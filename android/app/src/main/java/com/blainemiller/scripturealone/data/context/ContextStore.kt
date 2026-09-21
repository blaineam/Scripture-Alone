package com.blainemiller.scripturealone.data.context

import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.blainemiller.scripturealone.data.sql.SqlRow
import com.blainemiller.scripturealone.data.sql.SqlSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
 */
class ContextStore(private val db: SqlSource) {

    @Volatile private var cachedEras: List<Era>? = null

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
        ) { row -> row.long(0).toInt() to place(row, 1) }.forEach { (verse, place) ->
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
        db.query("SELECT $PLACE_COLUMNS FROM places p WHERE p.id = ?1", id) { place(it, 0) }.firstOrNull()

    /** The most-mentioned places, for the overview map. */
    fun prominentPlaces(limit: Int = 400): List<Place> =
        db.query("SELECT $PLACE_COLUMNS FROM places p ORDER BY p.mentions DESC, p.name LIMIT ?1", limit) { place(it, 0) }

    /** Places whose name or modern name contains the text, most-mentioned first. */
    fun searchPlaces(text: String, limit: Int = 60): List<Place> {
        val term = text.trim()
        if (term.isEmpty()) return emptyList()
        val pattern = "%" + term.replace("%", "").replace("_", "") + "%"
        return db.query(
            "SELECT $PLACE_COLUMNS FROM places p WHERE p.name LIKE ?1 OR p.modern LIKE ?1 " +
                "ORDER BY (p.name LIKE ?2) DESC, p.mentions DESC LIMIT ?3",
            pattern, "$term%", limit,
        ) { place(it, 0) }
    }

    // ---- Timeline -------------------------------------------------------------------------------

    fun eras(): List<Era> = cachedEras ?: db.query(
        "SELECT id, ord, name, short, start, end, dates, color, summary, debate FROM eras ORDER BY ord",
    ) { r ->
        Era(
            id = r.text(0), order = r.long(1).toInt(), name = r.text(2), shortName = r.text(3),
            start = r.optionalInt(4), end = r.optionalInt(5), dates = r.text(6), color = r.text(7),
            summary = r.text(8), debate = r.text(9),
        )
    }.also { cachedEras = it }

    fun time(chapter: ChapterRef): ChapterTime? {
        val all = eras()
        data class Row(val era: String, val year: Int?, val basis: String, val note: String?)
        val rows = db.query(
            "SELECT era, year, basis, note FROM chapter_eras WHERE book = ?1 AND chapter = ?2 ORDER BY ord",
            chapter.book, chapter.chapter,
        ) { r -> Row(r.text(0), r.optionalInt(1), r.text(2), if (r.isNull(3)) null else r.text(3)) }
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
                id = r.long(0).toInt(), eraId = r.text(1), order = r.long(2).toInt(), name = r.text(3),
                year = r.optionalInt(4), date = if (r.isNull(5)) null else r.text(5),
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
                id = r.text(0), order = r.long(1).toInt(), kind = kind, title = r.text(3), subtitle = r.text(4),
                sources = r.text(5), scope = decodeScope(r.text(6)), body = r.text(7),
            )
        }.filterNotNull()

    /** Charts suggested while reading this book. */
    fun charts(book: Int): List<ChartInfo> = charts().filter { book in it.scope }

    fun labels(): List<MapLabel> =
        db.query("SELECT text, sub, lon, lat, min_scale, kind, angle FROM labels") { r ->
            MapLabel(
                text = r.text(0), subtitle = if (r.isNull(1)) null else r.text(1),
                longitude = r.double(2), latitude = r.double(3), minimumScale = r.double(4),
                kind = MapLabel.Kind.of(r.text(5)), angle = r.double(6),
            )
        }

    companion object {
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
