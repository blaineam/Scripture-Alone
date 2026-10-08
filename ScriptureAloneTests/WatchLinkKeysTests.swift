import Foundation
import Testing
@testable import Scripture_Alone

/// The phone ↔ watch vocabulary. A received file is saved under its translation id, so the id check
/// is all that stands between a message and the watch's file system.
struct WatchLinkKeysTests {
    @Test(arguments: ["ASV", "NASB2020", "Imported-1A2B", "my_bible", String(repeating: "A", count: 64)])
    func safeIDs(id: String) {
        #expect(WatchLinkKeys.isSafeID(id))
    }

    @Test(arguments: ["", "../ASV", "a/b", "ASV.sqlite", "a b", "Ü", "名前", String(repeating: "A", count: 65), "a\nb"])
    func unsafeIDs(id: String) {
        #expect(!WatchLinkKeys.isSafeID(id))
    }

    @Test func sealedTranslationsTravelSealed() {
        #expect(WatchLinkKeys.sealed == Set(SealedTranslations.identifiers))
    }

    /// Phone and watch read the same keys from the same dictionaries; two keys sharing a name would
    /// make one message read as another.
    @Test func keysAreDistinct() {
        let keys = [WatchLinkKeys.translation, WatchLinkKeys.changedAt, WatchLinkKeys.editions,
                    WatchLinkKeys.editionVersions, WatchLinkKeys.imports, WatchLinkKeys.accent,
                    WatchLinkKeys.bundled, WatchLinkKeys.version, WatchLinkKeys.kind,
                    WatchLinkKeys.translationNotForWatch, WatchLinkKeys.translationLabel]
        #expect(Set(keys).count == keys.count)
        #expect(WatchLinkKeys.legacyBundled == ["ASV", "BSB", "KJV"], "what 1.1.0 watches carry; never changes")
    }

    /// The watch's footers name the phone's translation as the phone does, not by its internal id.
    @Test func theWatchNamesThePhonesTranslationAsThePhoneDoes() {
        #expect(WatchLinkKeys.label("NASB 1995", for: "NASB1995") == "NASB 1995")
        #expect(WatchLinkKeys.label("ESV", for: "IMPORT-NN0XUW") == "ESV")
        // An older phone app sends none; an empty or absurd one is ignored.
        #expect(WatchLinkKeys.label(nil, for: "NASB1995") == "NASB1995")
        #expect(WatchLinkKeys.label("  ", for: "NASB1995") == "NASB1995")
        #expect(WatchLinkKeys.label(String(repeating: "A", count: 65), for: "X") == "X")
    }
}
