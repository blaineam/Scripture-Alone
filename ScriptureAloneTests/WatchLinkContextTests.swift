#if os(iOS)
import Foundation
import Testing
@testable import Scripture_Alone

/// What the iPhone tells the Apple Watch in its application context (1.1.4): the reader's
/// translation with when it was chosen, the wearables flag for one whose publisher keeps it off
/// watches, and the name the reader sees for the watch's picker footer — never the internal id.
@MainActor
struct WatchLinkContextTests {
    let url = URL(fileURLWithPath: "/tmp/none.sqlite")

    func context(_ translation: TranslationEntry?, imports: [TranslationEntry]? = nil, accent: Int? = nil,
                 offWatch: Set<String> = []) -> [String: Any] {
        WatchLink.applicationContext(imports: imports, accent: accent, translation: translation, changedAt: 1_700_000_000,
                                     keptOffWatch: { offWatch.contains($0) })
    }

    @Test func aBundledTranslationGoesByItsIdAlone() {
        let sent = context(TranslationEntry(id: "BSB", name: "Berean Standard Bible", url: url))
        #expect(sent[WatchLinkKeys.translation] as? String == "BSB")
        #expect(sent[WatchLinkKeys.changedAt] as? TimeInterval == 1_700_000_000)
        #expect(sent[WatchLinkKeys.translationNotForWatch] == nil)
        #expect(sent[WatchLinkKeys.translationLabel] == nil, "the watch names the BSB itself")
    }

    @Test func aTranslationLicensedOffWatchesIsFlaggedAndNamedAsTheReaderSeesIt() {
        let nasb = TranslationEntry(id: "NASB1995", name: "New American Standard Bible 1995", source: .package, abbreviation: "NASB 1995")
        let sent = context(nasb, offWatch: ["NASB1995"])
        #expect(sent[WatchLinkKeys.translationNotForWatch] as? Bool == true)
        #expect(sent[WatchLinkKeys.translationLabel] as? String == "NASB 1995")
        // What the watch's picker then shows.
        #expect(WatchLinkKeys.label(sent[WatchLinkKeys.translationLabel] as? String, for: "NASB1995") == "NASB 1995")
    }

    @Test func anImportIsNamedByItsAbbreviation() {
        let esv = TranslationEntry(id: "IMPORT-NN0XUW", name: "English Standard Version", url: url, abbreviation: "ESV")
        let sent = context(esv, imports: [esv])
        #expect(sent[WatchLinkKeys.translationLabel] as? String == "ESV")
        #expect(sent[WatchLinkKeys.imports] as? [String] == ["IMPORT-NN0XUW"])
        #expect(sent[WatchLinkKeys.translationNotForWatch] == nil)
    }

    /// The context is replaced whole on every update: no imports key until the library has reported
    /// (an empty list would delete every import on the watch), and nothing about a translation before one.
    @Test func onlyWhatIsKnownIsSent() {
        let empty = context(nil)
        #expect(empty.isEmpty)
        let accentOnly = context(nil, accent: 0x3366CC)
        #expect(accentOnly[WatchLinkKeys.accent] as? Int == 0x3366CC)
        #expect(accentOnly[WatchLinkKeys.imports] == nil)
        #expect(context(nil, imports: [])[WatchLinkKeys.imports] as? [String] == [], "a reported empty library is sent")
    }
}
#endif
