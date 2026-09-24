package com.blainemiller.scripturealone.data.importer

import com.blainemiller.scripturealone.data.canon.BookID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Carrying the words of Christ from a translation that marks them to one that doesn't. Ported from
 * `RedLetterInferenceTests.swift`. Public-domain text only.
 */
class RedLetterInferenceTest {
    private fun scalars(text: String): IntArray = text.codePoints().toArray()

    private fun spans(target: String, reference: String, red: String): List<String> {
        val start = reference.codePointCount(0, reference.lastIndexOf(red))
        val result = RedLetterInference.redSpans(target, reference, listOf(ScalarSpan(start, red.codePointCount(0, red.length))))
        val points = scalars(target)
        return result.map { String(points, it.start, it.length) }
    }

    @Test fun spokenWordsFollowTheirAlignment() {
        val reference = "Jesus saith unto him, I am the way, and the truth, and the life: no man cometh unto the Father, but by me."
        val red = "I am the way, and the truth, and the life: no man cometh unto the Father, but by me."
        val target = "Jesus said to him, “I am the way, and the truth, and the life. No one comes to the Father except through me."
        assertEquals(listOf("I am the way, and the truth, and the life. No one comes to the Father except through me."), spans(target, reference, red))
    }

    @Test fun narrativeStaysBlack() {
        assertTrue(spans("There was a man of the Pharisees, named Nicodemus.", "Jesus saith unto him, I am the way.", "I am the way.").isEmpty())
    }

    @Test fun anotherLanguageIsLeftAlone() {
        val reference = "Jesus saith unto him, I am the way, and the truth, and the life."
        val target = "Jésus lui dit: Je suis le chemin, la vérité, et la vie."
        assertTrue(spans(target, reference, "I am the way, and the truth, and the life.").isEmpty())
    }

    private fun extract(body: String): ExtractedBible =
        BibleTextExtractor().extract(listOf("jn.xhtml" to "<html><body>$body</body></html>"))

    @Test fun aFileWithItsOwnRedLettersIsNotTouched() {
        val bible = extract("""<section title="John"><p><span class="chapter-num">14</span> Jesus saith unto him, <span class="wj">I am the way.</span></p></section>""")
        val before = bible.verses[ref(BookID.JOHN, 14, 1)]?.red
        val marked = bible.inferRedLetters { "Jesus saith, I am the way." to listOf(ScalarSpan(13, 13)) }
        assertEquals(0, marked)
        assertEquals(before, bible.verses[ref(BookID.JOHN, 14, 1)]?.red)
        assertFalse(bible.redLettersInferred)
    }

    @Test fun versesAndLayoutBothGetTheWords() {
        val bible = extract("""<section title="John"><p><span class="chapter-num">14</span> Jesus saith unto him, I am the way.</p></section>""")
        val marked = bible.inferRedLetters { "Jesus saith unto him, I am the way." to listOf(ScalarSpan(22, 13)) }
        assertEquals(1, marked)
        assertTrue(bible.redLettersInferred)
        assertEquals(listOf(ScalarSpan(22, 13)), bible.verses[ref(BookID.JOHN, 14, 1)]?.red)
        val fragment = required(bible.blocks(ChapterRef(BookID.JOHN, 14)).flatMap { it.fragments }.firstOrNull())
        assertEquals(listOf(ScalarSpan(22, 13)), fragment.red)
    }

    @Test fun theStoreSaysTheRedLettersWereInferred() {
        val file = ImportFixtures.write(
            ImportFixtures.epub(
                listOf(ImportFixtures.Document("jn.xhtml", """<section title="John"><p><span class="chapter-num">14</span> Jesus saith unto him, I am the way.</p></section>""")),
            ),
            "jn.epub",
        )
        val result = ImportFixtures.importer().importBible(
            file, directory = ImportFixtures.scratchDirectory(),
            redLetters = { "Jesus saith unto him, I am the way." to listOf(ScalarSpan(22, 13)) },
        )
        ImportedStoreReader(result.storeFile).use { store ->
            assertEquals("inferred", store.meta["red_letters"])
            assertEquals(listOf(ScalarSpan(22, 13)), store.verses(ref(BookID.JOHN, 14, 1)).single().red)
        }
    }
}
