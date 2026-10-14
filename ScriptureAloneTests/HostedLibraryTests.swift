import SwiftUI
import SwiftData
import Testing
import ScriptureAloneCore
@testable import Scripture_Alone

/// The reader's own library screens — topics, favorites and highlights, notes, keepsakes, family
/// sharing, importing, exporting and sharing — drawn against an in-memory library.
@MainActor
@Suite(.serialized)
struct HostedLibraryTests {
    let container: ModelContainer
    let context: ModelContext
    let model: ReaderModel

    init() throws {
        container = try ViewHost.container()
        context = ViewHost.seed(container)
        model = ViewHost.readerModel()
    }

    func draw<V: View>(_ view: V, dark: Bool = false, size: CGSize = CGSize(width: 820, height: 900),
                       legacy: LegacyLibrary? = nil, session: LegacySession = LegacySession()) async -> HostedView {
        await ViewHost.render(ViewHost.inReader(view, model: model, container: container, legacy: legacy, session: session),
                              size: size, dark: dark)
    }

    func stack<V: View>(_ view: V) -> some View { NavigationStack { view } }

    func keepsake(owner: String? = "Grandma") -> Keepsake {
        let when = Date(timeIntervalSince1970: 1_700_000_000)
        var manifest = KeepsakeManifest(ownerName: owner, dedication: "For the family", preferredTranslation: "ASV")
        manifest.passphraseHint = nil
        var value = Keepsake(
            manifest: manifest,
            highlights: [KeepsakeHighlight(verse: VerseRef(.john, 3, 16).key, color: "yellow", createdAt: when),
                         KeepsakeHighlight(verse: VerseRef(.john, 3, 17).key, color: "blue", createdAt: when)],
            notes: [KeepsakeNote(title: "Born again", body: "Nicodemus came by night.",
                                 anchors: [VerseRange(VerseRef(.john, 3, 1), VerseRef(.john, 3, 21))],
                                 createdAt: when, updatedAt: when),
                    KeepsakeNote(title: "", body: "A note with no title.", anchors: [], createdAt: when, updatedAt: when, origin: "camera")])
        value.refreshSummary()
        return value
    }

    // MARK: Topics

    @Test func topicsAndLifeThemesDraw() async throws {
        #expect(await draw(stack(TopicsDirectoryView())).isDrawn)
        let theme = try #require(LifeThemeCatalog.shared.themes.first)
        #expect(await draw(LifeThemeRow(theme: theme)).isDrawn)
        #expect(await draw(stack(LifeThemeView(theme: theme, onOpen: { _ in }))).isDrawn)
        #expect(await draw(CrisisCard()).isDrawn)
        let index = try #require(TopicsLibrary.index)
        let topic = try #require(index.topics.first(where: { $0.name == "Love" }) ?? index.topics.first)
        #expect(await draw(stack(IndexTopicView(index: index, topic: topic, onOpen: { _ in }))).isDrawn)
    }

    // MARK: Favorites, highlights, notes

    @Test func favoritesAndHighlightsDraw() async {
        for place in NotesPanel.Place.allCases {
            #expect(await draw(List { FavoritesSection(search: "", place: place) }).isDrawn)
            #expect(await draw(List { HighlightsSection(search: "", place: place) }, dark: true).isDrawn)
        }
        #expect(await draw(List { FavoritesSection(search: "love") }).isDrawn)
        #expect(await draw(List { HighlightsSection(search: "john") }).isDrawn)
        #expect(await draw(FavoriteButton(ranges: [VerseRange(VerseRef(.john, 3, 16))])).isDrawn)
    }

    @Test func theNotesPanelAndEditorDraw() async throws {
        var path: [UUID] = []
        let binding = Binding(get: { path }, set: { path = $0 })
        #expect(await draw(NotesPanel(path: binding)).isDrawn)
        #expect(await draw(NotesPanel(path: binding), dark: true, size: CGSize(width: 390, height: 800)).isDrawn)
        let note = try #require(try context.fetch(FetchDescriptor<Note>()).first)
        #expect(await draw(stack(NoteEditor(note: note))).isDrawn)
        #expect(await draw(stack(NoteEditor(note: Note())), dark: true).isDrawn)
        note.slidePhoto = Data([0xFF, 0xD8, 0xFF, 0xD9])
        #expect(await draw(Form { SlidePhotoSection(note: note) }).isDrawn)
        var onPath = [note.uuid]
        let withNote = Binding(get: { onPath }, set: { onPath = $0 })
        #expect(await draw(NotesPanel(path: withNote)).isDrawn)
    }

    @Test func theSelectionBarDrawsWithAndWithoutASelection() async {
        model.selection = [VerseRef(.john, 3, 16).key]
        #expect(await draw(SelectionBar(onNote: {})).isDrawn)
        model.selection = Set((1...5).map { VerseRef(.john, 3, $0).key })
        #expect(await draw(SelectionBar(onNote: {}), dark: true).isDrawn)
        model.selection = []
        #expect(await draw(SelectionBar(onNote: {})).isDrawn)
        #expect(await draw(NowPlayingBar()).isDrawn)
        AssetLibrary.shared.overrideState(.failed("No connection"), of: .kjv)
        defer { AssetLibrary.shared.overrideState(nil, of: .kjv) }
        #expect(await draw(TranslationDownloadBanner()).isLaidOut, "a failed download offers Try Again")
    }

    // MARK: Keepsakes and family

    @Test func legacySettingsDrawWithAndWithoutKeepsakes() async throws {
        #expect(await draw(stack(LegacySettingsView())).isDrawn)
        #expect(await draw(Form { LegacyAndExportRow() }).isDrawn)
        #expect(await draw(stack(KeepsakeCreateView())).isDrawn)

        let library = LegacyLibrary(directory: ViewHost.scratchDirectory("keepsakes"))
        try library.add(keepsake())
        try library.add(keepsake(owner: nil))
        let entry = try #require(library.entries.first)
        #expect(await draw(stack(LegacySettingsView()), legacy: library).isDrawn)
        #expect(await draw(KeepsakeSummaryHeader(manifest: entry.manifest), legacy: library).isDrawn)
    }

    @Test func keepsakesDrawInTheReader() async throws {
        let value = keepsake()
        let library = LegacyLibrary(directory: ViewHost.scratchDirectory("keepsakes"))
        let session = LegacySession()
        session.open(value, model: model)
        #expect(await draw(LegacyBanner(), legacy: library, session: session).isDrawn)
        var path: [UUID] = []
        let binding = Binding(get: { path }, set: { path = $0 })
        #expect(await draw(LegacyNotesPanel(keepsake: value, path: binding), legacy: library, session: session).isDrawn)
        let note = try #require(value.notes.first)
        #expect(await draw(stack(LegacyNoteDetail(note: note)), legacy: library, session: session).isDrawn)
        #expect(await draw(LegacyNotesPopover(keepsake: value, ids: [note.id.uuidString], openNote: { _ in }),
                           legacy: library, session: session).isDrawn)
        #expect(await draw(ReaderView(), legacy: library, session: session).isDrawn)
        session.close(model: model)
    }

    @Test func aKeepsakeFileOpensInTheImportSheet() async throws {
        let directory = ViewHost.scratchDirectory("import")
        let url = directory.appending(path: "Gift.\(KeepsakeArchive.fileExtension)")
        try KeepsakeArchive.encode(keepsake()).write(to: url)
        #expect(await draw(KeepsakeImportSheet(url: url)).isDrawn)
        let missing = directory.appending(path: "Missing.\(KeepsakeArchive.fileExtension)")
        #expect(await draw(KeepsakeImportSheet(url: missing), dark: true).isDrawn)
    }

    @Test func familySharingScreensDraw() async {
        #expect(await draw(stack(FamilySharingView())).isDrawn)
        #expect(await draw(Form { FamilySharingSettingsSection() }).isDrawn)
        #expect(await draw(List { SharedBiblesSection(open: { _ in }) }).isLaidOut, "no shared Bibles yet: the section is empty")
        let snapshot = FamilyBibleSnapshot(profile: nil)
        let entry = SharedBibleLibrary.Entry(id: "owner-1", snapshot: snapshot, fallbackID: UUID(), shareTitle: "Dad", lastFetched: .now, ended: nil)
        let ended = SharedBibleLibrary.Entry(id: "owner-2", snapshot: snapshot, fallbackID: UUID(), shareTitle: nil, lastFetched: nil, ended: .now)
        #expect(await draw(SharedBibleStatusText(entry: entry)).isDrawn)
        #expect(await draw(SharedBibleStatusText(entry: ended)).isDrawn)
        #expect(await draw(KeepSharedBibleSheet(entry: entry)).isDrawn)
        #expect(await draw(LiveShareCaption(id: "owner-1")).isDrawn)
        #expect(await draw(SharedBibleWelcome(id: "owner-1")).isDrawn)
        #expect(await draw(List { LegacyFavoritesList(favorites: [], search: "") }).isDrawn)
    }

    // MARK: Importing, exporting, sharing

    @Test func importingScreensDraw() async {
        #expect(await draw(stack(LifeBibleImportView())).isDrawn)
        #expect(await draw(stack(OnlineKeysView())).isDrawn)
        #expect(await draw(stack(CatalogView(onPick: { _, _ in }))).isDrawn)
        #expect(await draw(stack(CatalogView(onPick: { _, _ in })), dark: true, size: CGSize(width: 390, height: 800)).isDrawn)
    }

    @Test func exportAndShareDraw() async throws {
        let notes = keepsake().notes
        #expect(await draw(NotesExportSheet(notes: notes, title: "Notes")).isDrawn)
        #expect(await draw(NotesExportSheet(notes: [], title: "Empty", preferredTranslation: "ASV"), dark: true).isDrawn)
        let source = try #require(model.source)
        let share = try #require(ShareSource(source: source, ranges: [VerseRange(VerseRef(.john, 3, 16))]))
        #expect(await draw(ShareDesigner(source: share)).isDrawn)
        #expect(await draw(ShareDesigner(source: share), dark: true, size: CGSize(width: 390, height: 844)).isDrawn)
        #expect(await draw(ShareMenu(ranges: [VerseRange(VerseRef(.john, 3, 16))], quotation: "For God so loved the world")).isDrawn)
        #expect(await draw(BackdropSwatch(background: ShareBackground.allCases.first!, side: 44)).isDrawn)
    }

    @Test func aScannedSlideOpensInReview() async throws {
        let space = CGColorSpaceCreateDeviceRGB()
        let bitmap = try #require(CGContext(data: nil, width: 64, height: 48, bitsPerComponent: 8, bytesPerRow: 0, space: space,
                                            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        bitmap.setFillColor(CGColor(red: 0.9, green: 0.9, blue: 0.8, alpha: 1))
        bitmap.fill(CGRect(x: 0, y: 0, width: 64, height: 48))
        let image = try #require(bitmap.makeImage())
        #expect(await draw(SlideReviewView(slide: ScannedSlide(image: image), appendTo: nil, onSaved: { _ in })).isDrawn)
        #expect(await draw(SlideReviewView(slide: ScannedSlide(image: image, findsScreen: true), appendTo: nil, onSaved: { _ in }),
                           dark: true).isDrawn)
    }
}
