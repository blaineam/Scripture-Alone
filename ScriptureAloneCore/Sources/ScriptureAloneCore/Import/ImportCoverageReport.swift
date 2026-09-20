// Importing is an iPhone, iPad and Mac feature: the watch has no file picker and no
// catalogue. It is also 32-bit (arm64_32), where the ZIP64 sentinel 0xFFFF_FFFF does not
// fit in an Int at all — so this code is not merely unused there, it cannot compile.
#if !os(watchOS)
import Foundation

/// What the import actually got, book by book.
///
/// Publisher files vary enormously, and a run that yields 40% of Genesis must say so rather than
/// report success. Everything here is computed from the rows themselves — chapter counts come from
/// the app's canon, and missing verses are the gaps in each chapter's own numbering, because no
/// two translations agree on how many verses a chapter has.
public struct ImportCoverageReport: Sendable, Hashable, Codable {
    public struct ChapterCoverage: Sendable, Hashable, Codable {
        public let chapter: Int
        public let highestVerse: Int
        public let versesFound: Int
        /// Numbers missing from 1...highestVerse. A translation that genuinely omits a verse
        /// (the ASV omits sixteen) shows up here too, which is the honest answer.
        public let missingVerses: [Int]
        public let outOfOrder: Bool
    }

    public struct BookCoverage: Sendable, Hashable, Codable, Identifiable {
        public let book: BookID
        public let chaptersFound: Int
        public let chaptersExpected: Int
        public let versesFound: Int
        public let missingChapters: [Int]
        public let unexpectedChapters: [Int]
        public let chaptersWithGaps: [ChapterCoverage]

        public var id: Int { book.rawValue }
        public var completeness: Double {
            chaptersExpected == 0 ? 0 : Double(chaptersFound) / Double(chaptersExpected)
        }
        public var isComplete: Bool { missingChapters.isEmpty && chaptersWithGaps.isEmpty && unexpectedChapters.isEmpty }
    }

    public let books: [BookCoverage]
    public let booksMissing: [BookID]
    public let totalVerses: Int
    public let totalChapters: Int
    /// How each spine document's (or USFM file's) verse markup was recognised.
    public let markupShapes: [String: VerseMarkupShape]
    public let notes: [ImportNote]

    public var booksFound: [BookID] { books.map(\.book) }
    public var isWholeBible: Bool { booksMissing.isEmpty && books.allSatisfy(\.isComplete) }
    /// Share of the canon's 1,189 chapters that arrived.
    public var completeness: Double {
        let expected = BookID.allCases.reduce(0) { $0 + $1.chapterCount }
        return expected == 0 ? 0 : Double(totalChapters) / Double(expected)
    }

    public var highestSeverity: ImportNote.Severity {
        notes.map(\.severity).max() ?? .info
    }

    /// One line the UI can show without composing its own.
    public var summary: String {
        let books = booksFound.count
        let percent = Int((completeness * 100).rounded())
        if isWholeBible { return "All 66 books, \(totalChapters) chapters, \(totalVerses) verses." }
        return "\(books) book\(books == 1 ? "" : "s"), \(totalChapters) chapters, \(totalVerses) verses — \(percent)% of the canon."
    }

    /// Short lines describing everything incomplete, worst first.
    public var problems: [String] {
        var lines: [String] = []
        if !booksMissing.isEmpty {
            let names = booksMissing.map(\.name)
            let shown = names.prefix(6).joined(separator: ", ")
            lines.append(booksMissing.count > 6
                         ? "\(booksMissing.count) books are missing, including \(shown)."
                         : "Missing: \(shown).")
        }
        for book in books where !book.isComplete {
            if !book.missingChapters.isEmpty {
                lines.append("\(book.name): \(book.chaptersFound) of \(book.chaptersExpected) chapters "
                             + "(missing \(Self.condense(book.missingChapters))).")
            }
            if !book.unexpectedChapters.isEmpty {
                lines.append("\(book.name): unexpected chapter\(book.unexpectedChapters.count == 1 ? "" : "s") "
                             + Self.condense(book.unexpectedChapters) + ".")
            }
            for chapter in book.chaptersWithGaps {
                if !chapter.missingVerses.isEmpty {
                    lines.append("\(book.book.name) \(chapter.chapter): missing verse\(chapter.missingVerses.count == 1 ? "" : "s") "
                                 + Self.condense(chapter.missingVerses) + ".")
                }
                if chapter.outOfOrder {
                    lines.append("\(book.book.name) \(chapter.chapter): verse numbers ran out of order.")
                }
            }
        }
        lines.append(contentsOf: notes.filter { $0.severity != .info }.map(\.message))
        return lines
    }

    /// "1–3, 7, 19–21"
    static func condense(_ numbers: [Int]) -> String {
        var runs: [String] = []
        var index = 0
        let sorted = numbers.sorted()
        while index < sorted.count {
            var end = index
            while end + 1 < sorted.count, sorted[end + 1] == sorted[end] + 1 { end += 1 }
            runs.append(end == index ? "\(sorted[index])" : "\(sorted[index])–\(sorted[end])")
            index = end + 1
        }
        return runs.joined(separator: ", ")
    }

    // MARK: - Building

    public init(_ bible: ExtractedBible) {
        var coverage: [BookCoverage] = []
        var verseTotal = 0
        var chapterTotal = 0
        let outOfOrderChapters = bible.outOfOrderChapters
        // A verse the source printed as part of the verse before it is combined, not missing.
        let bridged = Set(bible.bridgedVerses.keys)

        for book in bible.books {
            let chapters = bible.chapterOrder.filter { $0.book == book }.map(\.chapter).sorted()
            var gaps: [ChapterCoverage] = []
            var versesInBook = 0
            for chapter in chapters {
                let ref = ChapterRef(book, chapter)
                let numbers = bible.verseNumbers(in: ref)
                versesInBook += numbers.count
                let highest = numbers.last ?? 0
                let present = Set(numbers)
                let missing = highest > 0
                    ? (1...highest).filter { !present.contains($0) && !bridged.contains(VerseRef(book, chapter, $0)) }
                    : []
                let disordered = outOfOrderChapters.contains(ref)
                if !missing.isEmpty || disordered || numbers.isEmpty {
                    gaps.append(ChapterCoverage(chapter: chapter, highestVerse: highest, versesFound: numbers.count,
                                                missingVerses: missing, outOfOrder: disordered))
                }
            }
            let expected = book.chapterCount
            let found = Set(chapters)
            let missingChapters = (1...expected).filter { !found.contains($0) }
            let unexpected = chapters.filter { $0 > expected || $0 < 1 }
            verseTotal += versesInBook
            chapterTotal += chapters.count
            coverage.append(BookCoverage(book: book, chaptersFound: chapters.count, chaptersExpected: expected,
                                         versesFound: versesInBook, missingChapters: missingChapters,
                                         unexpectedChapters: unexpected, chaptersWithGaps: gaps))
        }

        let present = Set(bible.books)
        books = coverage
        booksMissing = BookID.allCases.filter { !present.contains($0) }
        totalVerses = verseTotal
        totalChapters = chapterTotal
        markupShapes = bible.shapesByDocument
        notes = bible.notes
    }
}

private extension ImportCoverageReport.BookCoverage {
    var name: String { book.name }
}
#endif
