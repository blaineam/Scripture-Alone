import Foundation
import Testing
@testable import ScriptureAloneCore

/// Runs against the context data the app bundles (built by Tools/build_context.py).
@Suite struct ContextStoreTests {
    static let studyDirectory = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .appending(path: "ScriptureAlone/Resources/Study")

    func store() throws -> ContextStore {
        try ContextStore(url: Self.studyDirectory.appending(path: "Context.sqlite"))
    }

    @Test func acts13And14NameThePlacesOfPaulsFirstJourney() throws {
        let store = try store()
        let names = try [13, 14].flatMap { try store.places(in: ChapterRef(.acts, $0)).map(\.place.name) }
        for expected in ["Antioch", "Cyprus", "Iconium", "Lystra", "Derbe", "Seleucia", "Salamis", "Paphos"] {
            #expect(names.contains(expected), "Acts 13–14 should mention \(expected)")
        }
        let acts13 = try store.places(in: ChapterRef(.acts, 13))
        #expect(acts13.first?.place.name == "Antioch", "places come in order of first mention")
        let iconium = try #require(acts13.first { $0.place.name == "Iconium" })
        #expect(iconium.verses == [VerseRef(.acts, 13, 51).key])
        #expect(iconium.place.modernName == "Konya")
    }

    @Test func jerusalemIsWhereItShouldBe() throws {
        let store = try store()
        let jerusalem = try #require(try store.searchPlaces("Jerusalem").first)
        #expect(abs(jerusalem.latitude - 31.78) < 0.05 && abs(jerusalem.longitude - 35.23) < 0.05)
        #expect(jerusalem.confidenceLevel == .identified)
        let verses = try store.verses(mentioning: jerusalem.id)
        #expect(verses.count == jerusalem.mentions)
        #expect(verses.contains(VerseRef(.acts, 1, 8).key))
        #expect(verses == verses.sorted())
        #expect(try store.prominentPlaces(limit: 5).contains { $0.id == jerusalem.id })
    }

    @Test func chaptersSitInTheirEras() throws {
        let store = try store()
        #expect(try store.time(for: ChapterRef(.genesis, 12))?.era.id == "patriarchs")
        #expect(try store.time(for: ChapterRef(.genesis, 1))?.era.id == "primeval")
        #expect(try store.time(for: ChapterRef(.genesis, 1))?.year == nil)
        let kings = try #require(try store.time(for: ChapterRef(.firstKings, 12)))
        #expect(kings.era.id == "divided")
        #expect(kings.year == -931)
        #expect(kings.yearLabel == "c. 931 BC")
        #expect(try store.time(for: ChapterRef(.romans, 8))?.basis == .written)
        for book in BookID.allCases {
            for chapter in 1...book.chapterCount {
                #expect(try store.time(for: ChapterRef(book, chapter)) != nil, "\(book.name) \(chapter) has no era")
            }
        }
    }

    @Test func erasRunInOrderWithoutGaps() throws {
        let eras = try store().eras()
        #expect(eras.first?.id == "primeval")
        #expect(eras.map(\.id).contains("exile"))
        let dated = eras.filter { $0.start != nil }
        for (a, b) in zip(dated, dated.dropFirst()) {
            #expect(a.end == b.start, "\(a.name) should end where \(b.name) begins")
        }
    }

    @Test func eventsFindTheirChapters() throws {
        let store = try store()
        let events = try store.events(in: ChapterRef(.firstKings, 12))
        #expect(events.map(\.name).contains("The kingdom divides"))
        let all = try store.events()
        #expect(all.count > 60)
        #expect(all.first?.eraID == "primeval")
        #expect(all.last?.eraID == "church")
    }

    @Test func chartsDecodeAndAreSuggestedByBook() throws {
        let store = try store()
        let forKings = try store.charts(for: .firstKings)
        let kingsInfo = try #require(forKings.first { $0.kind == .kings })
        let kings = try kingsInfo.decode(KingsChart.self)
        #expect(kings.israel.first?.name == "Jeroboam I")
        #expect(kings.judah.last?.name == "Zedekiah")
        #expect(kings.judah.first { $0.name == "Josiah" }?.verdict == .good)

        let journeysInfo = try #require(try store.charts(for: .acts).first { $0.kind == .journeys })
        let journeys = try journeysInfo.decode(JourneysChart.self)
        #expect(journeys.journeys.count == 4)
        #expect(journeys.journeys[0].stops.first?.name == "Antioch")
        #expect(journeys.journeys[3].stops.last?.name == "Rome")
        #expect(journeys.journeys[0].refs.range.start == VerseRef(.acts, 13, 1))

        let all = try store.charts()
        #expect(try all.first { $0.kind == .tribes }?.decode(TribesChart.self).tribes.count == 13)
        #expect(try all.first { $0.kind == .feasts }?.decode(FeastsChart.self).feasts.first?.name == "Passover")
        #expect(try store.charts(for: .ruth).isEmpty)
    }

    @Test func basemapDecodes() throws {
        let data = try Data(contentsOf: Self.studyDirectory.appending(path: "Basemap.bin"))
        let map = try Basemap(data: data)
        #expect(map.layers.count == 6)
        let land = try #require(map.layer(.land, .fine))
        #expect(land.rings.count > 50)
        let points = land.rings.flatMap(\.points)
        #expect(points.allSatisfy { $0.x >= map.minLongitude - 0.001 && $0.x <= map.maxLongitude + 0.001 })
        // Jerusalem is on land: some land ring's box covers it.
        #expect(land.rings.contains { ring in
            let xs = ring.points.map(\.x), ys = ring.points.map(\.y)
            return (xs.min()!...xs.max()!).contains(35.23) && (ys.min()!...ys.max()!).contains(31.78)
        })
        #expect(throws: Basemap.DecodeError.self) { try Basemap(data: Data("nope".utf8)) }
        #expect(try store().labels().contains { $0.text == "Dead Sea" })
    }
}
