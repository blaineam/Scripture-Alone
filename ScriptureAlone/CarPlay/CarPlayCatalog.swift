#if os(iOS)
import Foundation
import ScriptureAloneCore

/// One row of a CarPlay list, and what choosing it does. The lists are built here, as plain values, so what
/// CarPlay shows is tested without a car: `CarPlayTemplates` only turns these into Apple's templates.
struct CarPlayEntry: Equatable {
    indirect enum Action: Equatable {
        /// Read today's reading in the reader's plan (Spurgeon's devotion included).
        case playToday
        /// Read on from where the reader left off.
        case playContinue
        case playChapter(ChapterRef)
        /// A favorite: stored (KJV) verse keys.
        case playRange(VerseRange)
        /// A folder of more rows — a testament, a book, a run of chapters.
        case open(title: String, entries: [CarPlayEntry])
    }

    let title: String
    let detail: String?
    let action: Action
}

enum CarPlayCatalog {
    /// How many rows a list may hold when the car won't say (CarPlay's own limit varies, from 12 up).
    static let defaultLimit = 12

    // MARK: Listen

    static func listen(today: TodaysReading?, location: ChapterRef) -> [CarPlayEntry] {
        [
            CarPlayEntry(
                title: String(localized: "Today’s Reading", comment: "CarPlay list row: reads the day's reading in the reader's plan"),
                detail: today?.display ?? String(localized: "Start a plan in Scripture Alone", comment: "CarPlay row detail when no reading plan is started"),
                action: .playToday),
            CarPlayEntry(
                title: String(localized: "Continue", comment: "CarPlay list row: reads on from where the reader left off"),
                detail: location.display, action: .playContinue),
        ]
    }

    // MARK: Books

    /// The Old and New Testaments, each a folder of books, each book a folder of chapters; every list kept to `limit`.
    static func testaments(limit: Int = defaultLimit) -> [CarPlayEntry] {
        let old = BookID.allCases.filter { !$0.isNewTestament }
        let new = BookID.allCases.filter(\.isNewTestament)
        return [
            folder(String(localized: "Old Testament", comment: "CarPlay: the Old Testament's books"), books: old, limit: limit),
            folder(String(localized: "New Testament", comment: "CarPlay: the New Testament's books"), books: new, limit: limit),
        ]
    }

    private static func folder(_ title: String, books: [BookID], limit: Int) -> CarPlayEntry {
        CarPlayEntry(title: title, detail: nil,
                     action: .open(title: title, entries: grouped(books.map { book($0, limit: limit) }, limit: limit)))
    }

    /// A book: its chapters, or the one chapter of a single-chapter book, which plays at once.
    static func book(_ book: BookID, limit: Int = defaultLimit) -> CarPlayEntry {
        if book.isSingleChapter {
            return CarPlayEntry(title: book.name, detail: nil, action: .playChapter(ChapterRef(book, 1)))
        }
        let chapters = (1...book.chapterCount).map { n in
            CarPlayEntry(title: "\(n)", detail: ChapterRef(book, n).display, action: .playChapter(ChapterRef(book, n)))
        }
        return CarPlayEntry(title: book.name, detail: nil,
                            action: .open(title: book.name, entries: grouped(chapters, limit: limit)))
    }

    // MARK: Favorites

    static func favorites(_ ranges: [VerseRange], display: (VerseRange) -> String, limit: Int = defaultLimit) -> [CarPlayEntry] {
        grouped(ranges.map { CarPlayEntry(title: display($0), detail: nil, action: .playRange($0)) }, limit: limit)
    }

    // MARK: Limits

    /// `entries` as they are when they fit in `limit` rows; otherwise folders of at most `limit` each, labelled by
    /// their first and last row ("Genesis – Ruth"), and folders of those folders if even they are too many.
    static func grouped(_ entries: [CarPlayEntry], limit: Int) -> [CarPlayEntry] {
        let limit = max(2, limit)
        guard entries.count > limit else { return entries }
        let folders = stride(from: 0, to: entries.count, by: limit).map { start -> CarPlayEntry in
            let slice = Array(entries[start..<min(start + limit, entries.count)])
            // A folder of folders spans from the first one's start to the last one's end: "1 – 144", not "1 – 12 – 133 – 144".
            let first = slice.first!.title.components(separatedBy: " – ").first!
            let last = slice.last!.title.components(separatedBy: " – ").last!
            let title = slice.count == 1 ? slice[0].title : "\(first) – \(last)"
            return CarPlayEntry(title: title, detail: nil, action: .open(title: title, entries: slice))
        }
        return grouped(folders, limit: limit)
    }
}
#endif
