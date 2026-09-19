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
    public static func books(matching raw: String) -> [BookID] {
        let token = BookInfo.normalize(normalizeOrdinals(raw.lowercased().trimmingCharacters(in: .whitespaces)))
        guard !token.isEmpty else { return [] }
        if let exact = BookID.allCases.first(where: { $0.info.aliases.contains(token) }) {
            let others = prefixMatches(token).filter { $0 != exact }
            return [exact] + others
        }
        return prefixMatches(token)
    }

    private static func prefixMatches(_ token: String) -> [BookID] {
        BookID.allCases
            .filter { book in book.info.aliases.contains { $0.hasPrefix(token) } }
            .sorted { rank($0) < rank($1) }
    }

    private static let pattern: NSRegularExpression = {
        // book, chapter, (":" verse | " " verse), ("-" chapter-or-verse (":" verse)?)
        let p = #"^([1-3]?\s*[a-z][a-z ]*?)\s*(\d+)?(?:\s*[:.]\s*(\d+)|\s+(\d+))?(?:\s*-\s*(\d+)?(?:\s*[:.]\s*(\d+))?)?$"#
        return try! NSRegularExpression(pattern: p)
    }()

    static func clean(_ text: String) -> String {
        var s = text.lowercased()
            .replacingOccurrences(of: "–", with: "-")
            .replacingOccurrences(of: "—", with: "-")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        // Periods after abbreviations ("Rom. 8") are noise; keep "3.16" as a separator.
        s = s.replacingOccurrences(of: #"(?<=[a-z])\."#, with: " ", options: .regularExpression)
        s = s.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
        return normalizeOrdinals(s)
    }

    /// Parses a single reference: "jn 3 16", "Rom 8:28-39", "1co13", "Ps 23", "Gen 1:1–2:3", "Jude 3".
    public static func parse(_ text: String) -> Passage? {
        let s = clean(text)
        let ns = s as NSString
        guard let m = pattern.firstMatch(in: s, range: NSRange(location: 0, length: ns.length)) else { return nil }
        func group(_ i: Int) -> String? {
            let r = m.range(at: i)
            return r.location == NSNotFound ? nil : ns.substring(with: r)
        }
        func number(_ i: Int) -> Int? { group(i).flatMap { Int($0) } }
        guard let bookText = group(1), let book = books(matching: bookText).first else { return nil }

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
    public static func parseList(_ text: String) -> [Passage] {
        var results: [Passage] = []
        let pieces = text.split(whereSeparator: { $0 == ";" || $0 == "," }).map { $0.trimmingCharacters(in: .whitespaces) }
        for piece in pieces where !piece.isEmpty {
            if piece.first?.isLetter == true || piece.range(of: #"^[1-3]\s*[A-Za-z]"#, options: .regularExpression) != nil {
                if let p = parse(piece) { results.append(p) }
                continue
            }
            guard let last = results.last else { continue }
            let s = clean(piece)
            if s.contains(":") || s.contains(".") {
                if let p = parse("\(last.book.info.aliases[0]) \(s)") { results.append(p) }
            } else if last.startVerse != nil {
                // "Rom 3:23, 25" → verse 25 of the same chapter; "…, 25-27" → a verse range.
                let parts = s.split(separator: "-").compactMap { Int($0.trimmingCharacters(in: .whitespaces)) }
                if let v1 = parts.first {
                    results.append(Passage(book: last.book, startChapter: last.endChapter, startVerse: v1,
                                           endChapter: last.endChapter, endVerse: parts.count > 1 ? parts[1] : v1))
                }
            } else if let p = parse("\(last.book.info.aliases[0]) \(s)") {
                results.append(p)
            }
        }
        return results
    }
}
