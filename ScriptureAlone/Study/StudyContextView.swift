import SwiftUI
import ScriptureAloneCore

/// Study mode's Context tab: when the chapter happened (era on a timeline), where (a map of
/// the places it names) and charts for the book. Opens larger viewers for reference beside
/// the text. Everything is bundled and offline; see ScriptureAlone/Study/Context/.
struct StudyContextView: View {
    let chapter: ChapterRef
    let verse: Int?

    init(chapter: ChapterRef, verse: Int?) {
        self.chapter = chapter
        self.verse = verse
    }

    @State private var selectedPlace: Place?
    @State private var viewer: ContextViewerRequest?
    @State private var showCredits = false
    @Environment(\.contextViewerHandler) private var viewerHandler

    var body: some View {
        if ContextLibrary.shared.store == nil {
            ContentUnavailableView("Context Unavailable", systemImage: "map",
                                   description: Text("The maps and timeline data are missing from this build."))
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 28) {
                    section("When", symbol: "calendar.day.timeline.left") {
                        ContextWhenSection(chapter: chapter) { open(.timeline) }
                    }
                    section("Where", symbol: "map") {
                        ContextWhereSection(chapter: chapter, verse: verse, openMap: { open(.map) },
                                            selectedPlace: $selectedPlace)
                    }
                    chartsSection
                    ImportedImagesSection(chapter: chapter)
                    Button("Sources & Credits", systemImage: "info.circle") { showCredits = true }
                        .font(.footnote)
                        .buttonStyle(.borderless)
                }
                .padding()
            }
            .sheet(item: $selectedPlace) { place in
                PlaceDetailView(place: place)
                    .presentationDetents([.medium, .large])
            }
            .sheet(isPresented: $showCredits) { ContextAttributionView() }
            .contextViewerPresenter($viewer)
        }
    }

    private var chartsSection: some View {
        let suggested = ContextLibrary.shared.charts(for: chapter.book)
        return section("Charts", symbol: "tablecells") {
            VStack(alignment: .leading, spacing: 8) {
                ForEach(suggested) { chart in
                    Button { open(.charts, chart: chart.id) } label: { ChartCard(chart: chart) }
                        .buttonStyle(.plain)
                }
                Button(suggested.isEmpty ? "Browse Charts" : "All Charts", systemImage: "square.grid.2x2") {
                    open(.charts)
                }
                .font(.subheadline)
                .buttonStyle(.borderless)
            }
        }
    }

    private func section<Content: View>(_ title: LocalizedStringKey, symbol: String,
                                        @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Label(title, systemImage: symbol)
                .font(.title3.weight(.semibold))
                .accessibilityAddTraits(.isHeader)
            content()
        }
    }

    private func open(_ tab: ContextViewerRequest.Tab, chart: String? = nil) {
        let request = ContextViewerRequest(chapter: chapter, tab: tab, chartID: chart)
        if let viewerHandler { viewerHandler.open(request) } else { viewer = request }
    }
}

/// Study mode's Context tab: the same four views as the Maps & Timeline viewer — the chapter's
/// overview, the map to explore, the whole timeline and the charts — switched in place, so the
/// Study sheet holds all of it and the reader needs no separate map button.
struct StudyContextBrowser: View {
    let chapter: ChapterRef
    let verse: Int?
    @State private var tab: ContextViewerRequest.Tab = {
        #if DEBUG
        // The maps screenshot: Study's Context tab, on the map.
        if ScreenshotScene.current == .maps { return .map }
        #endif
        return .overview
    }()
    /// A chart the overview asked for, opened on top.
    @State private var chart: String?

    var body: some View {
        VStack(spacing: 0) {
            Picker("View", selection: $tab) {
                ForEach(ContextViewerRequest.Tab.allCases) { tab in Text(tab.title).tag(tab) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .padding(.horizontal)
            .padding(.vertical, 8)
            Group {
                switch tab {
                case .overview:
                    StudyContextView(chapter: chapter, verse: verse)
                        .environment(\.contextViewerHandler, ContextViewerHandler { next in
                            tab = next.tab
                            chart = next.chartID
                        })
                case .map: ContextMapExplorer(chapter: chapter)
                case .timeline: FullTimelineView(chapter: chapter)
                case .charts: ChartsList(chapter: chapter)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .navigationDestination(for: String.self) { id in chartView(id) }
        .navigationDestination(item: $chart) { id in chartView(id) }
    }

    @ViewBuilder
    private func chartView(_ id: String) -> some View {
        if let chart = ContextLibrary.shared.charts.first(where: { $0.id == id }) {
            ChartView(chart: chart, chapter: chapter)
        }
    }
}

#Preview {
    StudyContextView(chapter: ChapterRef(.acts, 13), verse: nil)
}
