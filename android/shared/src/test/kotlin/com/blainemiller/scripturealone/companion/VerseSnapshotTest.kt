package com.blainemiller.scripturealone.companion

import com.blainemiller.scripturealone.companion.VerseSnapshot.Companion.FavoriteInput
import com.blainemiller.scripturealone.companion.VerseSnapshot.Companion.HighlightInput
import com.blainemiller.scripturealone.companion.VerseSnapshot.Companion.NoteInput
import com.blainemiller.scripturealone.companion.VerseSnapshot.Kind
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** A port of `VerseSnapshotTests` in `CompanionDataTests.swift`, case for case, plus Android's additions. */
class VerseSnapshotTest {

    private val now: Instant = Instant.ofEpochSecond(1_790_000_000)
    private fun ref(book: BookID, chapter: Int, verse: Int) = VerseRef(book.number, chapter, verse)
    private fun range(a: VerseRef, b: VerseRef = a) = VerseRange(a, b)

    private fun counts(book: Int, chapter: Int) = if (book == BookID.JOHN.number && chapter == 3) 36 else 30

    private fun snapshot(): VerseSnapshot {
        fun john3(v: Int) = ref(BookID.JOHN, 3, v).key
        return VerseSnapshot.build(
            favorites = listOf(
                FavoriteInput(range(ref(BookID.JOHN, 3, 16)), now.minusSeconds(60)),
                FavoriteInput(range(ref(BookID.PSALMS, 23, 1), ref(BookID.PSALMS, 23, 3)), now),
                FavoriteInput(range(ref(BookID.JOHN, 3, 16)), now.minusSeconds(600)), // duplicate
            ),
            highlights = listOf(
                HighlightInput(john3(16), "yellow", now.minusSeconds(100)),
                HighlightInput(john3(17), "yellow", now.minusSeconds(50)),
                HighlightInput(john3(18), "blue", now.minusSeconds(10)),
                // An older color on the same verse loses to the newer one.
                HighlightInput(john3(18), "green", now.minusSeconds(900)),
            ),
            notes = listOf(
                NoteInput("  ", listOf(range(ref(BookID.ROMANS, 8, 1), ref(BookID.ROMANS, 8, 17))), now),
                NoteInput("Sermon", listOf(range(ref(BookID.JOHN, 1, 1))), now.minusSeconds(5), body = "  In the beginning.  "),
                NoteInput("No passage", emptyList(), now),
            ),
            translation = "ASV",
            generatedAt = now,
            verseCount = ::counts,
            text = { r -> if (r.start.book == BookID.ROMANS.number) "word ".repeat(200) else "Text of ${r.display}" },
        )
    }

    @Test fun buildsOneItemPerRangeNewestFirst() {
        val snapshot = snapshot()
        val favorites = snapshot.items(setOf(Kind.FAVORITE))
        assertEquals(listOf("Psalms 23:1–3", "John 3:16"), favorites.map { it.reference })
        val highlights = snapshot.items(setOf(Kind.HIGHLIGHT))
        assertEquals(listOf("John 3:18", "John 3:16–17"), highlights.map { it.reference })
        assertEquals(listOf("blue", "yellow"), highlights.map { it.color })
        assertEquals(now.minusSeconds(50), highlights[1].date)
        val notes = snapshot.items(setOf(Kind.NOTE))
        assertEquals(listOf("Romans 8:1–17", "Sermon"), notes.map { it.noteTitle })
        assertTrue(notes[0].text.length <= VerseSnapshot.MAX_TEXT_LENGTH + 1 && notes[0].text.endsWith("…"))
        assertEquals(ref(BookID.ROMANS, 8, 1).key, notes[0].startKey)
        assertEquals(ref(BookID.ROMANS, 8, 17).key, notes[0].endKey)
        assertNull(notes[0].noteBody)
        assertEquals("In the beginning.", notes[1].noteBody)
    }

    @Test fun encodesAStableCompactFormat() {
        val snapshot = snapshot()
        val data = snapshot.encoded()
        val json = Json.parseToJsonElement(data).jsonObject
        assertEquals(setOf("version", "generatedAt", "translation", "items"), json.keys)
        assertEquals(1, json["version"]!!.jsonPrimitive.int)
        assertEquals("ASV", json["translation"]!!.jsonPrimitive.content)
        assertTrue(json["generatedAt"]!!.jsonPrimitive.content.endsWith("Z"))
        val first = json["items"]!!.jsonArray.first() as JsonObject
        assertEquals("favorite", first["kind"]!!.jsonPrimitive.content)
        assertEquals("19023001-19023003", first["range"]!!.jsonPrimitive.content)
        assertEquals(19_023_001, first["startKey"]!!.jsonPrimitive.int)
        assertEquals("Psalms 23:1–3", first["reference"]!!.jsonPrimitive.content)
        assertTrue(first["color"] == null && first["noteTitle"] == null && first["noteBody"] == null)
        assertEquals(snapshot, VerseSnapshot.decode(data))
        // Sorted keys make the file byte-stable, so an unchanged library rewrites identical bytes.
        assertEquals(data, snapshot.encoded())
        assertTrue("keys are sorted", data.startsWith("{\"generatedAt\":"))
        assertTrue(first.keys.toList() == first.keys.sorted())
    }

    /** A snapshot written by the Swift encoder decodes here — the Codable shape, not an imitation. */
    @Test fun decodesTheSwiftEncoding() {
        val swift = """{"generatedAt":"2026-09-21T12:00:00Z","items":[{"date":"2026-09-20T08:30:00Z","endKey":45008039,""" +
            """"kind":"favorite","range":"45008038-45008039","reference":"Romans 8:38–39","startKey":45008038,""" +
            """"text":"For I am persuaded"},{"color":"green","date":"2026-09-19T08:30:00Z","endKey":19023001,""" +
            """"kind":"highlight","range":"19023001-19023001","reference":"Psalms 23:1","startKey":19023001,""" +
            """"text":"Jehovah is my shepherd"}],"translation":"ASV","version":1}"""
        val snapshot = VerseSnapshot.decode(swift)!!
        assertEquals("ASV", snapshot.translation)
        assertEquals(listOf(Kind.FAVORITE, Kind.HIGHLIGHT), snapshot.items.map { it.kind })
        assertEquals(VerseRange(ref(BookID.ROMANS, 8, 38), ref(BookID.ROMANS, 8, 39)), snapshot.items[0].verseRange)
        assertEquals("green", snapshot.items[1].color)
        // Re-encoding gives the Swift bytes back.
        assertEquals(swift, snapshot.encoded())
    }

    /** The abbreviation and the days written ahead for an import travel as Swift's optional keys. */
    @Test fun encodesTheDailyPassagesAndAbbreviation() {
        val plain = snapshot()
        val snapshot = plain.copy(
            abbreviation = "CSB",
            daily = mapOf("43003016-43003016" to VerseSnapshot.DailyText("For God so loved", listOf(listOf(0, 3)))),
        )
        val data = snapshot.encoded()
        val json = Json.parseToJsonElement(data).jsonObject
        assertEquals("CSB", json["abbreviation"]!!.jsonPrimitive.content)
        val day = json["daily"]!!.jsonObject["43003016-43003016"]!!.jsonObject
        assertEquals(listOf("red", "text"), day.keys.toList())
        assertEquals(snapshot, VerseSnapshot.decode(data))
        assertEquals(listOf(0 to 3), VerseSnapshot.decode(data)!!.daily!!.values.single().redRanges)
        // Without them, nothing is written: the Swift bytes are unchanged.
        assertTrue("abbreviation" !in Json.parseToJsonElement(plain.encoded()).jsonObject.keys)
    }

    @Test fun malformedSnapshotsDecodeToNull() {
        assertNull(VerseSnapshot.decode(""))
        assertNull(VerseSnapshot.decode("[]"))
        assertNull(VerseSnapshot.decode("""{"version":1}"""))
        assertNull(VerseSnapshot.decode("""{"version":1,"generatedAt":"yesterday","translation":"ASV","items":[]}"""))
    }

    @Test fun rotationAdvancesBySlotAndNudge() {
        val utc = ZoneId.of("UTC")
        val morning = LocalDateTime.of(2026, 9, 18, 1, 0).atZone(utc).toInstant()
        val later = LocalDateTime.of(2026, 9, 18, 4, 0).atZone(utc).toInstant()
        val a = VerseSnapshot.rotationIndex(morning, 5, zone = utc)
        val b = VerseSnapshot.rotationIndex(later, 5, zone = utc)
        assertEquals((a + 1) % 5, b)
        assertEquals(b, VerseSnapshot.rotationIndex(morning, 5, nudge = 1, zone = utc))
        assertEquals(0, VerseSnapshot.rotationIndex(morning, 0, zone = utc))
        // The Swift arithmetic, worked by hand: day 9757 × 8 slots + slot 0 = 78056; 78056 mod 5 = 1.
        assertEquals(1, a)
    }

    @Test fun nextSlotIsTheNextThreeHourBoundaryOrMidnight() {
        val zone = ZoneId.of("America/Chicago")
        fun at(h: Int, m: Int) = LocalDateTime.of(2026, 9, 18, h, m).atZone(zone).toInstant()
        assertEquals(at(3, 0), VerseSnapshot.nextSlot(at(1, 15), zone = zone))
        assertEquals(at(6, 0), VerseSnapshot.nextSlot(at(3, 0), zone = zone))
        assertEquals(at(21, 0), VerseSnapshot.nextSlot(at(20, 59), zone = zone))
        assertEquals(LocalDateTime.of(2026, 9, 19, 0, 0).atZone(zone).toInstant(), VerseSnapshot.nextSlot(at(22, 30), zone = zone))
    }

    @Test fun consecutiveVersesJoinAcrossAChapterEnd() {
        val keys = listOf(ref(BookID.JOHN, 3, 36).key, ref(BookID.JOHN, 4, 1).key, ref(BookID.JOHN, 4, 3).key)
        val ranges = VerseSnapshot.ranges(keys, ::counts)
        assertEquals(listOf("John 3:36–4:1", "John 4:3"), ranges.map { it.display })
        // Verse 30 is not the end of John 3 (36 verses), so it doesn't run on into chapter 4.
        val gap = VerseSnapshot.ranges(listOf(ref(BookID.JOHN, 3, 30).key, ref(BookID.JOHN, 4, 1).key), ::counts)
        assertEquals(2, gap.size)
    }

    @Test fun trimmingCutsAtAWordAndDropsPunctuation() {
        assertEquals("short", VerseSnapshot.trimmed("short"))
        assertEquals("one two…", VerseSnapshot.trimmed("one two, three", limit = 9))
        // A surrogate pair at the cut is never split.
        val emoji = "a".repeat(8) + "😀" + " tail"
        assertTrue(VerseSnapshot.trimmed(emoji, limit = 9).let { it == "a".repeat(8) + "😀…" })
    }

    @Test fun favoritesStopAtTheLimit() {
        val many = (1..10).map { FavoriteInput(range(ref(BookID.PSALMS, 119, it)), now.minusSeconds(it.toLong())) }
        val snapshot = VerseSnapshot.build(many, emptyList(), emptyList(), "ASV", now, limitPerKind = 3,
            verseCount = ::counts, text = { "" })
        assertEquals(listOf("Psalms 119:1", "Psalms 119:2", "Psalms 119:3"), snapshot.items.map { it.reference })
    }
}
