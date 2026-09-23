import Foundation
import CryptoKit

// A licensed translation ships as a signed, encrypted package — one file, `.sabible`:
//
//     magic            "SABIBLE\0"                     8 bytes
//     version          u16, big-endian                 2 bytes
//     header length    u32, big-endian                 4 bytes
//     header           JSON, plaintext                 header length bytes
//     signature length u16, big-endian                 2 bytes
//     signature        Ed25519 over the header bytes   64 bytes
//     body             per-chapter AEAD-sealed blobs   to end of file
//
// The header is plaintext on purpose. A reader holding no key at all can still see what the package
// claims to be, who signed it, and under what terms — which is the point: secrecy of the terms was
// never the goal, integrity of them is. The text is what the key protects.
//
// Nothing here needs SQLite, ZIP or a file picker, so — unlike `Import/` — this file compiles on
// watchOS as it stands and needs no `#if !os(watchOS)` guard.

public enum TranslationPackageFormat {
    /// `SABIBLE` and a NUL, so a package is never mistaken for text.
    public static let magic = Data("SABIBLE\0".utf8)
    public static let version: UInt16 = 1
    public static let cipher = "AES-256-GCM"
    public static let signatureAlgorithm = "Ed25519"
    /// Names the shape of the per-chapter associated data. A future change to what a chapter is
    /// bound to gets a new name here, and old readers refuse the new packages rather than guessing.
    public static let associatedDataVersion = "sabible-chapter-v1"
    /// AES-GCM in CryptoKit's combined form: 12-byte nonce ‖ ciphertext ‖ 16-byte tag. The writer
    /// needs this to lay out the body *before* it seals anything — see `TranslationPackageWriter`.
    public static let sealedChapterOverhead = 12 + 16
    public static let signatureLength = 64
    /// A whole-Bible index is ~100 KB of JSON. Anything approaching this is not a header.
    public static let maximumHeaderBytes = 8 * 1024 * 1024
    /// Psalm 119 is the longest chapter in scripture at about 40 KB.
    public static let maximumChapterBytes = 4 * 1024 * 1024
    /// Most chapters one `verses(in:)` call will decrypt. A selection spans one or two chapters;
    /// this is the structural ceiling that keeps the convenience API from being a way to ask for a
    /// whole Bible in one breath.
    public static let chapterSpanLimit = 25
}

public enum TranslationPackageError: Error, Equatable, LocalizedError {
    case notAPackage
    case unsupportedFormat(Int)
    case damagedHeader(String)
    case unreadable(String)
    /// The header names a signing key this build does not pin.
    case unknownPublisherKey(String)
    /// The header does not match the signature: a byte of it changed, or the policy was edited.
    case signatureInvalid
    case expired(Date)
    case wrongContentKey
    case chapterMissing(ChapterRef)
    /// The chapter's own authentication failed: moved from another package, replayed under a
    /// different header, or edited.
    case chapterTampered(ChapterRef)
    case truncated
    case rangeTooLarge(chapters: Int)
    /// The package carries no search index, or one this build cannot read.
    case notSearchable(String)
    case damagedIndex(String)
    /// A bucket's own authentication failed: moved from another package, replayed under a different
    /// header, or edited.
    case bucketTampered(Int)

    public var errorDescription: String? {
        switch self {
        case .notAPackage: String(localized: "That isn’t a translation package.", bundle: .module)
        case .unsupportedFormat(let version): String(localized: "This package is version \(version); this app reads version \(Int(TranslationPackageFormat.version)).", bundle: .module, comment: "Error. Both numbers are file-format version numbers.")
        case .damagedHeader(let detail): String(localized: "This package’s details are damaged: \(detail)", bundle: .module, comment: "Error. %@ is a technical detail.")
        case .unreadable(let detail): String(localized: "Couldn’t read the package: \(detail)", bundle: .module, comment: "Error. %@ is a technical detail.")
        case .unknownPublisherKey(let id): String(localized: "This package is signed by a key this app doesn’t know (\(id)).", bundle: .module, comment: "Error. %@ is a key identifier.")
        case .signatureInvalid: String(localized: "This package’s details or terms have been altered, so it can’t be opened.", bundle: .module)
        case .expired(let date): String(localized: "This translation’s licence ended \(date.formatted(date: .abbreviated, time: .omitted)).", bundle: .module, comment: "Error. %@ is a date.")
        case .wrongContentKey: String(localized: "This translation’s key isn’t the one that opens this package.", bundle: .module)
        case .chapterMissing(let chapter): String(localized: "\(chapter.display) isn’t in this translation.", bundle: .module, comment: "Error. %@ is a chapter reference, e.g. “John 3”.")
        case .chapterTampered(let chapter): String(localized: "\(chapter.display) failed its integrity check, so it can’t be shown.", bundle: .module, comment: "Error. %@ is a chapter reference, e.g. “John 3”.")
        case .truncated: String(localized: "This package is incomplete.", bundle: .module)
        case .rangeTooLarge(let chapters): String(localized: "That’s \(chapters) chapters at once — more than a quotation.", bundle: .module, comment: "Error. %lld is a number of chapters.")
        case .notSearchable(let why): String(localized: "This translation can’t be searched: \(why)", bundle: .module, comment: "Error. %@ is a technical detail.")
        case .damagedIndex(let detail): String(localized: "This translation’s search index is damaged: \(detail)", bundle: .module, comment: "Error. %@ is a technical detail.")
        case .bucketTampered(let bucket): String(localized: "Part of the search index (\(bucket)) failed its integrity check.", bundle: .module, comment: "Error. %lld is an index section number.")
        }
    }
}

// MARK: - Header

/// Who the translation is and who may be told. Plaintext, and covered by the signature.
public struct PackagedTranslationIdentity: Hashable, Sendable, Codable {
    public var id: String
    public var name: String
    public var abbreviation: String
    public var publisher: String
    public var copyright: String
    public var license: String

    public init(id: String, name: String, abbreviation: String, publisher: String,
                copyright: String, license: String) {
        self.id = id
        self.name = name
        self.abbreviation = abbreviation
        self.publisher = publisher
        self.copyright = copyright
        self.license = license
    }
}

/// Which primitives sealed and signed this package, and which keys. Named rather than assumed, so a
/// reader refuses a package built for primitives it does not implement instead of misreading it.
public struct PackageCryptoParameters: Hashable, Sendable, Codable {
    public var cipher: String
    public var signature: String
    public var aad: String
    /// Identifies the content key without revealing it: the first 16 bytes of
    /// SHA-256 over a domain string and the key. Lets the app say "wrong key" instead of "damaged".
    public var keyID: String
    /// Identifies the Ed25519 public key the app must pin to accept this package.
    public var publisherKeyID: String

    public init(cipher: String = TranslationPackageFormat.cipher,
                signature: String = TranslationPackageFormat.signatureAlgorithm,
                aad: String = TranslationPackageFormat.associatedDataVersion,
                keyID: String, publisherKeyID: String) {
        self.cipher = cipher
        self.signature = signature
        self.aad = aad
        self.keyID = keyID
        self.publisherKeyID = publisherKeyID
    }
}

/// Where one chapter's sealed blob sits in the body, and how many verses it holds. Signed, so the
/// body cannot be rearranged or a blob swapped for one of a different size.
public struct PackagedChapterEntry: Hashable, Sendable, Codable {
    public var book: Int
    public var chapter: Int
    public var verses: Int
    /// Bytes from the start of the body.
    public var offset: Int
    public var length: Int

    public init(book: Int, chapter: Int, verses: Int, offset: Int, length: Int) {
        self.book = book
        self.chapter = chapter
        self.verses = verses
        self.offset = offset
        self.length = length
    }

    public var ref: ChapterRef? {
        BookID(rawValue: book).map { ChapterRef($0, chapter) }
    }
}

public struct TranslationPackageHeader: Hashable, Sendable, Codable {
    public var format: Int
    /// Distinguishes this build of the package from every other. Bound into each chapter, so a
    /// chapter cannot be moved between packages.
    public var packageID: String
    public var createdAt: String
    public var translation: PackagedTranslationIdentity
    public var policy: PackagePolicy
    public var crypto: PackageCryptoParameters
    public var chapters: [PackagedChapterEntry]
    /// The search index's parameters, when the package carries one. Inside the signed region, so the
    /// bucket count, the prefix lengths and the tokeniser cannot be altered under the app. The policy
    /// gains nothing from it: searching is reading, and reading is what a package is for.
    public var index: PackageIndexParameters?

    public init(format: Int = Int(TranslationPackageFormat.version), packageID: String, createdAt: String,
                translation: PackagedTranslationIdentity, policy: PackagePolicy,
                crypto: PackageCryptoParameters, chapters: [PackagedChapterEntry],
                index: PackageIndexParameters? = nil) {
        self.format = format
        self.packageID = packageID
        self.createdAt = createdAt
        self.translation = translation
        self.policy = policy
        self.crypto = crypto
        self.chapters = chapters
        self.index = index
    }
}

/// One chapter, decrypted: exactly what the reader draws and what a selection copies. There is no
/// type here that holds two.
public struct PackagedChapter: Sendable {
    public let ref: ChapterRef
    public let layout: ChapterLayout
    public let verses: [VerseText]
}

// MARK: - Keys

/// The Ed25519 public keys this build accepts, by identifier. A fork gets the code and this list;
/// neither contains a content key, so neither decrypts anything.
public struct PublisherKeyring: Sendable, Hashable {
    /// Raw 32-byte Ed25519 public keys, by identifier. Stored raw rather than as CryptoKit values so
    /// the keyring is trivially `Sendable` and can be pinned as constants in the app.
    private var keys: [String: Data]

    public init() { keys = [:] }

    /// - Parameter rawPublicKeys: 32-byte Ed25519 public keys. Identifiers are derived, never taken
    ///   on trust, so a keyring cannot claim a key is something it is not.
    public init(rawPublicKeys: [Data]) throws {
        var keys: [String: Data] = [:]
        for raw in rawPublicKeys {
            guard raw.count == 32 else { throw TranslationPackageError.damagedHeader("a pinned key is not 32 bytes") }
            keys[TranslationPackageKeys.publisherKeyID(raw)] = raw
        }
        self.keys = keys
    }

    public var identifiers: Set<String> { Set(keys.keys) }

    public func rawPublicKey(for id: String) -> Data? { keys[id] }

    public mutating func pin(rawPublicKey raw: Data) throws {
        guard raw.count == 32 else { throw TranslationPackageError.damagedHeader("a pinned key is not 32 bytes") }
        keys[TranslationPackageKeys.publisherKeyID(raw)] = raw
    }
}

public enum TranslationPackageKeys {
    /// Names the content key without revealing anything useful about it: a 32-byte random key has no
    /// feasible preimage, and only the first 16 bytes of the digest are published.
    public static func contentKeyID(_ key: SymmetricKey) -> String {
        let raw = key.withUnsafeBytes { Data($0) }
        return identifier(domain: "SABIBLE content key", raw: raw)
    }

    public static func publisherKeyID(_ rawPublicKey: Data) -> String {
        identifier(domain: "SABIBLE publisher key", raw: rawPublicKey)
    }

    static func identifier(domain: String, raw: Data) -> String {
        var input = Data(domain.utf8)
        input.append(0)
        input.append(raw)
        return hex(Data(SHA256.hash(data: input).prefix(16)))
    }

    static func hex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }
}

// MARK: - Reader

/// Reads a signed, encrypted translation package.
///
/// **Per chapter, never whole-file.** The only code in this type that opens a sealed box is
/// `plaintext(forChapterAt:)`, it takes one chapter's index entry, and the only caller is
/// `chapter(_:)`. There is no API that returns more than one chapter's plaintext, no property that
/// caches decrypted text, and no path that reads the body as one piece: the file is held open and
/// each chapter is read from its own byte range on demand. That is what makes "a whole Bible in the
/// clear never exists" a property of the shape of this class rather than a habit of its callers.
///
/// Opening a package verifies the signature over the header, checks the expiry, and checks that the
/// content key is the one named in the header. None of that touches the text.
public final class TranslationPackage: @unchecked Sendable {
    public let url: URL
    public let header: TranslationPackageHeader
    /// SHA-256 of the exact header bytes in the file. Bound into every chapter's associated data, so
    /// a chapter sealed under one header cannot be replayed under another.
    public let headerDigest: Data
    public let info: TranslationInfo

    private let handle: FileHandle
    private let bodyOffset: UInt64
    private let fileSize: UInt64
    private let contentKey: SymmetricKey
    private let index: [ChapterRef: PackagedChapterEntry]
    private let buckets: [Int: PackagedBucketEntry]
    private let lock = NSLock()
    /// How much of the file this reader has actually decrypted, so a test — or the app — can state
    /// the cost of a search rather than assume it. Guarded by the same lock as the reads it counts.
    private var counts = AccessCounts(chapters: 0, buckets: 0)

    public struct AccessCounts: Hashable, Sendable {
        public var chapters: Int
        public var buckets: Int
    }

    public var accessCounts: AccessCounts { lock.withLock { counts } }

    /// - Parameters:
    ///   - keyring: the Ed25519 public keys this build pins. A package signed by anything else is
    ///     refused before its terms are read.
    ///   - contentKey: the 32-byte AES key. In the app this comes from the keychain; it is never in
    ///     the repository and never in the package.
    ///   - now: for the expiry check. Explicit so the test suite can state the expiry rule.
    public static func open(url: URL, keyring: PublisherKeyring, contentKey: SymmetricKey,
                           now: Date = Date()) throws -> TranslationPackage {
        try TranslationPackage(url: url, keyring: keyring, contentKey: contentKey, now: now, checkKeyID: true)
    }

    /// `checkKeyID: false` skips the courtesy check that the key named in the header is the key we
    /// hold, so the test suite can prove that AES-GCM — not that check — is what actually refuses a
    /// wrong key. Nothing in the app calls it that way.
    init(url: URL, keyring: PublisherKeyring, contentKey: SymmetricKey, now: Date, checkKeyID: Bool) throws {
        self.url = url
        self.contentKey = contentKey
        let file: FileHandle
        do {
            file = try FileHandle(forReadingFrom: url)
        } catch {
            throw TranslationPackageError.unreadable(error.localizedDescription)
        }
        let size: UInt64
        do {
            size = try file.seekToEnd()
        } catch {
            try? file.close()
            throw TranslationPackageError.unreadable(error.localizedDescription)
        }
        handle = file
        fileSize = size

        // Local, so the closure captures the file rather than a half-initialised `self`.
        func read(_ count: Int, at offset: UInt64) throws -> Data {
            guard count >= 0, offset <= size, UInt64(count) <= size - offset else {
                throw TranslationPackageError.truncated
            }
            do {
                try file.seek(toOffset: offset)
                guard let data = try file.read(upToCount: count), data.count == count else {
                    throw TranslationPackageError.truncated
                }
                return data
            } catch let error as TranslationPackageError {
                throw error
            } catch {
                throw TranslationPackageError.unreadable(error.localizedDescription)
            }
        }

        do {
            let magicLength = TranslationPackageFormat.magic.count
            let preamble = try read(magicLength + 6, at: 0)
            guard preamble.prefix(magicLength) == TranslationPackageFormat.magic else {
                throw TranslationPackageError.notAPackage
            }
            let version = Int(Self.uint16(preamble, at: magicLength))
            guard version == Int(TranslationPackageFormat.version) else {
                throw TranslationPackageError.unsupportedFormat(version)
            }
            let headerLength = Int(Self.uint32(preamble, at: magicLength + 2))
            guard headerLength > 0, headerLength <= TranslationPackageFormat.maximumHeaderBytes else {
                throw TranslationPackageError.damagedHeader("header length \(headerLength)")
            }
            let headerStart = UInt64(magicLength + 6)
            let headerBytes = try read(headerLength, at: headerStart)
            let signatureLength = Int(Self.uint16(try read(2, at: headerStart + UInt64(headerLength)), at: 0))
            guard signatureLength == TranslationPackageFormat.signatureLength else {
                throw TranslationPackageError.damagedHeader("signature length \(signatureLength)")
            }
            let signature = try read(signatureLength, at: headerStart + UInt64(headerLength) + 2)
            bodyOffset = headerStart + UInt64(headerLength) + 2 + UInt64(signatureLength)

            headerDigest = Data(SHA256.hash(data: headerBytes))
            let decoded: TranslationPackageHeader
            do {
                decoded = try JSONDecoder().decode(TranslationPackageHeader.self, from: headerBytes)
            } catch {
                throw TranslationPackageError.damagedHeader(String(describing: error))
            }
            header = decoded

            // Order matters: who signed this, then is the signature good, then are the terms still in
            // force, then do we hold the key. Nothing about the text is touched by any of it.
            guard decoded.format == Int(TranslationPackageFormat.version) else {
                throw TranslationPackageError.unsupportedFormat(decoded.format)
            }
            guard decoded.crypto.cipher == TranslationPackageFormat.cipher,
                  decoded.crypto.signature == TranslationPackageFormat.signatureAlgorithm,
                  decoded.crypto.aad == TranslationPackageFormat.associatedDataVersion else {
                throw TranslationPackageError.damagedHeader("unsupported cipher, signature or binding")
            }
            guard let rawPublicKey = keyring.rawPublicKey(for: decoded.crypto.publisherKeyID),
                  // The keyring derives its own identifiers, so this also catches a keyring that
                  // filed a key under the wrong name.
                  TranslationPackageKeys.publisherKeyID(rawPublicKey) == decoded.crypto.publisherKeyID else {
                throw TranslationPackageError.unknownPublisherKey(decoded.crypto.publisherKeyID)
            }
            let publicKey: Curve25519.Signing.PublicKey
            do {
                publicKey = try Curve25519.Signing.PublicKey(rawRepresentation: rawPublicKey)
            } catch {
                throw TranslationPackageError.unknownPublisherKey(decoded.crypto.publisherKeyID)
            }
            guard publicKey.isValidSignature(signature, for: headerBytes) else {
                throw TranslationPackageError.signatureInvalid
            }

            let rights = try decoded.policy.rights()
            if let expires = rights.expires, now >= expires {
                throw TranslationPackageError.expired(expires)
            }
            if checkKeyID, TranslationPackageKeys.contentKeyID(contentKey) != decoded.crypto.keyID {
                throw TranslationPackageError.wrongContentKey
            }

            var index: [ChapterRef: PackagedChapterEntry] = [:]
            for entry in decoded.chapters {
                guard let ref = entry.ref else {
                    throw TranslationPackageError.damagedHeader("book \(entry.book) is not a book of the Bible")
                }
                guard entry.offset >= 0, entry.length > TranslationPackageFormat.sealedChapterOverhead,
                      entry.length <= TranslationPackageFormat.maximumChapterBytes,
                      bodyOffset + UInt64(entry.offset) + UInt64(entry.length) <= fileSize else {
                    throw TranslationPackageError.truncated
                }
                index[ref] = entry
            }
            guard !index.isEmpty else { throw TranslationPackageError.damagedHeader("no chapters") }
            self.index = index

            var buckets: [Int: PackagedBucketEntry] = [:]
            if let parameters = decoded.index {
                guard parameters.buckets > 0, parameters.buckets <= 65_536,
                      parameters.prefixMin >= 1, parameters.prefixMax >= parameters.prefixMin,
                      parameters.padding >= 0 else {
                    throw TranslationPackageError.damagedHeader("index parameters")
                }
                for entry in parameters.entries {
                    guard entry.bucket >= 0, entry.bucket < parameters.buckets,
                          entry.offset >= 0, entry.length > TranslationPackageFormat.sealedChapterOverhead,
                          entry.length <= PackageSearchIndex.maximumBucketBytes,
                          bodyOffset + UInt64(entry.offset) + UInt64(entry.length) <= fileSize else {
                        throw TranslationPackageError.truncated
                    }
                    buckets[entry.bucket] = entry
                }
            }
            self.buckets = buckets

            info = TranslationInfo(id: decoded.translation.id,
                                   name: decoded.translation.name,
                                   abbreviation: decoded.translation.abbreviation,
                                   copyright: decoded.translation.copyright,
                                   license: decoded.translation.license,
                                   grantedRights: rights)
        } catch {
            try? handle.close()
            throw error
        }
    }

    deinit { try? handle.close() }

    public var publisher: String { header.translation.publisher }
    public var policy: PackagePolicy { header.policy }
    /// The publisher's terms, as the rest of the app sees them. Identical in type and meaning to the
    /// rights a public-domain store has, which is the whole point of the mapping.
    public var rights: TranslationRights { info.rights }

    public var chapters: [ChapterRef] { index.keys.sorted() }
    public func contains(_ chapter: ChapterRef) -> Bool { index[chapter] != nil }
    public func verseCount(_ chapter: ChapterRef) -> Int { index[chapter]?.verses ?? 0 }

    /// One chapter, decrypted in memory. The only way to get plaintext out of a package.
    public func chapter(_ ref: ChapterRef) throws -> PackagedChapter {
        guard let entry = index[ref] else { throw TranslationPackageError.chapterMissing(ref) }
        let plaintext = try plaintext(forChapterAt: entry, ref: ref)
        return try Self.decode(plaintext, ref: ref)
    }

    public func layout(for chapter: ChapterRef) throws -> ChapterLayout {
        try self.chapter(chapter).layout
    }

    /// The verses in a selection, for copying, sharing and speech — decrypting each chapter the
    /// range touches, one at a time, and refusing a range wide enough to be an extraction rather
    /// than a quotation.
    public func verses(in range: VerseRange) throws -> [VerseText] {
        let touched = index.keys.filter { range.overlaps($0) }.sorted()
        guard touched.count <= TranslationPackageFormat.chapterSpanLimit else {
            throw TranslationPackageError.rangeTooLarge(chapters: touched.count)
        }
        var result: [VerseText] = []
        for ref in touched {
            for verse in try chapter(ref).verses where range.contains(verse.ref) {
                result.append(verse)
            }
        }
        return result
    }

    // MARK: - Search

    /// Whether this package carries an index this build can read.
    public var isSearchable: Bool {
        guard let parameters = header.index else { return false }
        return parameters.tokenizer == PackageSearchIndex.tokenizer
            && parameters.aad == PackageSearchIndex.associatedDataVersion
            && !buckets.isEmpty
    }

    /// Full-text search, answering the same call the app already makes on a `BibleStore`: every word
    /// must appear, the last word matches as a prefix so results narrow while typing, and a query in
    /// double quotes matches as an exact phrase. Results are in canonical order.
    ///
    /// What a search costs, and why: the query's tokens are hashed with a key derived from the
    /// content key, which names the buckets holding their postings. Only those buckets are opened —
    /// one or two, tens of kilobytes. The postings are intersected, a phrase is settled by comparing
    /// word positions rather than by scanning text, and only then are chapters decrypted, one at a
    /// time, for the verses that actually matched, because a hit has to carry its text. A search
    /// never opens the whole index and never decrypts the whole Bible; `accessCounts` says exactly how
    /// much it did open, and the test suite asserts on it.
    public func search(_ query: String, limit: Int = 300) throws -> [BibleStore.SearchHit] {
        guard let parameters = header.index, !buckets.isEmpty else {
            throw TranslationPackageError.notSearchable("this package was built without a search index")
        }
        guard parameters.tokenizer == PackageSearchIndex.tokenizer,
              parameters.aad == PackageSearchIndex.associatedDataVersion else {
            throw TranslationPackageError.notSearchable("its index was built by \(parameters.tokenizer), which this app doesn’t implement")
        }
        guard limit > 0, let parsed = PackagedSearchQuery.parse(query, prefixMin: parameters.prefixMin) else { return [] }

        let key = PackageSearchIndex.indexKey(contentKey: contentKey, translationID: header.translation.id)

        // 1. Name the postings this query needs, and the buckets they live in.
        struct Lookup {
            var id: UInt64
            var isPrefix: Bool
        }
        var groups: [[Lookup]] = []
        var longPrefixes: [String] = []
        var wanted: [Int: Set<UInt64>] = [:]
        for group in parsed.groups {
            var lookups: [Lookup] = []
            for (offset, token) in group.tokens.enumerated() {
                let isFinal = offset == group.tokens.count - 1
                let tag: Data
                let isPrefix = group.prefix && isFinal
                if isPrefix {
                    let scalars = Array(token.unicodeScalars)
                    let cut = min(parameters.prefixMax, scalars.count)
                    if cut < scalars.count { longPrefixes.append(token) }
                    tag = PackageSearchIndex.tag(key, .prefix, String(String.UnicodeScalarView(scalars[0..<cut])))
                } else {
                    tag = PackageSearchIndex.tag(key, .word, token)
                }
                let id = PackageSearchIndex.tokenID(tag)
                wanted[PackageSearchIndex.bucket(for: tag, count: parameters.buckets), default: []].insert(id)
                lookups.append(Lookup(id: id, isPrefix: isPrefix))
            }
            groups.append(lookups)
        }

        // 2. Open only those buckets.
        var entries: [UInt64: PackageSearchIndex.Entry] = [:]
        for (bucket, ids) in wanted {
            guard let entry = buckets[bucket] else { continue }
            for (id, decoded) in try PackageSearchIndex.decodeBucket(plaintext(forBucketAt: entry), wanted: ids) {
                entries[id] = decoded
            }
        }

        // 3. Intersect: every group must be satisfied in the same verse.
        var candidates: Set<Int>?
        for lookups in groups {
            let verses = Self.verses(satisfying: lookups.map { ($0.id, $0.isPrefix) }, entries: entries)
            candidates = candidates.map { $0.intersection(verses) } ?? verses
            if candidates?.isEmpty == true { return [] }
        }
        guard let candidates, !candidates.isEmpty else { return [] }

        // 4. Only now decrypt chapters — those that hold the matches, in canonical order, stopping at
        //    the limit. A prefix longer than the longest indexed one is a superset, so those hits are
        //    verified against the verse's own tokens; every other query needs no verification at all.
        var hits: [BibleStore.SearchHit] = []
        var chaptersOpened = 0
        var text: [Int: String] = [:]
        var loaded: ChapterRef?
        for key in candidates.sorted() {
            guard let ref = VerseRef(key: key) else { continue }
            if loaded != ref.chapterKey {
                guard chaptersOpened < Self.searchChapterBudget else { break }
                guard index[ref.chapterKey] != nil else { continue }
                let chapter = try self.chapter(ref.chapterKey)
                chaptersOpened += 1
                loaded = ref.chapterKey
                text = Dictionary(uniqueKeysWithValues: chapter.verses.map { ($0.ref.key, $0.text) })
            }
            guard let verse = text[key] else { continue }
            if !longPrefixes.isEmpty {
                let tokens = PackageSearchIndex.tokens(in: verse)
                guard longPrefixes.allSatisfy({ prefix in tokens.contains { $0.hasPrefix(prefix) } }) else { continue }
            }
            hits.append(BibleStore.SearchHit(ref: ref, text: verse))
            if hits.count == limit { break }
        }
        return hits
    }

    /// Chapters one search may decrypt. Only a prefix longer than the longest indexed one can produce
    /// candidates that turn out not to match, and with prefixes indexed to ten characters that is rare
    /// — this is the ceiling that keeps even a pathological query from walking the whole book.
    static let searchChapterBudget = 256

    /// One group of the query: adjacent word positions for a phrase, and a verse-level check for a
    /// trailing prefix, which has no positions by design.
    static func verses(satisfying lookups: [(id: UInt64, isPrefix: Bool)],
                       entries: [UInt64: PackageSearchIndex.Entry]) -> Set<Int> {
        var positional: [PackageSearchIndex.Entry] = []
        var prefixVerses: Set<Int>?
        for lookup in lookups {
            guard let entry = entries[lookup.id] else { return [] }
            if lookup.isPrefix {
                prefixVerses = Set(entry.postings.map(\.verse))
            } else {
                positional.append(entry)
            }
        }
        guard let first = positional.first else { return prefixVerses ?? [] }

        var running: [Int: Set<Int>] = [:]
        for posting in first.postings { running[posting.verse] = Set(posting.positions) }
        for entry in positional.dropFirst() {
            var next: [Int: Set<Int>] = [:]
            for posting in entry.postings {
                guard let previous = running[posting.verse] else { continue }
                let adjacent = Set(posting.positions).intersection(Set(previous.map { $0 + 1 }))
                if !adjacent.isEmpty { next[posting.verse] = adjacent }
            }
            running = next
            if running.isEmpty { return [] }
        }
        let matched = Set(running.keys)
        return prefixVerses.map { matched.intersection($0) } ?? matched
    }

    // MARK: - The two decrypting paths

    /// Reads a byte range of the body. Shared by the two decrypting functions below so that neither
    /// can quietly grow its own file access.
    private func sealedBytes(offset: Int, length: Int) throws -> Data {
        try lock.withLock {
            do {
                try handle.seek(toOffset: bodyOffset + UInt64(offset))
                guard let data = try handle.read(upToCount: length), data.count == length else {
                    throw TranslationPackageError.truncated
                }
                return data
            } catch let error as TranslationPackageError {
                throw error
            } catch {
                throw TranslationPackageError.unreadable(error.localizedDescription)
            }
        }
    }

    /// Reads one index bucket's sealed blob and opens it — the sibling of the chapter path, under the
    /// same discipline: one bucket, from its own byte range, bound to this package, this translation,
    /// this bucket number and this exact header. The domain string differs from a chapter's, so a
    /// chapter blob can never be opened as a bucket or the reverse.
    private func plaintext(forBucketAt entry: PackagedBucketEntry) throws -> Data {
        let sealed = try sealedBytes(offset: entry.offset, length: entry.length)
        let associated = Self.indexAssociatedData(packageID: header.packageID,
                                                  translationID: header.translation.id,
                                                  bucket: entry.bucket,
                                                  headerDigest: headerDigest)
        do {
            let box = try AES.GCM.SealedBox(combined: sealed)
            let plaintext = try AES.GCM.open(box, using: contentKey, authenticating: associated)
            lock.withLock { counts.buckets += 1 }
            return plaintext
        } catch {
            throw TranslationPackageError.bucketTampered(entry.bucket)
        }
    }

    /// `sabible-index-v1 ‖ package id ‖ translation id ‖ bucket ‖ header digest`, newline separated,
    /// UTF-8. Written the same way by `Tools/package_translation.py`.
    public static func indexAssociatedData(packageID: String, translationID: String, bucket: Int,
                                           headerDigest: Data) -> Data {
        let fields = [PackageSearchIndex.associatedDataVersion,
                      packageID,
                      translationID,
                      String(bucket),
                      TranslationPackageKeys.hex(headerDigest)]
        return Data(fields.joined(separator: "\n").utf8)
    }

    /// Reads one chapter's sealed blob from its own byte range and opens it. The associated data
    /// binds the blob to this package, this translation, this chapter and this exact header, so a
    /// blob moved between packages, replayed under an edited policy, or renamed to another chapter
    /// fails here rather than being shown as the wrong text.
    private func plaintext(forChapterAt entry: PackagedChapterEntry, ref: ChapterRef) throws -> Data {
        let sealed = try sealedBytes(offset: entry.offset, length: entry.length)
        let associated = Self.associatedData(packageID: header.packageID,
                                             translationID: header.translation.id,
                                             chapter: ref,
                                             headerDigest: headerDigest)
        do {
            let box = try AES.GCM.SealedBox(combined: sealed)
            let plaintext = try AES.GCM.open(box, using: contentKey, authenticating: associated)
            lock.withLock { counts.chapters += 1 }
            return plaintext
        } catch {
            // GCM cannot distinguish a wrong key from tampering. Either way this chapter is not
            // shown, and the caller is told which chapter refused.
            throw TranslationPackageError.chapterTampered(ref)
        }
    }

    /// `sabible-chapter-v1 ‖ package id ‖ translation id ‖ chapter key ‖ header digest`, newline
    /// separated, UTF-8. Written the same way by `Tools/package_translation.py`.
    public static func associatedData(packageID: String, translationID: String, chapter: ChapterRef,
                                      headerDigest: Data) -> Data {
        let fields = [TranslationPackageFormat.associatedDataVersion,
                      packageID,
                      translationID,
                      String(chapterKey(chapter)),
                      TranslationPackageKeys.hex(headerDigest)]
        return Data(fields.joined(separator: "\n").utf8)
    }

    /// `book × 1000 + chapter`, the chapter half of the verse key the whole app already uses.
    public static func chapterKey(_ chapter: ChapterRef) -> Int {
        chapter.book.rawValue * 1_000 + chapter.chapter
    }

    // MARK: - Chapter plaintext

    /// A chapter blob is JSON: the layout exactly as the store keeps it, and the verse rows with
    /// their words-of-Christ ranges. Decoded through the same `ChapterLayout` the reader draws from
    /// a SQLite store, so packaged text renders identically.
    struct ChapterPayload: Codable {
        struct Verse: Codable {
            var i: Int
            var t: String
            var r: [[Int]]?
        }
        var layout: String
        var verses: [Verse]
    }

    static func decode(_ plaintext: Data, ref: ChapterRef) throws -> PackagedChapter {
        let payload: ChapterPayload
        do {
            payload = try JSONDecoder().decode(ChapterPayload.self, from: plaintext)
        } catch {
            throw TranslationPackageError.chapterTampered(ref)
        }
        let layout: ChapterLayout
        do {
            layout = try JSONDecoder().decode(ChapterLayout.self, from: Data(payload.layout.utf8))
        } catch {
            throw TranslationPackageError.chapterTampered(ref)
        }
        let verses: [VerseText] = payload.verses.compactMap { row in
            guard let verse = VerseRef(key: row.i) else { return nil }
            let red: [NSRange]
            if let pairs = row.r, !pairs.isEmpty {
                red = pairs.compactMap { pair in
                    guard pair.count == 2 else { return nil }
                    let start = row.t.utf16Offset(ofScalar: pair[0])
                    let end = row.t.utf16Offset(ofScalar: pair[0] + pair[1])
                    return NSRange(location: start, length: end - start)
                }
            } else {
                red = []
            }
            return VerseText(ref: verse, text: row.t, red: red)
        }
        return PackagedChapter(ref: ref, layout: layout, verses: verses)
    }

    // MARK: - Big-endian scalars

    static func uint16(_ data: Data, at offset: Int) -> UInt16 {
        let bytes = Array(data[data.startIndex + offset ..< data.startIndex + offset + 2])
        return UInt16(bytes[0]) << 8 | UInt16(bytes[1])
    }

    static func uint32(_ data: Data, at offset: Int) -> UInt32 {
        let bytes = Array(data[data.startIndex + offset ..< data.startIndex + offset + 4])
        return bytes.reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
    }
}
