package com.blainemiller.scripturealone.data.camera

import com.blainemiller.scripturealone.data.slides.SlideParser
import com.blainemiller.scripturealone.data.slides.SlideReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ML Kit's output → [com.blainemiller.scripturealone.data.slides.SlideLine]s → a note, against real
 * recognizer runs recorded on the emulator (`src/test/resources/slides/`, written by
 * [SlideRecognizer.read] in a debug build) from sermon slides drawn to match `SlideParserTests.swift`.
 * The expectations are what the Swift `SlideParser` reads from the same lines, mapped the same way —
 * checked by running these recordings through the Swift package on the Mac: all six agree.
 */
class SlideLineMapperTest {

    private fun page(name: String) = RecognizedPage.fromJson(javaClass.getResource("/slides/$name.json")!!.readText())

    private fun read(name: String): SlideReading = SlideParser.read(SlideLineMapper.lines(page(name)))

    private fun SlideReading.displays() = passages.map { it.display }

    @Test fun boxesAreNormalizedFromTheTopLeftWithTheLettersOwnHeight() {
        val lines = SlideLineMapper.lines(page("slide-title"))
        val title = lines.first { it.text == "THE SHEPHERD WHO" }
        // corners (290,306)…(1624,400) on a 1920×1080 slide
        assertEquals(290.0 / 1920, title.box.x, 1e-9)
        assertEquals(306.0 / 1080, title.box.y, 1e-9)
        assertEquals((1624.0 - 290) / 1920, title.box.width, 1e-9)
        assertEquals(94.0 / 1080, title.box.height, 1e-9)
        assertEquals(0.8663505, title.confidence, 1e-6)
    }

    @Test fun aTiltedLineIsMeasuredAlongItsSides() {
        val line = SlideLineMapper.lines(page("slide-title-photo")).first { it.text == "THE SHEPHERD WHO" }
        // corners (595,678) (1773,619) (1777,703) (599,762): the letters are ~84 px tall, while the
        // tilted box's extent is 143 px.
        val left = kotlin.math.hypot(4.0, 84.0)
        val right = kotlin.math.hypot(4.0, 84.0)
        assertEquals((left + right) / 2 / 1800, line.box.height, 1e-9)
        assertEquals(619.0 / 1800, line.box.y, 1e-9)
    }

    @Test fun confidenceIsTheRecognizersOrTrustedWhenAbsent() {
        assertEquals(1.0, SlideLineMapper.confidence(null), 0.0)
        assertEquals(1.0, SlideLineMapper.confidence(0f), 0.0)
        assertEquals(1.0, SlideLineMapper.confidence(Float.NaN), 0.0)
        assertEquals(0.25, SlideLineMapper.confidence(0.25f), 1e-9)
        // The page number "7" came back at 0.40 — above the parser's 0.3 floor, dropped as a number.
        assertTrue(SlideLineMapper.lines(page("slide-points")).any { it.text == "7" })
    }

    @Test fun malformedLinesAreSkipped() {
        val page = RecognizedPage(100, 100, listOf(RecognizedLine("   ", emptyList(), 0.9f), RecognizedLine("No corners", emptyList(), 0.9f)))
        assertEquals(emptyList<Any>(), SlideLineMapper.lines(page))
    }

    @Test fun recordingRoundTrips() {
        val original = page("slide-welcome")
        assertEquals(original, RecognizedPage.fromJson(original.toJson()))
    }

    @Test fun titleSlide() {
        val reading = read("slide-title")
        assertEquals("The Shepherd Who Pursues", reading.title)
        assertEquals(listOf("Psalms 23:1–6", "John 10:11"), reading.displays())
        // ML Kit reads the em dash and curly quotes as ASCII; Vision keeps them.
        assertEquals(listOf("Pastor Mark Ellis - Week 3 of \"I Am\""), reading.bodyLines)
    }

    @Test fun titleSlidePhotographedAtAnAngle() {
        val reading = read("slide-title-photo")
        assertEquals("The Shepherd Who Pursues", reading.title)
        assertEquals(listOf("Psalms 23:1–6", "John 10:11"), reading.displays())
        assertEquals(listOf("Pastor Mark Ellis -Week 3 of \"I Am\""), reading.bodyLines)
    }

    @Test fun pointsSlide() {
        val reading = read("slide-points")
        assertEquals("Three Marks of a Good Shepherd", reading.title)
        assertEquals(listOf("John 10:15"), reading.displays())
        assertEquals(
            listOf("The Shepherd Who Pursues", "1. He knows his sheep by name", "2. He lays down his life", "3. He goes after the one who wanders"),
            reading.bodyLines,
        )
    }

    @Test fun referenceBrokenOverTwoLines() {
        val reading = read("slide-two-line-ref")
        assertEquals("Love Is Patient", reading.title)
        assertEquals(listOf("1 Corinthians 13:4–7"), reading.displays())
        assertEquals(emptyList<String>(), reading.bodyLines)
    }

    @Test fun welcomeSlide() {
        val reading = read("slide-welcome")
        assertEquals("Fear Not", reading.title)
        assertEquals(listOf("Isaiah 41:10"), reading.displays())
        assertEquals(emptyList<String>(), reading.bodyLines)
    }

    /**
     * The emulator's live camera captures only 960×1280, with the slide small in the frame: the title
     * and the first passage survive, "10:11" loses its last digit to a gap ("10:1 1"), and the fine
     * print is misread. A phone's 12 MP capture doesn't have this problem; the record keeps it honest.
     */
    @Test fun lowResolutionLiveCapture() {
        val reading = read("live-capture")
        assertEquals("The Shepherd Who Pursues", reading.title)
        assertEquals(listOf("Psalms 23:1–6", "John 10:1"), reading.displays())
        assertEquals(listOf("Grace Community Chureh", "Pastor Mark Ellis - Weak 3 of \"I Am\"", "grecommuniy. chuh"), reading.bodyLines)
    }
}
