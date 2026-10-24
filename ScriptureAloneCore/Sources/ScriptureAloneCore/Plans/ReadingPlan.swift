import Foundation

/// A passage of a reading plan, in the form it is stored. `Passage` is the parser's value type and is
/// not `Codable`; this is the same five fields, KJV-numbered like everything else the app stores.
public struct PlanPassage: Codable, Hashable, Sendable {
    public let book: BookID
    public let startChapter: Int
    public let startVerse: Int?
    public let endChapter: Int
    public let endVerse: Int?

    public init(_ passage: Passage) {
        book = passage.book
        startChapter = passage.startChapter
        startVerse = passage.startVerse
        endChapter = passage.endChapter
        endVerse = passage.endVerse
    }

    public init(book: BookID, chapters: ClosedRange<Int>) {
        self.init(Passage(book: book, startChapter: chapters.lowerBound, endChapter: chapters.upperBound))
    }

    public var passage: Passage {
        Passage(book: book, startChapter: startChapter, startVerse: startVerse, endChapter: endChapter, endVerse: endVerse)
    }

    public var display: String { passage.display }

    /// Every chapter the passage touches, in order.
    public var chapters: [ChapterRef] {
        let clamped = passage.clamped
        return (clamped.startChapter...max(clamped.startChapter, clamped.endChapter)).map { ChapterRef(book, $0) }
    }
}

/// One day of a plan: what to read.
public struct PlanDay: Codable, Hashable, Sendable, Identifiable {
    /// Position in the plan, from 0.
    public let index: Int
    /// The day the source published it, "yyyy-MM-dd". Present for an imported plan; a built-in plan
    /// has no dates, only an order.
    public let date: String?
    /// A heading for the day: "Epistle", "Exile Era" — whatever the source calls this stretch.
    public let label: String?
    public let passages: [PlanPassage]

    public var id: Int { index }

    public init(index: Int, date: String? = nil, label: String? = nil, passages: [PlanPassage]) {
        self.index = index
        self.date = date
        self.label = label
        self.passages = passages
    }

    public var display: String { passages.map(\.display).joined(separator: "; ") }
}

public enum PlanKind: String, Codable, Sendable {
    case canonical, chronological, book, imported
}

/// Where an imported plan came from, so its weekly study can be fetched on demand and credited.
public struct PlanSource: Codable, Hashable, Sendable {
    /// The site's front page for the plan, what "Open source" goes to.
    public let siteURL: URL
    /// The WordPress REST root ("https://example.com/bible/wp-json/wp/v2") when the source has one.
    public let apiRoot: URL?
    /// The WordPress category of the commentary posts, when the source publishes any.
    public let studyCategory: Int?
    /// The name the source gives itself, for credit lines.
    public let name: String
    /// The WordPress category the schedule came from, so a refresh asks for the same posts.
    public let scheduleCategory: Int?

    public init(siteURL: URL, apiRoot: URL?, studyCategory: Int?, name: String, scheduleCategory: Int? = nil) {
        self.scheduleCategory = scheduleCategory
        self.siteURL = siteURL
        self.apiRoot = apiRoot
        self.studyCategory = studyCategory
        self.name = name
    }
}

/// A schedule of passage references. The text is never part of a plan: it is read from the
/// translation the reader has chosen, on the day.
public struct ReadingPlan: Codable, Hashable, Sendable, Identifiable {
    public let id: String
    public let kind: PlanKind
    public let title: String
    public let summary: String
    public let days: [PlanDay]
    /// Calendar weekdays (1 = Sunday … 7 = Saturday) a plan with no dates of its own skips.
    public let skipWeekdays: Set<Int>
    public let source: PlanSource?

    public init(id: String, kind: PlanKind, title: String, summary: String, days: [PlanDay],
                skipWeekdays: Set<Int> = [], source: PlanSource? = nil) {
        self.id = id
        self.kind = kind
        self.title = title
        self.summary = summary
        self.days = days
        self.skipWeekdays = skipWeekdays
        self.source = source
    }

    /// True when every day carries the date its source gave it, so the plan can follow that calendar.
    public var isDated: Bool { !days.isEmpty && days.allSatisfy { $0.date != nil } }

    public static func decode(_ data: Data) throws -> ReadingPlan {
        try JSONDecoder().decode(ReadingPlan.self, from: data)
    }

    public func encoded() throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(self)
    }
}
