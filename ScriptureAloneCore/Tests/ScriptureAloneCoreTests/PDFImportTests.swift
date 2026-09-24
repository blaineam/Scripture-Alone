import Foundation
import Testing
@testable import ScriptureAloneCore

/// The PDF reader's rules, each judged against the file's own text rather than any one layout.
@Suite struct PDFImportTests {
    private func run(_ text: String, _ size: Double = 10) -> PDFBibleReader.Run {
        PDFBibleReader.Run(text: text, size: size, ownLine: false)
    }

    @Test func aLineBreakInsideAWordJoinsAndOneBetweenWordsSpaces() {
        // "Pharisees" appears whole elsewhere in the text; "werecreated" never does.
        let book = [run("the Pharisees came and the Pharisees asked them\n"), run("things were created by him and all\n")]
        let lexicon = Lexicon(book)
        let resolved = lexicon.resolveLineBreaks(in: [run("sent from the Phari\n"), run("sees to ask. All things were\n"), run("created by him\n")])
        let text = resolved.map(\.text).joined()
        #expect(text.contains("Pharisees to ask"))
        #expect(text.contains("were created"))
    }

    @Test func aHyphenAtALineEndIsKeptInACompound() {
        let lexicon = Lexicon([run("a three-year-old cow and a goat\n")])
        let resolved = lexicon.resolveLineBreaks(in: [run("a three-year-\n"), run("old ram\n")])
        #expect(resolved.map(\.text).joined().hasPrefix("a three-year-old ram"))
    }

    @Test func aWordBrokenAtAnFLigatureIsRejoined() {
        let book = (0..<6).map { _ in run("they brought an off ering and an off ering of grain to the altar\n") }
            + [run("and he gave it off the altar to them\n")]
        let lexicon = Lexicon(book)
        #expect(lexicon.repairingLigatures("an off ering") == "an offering")
        #expect(lexicon.repairingLigatures("take it off the altar") == "take it off the altar")
    }

    @Test func aTypesettersHyphenIsDroppedAndAWordsOwnKept() {
        let lexicon = Lexicon([run("the Pharisees came and a three-year-old cow\n")])
        #expect(lexicon.resolveLineBreaks(in: [run("sent from the Phari-\n"), run("sees to ask\n")]).map(\.text).joined()
            .hasPrefix("sent from the Pharisees to ask"))
        // The hyphen can come in a font of its own, the word's first half in the run before it.
        #expect(lexicon.resolveLineBreaks(in: [run("sent from the Phari"), run("-\n"), run("sees to ask\n")]).map(\.text).joined()
            .hasPrefix("sent from the Pharisees to ask"))
        #expect(lexicon.resolveLineBreaks(in: [run("a three-\n"), run("year-old ram\n")]).map(\.text).joined()
            .hasPrefix("a three-year-old ram"))
    }

    @Test func otherLigaturesAreRejoinedButNotRareWordsAfterOf() {
        let lexicon = Lexicon([run("the price of grinding was high for them\n")])
        #expect(lexicon.repairingLigatures("their hope of profi t was gone") == "their hope of profit was gone")
        #expect(lexicon.repairingLigatures("out of selfi sh ambition") == "out of selfish ambition")
        #expect(lexicon.repairingLigatures("the price of grinding") == "the price of grinding")
    }

    @Test func aChapterNumbersItsFirstVerseAndAMeasureIsNotAVerse() {
        let reader = PDFBibleReader(options: .init())
        var lastVerse = 0
        var chapterOpen = false
        // A psalm: its number, its title, then a "1" the number already began.
        let psalm = [run("3", 30), run("A psalm of David.\n", 9), run("1", 10), run(" Lord, how my foes increase!\n")]
        let (first, _) = reader.scan(psalm, page: 0, body: 10, rare: [], outside: false, lastVerse: &lastVerse, chapterOpen: &chapterOpen)
        #expect(!first.flow.contains { if case .text(let text, _) = $0 { text.contains("1") } else { false } })
        #expect(!first.flow.contains { if case .marker(let marker) = $0 { marker.verse == 1 } else { false } })
        // "10 1/2 feet": the fraction's font sets the whole number apart.
        let measure = [run("2", 10), run(" it was "), run("10 "), run("1/2", 10.1), run(" feet deep.\n")]
        let (second, _) = reader.scan(measure, page: 1, body: 10, rare: [], outside: false, lastVerse: &lastVerse, chapterOpen: &chapterOpen)
        #expect(!second.flow.contains { if case .marker(let marker) = $0 { marker.verse == 10 } else { false } })
        #expect(lastVerse == 2)
    }

    @Test func twoCallersOnOneWordAreTwoNotes() {
        let reader = PDFBibleReader(options: .init())
        var lastVerse = 4
        var chapterOpen = false
        let runs = [run("it is called Babylon,"), run(" l,m", 6), run("for there\n")]
        let (document, _) = reader.scan(runs, page: 0, body: 10, rare: [], outside: false, lastVerse: &lastVerse, chapterOpen: &chapterOpen)
        let labels = document.flow.compactMap { if case .noteMarker(_, let label) = $0 { label } else { nil } }
        #expect(labels == ["l", "m"])
        let text = document.flow.compactMap { if case .text(let text, _) = $0 { text } else { nil } }.joined()
        #expect(text.contains("Babylon, for"))
    }

    @Test func versesMustFollowOn() {
        #expect(PDFBibleReader.followsOn(8, after: 7))
        #expect(PDFBibleReader.followsOn(10, after: 7))   // a translation omits a verse
        #expect(PDFBibleReader.followsOn(1, after: 0))
        #expect(!PDFBibleReader.followsOn(75, after: 31)) // "75 feet" in the text
        #expect(!PDFBibleReader.followsOn(3, after: 12))
    }

    @Test func runningHeadsAndSlugsAreFurniture() {
        #expect(PDFBibleReader.isRunningHead("GENESIS 2-3 2"))
        #expect(PDFBibleReader.isRunningHead("NUMbERS 2-3 114"))   // small capitals, extracted
        #expect(PDFBibleReader.isRunningHead("235 1 SAMUEL 2-3"))
        #expect(PDFBibleReader.isRunningHead("1 CORINTHIANS 14-15 1020"))
        #expect(!PDFBibleReader.isRunningHead("THE CREATION"))
        #expect(PDFBibleReader.isPrinterSlug("Bible.indb 11 10/26/17 8:59 PM"))
        #expect(PDFBibleReader.isPrinterSlug("10/26/17 9:00 PM"))   // the time stamp come apart
        #expect(!PDFBibleReader.isPrinterSlug("In the beginning"))
    }

    @Test func smallCapitalsAndSoftHyphensAreTypesetting() {
        #expect(PDFBibleReader.bodyText("the L\u{AD}ord your G\u{AD}od") == "the LORD your GOD")
        #expect(PDFBibleReader.bodyText("\u{AD}con\u{AD}fessed") == "confessed")
    }

    @Test func noteReferencesAndCallers() {
        #expect(PDFBibleReader.isReference("15:4"))
        #expect(PDFBibleReader.isReference("Ps 3:2 Or"))
        #expect(!PDFBibleReader.isReference("Or created"))
        #expect(PDFBibleReader.isCallerLetters("a"))
        #expect(!PDFBibleReader.isCallerLetters("and"))
    }

    // MARK: Quality

    @Test func qualityJudgesContinuityAndCleanText() {
        #expect(ImportQuality.looksClean("In the beginning God created the heavens and the earth."))
        #expect(!ImportQuality.looksClean("the earth.The heavens"))
        #expect(!ImportQuality.looksClean("con\u{AD}fessed"))
        #expect(!ImportQuality.looksClean("two  spaces"))
        #expect(!ImportQuality.looksClean(String(repeating: "word ", count: 400)))
    }

    @Test func aDamagedReadIsRefused() throws {
        // 300 verses, but every chapter has holes in it.
        let chapters = (1...30).map { chapter -> (String, String) in
            let verses = stride(from: 1, through: 20, by: 2).map { "<span class=\"verse-num\">\($0)</span> Text of the verse." }
            return ("gen\(chapter).xhtml", "<section title=\"Genesis\"><p><span class=\"chapter-num\">\(chapter)</span> \(verses.joined(separator: " "))</p></section>")
        }
        let bible = try BibleTextExtractor().extract(documents: chapters.map { (path: $0.0, xhtml: "<html><body>\($0.1)</body></html>") })
        let identity = ImportedTranslationIdentity(id: "IMPORT-Q", name: "Damaged", abbreviation: "DMG", copyright: "© Example")
        let url = try ImportFixtures.scratchDirectory().appending(path: "IMPORT-Q.sqlite")
        #expect(throws: BibleImportError.self) { try ImportedBibleBuilder.write(bible, identity: identity, to: url) }
        #expect(ImportCoverageReport(bible).quality.score < ImportQuality.minimum)
    }

    @Test func aVersePrintedWithoutItsNumberAfterAnOmittedVerseIsRecovered() {
        // An omitted verse printed only as a footnote marker, the next verse's words running on
        // after it with no number.
        var bible = ExtractedBible()
        let chapter = ChapterRef(.acts, 24)
        let six = "He even tried to desecrate the temple, and so we apprehended him. By examining him yourself you will be able to discern the truth."
        let marker = "He even tried to desecrate the temple, and so we apprehended him.".unicodeScalars.count
        bible.append(ExtractedBlock(kind: .paragraph, fragments: [
            ExtractedFragment(verse: 6, numbered: true, text: six, footnotes: [ExtractedFootnote(position: marker, text: "Other mss add verse 7")]),
            ExtractedFragment(verse: 9, numbered: true, text: "The Jews also joined in the attack."),
        ]), to: chapter)
        bible.appendVerseText(six, red: [], to: VerseRef(.acts, 24, 6))
        bible.appendVerseText("The Jews also joined in the attack.", red: [], to: VerseRef(.acts, 24, 9))

        bible.recoverVersesAfterOmissions()

        #expect(bible.verses[VerseRef(.acts, 24, 6)]?.text == "He even tried to desecrate the temple, and so we apprehended him.")
        #expect(bible.verses[VerseRef(.acts, 24, 8)]?.text == "By examining him yourself you will be able to discern the truth.")
        #expect(bible.verses[VerseRef(.acts, 24, 7)] == nil)
        let fragments = bible.blocks(for: chapter).flatMap(\.fragments)
        #expect(fragments.map(\.verse) == [6, 8, 9])
        #expect(fragments[1].numbered)
    }

    @Test func aFootnoteMidSentenceIsNotTakenForAMissingVerse() {
        var bible = ExtractedBible()
        let chapter = ChapterRef(.acts, 24)
        let six = "He even tried to desecrate the temple and so we apprehended him before he could flee."
        bible.append(ExtractedBlock(kind: .paragraph, fragments: [
            ExtractedFragment(verse: 6, numbered: true, text: six, footnotes: [ExtractedFootnote(position: 27, text: "Or profane")]),
        ]), to: chapter)
        bible.appendVerseText(six, red: [], to: VerseRef(.acts, 24, 6))
        bible.appendVerseText("The Jews also joined in.", red: [], to: VerseRef(.acts, 24, 9))

        bible.recoverVersesAfterOmissions()

        #expect(bible.verses[VerseRef(.acts, 24, 8)] == nil)
        #expect(bible.verses[VerseRef(.acts, 24, 6)]?.text == six)
    }
}
