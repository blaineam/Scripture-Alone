package com.blainemiller.scripturealone.data.slides

import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.reference.Passage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A port of `SlideParserTests.swift`. Fixtures are shaped like what a text recognizer returns for
 * photographed sermon slides: one line each, boxes normalized with the origin at the top-left.
 */
class SlideParserTest {

    private fun SlideReading.displays() = passages.map { it.display }

    private val titleSlide = listOf(
        SlideLine("Grace Community Church", 0.06, 0.05, 0.30, 0.035),
        SlideLine("THE SHEPHERD WHO", 0.18, 0.26, 0.64, 0.11),
        SlideLine("PURSUES", 0.34, 0.39, 0.32, 0.11),
        SlideLine("Psalm 23:1–6 · John 10:11", 0.30, 0.56, 0.40, 0.05),
        SlideLine("Pastor Mark Ellis — Week 3 of “I Am”", 0.28, 0.66, 0.44, 0.035),
        SlideLine("Sunday, September 14, 2026", 0.36, 0.88, 0.28, 0.03),
        SlideLine("gracecommunity.church", 0.70, 0.93, 0.24, 0.025),
    )

    @Test fun titleSlideBecomesANote() {
        val reading = SlideParser.read(titleSlide)
        assertEquals("The Shepherd Who Pursues", reading.title)
        assertEquals(listOf("Psalms 23:1–6", "John 10:11"), reading.displays())
        assertEquals(listOf("Pastor Mark Ellis — Week 3 of \"I Am\""), reading.bodyLines)
    }

    /** A three-character Chinese title is a whole title, not noise too short to keep. */
    @Test fun shortChineseTitleIsKept() {
        val reading = SlideParser.read(listOf(
            SlideLine("好牧人", 0.10, 0.20, 0.25, 0.10),
            SlideLine("他按着名叫自己的羊", 0.10, 0.45, 0.40, 0.04),
            SlideLine("好牧人为羊舍命", 0.10, 0.52, 0.34, 0.04),
            SlideLine("一群羊，一个牧人", 0.10, 0.59, 0.36, 0.04),
        ))
        assertEquals("好牧人", reading.title)
        assertTrue(reading.bodyLines.contains("好牧人为羊舍命"))
    }

    @Test fun pointSlideKeepsItsLinesInReadingOrder() {
        val reading = SlideParser.read(listOf(
            SlideLine("The Shepherd Who Pursues", 0.05, 0.04, 0.35, 0.03),
            SlideLine("Three Marks of a Good Shepherd", 0.10, 0.16, 0.80, 0.08),
            SlideLine("1. He knows his sheep by name", 0.12, 0.34, 0.60, 0.045),
            SlideLine("2. He lays down his life (Jn 10:15)", 0.12, 0.44, 0.66, 0.045),
            SlideLine("3. He goes after the one who wanders", 0.12, 0.54, 0.70, 0.045),
            SlideLine("vv. 4-7", 0.80, 0.54, 0.10, 0.045),
            SlideLine("7", 0.94, 0.94, 0.02, 0.03),
        ))
        assertEquals("Three Marks of a Good Shepherd", reading.title)
        assertEquals(listOf("John 10:15"), reading.displays())
        assertEquals(listOf(
            "The Shepherd Who Pursues",
            "1. He knows his sheep by name",
            "2. He lays down his life",
            "3. He goes after the one who wanders",
        ), reading.bodyLines)
    }

    @Test fun referenceSplitAcrossLinesIsFound() {
        val reading = SlideParser.read(listOf(
            SlideLine("Love Is Patient", 0.2, 0.2, 0.6, 0.12),
            SlideLine("1 Corinthians", 0.35, 0.45, 0.3, 0.05),
            SlideLine("13:4–7", 0.42, 0.51, 0.16, 0.05),
        ))
        assertEquals("Love Is Patient", reading.title)
        assertEquals(listOf("1 Corinthians 13:4–7"), reading.displays())
        assertTrue(reading.bodyLines.isEmpty())
    }

    @Test fun ocrQuirks() {
        val cases = listOf(
            "Jn 3:16" to "John 3:16",
            "John 3 : 16" to "John 3:16",
            "1 Cor 13:4–7" to "1 Corinthians 13:4–7",
            "1 Cor. 13:4—7" to "1 Corinthians 13:4–7",
            "Romans 8, vv. 1-17" to "Romans 8:1–17",
            "Romans 8 verses 28–39" to "Romans 8:28–39",
            "Text: Isaiah 40:28‑31" to "Isaiah 40:28–31",
            "Luke 15;4" to "Luke 15:4",
            "II Timothy 3:16-17" to "2 Timothy 3:16–17",
            "(n 10:3)" to "John 10:3",
            "In 3:16" to "John 3:16",
        )
        for ((input, expected) in cases) {
            val reading = SlideParser.read(listOf(
                SlideLine("A Title", 0.1, 0.1, 0.8, 0.1),
                SlideLine(input, 0.1, 0.4, 0.5, 0.05),
            ))
            assertEquals(input, listOf(expected), reading.displays())
            assertTrue("$input left ${reading.bodyLines}", reading.bodyLines.isEmpty())
        }
    }

    @Test fun titleSkipsBoilerplateEvenWhenItIsLarger() {
        val reading = SlideParser.read(listOf(
            SlideLine("WELCOME", 0.2, 0.08, 0.6, 0.16),
            SlideLine("First Baptist Church of Springfield", 0.2, 0.28, 0.6, 0.09),
            SlideLine("Fear Not", 0.3, 0.45, 0.4, 0.08),
            SlideLine("Isaiah 41:10", 0.35, 0.58, 0.3, 0.04),
            SlideLine("CCLI License #1234567", 0.05, 0.95, 0.3, 0.02),
            SlideLine("9/14/26 · 10:30 AM", 0.7, 0.95, 0.25, 0.02),
        ))
        assertEquals("Fear Not", reading.title)
        assertEquals(listOf("Isaiah 41:10"), reading.displays())
        assertTrue(reading.bodyLines.isEmpty())
    }

    @Test fun datesAreBoilerplate() {
        for (line in listOf("unday, September 14, 2026", "Sunday, September 14, 2026", "Easter Sunday · April 5",
                            "9/14/26 · 10:30 AM", "14 September 2026", "Sept. 14")) {
            val reading = SlideParser.read(listOf(
                SlideLine("Fear Not", 0.1, 0.2, 0.8, 0.1),
                SlideLine(line, 0.1, 0.9, 0.4, 0.03),
            ))
            assertTrue("$line was kept", reading.bodyLines.isEmpty())
        }
    }

    @Test fun sentencesMentioningADateStay() {
        val reading = SlideParser.read(listOf(
            SlideLine("Fear Not", 0.1, 0.2, 0.8, 0.1),
            SlideLine("Baptism class meets Sunday, October 5", 0.1, 0.6, 0.6, 0.04),
        ))
        assertEquals(listOf("Baptism class meets Sunday, October 5"), reading.bodyLines)
    }

    @Test fun titleAndPassageOnOneLine() {
        val reading = SlideParser.read(listOf(
            SlideLine("“Fear Not” — Isaiah 41:10", 0.1, 0.3, 0.8, 0.1),
            SlideLine("God is with you in the storm", 0.2, 0.5, 0.6, 0.05),
        ))
        assertEquals("Fear Not", reading.title)
        assertEquals(listOf("Isaiah 41:10"), reading.displays())
        assertEquals(listOf("God is with you in the storm"), reading.bodyLines)
    }

    @Test fun titlesLabeledOrAChurchWordInside() {
        val reading = SlideParser.read(listOf(
            SlideLine("Sermon: The Church That Prays", 0.1, 0.3, 0.8, 0.1),
            SlideLine("Acts 12:1-17", 0.3, 0.5, 0.4, 0.05),
        ))
        assertEquals("The Church That Prays", reading.title)
    }

    @Test fun dedupesPassagesAndRepeatedLines() {
        val reading = SlideParser.read(listOf(
            SlideLine("Living Hope", 0.1, 0.1, 0.8, 0.1),
            SlideLine("1 Peter 1:3-9", 0.1, 0.3, 0.4, 0.05),
            SlideLine("Born again to a living hope", 0.1, 0.4, 0.6, 0.05),
            SlideLine("born again to a living hope", 0.1, 0.5, 0.6, 0.05),
            SlideLine("1 Pet. 1:3–9", 0.1, 0.6, 0.4, 0.05),
            SlideLine("LIVING HOPE", 0.1, 0.9, 0.3, 0.03),
        ))
        assertEquals(listOf("1 Peter 1:3–9"), reading.displays())
        assertEquals(listOf("Born again to a living hope"), reading.bodyLines)
    }

    @Test fun lowConfidenceAndTinyFragmentsAreDropped() {
        val reading = SlideParser.read(listOf(
            SlideLine("Rest for the Weary", 0.1, 0.2, 0.8, 0.1),
            SlideLine("Matthew 11:28-30", 0.3, 0.4, 0.4, 0.05),
            SlideLine("~ ~", 0.5, 0.6, 0.05, 0.03),
            SlideLine("Ok", 0.5, 0.7, 0.05, 0.03),
            SlideLine("zqxv wmmrr", 0.5, 0.8, 0.2, 0.03, confidence = 0.1),
            SlideLine("12 / 40", 0.9, 0.95, 0.05, 0.02),
        ))
        assertEquals("Rest for the Weary", reading.title)
        assertTrue(reading.bodyLines.isEmpty())
    }

    @Test fun plainTextFallback() {
        val reading = SlideParser.read("Welcome\nThe Good Shepherd\nJohn 10:1-18\nWe are known")
        assertEquals("The Good Shepherd", reading.title)
        assertEquals(listOf("John 10:1–18"), reading.displays())
        assertEquals(listOf("We are known"), reading.bodyLines)
    }

    @Test fun rangesClampToRealVerseCounts() {
        val counts = mapOf(
            (BookID.PSALMS to 23) to 6, (BookID.JOHN to 3) to 36,
            (BookID.GENESIS to 1) to 31, (BookID.GENESIS to 2) to 25,
        )
        val passages = listOf(
            Passage.of(BookID.PSALMS, 23, 1, 23, 66),
            Passage.of(BookID.PSALMS, 23),
            Passage.of(BookID.JOHN, 3, 16),
            Passage.of(BookID.JOHN, 3, 16),
            Passage.of(BookID.GENESIS, 1, 1, 2, 3),
        )
        val ranges = SlideParser.ranges(passages) { book, chapter -> counts[book to chapter] ?: 0 }
        assertEquals(listOf("Psalms 23:1–6", "John 3:16", "Genesis 1:1–2:3"), ranges.map { it.display })
    }

    @Test fun appendingSkipsWhatTheNoteAlreadySays() {
        val body = SlideParser.body(listOf("Pastor Mark Ellis"))
        assertEquals("• Pastor Mark Ellis", body)
        val next = SlideParser.append("Three Marks of a Good Shepherd",
            listOf("Pastor Mark Ellis", "He knows his sheep", "He knows his sheep"), body)
        assertEquals("• Pastor Mark Ellis\n\nThree Marks of a Good Shepherd\n• He knows his sheep", next)
        assertEquals(next, SlideParser.append(null, listOf("He knows his sheep"), next))
        assertEquals("• First line", SlideParser.append(null, listOf("First line"), ""))
        assertEquals(body, SlideParser.append(null, listOf("THE SHEPHERD WHO PURSUES"), body, title = "The Shepherd Who Pursues"))
        assertTrue(SlideParser.noteAlreadyHas("The Shepherd Who Pursues", "The Shepherd Who Pursues", ""))
        assertTrue(SlideParser.noteAlreadyHas("pastor mark ellis", "", body))
        assertFalse(SlideParser.noteAlreadyHas("He knows his sheep", "", body))
    }

    @Test fun ordinaryInIsNotJohn() {
        val reading = SlideParser.read("Grace Abounds\nIn 3 ways God meets us")
        assertTrue(reading.passages.isEmpty())
        assertEquals(listOf("In 3 ways God meets us"), reading.bodyLines)
    }

    @Test fun mergingDropsCoveredRanges() {
        val verse = VerseRange.of(VerseRef(43, 10, 11))
        val span = VerseRange.of(VerseRef(43, 10, 11), VerseRef(43, 10, 15))
        val psalm = VerseRange.of(VerseRef(19, 23, 1), VerseRef(19, 23, 6))
        assertEquals(listOf(psalm, span), SlideParser.merging(listOf(psalm, verse), listOf(span, psalm)))
    }

    @Test fun readingOrderGroupsRows() {
        val ordered = SlideParser.readingOrder(listOf(
            SlideLine("right", 0.6, 0.301, 0.3, 0.05),
            SlideLine("below", 0.1, 0.5, 0.3, 0.05),
            SlideLine("left", 0.1, 0.3, 0.3, 0.05),
        ))
        assertEquals(listOf("left", "right", "below"), ordered.map { it.text })
    }

    @Test fun accentedRepeatsFoldTogether() {
        val reading = SlideParser.read("Café Talk\nNaïve faith grows\nNAIVE FAITH GROWS")
        assertEquals(listOf("Naïve faith grows"), reading.bodyLines)
    }
}
