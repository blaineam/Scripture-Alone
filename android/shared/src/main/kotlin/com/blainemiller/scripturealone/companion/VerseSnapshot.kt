package com.blainemiller.scripturealone.companion

import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.daily.DailyVerseCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The compact, read-only picture of a reader's favorites, highlights and notes that the widgets and
 * the watch show. A port of `VerseSnapshot` in `ScriptureAloneCore/VerseSnapshot.swift`: the same
 * build rules, the same rotation and the same JSON (sorted keys, ISO-8601 dates to the second).
 *
 * Widgets never open the favorites store or a Bible database; the phone builds this whenever the
 * library or the translation changes, and the widgets — and, through the Data Layer, the watch — read it.
 *
 * One Android-only addition: an optional [Item.noteBody], so the watch can show a note's text as the
 * Apple Watch does (there it reads the synced notes store directly; here the snapshot is all it gets).
 * Absent, it is simply not written, so a snapshot without it is byte-identical to the Swift encoding.
 */
data class VerseSnapshot(
    val version: Int = CURRENT_VERSION,
    val generatedAt: Instant,
    /** Translation the texts are in (the reader's current one), e.g. "ASV". */
    val translation: String,
    /** Newest first within each kind. */
    val items: List<Item>,
) {
    enum class Kind(val raw: String) {
        FAVORITE("favorite"), HIGHLIGHT("highlight"), NOTE("note");

        companion object {
            fun of(raw: String): Kind? = entries.firstOrNull { it.raw == raw }
        }
    }

    data class Item(
        val kind: Kind,
        /** Verse range storage form, "43003016-43003018". */
        val range: String,
        val startKey: Int,
        val endKey: Int,
        /** "John 3:16–18" */
        val reference: String,
        /** Plain text of the range in the snapshot's translation, trimmed to a widget's needs. */
        val text: String,
        /** Highlight color name ("yellow", …) for highlights. */
        val color: String? = null,
        /** Note title (or its first passage when untitled) for notes. */
        val noteTitle: String? = null,
        val date: Instant,
        /** Android only: the note's body, trimmed, for the watch. */
        val noteBody: String? = null,
    ) {
        val id: String get() = "${kind.raw}:$range:${date.epochSecond}"
        val verseRange: VerseRange? get() = VerseRange.parse(range)

        companion object {
            fun of(
                kind: Kind, range: VerseRange, text: String, date: Instant,
                color: String? = null, noteTitle: String? = null, noteBody: String? = null,
            ) = Item(kind, range.storageString, range.start.key, range.end.key, range.display, text, color, noteTitle,
                date.truncatedTo(ChronoUnit.SECONDS), noteBody)
        }
    }

    fun items(kinds: Set<Kind>): List<Item> = items.filter { it.kind in kinds }

    // MARK: Encoding

    /** Sorted keys, so an unchanged library encodes to identical bytes, as `JSONEncoder(.sortedKeys)`. */
    fun encoded(): String {
        val root = sortedMapOf<String, JsonElement>(
            "generatedAt" to JsonPrimitive(iso(generatedAt)),
            "items" to kotlinx.serialization.json.JsonArray(items.map { item ->
                val fields = sortedMapOf<String, JsonElement>(
                    "date" to JsonPrimitive(iso(item.date)),
                    "endKey" to JsonPrimitive(item.endKey),
                    "kind" to JsonPrimitive(item.kind.raw),
                    "range" to JsonPrimitive(item.range),
                    "reference" to JsonPrimitive(item.reference),
                    "startKey" to JsonPrimitive(item.startKey),
                    "text" to JsonPrimitive(item.text),
                )
                item.color?.let { fields["color"] = JsonPrimitive(it) }
                item.noteTitle?.let { fields["noteTitle"] = JsonPrimitive(it) }
                item.noteBody?.let { fields["noteBody"] = JsonPrimitive(it) }
                JsonObject(fields)
            }),
            "translation" to JsonPrimitive(translation),
            "version" to JsonPrimitive(version),
        )
        return JsonObject(root).toString()
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val FILE_NAME = "VerseSnapshot.json"

        /** Longest text kept per item. A large widget shows ~300 characters; the rest is waste. */
        const val MAX_TEXT_LENGTH = 420

        /** Longest note body sent to the watch. */
        const val MAX_NOTE_BODY_LENGTH = 1_200

        /** Null for anything that isn't a snapshot this build understands — never a crash. */
        fun decode(json: String): VerseSnapshot? = try {
            val root = Json.parseToJsonElement(json).jsonObject
            VerseSnapshot(
                version = root["version"]!!.jsonPrimitive.int,
                generatedAt = Instant.parse(root["generatedAt"]!!.jsonPrimitive.content),
                translation = root["translation"]!!.jsonPrimitive.content,
                items = root["items"]!!.jsonArray.mapNotNull { element ->
                    val o = element.jsonObject
                    fun string(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    Item(
                        kind = Kind.of(string("kind") ?: return@mapNotNull null) ?: return@mapNotNull null,
                        range = string("range")!!,
                        startKey = o["startKey"]!!.jsonPrimitive.int,
                        endKey = o["endKey"]!!.jsonPrimitive.int,
                        reference = string("reference")!!,
                        text = string("text")!!,
                        color = string("color"),
                        noteTitle = string("noteTitle"),
                        date = Instant.parse(string("date")!!),
                        noteBody = string("noteBody"),
                    )
                },
            )
        } catch (e: RuntimeException) {
            // IllegalArgumentException (bad JSON), NullPointerException (a missing field),
            // DateTimeParseException — all mean "not a snapshot".
            null
        }

        private fun iso(instant: Instant): String =
            DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS))

        // MARK: Building

        data class FavoriteInput(val range: VerseRange, val date: Instant)
        data class HighlightInput(val verseKey: Int, val color: String, val date: Instant)
        data class NoteInput(val title: String, val anchors: List<VerseRange>, val date: Instant, val body: String = "")

        /**
         * Builds a snapshot, as Swift's `VerseSnapshot.build`. Highlights are stored one row per verse,
         * so neighboring verses of the same color collapse into one range (the newest verse's date wins;
         * the newest color wins when two devices colored one verse). Each note contributes its first
         * passage. At most [limitPerKind] of each kind are kept, newest first.
         */
        fun build(
            favorites: List<FavoriteInput>,
            highlights: List<HighlightInput>,
            notes: List<NoteInput>,
            translation: String,
            generatedAt: Instant,
            limitPerKind: Int = 60,
            verseCount: (book: Int, chapter: Int) -> Int,
            text: (VerseRange) -> String,
        ): VerseSnapshot {
            val items = mutableListOf<Item>()

            val seen = mutableSetOf<VerseRange>()
            for (favorite in favorites.sortedByDescending { it.date }) {
                if (!seen.add(favorite.range)) continue
                if (seen.size > limitPerKind) break
                items += Item.of(Kind.FAVORITE, favorite.range, trimmed(text(favorite.range)), favorite.date)
            }

            // Swift keeps the later of two rows with equal dates (`<=`); iterate in the same order.
            val newest = linkedMapOf<Int, HighlightInput>()
            for (highlight in highlights) {
                val current = newest[highlight.verseKey]
                if (current == null || current.date <= highlight.date) newest[highlight.verseKey] = highlight
            }
            data class Group(val range: VerseRange, val color: String, val date: Instant)
            val groups = mutableListOf<Group>()
            for ((color, rows) in newest.values.groupBy { it.color }) {
                val dates = rows.groupBy { it.verseKey }.mapValues { (_, r) -> r.maxOf { it.date } }
                for (range in ranges(dates.keys, verseCount)) {
                    val date = dates.filterKeys { range.contains(it) }.values.maxOrNull() ?: Instant.MIN
                    groups += Group(range, color, date)
                }
            }
            groups.sortWith(compareByDescending<Group> { it.date }.thenBy { it.range.start.key }.thenBy { it.range.end.key })
            for (group in groups.take(limitPerKind)) {
                items += Item.of(Kind.HIGHLIGHT, group.range, trimmed(text(group.range)), group.date, color = group.color)
            }

            for (note in notes.sortedByDescending { it.date }.take(limitPerKind)) {
                val first = note.anchors.minWithOrNull(compareBy<VerseRange> { it.start.key }.thenBy { it.end.key }) ?: continue
                val title = note.title.trim()
                val body = note.body.trim()
                items += Item.of(
                    Kind.NOTE, first, trimmed(text(first)), note.date,
                    noteTitle = title.ifEmpty { first.display },
                    noteBody = body.takeIf { it.isNotEmpty() }?.let { trimmed(it, MAX_NOTE_BODY_LENGTH) },
                )
            }

            return VerseSnapshot(generatedAt = generatedAt.truncatedTo(ChronoUnit.SECONDS), translation = translation, items = items)
        }

        /**
         * Consecutive verse keys as ranges — Swift's `VerseRange.ranges(from:verseCount:)`. The last
         * verse of a chapter runs on into verse 1 of the next.
         */
        fun ranges(keys: Collection<Int>, verseCount: (book: Int, chapter: Int) -> Int): List<VerseRange> {
            val refs = keys.mapNotNull { VerseRange.ref(it) }.sortedBy { it.key }
            val result = mutableListOf<VerseRange>()
            for (ref in refs) {
                val last = result.lastOrNull()
                if (last != null) {
                    val end = last.end
                    val sameChapter = end.book == ref.book && end.chapter == ref.chapter
                    val nextChapter = nextChapter(end.book, end.chapter)
                    val adjacent = (sameChapter && ref.verse == end.verse + 1) ||
                        (ref.verse == 1 && nextChapter == (ref.book to ref.chapter) && end.verse >= verseCount(end.book, end.chapter))
                    if (adjacent) {
                        result[result.size - 1] = VerseRange(last.start, ref)
                        continue
                    }
                }
                result += VerseRange(ref, ref)
            }
            return result
        }

        private fun nextChapter(book: Int, chapter: Int): Pair<Int, Int>? {
            val info = BookID.of(book) ?: return null
            return when {
                chapter < info.chapterCount -> book to chapter + 1
                book < BookID.entries.size -> book + 1 to 1
                else -> null
            }
        }

        /**
         * At most [limit] characters, cut at a word, trailing punctuation dropped and an ellipsis added —
         * Swift's `trimmed`. Counted in code points, so a cut never splits a surrogate pair.
         */
        fun trimmed(text: String, limit: Int = MAX_TEXT_LENGTH): String {
            if (text.codePointCount(0, text.length) <= limit) return text
            val cut = text.substring(0, text.offsetByCodePoints(0, limit))
            val space = cut.lastIndexOf(' ')
            val atWord = if (space >= 0) cut.substring(0, space) else cut
            return atWord.trim { it.isWhitespace() || it.isPunctuationMark() } + "…"
        }

        // MARK: Rotation

        /**
         * Which item a rotating widget shows: advances every [slotHours] through the local day and
         * continues day to day, plus a user nudge (the widget's "Next" button). Swift's `rotationIndex`.
         */
        fun rotationIndex(
            at: Instant, count: Int, slotHours: Int = 3, nudge: Int = 0, zone: ZoneId = ZoneId.systemDefault(),
        ): Int {
            if (count <= 0) return 0
            val local = at.atZone(zone)
            val day = DailyVerseCatalog.dayNumber(local.year, local.monthValue, local.dayOfMonth)
            val slotLength = maxOf(1, slotHours)
            val slotsPerDay = maxOf(1, 24 / slotLength)
            val slot = day.toLong() * slotsPerDay + local.hour / slotLength + nudge
            return (((slot % count) + count) % count).toInt()
        }

        /**
         * The next instant a rotating widget changes: the start of the next [slotHours]-aligned local
         * hour, or local midnight if that comes first. Where the iOS widget hands WidgetKit a day of
         * entries, an Android widget is redrawn at this instant.
         */
        fun nextSlot(after: Instant, slotHours: Int = 3, zone: ZoneId = ZoneId.systemDefault()): Instant {
            val midnight = DailyVerseCatalog.nextMidnight(after, zone)
            var hour = after.atZone(zone).truncatedTo(ChronoUnit.HOURS)
            while (true) {
                hour = hour.plusHours(1)
                val instant = hour.toInstant()
                if (!instant.isBefore(midnight)) return midnight
                if (hour.hour % maxOf(1, slotHours) == 0 && instant.isAfter(after)) return instant
            }
        }
    }
}

/** Foundation's `.punctuationCharacters`: the Unicode P* categories. */
internal fun Char.isPunctuationMark(): Boolean = when (Character.getType(this).toByte()) {
    Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
    Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
    Character.OTHER_PUNCTUATION -> true
    else -> false
}
