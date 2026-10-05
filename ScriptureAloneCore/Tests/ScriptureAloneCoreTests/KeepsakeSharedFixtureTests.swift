import Foundation
import Testing
@testable import ScriptureAloneCore

/// The keepsake format is shared with Android. Android's `KeepsakeTest` opens the files in
/// `android/app/src/test/resources/keepsake` and checks that it writes the same bytes; these tests
/// hold the Swift side to the same files, so a change to the Swift writer or reader that would
/// strand an Android user's keepsake (or an iPhone user's on Android) fails here, not in the field.
struct KeepsakeSharedFixtureTests {
    static let fixtures = URL(filePath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .appending(path: "android/app/src/test/resources/keepsake")

    static func fixture(_ name: String) throws -> Data {
        try Data(contentsOf: fixtures.appending(path: name))
    }

    static func date(_ seconds: Double) -> Date { Date(timeIntervalSince1970: seconds) }

    /// `KeepsakeTests.sample()` with the fixed identifiers the fixtures were written with
    /// (the same values Android's `KeepsakeTest.sample()` uses).
    static func sample() -> Keepsake {
        var manifest = KeepsakeManifest(bibleID: UUID(uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")!,
                                        ownerName: "Dad", dedication: "For Anna and Sam — read it slowly.",
                                        preferredTranslation: "ASV", generator: "Scripture Alone 1.0.0",
                                        createdAt: date(1_790_000_000.125))
        manifest.exportID = UUID(uuidString: "11111111-2222-3333-4444-555555555555")!
        let john316 = VerseRef(.john, 3, 16)
        var keepsake = Keepsake(
            manifest: manifest,
            highlights: [
                KeepsakeHighlight(verse: john316.key, color: "yellow", createdAt: date(1_700_000_000.5)),
                KeepsakeHighlight(verse: VerseRef(.psalms, 23, 1).key, color: "blue", createdAt: date(1_710_000_000)),
            ],
            notes: [
                KeepsakeNote(id: UUID(uuidString: "0E1B5C2A-9F3D-4E6B-8A7C-1D2E3F405162")!,
                             title: "No condemnation", body: "Pastor Jim, Sunday.\nLine two — “quoted”.",
                             anchors: [VerseRange(VerseRef(.romans, 8, 1), VerseRef(.romans, 8, 17))],
                             createdAt: date(1_720_000_000.25), updatedAt: date(1_730_000_000)),
                KeepsakeNote(id: UUID(uuidString: "7A8B9C0D-1E2F-4A5B-8C6D-7E8F90A1B2C3")!,
                             title: "", body: "", anchors: [VerseRange(john316)],
                             createdAt: date(1_740_000_000), updatedAt: date(1_740_000_000), origin: "camera"),
            ])
        keepsake.refreshSummary()
        return keepsake
    }

    @Test func opensTheSharedPlainFixture() throws {
        let decoded = try KeepsakeArchive.decode(try Self.fixture("swift-plain.scripturelegacy"))
        let expected = Self.sample()
        #expect(decoded.manifest.bibleID == expected.manifest.bibleID)
        #expect(decoded.manifest.exportID == expected.manifest.exportID)
        #expect(decoded.manifest.ownerName == "Dad")
        #expect(decoded.highlights == expected.highlights)
        #expect(decoded.notes == expected.notes)
    }

    @Test func opensTheSharedProtectedFixtureOnlyWithItsPassphrase() throws {
        let data = try Self.fixture("swift-sealed.scripturelegacy")
        let outer = try KeepsakeArchive.peek(data)
        #expect(outer.isEncrypted)
        #expect(outer.passphraseHint == "Our first dog")
        #expect(outer.encryption?.iterations == 2_000)
        let decoded = try KeepsakeArchive.decode(data, passphrase: "Mañana, grace")
        #expect(decoded.notes == Self.sample().notes)
        #expect(decoded.highlights == Self.sample().highlights)
        // The decomposed spelling of the same passphrase opens it too (NFC on both platforms)…
        #expect((try? KeepsakeArchive.decode(data, passphrase: "Man\u{0303}ana, grace")) != nil)
        // …but a different one, or none, fails cleanly.
        #expect(throws: KeepsakeError.wrongPassphrase) { try KeepsakeArchive.decode(data, passphrase: "mañana, grace") }
        #expect(throws: KeepsakeError.passphraseRequired) { try KeepsakeArchive.decode(data) }
    }

    /// The Swift writer still produces, file for file, the bytes Android checks itself against.
    @Test func writesTheBytesAndroidExpects() throws {
        let shared = try ZipArchive.read(try Self.fixture("swift-plain.scripturelegacy"))
        let ours = try ZipArchive.read(try KeepsakeArchive.encode(Self.sample()))
        #expect(Set(shared.keys) == Set(ours.keys))
        for name in shared.keys.sorted() {
            #expect(String(decoding: shared[name]!, as: UTF8.self) == String(decoding: ours[name] ?? Data(), as: UTF8.self),
                    "\(name) drifted from the shared fixture")
        }
        let sealed = try ZipArchive.read(try Self.fixture("swift-sealed.scripturelegacy"))
        #expect(String(decoding: sealed["README.txt"] ?? Data(), as: UTF8.self) == KeepsakeArchive.readme(protected: true))
    }
}
