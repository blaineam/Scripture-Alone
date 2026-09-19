import Foundation
import Testing
@testable import ScriptureAloneCore

/// Runs against the study database the app bundles (built by Tools/build_study.py).
@Suite struct StudyStoreTests {
    static let databaseURL = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .appending(path: "ScriptureAlone/Resources/Study/Study.sqlite")

    func store() throws -> StudyStore { try StudyStore(url: Self.databaseURL) }

    @Test func listsSourcesWithLicenses() throws {
        let store = try store()
        #expect(store.crossReferenceSource?.id == "openbible")
        #expect(store.crossReferenceSource?.license == "CC BY 4.0")
        #expect(store.commentarySources.map(\.id) == ["calvin", "gill", "jfb"])
        for source in store.sources {
            #expect(!source.attribution.isEmpty, "\(source.id) has no attribution")
            #expect(source.licenseURL != nil, "\(source.id) has no license link")
        }
    }

    @Test func crossReferencesAreRankedStrongestFirst() throws {
        let refs = try store().crossReferences(for: VerseRef(.john, 3, 16))
        #expect(refs.count >= 15)
        #expect(refs.first?.target == VerseRange(VerseRef(.romans, 5, 8)))
        #expect(refs.prefix(5).contains { $0.target == VerseRange(VerseRef(.firstJohn, 4, 9), VerseRef(.firstJohn, 4, 10)) })
        #expect(refs.map(\.votes) == refs.map(\.votes).sorted(by: >))
    }

    @Test func versesWithoutCrossReferencesReturnEmpty() throws {
        #expect(try store().crossReferences(for: VerseRef(.john, 3, 999)).isEmpty)
    }

    @Test func countsCrossReferencesPerVerse() throws {
        let counts = try store().crossReferenceCounts(in: ChapterRef(.john, 3))
        #expect((counts[VerseRef(.john, 3, 16).key] ?? 0) >= 15)
        #expect(counts.keys.allSatisfy { ChapterRef(.john, 3).keyRange.contains($0) })
    }

    @Test func decodesPackedRecords() {
        // Hebrews 11:3 (300 votes), then 1 John 4:9–10 (7 votes); little-endian UInt32, UInt32, UInt16.
        func le(_ value: Int, _ width: Int) -> [UInt8] { (0..<width).map { UInt8(truncatingIfNeeded: value >> (8 * $0)) } }
        let bytes = le(58_011_003, 4) + le(58_011_003, 4) + le(300, 2)
            + le(62_004_009, 4) + le(62_004_010, 4) + le(7, 2)
            + [0xFF, 0xFF] // a truncated trailing record is ignored
        let refs = StudyStore.decodeCrossReferences(Data(bytes))
        #expect(refs == [
            CrossReference(target: VerseRange(VerseRef(.hebrews, 11, 3)), votes: 300),
            CrossReference(target: VerseRange(VerseRef(.firstJohn, 4, 9), VerseRef(.firstJohn, 4, 10)), votes: 7),
        ])
    }

    @Test func commentaryCoversTheVerseFromEverySource() throws {
        let store = try store()
        let verse = VerseRef(.john, 3, 16)
        let gill = try #require(try store.commentary("gill", on: verse).first)
        #expect(gill.range == VerseRange(verse))
        #expect(gill.text.hasPrefix("For God so loved the world"))
        let calvin = try #require(try store.commentary("calvin", on: verse).first)
        #expect(calvin.range == VerseRange(VerseRef(.john, 3, 13), VerseRef(.john, 3, 18)))
        let jfb = try #require(try store.commentary("jfb", on: verse).first)
        #expect(jfb.text.hasPrefix("For God so loved"))
        #expect(try store.sourcesCommenting(on: verse) == ["calvin", "gill", "jfb"])
    }

    @Test func psalm23HasCommentary() throws {
        let store = try store()
        for source in ["calvin", "gill"] {
            let entries = try store.commentary(source, on: VerseRef(.psalms, 23, 1))
            #expect(entries.contains { $0.text.localizedCaseInsensitiveContains("shepherd") }, "\(source)")
        }
        // JFB speaks to verse 1 in the psalm's introduction.
        let intro = try #require(try store.introduction("jfb", to: ChapterRef(.psalms, 23)))
        #expect(intro.text.localizedCaseInsensitiveContains("shepherd"))
    }

    @Test func chapterIntroductionsAreSeparate() throws {
        let store = try store()
        let intro = try #require(try store.introduction("jfb", to: ChapterRef(.john, 3)))
        #expect(intro.isIntroduction)
        #expect(intro.text.contains("Nicodemus"))
        #expect(try store.commentary("jfb", on: VerseRef(.john, 3, 2)).allSatisfy { !$0.isIntroduction })
        let chapter = try store.commentary("jfb", in: ChapterRef(.john, 3))
        #expect(chapter.first?.isIntroduction == true)
        #expect(!chapter.dropFirst().contains { $0.isIntroduction })
    }

    @Test func entriesSplitIntoParagraphs() throws {
        let entry = try #require(try store().commentary("calvin", on: VerseRef(.john, 3, 16)).first)
        #expect(entry.paragraphs.count > 1)
        #expect(entry.paragraphs.allSatisfy { !$0.hasPrefix(" ") && !$0.isEmpty })
    }
}
