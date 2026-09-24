package com.blainemiller.scripturealone.wear

import com.blainemiller.scripturealone.companion.VerseSnapshot
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.daily.DailyVerseCatalog
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** [EditionRows] over JDBC: the watch's reader, run against the `*-Watch.sqlite` files it ships. */
private class JdbcRows(file: File) : EditionRows {
    val connection: Connection = DriverManager.getConnection("jdbc:sqlite:${file.path}")

    override fun <T> query(sql: String, vararg args: Any, map: (EditionRows.Row) -> T): List<T> =
        connection.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeQuery().use { rs ->
                val row = object : EditionRows.Row {
                    override fun long(column: Int) = rs.getLong(column + 1)
                    override fun text(column: Int): String = rs.getString(column + 1)
                    override fun isNull(column: Int) = rs.getObject(column + 1) == null
                }
                buildList { while (rs.next()) add(map(row)) }
            }
        }
}

class WatchEditionTest {

    companion object {
        private val dir = File(System.getProperty("scripturealone.watchResources") ?: error("scripturealone.watchResources is not set"))
        private val rows = WatchBible.BUNDLED.associateWith { JdbcRows(File(dir, "$it-Watch.sqlite")) }
        private fun edition(id: String) = WatchEdition(id, rows.getValue(id))

        @AfterClass @JvmStatic fun close() = rows.values.forEach { it.connection.close() }
    }

    private fun ref(book: BookID, chapter: Int, verse: Int) = VerseRef(book.number, chapter, verse)
    private fun range(a: VerseRef, b: VerseRef = a) = VerseRange(a, b)

    @Test fun everyBundledEditionOpens() {
        assertEquals("American Standard Version", edition("ASV").name)
        for (id in WatchBible.BUNDLED) {
            val e = edition(id)
            assertEquals(id, e.meta["id"])
            assertEquals("watch", e.meta["edition"])
            assertTrue("$id has Genesis 1", e.verseCount(1, 1) == 31)
        }
    }

    @Test fun john316WithTheWordsOfChrist() {
        val verses = edition("ASV").verses(range(ref(BookID.JOHN, 3, 16)))
        assertEquals(1, verses.size)
        assertTrue(verses[0].text.startsWith("For God so loved the world"))
        // The whole verse is red in the ASV's red-letter edition.
        assertEquals(listOf(0 to verses[0].text.codePointCount(0, verses[0].text.length)), verses[0].red)
        val kjv = edition("KJV").text(range(ref(BookID.JOHN, 3, 16)))
        // The KJV keeps its traditional paragraph mark, as the Apple Watch shows it.
        assertTrue(kjv, kjv.startsWith("¶ For God so loved the world, that he gave his only begotten Son"))
    }

    @Test fun aChapterIsItsVersesInOrderWithoutTheHeading() {
        val asv = edition("ASV")
        val psalm = asv.chapterRange(BookID.PSALMS.number, 23)
        assertEquals(6, psalm.end.verse)
        val verses = asv.verses(VerseRange(ref(BookID.PSALMS, 23, 0), ref(BookID.PSALMS, 23, 6)))
        assertEquals((1..6).toList(), verses.map { it.ref.verse })
        assertTrue(verses[0].text.startsWith("Jehovah is my shepherd"))
        // An empty range beyond the chapter reads nothing rather than failing.
        assertEquals(0, asv.verseCount(BookID.PSALMS.number, 151))
    }

    @Test fun everyDailyVerseReadsFromEveryEdition() {
        val root = System.getProperty("scripturealone.watchResources")!!
        val catalog = DailyVerseCatalog.parse(File(root, "../../ScriptureAlone/Shared/DailyVerses.json").readText())
        for (id in WatchBible.BUNDLED) {
            val e = edition(id)
            for (verse in catalog.verses) {
                assertTrue("$id ${verse.ref}", e.verses(verse.range!!).isNotEmpty())
            }
        }
    }

    @Test fun malformedRedSpansAreSkipped() {
        assertEquals(listOf(0 to 5, 7 to 2), WatchEdition.parseRed("[[0,5],[7,2]]"))
        assertEquals(listOf(0 to 5), WatchEdition.parseRed("[[0,5],[3],[-1,2],[4,0],\"x\"]"))
        assertEquals(emptyList<Pair<Int, Int>>(), WatchEdition.parseRed("not json"))
    }
}

class WatchContentTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val catalog by lazy {
        val root = System.getProperty("scripturealone.watchResources")!!
        DailyVerseCatalog.parse(File(root, "../../ScriptureAlone/Shared/DailyVerses.json").readText())
    }

    @Test fun aWeekOfDaysEachFromMidnightToMidnight() {
        val now = LocalDateTime.of(2026, 9, 21, 15, 30).atZone(zone).toInstant()
        val week = WatchVerseOfDay.week(catalog, now, "BSB", zone = zone)
        assertEquals(7, week.size)
        assertEquals(now, week[0].first)
        assertEquals(LocalDateTime.of(2026, 9, 22, 0, 0).atZone(zone).toInstant(), week[0].second)
        for (i in 1 until week.size) assertEquals(week[i - 1].second, week[i].first)
        for ((start, _, verse) in week) {
            assertEquals(catalog.verse(start, zone), verse.verse)
            assertEquals("BSB", verse.translation)
        }
    }

    @Test fun complicationTextMatchesTheAccessoryViews() {
        val psalm = catalog.verses.first { it.ref == "19023001-19023001" }
        val entry = WatchVerseOfDay(psalm, "ASV")
        assertEquals("Ps" to "23:1", entry.shortTextLines)
        assertEquals("Ps 23:1 · Jehovah is my shepherd…", entry.inline)
        // A translation the list doesn't carry falls back to the ASV.
        val esv = WatchVerseOfDay.at(catalog, Instant.parse("2026-09-21T12:00:00Z"), "ESV", zone)!!
        assertEquals("ASV", esv.translation)
    }

    /** `VerseOfDayCard.text` (iOS 3d560f9): an import the phone sent reads from the watch's own edition. */
    @Test fun anImportTheWatchHoldsReadsFromItsOwnEdition() {
        val at = Instant.parse("2026-09-21T12:00:00Z")
        val today = catalog.verse(at, zone)!!
        val own = WatchVerseOfDay.at(catalog, at, "ESV", zone, ownText = { "¶ Own words for ${it.display}." })!!
        assertEquals("ESV", own.translation)
        assertEquals("Own words for ${today.range!!.display}.", own.text)
        assertEquals(own.text, WatchVerseOfDay.week(catalog, at, "ESV", zone = zone, ownText = { "¶ Own words for ${it.display}." })[0].third.text)
        // No text for it on the watch: the list's ASV.
        val none = WatchVerseOfDay.at(catalog, at, "ESV", zone, ownText = { "" })!!
        assertEquals("ASV", none.translation)
        assertEquals(today.text("ASV"), none.text)
        // A translation the list carries still reads from the list.
        val bsb = WatchVerseOfDay.at(catalog, at, "BSB", zone, ownText = { "edition" })!!
        assertEquals(today.text("BSB"), bsb.text)
        // Being read, it is shown even where the device's language has a Bible of its own.
        assertEquals("ESV", WatchVerseOfDay.translation("ESV", catalog.translations, listOf("fr-FR"), hasOwnText = true))
        assertEquals("LSG", WatchVerseOfDay.translation("ESV", catalog.translations, listOf("fr-FR")))
    }

    private val now = Instant.ofEpochSecond(1_790_000_000)
    private fun item(kind: VerseSnapshot.Kind, a: Int, b: Int, color: String? = null, date: Instant = now) =
        VerseSnapshot.Item(kind, "$a-$b", a, b, "", "", color, if (kind == VerseSnapshot.Kind.NOTE) "Note" else null, date)

    @Test fun notesAndHighlightsFindTheirVerses() {
        val snapshot = VerseSnapshot(
            generatedAt = now, translation = "ASV",
            items = listOf(
                item(VerseSnapshot.Kind.NOTE, 43003001, 43003021),
                item(VerseSnapshot.Kind.NOTE, 45008001, 45008017),
                item(VerseSnapshot.Kind.HIGHLIGHT, 43003016, 43003017, "yellow", now.minusSeconds(10)),
                item(VerseSnapshot.Kind.HIGHLIGHT, 43003017, 43003017, "blue", now),
            ),
        )
        val john316 = VerseRange(VerseRef.fromKey(43003016), VerseRef.fromKey(43003016))
        assertEquals(listOf("43003001-43003021"), snapshot.notesOn(john316).map { it.range })
        val chapter = VerseRange(VerseRef.fromKey(43003000), VerseRef.fromKey(43003999))
        assertEquals(mapOf(43003016 to "yellow", 43003017 to "blue"), snapshot.highlightColors(chapter))
        assertEquals(emptyMap<Int, String>(), (null as VerseSnapshot?).highlightColors(chapter))
    }

    @Test fun redLettersAreConvertedFromScalarsToUtf16() {
        // A non-BMP character before the red span: two UTF-16 units, one scalar.
        val verse = WatchVerse(VerseRef(43, 3, 16), "𝐀 said, Follow me.", listOf(8 to 10))
        val annotated = verseAnnotated(verse, numbered = true)
        assertEquals("16 𝐀 said, Follow me.", annotated.text)
        val span = annotated.spanStyles.last()
        assertEquals("Follow me.", annotated.text.substring(span.start, span.end))
    }
}
