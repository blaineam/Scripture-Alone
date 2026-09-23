import Foundation
import Testing
@testable import ScriptureAloneCore

/// The big-8 locales' Bibles keep their own verse numbers; marks are stored under KJV keys.
/// Runs against the databases Tools/build_bibles.py builds (docs/localization.md).
@Suite struct VerseNumberingTests {
    func store(_ id: String) throws -> BibleStore {
        try BibleStore(url: BibleStoreTests.biblesDirectory.appending(path: "\(id).sqlite"))
    }

    func key(_ book: BookID, _ chapter: Int, _ verse: Int) -> Int { VerseRef(book, chapter, verse).key }

    @Test func englishBiblesAreIdentity() throws {
        for id in ["ASV", "BSB", "KJV"] {
            #expect(try store(id).numbering.isIdentity, "\(id) should number as the KJV does")
        }
    }

    @Test func segondPsalmsAndChapterBreaks() throws {
        let lsg = try store("LSG").numbering
        // "O Dieu! crée en moi un cœur pur" — French 51:12 is English 51:10.
        #expect(lsg.kjv(forNative: key(.psalms, 51, 12)) == key(.psalms, 51, 10))
        #expect(lsg.native(forKJV: key(.psalms, 51, 10)) == key(.psalms, 51, 12))
        // The title verses fold onto verse 1, and verse 1 comes back to the verse, not the title.
        #expect(lsg.kjv(forNative: key(.psalms, 51, 1)) == key(.psalms, 51, 1))
        #expect(lsg.native(forKJV: key(.psalms, 51, 1)) == key(.psalms, 51, 3))
        // The Hebrew chapter break: French Exodus 7:26 is English 8:1, and back.
        #expect(lsg.kjv(forNative: key(.exodus, 7, 26)) == key(.exodus, 8, 1))
        #expect(lsg.native(forKJV: key(.exodus, 8, 1)) == key(.exodus, 7, 26))
        #expect(lsg.native(forKJV: key(.exodus, 8, 5)) == key(.exodus, 8, 1))
        // A verse neither side moved is itself.
        #expect(lsg.kjv(forNative: key(.john, 3, 16)) == key(.john, 3, 16))
    }

    @Test func aMarkedChapterSpansItsKJVKeys() throws {
        let lsg = try store("LSG")
        // French Exodus 8 holds English 8:5 onward; its marks must be found by that key range.
        let range = lsg.numbering.kjvKeyRange(of: ChapterRef(.exodus, 8), verseCount: lsg.verseCount(ChapterRef(.exodus, 8)))
        #expect(range.lowerBound == key(.exodus, 8, 5))
        let french7 = lsg.numbering.kjvKeyRange(of: ChapterRef(.exodus, 7), verseCount: lsg.verseCount(ChapterRef(.exodus, 7)))
        #expect(french7.contains(key(.exodus, 8, 4)))
    }

    @Test func versesAreReadByKJVKeyAndKeepTheirOwnNumbers() throws {
        let lsg = try store("LSG")
        let verses = try lsg.verses(in: VerseRange(VerseRef(.psalms, 51, 10)))
        #expect(verses.map(\.ref) == [VerseRef(.psalms, 51, 12)])
        #expect(verses.first?.text.contains("cœur pur") == true)
    }

    @Test func aVerseThatHoldsNineKJVVerses() throws {
        let rvr = try store("RVR1909").numbering
        let native = key(.job, 39, 30)
        #expect(rvr.kjvKeys(forNative: native) == key(.job, 39, 27)...key(.job, 40, 5))
        #expect(rvr.native(forKJV: key(.job, 40, 3)) == native)
        #expect(rvr.native(forKJV: key(.job, 39, 28)) == native)
    }

    @Test func printedRangesDecodeAndMap() throws {
        let cuv = try store("CUVS")
        let layout = try cuv.layout(for: ChapterRef(.deuteronomy, 13))
        let range = try #require(layout.blocks.flatMap(\.fragments).first { $0.verse == 12 && $0.numbered })
        #expect(range.lastVerse == 13)
        #expect(range.label == "12–13")
        #expect(cuv.numbering.native(forKJV: key(.deuteronomy, 13, 13)) == key(.deuteronomy, 13, 12))
    }

    @Test func nativeBookNamesAndLanguage() throws {
        let lsg = try store("LSG")
        #expect(lsg.language == "fr")
        #expect(lsg.bookNames[.genesis] == "Genèse")
        #expect(try store("BSB").bookNames.isEmpty)
    }

    @Test func chineseSearchFindsTwoCharacterWords() throws {
        let cuv = try store("CUVS")
        let grace = try cuv.search("恩典", limit: 500)          // two characters: LIKE
        #expect(grace.count > 40)
        let jehovah = try cuv.search("耶和华", limit: 10)        // three: the trigram index
        #expect(jehovah.count == 10)
        let both = try cuv.search("神 爱世人", limit: 10)        // mixed
        #expect(both.contains { $0.ref == VerseRef(.john, 3, 16) })
    }

    @Test func koreanSearchIgnoresParticles() throws {
        // 하나님 appears as 하나님이, 하나님을, 하나님의… — a word index would miss them all.
        let hits = try store("KRV").search("하나님", limit: 5000)
        #expect(hits.count > 3000)
    }

    @Test func searchHitsCarryTheirKJVKey() throws {
        let hit = try #require(try store("LSG").search("cœur pur", limit: 50).first { $0.ref == VerseRef(.psalms, 51, 12) })
        #expect(hit.kjv == VerseRef(.psalms, 51, 10))
    }
}
