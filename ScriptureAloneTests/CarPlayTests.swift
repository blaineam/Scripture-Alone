#if os(iOS)
import CarPlay
import Foundation
import Testing
import UIKit
import ScriptureAloneCore
@testable import Scripture_Alone

/// What the CarPlay screens show and do. The lists are plain values (`CarPlayCatalog`), the templates are built from
/// them and inspected here; the car itself is the one thing a unit test can't be.
@MainActor
struct CarPlayCatalogTests {
    private func titles(_ entries: [CarPlayEntry]) -> [String] { entries.map(\.title) }

    private func flatten(_ entries: [CarPlayEntry]) -> [CarPlayEntry] {
        entries.flatMap { entry -> [CarPlayEntry] in
            if case .open(_, let inner) = entry.action { return flatten(inner) }
            return [entry]
        }
    }

    @Test func everyListFitsTheLimitWhateverItIs() {
        for limit in [2, 5, 12, 100] {
            func check(_ entries: [CarPlayEntry]) {
                #expect(entries.count <= limit)
                for case .open(_, let inner) in entries.map(\.action) { check(inner) }
            }
            check(CarPlayCatalog.testaments(limit: limit))
        }
    }

    @Test func everyChapterOfTheBibleIsReachableExactlyOnce() {
        let chapters = flatten(CarPlayCatalog.testaments(limit: 12)).compactMap { entry -> ChapterRef? in
            if case .playChapter(let chapter) = entry.action { return chapter }
            return nil
        }
        #expect(chapters.count == 1189)
        #expect(Set(chapters).count == 1189)
    }

    @Test func booksAreFoldersOfChaptersAndSingleChapterBooksPlayAtOnce() throws {
        let psalms = CarPlayCatalog.book(.psalms, limit: 12)
        guard case .open(_, let groups) = psalms.action else { Issue.record("Psalms is a folder"); return }
        // 150 chapters is 13 runs of 12, and 13 is too many for a list of 12: two folders of runs.
        #expect(groups.map(\.title) == ["1 – 144", "145 – 150"])
        guard case .open(_, let runs) = groups[0].action else { Issue.record("a folder of runs"); return }
        #expect(runs.count == 12 && runs.first?.title == "1 – 12" && runs.last?.title == "133 – 144")
        let jude = CarPlayCatalog.book(.jude)
        #expect(jude.action == .playChapter(ChapterRef(.jude, 1)))
        let john = CarPlayCatalog.book(.john, limit: 30)
        guard case .open(_, let chapters) = john.action else { Issue.record("John is a folder"); return }
        #expect(chapters.count == 21 && chapters[2].action == .playChapter(ChapterRef(.john, 3)))
    }

    @Test func testamentsSplitAtMatthew() {
        let both = CarPlayCatalog.testaments(limit: 100)
        #expect(both.count == 2)
        guard case .open(_, let old) = both[0].action, case .open(_, let new) = both[1].action else { Issue.record("two folders"); return }
        #expect(old.count == 39 && new.count == 27)
        #expect(old.first?.title == BookID.genesis.name && new.last?.title == BookID.revelation.name)
    }

    @Test func listenOffersTodayAndWhereYouLeftOff() {
        let rows = CarPlayCatalog.listen(today: nil, location: ChapterRef(.john, 3))
        #expect(rows.map(\.action) == [.playToday, .playContinue])
        #expect(rows[1].detail == ChapterRef(.john, 3).display)
        #expect(rows[0].detail?.isEmpty == false, "with no plan the row says what to do")
    }

    @Test func favoritesCarryTheirRanges() {
        let a = VerseRange(VerseRef(.john, 3, 16))
        let b = VerseRange(VerseRef(.psalms, 23, 1), VerseRef(.psalms, 23, 3))
        let rows = CarPlayCatalog.favorites([a, b], display: { $0.display })
        #expect(rows.map(\.action) == [.playRange(a), .playRange(b)])
        #expect(CarPlayCatalog.favorites([], display: { $0.display }).isEmpty)
    }
}

@MainActor
struct CarPlayWiringTests {
    @Test func theCarGetsTheCarPlaySceneAndWindowsDoNot() {
        #expect(FamilyShareAppDelegate.isCarPlay(.carTemplateApplication))
        #expect(!FamilyShareAppDelegate.isCarPlay(.windowApplication))
        #expect(FamilyShareAppDelegate.sceneDelegateClass(for: .carTemplateApplication) === CarPlaySceneDelegate.self)
        #expect(FamilyShareAppDelegate.sceneDelegateClass(for: .windowApplication) === FamilyShareSceneDelegate.self)
    }

    @Test func aCarRouteIsRecognised() {
        #expect(CarAudio.isCar([.builtInSpeaker, .carAudio]))
        #expect(!CarAudio.isCar([.builtInSpeaker, .headphones, .bluetoothA2DP]))
    }

    @Test func theAppDeclaresTheCarPlayScene() throws {
        let manifest = try #require(Bundle.main.object(forInfoDictionaryKey: "UIApplicationSceneManifest") as? [String: Any])
        let configurations = try #require(manifest["UISceneConfigurations"] as? [String: Any])
        let car = try #require((configurations["CPTemplateApplicationSceneSessionRoleApplication"] as? [[String: Any]])?.first)
        #expect((car["UISceneDelegateClassName"] as? String)?.hasSuffix("CarPlaySceneDelegate") == true)
    }
}

@MainActor
@Suite(.serialized)
struct CarPlayImportedBibleTests {
    private static let usfm = """
    \\id JHN Imported fixture
    \\h John
    \\toc1 The Gospel of John
    \\mt1 John
    \\c 3
    \\p
    \\v 16 For God so loved the world, that he gave his only begotten Son, that whosoever believeth on him should not perish, but have eternal life.
    \\v 17 For God sent not the Son into the world to judge the world; but that the world should be saved through him.
    \\c 4
    \\p
    \\v 1 When therefore the Lord knew how that the Pharisees had heard that Jesus was making and baptizing more disciples than John.
    """

    @Test func aBibleTheReaderImportedIsReadInTheCar() throws {
        let directory = FileManager.default.temporaryDirectory.appending(path: "car-imports-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let source = directory.appending(path: "fixture.zip")
        try ZipArchive.write([ZipArchive.Entry(name: "44-JHNfix.usfm", data: Data(Self.usfm.utf8))]).write(to: source)
        let identity = ImportedTranslationIdentity(id: "IMPORT-CAR", name: "Car Fixture", abbreviation: "CAR", copyright: "Public domain (test)")
        _ = try BibleFileImporter().importBible(at: source, as: identity, into: directory)
        let library = ImportedLibrary(directory: directory)
        #expect(library.entries.map(\.info.id) == ["IMPORT-CAR"])

        // The reader chose it last time: the car's reader comes up on it, not on the default.
        let defaults = UserDefaults.standard
        let saved = defaults.string(forKey: "translation")
        defaults.set("IMPORT-CAR", forKey: "translation")
        defer { if let saved { defaults.set(saved, forKey: "translation") } else { defaults.removeObject(forKey: "translation") } }
        let listen = ListenController.shared
        listen.stop()
        listen.speaksSilentlyForTesting = true
        defer { listen.stop(); listen.speaksSilentlyForTesting = false }
        let plans = PlanStore(directory: directory.appending(path: "plans"), bundled: [])
        plans.schedulesReminders = false
        let player = CarPlayPlayer(plans: plans, library: library)
        #expect(player.model.translations.contains { $0.id == "IMPORT-CAR" })
        #expect(player.model.translationID == "IMPORT-CAR")
        #expect(player.perform(.playChapter(ChapterRef(.john, 3))) == .started)
        #expect(listen.isListening(in: player.model))
    }
}

@MainActor
@Suite(.serialized)
struct CarPlayTemplateTests {
    private func store() -> PlanStore {
        let s = PlanStore(directory: FileManager.default.temporaryDirectory.appending(path: "plans-\(UUID().uuidString)"), bundled: [])
        s.schedulesReminders = false
        return s
    }

    @Test func playingARowStartsListeningAndAnEmptyPlanAnswers() {
        let listen = ListenController.shared
        listen.stop()
        listen.speaksSilentlyForTesting = true
        defer { listen.stop(); listen.speaksSilentlyForTesting = false }
        let plans = store()
        let player = CarPlayPlayer(plans: plans)
        #expect(player.perform(.playToday) != .started, "no plan, nothing to read")
        #expect(player.perform(.playChapter(ChapterRef(.jude, 1))) == .started)
        #expect(listen.isListening(in: player.model))
        plans.start(PlanCatalog.book(.john))
        #expect(player.perform(.playToday) == .started)
        #expect(player.perform(.playRange(VerseRange(VerseRef(.john, 3, 16), VerseRef(.john, 3, 17)))) == .started)
        #expect(player.perform(.playContinue) == .started)
    }

    @Test func speedStepsUpAndWraps() {
        let listen = ListenController.shared
        let saved = listen.speed
        defer { listen.speed = saved }
        let player = CarPlayPlayer(plans: store())
        listen.speed = 1
        player.cycleSpeed()
        #expect(listen.speed == 1.25)
        listen.speed = 2
        player.cycleSpeed()
        #expect(listen.speed == 0.75)
        listen.speed = 1.75
        player.cycleSpeed()
        #expect(listen.speed == 2)
    }
}
#endif
