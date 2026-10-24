import SwiftUI
import ScriptureAloneCore
import UserNotifications

/// Reading plans: today's reading, the plans the reader has started, and a way to add one.
struct PlansView: View {
    /// Open a passage in the reader (the sheet closes first).
    var onOpen: (Passage) -> Void
    /// Read today's reading aloud (the sheet closes first).
    var onListen: () -> Void

    @Environment(\.dismiss) private var dismiss
    private let store = PlanStore.shared

    var body: some View {
        NavigationStack {
            List {
                if let reading = store.todaysReading() {
                    TodaySection(reading: reading, onOpen: onOpen, onListen: onListen)
                } else if let id = store.primaryID, let schedule = store.schedule(for: id) {
                    RestSection(schedule: schedule)
                }
                if !store.started.isEmpty {
                    Section("Your Plans") {
                        ForEach(store.started) { plan in
                            NavigationLink(value: plan.id) { PlanRow(plan: plan, isPrimary: plan.id == store.primaryID) }
                        }
                        .onDelete { offsets in
                            let plans = store.started
                            for i in offsets { store.stop(plans[i].id) }
                        }
                    }
                }
                Section("Add a Plan") {
                    ForEach(PlanCatalog.builtIn) { plan in
                        NavigationLink(value: PlanRoute.start(plan.id)) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(plan.title)
                                Text(plan.summary).font(.footnote).foregroundStyle(.secondary)
                            }
                        }
                    }
                    NavigationLink(value: PlanRoute.books) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Book by Book")
                            Text("Pick a book and read a chapter a day.").font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                    .accessibilityIdentifier("plans.add.books")
                    NavigationLink(value: PlanRoute.importer) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Import from a Church or Website")
                            Text("Follow a plan a church publishes. Only the schedule is imported; you read it in your own translation.")
                                .font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                    .accessibilityIdentifier("plans.add.import")
                }
            }
            .navigationTitle("Reading Plans")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
            .navigationDestination(for: String.self) { id in
                PlanDetailView(planID: id, onOpen: onOpen)
            }
            .navigationDestination(for: PlanRoute.self) { route in
                switch route {
                case .start(let id): if let plan = PlanCatalog.plan(id: id) { StartPlanView(plan: plan, finished: dismissToRoot) }
                case .books: BookPlanPicker(finished: dismissToRoot)
                case .importer: ImportPlanView(finished: dismissToRoot)
                }
            }
            .task { await store.refreshImported() }
        }
        .accessibilityIdentifier("plans.sheet")
    }

    /// A plan was just started: Today is what the reader wants to see.
    private func dismissToRoot() { dismiss() }
}

enum PlanRoute: Hashable {
    case start(String)
    case books
    case importer
}

private struct PlanRow: View {
    let plan: ReadingPlan
    let isPrimary: Bool
    var body: some View {
        let schedule = PlanStore.shared.schedule(for: plan.id)
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(plan.title)
                if isPrimary { Image(systemName: "star.fill").font(.caption).foregroundStyle(.tint).accessibilityLabel("Primary plan") }
            }
            if let schedule {
                ProgressView(value: schedule.fraction)
                Text("\(schedule.completedCount) of \(plan.days.count) days")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
    }
}

// MARK: Today

private struct TodaySection: View {
    let reading: TodaysReading
    var onOpen: (Passage) -> Void
    var onListen: () -> Void
    @Environment(ReaderModel.self) private var model
    private let store = PlanStore.shared

    var body: some View {
        let schedule = store.schedule(for: reading.planID)
        let missed = schedule?.missedDays() ?? []
        Section {
            VStack(alignment: .leading, spacing: 6) {
                Text(reading.isCatchUp ? "Catching up" : "Today").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                if let label = reading.day.label { Text(label.uppercased()).font(.caption2).foregroundStyle(.secondary) }
                Text(reading.display).font(.title2.weight(.semibold))
                Text("\(reading.planTitle) · Day \(reading.dayNumber) of \(reading.totalDays)")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("plans.today")
            if let devotion = reading.day.devotion, !devotion.isEmpty {
                Text(devotion).textSelection(.enabled)
                Button { ListenController.shared.playProse(devotion, title: reading.planTitle, in: model) } label: { Label("Read Devotion Aloud", systemImage: "speaker.wave.2") }
            }
            ForEach(reading.passages, id: \.self) { passage in
                Button { onOpen(passage.passage) } label: { Label("Read \(passage.display)", systemImage: "book") }
            }
            Button(action: onListen) { Label("Listen to Today’s Reading", systemImage: "headphones") }
                .accessibilityIdentifier("plans.listen")
            Button {
                store.setDone(!reading.isDone, day: reading.day.index, in: reading.planID)
            } label: {
                Label(reading.isDone ? "Read — Tap to Undo" : "Mark as Read",
                      systemImage: reading.isDone ? "checkmark.circle.fill" : "circle")
            }
            .accessibilityIdentifier("plans.markDone")
            if let schedule {
                let streak = schedule.streak()
                if streak > 0 {
                    Label("\(streak)-day streak", systemImage: "flame").foregroundStyle(.orange)
                }
                if !missed.isEmpty {
                    NavigationLink(value: reading.planID) {
                        Label("\(missed.count) days to catch up on", systemImage: "arrow.uturn.backward")
                    }
                }
                if schedule.plan.source?.studyCategory != nil, let source = schedule.plan.source {
                    NavigationLink {
                        StudyView(source: source)
                    } label: { Label("This Week’s Study", systemImage: "text.book.closed") }
                }
            }
        }
    }
}

/// A plan with nothing to read today: a weekend, a day the source skipped, or the end of the plan.
private struct RestSection: View {
    let schedule: PlanSchedule

    var body: some View {
        Section {
            VStack(alignment: .leading, spacing: 4) {
                Text("Today").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                Text(headline).font(.title3.weight(.semibold))
                Text(schedule.plan.title).font(.footnote).foregroundStyle(.secondary)
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("plans.today")
            if let source = schedule.plan.source, source.studyCategory != nil {
                NavigationLink { StudyView(source: source) } label: { Label("This Week’s Study", systemImage: "text.book.closed") }
            }
        }
    }

    private var headline: String {
        switch schedule.today() {
        case .finished: String(localized: "You’ve finished this plan.", comment: "Reading plans: Today when every day is past")
        case .notStarted(let starts): String(localized: "Starts \(starts)", comment: "Reading plans: Today before the start date. %@ is a date like 2026-10-26.")
        default: String(localized: "No reading today.", comment: "Reading plans: Today on a rest day")
        }
    }
}

// MARK: Starting

struct StartPlanView: View {
    let plan: ReadingPlan
    var finished: () -> Void
    @State private var start = Date()

    var body: some View {
        Form {
            Section {
                Text(plan.summary)
                DatePicker("Start", selection: $start, displayedComponents: .date)
            } footer: {
                Text("The passages appear in the translation you’re reading. Change translation any time.")
            }
            Section {
                Button("Start Plan") {
                    PlanStore.shared.start(plan, on: start)
                    finished()
                }
                .accessibilityIdentifier("plans.startButton")
            }
        }
        .navigationTitle(plan.title)
    }
}

struct BookPlanPicker: View {
    var finished: () -> Void

    var body: some View {
        List(BookID.allCases) { book in
            NavigationLink(book.name) {
                StartPlanView(plan: PlanCatalog.book(book), finished: finished)
            }
            .badge(book.chapterCount)
            .accessibilityIdentifier("plans.book.\(book.rawValue)")
        }
        .navigationTitle("Choose a Book")
    }
}

// MARK: Detail

struct PlanDetailView: View {
    let planID: String
    var onOpen: (Passage) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var reminderOn = false
    @State private var reminderTime = Calendar.current.date(from: DateComponents(hour: 8, minute: 0)) ?? Date()
    @State private var remindersDenied = false
    private let store = PlanStore.shared

    var body: some View {
        if let schedule = store.schedule(for: planID) {
            let missed = Set(schedule.missedDays().map(\.index))
            List {
                Section {
                    ProgressView(value: schedule.fraction)
                    Text("\(schedule.completedCount) of \(schedule.plan.days.count) days read · \(schedule.streak())-day streak")
                        .font(.footnote).foregroundStyle(.secondary)
                    if planID != store.primaryID {
                        Button("Make This My Main Plan") { store.makePrimary(planID) }
                    }
                }
                Section("Reminder") {
                    Toggle("Daily Reminder", isOn: $reminderOn)
                    if reminderOn {
                        DatePicker("Time", selection: $reminderTime, displayedComponents: .hourAndMinute)
                    }
                    if remindersDenied {
                        Text("Notifications are off for Scripture Alone. Turn them on in Settings to be reminded.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
                .onChange(of: reminderOn) { applyReminder() }
                .onChange(of: reminderTime) { applyReminder() }
                if let source = schedule.plan.source {
                    Section("Source") {
                        Link(destination: source.siteURL) { Label(source.name, systemImage: "safari") }
                        if source.studyCategory != nil {
                            NavigationLink { StudyView(source: source) } label: { Label("This Week’s Study", systemImage: "text.book.closed") }
                        }
                    }
                }
                Section("Days") {
                    ForEach(schedule.plan.days) { day in
                        let done = schedule.progress.completed.contains(day.index)
                        HStack {
                            Button {
                                store.setDone(!done, day: day.index, in: planID)
                            } label: {
                                Image(systemName: done ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(done ? Color.accentColor : missed.contains(day.index) ? .orange : .secondary)
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel(done ? "Read" : "Not read")
                            Button { if let p = day.passages.first { onOpen(p.passage) } } label: {
                                VStack(alignment: .leading) {
                                    Text(day.display)
                                    Text(dayCaption(day, schedule)).font(.footnote).foregroundStyle(.secondary)
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                Section {
                    Button("Stop This Plan", role: .destructive) {
                        store.stop(planID)
                        dismiss()
                    }
                }
            }
            .navigationTitle(schedule.plan.title)
            .onAppear {
                if let time = schedule.progress.reminder {
                    reminderOn = true
                    reminderTime = Calendar.current.date(from: DateComponents(hour: time.hour, minute: time.minute)) ?? reminderTime
                }
            }
        }
    }

    private func dayCaption(_ day: PlanDay, _ schedule: PlanSchedule) -> String {
        let when = schedule.date(forDay: day.index).map { $0.formatted(date: .abbreviated, time: .omitted) } ?? ""
        return [day.label, when].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
    }

    private func applyReminder() {
        guard reminderOn else {
            store.setReminder(nil, for: planID)
            return
        }
        let parts = Calendar.current.dateComponents([.hour, .minute], from: reminderTime)
        Task {
            if await PlanReminders.requestAuthorization() {
                remindersDenied = false
                store.setReminder(.init(hour: parts.hour ?? 8, minute: parts.minute ?? 0), for: planID)
            } else {
                remindersDenied = true
                reminderOn = false
            }
        }
    }
}

// MARK: Import

struct ImportPlanView: View {
    var finished: () -> Void
    @State private var address = ""
    @State private var followCalendar = true
    @State private var since = Calendar.current.date(byAdding: .year, value: -1, to: Date()) ?? Date()
    @State private var working = false
    @State private var message: String?
    @State private var skipped = 0

    var body: some View {
        Form {
            Section {
                Button {
                    run(preset: PlanFeedImporter.disciplesChurch)
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Disciples Church Bible Reading Plan")
                        Text("discipleschurch.com/bible").font(.footnote).foregroundStyle(.secondary)
                    }
                }
                .disabled(working)
                .accessibilityIdentifier("plans.import.disciples")
            } header: { Text("A Church’s Plan") }
            Section {
                TextField("Plan address", text: $address)
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    .keyboardType(.URL)
                    #endif
                    .autocorrectionDisabled()
                    .accessibilityIdentifier("plans.import.address")
                Button("Import") { run(preset: nil) }
                    .disabled(working || address.trimmingCharacters(in: .whitespaces).isEmpty)
            } header: { Text("Or Paste an Address") } footer: {
                Text("A WordPress site or its feed whose post titles name the day’s passage, like “Epistle – 1 Corinthians 5”.")
            }
            Section {
                Toggle("Follow the Site’s Calendar", isOn: $followCalendar)
                if !followCalendar {
                    DatePicker("Start With Posts From", selection: $since, in: ...Date(), displayedComponents: .date)
                }
            } footer: {
                Text(followCalendar ? "Today’s reading is the one the site posts today."
                                    : "Counts the site’s days from today, one after another.")
            }
            if working { HStack { ProgressView(); Text("Reading the schedule…") } }
            if let message { Text(message).foregroundStyle(.secondary).accessibilityIdentifier("plans.import.message") }
        }
        .navigationTitle("Import a Plan")
    }

    private func run(preset: PlanFeedImporter.Preset?) {
        working = true
        message = nil
        Task {
            defer { working = false }
            do {
                let result = try await PlanStore.shared.importPlan(
                    pasted: address, preset: preset, mode: followCalendar ? .followCalendar : .fromDayOne(since: since))
                skipped = result.skipped.count
                finished()
            } catch PlanFeedError.offline {
                message = String(localized: "You’re offline. Connect to the internet to import a plan.",
                                 comment: "Reading plans import: shown when the request could not reach the site")
            } catch PlanFeedError.noReadings {
                message = String(localized: "That site has no posts titled with a Bible passage.",
                                 comment: "Reading plans import: shown when no post title names a passage")
            } catch PlanFeedError.badURL {
                message = String(localized: "That doesn’t look like a web address.", comment: "Reading plans import")
            } catch {
                message = String(localized: "Couldn’t read a plan from that address.", comment: "Reading plans import: generic failure")
            }
        }
    }
}

// MARK: Study

/// The site's weekly commentary, fetched when the reader asks for it and shown with a link back. It
/// is the site's own writing: never stored, never bundled.
struct StudyView: View {
    let source: PlanSource
    @Environment(ReaderModel.self) private var model
    @State private var state: Load = .loading

    enum Load {
        case loading
        case loaded(StudyPost)
        case none
        case offline
        case failed
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                switch state {
                case .loading:
                    ProgressView()
                case .loaded(let post):
                    Text(post.title).font(.title3.weight(.semibold))
                    Button { ListenController.shared.playProse(post.text, title: post.title, in: model) } label: {
                        Label("Read Aloud", systemImage: "speaker.wave.2")
                    }
                    .accessibilityIdentifier("plans.study.listen")
                    Text(post.text).textSelection(.enabled)
                    if let link = post.link {
                        Link("Read on \(source.name)", destination: link).font(.footnote)
                    }
                case .none:
                    Text("There’s no study to show yet.").foregroundStyle(.secondary)
                case .offline:
                    Text("The study is read online. Connect to the internet and try again.").foregroundStyle(.secondary)
                    retry
                case .failed:
                    Text("The study couldn’t be loaded.").foregroundStyle(.secondary)
                    retry
                }
                if case .loaded = state {} else {
                    Link("Open \(source.name)", destination: source.siteURL).font(.footnote)
                }
            }
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .navigationTitle("Weekly Study")
        .task { await load() }
        .accessibilityIdentifier("plans.study")
    }

    private var retry: some View {
        Button("Try Again") { Task { await load() } }
    }

    private func load() async {
        state = .loading
        do {
            let today = PlanSchedule.isoDay(Date())
            if let post = try await PlanFeedImporter(fetcher: URLSession.shared).latestStudy(source: source, onOrBefore: today) {
                state = .loaded(post)
            } else {
                state = .none
            }
        } catch PlanFeedError.offline {
            state = .offline
        } catch {
            state = .failed
        }
    }
}
