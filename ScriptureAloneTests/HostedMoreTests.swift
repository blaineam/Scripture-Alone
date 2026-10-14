import SwiftUI
import SwiftData
import Testing
import ScriptureAloneCore
@testable import Scripture_Alone

/// The reader under every setting the Aa sheet offers, the Go To sheet under real searches, an
/// imported translation read end to end, and a family member's shared Bible — all drawn for real.
@MainActor
@Suite(.serialized)
struct HostedMoreTests {
    let container: ModelContainer
    let model: ReaderModel

    init() throws {
        container = try ViewHost.container()
        ViewHost.seed(container)
        model = ViewHost.readerModel()
    }

    func draw<V: View>(_ view: V, dark: Bool = false, size: CGSize = CGSize(width: 820, height: 900),
                       model override: ReaderModel? = nil) async -> HostedView {
        await ViewHost.render(ViewHost.inReader(view, model: override ?? model, container: container), size: size, dark: dark)
    }

    /// Runs `body` with these reader settings in the host's defaults, and puts back what was there.
    func with(_ settings: [String: Any], _ body: () async -> Void) async {
        let defaults = UserDefaults.standard
        let saved = settings.keys.map { ($0, defaults.object(forKey: $0)) }
        for (key, value) in settings { defaults.set(value, forKey: key) }
        await body()
        for (key, value) in saved {
            if let value { defaults.set(value, forKey: key) } else { defaults.removeObject(forKey: key) }
        }
    }

    // MARK: The reader under every setting

    @Test func everyLayoutAndThemeDraws() async {
        for layout in ReadingLayout.allCases {
            for theme in ReaderTheme.allCases {
                await with([SettingsKey.layout: layout.rawValue, SettingsKey.theme: theme.rawValue]) {
                    let hosted = await draw(ReaderView(), dark: theme == .dark || theme == .black)
                    #expect(hosted.isDrawn, "\(layout) / \(theme)")
                }
            }
        }
    }

    @Test func everyTypefaceAccentAndSizeDraws() async {
        for family in FontFamily.allCases {
            await with([SettingsKey.fontFamily: family.rawValue, SettingsKey.fontSize: 24.0, SettingsKey.lineSpacing: 1.6]) {
                #expect(await draw(ReaderView()).isDrawn, "\(family)")
            }
        }
        for accent in ReaderAccent.allCases {
            await with([SettingsKey.accent: accent.rawValue]) {
                #expect(await draw(ReaderView(), dark: true).isDrawn, "\(accent)")
            }
        }
    }

    @Test func theReaderDrawsWithEachMarkingTurnedOff() async {
        let off: [String] = [SettingsKey.redLetters, SettingsKey.verseNumbers, SettingsKey.headings, SettingsKey.footnotes]
        for key in off {
            await with([key: false]) {
                #expect(await draw(ReaderView()).isDrawn, Comment(rawValue: key))
            }
        }
        await with(Dictionary(uniqueKeysWithValues: off.map { ($0, false) })) {
            #expect(await draw(ReaderView()).isDrawn)
        }
    }

    @Test func theColumnReaderDrawsOnAWideScreen() async {
        await with([SettingsKey.columns: true]) {
            for chapter in [ChapterRef(.psalms, 119), ChapterRef(.john, 3), ChapterRef(.obadiah, 1)] {
                model.show(chapter)
                #expect(await draw(ReaderView(), size: CGSize(width: 1280, height: 800)).isDrawn)
                #expect(await draw(ReaderView(), dark: true, size: CGSize(width: 1024, height: 700)).isDrawn)
            }
        }
        model.show(ChapterRef(.john, 3))
    }

    @Test func theReaderDrawsWithAVerseSelectedAndOnEveryKindOfChapter() async {
        model.show(ChapterRef(.john, 3), verse: 16)
        model.selection = [VerseRef(.john, 3, 16).key, VerseRef(.john, 3, 17).key]
        #expect(await draw(ReaderView()).isDrawn)
        model.selection = []
        for chapter in [ChapterRef(.genesis, 1), ChapterRef(.psalms, 23), ChapterRef(.matthew, 5), ChapterRef(.revelation, 22),
                        ChapterRef(.jude, 1), ChapterRef(.songOfSolomon, 2)] {
            model.show(chapter)
            #expect(await draw(ReaderView(), size: CGSize(width: 390, height: 844)).isDrawn, "\(chapter.display)")
        }
        model.show(ChapterRef(.john, 3))
    }

    // MARK: Go To

    @Test func theGoToSheetAnswersEveryKindOfSearch() async {
        for query in ["John 3:16", "romans 8", "love", "anxiety", "in the beginning", "zzzzzz", "psalm 23:1-3", "  ", "grief", "suicide"] {
            #expect(await draw(PassagePicker(initialQuery: query)).isDrawn, "“\(query)”")
        }
        #expect(await draw(PassagePicker(), dark: true, size: CGSize(width: 390, height: 844)).isDrawn)
    }

    // MARK: An imported translation

    static let usfm = """
    \\id JHN Imported fixture
    \\h John
    \\toc1 The Gospel of John
    \\mt1 John
    \\c 3
    \\s1 The new birth
    \\p
    \\v 16 For God so loved the world, that he gave his only begotten Son, that whosoever believeth on him should not perish, but have eternal life.
    \\v 17 For God sent not the Son into the world to judge the world; but that the world should be saved through him.
    """

    func importedLibrary() throws -> (ImportedLibrary, BibleImportResult) {
        let directory = ViewHost.scratchDirectory("imports")
        let source = directory.appending(path: "fixture.zip")
        try ZipArchive.write([ZipArchive.Entry(name: "44-JHNfix.usfm", data: Data(Self.usfm.utf8))]).write(to: source)
        let identity = ImportedTranslationIdentity(id: "IMPORT-FIX", name: "Fixture Bible", abbreviation: "FIX", copyright: "Public domain (test)")
        let result = try BibleFileImporter().importBible(at: source, as: identity, into: directory)
        return (ImportedLibrary(directory: directory), result)
    }

    @Test func anImportedTranslationIsListedAndRead() async throws {
        let (library, result) = try importedLibrary()
        #expect(library.entries.map(\.info.id) == ["IMPORT-FIX"])
        #expect(result.storeURL.lastPathComponent == "IMPORT-FIX.sqlite")

        let reader = ViewHost.readerModel()
        reader.refreshTranslations(imported: library.entries.map { ($0.info, $0.url) })
        #expect(reader.translations.contains { $0.id == "IMPORT-FIX" })
        reader.selectTranslation("IMPORT-FIX", remember: false)
        #expect(reader.translationID == "IMPORT-FIX")
        reader.show(ChapterRef(.john, 3))
        #expect(await draw(ReaderView(), model: reader).isDrawn)

        let screen = ViewHost.inReader(TranslationsView(), model: reader, container: container)
            .environment(library)
        #expect(await ViewHost.render(screen).isDrawn, "the Added by You section lists the import")

        library.remove(try #require(library.entries.first))
        #expect(library.entries.isEmpty)
    }

    // MARK: Shared Bibles

    @Test func aSharedBibleShowsInTheSectionAndWelcome() async throws {
        let id = "owner-welcome"
        var snapshot = FamilyBibleSnapshot(profile: FamilyOwnerProfile(bibleID: UUID(), ownerName: "Dad", dedication: "For you", preferredTranslation: "ASV"))
        let when = Date(timeIntervalSince1970: 1_700_000_000)
        snapshot.notes["n1"] = KeepsakeNote(title: "Grace", body: "Undeserved.", anchors: [VerseRange(VerseRef(.john, 3, 16))], createdAt: when, updatedAt: when)
        snapshot.highlights["h1"] = KeepsakeHighlight(verse: VerseRef(.john, 3, 16).key, color: "green", createdAt: when)
        snapshot.favorites["f1"] = FamilyFavorite(id: UUID(), start: VerseRef(.john, 3, 16).key, end: VerseRef(.john, 3, 17).key, createdAt: when)
        let live = SharedBibleLibrary.Entry(id: id, snapshot: snapshot, fallbackID: UUID(), shareTitle: nil, lastFetched: when, ended: nil)
        let ended = SharedBibleLibrary.Entry(id: "owner-ended", snapshot: snapshot, fallbackID: UUID(), shareTitle: "Mom", lastFetched: when, ended: when)
        SharedBibleLibrary.shared.setEntriesForTesting([live, ended])
        defer { SharedBibleLibrary.shared.setEntriesForTesting(nil) }

        let session = LegacySession()
        var opened: SharedBibleLibrary.Entry?
        let section = ViewHost.inReader(List { SharedBiblesSection(open: { opened = $0 }) }, model: model, container: container, session: session)
        #expect(await ViewHost.render(section).isDrawn)
        #expect(opened == nil)
        #expect(await draw(SharedBibleWelcome(id: id)).isDrawn)
        #expect(await draw(LiveShareCaption(id: id)).isDrawn)
        #expect(await draw(KeepSharedBibleSheet(entry: ended), dark: true).isDrawn)
        #expect(await draw(SharedBibleStatusText(entry: live)).isDrawn)
        #expect(await draw(List { LegacyFavoritesList(favorites: snapshot.favoriteList, search: "") }).isDrawn)
        #expect(await draw(List { LegacyFavoritesList(favorites: snapshot.favoriteList, search: "world") }).isLaidOut)

        // Reading it: the keepsake reader marks the shared highlights and notes.
        session.open(live.keepsake, model: model, source: .live(id), favorites: snapshot.favoriteList)
        #expect(await draw(ReaderView(), legacyOpen: session).isDrawn)
        session.close(model: model)
    }
}

extension HostedMoreTests {
    func draw<V: View>(_ view: V, legacyOpen session: LegacySession) async -> HostedView {
        await ViewHost.render(ViewHost.inReader(view, model: model, container: container, session: session))
    }
}
