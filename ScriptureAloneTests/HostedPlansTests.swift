import SwiftUI
import SwiftData
import Testing
import ScriptureAloneCore
@testable import Scripture_Alone

/// The reading-plans screens drawn for real: today's reading, the plan list, a plan's detail, the
/// start / book-picker / import sheets. They read `PlanStore.shared`, so each test starts a bundled
/// plan there and stops it again — reminders off, so nothing asks for notification permission.
@MainActor
@Suite(.serialized)
struct HostedPlansTests {
    let container: ModelContainer
    let model: ReaderModel
    let store = PlanStore.shared

    init() throws {
        container = try ViewHost.container()
        model = ViewHost.readerModel()
    }

    func draw<V: View>(_ view: V, dark: Bool = false) async -> HostedView {
        await ViewHost.render(ViewHost.inReader(view, model: model, container: container), dark: dark)
    }

    /// Runs `body` with a bundled plan started, then puts the store back as it was.
    func withStartedPlan(_ body: (ReadingPlan) async -> Void) async throws {
        let plan = try #require(store.offeredBundled.first)
        let reminders = store.schedulesReminders
        store.schedulesReminders = false
        store.start(plan)
        await body(plan)
        store.stop(plan.id)
        store.schedulesReminders = reminders
    }

    @Test func thePlanListDrawsEmptyAndWithAStartedPlan() async throws {
        #expect(await draw(PlansView(onOpen: { _ in }, onListen: {})).isDrawn)
        try await withStartedPlan { _ in
            #expect(await draw(PlansView(onOpen: { _ in }, onListen: {})).isDrawn)
            #expect(await draw(PlansView(onOpen: { _ in }, onListen: {}), dark: true).isDrawn)
        }
    }

    @Test func aPlanSDetailAndItsDevotionDraw() async throws {
        try await withStartedPlan { plan in
            #expect(await draw(PlanDetailView(planID: plan.id, onOpen: { _ in })).isDrawn)
            if let reading = store.todaysReading() {
                #expect(await draw(DevotionView(reading: reading, onListen: {})).isDrawn)
            }
        }
    }

    @Test func theStartAndPickerSheetsDraw() async throws {
        let plan = try #require(store.offeredBundled.first)
        #expect(await draw(StartPlanView(plan: plan, finished: {})).isDrawn)
        #expect(await draw(BookPlanPicker(finished: {})).isDrawn)
        #expect(await draw(ImportPlanView(finished: {})).isDrawn)
        #expect(await draw(SpokenText(text: "In the beginning", spoken: "In the beginning, God")).isDrawn)
    }
}
