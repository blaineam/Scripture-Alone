package com.blainemiller.scripturealone.data.daily

import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A port of `DailyVerseTests` in `CompanionDataTests.swift`, against the very `DailyVerses.json` the
 * iOS app ships. The pinned picks are the cross-platform promise: same date, same passage.
 */
class DailyVersesTest {

    private val catalog by lazy {
        val root = System.getProperty("scripturealone.resources") ?: error("scripturealone.resources is not set")
        DailyVerseCatalog.parse(File(root, "../Shared/DailyVerses.json").readText())
    }

    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0, zone: String) =
        LocalDateTime.of(y, m, d, h, min).atZone(ZoneId.of(zone)).toInstant()

    @Test fun dayNumbersAreCivilDays() {
        assertEquals(0, DailyVerseCatalog.dayNumber(2000, 1, 1))
        assertEquals(60, DailyVerseCatalog.dayNumber(2000, 3, 1)) // 2000 is a leap year
        assertEquals(366, DailyVerseCatalog.dayNumber(2001, 1, 1))
        assertEquals(-1, DailyVerseCatalog.dayNumber(1999, 12, 31))
        assertEquals(1, DailyVerseCatalog.dayNumber(2026, 9, 18) - DailyVerseCatalog.dayNumber(2026, 9, 17))
        // Far before the epoch the truncating division must still agree with the calendar.
        assertEquals(java.time.LocalDate.of(1600, 2, 29).toEpochDay() - java.time.LocalDate.of(2000, 1, 1).toEpochDay(),
            DailyVerseCatalog.dayNumber(1600, 2, 29).toLong())
    }

    @Test fun strideVisitsEveryPassageOncePerCycle() {
        for (count in listOf(1, 2, 7, 60, 364, 365, 366, 400)) {
            val start = DailyVerseCatalog.dayNumber(2026, 1, 1)
            val picks = (0 until count).map { DailyVerseCatalog.index(start + it, count) }
            assertEquals("count $count", count, picks.toSet().size)
            assertTrue(picks.all { it in 0 until count })
        }
    }

    @Test fun consecutiveDaysJumpAcrossTheList() {
        assertTrue(Math.abs(DailyVerseCatalog.index(9_757, 365) - DailyVerseCatalog.index(9_758, 365)) > 30)
    }

    @Test fun sameLocalDateSamePassageAnywhere() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val chicago = ZoneId.of("America/Chicago")
        val a = catalog.verse(at(2026, 9, 18, 0, 5, "Asia/Tokyo"), tokyo)
        val b = catalog.verse(at(2026, 9, 18, 23, 55, "America/Chicago"), chicago)
        assertNotNull(a)
        assertEquals(a, b)
    }

    @Test fun passageChangesAtLocalMidnight() {
        val chicago = ZoneId.of("America/Chicago")
        val before = at(2026, 11, 1, 23, 59, "America/Chicago") // DST ends that morning
        val midnight = DailyVerseCatalog.nextMidnight(before, chicago)
        assertEquals(at(2026, 11, 2, 0, 0, "America/Chicago"), midnight)
        assertNotEquals(catalog.verse(before, chicago), catalog.verse(midnight, chicago))
        assertEquals(catalog.verse(before, chicago), catalog.verse(midnight.minusSeconds(1), chicago))
        // Across a 25-hour day the midnight is still the calendar midnight.
        assertEquals(midnight, DailyVerseCatalog.nextMidnight(at(2026, 11, 1, 0, 30, "America/Chicago"), chicago))
    }

    @Test fun pinnedPicksMatchIos() {
        // The same pins as the Swift test: if these change, every user's Verse of the Day shifts.
        val utc = ZoneId.of("UTC")
        assertEquals(365, catalog.verses.size)
        assertEquals(139, DailyVerseCatalog.stride(365))
        assertEquals(9_757, DailyVerseCatalog.dayNumber(2026, 9, 18))
        assertEquals(248, DailyVerseCatalog.index(at(2026, 9, 18, zone = "UTC"), 365, utc))
        assertEquals("45008018-45008018", catalog.verse(at(2026, 9, 18, zone = "UTC"), utc)?.ref) // Romans 8:18
    }

    @Test fun catalogCoversTheCanonInEveryTranslation() {
        assertEquals(listOf("ASV", "BSB", "KJV"), catalog.translations)
        assertEquals(catalog.verses.size, catalog.verses.map { it.ref }.toSet().size)
        val books = mutableSetOf<BookID>()
        for (verse in catalog.verses) {
            val range = verse.range ?: error("bad ref ${verse.ref}")
            books += BookID.of(range.start.book)!!
            assertTrue(range.end.key - range.start.key < 3)
            for (id in catalog.translations) {
                val text = verse.text[id] ?: error("${verse.ref} missing $id")
                assertTrue(text.isNotEmpty() && !text.startsWith("¶"))
                val scalars = text.codePointCount(0, text.length)
                assertTrue(verse.redRanges(id).all { (start, length) -> start + length <= scalars })
            }
        }
        assertTrue(BookID.GENESIS in books && BookID.REVELATION in books)
        assertTrue(books.count { it.ordinal < 39 } >= 25)
        assertTrue(books.count { it.ordinal >= 39 } >= 20)
    }

    @Test fun redLettersAndFallbacks() {
        val way = catalog.verses.first { it.ref == "43014006-43014006" }
        val text = way.text("KJV")
        val (start, length) = way.redRanges("KJV").first()
        val from = text.offsetByCodePoints(0, start)
        assertTrue(text.substring(from, text.offsetByCodePoints(from, length)).contains("I am the way"))
        assertEquals(way.text("ASV"), way.text("NOPE"))
        assertTrue(catalog.verses.first { it.ref == "19023001-19023001" }.redRanges("BSB").isEmpty())
    }

    @Test fun abbreviatedReferences() {
        assertEquals("Ps 23:1", VerseRange.of(VerseRef(19, 23, 1)).abbreviatedDisplay)
        assertEquals("1 Cor 13:4–7", VerseRange.of(VerseRef(46, 13, 4), VerseRef(46, 13, 7)).abbreviatedDisplay)
        assertEquals("Gen 1:1–2:3", VerseRange.of(VerseRef(1, 1, 1), VerseRef(1, 2, 3)).abbreviatedDisplay)
        assertEquals("John 3:16", VerseRange.of(VerseRef(43, 3, 16)).abbreviatedDisplay)
    }
}
