import Foundation
import Testing
@testable import ScriptureAloneCore

@Suite struct OSISReferenceTests {
    @Test func codesCoverTheCanon() {
        #expect(OSISReference.bookCodes.count == BookID.allCases.count)
        #expect(Set(OSISReference.bookCodes.map { $0.lowercased() }).count == BookID.allCases.count)
        for book in BookID.allCases {
            #expect(OSISReference.book(forCode: OSISReference.code(for: book)) == book)
            // USFM codes too, and they never name a different book than OSIS does.
            #expect(OSISReference.book(forCode: book.code) == book)
        }
    }

    @Test(arguments: [
        ("John.3.16", Passage(book: .john, startChapter: 3, startVerse: 16)),
        ("john.3.16", Passage(book: .john, startChapter: 3, startVerse: 16)),
        ("JHN.3.16", Passage(book: .john, startChapter: 3, startVerse: 16)),
        ("Gen.1.1-Gen.1.3", Passage(book: .genesis, startChapter: 1, startVerse: 1, endChapter: 1, endVerse: 3)),
        ("Ps.23", Passage(book: .psalms, startChapter: 23)),
        ("1Cor.13.4-1Cor.13.7", Passage(book: .firstCorinthians, startChapter: 13, startVerse: 4, endChapter: 13, endVerse: 7)),
        ("Gen.1.1-Gen.2.3", Passage(book: .genesis, startChapter: 1, startVerse: 1, endChapter: 2, endVerse: 3)),
        ("John.3.16-18", Passage(book: .john, startChapter: 3, startVerse: 16, endChapter: 3, endVerse: 18)),
        ("John.3.16-4.2", Passage(book: .john, startChapter: 3, startVerse: 16, endChapter: 4, endVerse: 2)),
        ("John.3-4", Passage(book: .john, startChapter: 3, endChapter: 4)),
        ("John.3-John.4", Passage(book: .john, startChapter: 3, endChapter: 4)),
        ("Rom.8.38-Rom.9", Passage(book: .romans, startChapter: 8, startVerse: 38, endChapter: 9, endVerse: nil)),
        ("Jude.1.3", Passage(book: .jude, startChapter: 1, startVerse: 3)),
        ("Jude.3", Passage(book: .jude, startChapter: 1, startVerse: 3)),
        ("Obad.1", Passage(book: .obadiah, startChapter: 1)),
        ("Gen", Passage(book: .genesis, startChapter: 1)),
        ("urn:osis:John.3.16", Passage(book: .john, startChapter: 3, startVerse: 16)),
        ("osis:Ps.23", Passage(book: .psalms, startChapter: 23)),
        ("URN:OSIS:Rev.22.21", Passage(book: .revelation, startChapter: 22, startVerse: 21)),
        ("Bible.KJV:John.3.16", Passage(book: .john, startChapter: 3, startVerse: 16)),
        ("John.3.16!a", Passage(book: .john, startChapter: 3, startVerse: 16)),
        ("Mal.4.6-Matt.1.1", Passage(book: .malachi, startChapter: 4, startVerse: 6, endChapter: 4, endVerse: nil)),
    ])
    func parses(_ text: String, _ expected: Passage) throws {
        let passages = try #require(OSISReference.parse(text))
        #expect(passages == [expected])
    }

    @Test func parsesLists() throws {
        let passages = try #require(OSISReference.parse("John.3.16 Rom.8.28;Ps.23, Eph.2.8-Eph.2.9"))
        #expect(passages.map(\.display) == ["John 3:16", "Romans 8:28", "Psalms 23", "Ephesians 2:8–9"])
    }

    @Test(arguments: [
        "", "John 3:16", "Ps 23", "Jn 3.16", "Rom 8:28-39", "nonsense", "John.3.x", "John.22.1", "John.3.16-3.15",
        "John.3.0", "Matt.1.1-Mal.4.6", "John.3.16.4", "Jean 3:16",
    ])
    func rejects(_ text: String) {
        #expect(OSISReference.parse(text) == nil)
    }

    @Test func formatsStoredRanges() {
        #expect(OSISReference.string(for: VerseRange(VerseRef(.john, 3, 16))) == "John.3.16")
        #expect(OSISReference.string(for: VerseRange(VerseRef(.firstCorinthians, 13, 4), VerseRef(.firstCorinthians, 13, 7)))
                == "1Cor.13.4-1Cor.13.7")
        let range = VerseRange(VerseRef(.genesis, 1, 1), VerseRef(.genesis, 2, 3))
        let parsed = OSISReference.parse(OSISReference.string(for: range))?.first
        #expect(parsed?.range { _ in 31 } == range)
    }

    @Test func wholeChaptersResolveAgainstVerseCounts() throws {
        let psalm = try #require(OSISReference.parse("Ps.23")?.first)
        #expect(psalm.isWholeChapter)
        #expect(psalm.range { _ in 6 } == VerseRange(VerseRef(.psalms, 23, 1), VerseRef(.psalms, 23, 6)))
    }
}

@Suite struct AppLinkRoutingTests {
    private func link(_ string: String) -> AppLink? { AppLink(url: URL(string: string)!) }

    @Test func keepsTheStoredKeyForm() {
        #expect(link("scripturealone://open?ref=43003016-43003017")
                == .open([VerseRange(VerseRef(.john, 3, 16), VerseRef(.john, 3, 17))]))
        #expect(link("scripturealone://open?ref=43003016") == .open([VerseRange(VerseRef(.john, 3, 16))]))
    }

    @Test func opensOSIS() {
        let john316 = AppLink.osis([Passage(book: .john, startChapter: 3, startVerse: 16)])
        #expect(link("scripturealone://open?ref=John.3.16") == john316)
        #expect(link("scripturealone://open?ref=urn:osis:John.3.16") == john316)
        #expect(link("scripturealone://open?ref=osis:John.3.16") == john316)
        #expect(link("scripturealone://passage/John.3.16") == john316)
        #expect(link("scripturealone://passage/urn:osis:John.3.16") == john316)
        #expect(link("scripturealone://open?ref=Ps.23") == .osis([Passage(book: .psalms, startChapter: 23)]))
        #expect(link("scripturealone://open?ref=Gen.1.1-Gen.1.3")
                == .osis([Passage(book: .genesis, startChapter: 1, startVerse: 1, endChapter: 1, endVerse: 3)]))
    }

    @Test func opensPlainReferencesInAnyLanguage() {
        let john316 = AppLink.passage([Passage(book: .john, startChapter: 3, startVerse: 16)])
        #expect(link("scripturealone://open?ref=John%203:16") == john316)
        #expect(AppLink.reference("John 3:16", language: "en") == john316)
        #expect(AppLink.reference("Jean 3:16", language: "fr") == john316)
        #expect(AppLink.reference("Johannes 3,16", language: "de") == john316)
        #expect(AppLink.reference("约翰福音 3:16", language: "zh-Hans") == john316)
        #expect(AppLink.reference("요한복음 3:16", language: "ko") == john316)
        #expect(link("scripturealone://passage/Rom%208:28-39")
                == .passage([Passage(book: .romans, startChapter: 8, startVerse: 28, endChapter: 8, endVerse: 39)]))
    }

    @Test func opensOtherPlaces() {
        #expect(link("scripturealone://search?q=love%20one%20another") == .search("love one another"))
        let id = UUID()
        #expect(link("scripturealone://note/\(id.uuidString)") == .note(id))
        #expect(AppLink.noteURL(id).flatMap(AppLink.init(url:)) == .note(id))
        #expect(link("scripturealone://notes") == .notes)
        #expect(link("scripturealone://favorites") == .favorites)
        #expect(link("scripturealone://note/not-a-uuid") == nil)
        #expect(link("scripturealone://search") == nil)
        #expect(link("scripturealone://somewhere") == nil)
    }

    @Test func opensWebPassages() {
        let john316 = AppLink.osis([Passage(book: .john, startChapter: 3, startVerse: 16)])
        #expect(link("https://wemiller.com/apps/scripture-alone/?ref=John.3.16") == john316)
        #expect(link("https://wemiller.com/apps/scripture-alone/#ref=John.3.16") == john316)
        #expect(link("https://wemiller.com/apps/scripture-alone/passage/John.3.16") == john316)
        #expect(link("https://www.wemiller.com/apps/scripture-alone/passage/urn:osis:John.3.16") == john316)
        // Only the passage forms on the web; the app's own places stay on the custom scheme.
        #expect(link("https://wemiller.com/apps/scripture-alone/notes") == nil)
        #expect(link("https://example.com/apps/scripture-alone/?ref=John.3.16") == nil)
        #expect(link("https://wemiller.com/apps/other/?ref=John.3.16") == nil)
    }
}
