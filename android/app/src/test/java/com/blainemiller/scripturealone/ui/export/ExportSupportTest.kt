package com.blainemiller.scripturealone.ui.export

import com.blainemiller.scripturealone.data.ChapterVerse
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.keepsake.KeepsakeNote
import com.blainemiller.scripturealone.data.rights.TranslationRights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ExportSupportTest {

    private fun range(a: VerseRef, b: VerseRef = a) = VerseRange.of(a, b)

    private fun note(title: String, anchors: List<VerseRange>, created: Long) =
        KeepsakeNote.of(title, "", anchors, Instant.ofEpochSecond(created), Instant.ofEpochSecond(created))

    /** Romans 8 as "v{n}" text, with a heading at verse 0 that must never be quoted or counted. */
    private val romans8: (VerseRange) -> List<ChapterVerse> = { r ->
        (0..39).map { ChapterVerse(VerseRef(45, 8, it), if (it == 0) "Heading" else "v$it", emptyList()) }
            .filter { r.contains(it.ref.key) || it.ref.verse == 0 }
    }

    @Test fun notesSortInBibleOrderThenUnanchoredOldestFirst() {
        val journalNew = note("journal new", emptyList(), 300)
        val journalOld = note("journal old", emptyList(), 100)
        val romans = note("romans", listOf(range(VerseRef(45, 8, 1))), 50)
        val genesis = note("genesis", listOf(range(VerseRef(1, 1, 1), VerseRef(1, 1, 3))), 400)
        val genesisShort = note("genesis short", listOf(range(VerseRef(1, 1, 1))), 500)
        val sorted = ExportSupport.canonicallySorted(listOf(journalNew, romans, genesis, journalOld, genesisShort))
        assertEquals(listOf("genesis short", "genesis", "romans", "journal old", "journal new"), sorted.map { it.title })
    }

    @Test fun oneVerseIsItsTextAndSeveralAreNumbered() {
        val text = ExportSupport.verseText(TranslationRights.PUBLIC_DOMAIN, romans8)
        assertEquals("v28", text(range(VerseRef(45, 8, 28))))
        assertEquals("1 v1 2 v2 3 v3", text(range(VerseRef(45, 8, 1), VerseRef(45, 8, 3))))
    }

    @Test fun longPassagesAreShortenedToTheirOpeningVerses() {
        val text = ExportSupport.verseText(TranslationRights.PUBLIC_DOMAIN, romans8)
        // Twenty verses are quoted whole; twenty-one become the first five and an ellipsis.
        assertEquals((1..20).joinToString(" ") { "$it v$it" }, text(range(VerseRef(45, 8, 1), VerseRef(45, 8, 20))))
        assertEquals("1 v1 2 v2 3 v3 4 v4 5 v5 …", text(range(VerseRef(45, 8, 1), VerseRef(45, 8, 21))))
    }

    @Test fun nothingIsQuotedWhenTheTermsForbidExport() {
        val closed = TranslationRights.LICENSED_DEFAULT.copy(allowNotesExport = false)
        assertNull(ExportSupport.verseText(closed, romans8)(range(VerseRef(45, 8, 28))))
    }

    @Test fun eachPassageMustBeWithinTheQuotationLimit() {
        val three = TranslationRights.LICENSED_DEFAULT.copy(maxQuotationVerses = 3)
        val text = ExportSupport.verseText(three, romans8)
        assertEquals("1 v1 2 v2 3 v3", text(range(VerseRef(45, 8, 1), VerseRef(45, 8, 3))))
        assertNull(text(range(VerseRef(45, 8, 1), VerseRef(45, 8, 4))))
        // A long passage is judged by what is actually quoted: its five opening verses.
        assertNull(text(range(VerseRef(45, 8, 1), VerseRef(45, 8, 30))))
        val five = TranslationRights.LICENSED_DEFAULT.copy(maxQuotationVerses = 5)
        assertEquals("1 v1 2 v2 3 v3 4 v4 5 v5 …", ExportSupport.verseText(five, romans8)(range(VerseRef(45, 8, 1), VerseRef(45, 8, 30))))
    }

    @Test fun anExpiredGrantQuotesNothing() {
        val lapsed = TranslationRights.PUBLIC_DOMAIN.copy(expires = Instant.parse("2020-01-01T00:00:00Z"))
        assertNull(ExportSupport.verseText(lapsed, romans8)(range(VerseRef(45, 8, 28))))
    }

    @Test fun missingTextIsNull() {
        assertNull(ExportSupport.verseText(TranslationRights.PUBLIC_DOMAIN) { emptyList() }(range(VerseRef(45, 8, 28))))
    }

    @Test fun safeNamesAndTitles() {
        assertEquals("Dad’s Notes", ExportSupport.safeName("Dad’s Notes"))
        assertEquals("a-b-c-d", ExportSupport.safeName("a/b:c\nd"))
        assertEquals("Export", ExportSupport.safeName("  "))
        assertEquals("Notes", ExportSupport.notesTitle(null))
        assertEquals("Dad’s Notes", ExportSupport.notesTitle(" Dad "))
        assertEquals("James’ Notes", ExportSupport.notesTitle("James"))
    }

    @Test fun aSingleNoteHasNoFolderFormat() {
        assertEquals(listOf(ExportFormat.PDF, ExportFormat.MARKDOWN, ExportFormat.PLAIN_TEXT), ExportFormat.available(1))
        assertEquals(ExportFormat.entries, ExportFormat.available(2))
    }
}
