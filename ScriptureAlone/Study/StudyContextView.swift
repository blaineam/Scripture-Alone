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

#Preview {
    StudyContextView(chapter: ChapterRef(.acts, 13), verse: nil)
}
