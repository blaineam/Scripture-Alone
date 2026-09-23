package com.blainemiller.scripturealone.data.reference

import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `OSISReferenceTests.swift`, case for case. */
class OSISReferenceTest {
    @Test fun codesCoverTheCanon() {
        assertEquals(BookID.entries.size, OSISReference.bookCodes.size)
        assertEquals(BookID.entries.size, OSISReference.bookCodes.map { it.lowercase() }.toSet().size)
        for (book in BookID.entries) {
            assertEquals(book, OSISReference.book(OSISReference.code(book)))
            // USFM codes too, and they never name a different book than OSIS does.
            assertEquals(book, OSISReference.book(book.code))
        }
    }

    @Test fun parses() {
        val cases = listOf(
            "John.3.16" to Passage.of(BookID.JOHN, 3, 16),
            "john.3.16" to Passage.of(BookID.JOHN, 3, 16),
            "JHN.3.16" to Passage.of(BookID.JOHN, 3, 16),
            "Gen.1.1-Gen.1.3" to Passage.of(BookID.GENESIS, 1, 1, 1, 3),
            "Ps.23" to Passage.of(BookID.PSALMS, 23),
            "1Cor.13.4-1Cor.13.7" to Passage.of(BookID.FIRST_CORINTHIANS, 13, 4, 13, 7),
            "Gen.1.1-Gen.2.3" to Passage.of(BookID.GENESIS, 1, 1, 2, 3),
            "John.3.16-18" to Passage.of(BookID.JOHN, 3, 16, 3, 18),
            "John.3.16-4.2" to Passage.of(BookID.JOHN, 3, 16, 4, 2),
            "John.3-4" to Passage.of(BookID.JOHN, 3, null, 4, null),
            "John.3-John.4" to Passage.of(BookID.JOHN, 3, null, 4, null),
            "Rom.8.38-Rom.9" to Passage.of(BookID.ROMANS, 8, 38, 9, null),
            "Jude.1.3" to Passage.of(BookID.JUDE, 1, 3),
            "Jude.3" to Passage.of(BookID.JUDE, 1, 3),
            "Obad.1" to Passage.of(BookID.OBADIAH, 1),
            "Gen" to Passage.of(BookID.GENESIS, 1),
            "urn:osis:John.3.16" to Passage.of(BookID.JOHN, 3, 16),
            "osis:Ps.23" to Passage.of(BookID.PSALMS, 23),
            "URN:OSIS:Rev.22.21" to Passage.of(BookID.REVELATION, 22, 21),
            "Bible.KJV:John.3.16" to Passage.of(BookID.JOHN, 3, 16),
            "John.3.16!a" to Passage.of(BookID.JOHN, 3, 16),
            "Mal.4.6-Matt.1.1" to Passage.of(BookID.MALACHI, 4, 6, 4, null),
        )
        for ((text, expected) in cases) assertEquals(text, listOf(expected), OSISReference.parse(text))
    }

    @Test fun parsesLists() {
        val passages = OSISReference.parse("John.3.16 Rom.8.28;Ps.23, Eph.2.8-Eph.2.9")!!
        assertEquals(listOf("John 3:16", "Romans 8:28", "Psalms 23", "Ephesians 2:8–9"), passages.map { it.display })
    }

    @Test fun rejects() {
        for (text in listOf(
            "", "John 3:16", "Ps 23", "Jn 3.16", "Rom 8:28-39", "nonsense", "John.3.x", "John.22.1", "John.3.16-3.15",
            "John.3.0", "Matt.1.1-Mal.4.6", "John.3.16.4", "Jean 3:16",
        )) {
            assertNull(text, OSISReference.parse(text))
        }
    }

    @Test fun formatsStoredRanges() {
        assertEquals("John.3.16", OSISReference.string(VerseRange.of(VerseRef(43, 3, 16))))
        assertEquals("1Cor.13.4-1Cor.13.7", OSISReference.string(VerseRange.of(VerseRef(46, 13, 4), VerseRef(46, 13, 7))))
        val range = VerseRange.of(VerseRef(1, 1, 1), VerseRef(1, 2, 3))
        val parsed = OSISReference.parse(OSISReference.string(range))?.first()!!
        assertEquals(range.start.key to range.end.key, parsed.range { _, _ -> 31 })
    }

    @Test fun wholeChaptersResolveAgainstVerseCounts() {
        val psalm = OSISReference.parse("Ps.23")!!.first()
        assertTrue(psalm.isWholeChapter)
        assertEquals(VerseRef(19, 23, 1).key to VerseRef(19, 23, 6).key, psalm.range { _, _ -> 6 })
    }
}
