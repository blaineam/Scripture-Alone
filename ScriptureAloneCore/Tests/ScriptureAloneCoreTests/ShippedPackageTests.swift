import CryptoKit
import Foundation
import Testing
@testable import ScriptureAloneCore

/// The package the app actually ships, opened the way the app actually opens it.
///
/// `DemoPackageTests` proves the mechanism against packages built on the spot. This proves the
/// artifact: `ScriptureAlone/Resources/Packages/ASV.sabible` is the American Standard Version, the
/// translation the app opens by default, and it ships sealed instead of as a database. There is no
/// `ASV.sqlite` in the app to fall back on — so if the Python tool and the Swift reader drift
/// apart, a fresh install has nothing to read. This is where that is caught instead.
@Suite struct ShippedPackageTests {

    /// The repository, found from this file rather than from the working directory, which SwiftPM
    /// does not promise anything about.
    static let repository = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()   // ScriptureAloneCoreTests
        .deletingLastPathComponent()   // Tests
        .deletingLastPathComponent()   // ScriptureAloneCore
        .deletingLastPathComponent()   // repository root

    static let packageURL = repository
        .appending(path: "ScriptureAlone/Resources/Packages/ASV.sabible")
    static let publisherKeyURL = repository
        .appending(path: "ScriptureAlone/Resources/Packages/bundled-signing.pub")

    /// The same published seed `SealedTranslations` compiles in and `Tools/package_translation.py
    /// bundle` builds with. Published on purpose: it protects a public-domain text, so secrecy
    /// would be theatre.
    static let seed = Data("SCRIPTURE-ALONE-BUNDLED-SEED-v1".utf8)
    static let translationID = "ASV"

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
        try withKnownIssue("the sealed translation has not been built", isIntermittent: true) {
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
        // Verse 0 is the psalm's Hebrew superscription ("A Psalm of David"), which the store
        // carries as its own numbered fragment — so the sealed layout should carry it too.
        #expect(numbered == Set(0...6))
        #expect(layout.blocks.contains { $0.kind == .title })
        #expect(layout.blocks.contains { $0.kind == .poetry1 })

        let opening = try #require(package.verses(in: VerseRange(VerseRef(.psalms, 23, 1))).first)
        #expect(opening.text.lowercased().contains("shepherd"))

        // It is the whole Bible, not a sample: the first chapter and the last, from the same file.
        #expect(package.contains(ChapterRef(.genesis, 1)))
        #expect(package.contains(ChapterRef(.revelation, 22)))
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
        // "Jehovah", not "the LORD" — this is the American Standard Version, and a test that
        // passed against another translation's wording would not be testing this one.
        let prefix = try package.search("jehovah is my shep", limit: 50)
        #expect(prefix.contains { $0.ref == VerseRef(.psalms, 23, 1) })

        // Words that all occur, but never in this order, must not match as a phrase.
        let nonsense = try package.search("\"shepherd loved the world\"", limit: 50)
        #expect(nonsense.isEmpty)
    }

    /// The terms this package carries are the terms of a public-domain text: everything permitted.
    ///
    /// Sealing the American Standard Version protects nobody's rights and is not meant to. A reader
    /// must lose nothing by it — no disabled buttons, no quotation cap — because the only thing
    /// being proved here is that a real translation reads out of a sealed package on every launch.
    /// Enforcement of terms that *forbid* things is proved separately, in `PackagePolicyTests`,
    /// against packages built to forbid them.
    @Test func shippedTranslationIsUnrestricted() throws {
        guard Self.isBuilt else { return }
        let rights = try Self.openShipped().info.rights
        #expect(rights.permits(\.allowCopy))
        #expect(rights.permits(\.allowShare))
        #expect(rights.permits(\.allowVerseImages))
        #expect(rights.permits(\.allowNotesExport))
        #expect(rights.permits(\.allowExternalHandoff))
        #expect(rights.permits(\.allowOfflineStorage))
        #expect(rights.maxQuotationVerses == TranslationRights.unlimitedQuotation)
        // The whole canon is 31,102 verses; an unlimited cap has to clear that and then some.
        #expect(rights.mayQuote(verseCount: 31_102))
        #expect(rights.expires == nil)
    }
}
