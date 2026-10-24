import Foundation

/// The plans that ship with the app. Each is generated from the canon, so there is nothing to
/// bundle and nothing to license: a plan is a list of references.
public enum PlanCatalog {
    public static let year = 365

    public static var builtIn: [ReadingPlan] { [chronological, canonical] }

    /// Every plan with a body: the built-ins, plus a book plan for each book.
    public static func plan(id: String) -> ReadingPlan? {
        if id == chronological.id { return chronological }
        if id == canonical.id { return canonical }
        if id.hasPrefix(bookPrefix), let raw = Int(id.dropFirst(bookPrefix.count)), let found = BookID(rawValue: raw) {
            return book(found)
        }
        return nil
    }

    // MARK: Bible in a year

    public static let canonical: ReadingPlan = ReadingPlan(
        id: "bible-year-canonical", kind: .canonical, title: "Bible in a Year",
        summary: "Genesis to Revelation in order, about three chapters a day.",
        days: spread(BookID.allCases.map { PlanPassage(book: $0, chapters: 1...$0.chapterCount) }, over: year))

    public static let chronological: ReadingPlan = ReadingPlan(
        id: "bible-year-chronological", kind: .chronological, title: "Bible in a Year, Chronological",
        summary: "The whole Bible in the order the events happened, about three chapters a day.",
        days: spread(chronologicalOrder.map { PlanPassage(book: $0.0, chapters: $0.1...$0.2) }, over: year))

    // MARK: Book by book

    static let bookPrefix = "book-"

    /// One chapter a day through a single book.
    public static func book(_ book: BookID) -> ReadingPlan {
        ReadingPlan(
            id: "\(bookPrefix)\(book.rawValue)", kind: .book, title: book.englishName,
            summary: "One chapter a day through \(book.englishName).",
            days: (1...book.chapterCount).enumerated().map { PlanDay(index: $0.offset, passages: [PlanPassage(book: book, chapters: $0.element...$0.element)]) })
    }

    // MARK: Devotional scaffolds

    /// Structure only: the plans exist so the schema, the Today view and the reminders are proved with
    /// them, but no devotional writing ships until the owner decides who writes it and under what
    /// licence (docs/reading-plans.md). A scaffold has no days and is listed nowhere.
    public static let mensDevotional = scaffold(id: "devotional-men", title: "Men’s Devotional")
    public static let womensDevotional = scaffold(id: "devotional-women", title: "Women’s Devotional")

    private static func scaffold(id: String, title: String) -> ReadingPlan {
        ReadingPlan(id: id, kind: .devotional, title: title, summary: "", days: [], isScaffold: true)
    }

    // MARK: Spreading chapters over days

    /// Cuts a run of passages into `count` days of nearly equal chapters, in order. A day that crosses
    /// a book boundary carries a passage for each book.
    static func spread(_ passages: [PlanPassage], over count: Int) -> [PlanDay] {
        let chapters = passages.flatMap(\.chapters)
        let total = chapters.count
        return (0..<count).map { day in
            let from = day * total / count
            let to = (day + 1) * total / count
            var merged: [PlanPassage] = []
            var run: [ChapterRef] = []
            func flush() {
                if let first = run.first, let last = run.last {
                    merged.append(PlanPassage(book: first.book, chapters: first.chapter...last.chapter))
                }
                run = []
            }
            for chapter in chapters[from..<to] {
                if let last = run.last, last.book != chapter.book { flush() }
                run.append(chapter)
            }
            flush()
            return PlanDay(index: day, passages: merged)
        }
    }

    /// An editorial chronological order, chapter-granular: each segment is (book, first chapter, last
    /// chapter). Together the segments cover every chapter of the canon exactly once (a test holds it).
    static let chronologicalOrder: [(BookID, Int, Int)] = [
        (.genesis, 1, 11), (.job, 1, 42), (.genesis, 12, 50), (.exodus, 1, 40), (.leviticus, 1, 27),
        (.numbers, 1, 36), (.deuteronomy, 1, 34), (.psalms, 90, 90), (.joshua, 1, 24), (.judges, 1, 21),
        (.ruth, 1, 4), (.firstSamuel, 1, 31), (.secondSamuel, 1, 24), (.firstChronicles, 1, 29),
        (.psalms, 1, 72), (.firstKings, 1, 11), (.secondChronicles, 1, 9), (.proverbs, 1, 31),
        (.ecclesiastes, 1, 12), (.songOfSolomon, 1, 8), (.psalms, 73, 89), (.firstKings, 12, 22),
        (.secondChronicles, 10, 20), (.obadiah, 1, 1), (.secondKings, 1, 14), (.secondChronicles, 21, 25),
        (.joel, 1, 3), (.jonah, 1, 4), (.amos, 1, 9), (.hosea, 1, 14), (.secondKings, 15, 16),
        (.secondChronicles, 26, 28), (.isaiah, 1, 39), (.micah, 1, 7), (.secondKings, 17, 19),
        (.secondChronicles, 29, 32), (.psalms, 91, 119), (.nahum, 1, 3), (.secondKings, 20, 21),
        (.secondChronicles, 33, 33), (.zephaniah, 1, 3), (.isaiah, 40, 66), (.jeremiah, 1, 52),
        (.lamentations, 1, 5), (.habakkuk, 1, 3), (.secondKings, 22, 25), (.secondChronicles, 34, 36),
        (.ezekiel, 1, 48), (.daniel, 1, 12), (.ezra, 1, 10), (.haggai, 1, 2), (.zechariah, 1, 14),
        (.esther, 1, 10), (.nehemiah, 1, 13), (.malachi, 1, 4), (.psalms, 120, 150),
        (.matthew, 1, 28), (.mark, 1, 16), (.luke, 1, 24), (.john, 1, 21), (.acts, 1, 28),
        (.james, 1, 5), (.galatians, 1, 6), (.firstThessalonians, 1, 5), (.secondThessalonians, 1, 3),
        (.firstCorinthians, 1, 16), (.secondCorinthians, 1, 13), (.romans, 1, 16), (.ephesians, 1, 6),
        (.philippians, 1, 4), (.colossians, 1, 4), (.philemon, 1, 1), (.firstTimothy, 1, 6),
        (.titus, 1, 3), (.firstPeter, 1, 5), (.jude, 1, 1), (.hebrews, 1, 13), (.secondTimothy, 1, 4),
        (.secondPeter, 1, 3), (.firstJohn, 1, 5), (.secondJohn, 1, 1), (.thirdJohn, 1, 1), (.revelation, 1, 22),
    ]
}
