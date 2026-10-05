import Foundation
import Testing
@testable import ScriptureAloneCore

/// The two lenient readers every ePub import rests on: the XHTML scanner and the label reader that
/// turns publishers' headings, ids and file names into books, chapters and verses.
struct XMLScannerTests {
    private func starts(_ events: [XMLEvent]) -> [XMLTag] {
        events.compactMap { if case .start(let tag) = $0 { tag } else { nil } }
    }

    private func text(_ events: [XMLEvent]) -> String {
        events.compactMap { if case .text(let t) = $0 { t } else { nil } }.joined()
    }

    @Test func namesLoseTheirNamespaceAndCase() {
        let events = XMLScanner.scan(#"<HTML:P Class="Verse">x</html:p>"#)
        let tag = starts(events).first
        #expect(tag?.name == "p")
        #expect(tag?.attribute("class") == "Verse")
        #expect(tag?.classes == ["verse"])
        guard case .end(let name) = events.last else { Issue.record("no end tag"); return }
        #expect(name == "p")
    }

    @Test func epubTypeKeepsItsPrefixAndFallsBackToBareType() {
        let ops3 = starts(XMLScanner.scan(#"<aside epub:type="Footnote">n</aside>"#)).first
        #expect(ops3?.epubType == "footnote")
        let ops2 = starts(XMLScanner.scan(#"<aside type="note">n</aside>"#)).first
        #expect(ops2?.epubType == "note")
    }

    /// The reason this scanner exists: undeclared HTML entities must not abort the document.
    @Test func undeclaredHTMLEntitiesAreDecodedNotFatal() {
        let events = XMLScanner.scan("<p>Grace&nbsp;and peace &mdash; Paul&hellip; &amp; &#8220;Timothy&#x201D; &bogus;</p>")
        #expect(text(events) == "Grace\u{00A0}and peace — Paul… & “Timothy” &bogus;")
    }

    @Test func softHyphensVanishSoWordsStaySearchable() {
        #expect(XMLScanner.decodeEntities("right&shy;eous&shy;ness") == "righteousness")
    }

    @Test func voidAndSelfClosingElementsCloseThemselves() {
        let events = XMLScanner.scan("<p>a<br>b<img src='x.png'/>c</p>")
        let ends = events.compactMap { if case .end(let n) = $0 { n } else { nil } }
        #expect(ends == ["br", "img", "p"])
        #expect(text(events) == "abc")
        #expect(starts(events).first { $0.name == "img" }?.attribute("src") == "x.png")
    }

    @Test func commentsDoctypeAndProcessingInstructionsAreSkippedButCDATAIsText() {
        let source = """
        <?xml version="1.0"?><!DOCTYPE html [ <!ENTITY x "y"> ]><!-- a <p>comment</p> -->\
        <p><![CDATA[1 < 2]]></p>
        """
        let events = XMLScanner.scan(source)
        #expect(starts(events).map(\.name) == ["p"])
        #expect(text(events) == "1 < 2")
    }

    @Test func aBareLessThanInProseStaysText() {
        #expect(text(XMLScanner.scan("<p>3 < 4 and 5 <6</p>")) == "3 < 4 and 5 <6")
    }

    @Test func unquotedAndEntityBearingAttributes() {
        let tag = starts(XMLScanner.scan(#"<a href=ch1.xhtml title="Tom &amp; Jerry" data-x='q'>"#)).first
        #expect(tag?.attribute("href") == "ch1.xhtml")
        #expect(tag?.attribute("title") == "Tom & Jerry")
        #expect(tag?.attribute("data-x") == "q")
    }

    @Test func truncatedInputDoesNotHang() {
        // A damaged file ends mid-tag or mid-comment; the scanner returns what it read.
        _ = XMLScanner.scan("<p class=\"a")
        _ = XMLScanner.scan("<!-- never closed")
        _ = XMLScanner.scan("<![CDATA[ never closed")
        #expect(text(XMLScanner.scan("text <")) == "text <")
    }
}

struct ScriptureLabelsTests {
    @Test(arguments: [
        ("The Gospel According to St. John", BookID.john, Int?.none),
        ("PSALM 23", .psalms, 23),
        ("The First Epistle of Paul the Apostle to the Corinthians", .firstCorinthians, nil),
        ("1 John", .firstJohn, nil),
        ("Song of Solomon 2", .songOfSolomon, 2),
        ("Genesis", .genesis, nil),
    ])
    func headingsNameBooksAndChapters(raw: String, book: BookID, chapter: Int?) throws {
        let heading = try #require(ScriptureLabels.heading(raw))
        #expect(heading.book == book)
        #expect(heading.chapter == chapter)
    }

    /// A section heading that happens to contain a book's name is not that book.
    @Test func sectionHeadingsAreNotBooks() {
        #expect(ScriptureLabels.heading("Job’s Complaint")?.book == nil)
        #expect(ScriptureLabels.heading("The Creation of the World")?.book == nil)
        #expect(ScriptureLabels.heading("") == nil)
    }

    @Test func aBareNumberIsAChapter() throws {
        let heading = try #require(ScriptureLabels.heading("12"))
        #expect(heading.book == nil)
        #expect(heading.chapter == 12)
    }

    @Test(arguments: [
        ("ABC_Gen.1.1", ScriptureLabels.Identifier.verse(.genesis, chapter: 1, verse: 1)),
        ("xyz-Gen-1-1", .verse(.genesis, chapter: 1, verse: 1)),
        ("MAT.5.3", .verse(.matthew, chapter: 5, verse: 3)),
        ("ABC_1Cor.13.4", .verse(.firstCorinthians, chapter: 13, verse: 4)),
        ("Gen.1", .chapter(.genesis, chapter: 1)),
        ("v12", .verseNumber(12)),
        ("verse-3", .verseNumber(3)),
        ("ch2", .chapterNumber(2)),
    ])
    func elementIdentifiers(raw: String, expected: ScriptureLabels.Identifier) {
        #expect(ScriptureLabels.identifier(raw) == expected)
    }

    @Test func identifiersThatNameNothing() {
        #expect(ScriptureLabels.identifier("footnote") == nil)
        #expect(ScriptureLabels.identifier("note-0") == nil)
        #expect(ScriptureLabels.identifier("x-1000") == nil)
        #expect(ScriptureLabels.identifier("the.1") == nil, "an English word is never a book prefix")
    }

    @Test func fileStemsCarryBookAndChapter() {
        #expect(ScriptureLabels.fileStem("01_Genesis")?.book == .genesis)
        let gen01 = ScriptureLabels.fileStem("Gen.1")
        #expect(gen01?.book == .genesis && gen01?.chapter == 1)
        // A bare small ordinal belongs to the name.
        #expect(ScriptureLabels.fileStem("1_John")?.book == .firstJohn)
    }

    @Test func idComponentsSplitOnLetterDigitBoundaries() {
        let parts = ScriptureLabels.idComponents("ABC_1Cor.13.4")
        #expect(parts.map(\.text) == ["abc", "1", "cor", "13", "4"])
        #expect(parts.map(\.isDigits) == [false, true, false, true, true])
    }
}

struct BracketVerseParserTests {
    @Test func splitsByBracketedNumbersInTheRequestedChapter() {
        let verses = BracketVerseParser.parse("[1] In the beginning God [2] And the earth", in: ChapterRef(.genesis, 1))
        #expect(verses.map(\.ref) == [VerseRef(.genesis, 1, 1), VerseRef(.genesis, 1, 2)])
        #expect(verses.map(\.text) == ["In the beginning God", "And the earth"])
        #expect(verses.allSatisfy { $0.red.isEmpty })
    }

    @Test func emptyVersesAreSkippedAndUnclosedBracketsKept() {
        let verses = BracketVerseParser.parse("[1]   [2] Text with [unclosed", in: ChapterRef(.genesis, 1))
        #expect(verses.count == 1)
        #expect(verses[0].ref.verse == 2)
        #expect(verses[0].text == "Text with [unclosed")
    }

    @Test func chapterVerseLabelsAreNotVerses() {
        let verses = BracketVerseParser.parse("[3] says [1:1] here", in: ChapterRef(.john, 1))
        #expect(verses.count == 1)
        #expect(verses[0].text == "says [1:1] here")
    }

    @Test func noMarkersMeansNoVerses() {
        #expect(BracketVerseParser.parse("just prose", in: ChapterRef(.john, 1)).isEmpty)
    }
}
