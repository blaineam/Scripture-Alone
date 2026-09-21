import Foundation
import Testing
@testable import ScriptureAloneCore

/// Notes pasted or exported from somewhere this app has never seen.
///
/// Every fixture here is a *shape*, not a vendor. Nobody has handed me an Olive Tree CSV or a Logos
/// export, and writing tests that claim to reproduce one would be inventing evidence — the same
/// mistake as writing the parser from documentation. So these assert what the parser promises: a
/// reference and some text, however they are arranged, and nothing guessed when there is no
/// reference to be found.
@Suite struct PastedNotesImportTests {

    // MARK: Tables

    @Test func aTableWithAHeaderFindsItsReferenceAndTextColumns() throws {
        let csv = """
        Reference,Note,Created
        John 3:16,"For God so loved the world — the whole gospel in one verse",2024-01-05
        Romans 8:28,"All things work together for good, to them that love God",2024-02-11
        """
        let result = try PastedNotesImport.parse(csv)
        #expect(result.verseNotes.count == 2)
        #expect(result.verseNotes[0].range?.start == VerseRef(.john, 3, 16))
        #expect(result.verseNotes[0].body.hasPrefix("For God so loved"))
        #expect(result.verseNotes[1].range?.start == VerseRef(.romans, 8, 28))
        // The header names no verse, and is not worth reporting as a failure.
        #expect(result.unresolved.isEmpty)
    }

    /// The commas inside a quoted note must not become columns.
    @Test func quotedCommasStayInsideTheNote() throws {
        let result = try PastedNotesImport.parse("""
        Reference,Note
        Psalms 23:1,"He leads me, he restores me, he is enough"
        Psalms 23:4,"Though I walk, I fear no evil"
        """)
        #expect(result.verseNotes.count == 2)
        #expect(result.verseNotes[0].body == "He leads me, he restores me, he is enough")
    }

    @Test func aColourColumnBecomesHighlights() throws {
        let result = try PastedNotesImport.parse("""
        Verse,Colour
        John 1:1,blue
        John 1:2,yellow
        John 1:3,#b3e487
        """)
        #expect(result.highlights.count == 3)
        #expect(result.highlights[0].color == "blue")
        #expect(result.highlights[1].color == "yellow")
        #expect(result.highlights[2].color == "green")
        #expect(result.verseNotes.isEmpty)
    }

    @Test func tabSeparatedWorksToo() throws {
        let result = try PastedNotesImport.parse("John 3:16\tGod's love\nJohn 3:17\tNot to condemn")
        #expect(result.verseNotes.count == 2)
        #expect(result.verseNotes[1].body == "Not to condemn")
    }

    // MARK: Prose

    @Test func blocksSeparatedByBlankLinesEachBecomeANote() throws {
        let result = try PastedNotesImport.parse("""
        John 3:16
        The whole gospel in one verse.
        Worth memorising.

        Romans 8:28
        Not that all things are good — that they work together for good.
        """)
        #expect(result.verseNotes.count == 2)
        #expect(result.verseNotes[0].range?.start == VerseRef(.john, 3, 16))
        #expect(result.verseNotes[0].body == "The whole gospel in one verse.\nWorth memorising.")
        #expect(result.verseNotes[1].body.hasPrefix("Not that all things"))
    }

    /// What copying notes out of another app by hand actually looks like: no blank lines, the
    /// reference leading each entry.
    @Test func aReferenceLeadingALineStartsANewNote() throws {
        let result = try PastedNotesImport.parse("""
        John 3:16 — God so loved the world
        Romans 8:28 — all things work together
        1 Chronicles 29:14 — who am I that we should be able to offer
        """)
        #expect(result.verseNotes.count == 3)
        #expect(result.verseNotes[0].body == "God so loved the world")
        #expect(result.verseNotes[2].range?.start == VerseRef(.firstChronicles, 29, 14))
        #expect(result.verseNotes[2].body.hasPrefix("who am I"))
    }

    /// A reference with nothing said about it is a saved verse, not an empty note.
    @Test func bareReferencesBecomeSavedVerses() throws {
        let result = try PastedNotesImport.parse("Philippians 4:6\n1 John 1:9\nPsalms 23:1")
        #expect(result.saved.count == 3)
        #expect(result.verseNotes.isEmpty)
    }

    // MARK: Refusing to guess

    @Test func textWithNoReferencesIsRefusedRatherThanImported() {
        #expect(throws: NoteImportError.nothingRecognised) {
            try PastedNotesImport.parse("Just some thoughts I had on Sunday.\nNothing cited.")
        }
        #expect(throws: NoteImportError.nothingRecognised) {
            try PastedNotesImport.parse("   \n  \n")
        }
    }

    /// A number is not a reference, however willing a parser might be to read it as one.
    @Test func bareNumbersAreNotReferences() {
        #expect(PastedNotesImport.reference(in: "42") == nil)
        #expect(PastedNotesImport.reference(in: "3:16") == nil)
        #expect(PastedNotesImport.reference(in: "2024-01-05") == nil)
    }

    /// Lines that name no verse are reported, not dropped and not attached to whatever came before.
    @Test func unresolvedLinesAreKeptToShowTheReader() throws {
        let result = try PastedNotesImport.parse("""
        John 3:16,A real note
        Enchiridion 4:2,Something this app can't place
        """)
        #expect(result.verseNotes.count == 1)
        #expect(result.unresolved.count == 1)
        #expect(result.unresolved[0].contains("Enchiridion"))
    }

    // MARK: Telling the two apart

    @Test func proseWithCommasIsNotMistakenForATable() {
        // Every line has commas, but not the same number — this is writing, not a table.
        let prose = """
        John 3:16
        For God so loved the world, that he gave his only Son, that whoever believes in him
        should not perish, but have eternal life.
        """
        #expect(!PastedNotesImport.looksLikeATable(prose))
    }

    @Test func aTableIsRecognisedByItsRegularity() {
        #expect(PastedNotesImport.looksLikeATable("a,b,c\nd,e,f\ng,h,i"))
        #expect(PastedNotesImport.looksLikeATable("John 3:16\tnote\nJohn 3:17\tnote"))
    }

    /// The colour vocabulary people and other apps actually use.
    @Test func colourNamesAndHexBothMapOntoThisAppsFive() {
        #expect(PastedNotesImport.colour(in: "Yellow") == "yellow")
        #expect(PastedNotesImport.colour(in: "orange") == "yellow")
        #expect(PastedNotesImport.colour(in: "#cae1fe") == "blue")
        #expect(PastedNotesImport.colour(in: "lavender") == "purple")
        #expect(PastedNotesImport.colour(in: "a note about colour") == nil)
    }

    /// A highlighted range across a chapter break is walked through the verses that exist.
    @Test func aHighlightedRangeAcrossAChapterBreakCoversOnlyRealVerses() throws {
        let result = try PastedNotesImport.parse("""
        Verse,Colour
        Genesis 1:30-2:2,#b3e487
        John 1:1,blue
        """, verseCount: { $0 == ChapterRef(.genesis, 1) ? 31 : 25 })
        #expect(result.highlights.map(\.verse.key)
                == [1_001_030, 1_001_031, 1_002_001, 1_002_002, 43_001_001])
    }

    /// Six hex letters are also ordinary English words. They are not colours; real hex still is.
    @Test func wordsMadeOfHexLettersAreNotColours() throws {
        #expect(PastedNotesImport.colour(in: "decade") == nil)
        #expect(PastedNotesImport.colour(in: "facade") == nil)
        #expect(PastedNotesImport.colour(in: "beaded") == nil)
        #expect(PastedNotesImport.colour(in: "#facade") != nil)
        #expect(PastedNotesImport.colour(in: "b3e487") == "green")
        #expect(PastedNotesImport.colour(in: "#B3E487") == "green")
        #expect(PastedNotesImport.colour(in: "#blessed") == nil)

        // A notes column of such words stays a notes column, rather than becoming highlights.
        let result = try PastedNotesImport.parse("""
        John 1:1,facade
        John 1:2,decade
        John 1:3,beaded
        """)
        #expect(result.highlights.isEmpty)
        #expect(result.verseNotes.map(\.body) == ["facade", "decade", "beaded"])
    }
}
