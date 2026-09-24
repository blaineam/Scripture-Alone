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
        #expect(!PDFBibleReader.isRunningHead("THE CREATION"))
        #expect(PDFBibleReader.isPrinterSlug("Bible.indb 11 10/26/17 8:59 PM"))
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
}
