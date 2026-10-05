import Foundation
import Testing
import ScriptureAloneCore
@testable import Scripture_Alone

/// The sealed translations as the app opens them: pinned key from the bundle, content key through
/// the device vault (or derived when the simulator's keychain won't cooperate), chapter decrypted on
/// read. Debug builds carry the ASV package (the project's Debug-only copy phase), so it is opened
/// here exactly as a reader's downloaded copy would be.
@MainActor
struct SealedTranslationsTests {
    @Test func theASVOpensAndReadsGenesis() throws {
        let sealed = SealedTranslations.shared
        #expect(sealed.failure("ASV") == nil, "ASV failed to open: \(sealed.failure("ASV") ?? "")")
        let package = try #require(sealed.package("ASV"))
        #expect(package.info.id == "ASV")
        let verses = try package.verses(in: VerseRange(VerseRef(.genesis, 1, 1), VerseRef(.genesis, 1, 3)))
        #expect(verses.map(\.ref.verse) == [1, 2, 3])
        #expect(verses.first?.text.contains("In the beginning God created the heavens and the earth") == true)
        let layout = try package.layout(for: ChapterRef(.john, 3))
        #expect(!layout.blocks.isEmpty)
    }

    @Test func reopeningKeepsItOpen() {
        #expect(SealedTranslations.shared.reopen("ASV"))
        #expect(SealedTranslations.shared.package("ASV") != nil)
    }

    @Test func onlySealedIdentifiersShip() {
        #expect(SealedTranslations.ships("ASV"))
        #expect(!SealedTranslations.ships("KJV"), "a plain database is not a sealed translation")
        #expect(!SealedTranslations.ships("ESV"))
        #expect(SealedTranslations.identifiers == ["NASB2020", "NASB1995", "ASV"])
        #expect(SealedTranslations.licensedIdentifiers.contains(SealedTranslations.licensedDefault))
    }

    /// A build without the secret seed (every local and fork build) offers no licensed translation,
    /// and says why instead of spinning. A build with it opens every licensed package it carries.
    @Test func licensedTranslationsNeedTheSeed() {
        let sealed = SealedTranslations.shared
        for id in SealedTranslations.licensedIdentifiers {
            if ContentKeySeed.data == nil {
                #expect(!SealedTranslations.ships(id))
                #expect(sealed.package(id) == nil)
                #expect(sealed.failure(id) != nil, "\(id) gives no reason")
            } else if SealedTranslations.isInApp(id) {
                #expect(SealedTranslations.ships(id))
                #expect(sealed.package(id) != nil, "\(id) is in the app but failed: \(sealed.failure(id) ?? "")")
            }
        }
    }

    /// The ASV's seed is published on purpose; a change to it would strand every package already
    /// downloaded.
    @Test func thePublishedASVSeedIsPinned() {
        #expect(SealedTranslations.seed == Data("SCRIPTURE-ALONE-BUNDLED-SEED-v1".utf8))
    }
}
