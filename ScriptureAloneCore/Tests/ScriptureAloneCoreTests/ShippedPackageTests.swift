import CryptoKit
import Foundation
import Testing
@testable import ScriptureAloneCore

/// The package the app actually ships, opened the way the app actually opens it.
///
/// `DemoPackageTests` proves the mechanism against packages built on the spot. This proves the
/// artifact: `ScriptureAlone/Resources/Packages/BSBX.sabible` — the bytes that go into the
/// `encrypted-demo` asset pack, downloaded on demand and unsealed on the device — read with the
/// seed and the pinned publisher key that `EncryptedDemoLibrary` uses. If the tool and the app ever
/// drift apart, a reader sees a translation that won't open; this is where that is caught instead.
@Suite struct ShippedPackageTests {

    /// The repository, found from this file rather than from the working directory, which SwiftPM
    /// does not promise anything about.
    static let repository = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()   // ScriptureAloneCoreTests
        .deletingLastPathComponent()   // Tests
        .deletingLastPathComponent()   // ScriptureAloneCore
        .deletingLastPathComponent()   // repository root

    static let packageURL = repository
        .appending(path: "ScriptureAlone/Resources/Packages/BSBX.sabible")
    static let publisherKeyURL = repository
        .appending(path: "ScriptureAlone/Resources/Packages/demo-signing.pub")

    /// The same published seed `EncryptedDemoLibrary` compiles in and `Tools/package_translation.py
    /// bundle-demo` builds with. Published on purpose: it protects a public-domain text, so secrecy
    /// would be theatre.
    static let seed = Data("SCRIPTURE-ALONE-DEMO-SEED-v1!!!".utf8)
    static let translationID = "BSBX"

    static var isBuilt: Bool {
        FileManager.default.fileExists(atPath: packageURL.path)
            && FileManager.default.fileExists(atPath: publisherKeyURL.path)
    }

    /// Opens it exactly as the app does: derive the content key from the seed, pin the one
    /// publisher key, verify the signature, open.
    static func openShipped() throws -> TranslationPackage {
        let keyring = try PublisherKeyring(rawPublicKeys: [try Data(contentsOf: publisherKeyURL)])
        let contentKey = ContentKeyVault.deriveContentKey(seed: seed, account: translationID)
        return try TranslationPackage.open(url: packageURL, keyring: keyring, contentKey: contentKey)
    }

    @Test func shippedPackageOpensAndReads() throws {
        try withKnownIssue("the demonstration package has not been built", isIntermittent: true) {
            try #require(Self.isBuilt)
        } when: {
            !Self.isBuilt
        }
        guard Self.isBuilt else { return }

        let package = try Self.openShipped()
        #expect(package.info.id == Self.translationID)
        #expect(package.isSearchable)

        // A chapter chosen for what it carries rather than for being famous: a psalm title, poetry
        // lines, and a verse count the layout has to agree with.
        let psalm = ChapterRef(.psalms, 23)
        #expect(package.contains(psalm))
        #expect(package.verseCount(psalm) == 6)

        // Structure survives the round trip, not just the words: a psalm comes back as poetry
        // lines carrying all six verses, the way the store would have given them.
        let layout = try package.layout(for: psalm)
        let numbered = Set(layout.blocks.flatMap(\.fragments).compactMap(\.verse))
        #expect(numbered == Set(1...6))
        #expect(layout.blocks.contains { $0.kind == .poetry1 })

        let opening = try #require(package.verses(in: VerseRange(VerseRef(.psalms, 23, 1))).first)
        #expect(opening.text.lowercased().contains("shepherd"))
    }

    /// The wrong key is refused at the door, not at the first chapter.
    ///
    /// That is the useful behaviour: a reader whose key is wrong — a lapsed licence, a package from
    /// another device — is told immediately, rather than seeing a translation appear in the list and
    /// fail on whatever chapter they happen to open first.
    @Test func wrongContentKeyIsRefused() throws {
        guard Self.isBuilt else { return }
        let keyring = try PublisherKeyring(rawPublicKeys: [try Data(contentsOf: Self.publisherKeyURL)])
        let wrong = ContentKeyVault.deriveContentKey(seed: Data("not-the-seed".utf8),
                                                     account: Self.translationID)
        #expect(throws: TranslationPackageError.wrongContentKey) {
            try TranslationPackage.open(url: Self.packageURL, keyring: keyring, contentKey: wrong)
        }
    }

    /// A key nobody signed with is not on the keyring, so the package never opens at all.
    @Test func unpinnedPublisherKeyIsRefused() throws {
        guard Self.isBuilt else { return }
        let stranger = Curve25519.Signing.PrivateKey().publicKey.rawRepresentation
        let keyring = try PublisherKeyring(rawPublicKeys: [stranger])
        let contentKey = ContentKeyVault.deriveContentKey(seed: Self.seed, account: Self.translationID)
        #expect(throws: (any Error).self) {
            try TranslationPackage.open(url: Self.packageURL, keyring: keyring, contentKey: contentKey)
        }
    }

    /// Search over the sealed index: a phrase, and a prefix. Both have to find the verse a reader
    /// would expect, because a search that cannot find John 3:16 is not a search.
    @Test func shippedPackageSearches() throws {
        guard Self.isBuilt else { return }
        let package = try Self.openShipped()

        let phrase = try package.search("\"God so loved the world\"", limit: 50)
        #expect(!phrase.isEmpty)
        #expect(phrase.contains { $0.ref == VerseRef(.john, 3, 16) })

        // A bare query matches its last word as a prefix, so a half-typed word still finds it.
        let prefix = try package.search("the lord is my shep", limit: 50)
        #expect(prefix.contains { $0.ref == VerseRef(.psalms, 23, 1) })

        // Words that all occur, but never in this order, must not match as a phrase.
        let nonsense = try package.search("\"shepherd loved the world\"", limit: 50)
        #expect(nonsense.isEmpty)
    }

    /// The policy the package carries is the policy the app enforces. This one is deliberately
    /// restrictive — it stands in for a licensed translation — so the refusals are the point.
    @Test func shippedPolicyIsEnforced() throws {
        guard Self.isBuilt else { return }
        let rights = try Self.openShipped().info.rights
        #expect(rights.allowCopy)
        #expect(!rights.allowNotesExport)
        #expect(!rights.allowExternalHandoff)
        #expect(rights.maxQuotationVerses == 25)

        // The cap is a boundary, so check the boundary rather than the number beside it. This is
        // what the reader's copy and share controls are disabled by.
        #expect(rights.mayQuote(verseCount: 25))
        #expect(!rights.mayQuote(verseCount: 26))
        #expect(!rights.permits(\.allowNotesExport))
        #expect(!rights.permits(\.allowExternalHandoff))
    }
}
