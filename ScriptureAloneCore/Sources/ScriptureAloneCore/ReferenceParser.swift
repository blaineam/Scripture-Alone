import Foundation

/// A passage as typed: a book with optional chapter/verse bounds. Verse counts are
/// resolved later against a translation (`range(verseCount:)`).
public struct Passage: Hashable, Sendable {
    public let book: BookID
    public let startChapter: Int
    public let startVerse: Int?
    public let endChapter: Int
    public let endVerse: Int?

    public init(book: BookID, startChapter: Int, startVerse: Int? = nil, endChapter: Int? = nil, endVerse: Int? = nil) {
        self.book = book
        self.startChapter = startChapter
        self.startVerse = startVerse
        self.endChapter = endChapter ?? startChapter
        self.endVerse = endVerse ?? (endChapter == nil ? startVerse : nil)
    }

    public var chapter: ChapterRef { ChapterRef(book, startChapter) }
    public var firstVerse: VerseRef { VerseRef(book, startChapter, startVerse ?? 1) }
    public var isWholeChapter: Bool { startVerse == nil }

    public func range(verseCount: (ChapterRef) -> Int) -> VerseRange {
        let endChapterRef = ChapterRef(book, endChapter)
        let last = endVerse ?? verseCount(endChapterRef)
        return VerseRange(firstVerse, VerseRef(book, endChapter, max(1, last)))
    }

    /// Clamps chapters to the book (verses are clamped by the reader against real counts).
    public var clamped: Passage {
        let c1 = min(max(1, startChapter), book.chapterCount)
        let c2 = min(max(c1, endChapter), book.chapterCount)
        return Passage(book: book, startChapter: c1, startVerse: startVerse, endChapter: c2, endVerse: endVerse)
    }

    /// "John 3:16", "Romans 8:28–39", "Psalms 23", "Genesis 1:1–2:3", "Psalms 23–24"
    public var display: String {
        let name = book.name
        switch (startVerse, endVerse) {
        case (nil, _):
            if book.isSingleChapter { return name }
            return endChapter == startChapter ? "\(name) \(startChapter)" : "\(name) \(startChapter)–\(endChapter)"
        case let (v1?, v2):
            let head = book.isSingleChapter ? "\(name) \(v1)" : "\(name) \(startChapter):\(v1)"
            if endChapter != startChapter {
                return "\(head)–\(endChapter):\(v2.map(String.init) ?? "")"
            }
            guard let v2, v2 != v1 else { return head }
            return "\(head)–\(v2)"
        }
    }
}

public enum ReferenceParser {
    /// Books most people mean when an abbreviation is ambiguous ("jo" → John, "ph" → Philippians).
    private static let popularity: [BookID] = [
        .john, .psalms, .romans, .matthew, .genesis, .mark, .luke, .proverbs, .isaiah, .acts,
        .philippians, .ephesians, .hebrews, .james, .firstCorinthians, .galatians, .revelation,
        .exodus, .job, .jeremiah, .daniel, .colossians, .firstJohn, .firstPeter, .joshua, .judges,
        .ruth, .jonah, .deuteronomy, .leviticus, .numbers, .ecclesiastes, .firstSamuel, .firstKings,
        .firstThessalonians, .firstTimothy, .secondTimothy, .titus, .jude,
    ]

    private static func rank(_ book: BookID) -> Int {
        popularity.firstIndex(of: book) ?? (100 + book.rawValue)
    }

    /// Replaces spelled-out or Roman ordinals before a book name: "First John", "II Cor" → "1 john", "2 cor".
    static func normalizeOrdinals(_ s: String) -> String {
        let patterns: [(String, String)] = [
            (#"^(iii|3rd|third)\s+"#, "3 "), (#"^(ii|2nd|second)\s+"#, "2 "), (#"^(i|1st|first)\s+"#, "1 "),
        ]
        for (pattern, replacement) in patterns {
            if let range = s.range(of: pattern, options: .regularExpression) {
                return s.replacingCharacters(in: range, with: replacement)
            }
        }
        return s
    }

    /// Books matching what the user has typed so far, best first. Exact abbreviations win,
    /// then prefixes of names or abbreviations, ordered by how often each book is read.
    public static func books(matching raw: String, language: String? = BookNames.current) -> [BookID] {
        let token = BookInfo.normalize(normalizeOrdinals(raw.lowercased().trimmingCharacters(in: .whitespaces)))
        guard !token.isEmpty else { return [] }
        // The reader's own language first, then English, then every other language's spellings
        // that mean only one book — "Jean 3:16" works for anyone, "Es 1" only means Isaiah in French.
        let tiers = spellingTiers(language)
        for tier in tiers {
            if let exact = BookID.allCases.first(where: { tier[$0]?.contains(token) == true }) {
                let others = prefixMatches(token, tiers).filter { $0 != exact }
                return [exact] + others
            }
        }
        return prefixMatches(token, tiers)
    }

    private static func spellingTiers(_ current: String?) -> [[BookID: Set<String>]] {
        var tiers: [[BookID: Set<String>]] = []
        if let current, let own = BookNames.aliases[current] { tiers.append(own) }
        tiers.append(Dictionary(uniqueKeysWithValues: BookID.allCases.map { ($0, Set($0.info.aliases)) }))
        var others: [BookID: Set<String>] = [:]
        for (language, books) in BookNames.aliases where language != current {
            for (book, spellings) in books { others[book, default: []].formUnion(spellings.subtracting(BookNames.ambiguous)) }
        }
        tiers.append(others)
        return tiers
    }

    private static func prefixMatches(_ token: String, _ tiers: [[BookID: Set<String>]]) -> [BookID] {
        for tier in tiers {
            let hits = BookID.allCases.filter { book in tier[book]?.contains { $0.hasPrefix(token) } == true }
            if !hits.isEmpty { return hits.sorted { rank($0) < rank($1) } }
        }
        return []
    }

    private static let pattern: NSRegularExpression = {
        // book, chapter, (":" verse | " " verse), ("-" chapter-or-verse (":" verse)?)
        // Letters in any script: "Genèse", "创世记", "요한복음", "ヨハネ傳福音書".
        let p = #"^([1-3]?\s*\p{L}[\p{L}\p{M} ]*?)\s*(\d+)?(?:\s*[:.]\s*(\d+)|\s+(\d+))?(?:\s*-\s*(\d+)?(?:\s*[:.]\s*(\d+))?)?$"#
        return try! NSRegularExpression(pattern: p)
    }()

    static func clean(_ text: String) -> String {
        var s = text.lowercased()
            .replacingOccurrences(of: "–", with: "-")
            .replacingOccurrences(of: "—", with: "-")
            .replacingOccurrences(of: "〜", with: "-")
            .replacingOccurrences(of: "～", with: "-")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        // Full-width digits and punctuation, as a Chinese, Japanese or Korean keyboard types them.
        s = String(s.unicodeScalars.map { scalar -> Character in
            switch scalar.value {
            case 0xFF10...0xFF19: Character(UnicodeScalar(scalar.value - 0xFF10 + 0x30)!)
            case 0xFF1A: ":"
            case 0xFF0E, 0x3002: "."
            case 0xFF0D: "-"
            default: Character(scalar)
            }
        })
        // "3章16節", "3章16节", "3장 16절": chapter and verse counters.
        s = s.replacingOccurrences(of: #"(\d+)\s*[章장]\s*(\d+)\s*[節节절]?"#, with: "$1:$2", options: .regularExpression)
        s = s.replacingOccurrences(of: #"(\d+)\s*[章장節节절]"#, with: "$1", options: .regularExpression)
        // "Joh 3,16": German writes chapter and verse with a comma.
        s = s.replacingOccurrences(of: #"(\d),(\d)"#, with: "$1:$2", options: .regularExpression)
        // "1. Mose", "2. Korinther": a German ordinal's period is not a separator.
        s = s.replacingOccurrences(of: #"^([1-3])\.\s*(?=\p{L})"#, with: "$1 ", options: .regularExpression)
        // Periods after abbreviations ("Rom. 8") are noise; keep "3.16" as a separator.
        s = s.replacingOccurrences(of: #"(?<=\p{L})\."#, with: " ", options: .regularExpression)
        s = s.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
        return normalizeOrdinals(s)
    }

    /// Parses a single reference: "jn 3 16", "Rom 8:28-39", "1co13", "Ps 23", "Gen 1:1–2:3", "Jude 3".
    public static func parse(_ text: String, language: String? = BookNames.current) -> Passage? {
        let s = clean(text)
        let ns = s as NSString
        guard let m = pattern.firstMatch(in: s, range: NSRange(location: 0, length: ns.length)) else { return nil }
        func group(_ i: Int) -> String? {
            let r = m.range(at: i)
            return r.location == NSNotFound ? nil : ns.substring(with: r)
        }
        func number(_ i: Int) -> Int? { group(i).flatMap { Int($0) } }
        guard let bookText = group(1), let book = books(matching: bookText, language: language).first else { return nil }

        let first = number(2)
        let verse = number(3) ?? number(4)
        let dashA = number(5)
        let dashB = number(6)

        guard let chapter = first else {
            return Passage(book: book, startChapter: 1)
        }
        if book.isSingleChapter, verse == nil {
            // "Jude 3" / "Jude 3-5" mean verses.
            if chapter == 1, dashA == nil { return Passage(book: book, startChapter: 1) }
            return Passage(book: book, startChapter: 1, startVerse: chapter, endChapter: 1, endVerse: dashA ?? chapter)
        }
        if let verse {
            if let dashA, let dashB {
                return Passage(book: book, startChapter: chapter, startVerse: verse, endChapter: dashA, endVerse: dashB)
            }
            return Passage(book: book, startChapter: chapter, startVerse: verse, endChapter: chapter, endVerse: dashA ?? verse)
        }
        if let dashA {
            if let dashB {
                // "Gen 1-2:3" → 1:1 through 2:3
                return Passage(book: book, startChapter: chapter, startVerse: 1, endChapter: dashA, endVerse: dashB)
            }
            return Passage(book: book, startChapter: chapter, endChapter: max(chapter, dashA))
        }
        return Passage(book: book, startChapter: chapter)
    }

    /// Parses a list: "Eph 2:1-10; Rom 3:23, 6:23" or "Ps 23, 24". Later items inherit the book
    /// (and chapter, for bare verse numbers after a verse reference).
    public static func parseList(_ text: String, language: String? = BookNames.current) -> [Passage] {
        var results: [Passage] = []
        // Full-width separators too: "；" "，" "、" in Chinese and Japanese lists.
        // A comma between digits is German's chapter-verse separator ("Joh 3,16"), not a list break.
        let joined = text.replacingOccurrences(of: #"(\d),(\d)"#, with: "$1:$2", options: .regularExpression)
        let pieces = joined.split(whereSeparator: { ";,；，、".contains($0) }).map { $0.trimmingCharacters(in: .whitespaces) }
        for piece in pieces where !piece.isEmpty {
            if piece.first?.isLetter == true || piece.range(of: #"^[1-3]\.?\s*\p{L}"#, options: .regularExpression) != nil {
                if let p = parse(piece, language: language) { results.append(p) }
                continue
            }
            guard let last = results.last else { continue }
            let s = clean(piece)
            if s.contains(":") || s.contains(".") {
                if let p = parse("\(last.book.info.aliases[0]) \(s)", language: language) { results.append(p) }
            } else if last.startVerse != nil {
                // "Rom 3:23, 25" → verse 25 of the same chapter; "…, 25-27" → a verse range.
                let parts = s.split(separator: "-").compactMap { Int($0.trimmingCharacters(in: .whitespaces)) }
                if let v1 = parts.first {
                    results.append(Passage(book: last.book, startChapter: last.endChapter, startVerse: v1,
                                           endChapter: last.endChapter, endVerse: parts.count > 1 ? parts[1] : v1))
                }
            } else if let p = parse("\(last.book.info.aliases[0]) \(s)", language: language) {
                results.append(p)
            }
        }
        return results
    }
}
