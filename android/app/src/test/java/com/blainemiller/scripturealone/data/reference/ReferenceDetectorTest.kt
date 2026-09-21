package com.blainemiller.scripturealone.data.reference

import org.junit.Assert.assertEquals
import org.junit.Test

/** Ported from `ReferenceDetectorTests` in `ReferenceParserTests.swift`. */
class ReferenceDetectorTest {

    @Test fun findsReferencesOnASermonSlide() {
        val slide = """
            The Shepherd Who Pursues
            Psalm 23:1-6 · John 10:11
            Pastor Mark — Week 3 of "I Am"
            See also 1 Pet. 2:25 and Ezekiel 34
        """.trimIndent()
        assertEquals(
            listOf("Psalms 23:1–6", "John 10:11", "1 Peter 2:25", "Ezekiel 34"),
            ReferenceDetector.detect(slide).map { it.passage.display },
        )
    }

    @Test fun ignoresOrdinaryWords() {
        val text = "Is 5 people enough? Am 3 years old. We read the book of John 3:16 together."
        assertEquals(listOf("John 3:16"), ReferenceDetector.detect(text).map { it.passage.display })
    }

    /** The range must cover exactly the reference, so a caller can link or highlight it in place. */
    @Test fun rangesPointAtTheReferenceText() {
        val text = "Read Romans 8:28 today"
        val match = ReferenceDetector.detect(text).single()
        assertEquals("Romans 8:28", text.substring(match.range))
    }
}
