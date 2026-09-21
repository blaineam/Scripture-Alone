package com.blainemiller.scripturealone.data.camera

import com.blainemiller.scripturealone.data.slides.SlideParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The recognizer-side stand-in for Vision's custom words: misread book names mended, prose left alone. */
class BookNameRepairTest {

    private fun repaired(text: String) = BookNameRepair.repair(text)

    @Test fun nearMissesOfBookNamesBecomeTheBook() {
        assertEquals("Ephesians 2:8-9", repaired("Ephesains 2:8-9"))
        assertEquals("Philippians 4:13", repaired("Philipians 4:13"))
        assertEquals("Psalm 23:1–6", repaired("Psaim 23:1–6"))
        assertEquals("Romans 8:28", repaired("Rornans 8:28"))
        assertEquals("Galatians 5:22", repaired("Galatlans 5:22"))
        assertEquals("REVELATION 21:4", repaired("REVELATlON 21:4"))
    }

    @Test fun ordinalsAndNumbersReadAsLetters() {
        assertEquals("1 Corinthians 13:4–7", repaired("l Corinthians 13:4–7"))
        assertEquals("2 Timothy 3:16", repaired("|| Timothy 3:16"))
        assertEquals("John 10:11", repaired("John l0:ll"))
        assertEquals("Psalm 23:1–6", repaired("Psalm 23:l–6"))
        // A Roman numeral the reference parser already reads is left as printed.
        assertEquals("I Corinthians 13:4", repaired("I Corinthians 13:4"))
    }

    @Test fun proseAndTimesAreLeftAlone() {
        for (text in listOf(
            "Pastor Mark Ellis — Week 3 of \"I Am\"", "Service at 10:30 AM", "June 9:30 AM", "Step O: 1",
            "Sunday, September 14, 2026", "Doors open 9:15", "He knows his sheep by name",
        )) assertEquals(text, repaired(text))
    }

    @Test fun ambiguousOrDistantWordsAreNotGuessed() {
        assertNull(BookNameRepair.nearest("Mark")) // exact
        assertNull(BookNameRepair.nearest("Sunday"))
        assertNull(BookNameRepair.nearest("Jom")) // too short to guess
    }

    @Test fun aRepairedSlideParsesToItsPassages() {
        val reading = SlideParser.read(
            SlideLineMapper.lines(
                RecognizedPage(
                    1000, 1000,
                    listOf(
                        line("Saved by Grace", 100, 100, 800, 120, 0.95f),
                        line("Ephesains 2:8-9 · l Corinthians l5:3", 200, 400, 600, 50, 0.8f),
                    ),
                ),
            ),
        )
        assertEquals("Saved by Grace", reading.title)
        assertEquals(listOf("Ephesians 2:8–9", "1 Corinthians 15:3"), reading.passages.map { it.display })
        assertEquals(emptyList<String>(), reading.bodyLines)
    }

    private fun line(text: String, x: Int, y: Int, w: Int, h: Int, confidence: Float?) = RecognizedLine(
        text,
        listOf(x to y, x + w to y, x + w to y + h, x to y + h).map { (px, py) -> RecognizedLine.Point(px.toFloat(), py.toFloat()) },
        confidence,
    )
}
