import Foundation
import Testing
import ScriptureAloneCore
import UserNotifications
@testable import Scripture_Alone

private struct Stub: PlanFetching {
    var posts: String
    var offline = false
    func fetch(_ url: URL) async throws -> (Data, HTTPURLResponse) {
        if offline { throw URLError(.notConnectedToInternet) }
        return (Data(posts.utf8), HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: ["X-WP-TotalPages": "1"])!)
    }
}

@MainActor
private func freshStore(_ calendar: Calendar = .current) -> PlanStore {
    let store = PlanStore(directory: FileManager.default.temporaryDirectory.appending(path: "plans-\(UUID().uuidString)"),
                          calendar: calendar)
    store.schedulesReminders = false
    return store
}

@MainActor
struct ReadingPlansTests {
    @Test func startingAPlanGivesTodaysReading() throws {
        let store = freshStore()
        #expect(store.todaysReading() == nil)
        store.start(PlanCatalog.book(.romans))
        let reading = try #require(store.todaysReading())
        #expect(reading.display == "Romans 1")
        #expect(reading.dayNumber == 1 && reading.totalDays == 16 && !reading.isDone && !reading.isCatchUp)
        store.markDone(reading)
        #expect(store.todaysReading()?.isDone == true)
    }

    @Test func progressSurvivesARelaunch() throws {
        let dir = FileManager.default.temporaryDirectory.appending(path: "plans-\(UUID().uuidString)")
        let first = PlanStore(directory: dir)
        first.schedulesReminders = false
        first.start(PlanCatalog.canonical)
        first.setDone(true, day: 0, in: PlanCatalog.canonical.id)
        first.setReminder(.init(hour: 7, minute: 30), for: PlanCatalog.canonical.id)
        let second = PlanStore(directory: dir)
        #expect(second.primaryID == PlanCatalog.canonical.id)
        #expect(second.progress(for: PlanCatalog.canonical.id)?.completed == [0])
        #expect(second.progress(for: PlanCatalog.canonical.id)?.reminder == .init(hour: 7, minute: 30))
    }

    @Test func aPlanStartedLastWeekOffersCatchUp() throws {
        let store = freshStore()
        let weekAgo = Date().addingTimeInterval(-3 * 86_400)
        store.start(PlanCatalog.book(.psalms), on: weekAgo)
        let schedule = try #require(store.schedule(for: PlanCatalog.book(.psalms).id))
        #expect(schedule.missedDays().count == 3)
        #expect(store.todaysReading()?.dayNumber == 4)
    }

    @Test func stoppingAPlanForgetsIt() {
        let store = freshStore()
        store.start(PlanCatalog.book(.jude))
        store.start(PlanCatalog.book(.ruth))
        #expect(store.primaryID == PlanCatalog.book(.ruth).id)
        store.stop(PlanCatalog.book(.ruth).id)
        #expect(store.primaryID == PlanCatalog.book(.jude).id)
        store.stop(PlanCatalog.book(.jude).id)
        #expect(store.todaysReading() == nil && store.started.isEmpty)
    }

    private func posts(for date: Date) -> String {
        let today = PlanSchedule.isoDay(date)
        let tomorrow = PlanSchedule.isoDay(date.addingTimeInterval(86_400))
        return """
        [{"id":1,"date":"\(today)T00:00:00","link":"https://s.org/a/","title":{"rendered":"Epistle- 1 Corinthians 5"}},
         {"id":2,"date":"\(tomorrow)T00:00:00","link":"https://s.org/b/","title":{"rendered":"Epistle- 1 Corinthians 6"}}]
        """
    }

    @Test func importFollowsTheSitesCalendar() async throws {
        let store = freshStore()
        let result = try await store.importPlan(pasted: "https://s.org/bible", mode: .followCalendar,
                                                fetcher: Stub(posts: posts(for: Date())))
        #expect(result.plan.days.count == 2)
        let reading = try #require(store.todaysReading())
        #expect(reading.display == "1 Corinthians 5" && reading.day.label == "Epistle")
        #expect(store.imported.count == 1 && store.progress(for: result.plan.id)?.followsCalendar == true)
    }

    @Test func importOfflineLeavesNothingBehind() async {
        let store = freshStore()
        await #expect(throws: PlanFeedError.offline) {
            try await store.importPlan(pasted: "https://s.org/bible", mode: .followCalendar, fetcher: Stub(posts: "", offline: true))
        }
        #expect(store.imported.isEmpty && store.progress.isEmpty)
    }

    @Test func refreshAddsNewDaysAndIgnoresAFailure() async throws {
        let store = freshStore()
        let day = Date()
        let result = try await store.importPlan(pasted: "https://s.org/bible", mode: .followCalendar, fetcher: Stub(posts: posts(for: day)))
        let tomorrow = day.addingTimeInterval(86_400)
        await store.refreshImported(fetcher: Stub(posts: "", offline: true), now: tomorrow)
        #expect(store.plan(id: result.plan.id)?.days.count == 2)
        let later = """
        [{"id":1,"date":"\(PlanSchedule.isoDay(day))T00:00:00","title":{"rendered":"Epistle- 1 Corinthians 5"}},
         {"id":2,"date":"\(PlanSchedule.isoDay(tomorrow))T00:00:00","title":{"rendered":"Epistle- 1 Corinthians 6"}},
         {"id":3,"date":"\(PlanSchedule.isoDay(tomorrow.addingTimeInterval(86_400)))T00:00:00","title":{"rendered":"Epistle- 1 Corinthians 7"}}]
        """
        await store.refreshImported(fetcher: Stub(posts: later), now: tomorrow.addingTimeInterval(86_400))
        #expect(store.plan(id: result.plan.id)?.days.count == 3)
        #expect(store.progress.count == 1, "refreshing keeps the reader's progress")
    }

    @Test func remindersCoverTheComingDaysAndSkipReadOnes() throws {
        let store = freshStore()
        store.start(PlanCatalog.book(.psalms))
        let id = PlanCatalog.book(.psalms).id
        store.setReminder(.init(hour: 8, minute: 0), for: id)
        let midnight = Calendar.current.startOfDay(for: Date())
        let early = PlanReminders.requests(for: store, now: midnight)
        #expect(early.count == 14)
        #expect(early.allSatisfy { $0.identifier.hasPrefix("plan.\(id).") })
        store.setDone(true, day: 0, in: id)
        #expect(PlanReminders.requests(for: store, now: midnight).count == 13)
        // After the reminder time today's has passed.
        let noon = Calendar.current.date(byAdding: .hour, value: 12, to: midnight)!
        #expect(PlanReminders.requests(for: store, now: noon).count == 13)
        let first = try #require(PlanReminders.requests(for: store, now: midnight).first)
        #expect(first.content.body.contains("Psalms 2"))
    }

    @Test func theIntentNeedsAPlan() async throws {
        for id in PlanStore.shared.progress.map(\.planID) { PlanStore.shared.stop(id) }
        await #expect(throws: ScriptureIntentError.self) { _ = try await StartTodaysReadingIntent().perform() }
        PlanStore.shared.schedulesReminders = false
        PlanStore.shared.start(PlanCatalog.book(.romans))
        defer { PlanStore.shared.stop(PlanCatalog.book(.romans).id) }
        _ = AppCommandCenter.shared.take()
        _ = try await StartTodaysReadingIntent().perform()
        #expect(AppCommandCenter.shared.take() == [.playTodaysReading])
    }
}

#if os(iOS)
@MainActor
@Suite(.serialized)
struct PlanListenTests {
    let model = ViewHost.readerModel()
    let listen = ListenController.shared

    init() {
        listen.stop()
        listen.speaksSilentlyForTesting = true
    }

    func finish() {
        listen.stop()
        listen.speaksSilentlyForTesting = false
    }

    @Test func aPassageIsReadVerseByVerseThenStops() {
        defer { finish() }
        #expect(listen.playReading([PlanPassage(Passage(book: .john, startChapter: 3, startVerse: 16, endChapter: 3, endVerse: 18))], in: model))
        #expect(listen.isListening(in: model))
        #expect(listen.speakingVerse(in: model) == VerseRef(.john, 3, 16).key)
    }

    @Test func aWholeChapterStartsWithItsAnnouncement() {
        defer { finish() }
        #expect(listen.playReading([PlanPassage(book: .jude, chapters: 1...1), PlanPassage(book: .ruth, chapters: 1...2)], in: model))
        #expect(listen.isListening(in: model))
    }

    @Test func todaysReadingPlaysFromThePlan() {
        defer { finish() }
        let store = freshStore()
        #expect(!listen.playTodaysReading(in: model, store: store), "nothing to play without a plan")
        store.start(PlanCatalog.book(.john))
        #expect(listen.playTodaysReading(in: model, store: store))
        #expect(listen.isPlaying)
    }

    @Test func nothingToReadIsReportedNotStarted() {
        defer { finish() }
        #expect(!listen.playReading([], in: model))
        #expect(!listen.isListening(in: model))
    }
}
#endif

#if os(iOS)
@MainActor
@Suite(.serialized)
struct PlanProseListenTests {
    let model = ViewHost.readerModel()
    let listen = ListenController.shared

    @Test func aStudyIsReadAloudParagraphByParagraphUnderItsOwnTitle() {
        listen.stop()
        listen.speaksSilentlyForTesting = true
        defer { listen.stop(); listen.speaksSilentlyForTesting = false }
        #expect(listen.playProse("First.\n\nSecond.", title: "Saturday Study", in: model))
        #expect(listen.isListening(in: model))
        #expect(listen.nowPlayingTitle == "Saturday Study")
        #expect(!listen.playProse("  \n ", title: "x", in: model))
        listen.stop()
        #expect(listen.playReading([PlanPassage(book: .jude, chapters: 1...1)], in: model))
        #expect(listen.nowPlayingTitle != "Saturday Study", "a verse session drops the prose title")
    }
}
#endif

@MainActor
struct StudyCacheTests {
    @Test func theLastStudyIsKeptPerSourceAndDiscardedWithIt() {
        StudyCache.directory = FileManager.default.temporaryDirectory.appending(path: "studies-\(UUID().uuidString)")
        let a = PlanSource(siteURL: URL(string: "https://a.org/bible")!, apiRoot: nil, studyCategory: 7, name: "A")
        let b = PlanSource(siteURL: URL(string: "https://b.org")!, apiRoot: nil, studyCategory: 7, name: "B")
        #expect(StudyCache.load(for: a) == nil)
        let post = StudyPost(title: "Saturday Study", date: "2026-10-17", link: URL(string: "https://a.org/s/"), text: "Text")
        StudyCache.save(post, for: a)
        #expect(StudyCache.load(for: a) == post && StudyCache.load(for: b) == nil)
        StudyCache.discard(a)
        #expect(StudyCache.load(for: a) == nil)
    }
}

@MainActor
struct BundledPlanTests {
    @Test func spurgeonShipsInTheAppAndPlaysItsDevotion() throws {
        let plan = try #require(PlanStore().bundled.first, "the app bundle carries SpurgeonMorningEvening.json")
        #expect(plan.annual && plan.days.count == 366)
        let store = PlanStore(directory: FileManager.default.temporaryDirectory.appending(path: "plans-\(UUID().uuidString)"), bundled: [plan])
        store.schedulesReminders = false
        #expect(store.plan(id: plan.id) == plan)
        store.start(plan)
        let reading = try #require(store.todaysReading())
        #expect(reading.devotions.count == 2 && reading.passages.count == 2 && !reading.isCatchUp)
        #expect(store.offeredBundled.isEmpty, "a plan being followed isn't offered again")
    }
}

#if os(iOS)
@MainActor
@Suite(.serialized)
struct DevotionListenTests {
    @Test func aDevotionIsReadAsHeadingVerseAndMeditation() throws {
        let listen = ListenController.shared
        listen.stop()
        listen.speaksSilentlyForTesting = true
        defer { listen.stop(); listen.speaksSilentlyForTesting = false }
        let model = ViewHost.readerModel()
        let plan = try #require(PlanStore().bundled.first)
        let day = plan.days[0]
        #expect(listen.playReading(day.passages, devotions: day.devotions, in: model))
        #expect(listen.isListening(in: model))
    }
}
#endif
