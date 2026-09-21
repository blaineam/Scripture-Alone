package com.blainemiller.scripturealone.data.notesimport

import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A port of `PastedNotesImportTests.swift`: notes pasted or exported from somewhere this app has
 * never seen. Every fixture is a *shape*, not a vendor — a reference and some text, however they are
 * arranged, and nothing guessed when there is no reference to be found.
 */
class PastedNotesImportTest {

    private fun ref(book: BookID, chapter: Int, verse: Int) = VerseRef(book.number, chapter, verse)

    private fun lines(vararg lines: String) = lines.joinToString("\n")

    private fun assertNothingRecognised(text: String) {
        try {
            PastedNotesImport.parse(text)
            fail("expected NOTHING_RECOGNISED for ${text.take(40)}")
        } catch (e: NoteImportException) {
            assertEquals(NoteImportError.NOTHING_RECOGNISED, e.error)
        }
    }

    // MARK: Tables

    @Test fun aTableWithAHeaderFindsItsReferenceAndTextColumns() {
        val csv = lines(
            "Reference,Note,Created",
            "John 3:16,\"For God so loved the world — the whole gospel in one verse\",2024-01-05",
            "Romans 8:28,\"All things work together for good, to them that love God\",2024-02-11",
        )
        val result = PastedNotesImport.parse(csv)
        assertEquals(2, result.verseNotes.size)
        assertEquals(ref(BookID.JOHN, 3, 16), result.verseNotes[0].range?.start)
        assertTrue(result.verseNotes[0].body.startsWith("For God so loved"))
        assertEquals(ref(BookID.ROMANS, 8, 28), result.verseNotes[1].range?.start)
        // The header names no verse, and is not worth reporting as a failure.
        assertTrue(result.unresolved.isEmpty())
    }

    /** The commas inside a quoted note must not become columns. */
    @Test fun quotedCommasStayInsideTheNote() {
        val result = PastedNotesImport.parse(lines(
            "Reference,Note",
            "Psalms 23:1,\"He leads me, he restores me, he is enough\"",
            "Psalms 23:4,\"Though I walk, I fear no evil\"",
        ))
        assertEquals(2, result.verseNotes.size)
        assertEquals("He leads me, he restores me, he is enough", result.verseNotes[0].body)
    }

    @Test fun aColourColumnBecomesHighlights() {
        val result = PastedNotesImport.parse(lines(
            "Verse,Colour",
            "John 1:1,blue",
            "John 1:2,yellow",
            "John 1:3,#b3e487",
        ))
        assertEquals(3, result.highlights.size)
        assertEquals("blue", result.highlights[0].color)
        assertEquals("yellow", result.highlights[1].color)
        assertEquals("green", result.highlights[2].color)
        assertTrue(result.verseNotes.isEmpty())
    }

    @Test fun tabSeparatedWorksToo() {
        val result = PastedNotesImport.parse("John 3:16\tGod's love\nJohn 3:17\tNot to condemn")
        assertEquals(2, result.verseNotes.size)
        assertEquals("Not to condemn", result.verseNotes[1].body)
    }

    // MARK: Prose

    @Test fun blocksSeparatedByBlankLinesEachBecomeANote() {
        val result = PastedNotesImport.parse(lines(
            "John 3:16",
            "The whole gospel in one verse.",
            "Worth memorising.",
            "",
            "Romans 8:28",
            "Not that all things are good — that they work together for good.",
        ))
        assertEquals(2, result.verseNotes.size)
        assertEquals(ref(BookID.JOHN, 3, 16), result.verseNotes[0].range?.start)
        assertEquals("The whole gospel in one verse.\nWorth memorising.", result.verseNotes[0].body)
        assertTrue(result.verseNotes[1].body.startsWith("Not that all things"))
    }

    /**
     * What copying notes out of another app by hand actually looks like: no blank lines, the
     * reference leading each entry.
     */
    @Test fun aReferenceLeadingALineStartsANewNote() {
        val result = PastedNotesImport.parse(lines(
            "John 3:16 — God so loved the world",
            "Romans 8:28 — all things work together",
            "1 Chronicles 29:14 — who am I that we should be able to offer",
        ))
        assertEquals(3, result.verseNotes.size)
        assertEquals("God so loved the world", result.verseNotes[0].body)
        assertEquals(ref(BookID.FIRST_CHRONICLES, 29, 14), result.verseNotes[2].range?.start)
        assertTrue(result.verseNotes[2].body.startsWith("who am I"))
    }

    /** A reference with nothing said about it is a saved verse, not an empty note. */
    @Test fun bareReferencesBecomeSavedVerses() {
        val result = PastedNotesImport.parse("Philippians 4:6\n1 John 1:9\nPsalms 23:1")
        assertEquals(3, result.saved.size)
        assertTrue(result.verseNotes.isEmpty())
    }

    // MARK: Refusing to guess

    @Test fun textWithNoReferencesIsRefusedRatherThanImported() {
        assertNothingRecognised("Just some thoughts I had on Sunday.\nNothing cited.")
        assertNothingRecognised("   \n  \n")
    }

    /** A number is not a reference, however willing a parser might be to read it as one. */
    @Test fun bareNumbersAreNotReferences() {
        assertNull(PastedNotesImport.reference("42"))
        assertNull(PastedNotesImport.reference("3:16"))
        assertNull(PastedNotesImport.reference("2024-01-05"))
    }

    /** Lines that name no verse are reported, not dropped and not attached to whatever came before. */
    @Test fun unresolvedLinesAreKeptToShowTheReader() {
        val result = PastedNotesImport.parse(lines(
            "John 3:16,A real note",
            "Enchiridion 4:2,Something this app can't place",
        ))
        assertEquals(1, result.verseNotes.size)
        assertEquals(1, result.unresolved.size)
        assertTrue(result.unresolved[0].contains("Enchiridion"))
    }

    // MARK: Telling the two apart

    @Test fun proseWithCommasIsNotMistakenForATable() {
        // Every line has commas, but not the same number — this is writing, not a table.
        val prose = lines(
            "John 3:16",
            "For God so loved the world, that he gave his only Son, that whoever believes in him",
            "should not perish, but have eternal life.",
        )
        assertFalse(PastedNotesImport.looksLikeATable(prose))
    }

    @Test fun aTableIsRecognisedByItsRegularity() {
        assertTrue(PastedNotesImport.looksLikeATable("a,b,c\nd,e,f\ng,h,i"))
        assertTrue(PastedNotesImport.looksLikeATable("John 3:16\tnote\nJohn 3:17\tnote"))
    }

    /** The colour vocabulary people and other apps actually use. */
    @Test fun colourNamesAndHexBothMapOntoThisAppsFive() {
        assertEquals("yellow", PastedNotesImport.colour("Yellow"))
        assertEquals("yellow", PastedNotesImport.colour("orange"))
        assertEquals("blue", PastedNotesImport.colour("#cae1fe"))
        assertEquals("purple", PastedNotesImport.colour("lavender"))
        assertNull(PastedNotesImport.colour("a note about colour"))
    }
}
