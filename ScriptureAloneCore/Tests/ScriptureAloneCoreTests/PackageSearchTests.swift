import Foundation
import CryptoKit
import Testing
@testable import ScriptureAloneCore

/// Searching a packaged translation: that it finds what the app's own index finds, that it costs what
/// it claims to cost, and that the index is as hard to tamper with as the text.
///
/// The index is sealed rather than left as hashed tokens in the clear, and the reason is specific to
/// this corpus: an attacker knows the file is a Bible, so token frequencies and positions could be
/// aligned against any public Bible until every hash was identified — which would reconstruct the
/// wording, the one thing the format exists to protect.
@Suite struct PackageSearchTests {

    static let bibles = BibleStoreTests.biblesDirectory

    /// A package of a real translation, so parity with the store's FTS5 index is tested against the
    /// same words in the same order. Built once and shared: 1,189 chapters plus a 5.8 MB index is a
    /// second of work, and every test here reads the same file.
    struct PackagedASV {
        let store: BibleStore
        let package: TranslationPackage
        let contentKey: SymmetricKey
        let signingKey: Curve25519.Signing.PrivateKey
        let url: URL
        let bytes: Data
    }

    static let asv: PackagedASV = {
        // `try!` in a test fixture: if the bundled ASV cannot be packaged there is no test to run, and
        // a crash here names the problem more clearly than every assertion failing separately.
        let store = try! BibleStore(url: bibles.appending(path: "ASV.sqlite"))
        let chapters = try! BookID.allCases.flatMap { book in
            try (1...book.chapterCount).compactMap { chapter -> TranslationPackageWriter.SourceChapter? in
                let ref = ChapterRef(book, chapter)
                return store.contains(ref) ? try store.packagingChapter(ref) : nil
            }
        }
        let contentKey = SymmetricKey(size: .bits256)
        let signingKey = Curve25519.Signing.PrivateKey()
        let bytes = try! TranslationPackageWriter.data(
            identity: PackagedTranslationIdentity(id: store.info.id, name: store.info.name,
                                                  abbreviation: store.info.abbreviation,
                                                  publisher: "Scripture Alone demonstration",
                                                  copyright: store.info.copyright,
                                                  license: store.info.license),
            policy: .publisherStandard, chapters: chapters,
            contentKey: contentKey, signingKey: signingKey)
        let url = URL.temporaryDirectory.appending(path: "search-\(UUID().uuidString).sabible")
        try! bytes.write(to: url)
        let package = try! TranslationPackage.open(
            url: url,
            keyring: try PublisherKeyring(rawPublicKeys: [signingKey.publicKey.rawRepresentation]),
            contentKey: contentKey)
        return PackagedASV(store: store, package: package, contentKey: contentKey,
                           signingKey: signingKey, url: url, bytes: bytes)
    }()

    /// A fresh reader over the same file, so a test that counts what a search decrypted starts at zero.
    static func freshReader() throws -> TranslationPackage {
        try TranslationPackage.open(
            url: asv.url,
            keyring: try PublisherKeyring(rawPublicKeys: [asv.signingKey.publicKey.rawRepresentation]),
            contentKey: asv.contentKey)
    }

    // MARK: - It finds what the store finds

    /// The claim that matters to a reader: a packaged translation searches like any other. Every query
    /// here is run against the app's FTS5 index and against the sealed index, and the two must agree
    /// verse for verse, in the same order.
    @Test(arguments: ["good shep", "faith hope love", "\"Jesus wept\"", "\"the good shepherd\"",
                      "shepherd", "begotten", "believeth", "lord god almighty", "nebuchadnezzar",
                      "jesus wep", "loving kindness", "\"in the beginning\""])
    func aPackageFindsExactlyWhatTheStoreFinds(_ query: String) throws {
        let expected = try Self.asv.store.search(query)
        let found = try Self.asv.package.search(query)
        #expect(found.map(\.ref) == expected.map(\.ref), "refs for “\(query)”")
        #expect(found.map(\.text) == expected.map(\.text), "text for “\(query)”")
    }

    @Test func emptyAndUselessQueriesBehaveTheSame() throws {
        #expect(try Self.asv.package.search("   ").isEmpty)
        #expect(try Self.asv.package.search("!!!").isEmpty)
        #expect(try Self.asv.package.search("zzzzq").isEmpty)
        #expect(try Self.asv.package.search("good shepherd", limit: 1).count == 1)
        #expect(try Self.asv.package.search("good", limit: 0).isEmpty)
    }

    /// A phrase is settled by comparing word positions, not by scanning text: "Jesus wept" matches and
    /// "wept Jesus" does not, though both words are in the verse.
    @Test func aPhraseIsAnAdjacencyCheckOnPositions() throws {
        #expect(try Self.asv.package.search("\"Jesus wept\"").map(\.ref) == [VerseRef(.john, 11, 35)])
        #expect(try Self.asv.package.search("\"wept Jesus\"").isEmpty)
        #expect(try Self.asv.package.search("jesus wept").count > 1)  // the two words, anywhere
    }

    /// A prefix narrows while typing, the way the store's does.
    @Test func aPrefixNarrowsWhileTyping() throws {
        let store = Self.asv.store
        let package = Self.asv.package
        for query in ["good she", "good shep", "good shephe", "good shepherd"] {
            #expect(try package.search(query).map(\.ref) == (try store.search(query)).map(\.ref),
                    "while typing “\(query)”")
        }
    }

    // MARK: - What a search costs

    /// A search opens the buckets its query names and decrypts the chapters its hits are in — nothing
    /// else. The numbers are the point: 256 buckets exist and 1,189 chapters exist, and a search for a
    /// common word touches a handful of each.
    @Test func aSearchOpensOnlyTheBucketsAndChaptersItNeeds() throws {
        let package = try Self.freshReader()
        #expect(package.accessCounts == TranslationPackage.AccessCounts(chapters: 0, buckets: 0))

        let hits = try package.search("shepherd", limit: 20)
        #expect(!hits.isEmpty)
        let counts = package.accessCounts
        // One token, one bucket. Not 256.
        #expect(counts.buckets == 1, "opened \(counts.buckets) of 256 buckets")
        // The chapters holding those twenty hits, and no others.
        let chaptersWithHits = Set(hits.map(\.ref.chapterKey)).count
        #expect(counts.chapters == chaptersWithHits, "decrypted \(counts.chapters) chapters for \(chaptersWithHits) chapters of hits")
        #expect(counts.chapters < 25)
    }

    /// The commonest word in the book, which is the case where a naive implementation would fall over:
    /// "the" appears in most verses of the Bible, and searching for it still opens one bucket and
    /// decrypts only the chapters the first page of results is in.
    @Test func thePlainestPossibleSearchStillDecryptsAlmostNothing() throws {
        let package = try Self.freshReader()
        let hits = try package.search("the", limit: 300)
        #expect(hits.count == 300)
        let counts = package.accessCounts
        #expect(counts.buckets == 1)
        #expect(counts.chapters <= 30, "decrypted \(counts.chapters) chapters of 1,189")
        // And the whole index was never opened: 5.8 MB of buckets, one of them read.
        #expect(counts.buckets * 2 < PackageSearchIndex.defaultBucketCount)
    }

    /// A phrase touches one bucket per distinct word, and then only the chapters of its hits.
    @Test func aPhraseSearchCostsOneBucketPerWord() throws {
        let package = try Self.freshReader()
        let hits = try package.search("\"Jesus wept\"")
        #expect(hits.map(\.ref) == [VerseRef(.john, 11, 35)])
        let counts = package.accessCounts
        #expect(counts.buckets <= 2)
        #expect(counts.chapters == 1, "one hit, one chapter, \(counts.chapters) decrypted")
    }

    /// A search that matches nothing decrypts no text at all — the postings settle it, and no chapter
    /// is touched.
    @Test func aSearchWithNoMatchesDecryptsNoChapters() throws {
        let package = try Self.freshReader()
        #expect(try package.search("nebuchadnezzar zzzzq").isEmpty)
        #expect(package.accessCounts.chapters == 0)
        #expect(package.accessCounts.buckets <= 2)
    }

    // MARK: - The index is sealed like the text

    /// One package's bucket, dropped into another built from the same text with the same content key.
    /// Same length, same offset, and it does not open: a bucket is bound to its package exactly as a
    /// chapter is. The bucket chosen is the one a real query lands in, so the test breaks the thing it
    /// then asks for.
    @Test func anIndexBucketTransplantedFromAnotherPackageFailsItsSeal() throws {
        let key = SymmetricKey(size: .bits256)
        let signing = Curve25519.Signing.PrivateKey()
        let chapters = TranslationPackageTests.sourceChapters()
        let identity = TranslationPackageTests.identity
        func build(_ id: String) throws -> Data {
            try TranslationPackageWriter.data(identity: identity, policy: .publisherStandard,
                                              chapters: chapters, contentKey: key, signingKey: signing,
                                              packageID: id)
        }
        let first = try build(UUID().uuidString)
        let second = try build(UUID().uuidString)
        let firstHeader = try JSONDecoder().decode(TranslationPackageHeader.self,
                                                   from: first.subdata(in: TranslationPackageTests.regions(first).header))
        let parameters = try #require(firstHeader.index)

        // The bucket the query "god" lands in — computed the way the reader computes it. Note it is the
        // *prefix* tag, not the word tag: the last word of an unquoted query prefix-matches, so that is
        // the posting list a one-word search actually reads.
        let indexKey = PackageSearchIndex.indexKey(contentKey: key, translationID: identity.id)
        let wanted = PackageSearchIndex.bucket(for: PackageSearchIndex.tag(indexKey, .prefix, "god"),
                                               count: parameters.buckets)
        let bucket = try #require(parameters.entries.first { $0.bucket == wanted })

        let firstBody = TranslationPackageTests.regions(first).body
        let secondBody = TranslationPackageTests.regions(second).body
        var transplanted = first
        transplanted.replaceSubrange((firstBody.lowerBound + bucket.offset) ..< (firstBody.lowerBound + bucket.offset + bucket.length),
                                     with: second.subdata(in: (secondBody.lowerBound + bucket.offset) ..< (secondBody.lowerBound + bucket.offset + bucket.length)))
        #expect(transplanted.count == first.count)

        let directory = URL.temporaryDirectory.appending(path: "bucket-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appending(path: "transplanted.sabible")
        try transplanted.write(to: url)
        let keyring = try PublisherKeyring(rawPublicKeys: [signing.publicKey.rawRepresentation])
        let package = try TranslationPackage.open(url: url, keyring: keyring, contentKey: key)

        #expect(throws: TranslationPackageError.bucketTampered(wanted)) {
            try package.search("god")
        }
        // The text is untouched, and so is every other bucket.
        #expect(try package.chapter(ChapterRef(.john, 3)).verses.count == 2)
        let elsewhere = PackageSearchIndex.bucket(for: PackageSearchIndex.tag(indexKey, .prefix, "beginning"),
                                                  count: parameters.buckets)
        if elsewhere != wanted {
            #expect(try package.search("beginning").map(\.ref) == [VerseRef(.genesis, 1, 1)])
        }
    }

    /// The index's parameters are inside the signed header: change the bucket count, the prefix lengths
    /// or the tokeniser, and the package stops opening.
    @Test func editingTheIndexParametersFailsTheSignature() throws {
        let built = try TranslationPackageTests.build()
        let regions = TranslationPackageTests.regions(built.bytes)
        var header = try JSONDecoder().decode(TranslationPackageHeader.self, from: built.bytes.subdata(in: regions.header))
        #expect(header.index?.buckets == PackageSearchIndex.defaultBucketCount)
        header.index?.tokenizer = "sabible-tokens-v0"
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        let url = try TranslationPackageTests.write(
            TranslationPackageTests.assemble(header: try encoder.encode(header),
                                             signature: built.bytes.subdata(in: regions.signature),
                                             body: built.bytes.subdata(in: regions.body)),
            beside: built.url, named: "index-params.sabible")
        #expect(throws: TranslationPackageError.signatureInvalid) {
            try TranslationPackage.open(url: url, keyring: built.keyring, contentKey: built.contentKey)
        }
    }

    /// A package built without an index says so, rather than answering "no matches" — those are
    /// different sentences to put in front of a reader.
    @Test func aPackageWithoutAnIndexRefusesToSearch() throws {
        let contentKey = SymmetricKey(size: .bits256)
        let signingKey = Curve25519.Signing.PrivateKey()
        let bytes = try TranslationPackageWriter.data(identity: TranslationPackageTests.identity,
                                                      policy: .publisherStandard,
                                                      chapters: TranslationPackageTests.sourceChapters(),
                                                      contentKey: contentKey, signingKey: signingKey,
                                                      buildIndex: false)
        let directory = URL.temporaryDirectory.appending(path: "noindex-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appending(path: "plain.sabible")
        try bytes.write(to: url)
        let package = try TranslationPackage.open(
            url: url, keyring: try PublisherKeyring(rawPublicKeys: [signingKey.publicKey.rawRepresentation]),
            contentKey: contentKey)
        #expect(!package.isSearchable)
        #expect(throws: TranslationPackageError.self) { try package.search("god") }
        // …and it still reads, because searching is a capability, not a precondition.
        #expect(try package.chapter(ChapterRef(.john, 3)).verses.count == 2)
    }

    // MARK: - The seam

    /// A store and a package, searched through one protocol-typed variable. This is why search belongs
    /// on `ChapterTextSource`: the alternative is the one place in the app that searches having to ask
    /// what kind of translation it is holding.
    @Test func aStoreAndAPackageAreSearchedThroughOneInterface() throws {
        let sources: [any ChapterTextSource] = [Self.asv.store, Self.asv.package]
        for source in sources {
            #expect(source.isSearchable)
            let hits = try source.search("good shepherd", limit: 5)
            #expect(hits.first?.ref == VerseRef(.john, 10, 11))
            #expect(try source.search("\"Jesus wept\"").map(\.ref) == [VerseRef(.john, 11, 35)])
        }
    }

    // MARK: - The tokeniser, stated

    @Test func theTokeniserIsTheOneTheStoreUses() {
        #expect(PackageSearchIndex.tokens(in: "In the beginning God created") == ["in", "the", "beginning", "god", "created"])
        // An apostrophe separates, as it does in SQLite's unicode61.
        #expect(PackageSearchIndex.tokens(in: "the LORD’s anointed") == ["the", "lord", "s", "anointed"])
        // Diacritics fold, matching remove_diacritics 2.
        #expect(PackageSearchIndex.tokens(in: "Bethsaïda") == ["bethsaida"])
        #expect(PackageSearchIndex.tokens(in: "1 Corinthians 13:4—love") == ["1", "corinthians", "13", "4", "love"])
        #expect(PackageSearchIndex.tokens(in: "   ").isEmpty)
    }

    @Test func aQueryIsReadTheSameWayTheStoreReadsIt() throws {
        let words = try #require(PackagedSearchQuery.parse("faith hope lov", prefixMin: 3))
        #expect(words.groups.map(\.tokens) == [["faith"], ["hope"], ["lov"]])
        #expect(words.groups.map(\.prefix) == [false, false, true])

        let phrase = try #require(PackagedSearchQuery.parse("\"Jesus wept\"", prefixMin: 3))
        #expect(phrase.groups.count == 1)
        #expect(phrase.groups[0].tokens == ["jesus", "wept"])
        #expect(phrase.groups[0].prefix == false)

        // A word with an apostrophe becomes a phrase of its parts, which is what FTS5 does with it.
        let apostrophe = try #require(PackagedSearchQuery.parse("the LORD's", prefixMin: 3))
        #expect(apostrophe.groups.map(\.tokens) == [["the"], ["lord", "s"]])

        // Below the shortest indexed prefix, the last word is matched whole: results narrow one
        // keystroke later than a store's, never wrongly.
        let short = try #require(PackagedSearchQuery.parse("in th", prefixMin: 3))
        #expect(short.groups.last?.prefix == false)
        #expect(PackagedSearchQuery.parse("   ", prefixMin: 3) == nil)
    }
}
