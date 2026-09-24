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

    @Test fun aQuotationTurnsRedWholeAndTheNarrationAroundItStaysBlack() {
        // A parable told across verses, worded unlike the reference in places ("Calling", "come"), and
        // a verse the reference renders too freely to line up at all.
        val bible = ExtractedBible()
        val chapter = ChapterRef(BookID.LUKE, 19)
        val texts = mapOf(
            12 to "He said therefore, “A nobleman went into a far country to receive for himself a kingdom and then return.",
            13 to "Calling ten of his servants, he gave them ten minas, and said to them, ‘Engage in business until I come.’",
            14 to "But his citizens hated him and sent a delegation after him, saying, ‘We do not want this man to reign over us.’”",
            15 to "When he returned, the people said, “Who is this man?”",
        )
        bible.append(
            ExtractedBlock(ExtractedBlock.Kind.PARAGRAPH, fragments = texts.keys.sorted().map { ExtractedFragment(it, true, texts.getValue(it)) }),
            chapter,
        )
        for ((verse, text) in texts) bible.appendVerseText(text, emptyList(), ref(BookID.LUKE, 19, verse))
        // (text, where the red begins; null = none)
        val reference = mapOf<Int, Pair<String, Int?>>(
            12 to ("So He said, “A man of noble birth went to a distant country to lay claim to his kingship and then return." to 12),
            13 to ("Beforehand, he called ten of his servants and gave them ten minas. ‘Conduct business with this until I return,’ he said." to 0),
            14 to ("Something entirely different that lines up with nothing here at all whatsoever." to 0),
            15 to ("And the crowd asked who this could possibly be." to null),
        )
        bible.inferRedLetters { verse ->
            val (text, start) = reference[verse.verse] ?: return@inferRedLetters null
            if (start == null) text to emptyList() else text to listOf(ScalarSpan(start, text.codePointCount(0, text.length) - start))
        }
        fun red(verse: Int): String {
            val points = scalars(texts.getValue(verse))
            return (bible.verses[ref(BookID.LUKE, 19, verse)]?.red ?: emptyList()).joinToString("|") { String(points, it.start, it.length) }
        }
        assertEquals("“A nobleman went into a far country to receive for himself a kingdom and then return.", red(12))
        assertEquals(texts.getValue(13), red(13)) // "Calling" and "come" too: they're inside the quotation
        assertEquals(texts.getValue(14), red(14)) // no alignment, but the whole quotation is His
        assertEquals("", red(15)) // someone else's question stays black
        val fragments = bible.blocks(chapter).flatMap { it.fragments }
        assertEquals(
            listOf(ScalarSpan(0, texts.getValue(13).codePointCount(0, texts.getValue(13).length))),
            fragments.firstOrNull { it.verse == 13 }?.red,
        )
    }

    @Test fun aBookWithoutQuotationMarksIsMatchedWordByWord() {
        val bible = ExtractedBible()
        val text = "Jesus saith unto him, I am the way, the truth, and the life."
        bible.append(ExtractedBlock(ExtractedBlock.Kind.PARAGRAPH, fragments = listOf(ExtractedFragment(6, true, text))), ChapterRef(BookID.JOHN, 14))
        bible.appendVerseText(text, emptyList(), ref(BookID.JOHN, 14, 6))
        val source = "Jesus answered, “I am the way and the truth and the life.”"
        bible.inferRedLetters { source to listOf(ScalarSpan(16, source.codePointCount(0, source.length) - 16)) }
        val red = bible.verses[ref(BookID.JOHN, 14, 6)]?.red ?: emptyList()
        assertEquals(1, red.size)
        assertEquals(22, red.first().start)
    }

    @Test fun aBookInAnotherLanguageIsLeftAlone() {
        // Quotation marks alone must never colour a text whose words can't be checked.
        val bible = ExtractedBible()
        val text = "Jesus sprach zu ihm: “Ich bin der Weg und die Wahrheit und das Leben.”"
        bible.append(ExtractedBlock(ExtractedBlock.Kind.PARAGRAPH, fragments = listOf(ExtractedFragment(6, true, text))), ChapterRef(BookID.JOHN, 14))
        bible.appendVerseText(text, emptyList(), ref(BookID.JOHN, 14, 6))
        val source = "Jesus answered, “I am the way and the truth and the life.”"
        val marked = bible.inferRedLetters { source to listOf(ScalarSpan(16, source.codePointCount(0, source.length) - 16)) }
        assertEquals(0, marked)
        assertTrue(bible.verses[ref(BookID.JOHN, 14, 6)]?.red?.isEmpty() == true)
    }
}
