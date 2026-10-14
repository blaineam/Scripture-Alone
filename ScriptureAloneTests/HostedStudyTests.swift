import SwiftUI
import SwiftData
import Testing
import ScriptureAloneCore
@testable import Scripture_Alone

/// The Study screens (cross references, commentary, interlinear, maps, timelines and charts) drawn
/// against the bundled study data: each opens on real chapters and lays out.
@MainActor
@Suite(.serialized)
struct HostedStudyTests {
    let container: ModelContainer
    let model: ReaderModel

    init() throws {
        container = try ViewHost.container()
        model = ViewHost.readerModel()
    }

    func draw<V: View>(_ view: V, dark: Bool = false, size: CGSize = CGSize(width: 820, height: 900)) async -> HostedView {
        await ViewHost.render(ViewHost.inReader(view, model: model, container: container), size: size, dark: dark)
    }

    var library: ContextLibrary { ContextLibrary.shared }

    // MARK: Context

    @Test func theContextViewerOpensOnEveryTab() async {
        for tab in ContextViewerRequest.Tab.allCases {
            let request = ContextViewerRequest(chapter: ChapterRef(.acts, 13), tab: tab)
            #expect(await draw(ContextViewer(request: request, isWindow: false)).isDrawn)
            #expect(await draw(ContextViewer(request: request, isWindow: true), dark: true).isDrawn)
        }
        #expect(await draw(ContextViewerWindow(request: ContextViewerRequest(chapter: ChapterRef(.genesis, 12)))).isDrawn)
        #expect(await draw(ContextViewerWindow(request: nil)).isDrawn)
    }

    @Test func aChartOpensFromTheViewerRequest() async throws {
        let chart = try #require(library.charts.first)
        let request = ContextViewerRequest(chapter: ChapterRef(.firstKings, 12), tab: .charts, chartID: chart.id)
        #expect(await draw(ContextViewer(request: request, isWindow: false)).isDrawn)
    }

    @Test func theStudyContextOverviewDraws() async {
        #expect(await draw(StudyContextView(chapter: ChapterRef(.acts, 13), verse: nil)).isDrawn)
        #expect(await draw(StudyContextView(chapter: ChapterRef(.acts, 13), verse: 4)).isDrawn)
        #expect(await draw(StudyContextBrowser(chapter: ChapterRef(.exodus, 14), verse: nil)).isDrawn)
        #expect(await draw(StudyContextView(chapter: ChapterRef(.obadiah, 1), verse: 1)).isDrawn)
        #expect(await draw(ContextAttributionView()).isDrawn)
        #expect(await draw(ContextMapExplorer(chapter: ChapterRef(.luke, 2))).isDrawn)
        #expect(await draw(FullTimelineView(chapter: ChapterRef(.daniel, 2))).isDrawn)
        #expect(await draw(ChartsList(chapter: ChapterRef(.firstKings, 12))).isDrawn)
    }

    @Test func everyChartDrawsItsKind() async {
        #expect(!library.charts.isEmpty)
        for chart in library.charts {
            #expect(await draw(ChartView(chart: chart, chapter: ChapterRef(.firstKings, 12))).isLaidOut, "\(chart.title)")
            #expect(await draw(ChartCard(chart: chart)).isDrawn)
            #expect(await draw(ChartCard(chart: chart, framed: false), dark: true).isDrawn)
        }
    }

    @Test func chartBodiesDrawWithTheirOwnViews() async throws {
        for chart in library.charts {
            switch chart.kind {
            case .kings:
                let body = try chart.decode(KingsChart.self)
                #expect(await draw(KingsChartView(chart: body, chapter: ChapterRef(.secondKings, 18))).isDrawn)
                #expect(await draw(KingsChartView(chart: body, chapter: ChapterRef(.firstKings, 12)), dark: true).isDrawn)
            case .journeys:
                let body = try chart.decode(JourneysChart.self)
                #expect(await draw(JourneysChartView(chart: body, chapter: ChapterRef(.acts, 13))).isDrawn)
            case .tribes:
                #expect(await draw(TribesChartView(chart: try chart.decode(TribesChart.self))).isDrawn)
            case .feasts:
                #expect(await draw(FeastsChartView(chart: try chart.decode(FeastsChart.self))).isDrawn)
            }
        }
    }

    @Test func placesAndTimelineRowsDraw() async throws {
        let mentions = library.places(in: ChapterRef(.acts, 13))
        let first = try #require(mentions.first)
        #expect(await draw(PlaceDetailView(place: first.place)).isDrawn)
        #expect(await draw(PlaceRow(place: first.place, verses: first.verses, highlightVerse: first.verses.first.map { $0 % 1000 })).isDrawn)
        #expect(await draw(ConfidenceBadge(place: first.place)).isDrawn)
        var selected: Place?
        let binding = Binding(get: { selected }, set: { selected = $0 })
        #expect(await draw(ContextWhereSection(chapter: ChapterRef(.acts, 13), verse: 4, openMap: {}, selectedPlace: binding)).isDrawn)
        #expect(await draw(ContextWhereSection(chapter: ChapterRef(.acts, 13), verse: nil, openMap: {}, selectedPlace: binding)).isDrawn)
        #expect(await draw(ContextWhenSection(chapter: ChapterRef(.acts, 13), openTimeline: {})).isDrawn)
        let event = try #require(library.events(in: ChapterRef(.acts, 13)).first ?? library.events.first)
        #expect(await draw(EventRow(event: event)).isDrawn)
        #expect(await draw(EventRow(event: event, compact: true)).isDrawn)
        #expect(await draw(EraBand(eras: library.eras, time: library.time(for: ChapterRef(.acts, 13)))).isDrawn)
        #expect(await draw(EraBand(eras: library.eras, time: nil)).isDrawn)
    }

    @Test func theMapDrawsPinsRoutesAndTags() async {
        let pins = library.places(in: ChapterRef(.luke, 2)).map { MapPin(place: $0.place) }
        let route = MapRoute(id: "r", name: "Journey", points: pins.map(\.point), color: .blue)
        let tags = pins.prefix(2).map { MapTag(text: $0.name, point: $0.point) }
        let content = MapContent(pins: pins, routes: [route], tags: Array(tags), selectedID: pins.first?.id,
                                 fitRect: MapContent.fitRect(for: pins))
        #expect(await draw(BibleMapView(content: content)).isDrawn)
        #expect(await draw(BibleMapView(content: content, showsControls: false, interactive: false), dark: true).isDrawn)
        #expect(await draw(BibleMapView(content: MapContent(showsBackgroundPlaces: false)), size: CGSize(width: 390, height: 600)).isDrawn)
    }

    // MARK: Study panel

    @Test func theStudyPanelDrawsEveryTab() async {
        model.show(ChapterRef(.john, 3), verse: 16)
        #expect(await draw(StudyPanel()).isDrawn)
        #expect(await draw(StudyPanel(isSheet: true), size: CGSize(width: 390, height: 700)).isDrawn)
        #expect(await draw(StudySourcesView()).isDrawn)
        #expect(await draw(CommentatorTraditionsView()).isDrawn)
    }

    @Test func crossReferencesCommentaryAndInterlinearDraw() async {
        for verse in [VerseRef(.john, 3, 16), VerseRef(.genesis, 1, 1), VerseRef(.romans, 8, 28)] {
            #expect(await draw(CrossReferencesView(verse: verse)).isDrawn)
            #expect(await draw(CommentaryView(verse: verse)).isDrawn)
        }
        #expect(await draw(InterlinearView(verse: VerseRef(.john, 1, 1), verseText: "In the beginning was the Word")).isDrawn)
        #expect(await draw(InterlinearView(verse: VerseRef(.genesis, 1, 1), verseText: "In the beginning God created", glossText: "bereshit"),
                           dark: true).isDrawn)
    }
}
