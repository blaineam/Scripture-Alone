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

/// A short piece of a devotional plan's own writing for a day: "Morning", the verse it opens with, and the
/// meditation (paragraphs separated by a blank line). Shipped plans only; an imported plan has none.
public struct PlanDevotion: Codable, Hashable, Sendable {
    public let heading: String
    public let quote: String?
    public let text: String

    public init(heading: String, quote: String? = nil, text: String) {
        self.heading = heading
        self.quote = quote
        self.text = text
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
    /// The plan's own writing for the day, when it has any (a devotional plan).
    public let devotions: [PlanDevotion]

    public var id: Int { index }

    public init(index: Int, date: String? = nil, label: String? = nil, passages: [PlanPassage], devotions: [PlanDevotion] = []) {
        self.index = index
        self.date = date
        self.label = label
        self.passages = passages
        self.devotions = devotions
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        index = try c.decode(Int.self, forKey: .index)
        date = try c.decodeIfPresent(String.self, forKey: .date)
        label = try c.decodeIfPresent(String.self, forKey: .label)
        passages = try c.decode([PlanPassage].self, forKey: .passages)
        devotions = try c.decodeIfPresent([PlanDevotion].self, forKey: .devotions) ?? []
    }

    public var display: String { passages.map(\.display).joined(separator: "; ") }
}

public enum PlanKind: String, Codable, Sendable {
    case canonical, chronological, book, imported
    /// A plan that ships its own writing (`PlanDay.devotions`), like Spurgeon's *Morning and Evening*.
    case devotional
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
    /// Days are a month and a day (`PlanDay.date` is "MM-DD"), not a count from a start date: today's reading
    /// is the one for today's date, and the plan starts over each January. 366 days, so February 29 has its own.
    public let annual: Bool

    public init(id: String, kind: PlanKind, title: String, summary: String, days: [PlanDay],
                skipWeekdays: Set<Int> = [], source: PlanSource? = nil, annual: Bool = false) {
        self.id = id
        self.kind = kind
        self.title = title
        self.summary = summary
        self.days = days
        self.skipWeekdays = skipWeekdays
        self.source = source
        self.annual = annual
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        kind = try c.decode(PlanKind.self, forKey: .kind)
        title = try c.decode(String.self, forKey: .title)
        summary = try c.decode(String.self, forKey: .summary)
        days = try c.decode([PlanDay].self, forKey: .days)
        skipWeekdays = try c.decodeIfPresent(Set<Int>.self, forKey: .skipWeekdays) ?? []
        source = try c.decodeIfPresent(PlanSource.self, forKey: .source)
        annual = try c.decodeIfPresent(Bool.self, forKey: .annual) ?? false
    }

    /// True when every day carries the date its source gave it, so the plan can follow that calendar.
    public var isDated: Bool { !annual && !days.isEmpty && days.allSatisfy { $0.date != nil } }

    public static func decode(_ data: Data) throws -> ReadingPlan {
        try JSONDecoder().decode(ReadingPlan.self, from: data)
    }

    public func encoded() throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(self)
    }
}
