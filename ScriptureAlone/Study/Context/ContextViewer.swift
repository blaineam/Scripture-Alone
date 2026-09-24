import SwiftUI
import ScriptureAloneCore

/// The large map, timeline and charts. Opens in its own window on iPad and Mac, so it can sit
/// beside the text for reference, and as a resizable sheet on iPhone.
struct ContextViewer: View {
    @State private var request: ContextViewerRequest
    /// Follows the reader to each new chapter (a window can be pinned instead).
    let isWindow: Bool
    @State private var followsReader = true
    @State private var chartPath: [String] = []
    @Environment(\.dismiss) private var dismiss

    init(request: ContextViewerRequest, isWindow: Bool) {
        _request = State(initialValue: request)
        _chartPath = State(initialValue: request.chartID.map { [$0] } ?? [])
        self.isWindow = isWindow
    }

    var body: some View {
        NavigationStack(path: $chartPath) {
            Group {
                switch request.tab {
                case .overview:
                    StudyContextView(chapter: request.chapter, verse: nil)
                        .environment(\.contextViewerHandler, ContextViewerHandler { next in
                            request.tab = next.tab
                            chartPath = next.chartID.map { [$0] } ?? []
                        })
                case .map: ContextMapExplorer(chapter: request.chapter)
                case .timeline: FullTimelineView(chapter: request.chapter)
                case .charts: ChartsList(chapter: request.chapter)
                }
            }
            .navigationTitle(request.chapter.display)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar { toolbar }
            #if os(iOS)
            .safeAreaBar(edge: .top, spacing: 0) {
                tabPicker
                    .padding(.horizontal)
                    .padding(.bottom, 8)
            }
            #endif
            .navigationDestination(for: String.self) { id in
                if let chart = ContextLibrary.shared.charts.first(where: { $0.id == id }) {
                    ChartView(chart: chart, chapter: request.chapter)
                }
            }
        }
        .onChange(of: ReadingFocus.shared.chapter) { _, chapter in
            if followsReader, let chapter { request.chapter = chapter }
        }
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 480)
        #endif
    }

    private var tabPicker: some View {
        Picker("View", selection: Binding(get: { request.tab }, set: { request.tab = $0; chartPath = [] })) {
            ForEach(ContextViewerRequest.Tab.allCases) { tab in
                Label(tab.title, systemImage: tab.symbol).tag(tab)
            }
        }
        .pickerStyle(.segmented)
        .labelStyle(.titleOnly)
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        #if os(macOS)
        ToolbarItem(placement: .principal) { tabPicker.fixedSize() }
        #endif
        if isWindow {
            ToolbarItem(placement: .primaryAction) {
                Toggle(isOn: $followsReader) {
                    Label(followsReader ? "Following the Reader" : "Pinned to \(request.chapter.display)",
                          systemImage: followsReader ? "link" : "pin")
                }
                .help("Follow the chapter you are reading")
            }
        } else {
            ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
        }
    }
}

/// Window content for `ContextViewerRequest.windowID`.
struct ContextViewerWindow: View {
    let request: ContextViewerRequest?

    var body: some View {
        ContextViewer(request: request ?? ContextViewerRequest(chapter: ReadingFocus.shared.chapter ?? ChapterRef(.acts, 13)),
                      isWindow: true)
    }
}

/// Full-size map of the chapter's places with search across every place.
struct ContextMapExplorer: View {
    let chapter: ChapterRef
    @State private var search = ""
    @State private var focus: Place?
    @State private var detail: Place?

    var body: some View {
        let mentions = ContextLibrary.shared.places(in: chapter)
        var pins = mentions.map { MapPin(place: $0.place) }
        if let focus, !pins.contains(where: { $0.id == focus.id }) { pins.insert(MapPin(place: focus), at: 0) }
        let fit = focus.map { MapProjection.fit([MapPin(place: $0).point], minSpan: 3) } ?? MapContent.fitRect(for: pins)
        return BibleMapView(content: MapContent(pins: pins, selectedID: focus?.id ?? detail?.id, fitRect: fit),
                            fitToken: [AnyHashable(chapter), AnyHashable(focus?.id)]) { id in
            detail = mentions.first { $0.place.id == id }?.place ?? ((try? ContextLibrary.shared.store?.place(id: id)) ?? nil)
        }
        .ignoresSafeArea(edges: .bottom)
        .searchable(text: $search, prompt: "Find a place")
        .searchSuggestions {
            ForEach((try? ContextLibrary.shared.store?.searchPlaces(search, limit: 12)) ?? []) { place in
                Button {
                    focus = place
                    search = ""
                } label: {
                    VStack(alignment: .leading) {
                        Text(place.name)
                        Text(place.modernName.isEmpty ? "\(place.mentions) verses" : "\(place.modernName) · \(place.mentions) verses")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .sheet(item: $detail) { PlaceDetailView(place: $0).presentationDetents([.medium, .large]) }
        .onChange(of: chapter) { focus = nil }
    }
}

/// Every chart, those for the current book first.
struct ChartsList: View {
    let chapter: ChapterRef

    var body: some View {
        let all = ContextLibrary.shared.charts
        let suggested = all.filter { $0.scope.contains(chapter.book) }
        let others = all.filter { !$0.scope.contains(chapter.book) }
        List {
            if !suggested.isEmpty {
                Section("For \(chapter.book.name)") {
                    ForEach(suggested) { chart in NavigationLink(value: chart.id) { ChartCard(chart: chart, framed: false) } }
                }
            }
            Section(suggested.isEmpty ? "Charts" : "More Charts") {
                ForEach(others) { chart in NavigationLink(value: chart.id) { ChartCard(chart: chart, framed: false) } }
            }
        }
    }
}

/// Opens a context viewer: a window where the platform supports several, otherwise a sheet.
struct ContextViewerPresenter: ViewModifier {
    @Binding var request: ContextViewerRequest?
    @State private var sheet: ContextViewerRequest?
    @Environment(\.openWindow) private var openWindow
    @Environment(\.supportsMultipleWindows) private var supportsMultipleWindows
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var sizeClass
    private var roomForWindows: Bool { sizeClass != .compact }
    #else
    private let roomForWindows = true
    #endif

    func body(content: Content) -> some View {
        content
            .onChange(of: request) { _, new in
                guard let new else { return }
                request = nil
                #if DEBUG
                // Screenshots keep the viewer over the reader instead of in a second window.
                if ScreenshotScene.current != nil {
                    sheet = new
                    return
                }
                #endif
                if supportsMultipleWindows && roomForWindows {
                    openWindow(id: ContextViewerRequest.windowID, value: new)
                } else {
                    sheet = new
                }
            }
            .sheet(item: $sheet) { request in
                ContextViewer(request: request, isWindow: false)
                    .presentationDetents([.medium, .large])
                    .presentationBackgroundInteraction(.enabled(upThrough: .medium))
            }
    }
}

extension ContextViewerRequest: Identifiable {
    var id: String { "\(chapter.book.rawValue)-\(chapter.chapter)-\(tab.rawValue)-\(chartID ?? "")" }
}

extension View {
    func contextViewerPresenter(_ request: Binding<ContextViewerRequest?>) -> some View {
        modifier(ContextViewerPresenter(request: request))
    }
}

/// The reader's hooks into Study context: a "Maps & Timeline" toolbar button (iPad and Mac), keeping
/// `ReadingFocus` current, and jumping when a map or chart asks to open a passage.
struct ContextReaderHooks: ViewModifier {
    @Environment(ReaderModel.self) private var model
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    #endif
    @State private var viewer: ContextViewerRequest?

    func body(content: Content) -> some View {
        content
            .toolbar {
                #if os(iOS)
                // On a phone the Study sheet's Context tab holds the maps, timeline and charts;
                // iPad keeps the button, whose viewer opens in a window beside the text.
                if horizontalSizeClass != .compact {
                    ToolbarItem(placement: .topBarTrailing) { button }
                }
                #else
                ToolbarItem(placement: .primaryAction) { button }
                #endif
            }
            .contextViewerPresenter($viewer)
            .onChange(of: model.location, initial: true) { _, location in ReadingFocus.shared.chapter = location }
            .onChange(of: ReadingFocus.shared.jumpRequest) { _, jump in
                if let jump { model.go(to: jump.verse) }
            }
            #if DEBUG
            .task {
                guard ScreenshotScene.current == .maps else { return }
                try? await Task.sleep(for: .milliseconds(1_500))
                viewer = ContextViewerRequest(chapter: ChapterRef(.acts, 13), tab: .map)
            }
            #endif
    }

    private var button: some View {
        Button { viewer = ContextViewerRequest(chapter: model.location, tab: .overview) } label: {
            Label("Maps & Timeline", systemImage: "map")
        }
        .keyboardShortcut("m", modifiers: [.command, .shift])
        .help("Maps, timeline and charts for this chapter")
    }
}

extension View {
    /// Adds the "Maps & Timeline" entry point to the reader.
    func contextReaderHooks() -> some View { modifier(ContextReaderHooks()) }
}

