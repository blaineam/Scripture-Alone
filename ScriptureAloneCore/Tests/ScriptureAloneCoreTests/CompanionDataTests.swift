import Foundation
import Testing
@testable import ScriptureAloneCore

/// Verse of the Day selection, the widget snapshot format, deep links, and the data files
/// Tools/build_companion_data.py writes.
@Suite struct DailyVerseTests {
    static let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()

    static func catalog() throws -> DailyVerseCatalog {
        try DailyVerseCatalog(data: Data(contentsOf: root.appending(path: "ScriptureAlone/Shared/DailyVerses.json")))
    }

    static func calendar(_ zone: String) -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: zone)!
        return calendar
    }

    static func date(_ y: Int, _ m: Int, _ d: Int, _ h: Int = 12, _ min: Int = 0, in calendar: Calendar) -> Date {
        calendar.date(from: DateComponents(year: y, month: m, day: d, hour: h, minute: min))!
    }

    @Test func dayNumbersAreCivilDays() {
        #expect(DailyVerseCatalog.dayNumber(year: 2000, month: 1, day: 1) == 0)
        #expect(DailyVerseCatalog.dayNumber(year: 2000, month: 3, day: 1) == 60) // 2000 is a leap year
        #expect(DailyVerseCatalog.dayNumber(year: 2001, month: 1, day: 1) == 366)
        #expect(DailyVerseCatalog.dayNumber(year: 1999, month: 12, day: 31) == -1)
        #expect(DailyVerseCatalog.dayNumber(year: 2026, month: 9, day: 18)
                - DailyVerseCatalog.dayNumber(year: 2026, month: 9, day: 17) == 1)
    }

    @Test(arguments: [1, 2, 7, 60, 364, 365, 366, 400])
    func strideVisitsEveryPassageOncePerCycle(count: Int) {
        let start = DailyVerseCatalog.dayNumber(year: 2026, month: 1, day: 1)
        let picks = (0..<count).map { DailyVerseCatalog.index(forDay: start + $0, count: count) }
        #expect(Set(picks).count == count)
        #expect(picks.allSatisfy { (0..<count).contains($0) })
    }

    @Test func consecutiveDaysJumpAcrossTheList() {
        let count = 365
        let today = DailyVerseCatalog.index(forDay: 9_757, count: count)
        let tomorrow = DailyVerseCatalog.index(forDay: 9_758, count: count)
        #expect(abs(today - tomorrow) > 30)
    }

    @Test func sameLocalDateSamePassageAnywhere() throws {
        let catalog = try Self.catalog()
        let tokyo = Self.calendar("Asia/Tokyo")
        let chicago = Self.calendar("America/Chicago")
        let a = catalog.verse(on: Self.date(2026, 9, 18, 0, 5, in: tokyo), calendar: tokyo)
        let b = catalog.verse(on: Self.date(2026, 9, 18, 23, 55, in: chicago), calendar: chicago)
        #expect(a != nil && a == b)
        // Deterministic: the same instant always maps to the same passage.
        let noon = Self.date(2026, 9, 18, in: chicago)
        #expect(catalog.verse(on: noon, calendar: chicago) == catalog.verse(on: noon, calendar: chicago))
    }

    @Test func passageChangesAtLocalMidnight() throws {
        let catalog = try Self.catalog()
        let chicago = Self.calendar("America/Chicago")
        let before = Self.date(2026, 11, 1, 23, 59, in: chicago)   // DST ends that morning
        let midnight = DailyVerseCatalog.nextMidnight(after: before, calendar: chicago)
        #expect(midnight == Self.date(2026, 11, 2, 0, 0, in: chicago))
        #expect(catalog.verse(on: before, calendar: chicago) != catalog.verse(on: midnight, calendar: chicago))
        #expect(catalog.verse(on: midnight.addingTimeInterval(-1), calendar: chicago) == catalog.verse(on: before, calendar: chicago))
        // Across a 25-hour day the midnight is still the calendar midnight.
        let dstDay = Self.date(2026, 11, 1, 0, 30, in: chicago)
        #expect(DailyVerseCatalog.nextMidnight(after: dstDay, calendar: chicago) == Self.date(2026, 11, 2, 0, 0, in: chicago))
    }

    @Test func pinnedPicksGuardAgainstReordering() throws {
        // If these change, every user's Verse of the Day shifts — do it deliberately.
        let catalog = try Self.catalog()
        let utc = Self.calendar("UTC")
        #expect(catalog.verses.count == 365)
        #expect(DailyVerseCatalog.stride(for: 365) == 139)
        #expect(DailyVerseCatalog.dayNumber(year: 2026, month: 9, day: 18) == 9_757)
        let index = DailyVerseCatalog.index(on: Self.date(2026, 9, 18, in: utc), count: 365, calendar: utc)
        #expect(index == 248)
        #expect(catalog.verse(on: Self.date(2026, 9, 18, in: utc), calendar: utc)?.ref == "45008018-45008018") // Romans 8:18
    }

    @Test func catalogCoversTheCanonInEveryTranslation() throws {
        let catalog = try Self.catalog()
        // The English three, then the big-8 locales' Bibles (docs/localization.md), each read
        // through its own numbering by Tools/build_companion_data.py.
        #expect(catalog.translations == ["ASV", "BSB", "KJV", "CUVS", "BUNGO", "LUT1912", "LSG",
                                         "RVR1909", "KRV", "BLIVRE", "RIV1927"])
        #expect(Set(catalog.verses.map(\.ref)).count == catalog.verses.count)
        var books = Set<BookID>()
        for verse in catalog.verses {
            let range = try #require(verse.range, "bad ref \(verse.ref)")
            books.insert(range.start.book)
            #expect(range.end.key - range.start.key < 3)
            for id in catalog.translations {
                let text = try #require(verse.text[id], "\(verse.ref) missing \(id)")
                #expect(!text.isEmpty && !text.hasPrefix("¶"))
                let scalars = text.unicodeScalars.count
                #expect(verse.redRanges(in: id).allSatisfy { $0.upperBound <= scalars })
            }
        }
        #expect(books.contains(.genesis) && books.contains(.revelation))
        #expect(books.filter { !$0.isNewTestament }.count >= 25)
        #expect(books.filter(\.isNewTestament).count >= 20)
    }

    @Test func redLettersAndFallbacks() throws {
        let catalog = try Self.catalog()
        let way = try #require(catalog.verses.first { $0.ref == "43014006-43014006" })
        let text = way.text(in: "KJV")
        let red = try #require(way.redRanges(in: "KJV").first)
        let scalars = Array(text.unicodeScalars)
        let spoken = String(String.UnicodeScalarView(scalars[red]))
        #expect(spoken.contains("I am the way"))
        #expect(way.text(in: "NOPE") == way.text(in: "ASV"))
        let psalm = try #require(catalog.verses.first { $0.ref == "19023001-19023001" })
        #expect(psalm.redRanges(in: "BSB").isEmpty)
    }

    @Test func abbreviatedReferences() {
        #expect(VerseRange(VerseRef(.psalms, 23, 1)).abbreviatedDisplay == "Ps 23:1")
        #expect(VerseRange(VerseRef(.firstCorinthians, 13, 4), VerseRef(.firstCorinthians, 13, 7)).abbreviatedDisplay == "1 Cor 13:4–7")
        #expect(VerseRange(VerseRef(.genesis, 1, 1), VerseRef(.genesis, 2, 3)).abbreviatedDisplay == "Gen 1:1–2:3")
        #expect(VerseRange(VerseRef(.john, 3, 16)).abbreviatedDisplay == "John 3:16")
    }
}

@Suite struct VerseSnapshotTests {
    static let now = Date(timeIntervalSince1970: 1_790_000_000)

    func counts(_ chapter: ChapterRef) -> Int { chapter == ChapterRef(.john, 3) ? 36 : 30 }

    func snapshot() -> VerseSnapshot {
        let john3 = { (v: Int) in VerseRef(.john, 3, v).key }
        return VerseSnapshot.build(
            favorites: [(VerseRange(VerseRef(.john, 3, 16)), Self.now.addingTimeInterval(-60)),
                        (VerseRange(VerseRef(.psalms, 23, 1), VerseRef(.psalms, 23, 3)), Self.now),
                        (VerseRange(VerseRef(.john, 3, 16)), Self.now.addingTimeInterval(-600))], // duplicate
            highlights: [.init(verseKey: john3(16), color: "yellow", date: Self.now.addingTimeInterval(-100)),
                         .init(verseKey: john3(17), color: "yellow", date: Self.now.addingTimeInterval(-50)),
                         .init(verseKey: john3(18), color: "blue", date: Self.now.addingTimeInterval(-10)),
                         // An older color on the same verse loses to the newer one.
                         .init(verseKey: john3(18), color: "green", date: Self.now.addingTimeInterval(-900))],
            notes: [.init(title: "  ", anchors: [VerseRange(VerseRef(.romans, 8, 1), VerseRef(.romans, 8, 17))], date: Self.now),
                    .init(title: "Sermon", anchors: [VerseRange(VerseRef(.john, 1, 1))], date: Self.now.addingTimeInterval(-5)),
                    .init(title: "No passage", anchors: [], date: Self.now)],
            translation: "ASV",
            generatedAt: Self.now,
            verseCount: counts,
            text: { range in range.start.book == .romans ? String(repeating: "word ", count: 200) : "Text of \(range.display)" })
    }

    @Test func buildsOneItemPerRangeNewestFirst() throws {
        let snapshot = snapshot()
        let favorites = snapshot.items(of: [.favorite])
        #expect(favorites.map(\.reference) == ["Psalms 23:1–3", "John 3:16"])
        let highlights = snapshot.items(of: [.highlight])
        #expect(highlights.map(\.reference) == ["John 3:18", "John 3:16–17"])
        #expect(highlights.map(\.color) == ["blue", "yellow"])
        #expect(highlights[1].date == Self.now.addingTimeInterval(-50))
        let notes = snapshot.items(of: [.note])
        #expect(notes.map(\.noteTitle) == ["Romans 8:1–17", "Sermon"])
        #expect(notes[0].text.count <= VerseSnapshot.maxTextLength + 1 && notes[0].text.hasSuffix("…"))
        #expect(notes[0].startKey == VerseRef(.romans, 8, 1).key && notes[0].endKey == VerseRef(.romans, 8, 17).key)
    }

    @Test func encodesAStableCompactFormat() throws {
        let snapshot = snapshot()
        let data = try snapshot.encoded()
        let json = try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        #expect(Set(json.keys) == ["version", "generatedAt", "translation", "items"])
        #expect(json["version"] as? Int == 1)
        #expect(json["translation"] as? String == "ASV")
        #expect((json["generatedAt"] as? String)?.hasSuffix("Z") == true)
        let first = try #require((json["items"] as? [[String: Any]])?.first)
        #expect(first["kind"] as? String == "favorite")
        #expect(first["range"] as? String == "19023001-19023003")
        #expect(first["startKey"] as? Int == 19_023_001)
        #expect(first["reference"] as? String == "Psalms 23:1–3")
        #expect(first["color"] == nil && first["noteTitle"] == nil)
        #expect(try VerseSnapshot.decode(data) == snapshot)
        // Sorted keys make the file byte-stable, so an unchanged library rewrites identical bytes.
        #expect(try snapshot.encoded() == data)
    }

    @Test func rotationAdvancesBySlotAndNudge() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        let morning = calendar.date(from: DateComponents(year: 2026, month: 9, day: 18, hour: 1))!
        let later = calendar.date(from: DateComponents(year: 2026, month: 9, day: 18, hour: 4))!
        let a = VerseSnapshot.rotationIndex(at: morning, count: 5, calendar: calendar)
        let b = VerseSnapshot.rotationIndex(at: later, count: 5, calendar: calendar)
        #expect(b == (a + 1) % 5)
        #expect(VerseSnapshot.rotationIndex(at: morning, count: 5, nudge: 1, calendar: calendar) == b)
        #expect(VerseSnapshot.rotationIndex(at: morning, count: 0, calendar: calendar) == 0)
    }

    @Test func deepLinksRoundTrip() throws {
        let range = VerseRange(VerseRef(.romans, 8, 28), VerseRef(.romans, 8, 30))
        let url = ScriptureLink.url(for: range)
        #expect(url.absoluteString == "scripturealone://open?ref=45008028-45008030")
        #expect(ScriptureLink.range(from: url) == range)
        #expect(ScriptureLink.range(from: URL(string: "scripturealone://open?ref=43003016")!) == VerseRange(VerseRef(.john, 3, 16)))
        #expect(ScriptureLink.range(from: URL(string: "https://open?ref=43003016")!) == nil)
        #expect(ScriptureLink.range(from: URL(string: "scripturealone://share?ref=43003016")!) == nil)
    }
}

@Suite struct WatchBibleTests {
    @Test func compactWatchDatabaseOpensWithBibleStore() throws {
        let url = DailyVerseTests.root.appending(path: "ScriptureAloneWatch/Resources/ASV-Watch.sqlite")
        let store = try BibleStore(url: url)
        #expect(store.info.abbreviation == "ASV")
        #expect(store.verseCount(ChapterRef(.psalms, 119)) == 176)
        let verses = try store.verses(in: VerseRange(VerseRef(.john, 14, 6)))
        #expect(verses.first?.red.isEmpty == false)
        #expect(try store.verses(in: VerseRange(VerseRef(.john, 11, 35))).first?.text == "Jesus wept.")
        let size = try FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int ?? .max
        #expect(size < 6_000_000, "the watch database should stay small")
    }
}
