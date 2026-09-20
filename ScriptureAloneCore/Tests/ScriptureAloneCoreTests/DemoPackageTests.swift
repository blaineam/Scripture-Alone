import Foundation
import CryptoKit
import Testing
@testable import ScriptureAloneCore

/// The demonstration: the bundled public-domain texts, packaged and read back through the same
/// interface the reader uses for a SQLite store.
///
/// This is what a publisher is shown. Nothing here needs anyone's licensed text — the American
/// Standard Version and the Berean Standard Bible carry the mechanism, and a licensed translation
/// would travel exactly the same path.
@Suite struct DemoPackageTests {

    static let bibles = BibleStoreTests.biblesDirectory

    /// Where `Tools/package_translation.py demo` puts its packages and its demonstration keys —
    /// outside the repository, like any other key.
    static let demoDirectory = URL(fileURLWithPath: NSHomeDirectory()).appending(path: ".scripture-alone-demo")

    static var demoPackagesExist: Bool {
        ["ASV.sabible", "BSB.sabible", "content.key", "signing.pub"].allSatisfy {
            FileManager.default.fileExists(atPath: demoDirectory.appending(path: $0).path)
        }
    }

    static func hexKeyFile(_ name: String) throws -> Data {
        let text = try String(contentsOf: demoDirectory.appending(path: name), encoding: .utf8)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        var bytes = Data()
        var index = text.startIndex
        while index < text.endIndex {
            let next = text.index(index, offsetBy: 2)
            bytes.append(UInt8(text[index..<next], radix: 16)!)
            index = next
        }
        return bytes
    }

    /// Chapters spread across the canon, and chosen for what they exercise: poetry with a psalm title,
    /// the longest chapter in scripture, words of Christ, footnotes, and the last chapter of the last
    /// book.
    static let spotChecks = [ChapterRef(.genesis, 1), ChapterRef(.psalms, 23), ChapterRef(.psalms, 119),
                             ChapterRef(.john, 3), ChapterRef(.john, 14), ChapterRef(.revelation, 22)]

    static func demonstrationIdentity(_ store: BibleStore) -> PackagedTranslationIdentity {
        PackagedTranslationIdentity(id: store.info.id, name: store.info.name,
                                    abbreviation: store.info.abbreviation,
                                    publisher: "Scripture Alone demonstration",
                                    copyright: store.info.copyright, license: store.info.license)
    }

    /// A whole Bible, packaged from the store the app ships and read back verse for verse. 1,189
    /// chapters sealed separately, and the package opened without decrypting any of them.
    @Test(arguments: ["ASV", "BSB"]) func aBundledTranslationPackagesAndReadsBackIdentically(_ abbreviation: String) throws {
        let store = try BibleStore(url: Self.bibles.appending(path: "\(abbreviation).sqlite"))
        let contentKey = SymmetricKey(size: .bits256)
        let signingKey = Curve25519.Signing.PrivateKey()

        let chapters = try store.info.id.isEmpty ? [] : BookID.allCases.flatMap { book in
            try (1...book.chapterCount).compactMap { chapter -> TranslationPackageWriter.SourceChapter? in
                let ref = ChapterRef(book, chapter)
                return store.contains(ref) ? try store.packagingChapter(ref) : nil
            }
        }
        #expect(chapters.count == 1_189)

        let bytes = try TranslationPackageWriter.data(identity: Self.demonstrationIdentity(store),
                                                     policy: .publisherStandard, chapters: chapters,
                                                     contentKey: contentKey, signingKey: signingKey)
        let url = URL.temporaryDirectory.appending(path: "demo-\(abbreviation)-\(UUID().uuidString).sabible")
        try bytes.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }

        let keyring = try PublisherKeyring(rawPublicKeys: [signingKey.publicKey.rawRepresentation])
        let package = try TranslationPackage.open(url: url, keyring: keyring, contentKey: contentKey)
        #expect(package.chapters.count == 1_189)
        #expect(package.info.abbreviation == store.info.abbreviation)

        for ref in Self.spotChecks {
            let packaged = try package.chapter(ref)
            let stored = try store.layout(for: ref)
            #expect(packaged.layout.blocks.count == stored.blocks.count, "\(ref.display) layout")
            #expect(packaged.layout.blocks.flatMap(\.fragments).map(\.text)
                    == stored.blocks.flatMap(\.fragments).map(\.text), "\(ref.display) text")
            #expect(packaged.verses.count == store.verseCount(ref), "\(ref.display) verse count")

            let range = VerseRange(VerseRef(ref.book, ref.chapter, 1),
                                   VerseRef(ref.book, ref.chapter, store.verseCount(ref)))
            let storedVerses = try store.verses(in: range)
            #expect(packaged.verses.map(\.text) == storedVerses.map(\.text), "\(ref.display) verses")
            #expect(packaged.verses.map(\.red) == storedVerses.map(\.red), "\(ref.display) red letters")
        }

        // The claim the whole format exists for, against a whole Bible: there is no call that returns
        // it. Asking for every verse from Genesis to Revelation is refused before a byte is decrypted.
        #expect(throws: TranslationPackageError.rangeTooLarge(chapters: 1_189)) {
            try package.verses(in: VerseRange(VerseRef(.genesis, 1, 1), VerseRef(.revelation, 22, 21)))
        }
    }

    /// The seam the app reads through. One variable, two kinds of translation: a SQLite store and a
    /// signed package, asked the same questions and giving the same answers.
    @Test func aPackageAndAStoreAreReadThroughOneInterface() throws {
        let store = try BibleStore(url: Self.bibles.appending(path: "ASV.sqlite"))
        let chapters = try [ChapterRef(.john, 3), ChapterRef(.john, 14)].map { try store.packagingChapter($0) }
        let contentKey = SymmetricKey(size: .bits256)
        let signingKey = Curve25519.Signing.PrivateKey()
        let bytes = try TranslationPackageWriter.data(identity: Self.demonstrationIdentity(store),
                                                     policy: .publisherStandard, chapters: chapters,
                                                     contentKey: contentKey, signingKey: signingKey)
        let url = URL.temporaryDirectory.appending(path: "seam-\(UUID().uuidString).sabible")
        try bytes.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let package = try TranslationPackage.open(
            url: url,
            keyring: try PublisherKeyring(rawPublicKeys: [signingKey.publicKey.rawRepresentation]),
            contentKey: contentKey)

        let sources: [any ChapterTextSource] = [store, package]
        for source in sources {
            #expect(source.info.abbreviation == "ASV")
            #expect(source.contains(ChapterRef(.john, 3)))
            #expect(source.verseCount(ChapterRef(.john, 3)) == 36)
            let layout = try source.layout(for: ChapterRef(.john, 3))
            let verse16 = try #require(layout.blocks.flatMap(\.fragments).first { $0.verse == 16 && $0.numbered })
            #expect(verse16.text.hasPrefix("For God so loved the world"))
            let selection = try source.verses(in: VerseRange(VerseRef(.john, 3, 16), VerseRef(.john, 3, 17)))
            #expect(selection.map(\.ref) == [VerseRef(.john, 3, 16), VerseRef(.john, 3, 17)])
            // …and the same gate, answering for the terms each one carries.
            #expect(source.info.mayQuote(verseCount: 2))
        }
        // The store's terms come from its licence line; the package's come from its signed policy.
        #expect(store.info.grantedRights == nil)
        let demonstrationRights = try PackagePolicy.publisherStandard.rights()
        #expect(package.info.grantedRights == demonstrationRights)
        #expect(store.info.mayHandOffToOtherApps)          // public domain: free to pass on
        #expect(!package.info.mayHandOffToOtherApps)       // the demonstration policy withholds it
    }

    /// Cross-language conformance: the packages `Tools/package_translation.py demo` built, opened by
    /// the Swift reader with the key and pinned public key from that same directory.
    ///
    /// This is the test that proves a publisher can build a package on their own machine, with their
    /// own keys and their own tooling, and have this app read it. It is skipped when the demonstration
    /// packages have not been built — run `Tools/package_translation.py demo` first.
    @Test(.enabled(if: DemoPackageTests.demoPackagesExist))
    func theToolsDemoPackagesOpenInTheApp() throws {
        let contentKey = SymmetricKey(data: try Self.hexKeyFile("content.key"))
        let keyring = try PublisherKeyring(rawPublicKeys: [try Self.hexKeyFile("signing.pub")])

        for abbreviation in ["ASV", "BSB"] {
            let package = try TranslationPackage.open(url: Self.demoDirectory.appending(path: "\(abbreviation).sabible"),
                                                      keyring: keyring, contentKey: contentKey)
            let store = try BibleStore(url: Self.bibles.appending(path: "\(abbreviation).sqlite"))
            #expect(package.info.abbreviation == abbreviation)
            #expect(package.publisher == "Scripture Alone demonstration")
            #expect(package.chapters.count == 1_189)
            #expect(!package.info.mayHandOffToOtherApps)
            #expect(package.rights.maxQuotationVerses == 500)

            for ref in Self.spotChecks {
                let packaged = try package.chapter(ref)
                let range = VerseRange(VerseRef(ref.book, ref.chapter, 1),
                                       VerseRef(ref.book, ref.chapter, store.verseCount(ref)))
                #expect(packaged.verses.map(\.text) == (try store.verses(in: range)).map(\.text),
                        "\(abbreviation) \(ref.display)")
                #expect(packaged.layout.blocks.count == (try store.layout(for: ref)).blocks.count,
                        "\(abbreviation) \(ref.display) layout")
            }

            // Read one verse out of a package built by another implementation, and print it: the
            // demonstration in one line.
            let john316 = try #require(package.verses(in: VerseRange(VerseRef(.john, 3, 16))).first)
            print("\(abbreviation) \(john316.ref.display) — \(john316.text)")
        }
    }

    /// The index the Python tool built, searched by the Swift reader. This is the interop check that
    /// matters most for the index: the tokeniser, the HKDF derivation, the HMAC tags, the bucket
    /// arithmetic and the varint posting encoding must all agree between two implementations, and a
    /// search returning the same verses as the app's own FTS5 index is the proof that they do.
    @Test(.enabled(if: DemoPackageTests.demoPackagesExist))
    func theToolsDemoIndexIsSearchedByTheApp() throws {
        let contentKey = SymmetricKey(data: try Self.hexKeyFile("content.key"))
        let keyring = try PublisherKeyring(rawPublicKeys: [try Self.hexKeyFile("signing.pub")])

        for abbreviation in ["ASV", "BSB"] {
            let package = try TranslationPackage.open(url: Self.demoDirectory.appending(path: "\(abbreviation).sabible"),
                                                      keyring: keyring, contentKey: contentKey)
            let store = try BibleStore(url: Self.bibles.appending(path: "\(abbreviation).sqlite"))
            #expect(package.isSearchable)
            #expect(package.header.index?.tokenizer == PackageSearchIndex.tokenizer)
            #expect(package.header.index?.buckets == PackageSearchIndex.defaultBucketCount)
            #expect(package.header.index?.prefixMax == PackageSearchIndex.defaultPrefixMax)

            for query in ["shepherd", "good shep", "\"Jesus wept\"", "faith hope love", "begotten"] {
                #expect(try package.search(query).map(\.ref) == (try store.search(query)).map(\.ref),
                        "\(abbreviation): “\(query)” in a package the Python tool built")
            }

            let counts = package.accessCounts
            #expect(counts.buckets <= 12, "\(abbreviation) opened \(counts.buckets) of 256 buckets for five searches")
            #expect(counts.chapters <= 200, "\(abbreviation) decrypted \(counts.chapters) of 1,189 chapters for five searches")
        }
    }

    /// A wrong key against the tool's own packages, so the claim is tested against the real artefact
    /// and not only against packages this test suite wrote.
    @Test(.enabled(if: DemoPackageTests.demoPackagesExist))
    func theToolsDemoPackagesRefuseAWrongKey() throws {
        let keyring = try PublisherKeyring(rawPublicKeys: [try Self.hexKeyFile("signing.pub")])
        let url = Self.demoDirectory.appending(path: "ASV.sabible")
        #expect(throws: TranslationPackageError.wrongContentKey) {
            try TranslationPackage.open(url: url, keyring: keyring, contentKey: SymmetricKey(size: .bits256))
        }
        let package = try TranslationPackage(url: url, keyring: keyring,
                                             contentKey: SymmetricKey(size: .bits256),
                                             now: Date(), checkKeyID: false)
        #expect(throws: TranslationPackageError.chapterTampered(ChapterRef(.john, 3))) {
            try package.chapter(ChapterRef(.john, 3))
        }
        // And an unpinned signing key is refused whatever content key is offered.
        #expect(throws: TranslationPackageError.self) {
            try TranslationPackage.open(url: url, keyring: PublisherKeyring(),
                                        contentKey: SymmetricKey(data: try Self.hexKeyFile("content.key")))
        }
    }
}
