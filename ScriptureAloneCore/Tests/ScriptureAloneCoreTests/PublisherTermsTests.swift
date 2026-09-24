import Foundation
import Testing
@testable import ScriptureAloneCore

@Suite struct PublisherTermsTests {
    private func info(_ abbreviation: String, name: String = "", copyright: String = "© A Publisher") -> TranslationInfo {
        TranslationInfo(id: abbreviation, name: name, abbreviation: abbreviation, copyright: copyright, license: "")
    }

    @Test func recognisesByAbbreviationThenByName() {
        #expect(PublisherTerms.matching(abbreviation: "ESV", name: "", copyright: "")?.abbreviation == "ESV")
        #expect(PublisherTerms.matching(abbreviation: "NASB95", name: "", copyright: "")?.abbreviation == "NASB1995")
        #expect(PublisherTerms.matching(abbreviation: "RSB", name: "Study Bible",
                                        copyright: "The Holy Bible, English Standard Version")?.abbreviation == "ESV")
        // More specific names win over the names they contain.
        #expect(PublisherTerms.matching(text: "Holman Christian Standard Bible®")?.abbreviation == "HCSB")
        #expect(PublisherTerms.matching(text: "New Revised Standard Version Bible")?.abbreviation == "NRSV")
        #expect(PublisherTerms.matching(text: "Revised Standard Version")?.abbreviation == "RSV")
        #expect(PublisherTerms.matching(abbreviation: "XYZ", name: "My Bible", copyright: "© Me") == nil)
    }

    @Test func publicDomainAndPackagesAreNotHeldToPublisherTerms() {
        let asv = TranslationInfo(id: "ASV", name: "American Standard Version", abbreviation: "ASV", copyright: "", license: "Public Domain")
        #expect(asv.publisherTerms == nil)
        #expect(asv.rights == .publicDomain)
        #expect(asv.attributionNotice == nil)
        let unknown = info("XYZ")
        #expect(unknown.rights == .licensedDefault)
        #expect(unknown.attributionNotice == "© A Publisher")
    }

    @Test func eachTranslationGetsItsOwnLimitAndNotice() {
        #expect(info("ESV").rights.maxQuotationVerses == 500)
        #expect(info("CSB").rights.maxQuotationVerses == 1000)
        #expect(info("CSB").attributionNotice?.contains("Holman Bible Publishers") == true)
        #expect(info("NET").rights.maxQuotationVerses == TranslationRights.unlimitedQuotation)
        #expect(!info("NIV").rights.allowVerseImages)
        #expect(info("NIV").rights.allowShare)
        #expect(!info("NRSV").rights.allowShare)
        #expect(!info("MSG").rights.allowVerseImages)
        #expect(info("ESV").rights.allowCopy)
        #expect(!info("ESV").rights.allowExternalHandoff)
    }

    @Test func wholeBooksAndShareOfABookAreRefused() {
        // Jude: one chapter of 25 verses.
        let jude = (1...25).map { VerseRef(.jude, 1, $0) }
        let counts: (ChapterRef) -> Int = { $0.book == .jude ? 25 : 30 }
        #expect(info("CSB").quotationRefusal(for: jude, chapterVerses: counts) == .wholeBook(.jude))
        // The ESV also caps any one book at half.
        #expect(info("ESV").quotationRefusal(for: Array(jude.prefix(13)), chapterVerses: counts)
                == .tooMuchOfBook(.jude, percent: 50))
        #expect(info("ESV").quotationRefusal(for: Array(jude.prefix(12)), chapterVerses: counts) == nil)
        // CSB has no share-of-a-book rule, only whole books.
        #expect(info("CSB").quotationRefusal(for: Array(jude.prefix(24)), chapterVerses: counts) == nil)
        // NET allows a whole book.
        #expect(info("NET").quotationRefusal(for: jude, chapterVerses: counts) == nil)
    }

    @Test func tooManyVersesIsRefusedFirst() {
        let psalms = (1...150).flatMap { chapter in (1...3).map { VerseRef(.psalms, chapter, $0) } }
        let counts: (ChapterRef) -> Int = { _ in 20 }
        #expect(info("ESV").quotationRefusal(for: psalms, chapterVerses: counts) == nil)
        let more = psalms + (1...150).map { VerseRef(.psalms, $0, 4) }
        #expect(info("ESV").quotationRefusal(for: more, chapterVerses: counts) == .tooManyVerses(limit: 500))
    }

    @Test func theImporterReadsTheTranslationOffTheCopyrightPage() throws {
        let data = ImportFixtures.epub(documents: [
            ImportFixtures.Document("copy.xhtml", "<p>The Holy Bible, English Standard Version® (ESV®). Copyright © 2001 by Crossway.</p>"),
            ImportFixtures.Document("gen01.xhtml", "<h1>Genesis 1</h1><p><sup>1</sup>In the beginning.</p>"),
        ])
        let package = try EPUBPackage(data: data)
        #expect(BibleFileImporter.recognizedTerms(in: package)?.abbreviation == "ESV")
    }
}
