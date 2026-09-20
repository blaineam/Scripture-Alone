import Foundation
import SQLite3

public enum InterlinearStoreError: Error, LocalizedError {
    case open(String)
    case query(String)
    /// The word records of this verse do not fit the text they were handed — the caller passed a
    /// different translation's verse. The interlinear aligns to the Berean Standard Bible only.
    case textMismatch(VerseRef)
    /// A `BibleStore` that is not the BSB was handed to `words(for:from:)`.
    case wrongTranslation(String)

    public var errorDescription: String? {
        switch self {
        case .open(let message): "Couldn’t open the original-language data: \(message)"
        case .query(let message): "Couldn’t read the original-language data: \(message)"
        case .textMismatch(let verse):
            "The original-language data for \(verse.display) doesn’t match this text. "
            + "Interlinear data is aligned to the Berean Standard Bible."
        case .wrongTranslation(let abbreviation):
            "Interlinear data is aligned to the Berean Standard Bible, not \(abbreviation)."
        }
    }
}

/// Read-only access to the bundled interlinear database (built by `Tools/build_interlinear.py`):
/// word-level Hebrew, Aramaic and Greek for the whole Bible, aligned to the bundled Berean Standard
/// Bible, plus STEPBible's Strong's lexicon.
///
/// Opened `immutable=1`: the file genuinely never changes once bundled, so SQLite takes no locks and
/// caches pages indefinitely. The `NSLock` serializes the one connection, exactly as `StudyStore`
/// does; nothing here touches the main actor.
///
/// **The alignment is to the BSB and only the BSB.** A word's English is not stored — it is a
/// UTF-16 slice of the BSB's own verse text, which is what makes the highlight exact by
/// construction instead of re-deriving the tables' spacing rules at runtime. Handing this store the
/// ASV's or KJV's text for the same verse produces nonsense, so it refuses: see
/// `alignsTo(_:)`, which is the check the UI uses to hide the affordance.
///
/// This compiles on watchOS as it stands (no 64-bit arithmetic, nothing beyond Foundation and
/// SQLite3) and so needs no `#if !os(watchOS)` guard. The watch does not *bundle* the 11 MB
/// database — `ScriptureAloneWatch` lists its resources file by file in `project.yml` and this is
/// not one of them — so on the watch there is simply nothing for it to open.
public final class InterlinearStore: @unchecked Sendable {
    /// The one translation this data aligns to, as `TranslationInfo.id` spells it.
    public static let translationID = "BSB"

    /// Whether a translation can show interlinear data at all. False for the ASV and the KJV, for
    /// every import, and for every online translation — the alignment exists for the BSB alone.
    public static func alignsTo(_ translation: TranslationInfo) -> Bool {
        translation.id == translationID
    }

    public let url: URL
    public let attribution: InterlinearAttribution
    public let statistics: InterlinearStatistics

    private let db: OpaquePointer
    private let lock = NSLock()
    /// 3,821 interned parsing codes, loaded once. Immutable after `init`, so it needs no lock.
    private let parsings: [Int: InterlinearParsing]
    /// Inflated chapter blobs and lexicon buckets, both guarded by `lock`.
    private var chapterCache = InflatedCache<[Substring]>(limit: 4)
    private var lexiconCache = InflatedCache<[Substring]>(limit: 8)

    public init(url: URL) throws {
        self.url = url
        var handle: OpaquePointer?
        let uri = "file:\(url.path(percentEncoded: true))?immutable=1"
        guard sqlite3_open_v2(uri, &handle, SQLITE_OPEN_READONLY | SQLITE_OPEN_URI | SQLITE_OPEN_NOMUTEX, nil) == SQLITE_OK,
              let handle else {
            let message = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "unknown error"
            sqlite3_close(handle)
            throw InterlinearStoreError.open(message)
        }
        db = handle

        var meta: [String: String] = [:]
        try Self.rows(db, "SELECT key, value FROM meta") { stmt in
            meta[Self.string(stmt, 0)] = Self.string(stmt, 1)
        }
        attribution = InterlinearAttribution(
            words: meta["bsb_attribution"] ?? "",
            wordsLicense: meta["bsb_license"] ?? "",
            wordsLicenseURL: URL(string: meta["bsb_license_url"] ?? ""),
            wordsSourceURL: URL(string: meta["bsb_url"] ?? ""),
            lexicon: meta["stepbible_attribution"] ?? "",
            lexiconLicense: meta["stepbible_license"] ?? "",
            lexiconLicenseURL: URL(string: meta["stepbible_license_url"] ?? ""),
            lexiconSourceURL: URL(string: meta["stepbible_url"] ?? ""),
            lexiconChanges: meta["stepbible_changes"] ?? "")
        statistics = InterlinearStatistics(
            version: meta["version"] ?? "",
            checked: meta["checked"] ?? "",
            lexiconCommit: meta["stepbible_commit"] ?? "",
            words: Int(meta["words"] ?? "") ?? 0,
            taggedWords: Int(meta["tagged_words"] ?? "") ?? 0,
            strongsNumbers: Int(meta["strongs"] ?? "") ?? 0,
            versesAligned: Int(meta["verses_aligned"] ?? "") ?? 0,
            verses: Int(meta["verses_total"] ?? "") ?? 0)

        var codes: [Int: InterlinearParsing] = [:]
        try Self.rows(db, "SELECT id, code, description FROM parsings") { stmt in
            codes[Int(sqlite3_column_int64(stmt, 0))] = InterlinearParsing(code: Self.string(stmt, 1),
                                                                          description: Self.string(stmt, 2))
        }
        parsings = codes
    }

    deinit { sqlite3_close(db) }

    // MARK: - Coverage

    /// How many original-language words each verse of the chapter has. Verses with none are absent,
    /// so `counts[verse.key] != nil` is the answer to "does this verse have interlinear data".
    ///
    /// This is the call to make while rendering a chapter: one indexed range scan over `verses`
    /// (~30 rows) and no blob is inflated. `crossReferenceCounts(in:)` has the same shape.
    public func wordCounts(in chapter: ChapterRef) throws -> [Int: Int] {
        try locked {
            var counts: [Int: Int] = [:]
            try Self.rows(db, """
                SELECT verse_key, count FROM verses
                WHERE verse_key BETWEEN ?1 AND ?2 AND count > 0
                """, bind: [chapter.keyRange.lowerBound, chapter.keyRange.upperBound]) { stmt in
                counts[Int(sqlite3_column_int64(stmt, 0))] = Int(sqlite3_column_int64(stmt, 1))
            }
            return counts
        }
    }

    /// Whether one verse has any original-language words. One primary-key lookup, no inflation.
    ///
    /// Nehemiah 7:68 is the only verse of the BSB with none — the BSB supplies it from "some Hebrew
    /// manuscripts" and most of the Masoretic Text does not have it.
    public func hasWords(for verse: VerseRef) throws -> Bool {
        try locked {
            var count = 0
            try Self.rows(db, "SELECT count FROM verses WHERE verse_key = ?1", bind: [verse.key]) { stmt in
                count = Int(sqlite3_column_int64(stmt, 0))
            }
            return count > 0
        }
    }

    // MARK: - Words

    /// Every original-language word of one verse, in English (BSB) reading order, with the English
    /// each one renders already resolved.
    ///
    /// A verse with no data — Nehemiah 7:68, or any key this database does not carry — returns an
    /// empty array rather than throwing. Superscription words (Psalm titles, Zechariah 12:1's
    /// oracle heading) come first and are flagged; the BSB keeps them out of `verseText`.
    ///
    /// - Parameters:
    ///   - verse: the verse to read.
    ///   - verseText: **the BSB's** text for that verse, exactly as `BibleStore` stores it. The
    ///     caller supplies it rather than this store fetching it, for two reasons: the UI already
    ///     has that string on screen — it is the very text the returned `range`s highlight, so
    ///     re-reading it here would risk highlighting a *different* string than the one displayed —
    ///     and an interlinear store that owned a `BibleStore` would own a translation's lifetime,
    ///     which is the reader's job, not the study data's. Pass the wrong translation's text and
    ///     this throws `textMismatch` rather than silently mis-highlighting; the
    ///     `words(for:from:)` overload does the fetching when that is what you want.
    public func words(for verse: VerseRef, in verseText: String) throws -> [InterlinearWord] {
        let records = try locked { try self.records(for: verse) }
        guard !records.isEmpty else { return [] }
        let units = Array(verseText.utf16)
        var result: [InterlinearWord] = []
        result.reserveCapacity(records.count)
        for (position, record) in records.enumerated() {
            let fields = record.split(separator: "\t", omittingEmptySubsequences: false)
            guard fields.count >= 10 else {
                throw InterlinearStoreError.query("malformed word record in \(verse.display)")
            }
            let start = Int(fields[7]) ?? -1
            let length = Int(fields[8]) ?? -1
            var english = String(fields[0])
            var range: NSRange?
            if start >= 0, length >= 0 {
                guard start + length <= units.count else { throw InterlinearStoreError.textMismatch(verse) }
                english = String(decoding: units[start..<(start + length)], as: UTF16.self)
                range = NSRange(location: start, length: length)
            }
            let strongs = String(fields[4])
            let parsingID = Int(fields[3]) ?? 0
            result.append(InterlinearWord(
                position: position,
                english: english,
                original: String(fields[1]),
                transliteration: String(fields[2]),
                parsing: parsings[parsingID],
                strongs: strongs.isEmpty ? nil : strongs,
                language: InterlinearLanguage(rawValue: String(fields[5])) ?? .hebrew,
                originalOrder: Int(fields[6]) ?? position + 1,
                range: range,
                isSuperscription: (Int(fields[9]) ?? 0) & 1 == 1))
        }
        return result
    }

    /// `words(for:in:)` with the verse text read out of a `BibleStore`, which must be the BSB.
    public func words(for verse: VerseRef, from bible: BibleStore) throws -> [InterlinearWord] {
        guard Self.alignsTo(bible.info) else {
            throw InterlinearStoreError.wrongTranslation(bible.info.abbreviation.isEmpty ? bible.info.id
                                                                                         : bible.info.abbreviation)
        }
        let text = try bible.verses(in: VerseRange(verse)).first?.text ?? ""
        return try words(for: verse, in: text)
    }

    /// The verse's raw records, sliced out of its chapter blob. Caller holds `lock`.
    private func records(for verse: VerseRef) throws -> ArraySlice<Substring> {
        var chapterKey = 0, first = 0, count = 0
        try Self.rows(db, "SELECT chapter_key, first, count FROM verses WHERE verse_key = ?1",
                      bind: [verse.key]) { stmt in
            chapterKey = Int(sqlite3_column_int64(stmt, 0))
            first = Int(sqlite3_column_int64(stmt, 1))
            count = Int(sqlite3_column_int64(stmt, 2))
        }
        guard count > 0 else { return [] }
        let lines = try chapter(chapterKey)
        guard first >= 0, first + count <= lines.count else {
            throw InterlinearStoreError.query("chapter \(chapterKey) is shorter than \(verse.display) claims")
        }
        return lines[first..<(first + count)]
    }

    /// One chapter's word records, inflated at most once per cache eviction. Caller holds `lock`.
    private func chapter(_ chapterKey: Int) throws -> [Substring] {
        if let cached = chapterCache[chapterKey] { return cached }
        var body = Data()
        try Self.rows(db, "SELECT words FROM chapters WHERE chapter_key = ?1", bind: [chapterKey]) { stmt in
            body = Self.blob(stmt, 0)
        }
        guard let text = StudyStore.inflate(body) else {
            throw InterlinearStoreError.query("chapter \(chapterKey) does not inflate")
        }
        let lines = text.split(separator: "\n", omittingEmptySubsequences: false)
        chapterCache[chapterKey] = lines
        return lines
    }

    // MARK: - Lexicon

    /// The Strong's number's lexicon entry: gloss, fuller definition and sub-senses in order.
    ///
    /// Accepts the zero-padded form the word records use ("H0430") and the way people write it
    /// ("H430", "h430"); an extended key's suffix is dropped, because the build collapsed extended
    /// keys into their base number. Returns nil for a number no entry covers.
    public func entry(for strongs: String) throws -> LexiconEntry? {
        guard let number = Self.normalize(strongs) else { return nil }
        return try locked {
            var bucketID = 0, part = -1
            try Self.rows(db, "SELECT entry_id, part FROM lexicon WHERE strongs = ?1", bind: [number]) { stmt in
                bucketID = Int(sqlite3_column_int64(stmt, 0))
                part = Int(sqlite3_column_int64(stmt, 1))
            }
            guard part >= 0 else { return nil }
            let bucket = try self.lexiconBucket(bucketID)
            guard part < bucket.count else {
                throw InterlinearStoreError.query("lexicon bucket \(bucketID) has no part \(part)")
            }
            // Senses are separated by U+001E, their five fields by tabs.
            let senses = bucket[part].split(separator: "\u{1e}", omittingEmptySubsequences: false).compactMap { sense -> LexiconSense? in
                let fields = sense.split(separator: "\t", omittingEmptySubsequences: false)
                guard fields.count >= 5 else { return nil }
                return LexiconSense(lemma: String(fields[0]), transliteration: String(fields[1]),
                                    morphology: String(fields[2]), gloss: String(fields[3]),
                                    definition: String(fields[4]))
            }
            return senses.isEmpty ? nil : LexiconEntry(strongs: number, senses: senses)
        }
    }

    /// A bucket of 64 entries, separated by U+0000. Caller holds `lock`.
    private func lexiconBucket(_ id: Int) throws -> [Substring] {
        if let cached = lexiconCache[id] { return cached }
        var body = Data()
        try Self.rows(db, "SELECT body FROM lexicon_text WHERE id = ?1", bind: [id]) { stmt in
            body = Self.blob(stmt, 0)
        }
        guard let text = StudyStore.inflate(body) else {
            throw InterlinearStoreError.query("lexicon bucket \(id) does not inflate")
        }
        let parts = text.split(separator: "\0", omittingEmptySubsequences: false)
        lexiconCache[id] = parts
        return parts
    }

    /// "h430" and "H0430G" both mean the entry stored as "H0430". Nil for anything else.
    static func normalize(_ strongs: String) -> String? {
        var letter: Character?
        var digits = ""
        for character in strongs.trimmingCharacters(in: .whitespaces) {
            if letter == nil {
                guard character == "H" || character == "h" || character == "G" || character == "g" else { return nil }
                letter = Character(character.uppercased())
            } else if character.isNumber {
                guard digits.count < 5 else { return nil }
                digits.append(character)
            } else if character.isLetter {
                break   // an extended key's suffix: H0430G is stored under H0430
            } else {
                return nil
            }
        }
        guard let letter, let value = Int(digits) else { return nil }
        return "\(letter)\(String(format: "%04d", value))"
    }

    // MARK: - Cache

    /// A handful of inflated blobs, evicted oldest-first. A reader moves through one chapter at a
    /// time and a lexicon bucket holds 64 consecutive Strong's numbers, so a very small cache turns
    /// a verse's worth of taps into one inflation instead of one per word.
    private struct InflatedCache<Value> {
        private let limit: Int
        private var order: [Int] = []
        private var values: [Int: Value] = [:]

        init(limit: Int) { self.limit = limit }

        subscript(key: Int) -> Value? {
            get { values[key] }
            set {
                guard let newValue else {
                    values[key] = nil
                    order.removeAll { $0 == key }
                    return
                }
                if values[key] == nil {
                    order.append(key)
                    if order.count > limit { values[order.removeFirst()] = nil }
                }
                values[key] = newValue
            }
        }
    }

    // MARK: - SQLite helpers

    private func locked<T>(_ body: () throws -> T) rethrows -> T {
        lock.lock()
        defer { lock.unlock() }
        return try body()
    }

    private static func rows(_ db: OpaquePointer, _ sql: String, bind: [Int] = [], _ row: (OpaquePointer) throws -> Void) throws {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else {
            throw InterlinearStoreError.query(String(cString: sqlite3_errmsg(db)))
        }
        defer { sqlite3_finalize(stmt) }
        for (index, value) in bind.enumerated() {
            sqlite3_bind_int64(stmt, Int32(index + 1), sqlite3_int64(value))
        }
        try step(db, stmt, row)
    }

    private static func rows(_ db: OpaquePointer, _ sql: String, bind: [String], _ row: (OpaquePointer) throws -> Void) throws {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else {
            throw InterlinearStoreError.query(String(cString: sqlite3_errmsg(db)))
        }
        defer { sqlite3_finalize(stmt) }
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        for (index, value) in bind.enumerated() {
            sqlite3_bind_text(stmt, Int32(index + 1), value, -1, transient)
        }
        try step(db, stmt, row)
    }

    private static func step(_ db: OpaquePointer, _ stmt: OpaquePointer, _ row: (OpaquePointer) throws -> Void) throws {
        while true {
            let status = sqlite3_step(stmt)
            if status == SQLITE_ROW {
                try row(stmt)
            } else if status == SQLITE_DONE {
                return
            } else {
                throw InterlinearStoreError.query(String(cString: sqlite3_errmsg(db)))
            }
        }
    }

    private static func string(_ stmt: OpaquePointer, _ column: Int32) -> String {
        guard let cString = sqlite3_column_text(stmt, column) else { return "" }
        return String(cString: cString)
    }

    private static func blob(_ stmt: OpaquePointer, _ column: Int32) -> Data {
        let count = Int(sqlite3_column_bytes(stmt, column))
        guard count > 0, let pointer = sqlite3_column_blob(stmt, column) else { return Data() }
        return Data(bytes: pointer, count: count)
    }
}
