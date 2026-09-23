import Foundation

/// OSIS references — the standard machine form for Bible references: `John.3.16`,
/// `Gen.1.1-Gen.1.3`, `Ps.23`, `1Cor.13.4-1Cor.13.7`. What other apps, study tools and URNs
/// (`urn:osis:John.3.16`) use to name a passage without depending on anyone's language.
///
/// OSIS names verses by KJV versification, which is also what this app stores, so a parsed
/// reference is a **KJV key** passage: go to it with `go(to: VerseRef)`, never `go(to: Passage)`.
///
/// Accepted beyond strict OSIS, because references in the wild bend the rules:
/// - a `urn:osis:` or `osis:` prefix, and an osisRef work prefix (`Bible.KJV:John.3.16`);
/// - USFM book codes (`JHN.3.16`), case-insensitively;
/// - a shortened end (`John.3.16-18`, `John.3-4`, `John.3.16-4.2`);
/// - several references separated by spaces, commas or semicolons;
/// - grain suffixes (`John.3.16!a`, `John.3.16@s[love]`), which are dropped.
public enum OSISReference {
    /// The OSIS book abbreviations, in canonical order (index = `BookID.rawValue - 1`).
    public static let bookCodes: [String] = [
        "Gen", "Exod", "Lev", "Num", "Deut", "Josh", "Judg", "Ruth", "1Sam", "2Sam", "1Kgs", "2Kgs",
        "1Chr", "2Chr", "Ezra", "Neh", "Esth", "Job", "Ps", "Prov", "Eccl", "Song", "Isa", "Jer", "Lam",
        "Ezek", "Dan", "Hos", "Joel", "Amos", "Obad", "Jonah", "Mic", "Nah", "Hab", "Zeph", "Hag", "Zech",
        "Mal", "Matt", "Mark", "Luke", "John", "Acts", "Rom", "1Cor", "2Cor", "Gal", "Eph", "Phil", "Col",
        "1Thess", "2Thess", "1Tim", "2Tim", "Titus", "Phlm", "Heb", "Jas", "1Pet", "2Pet", "1John",
        "2John", "3John", "Jude", "Rev",
    ]

    /// Lowercased OSIS and USFM codes → book. The two sets never disagree about a spelling.
    private static let books: [String: BookID] = {
        var map: [String: BookID] = [:]
        for book in BookID.allCases {
            map[book.info.code.lowercased()] = book
            map[bookCodes[book.rawValue - 1].lowercased()] = book
        }
        return map
    }()

    /// The OSIS code for a book: `John`, `1Cor`.
    public static func code(for book: BookID) -> String { bookCodes[book.rawValue - 1] }

    /// A book from its OSIS or USFM code, ignoring case.
    public static func book(forCode code: String) -> BookID? { books[code.lowercased()] }

    /// `John.3.16`, `John.3.16-John.3.17`, `Gen.1.1-Gen.2.3` — a stored (KJV) range in OSIS form.
    public static func string(for range: VerseRange) -> String {
        func one(_ ref: VerseRef) -> String { "\(code(for: ref.book)).\(ref.chapter).\(ref.verse)" }
        return range.start == range.end ? one(range.start) : "\(one(range.start))-\(one(range.end))"
    }

    /// Strips a `urn:osis:` or `osis:` prefix, which a link may put in front of any reference.
    public static func stripURNPrefix(_ text: String) -> String {
        let s = text.trimmingCharacters(in: .whitespacesAndNewlines)
        for prefix in ["urn:osis:", "osis:"] where s.lowercased().hasPrefix(prefix) {
            return String(s.dropFirst(prefix.count)).trimmingCharacters(in: .whitespaces)
        }
        return s
    }

    /// `stripURNPrefix`, then an osisRef work prefix (`Bible.KJV:John.3.16`). An OSIS reference has
    /// no colon of its own, so everything before one is the work — unless it has a space in it,
    /// which makes it a plain reference like "John 3:16".
    static func stripPrefix(_ text: String) -> String {
        let s = stripURNPrefix(text)
        guard let colon = s.lastIndex(of: ":") else { return s }
        let work = s[..<colon]
        guard work.contains(where: \.isLetter), !work.contains(where: \.isWhitespace) else { return s }
        return String(s[s.index(after: colon)...])
    }

    /// Parses one or more OSIS references. Returns nil unless *every* piece is OSIS, so a plain
    /// reference ("John 3:16") is left for `ReferenceParser`.
    ///
    /// A whole-chapter reference (`Ps.23`) keeps `startVerse == nil`; a range whose end names only
    /// a chapter (`John.3.16-John.4`) has `endVerse == nil` — resolve both against a translation's
    /// verse counts with `Passage.range(verseCount:)`. A range that crosses into another book is
    /// cut at the end of the first book: a `Passage` belongs to one book.
    public static func parse(_ text: String) -> [Passage]? {
        let body = stripPrefix(text)
        let pieces = body.split(whereSeparator: { $0.isWhitespace || $0 == "," || $0 == ";" })
        guard !pieces.isEmpty else { return nil }
        var result: [Passage] = []
        for piece in pieces {
            guard let passage = parseOne(String(piece)) else { return nil }
            result.append(passage)
        }
        return result
    }

    private struct Point {
        var book: BookID?
        var numbers: [Int]
    }

    /// "John.3.16" → (John, [3, 16]); "16" → (nil, [16]). Nil when it isn't dotted OSIS.
    private static func point(_ raw: Substring) -> Point? {
        // Grain and sub-identifiers ("!a", "@s[love]") refine a verse; the verse is what we open.
        let trimmed = raw.split(whereSeparator: { $0 == "!" || $0 == "@" }).first ?? ""
        let parts = trimmed.split(separator: ".", omittingEmptySubsequences: false)
        guard let head = parts.first, !head.isEmpty else { return nil }
        if let number = Int(head) {
            // A bare end ("-18", "-4.2"): numbers only.
            let numbers = parts.compactMap { Int($0) }
            guard numbers.count == parts.count, numbers.count <= 2, number > 0, numbers.allSatisfy({ $0 > 0 })
            else { return nil }
            return Point(book: nil, numbers: numbers)
        }
        guard let book = book(forCode: String(head)) else { return nil }
        let rest = parts.dropFirst()
        let numbers = rest.compactMap { Int($0) }
        guard numbers.count == rest.count, numbers.count <= 2, numbers.allSatisfy({ $0 > 0 }) else { return nil }
        return Point(book: book, numbers: numbers)
    }

    private static func parseOne(_ piece: String) -> Passage? {
        let ends = piece.split(separator: "-", maxSplits: 1, omittingEmptySubsequences: false)
        guard let first = ends.first, let start = point(first), let book = start.book else { return nil }

        // Single-chapter books: "Jude.3" is how people write verse 3; OSIS proper says "Jude.1.3".
        var startNumbers = start.numbers
        if book.isSingleChapter, startNumbers.count == 1, startNumbers[0] > 1 { startNumbers = [1, startNumbers[0]] }

        guard let startChapter = startNumbers.first else {
            // A bare book ("Gen") is its first chapter.
            guard ends.count == 1 else { return nil }
            return Passage(book: book, startChapter: 1)
        }
        guard startChapter <= book.chapterCount else { return nil }
        let startVerse = startNumbers.count > 1 ? startNumbers[1] : nil

        guard ends.count == 2 else {
            return Passage(book: book, startChapter: startChapter, startVerse: startVerse)
        }
        guard let end = point(ends[1]) else { return nil }
        if let endBook = end.book, endBook != book {
            // Crossing into another book: stop at the end of this one.
            guard endBook > book else { return nil }
            return Passage(book: book, startChapter: startChapter, startVerse: startVerse ?? 1,
                           endChapter: book.chapterCount, endVerse: nil)
        }
        var endNumbers = end.numbers
        if end.book != nil, book.isSingleChapter, endNumbers.count == 1, endNumbers[0] > 1 { endNumbers = [1, endNumbers[0]] }

        let endChapter: Int
        let endVerse: Int?
        switch (startVerse, endNumbers.count, end.book) {
        case (_, 0, _):
            return nil
        case (.some, 1, nil):
            // "John.3.16-18": a verse in the same chapter.
            endChapter = startChapter
            endVerse = endNumbers[0]
        case (_, 1, _):
            // "John.3-4", "John.3-John.4", "John.3.16-John.4": a chapter.
            endChapter = endNumbers[0]
            endVerse = nil
        default:
            endChapter = endNumbers[0]
            endVerse = endNumbers[1]
        }
        guard endChapter >= startChapter, endChapter <= book.chapterCount else { return nil }
        if endChapter == startChapter, let startVerse, let verse = endVerse, verse < startVerse { return nil }
        if startVerse == nil {
            // "John.3-John.4.2" starts at verse 1; "John.3-4" stays whole chapters.
            if let endVerse { return Passage(book: book, startChapter: startChapter, startVerse: 1, endChapter: endChapter, endVerse: endVerse) }
            return Passage(book: book, startChapter: startChapter, endChapter: endChapter)
        }
        return Passage(book: book, startChapter: startChapter, startVerse: startVerse, endChapter: endChapter, endVerse: endVerse)
    }
}
