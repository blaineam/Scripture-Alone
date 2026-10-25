import Foundation
import Observation
import PDFKit
import ScriptureAloneCore

/// What to read today in the reader's plan. UI-free, so anything that can start audio — the reader's
/// Listen button, an App Intent, a CarPlay template — asks the same question and gets the same answer.
struct TodaysReading: Equatable {
    let planID: String
    let planTitle: String
    let day: PlanDay
    /// 1-based, for "Day 12 of 365".
    let dayNumber: Int
    let totalDays: Int
    /// An older day that was missed, offered because today has no reading.
    let isCatchUp: Bool
    let isDone: Bool

    var passages: [PlanPassage] { day.passages }
    /// The plan's own writing for the day (Spurgeon's morning and evening), when it has any.
    var devotions: [PlanDevotion] { day.devotions }
    var display: String { day.display }
}

/// The reader's plans: the ones they imported, and where they are in each plan they started.
/// Kept as two small JSON files — a plan holds only references, and progress only day numbers.
@Observable
final class PlanStore {
    static let shared: PlanStore = {
        #if DEBUG
        // A UI test starts with no plans, and leaves nothing behind or asks for notification permission.
        if UITestMode.isOn {
            let store = PlanStore(directory: FileManager.default.temporaryDirectory.appending(path: "plans-ui-\(UUID().uuidString)"))
            store.schedulesReminders = false
            return store
        }
        #endif
        return PlanStore()
    }()

    private(set) var imported: [ReadingPlan] = []
    /// Plans that ship in the app (Spurgeon's *Morning and Evening*), read from the bundle.
    let bundled: [ReadingPlan]
    private(set) var progress: [PlanProgress] = []
    /// The plan Today shows and "start today's reading" plays.
    private(set) var primaryID: String?
    /// Set when a fetch or save failed, for the screen to show once.
    var notice: String?

    /// Off in tests, which must not touch the notification center.
    var schedulesReminders = true
    private let directory: URL
    private let calendar: Calendar

    init(directory: URL? = nil, calendar: Calendar = .current, bundled: [ReadingPlan]? = nil) {
        self.bundled = bundled ?? Self.loadBundled()
        self.directory = directory ?? URL.applicationSupportDirectory.appending(path: "Plans", directoryHint: .isDirectory)
        self.calendar = calendar
        load()
        rollOverAnnualPlans()
    }

    private static func loadBundled() -> [ReadingPlan] {
        guard let url = Bundle.main.url(forResource: "SpurgeonMorningEvening", withExtension: "json") else { return [] }
        return (try? ReadingPlan.decode(Data(contentsOf: url))).map { [$0] } ?? []
    }

    /// The shipped plans offered to this reader. Their text is English, so a device set to another language
    /// isn't offered them (docs/localization.md: English-only commentary is hidden outside English).
    var offeredBundled: [ReadingPlan] {
        guard Locale.preferredLanguages.first?.hasPrefix("en") ?? true else { return [] }
        return bundled.filter { plan in !progress.contains { $0.planID == plan.id } }
    }

    /// An annual plan starts over each January: the year's progress is forgotten on the first open of a new year.
    func rollOverAnnualPlans(now: Date = Date()) {
        var changed = false
        for (i, p) in progress.enumerated() {
            guard let plan = plan(id: p.planID), let next = p.rolledOver(for: plan, now: now, calendar: calendar) else { continue }
            progress[i] = next
            changed = true
        }
        if changed { save() }
    }

    // MARK: Lookup

    func plan(id: String) -> ReadingPlan? {
        imported.first { $0.id == id } ?? bundled.first { $0.id == id } ?? PlanCatalog.plan(id: id)
    }

    func progress(for planID: String) -> PlanProgress? { progress.first { $0.planID == planID } }

    func schedule(for planID: String) -> PlanSchedule? {
        guard let plan = plan(id: planID), let progress = progress(for: planID) else { return nil }
        return PlanSchedule(plan: plan, progress: progress, calendar: calendar)
    }

    /// The plans the reader has started, the primary one first.
    var started: [ReadingPlan] {
        let all = progress.compactMap { plan(id: $0.planID) }
        return all.sorted { ($0.id == primaryID ? 0 : 1) < ($1.id == primaryID ? 0 : 1) }
    }

    /// Today's reading in the primary plan; failing that, in any plan the reader started.
    func todaysReading(now: Date = Date()) -> TodaysReading? {
        let ids = [primaryID].compactMap { $0 } + progress.map(\.planID).filter { $0 != primaryID }
        for id in ids {
            guard let schedule = schedule(for: id), let day = schedule.reading(now: now) else { continue }
            var isToday = false
            if case .reading(let today) = schedule.today(now: now), today.index == day.index { isToday = true }
            return TodaysReading(planID: id, planTitle: schedule.plan.displayTitle, day: day, dayNumber: day.index + 1,
                                 totalDays: schedule.plan.days.count, isCatchUp: !isToday,
                                 isDone: schedule.progress.completed.contains(day.index))
        }
        return nil
    }

    // MARK: Changing progress

    func isoToday(_ now: Date = Date()) -> String { PlanSchedule.isoDay(now, calendar: calendar) }

    /// Starts a plan (or restarts it, forgetting what was done) and makes it the primary one.
    func start(_ plan: ReadingPlan, on start: Date = Date(), followsCalendar: Bool = false) {
        guard !plan.days.isEmpty else { return }
        let existingReminder = progress(for: plan.id)?.reminder
        progress.removeAll { $0.planID == plan.id }
        progress.append(PlanProgress(planID: plan.id, startDate: PlanSchedule.isoDay(start, calendar: calendar),
                                     followsCalendar: followsCalendar && plan.isDated, reminder: existingReminder))
        primaryID = plan.id
        save()
    }

    func stop(_ planID: String) {
        if let source = imported.first(where: { $0.id == planID })?.source { StudyCache.discard(source) }
        progress.removeAll { $0.planID == planID }
        imported.removeAll { $0.id == planID }
        if primaryID == planID { primaryID = progress.first?.planID }
        save()
    }

    func makePrimary(_ planID: String) {
        guard progress(for: planID) != nil else { return }
        primaryID = planID
        save()
    }

    func setDone(_ done: Bool, day: Int, in planID: String) {
        guard let i = progress.firstIndex(where: { $0.planID == planID }) else { return }
        if done { progress[i].completed.insert(day) } else { progress[i].completed.remove(day) }
        save()
    }

    func markDone(_ reading: TodaysReading) { setDone(true, day: reading.day.index, in: reading.planID) }

    func setReminder(_ time: PlanProgress.ReminderTime?, for planID: String) {
        guard let i = progress.firstIndex(where: { $0.planID == planID }) else { return }
        progress[i].reminder = time
        save()
    }

    // MARK: Importing

    /// Keeps an imported plan. Re-importing the same source replaces its days and keeps its progress.
    func add(imported plan: ReadingPlan) {
        imported.removeAll { $0.id == plan.id }
        imported.append(plan)
        save()
    }

    // MARK: Persistence

    private struct Saved: Codable {
        var imported: [ReadingPlan]
        var progress: [PlanProgress]
        var primaryID: String?
    }

    private var file: URL { directory.appending(path: "plans.json") }

    private func load() {
        guard let data = try? Data(contentsOf: file), let saved = try? JSONDecoder().decode(Saved.self, from: data) else { return }
        imported = saved.imported
        progress = saved.progress
        primaryID = saved.primaryID ?? saved.progress.first?.planID
    }

    private func save() {
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let data = try JSONEncoder().encode(Saved(imported: imported, progress: progress, primaryID: primaryID))
            try data.write(to: file, options: .atomic)
        } catch {
            notice = String(localized: "Your plan progress couldn’t be saved.", comment: "Reading plans: shown when saving to disk failed")
        }
        PlanReminders.refresh(self)
    }
}

extension ListenController {
    /// Reads today's passage in the reader's plan aloud. The whole of "play today's reading": no
    /// screen is involved, so a CarPlay audio entry can call it as it stands. False when no plan is
    /// started or the translation can't supply the text.
    @discardableResult
    func playTodaysReading(in model: ReaderModel, store: PlanStore = .shared) -> Bool {
        guard let reading = store.todaysReading() else { return false }
        return playReading(reading.passages, devotions: reading.devotions, in: model)
    }
}

// MARK: Importing from a site

extension PlanStore {
    enum ImportMode {
        /// Follow the dates the site gave its days, from today on.
        case followCalendar
        /// Count the site's days from today, taking posts published on or after `since`.
        case fromDayOne(since: Date)
    }

    /// Reads a plan's schedule from a pasted address (or a known preset's) and starts it. Only
    /// references are kept; the passage text comes from the reader's own translation.
    @discardableResult
    func importPlan(pasted: String, preset: PlanFeedImporter.Preset? = nil, mode: ImportMode,
                    fetcher: any PlanFetching = URLSession.shared, now: Date = Date()) async throws -> PlanFeedImporter.Import {
        let importer = PlanFeedImporter(fetcher: fetcher)
        let after: Date
        var limit = 400
        // The church's own yearly plan, when it publishes one: the whole year's schedule, not a guess.
        var published: [PlanDay] = []
        if case .followCalendar = mode { published = await publishedSchedule(importer, page: preset?.planPage) }
        // A month back, so a weekend or a holiday still finds posts; the plan starts today all the same.
        let recent = calendar.startOfDay(for: now).addingTimeInterval(-30 * 86_400)
        switch mode {
        case .followCalendar:
            // A site posts each day's reading on the day, so nothing is published ahead. It runs the same
            // reading order every few years, though: read that far back to find the last pass and
            // project the days still to come from it (`PlanFeedImporter.projection`).
            if published.isEmpty {
                after = calendar.startOfDay(for: now).addingTimeInterval(-5 * 366 * 86_400)
                limit = 2_500
            } else {
                after = recent
            }
        case .fromDayOne(let since): after = since
        }
        let endpoints: [PlanFeedImporter.Endpoint] = preset.map {
            [.wordPress(site: $0.site, category: $0.scheduleCategory)]
        } ?? PlanFeedImporter.endpoints(for: pasted)
        guard !endpoints.isEmpty else { throw PlanFeedError.badURL }
        var found: (PlanFeedImporter.Endpoint, [FeedEntry])?
        var failure: Error = PlanFeedError.notAFeed
        for endpoint in endpoints {
            do {
                let entries = try await importer.entries(endpoint, after: after, limit: limit)
                if !entries.isEmpty { found = (endpoint, entries); break }
            } catch PlanFeedError.offline {
                throw PlanFeedError.offline
            } catch {
                failure = error
            }
        }
        guard let (endpoint, entries) = found else { throw failure }
        let site: URL
        var apiRoot: URL?
        var category: Int?
        switch endpoint {
        case .wordPress(let url, let id):
            site = url
            apiRoot = PlanFeedImporter.apiRoot(of: url)
            category = id
        case .rss(let url):
            site = url
        }
        let name = preset?.name ?? site.host() ?? site.absoluteString
        let source = PlanSource(siteURL: site, apiRoot: apiRoot, studyCategory: preset?.studyCategory, name: name, scheduleCategory: category)
        let following: Bool
        if case .followCalendar = mode { following = true } else { following = false }
        let result = try PlanFeedImporter.plan(
            from: entries, title: name, source: source,
            keepingFrom: following ? PlanSchedule.isoDay(recent, calendar: calendar) : nil, projecting: following,
            scheduled: published)
        add(imported: result.plan)
        switch mode {
        case .followCalendar: start(result.plan, on: now, followsCalendar: true)
        case .fromDayOne: start(result.plan, on: now, followsCalendar: false)
        }
        return result
    }

    /// The days of the yearly plan a site links as a PDF (empty when it has none, or it can't be read as a
    /// whole year's rows — the projection from earlier passes stands in).
    private func publishedSchedule(_ importer: PlanFeedImporter, page: URL?) async -> [PlanDay] {
        guard let page, let pdf = try? await importer.latestPlanPDF(page: page),
              let text = PDFDocument(data: pdf.data)?.string else { return [] }
        let days = PlanFeedImporter.parseScheduleText(text, year: pdf.year)
        return days.count >= 100 ? days : []
    }

    /// Fetches the days a followed site has published since the plan was imported. Quiet on failure:
    /// the reader keeps the days already on the device, which is all an offline phone needs.
    func refreshImported(fetcher: any PlanFetching = URLSession.shared, now: Date = Date()) async {
        for plan in imported {
            // The days up to the last one the site really posted; whatever follows is a projection.
            let posted = plan.days.filter { !$0.projected }
            guard progress(for: plan.id)?.followsCalendar == true, let source = plan.source, let first = plan.days.first?.date,
                  posted.last?.date.map({ $0 < isoToday(now) }) ?? true,
                  let root = source.apiRoot else { continue }
            let site = root.deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            let parts = first.split(separator: "-").compactMap { Int($0) }
            guard parts.count == 3, let since = calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2])) else { continue }
            let importer = PlanFeedImporter(fetcher: fetcher)
            // A plan made before days could be projected has none: read the site's history once a week
            // to look for the earlier pass (a site with none is not asked again every time the app opens).
            let triedKey = "planProjectionTried." + plan.id
            let lookBack = !plan.days.contains { $0.projected }
                && (UserDefaults.standard.object(forKey: triedKey) as? Date).map { now.timeIntervalSince($0) > 7 * 86_400 } ?? true
            let published = lookBack ? await publishedSchedule(importer, page: PlanFeedImporter.preset(for: source.siteURL)?.planPage) : []
            let from = lookBack && published.isEmpty ? calendar.startOfDay(for: now).addingTimeInterval(-5 * 366 * 86_400) : since
            guard let entries = try? await importer.entries(.wordPress(site: site, category: source.scheduleCategory),
                                                            after: from.addingTimeInterval(-1), limit: lookBack && published.isEmpty ? 2_500 : 400) else { continue }
            if lookBack { UserDefaults.standard.set(now, forKey: triedKey) }
            guard let result = try? PlanFeedImporter.plan(from: entries, title: plan.title, source: source,
                                                          keepingFrom: lookBack ? first : nil, projecting: lookBack, scheduled: published),
                  result.plan.id == plan.id, result.plan.days.filter({ !$0.projected }).count >= posted.count else { continue }
            // What the site had not posted yet when the plan was made carries on from where it now stands.
            let ahead = lookBack ? [] : PlanFeedImporter.projection(after: result.plan.days, pool: plan.days)
            add(imported: ReadingPlan(id: plan.id, kind: plan.kind, title: plan.title, summary: plan.summary,
                                      days: result.plan.days + ahead, skipWeekdays: result.plan.skipWeekdays, source: source))
        }
    }
}

extension PlanStore {
    /// What to tell the reader when an import fails.
    static func message(for error: any Error) -> String {
        switch error as? PlanFeedError {
        case .offline:
            String(localized: "You’re offline. Connect to the internet to import a plan.",
                   comment: "Reading plans import: shown when the request could not reach the site")
        case .noReadings:
            String(localized: "That site has no posts titled with a Bible passage.",
                   comment: "Reading plans import: shown when no post title names a passage")
        case .badURL:
            String(localized: "That doesn’t look like a web address.", comment: "Reading plans import")
        default:
            String(localized: "Couldn’t read a plan from that address.", comment: "Reading plans import: generic failure")
        }
    }
}
