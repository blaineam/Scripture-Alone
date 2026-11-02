import Foundation
import Testing
@testable import ScriptureAloneCore

/// A German study-Bible ePub shape (chapter ids `cref-C`, verse ids `vref-C-V`, classes
/// `bvn`/`bcn`, `p.ip` commentary between the scripture paragraphs, EPUB 3 endnote files), and
/// German chapter numbering for Joel and Malachi. The markup is the one GitHub issue #7 describes;
/// every word of verse text and commentary below is invented placeholder text.
@Suite struct GermanStudyBibleImportTests {
    private func page(_ title: String, _ body: String) -> String {
        """
        <?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
        <head><title>\(title)</title></head><body>\(body)</body></html>
        """
    }

    private func extract(_ documents: [(String, String)]) throws -> ExtractedBible {
        try BibleTextExtractor().extract(documents: documents.map { (path: $0.0, xhtml: $0.1) })
    }

    private func text(_ bible: ExtractedBible, _ book: BookID, _ chapter: Int, _ verse: Int) -> String? {
        bible.verses[VerseRef(book, chapter, verse)]?.text
    }

    private func chapterLabel(_ file: String, _ label: String, _ number: Int) -> String {
        "<div id=\"cref-\(number)\" class=\"bcn\"><a href=\"\(file)#top_of_book\"><span class=\"cb\">\(label) </span>\(number)</a></div>"
    }

    private func verse(_ chapter: Int, _ number: Int, shown: String? = nil, withId: Bool = true) -> String {
        let label = shown ?? "\(number)"
        return withId ? "<span id=\"vref-\(chapter)-\(number)\" class=\"bvn\">\(label)</span>" : "<span class=\"bvn\">\(label)</span>"
    }

    // The shape: Genesis, in a file named by an index, with commentary, endnote callers and alternative numbers.
    private var genesis: String {
        page("Das erste Buch Mose (Genesis)",
             chapterLabel("gen_1.xhtml", "1. Mose", 1)
             + "<h2 class=\"s1\">Erfundene Ueberschrift</h2>"
             + """
               <p class="p"> \(verse(1, 1)) Erfundener Text eins. \(verse(1, 2)) Erfundener Text zwei.
               <a epub:type="noteref" href="notes_gen_1.xhtml#fn-gen-1">a</a> \(verse(1, 3, withId: false)) Text drei ohne Kennung.
               <span class="va">[9]</span> \(verse(1, 4, shown: "4-5")) Text vier und fuenf. \(verse(1, 6, shown: "6a")) Erster Teil
               <span class="bvn">6b</span> zweiter Teil.</p>
               """
             + "<p class=\"ip\">1,1-6 Erfundener Kommentar zu den Versen, nicht Teil des Textes.</p>"
             + "<p class=\"p\"> \(verse(1, 7)) Text sieben.</p>"
             + "<p class=\"ip\">1,7 Zweiter erfundener Kommentar.</p>"
             + chapterLabel("gen_1.xhtml", "1. Mose", 2)
             + "<p class=\"p\"> \(verse(2, 1)) Anfang des zweiten Kapitels.</p>")
    }

    private var genesisNotes: String {
        page("Anmerkungen", """
             <ol><li id="fn-gen-1"><p>Erfundene Querverweise 1,1.</p>
             <a role="doc-backlink" epub:type="backlink" href="gen_1.xhtml#fnref-gen-1">1</a></li></ol>
             """)
    }

    private var exodus: String {
        page("Das zweite Buch Mose (Exodus)",
             chapterLabel("exo_2.xhtml", "2. Mose", 1)
             + "<p class=\"p\"> \(verse(1, 1)) Erfundener Anfang des zweiten Buches.</p>")
    }

    @Test func readsTheChapterAndVerseIdsAndClasses() throws {
        let bible = try extract([("gen_1.xhtml", genesis), ("notes_gen_1.xhtml", genesisNotes), ("exo_2.xhtml", exodus)])
        #expect(bible.books == [.genesis, .exodus])
        #expect(text(bible, .genesis, 1, 1) == "Erfundener Text eins.")
        #expect(text(bible, .genesis, 1, 2) == "Erfundener Text zwei.")
        #expect(text(bible, .genesis, 2, 1) == "Anfang des zweiten Kapitels.")
        // The file's number ("exo_2") is an index, not the chapter: the label names chapter 1.
        #expect(text(bible, .exodus, 1, 1) == "Erfundener Anfang des zweiten Buches.")
        #expect(text(bible, .exodus, 2, 1) == nil)
    }

    @Test func aVerseSpanWithoutAnIdIsStillAVerse() throws {
        let bible = try extract([("gen_1.xhtml", genesis)])
        #expect(text(bible, .genesis, 1, 3) == "Text drei ohne Kennung.")
        #expect(bible.verseNumbers(in: ChapterRef(.genesis, 1)) == [1, 2, 3, 4, 6, 7])
    }

    @Test func aBridgedVerseKeepsItsFirstNumberAndSplitVersesJoin() throws {
        let bible = try extract([("gen_1.xhtml", genesis)])
        #expect(text(bible, .genesis, 1, 4) == "Text vier und fuenf.")
        #expect(bible.bridgedVerses[VerseRef(.genesis, 1, 5)] == VerseRef(.genesis, 1, 4))
        #expect(text(bible, .genesis, 1, 6) == "Erster Teil zweiter Teil.")
    }

    @Test func alternativeNumberingAndChapterLabelsAreNotText() throws {
        let bible = try extract([("gen_1.xhtml", genesis)])
        #expect(!bible.verses.values.contains { $0.text.contains("[9]") || $0.text.contains("Mose") })
        #expect(text(bible, .genesis, 1, 7) == "Text sieben.")
    }

    @Test func commentaryIsAStudyNoteNotVerseText() throws {
        let bible = try extract([("gen_1.xhtml", genesis)])
        #expect(!bible.verses.values.contains { $0.text.contains("Kommentar") })
        let notes = bible.study.orderedNotes
        #expect(notes.count == 2)
        // The note covers the passage read since the previous one, and the reference it opens with goes.
        #expect(notes[0].start == VerseRef(.genesis, 1, 1))
        #expect(notes[0].end == VerseRef(.genesis, 1, 6))
        #expect(notes[0].text == "Erfundener Kommentar zu den Versen, nicht Teil des Textes.")
        #expect(notes[1].start == VerseRef(.genesis, 1, 7))
        #expect(notes[1].end == VerseRef(.genesis, 1, 7))
    }

    @Test func endnoteFilesAreNotScripture() throws {
        let bible = try extract([("gen_1.xhtml", genesis), ("notes_gen_1.xhtml", genesisNotes)])
        #expect(!bible.verses.values.contains { $0.text.contains("Querverweise") })
        #expect(bible.books == [.genesis])
    }

    @Test func commentaryWithoutStudyMaterialIsDropped() throws {
        var options = BibleTextExtractor.Options()
        options.studyMaterial = false
        let bible = try BibleTextExtractor(options: options).extract(documents: [(path: "gen_1.xhtml", xhtml: genesis)])
        #expect(bible.study.isEmpty)
        #expect(!bible.verses.values.contains { $0.text.contains("Kommentar") })
    }

    @Test func aPsalmHeadingIsVerseOne() throws {
        let bible = try extract([("psa_9.xhtml", page("Die Psalmen",
            chapterLabel("psa_9.xhtml", "Psalm", 3)
            + "<p class=\"d\">\(verse(3, 1)) Ein erfundenes Lied.</p><p class=\"q1\">\(verse(3, 2)) Erfundene Zeile.</p>"))])
        #expect(text(bible, .psalms, 3, 1) == "Ein erfundenes Lied.")
        #expect(text(bible, .psalms, 3, 2) == "Erfundene Zeile.")
    }

    // MARK: - Labels

    @Test func readsTheIdsAndGermanNames() {
        #expect(ScriptureLabels.identifier("vref-35-1") == .chapterVerse(chapter: 35, verse: 1))
        #expect(ScriptureLabels.identifier("cref-35") == .chapterNumber(35))
        #expect(ScriptureLabels.heading("1. Mose 35")?.book == .genesis)
        #expect(ScriptureLabels.heading("1. Mose 35")?.chapter == 35)
        #expect(ScriptureLabels.heading("1. Könige")?.book == .firstKings)
        #expect(ScriptureLabels.heading("Offenbarung")?.book == .revelation)
        #expect(ScriptureLabels.heading("Ester 2")?.book == .esther)
        #expect(ScriptureLabels.fileStem("1_Mose")?.book == .genesis)
        // A section heading is still not a book.
        #expect(ScriptureLabels.heading("Der Tag des Herrn")?.book == nil)
    }

    // MARK: - German Joel and Malachi

    private var joelGerman: String {
        page("Joel",
             chapterLabel("joe_1.xhtml", "Joel", 1)
             + "<p class=\"p\">\(verse(1, 1)) Erfundener Joel eins.</p>"
             + chapterLabel("joe_1.xhtml", "Joel", 2)
             + """
               <p class="p">\(verse(2, 1)) Erfundener Joel zwei eins. \(verse(2, 2)) Zwei zwei.
               \(verse(2, 26)) Zwei sechsundzwanzig. \(verse(2, 27)) Zwei siebenundzwanzig.</p>
               """
             + chapterLabel("joe_1.xhtml", "Joel", 3)
             + """
               <h2 class="s1">Erfundene Ueberschrift</h2>
               <p class="p">\(verse(3, 1)) Drei eins. \(verse(3, 2)) Drei zwei. \(verse(3, 5)) Drei fuenf.</p>
               """
             + "<p class=\"ip\">3,1-5 Erfundener Kommentar zu Joel drei.</p>"
             + chapterLabel("joe_1.xhtml", "Joel", 4)
             + "<p class=\"p\">\(verse(4, 1)) Vier eins. \(verse(4, 2)) Vier zwei.</p>")
    }

    private var malachiGerman: String {
        page("Maleachi",
             chapterLabel("mal_1.xhtml", "Maleachi", 1)
             + "<p class=\"p\">\(verse(1, 1)) Erfundener Maleachi eins.</p>"
             + chapterLabel("mal_1.xhtml", "Maleachi", 3)
             + """
               <p class="p">\(verse(3, 1)) Drei eins. \(verse(3, 18)) Drei achtzehn.
               \(verse(3, 19)) Drei neunzehn. \(verse(3, 22)) Drei zweiundzwanzig. \(verse(3, 24)) Drei vierundzwanzig.</p>
               """)
    }

    @Test func germanJoelIsRenumberedToTheKJVDivision() throws {
        let bible = try extract([("joe_1.xhtml", joelGerman)])
        #expect(text(bible, .joel, 2, 26) == "Zwei sechsundzwanzig.")
        #expect(text(bible, .joel, 2, 27) == "Zwei siebenundzwanzig.")
        // German 3:1-5 = KJV 2:28-32.
        #expect(text(bible, .joel, 2, 28) == "Drei eins.")
        #expect(text(bible, .joel, 2, 29) == "Drei zwei.")
        #expect(text(bible, .joel, 2, 32) == "Drei fuenf.")
        // German 4 = KJV 3, and nothing is merged with it.
        #expect(text(bible, .joel, 3, 1) == "Vier eins.")
        #expect(text(bible, .joel, 3, 2) == "Vier zwei.")
        #expect(bible.verseNumbers(in: ChapterRef(.joel, 3)) == [1, 2])
        #expect(bible.verses[VerseRef(.joel, 4, 1)] == nil)
        #expect(bible.chapterOrder.filter { $0.book == .joel }.count == 3)
    }

    @Test func germanJoelKeepsItsBlocksAndStudyNotesInStep() throws {
        let bible = try extract([("joe_1.xhtml", joelGerman)])
        let chapter2 = bible.blocks[ChapterRef(.joel, 2)] ?? []
        #expect(chapter2.flatMap(\.fragments).map(\.verse) == [1, 2, 26, 27, 28, 29, 32])
        // The heading that stood above German 3:1 travels with it.
        #expect(chapter2.first { $0.kind.isHeading }?.heading == "Erfundene Ueberschrift")
        #expect((bible.blocks[ChapterRef(.joel, 3)] ?? []).flatMap(\.fragments).map(\.verse) == [1, 2])
        let note = try #require(bible.study.orderedNotes.first)
        #expect(bible.study.orderedNotes.count == 1)
        #expect(note.start == VerseRef(.joel, 2, 28))
        #expect(note.end == VerseRef(.joel, 2, 32))
    }

    @Test func germanMalachiChapterThreeSplitsIntoThreeAndFour() throws {
        let bible = try extract([("mal_1.xhtml", malachiGerman)])
        #expect(text(bible, .malachi, 3, 1) == "Drei eins.")
        #expect(text(bible, .malachi, 3, 18) == "Drei achtzehn.")
        #expect(text(bible, .malachi, 4, 1) == "Drei neunzehn.")
        #expect(text(bible, .malachi, 4, 4) == "Drei zweiundzwanzig.")
        #expect(text(bible, .malachi, 4, 6) == "Drei vierundzwanzig.")
        #expect(bible.verses[VerseRef(.malachi, 3, 19)] == nil)
        // The block that held 3:1-24 is two paragraphs now, one in each chapter.
        #expect((bible.blocks[ChapterRef(.malachi, 3)] ?? []).flatMap(\.fragments).map(\.verse) == [1, 18])
        #expect((bible.blocks[ChapterRef(.malachi, 4)] ?? []).flatMap(\.fragments).map(\.verse) == [1, 4, 6])
    }

    @Test func anEnglishNumberedJoelAndMalachiAreUntouched() throws {
        let bible = try extract([
            ("joe.xhtml", page("Joel",
                chapterLabel("joe.xhtml", "Joel", 2) + "<p>\(verse(2, 28)) Erfunden achtundzwanzig. \(verse(2, 32)) Erfunden zweiunddreissig.</p>"
                + chapterLabel("joe.xhtml", "Joel", 3) + "<p>\(verse(3, 1)) Erfunden drei eins.</p>")),
            ("mal.xhtml", page("Maleachi",
                chapterLabel("mal.xhtml", "Maleachi", 3) + "<p>\(verse(3, 1)) Eins. \(verse(3, 18)) Achtzehn.</p>"
                + chapterLabel("mal.xhtml", "Maleachi", 4) + "<p>\(verse(4, 1)) Vier eins. \(verse(4, 6)) Vier sechs.</p>")),
        ])
        #expect(text(bible, .joel, 2, 28) == "Erfunden achtundzwanzig.")
        #expect(text(bible, .joel, 3, 1) == "Erfunden drei eins.")
        #expect(text(bible, .malachi, 4, 6) == "Vier sechs.")
        #expect(text(bible, .malachi, 3, 18) == "Achtzehn.")
        #expect(bible.verses[VerseRef(.malachi, 4, 1)] != nil)
    }

    @Test func theVersificationTable() {
        func check(_ book: BookID, _ chapter: Int, _ verse: Int, _ expected: (Int, Int)) {
            let mapped = GermanVersification.english(book, chapter: chapter, verse: verse)
            #expect(mapped.chapter == expected.0 && mapped.verse == expected.1)
        }
        check(.joel, 3, 1, (2, 28))
        check(.joel, 3, 5, (2, 32))
        check(.joel, 4, 21, (3, 21))
        check(.joel, 2, 27, (2, 27))
        check(.malachi, 3, 18, (3, 18))
        check(.malachi, 3, 19, (4, 1))
        check(.malachi, 3, 24, (4, 6))
        check(.genesis, 1, 5, (1, 5))
    }
}
