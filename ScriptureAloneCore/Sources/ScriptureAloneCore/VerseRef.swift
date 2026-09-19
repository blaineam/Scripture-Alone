import Foundation

/// A single verse. `key` (book * 1_000_000 + chapter * 1_000 + verse) is the stable
/// identifier stored with highlights and notes, and matches the databases' verse ids.
public struct VerseRef: Hashable, Comparable, Sendable, Codable {
    public let book: BookID
    public let chapter: Int
    public let verse: Int

    public init(_ book: BookID, _ chapter: Int, _ verse: Int) {
        self.book = book
        self.chapter = chapter
        self.verse = verse
    }

    public init?(key: Int) {
        guard let book = BookID(rawValue: key / 1_000_000) else { return nil }
        self.init(book, key / 1_000 % 1_000, key % 1_000)
    }

    public var key: Int { book.rawValue * 1_000_000 + chapter * 1_000 + verse }
    public var chapterKey: ChapterRef { ChapterRef(book, chapter) }

    public static func < (lhs: VerseRef, rhs: VerseRef) -> Bool { lhs.key < rhs.key }

    /// "John 3:16"
    public var display: String { "\(book.name) \(chapter):\(verse)" }
}

public struct ChapterRef: Hashable, Comparable, Sendable, Codable {
    public let book: BookID
    public let chapter: Int

    public init(_ book: BookID, _ chapter: Int) {
        self.book = book
        self.chapter = chapter
    }

    /// Range of verse keys this chapter can contain.
    public var keyRange: ClosedRange<Int> {
        let base = book.rawValue * 1_000_000 + chapter * 1_000
        return base...(base + 999)
    }

    public static func < (lhs: ChapterRef, rhs: ChapterRef) -> Bool {
        (lhs.book.rawValue, lhs.chapter) < (rhs.book.rawValue, rhs.chapter)
    }

    public var display: String { book.isSingleChapter ? book.name : "\(book.name) \(chapter)" }

    public var next: ChapterRef? {
        if chapter < book.chapterCount { return ChapterRef(book, chapter + 1) }
        guard let nextBook = BookID(rawValue: book.rawValue + 1) else { return nil }
        return ChapterRef(nextBook, 1)
    }

    public var previous: ChapterRef? {
        if chapter > 1 { return ChapterRef(book, chapter - 1) }
        guard let previousBook = BookID(rawValue: book.rawValue - 1) else { return nil }
        return ChapterRef(previousBook, previousBook.chapterCount)
    }
}

/// An inclusive span of verses, possibly across chapters.
public struct VerseRange: Hashable, Sendable, Codable, Comparable {
    public let start: VerseRef
    public let end: VerseRef

    public init(_ start: VerseRef, _ end: VerseRef) {
        self.start = min(start, end)
        self.end = max(start, end)
    }

    public init(_ verse: VerseRef) { self.init(verse, verse) }

    public func contains(_ verse: VerseRef) -> Bool { (start.key...end.key).contains(verse.key) }
    public func contains(key: Int) -> Bool { (start.key...end.key).contains(key) }
    public func overlaps(_ chapter: ChapterRef) -> Bool { (start.key...end.key).overlaps(chapter.keyRange) }

    public static func < (lhs: VerseRange, rhs: VerseRange) -> Bool {
        (lhs.start, lhs.end) < (rhs.start, rhs.end)
    }

    /// "John 3:16", "Romans 8:1–17", "Genesis 1:1–2:3", "Romans 8:1–Romans 9:2"
    public var display: String {
        if start == end { return start.display }
        if start.book != end.book { return "\(start.display)–\(end.display)" }
        if start.chapter != end.chapter { return "\(start.display)–\(end.chapter):\(end.verse)" }
        return "\(start.display)–\(end.verse)"
    }

    /// Compact storage form: "43003016-43003018".
    public var storageString: String { "\(start.key)-\(end.key)" }

    public init?(storageString: String) {
        let parts = storageString.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 2, let a = VerseRef(key: parts[0]), let b = VerseRef(key: parts[1]) else { return nil }
        self.init(a, b)
    }

    /// Collapses a set of verse keys into contiguous ranges, given how many verses each chapter has.
    public static func ranges(from keys: some Collection<Int>, verseCount: (ChapterRef) -> Int) -> [VerseRange] {
        let refs = keys.compactMap(VerseRef.init(key:)).sorted()
        var result: [VerseRange] = []
        for ref in refs {
            if let last = result.last {
                let end = last.end
                let adjacent = (end.chapterKey == ref.chapterKey && ref.verse == end.verse + 1)
                    || (ref.verse == 1 && end.chapterKey.next == ref.chapterKey && end.verse >= verseCount(end.chapterKey))
                if adjacent {
                    result[result.count - 1] = VerseRange(last.start, ref)
                    continue
                }
            }
            result.append(VerseRange(ref))
        }
        return result
    }
}
