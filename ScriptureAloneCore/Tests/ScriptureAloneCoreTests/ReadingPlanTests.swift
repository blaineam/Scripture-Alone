import Foundation
import Testing
@testable import ScriptureAloneCore

private func utc() -> Calendar {
    var c = Calendar(identifier: .gregorian)
    c.timeZone = TimeZone(identifier: "UTC")!
    return c
}

private func day(_ iso: String) -> Date {
    let p = iso.split(separator: "-").compactMap { Int($0) }
    return utc().date(from: DateComponents(year: p[0], month: p[1], day: p[2]))!
}

@Suite struct PlanCatalogTests {
    @Test(arguments: [PlanCatalog.canonical, PlanCatalog.chronological])
    func coversEveryChapterExactlyOnce(plan: ReadingPlan) {
        let chapters = plan.days.flatMap { $0.passages.flatMap(\.chapters) }
        #expect(plan.days.count == 365)
        #expect(chapters.count == 1189)
        #expect(Set(chapters).count == 1189)
        #expect(plan.days.allSatisfy { !$0.passages.isEmpty })
    }

    @Test func canonicalRunsInOrder() {
        let chapters = PlanCatalog.canonical.days.flatMap { $0.passages.flatMap(\.chapters) }
        #expect(chapters == chapters.sorted())
        #expect(PlanCatalog.canonical.days[0].passages.first?.display == "Genesis 1–3")
        #expect(PlanCatalog.canonical.days.last?.passages.last?.book == .revelation)
    }

    @Test func chronologicalPutsJobEarly() {
        let order = PlanCatalog.chronological.days.flatMap { $0.passages.map(\.book) }
        #expect(order.firstIndex(of: .job)! < order.firstIndex(of: .exodus)!)
        #expect(order.firstIndex(of: .daniel)! < order.firstIndex(of: .matthew)!)
    }

    @Test func daySplitsAtBookBoundary() {
        let crossing = PlanCatalog.canonical.days.first { $0.passages.count > 1 }
        #expect(crossing != nil)
    }

    @Test func bookPlanIsOneChapterADay() throws {
        let plan = try #require(PlanCatalog.plan(id: PlanCatalog.book(.romans).id))
        #expect(plan.days.count == 16)
        #expect(plan.days[7].display == "Romans 8")
        let single = PlanCatalog.book(.jude)
        #expect(single.days.count == 1)
        #expect(PlanCatalog.plan(id: "nope") == nil)
    }

    @Test func planRoundTripsThroughJSON() throws {
        let plan = PlanCatalog.book(.psalms)
        #expect(try ReadingPlan.decode(plan.encoded()) == plan)
    }
}

@Suite struct PlanScheduleTests {
    private func schedule(_ plan: ReadingPlan = PlanCatalog.book(.romans), start: String = "2026-10-05",
                           done: Set<Int> = [], follows: Bool = false) -> PlanSchedule {
        PlanSchedule(plan: plan, progress: PlanProgress(planID: plan.id, startDate: start, followsCalendar: follows, completed: done),
                     calendar: utc())
    }

    @Test func ordinalDaysFollowTheCalendar() {
        let s = schedule()
        #expect(s.today(now: day("2026-10-05")) == .reading(PlanCatalog.book(.romans).days[0]))
        #expect(s.dayIndex(on: day("2026-10-08")) == 3)
        #expect(s.dayIndex(on: day("2026-10-04")) == nil)
        #expect(s.today(now: day("2026-10-01")) == .notStarted(starts: "2026-10-05"))
        #expect(s.today(now: day("2027-01-01")) == .finished)
    }

    @Test func skippedWeekdaysAreRestDays() {
        let plan = ReadingPlan(id: "w", kind: .imported, title: "W", summary: "",
                               days: (0..<10).map { PlanDay(index: $0, passages: [PlanPassage(book: .psalms, chapters: ($0 + 1)...($0 + 1))]) },
                               skipWeekdays: [1, 7])
        let s = schedule(plan, start: "2026-10-05") // a Monday
        #expect(s.dayIndex(on: day("2026-10-09")) == 4)  // Friday
        #expect(s.dayIndex(on: day("2026-10-10")) == nil) // Saturday
        #expect(s.dayIndex(on: day("2026-10-12")) == 5)   // next Monday
        if case .rest(let next) = s.today(now: day("2026-10-10")) { #expect(next?.index == 5) } else { Issue.record("expected rest") }
        #expect(utc().component(.weekday, from: s.date(forDay: 5)!) == 2)
    }

    @Test func missedDaysAndCatchUp() {
        let s = schedule(done: [0, 2])
        #expect(s.missedDays(now: day("2026-10-09")).map(\.index) == [1, 3])
        #expect(s.reading(now: day("2026-10-09"))?.index == 4)
        #expect(schedule(done: [0, 1, 2, 3, 4]).missedDays(now: day("2026-10-09")).isEmpty)
    }

    @Test func streakSurvivesAnOpenToday() {
        #expect(schedule(done: [0, 1, 2]).streak(now: day("2026-10-08")) == 3)  // today (3) still open
        #expect(schedule(done: [0, 1, 2, 3]).streak(now: day("2026-10-08")) == 4)
        #expect(schedule(done: [0, 1, 3]).streak(now: day("2026-10-08")) == 1)  // day 2 missed, 3 read
        #expect(schedule(done: [0, 1]).streak(now: day("2026-10-09")) == 0)     // yesterday (3) missed
        #expect(schedule().streak(now: day("2026-10-01")) == 0)
    }

    @Test func progressFraction() {
        #expect(schedule(done: [0, 1, 99]).completedCount == 2)
        #expect(abs(schedule(done: [0, 1, 2, 3]).fraction - 0.25) < 0.0001)
    }

    @Test func calendarModeUsesSourceDates() {
        let dated = ReadingPlan(id: "c", kind: .imported, title: "C", summary: "", days: [
            PlanDay(index: 0, date: "2026-10-19", passages: [PlanPassage(book: .firstCorinthians, chapters: 1...1)]),
            PlanDay(index: 1, date: "2026-10-20", passages: [PlanPassage(book: .firstCorinthians, chapters: 2...2)]),
            PlanDay(index: 2, date: "2026-10-22", passages: [PlanPassage(book: .firstCorinthians, chapters: 3...3)]),
        ])
        let s = schedule(dated, start: "2026-10-19", follows: true)
        #expect(s.dayIndex(on: day("2026-10-22")) == 2)
        #expect(s.dayIndex(on: day("2026-10-21")) == nil)
        #expect(s.missedDays(now: day("2026-10-22")).map(\.index) == [0, 1])
        if case .rest(let next) = s.today(now: day("2026-10-21")) { #expect(next?.index == 2) } else { Issue.record("expected rest") }
        // A plan with no dates cannot follow a calendar it doesn't have.
        #expect(schedule(follows: true).dayIndex(on: day("2026-10-06")) == 1)
    }

    @Test func emptyPlanIsFinished() {
        let empty = ReadingPlan(id: "e", kind: .book, title: "E", summary: "", days: [])
        #expect(schedule(empty).today(now: day("2026-10-05")) == .finished)
        #expect(schedule(empty).reading(now: day("2026-10-05")) == nil)
    }
}

private struct Table: PlanFetching {
    var responses: [String: (Int, [String: String], String)]
    var offline = false
    func fetch(_ url: URL) async throws -> (Data, HTTPURLResponse) {
        if offline { throw URLError(.notConnectedToInternet) }
        let key = url.absoluteString
        guard let hit = responses.first(where: { key.hasPrefix($0.key) })?.value else {
            return (Data(), HTTPURLResponse(url: url, statusCode: 404, httpVersion: nil, headerFields: nil)!)
        }
        return (Data(hit.2.utf8), HTTPURLResponse(url: url, statusCode: hit.0, httpVersion: nil, headerFields: hit.1)!)
    }
}

@Suite struct PlanFeedImporterTests {
    @Test(arguments: [
        ("Epistle- 1 Corinthians 5", "Epistle", "1 Corinthians 5"),
        ("Exile Era- Daniel 12", "Exile Era", "Daniel 12"),
        ("Psalms 23-24", nil, "Psalms 23–24"),
        ("Day 4: John 3:1-21", "Day 4", "John 3:1–21"),
        ("Gospel &#8211; Luke 2", "Gospel", "Luke 2"),
        ("Wisdom- Psalms 1; Proverbs 1", "Wisdom", "Psalms 1"),
    ] as [(String, String?, String)])
    func parsesTitles(title: String, label: String?, first: String) throws {
        let parsed = try #require(PlanFeedImporter.parseTitle(title))
        #expect(parsed.label == label)
        #expect(parsed.passages.first?.display == first)
    }

    @Test(arguments: ["About The Plan", "Olive Drive Church", "Download Plan", ""])
    func skipsTitlesWithoutReferences(title: String) {
        #expect(PlanFeedImporter.parseTitle(title) == nil)
    }

    @Test func pastedAddresses() {
        #expect(PlanFeedImporter.endpoints(for: "discipleschurch.com/bible") == [
            .wordPress(site: URL(string: "https://discipleschurch.com/bible")!, category: nil),
            .rss(URL(string: "https://discipleschurch.com/bible/feed/")!)])
        #expect(PlanFeedImporter.endpoints(for: "https://x.org/bible/wp-json/wp/v2/posts?categories=2&per_page=5") == [
            .wordPress(site: URL(string: "https://x.org/bible")!, category: 2)])
        #expect(PlanFeedImporter.endpoints(for: "https://x.org/bible/feed/") == [.rss(URL(string: "https://x.org/bible/feed/")!)])
        #expect(PlanFeedImporter.endpoints(for: "  ").isEmpty)
        #expect(PlanFeedImporter.endpoints(for: "not a url").isEmpty)
        #expect(PlanFeedImporter.endpoints(for: "ftp://x.org/a").isEmpty)
    }

    private let posts = """
    [{"id":1,"date":"2026-10-19T00:00:00","link":"https://s.org/a/","title":{"rendered":"Epistle- 1 Corinthians 1"}},
     {"id":2,"date":"2026-10-20T00:00:00","link":"https://s.org/b/","title":{"rendered":"Epistle- 1 Corinthians 2"}},
     {"id":3,"date":"2026-10-20T00:00:30","link":"https://s.org/b2/","title":{"rendered":"Epistle- 1 Corinthians 2"}},
     {"id":4,"date":"2026-10-21T00:00:00","link":"https://s.org/c/","title":{"rendered":"About The Plan"}},
     {"id":5,"date":"2026-10-22T00:00:00","link":"https://s.org/d/","title":{"rendered":"Epistle- 1 Corinthians 3"}}]
    """

    @Test func importsScheduleFromWordPress() async throws {
        let table = Table(responses: ["https://s.org/bible/wp-json/wp/v2/posts": (200, ["X-WP-TotalPages": "1"], posts)])
        let importer = PlanFeedImporter(fetcher: table)
        let entries = try await importer.entries(.wordPress(site: URL(string: "https://s.org/bible")!, category: 2))
        #expect(entries.count == 5)
        let source = PlanSource(siteURL: URL(string: "https://s.org/bible")!, apiRoot: nil, studyCategory: nil, name: "S")
        let result = try PlanFeedImporter.plan(from: entries, title: "S Plan", source: source)
        #expect(result.plan.days.map(\.display) == ["1 Corinthians 1", "1 Corinthians 2", "1 Corinthians 3"])
        #expect(result.plan.days.map(\.date) == ["2026-10-19", "2026-10-20", "2026-10-22"])
        #expect(result.plan.days.first?.label == "Epistle")
        #expect(result.skipped == ["About The Plan"])
        #expect(result.plan.isDated && result.plan.kind == .imported)
        // The same site and title import to the same plan id, so a re-import replaces rather than duplicates.
        #expect(try PlanFeedImporter.plan(from: entries, title: "S Plan", source: source).plan.id == result.plan.id)
    }

    @Test func derivesRestWeekdays() throws {
        var entries: [FeedEntry] = []
        let cal = utc()
        for i in 0..<28 {
            let d = cal.date(byAdding: .day, value: i, to: day("2026-10-05"))!
            let wd = cal.component(.weekday, from: d)
            guard wd != 1, wd != 7 else { continue }
            entries.append(FeedEntry(title: "Psalms \(entries.count % 100 + 1)", date: PlanSchedule.isoDay(d, calendar: cal), link: nil))
        }
        let source = PlanSource(siteURL: URL(string: "https://s.org")!, apiRoot: nil, studyCategory: nil, name: "S")
        #expect(try PlanFeedImporter.plan(from: entries, title: "P", source: source).plan.skipWeekdays == [1, 7])
    }

    @Test func pagesThroughLongFeeds() async throws {
        let page = "[{\"id\":1,\"date\":\"2026-01-01T00:00:00\",\"title\":{\"rendered\":\"Psalms 1\"}}]"
        let table = Table(responses: ["https://s.org/wp-json/wp/v2/posts": (200, ["X-WP-TotalPages": "3"], page)])
        let entries = try await PlanFeedImporter(fetcher: table).entries(.wordPress(site: URL(string: "https://s.org")!, category: nil))
        #expect(entries.count == 3)
        let limited = try await PlanFeedImporter(fetcher: table).entries(.wordPress(site: URL(string: "https://s.org")!, category: nil), limit: 1)
        #expect(limited.count == 1)
    }

    @Test func noReadingsIsAnError() {
        let source = PlanSource(siteURL: URL(string: "https://s.org")!, apiRoot: nil, studyCategory: nil, name: "S")
        #expect(throws: PlanFeedError.noReadings) {
            try PlanFeedImporter.plan(from: [FeedEntry(title: "Hello", date: "2026-01-01", link: nil)], title: "t", source: source)
        }
    }

    @Test func offlineAndServerFailuresAreDistinct() async {
        let site = URL(string: "https://s.org")!
        await #expect(throws: PlanFeedError.offline) {
            try await PlanFeedImporter(fetcher: Table(responses: [:], offline: true)).entries(.wordPress(site: site, category: nil))
        }
        await #expect(throws: PlanFeedError.badResponse(404)) {
            try await PlanFeedImporter(fetcher: Table(responses: [:])).entries(.wordPress(site: site, category: nil))
        }
        await #expect(throws: PlanFeedError.offline) {
            try await PlanFeedImporter(fetcher: Table(responses: [:], offline: true)).entries(forPasted: "s.org")
        }
    }

    @Test func pastedSiteFallsBackToRSS() async throws {
        let rss = """
        <?xml version="1.0"?><rss version="2.0"><channel><title>X</title>
        <item><title>Epistle- 1 Corinthians 5</title><link>https://s.org/a/</link><pubDate>Fri, 23 Oct 2026 07:00:00 +0000</pubDate></item>
        <item><title><![CDATA[Epistle- 1 Corinthians 4]]></title><link>https://s.org/b/</link><pubDate>Thu, 22 Oct 2026 07:00:00 +0000</pubDate></item>
        </channel></rss>
        """
        let table = Table(responses: ["https://s.org/feed/": (200, [:], rss)])
        let (endpoint, entries) = try await PlanFeedImporter(fetcher: table).entries(forPasted: "https://s.org")
        #expect(endpoint == .rss(URL(string: "https://s.org/feed/")!))
        #expect(entries.map(\.date) == ["2026-10-22", "2026-10-23"])
        #expect(entries.map(\.title) == ["Epistle- 1 Corinthians 4", "Epistle- 1 Corinthians 5"])
    }

    @Test func listsCategories() async throws {
        let body = "[{\"id\":7,\"name\":\"Saturday Study\",\"count\":546},{\"id\":2,\"name\":\"Scripture\",\"count\":3700}]"
        let table = Table(responses: ["https://s.org/wp-json/wp/v2/categories": (200, [:], body)])
        let found = try await PlanFeedImporter(fetcher: table).categories(site: URL(string: "https://s.org")!)
        #expect(found.map(\.id) == [2, 7])
    }

    @Test func fetchesTheLatestStudyAsPlainText() async throws {
        let body = "[{\"id\":9,\"date\":\"2026-10-17T00:01:00\",\"link\":\"https://s.org/bible/study/\",\"title\":{\"rendered\":\"Saturday Study &#8211; Daniel 8-12\"},\"content\":{\"rendered\":\"<p>Daniel 8-12</p>\\n<p>It&#8217;s <em>here</em>.</p><script>x()</script>\"}}]"
        let table = Table(responses: ["https://s.org/bible/wp-json/wp/v2/posts": (200, [:], body)])
        let site = URL(string: "https://s.org/bible")!
        let source = PlanSource(siteURL: site, apiRoot: PlanFeedImporter.apiRoot(of: site), studyCategory: 7, name: "S")
        let study = try #require(try await PlanFeedImporter(fetcher: table).latestStudy(source: source, onOrBefore: "2026-10-19"))
        #expect(study.title == "Saturday Study – Daniel 8-12")
        #expect(study.text == "Daniel 8-12\n\nIt’s here.")
        #expect(study.link?.absoluteString == "https://s.org/bible/study/")
        let none = PlanSource(siteURL: site, apiRoot: nil, studyCategory: nil, name: "S")
        #expect(try await PlanFeedImporter(fetcher: table).latestStudy(source: none, onOrBefore: "2026-10-19") == nil)
    }

    @Test func decodesEntities() {
        #expect(PlanFeedImporter.decodeEntities("a &amp; b &#8211; c &#x2019; &bogus; &") == "a & b – c ’ &bogus; &")
    }
}
