package com.blainemiller.scripturealone.data.reference

import com.blainemiller.scripturealone.data.canon.BookID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /** `slidesInEveryLanguageYieldTheirPassages` in LocalizedReferenceTests.swift. */
    @Test fun slidesInEveryLanguageYieldTheirPassages() {
        val cases = listOf(
            "Le bon berger\nJean 10:11–18  ·  Psaumes 23:1–6" to listOf(BookID.JOHN, BookID.PSALMS),
            "Der gute Hirte\nJohannes 10,11–18  ·  Psalm 23,1–6" to listOf(BookID.JOHN, BookID.PSALMS),
            "El buen pastor\nJuan 10:11–18  ·  Salmos 23:1–6" to listOf(BookID.JOHN, BookID.PSALMS),
            "好牧人\n约翰福音 10:11–18  ·  诗篇 23:1–6" to listOf(BookID.JOHN, BookID.PSALMS),
            "善き牧者\nヨハネ傳福音書 10:11–18  ·  詩篇 23:1–6" to listOf(BookID.JOHN, BookID.PSALMS),
            "선한 목자\n요한복음 10:11–18  ·  시편 23:1–6" to listOf(BookID.JOHN, BookID.PSALMS),
            "The Good Shepherd\nJohn 10:11–18  ·  Psalm 23:1–6" to listOf(BookID.JOHN, BookID.PSALMS),
        )
        for ((text, books) in cases) {
            assertEquals(text, books, ReferenceDetector.detect(text).map { it.passage.book })
        }
        // Ordinary words are not books: "Il connaît les siens par leur nom" names none.
        assertTrue(ReferenceDetector.detect("Il connaît les siens par leur nom").isEmpty())
    }
}
