import Foundation
import CryptoKit

// A packaged translation is searchable, and the index is sealed exactly like the text is.
//
// The obvious design — HMAC(word) → postings, left in the clear — is wrong for *this* corpus. The
// attacker knows the file is a Bible. Token frequencies and the positions those tokens sit at can be
// aligned against any public Bible until each hash is identified, and identifying the hashes
// reconstructs this translation's wording, which is the whole asset. Searchable-encryption schemes
// assume the plaintext distribution is unknown; ours is the most published text in history. So the
// postings are encrypted too, in buckets, and a search opens only the buckets its query names.
//
// What is left in the clear is the shape of the thing: how many buckets there are and how big each
// one is. `docs/encrypted-translations.md` says what that reveals and what it does not.

/// The index's parameters. They live inside the signed header, so the bucket count, the prefix
/// lengths and the tokeniser cannot be altered under the app — a package whose index was built by a
/// tokeniser this build does not implement is refused rather than silently mis-searched.
public struct PackageIndexParameters: Hashable, Sendable, Codable {
    public var tokenizer: String
    public var aad: String
    public var buckets: Int
    public var prefixMin: Int
    public var prefixMax: Int
    /// Sealed buckets are padded to a multiple of this many bytes.
    public var padding: Int
    public var entries: [PackagedBucketEntry]

    public init(tokenizer: String = PackageSearchIndex.tokenizer,
                aad: String = PackageSearchIndex.associatedDataVersion,
                buckets: Int = PackageSearchIndex.defaultBucketCount,
                prefixMin: Int = PackageSearchIndex.defaultPrefixMin,
                prefixMax: Int = PackageSearchIndex.defaultPrefixMax,
                padding: Int = PackageSearchIndex.defaultPadding,
                entries: [PackagedBucketEntry]) {
        self.tokenizer = tokenizer
        self.aad = aad
        self.buckets = buckets
        self.prefixMin = prefixMin
        self.prefixMax = prefixMax
        self.padding = padding
        self.entries = entries
    }
}

public struct PackagedBucketEntry: Hashable, Sendable, Codable {
    public var bucket: Int
    /// Bytes from the start of the body, like a chapter's.
    public var offset: Int
    public var length: Int

    public init(bucket: Int, offset: Int, length: Int) {
        self.bucket = bucket
        self.offset = offset
        self.length = length
    }
}

public enum PackageSearchIndex {
    public static let tokenizer = "sabible-tokens-v1"
    public static let associatedDataVersion = "sabible-index-v1"
    /// 256 buckets puts a whole Bible's postings — about 5.8 MB for the ASV — into buckets of 8 KB to
    /// 120 KB, a median of 16 KB. Small enough that a search decrypts tens of kilobytes rather than
    /// megabytes; large enough that a bucket holds tens of tokens rather than one, which is what keeps
    /// a bucket's size from being a single word's frequency.
    public static let defaultBucketCount = 256
    public static let defaultPrefixMin = 3
    /// Prefixes run to ten characters, not the six first sketched. A prefix longer than the longest
    /// indexed one has to be verified against the text, which means decrypting chapters that turn out
    /// not to match — exactly what a search should avoid. Measured on the ASV, six to ten costs
    /// 189,134 more (prefix, verse) pairs out of 1.2 million, about 0.3 MB, and in exchange every
    /// realistic prefix is exact: chapters decrypted equals chapters with hits.
    public static let defaultPrefixMax = 10
    public static let defaultPadding = 4096
    /// A bucket over this size is not one of ours.
    public static let maximumBucketBytes = 8 * 1024 * 1024

    // MARK: - Tokeniser v1

    /// NFD, drop combining marks, lowercase. `Tools/package_translation.py` does exactly this, and a
    /// divergence between the two shows up as a failing cross-language test rather than as a search
    /// that quietly misses words.
    public static func fold(_ text: String) -> String {
        var folded = String.UnicodeScalarView()
        for scalar in text.decomposedStringWithCanonicalMapping.unicodeScalars
        where scalar.properties.generalCategory != .nonspacingMark {
            folded.append(scalar)
        }
        return String(folded).lowercased()
    }

    /// Runs of letters and digits, matching SQLite's `unicode61` — which treats an apostrophe as a
    /// separator, so "the LORD's" is three tokens in the store's index and three here.
    public static func tokens(in text: String) -> [String] {
        var tokens: [String] = []
        var current = String.UnicodeScalarView()
        for scalar in fold(text).unicodeScalars {
            if Character(scalar).isLetter || Character(scalar).isNumber {
                current.append(scalar)
            } else if !current.isEmpty {
                tokens.append(String(current))
                current = String.UnicodeScalarView()
            }
        }
        if !current.isEmpty { tokens.append(String(current)) }
        return tokens
    }

    // MARK: - Keys and tags

    /// A key of its own for the index, derived from the content key, so a token tag can never be
    /// confused with content-key material. The translation id is in the derivation too: a publisher
    /// who packages three translations under one content key would otherwise get the same token →
    /// bucket mapping in all three, and bucket sizes could be correlated across the files.
    public static func indexKey(contentKey: SymmetricKey, translationID: String) -> SymmetricKey {
        var info = Data("SABIBLE index v1".utf8)
        info.append(0)
        info.append(Data(translationID.utf8))
        return HKDF<SHA256>.deriveKey(inputKeyMaterial: contentKey, info: info, outputByteCount: 32)
    }

    enum TagKind: String {
        case word = "w"
        case prefix = "p"
    }

    static func tag(_ key: SymmetricKey, _ kind: TagKind, _ token: String) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: Data("\(kind.rawValue):\(token)".utf8), using: key))
    }

    static func bucket(for tag: Data, count: Int) -> Int {
        let value = tag.prefix(4).reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
        return Int(value % UInt32(count))
    }

    static func tokenID(_ tag: Data) -> UInt64 {
        tag.prefix(8).reduce(UInt64(0)) { $0 << 8 | UInt64($1) }
    }

    // MARK: - Bucket payload

    /// One token's postings inside a bucket. A word entry carries positions so a phrase is an
    /// adjacency check; a prefix entry is verse-level only, because the app's search prefix-matches
    /// just the last word of a query and positions there would be weight with no use.
    struct Posting: Sendable {
        var verse: Int
        var positions: [Int]
    }

    struct Entry: Sendable {
        var tokenID: UInt64
        var isPrefix: Bool
        var postings: [Posting]
    }

    /// `varint(payload length) ‖ varint(entry count) ‖ entries ‖ zero padding`, entries sorted by
    /// token id, verse keys and positions delta-encoded. Written byte for byte by the Python tool.
    static func encodeBucket(_ entries: [Entry], padding: Int) -> Data {
        var payload = Data()
        payload.appendVarint(entries.count)
        for entry in entries.sorted(by: { $0.tokenID < $1.tokenID }) {
            var id = entry.tokenID
            var idBytes = [UInt8](repeating: 0, count: 8)
            for index in stride(from: 7, through: 0, by: -1) {
                idBytes[index] = UInt8(truncatingIfNeeded: id)
                id >>= 8
            }
            payload.append(contentsOf: idBytes)
            payload.append(entry.isPrefix ? 1 : 0)
            payload.appendVarint(entry.postings.count)
            var previousVerse = 0
            for posting in entry.postings.sorted(by: { $0.verse < $1.verse }) {
                payload.appendVarint(posting.verse - previousVerse)
                previousVerse = posting.verse
                guard !entry.isPrefix else { continue }
                payload.appendVarint(posting.positions.count)
                var last = 0
                for position in posting.positions.sorted() {
                    payload.appendVarint(position - last)
                    last = position
                }
            }
        }
        var body = Data()
        body.appendVarint(payload.count)
        body.append(payload)
        let remainder = padding > 0 ? (padding - body.count % padding) % padding : 0
        if remainder > 0 { body.append(Data(repeating: 0, count: remainder)) }
        return body
    }

    /// Reads back only the entries asked for. A bucket holds about fifty tokens; a query wants one or
    /// two of them, and the rest are skipped rather than materialised.
    static func decodeBucket(_ data: Data, wanted: Set<UInt64>) throws -> [UInt64: Entry] {
        var reader = ByteReader(data)
        let payloadLength = try reader.varint()
        guard payloadLength <= reader.remaining else { throw TranslationPackageError.damagedIndex("bucket length") }
        let entryCount = try reader.varint()
        var found: [UInt64: Entry] = [:]
        for _ in 0..<entryCount {
            let id = try reader.uint64()
            let isPrefix = try reader.byte() == 1
            let postingCount = try reader.varint()
            let keep = wanted.contains(id)
            var postings: [Posting] = []
            if keep { postings.reserveCapacity(postingCount) }
            var verse = 0
            for _ in 0..<postingCount {
                verse += try reader.varint()
                if isPrefix {
                    if keep { postings.append(Posting(verse: verse, positions: [])) }
                    continue
                }
                let positionCount = try reader.varint()
                var position = 0
                var positions: [Int] = []
                if keep { positions.reserveCapacity(positionCount) }
                for _ in 0..<positionCount {
                    position += try reader.varint()
                    if keep { positions.append(position) }
                }
                if keep { postings.append(Posting(verse: verse, positions: positions)) }
            }
            if keep { found[id] = Entry(tokenID: id, isPrefix: isPrefix, postings: postings) }
            if found.count == wanted.count { break }
        }
        return found
    }

    // MARK: - Building

    /// Builds every bucket's plaintext from the chapters going into a package. The Swift counterpart
    /// of `build_index_buckets` in the packaging tool.
    static func buildBuckets(chapters: [TranslationPackageWriter.SourceChapter],
                             contentKey: SymmetricKey,
                             translationID: String,
                             parameters: (buckets: Int, prefixMin: Int, prefixMax: Int, padding: Int)) -> [Data] {
        let key = indexKey(contentKey: contentKey, translationID: translationID)
        var words: [UInt64: [Int: [Int]]] = [:]
        var wordBucket: [UInt64: Int] = [:]
        var prefixes: [UInt64: Set<Int>] = [:]
        var prefixBucket: [UInt64: Int] = [:]

        for chapter in chapters {
            for row in chapter.verseRows {
                for (position, token) in tokens(in: row.text).enumerated() {
                    let wordTag = tag(key, .word, token)
                    let id = tokenID(wordTag)
                    wordBucket[id] = bucket(for: wordTag, count: parameters.buckets)
                    words[id, default: [:]][row.key, default: []].append(position)
                    let scalars = Array(token.unicodeScalars)
                    guard scalars.count >= parameters.prefixMin else { continue }
                    for length in parameters.prefixMin...min(parameters.prefixMax, scalars.count) {
                        let prefix = String(String.UnicodeScalarView(scalars[0..<length]))
                        let prefixTag = tag(key, .prefix, prefix)
                        let prefixID = tokenID(prefixTag)
                        prefixBucket[prefixID] = bucket(for: prefixTag, count: parameters.buckets)
                        prefixes[prefixID, default: []].insert(row.key)
                    }
                }
            }
        }

        var grouped = [[Entry]](repeating: [], count: parameters.buckets)
        for (id, postings) in words {
            let entry = Entry(tokenID: id, isPrefix: false,
                              postings: postings.map { Posting(verse: $0.key, positions: $0.value) })
            grouped[wordBucket[id] ?? 0].append(entry)
        }
        for (id, verses) in prefixes {
            let entry = Entry(tokenID: id, isPrefix: true,
                              postings: verses.map { Posting(verse: $0, positions: []) })
            grouped[prefixBucket[id] ?? 0].append(entry)
        }
        return grouped.map { encodeBucket($0, padding: parameters.padding) }
    }
}

// MARK: - Queries

/// A query, in the shape the index answers: a list of groups that must all appear in one verse, each
/// group a phrase of one or more adjacent tokens, with the last token of the last group optionally
/// matching as a prefix.
///
/// This mirrors what `BibleStore.ftsQuery` asks SQLite for, so search feels the same whichever kind
/// of translation is open: every word must appear, the last word matches as a prefix so results
/// narrow while typing, and a quoted query is an exact phrase. A word containing an apostrophe
/// becomes a phrase of its parts, which is what FTS5 does with it too.
public struct PackagedSearchQuery: Sendable, Equatable {
    public struct Group: Sendable, Equatable {
        public var tokens: [String]
        /// The final token matches as a prefix rather than whole.
        public var prefix: Bool
    }

    public var groups: [Group]

    /// - Parameter prefixMin: shorter than this, a trailing word is matched whole — the index holds no
    ///   postings for one- and two-character prefixes, and inventing them would cost more than the
    ///   keystroke is worth. The effect is that results narrow one character later than they do for a
    ///   store, never that a wrong verse appears.
    public static func parse(_ query: String, prefixMin: Int) -> PackagedSearchQuery? {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.count > 2, trimmed.hasPrefix("\""), trimmed.hasSuffix("\"") {
            let phrase = trimmed.dropFirst().dropLast().replacingOccurrences(of: "\"", with: "")
            let tokens = PackageSearchIndex.tokens(in: phrase)
            guard !tokens.isEmpty else { return nil }
            return PackagedSearchQuery(groups: [Group(tokens: tokens, prefix: false)])
        }
        // Split the way the store's query builder does: words keep their apostrophes here and are
        // then tokenised, so "lord's" becomes the phrase lord + s.
        let words = trimmed
            .components(separatedBy: CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "'’")).inverted)
            .filter { !$0.isEmpty }
        var groups: [Group] = []
        for word in words {
            let tokens = PackageSearchIndex.tokens(in: word)
            guard !tokens.isEmpty else { continue }
            groups.append(Group(tokens: tokens, prefix: false))
        }
        guard !groups.isEmpty else { return nil }
        if let last = groups.last?.tokens.last, last.count >= prefixMin {
            groups[groups.count - 1].prefix = true
        }
        return PackagedSearchQuery(groups: groups)
    }
}

// MARK: - Bytes

extension Data {
    mutating func appendVarint(_ value: Int) {
        var remaining = UInt64(Swift.max(0, value))
        repeat {
            var byte = UInt8(remaining & 0x7F)
            remaining >>= 7
            if remaining != 0 { byte |= 0x80 }
            append(byte)
        } while remaining != 0
    }
}

/// A cursor over a bucket's plaintext. Every read is bounds-checked: a bucket is decrypted before it
/// is parsed, so a malformed one means our own writer erred, but it is still not allowed to walk off
/// the end of the buffer.
struct ByteReader {
    private let data: Data
    private var index: Int

    init(_ data: Data) {
        self.data = data
        index = data.startIndex
    }

    var remaining: Int { data.endIndex - index }

    mutating func byte() throws -> UInt8 {
        guard index < data.endIndex else { throw TranslationPackageError.damagedIndex("truncated bucket") }
        defer { index += 1 }
        return data[index]
    }

    mutating func varint() throws -> Int {
        var result = 0
        var shift = 0
        while true {
            let byte = try self.byte()
            result |= Int(byte & 0x7F) << shift
            if byte & 0x80 == 0 { return result }
            shift += 7
            guard shift <= 56 else { throw TranslationPackageError.damagedIndex("varint") }
        }
    }

    mutating func uint64() throws -> UInt64 {
        var value: UInt64 = 0
        for _ in 0..<8 { value = value << 8 | UInt64(try byte()) }
        return value
    }
}
