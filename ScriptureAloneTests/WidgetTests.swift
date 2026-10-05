import Foundation
import Testing
@testable import ScriptureAloneCore
@testable import Scripture_Alone

/// The Verse of the Day as the widgets and complications show it: the bundled list, the week of
/// timeline entries rolled at each local midnight, the fallback when the reader's translation isn't
/// in the list, and the texts the app hands the widgets for a translation the list can't carry.
/// (`VerseOfDayShared.swift` is compiled into this bundle from the widget extension.)
struct WidgetTests {
    // MARK: The bundled list

    @Test func theAppCarriesTheDailyList() throws {
        let catalog = try #require(DailyVerseLibrary.catalog, "DailyVerses.json is not in the app")
        #expect(catalog.verses.count == 365)
        #expect(Set(catalog.translations).isSuperset(of: ["ASV", "BSB", "KJV", "LUT1912", "CUVS"]))
        for verse in catalog.verses {
            #expect(verse.range != nil, "\(verse.ref) is not a range")
            #expect(!verse.text(in: "ASV").isEmpty, "\(verse.ref) has no ASV text")
        }
    }

    @Test func everyDayOfAYearHasAPassageAndNeighboursDiffer() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/Los_Angeles")!
        var date = calendar.date(from: DateComponents(year: 2026, month: 1, day: 1, hour: 9))!
        var seen: [String] = []
        for _ in 0..<365 {
            let verse = try #require(DailyVerseLibrary.verse(on: date, calendar: calendar))
            if let last = seen.last { #expect(verse.ref != last) }
            seen.append(verse.ref)
            date = calendar.date(byAdding: .day, value: 1, to: date)!
        }
        #expect(Set(seen).count == 365, "a year shows every passage once")
    }

    // MARK: The timeline

    @Test func aWeekOfEntriesOnePerLocalMidnightAcrossADaylightSavingChange() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/New_York")!
        // The Saturday before clocks go back (1 November 2026): one day in the week has 25 hours.
        let now = calendar.date(from: DateComponents(year: 2026, month: 10, day: 30, hour: 15, minute: 42))!
        let entries = VerseOfDayProvider().entries(from: now, calendar: calendar)
        #expect(entries.count == VerseOfDayProvider.days)
        #expect(entries.first?.date == now, "today's passage shows at once")
        let today = calendar.startOfDay(for: now)
        for (index, entry) in entries.enumerated().dropFirst() {
            let parts = calendar.dateComponents([.hour, .minute], from: entry.date)
            #expect(parts.hour == 0 && parts.minute == 0, "entry \(index) is not at local midnight: \(entry.date)")
            #expect(entry.date == calendar.date(byAdding: .day, value: index, to: today))
        }
        // Across the change, the gap between two midnights is 25 hours, not 24.
        #expect(entries[3].date.timeIntervalSince(entries[2].date) == 25 * 3600)
        for entry in entries {
            #expect(entry.verse.ref == DailyVerseLibrary.verse(on: entry.date, calendar: calendar)?.ref)
        }
    }

    @Test func aTranslationTheListLacksFallsBackAndSaysSo() {
        let verse = DailyVerseLibrary.placeholder
        let entry = VerseEntry(date: .now, verse: verse, translation: "IMPORTED-XYZ", label: "XYZ")
        #expect(entry.text == verse.text(in: DailyVerseCatalog.fallbackTranslation))
        #expect(entry.shownTranslation == DailyVerseCatalog.fallbackTranslation, "the label names the text shown")

        let own = VerseEntry(date: .now, verse: verse, translation: "IMPORTED-XYZ", label: "XYZ",
                             own: VerseSnapshot.DailyText(text: "Der Herr ist mein Hirte.", red: []))
        #expect(own.text == "Der Herr ist mein Hirte.")
        #expect(own.shownTranslation == "XYZ")

        let listed = VerseEntry(date: .now, verse: verse, translation: "KJV", label: "")
        #expect(listed.shownTranslation == "KJV")
        #expect(listed.url == ScriptureLink.url(for: VerseRange(VerseRef(.psalms, 23, 1))))
        #expect(listed.reference == "Psalms 23:1" || listed.reference == "Psalm 23:1")
    }

    @Test func accessoryReferencesSplitBookFromVerses() {
        #expect(VerseAccessoryView.split("1 Cor 13:4–7") == ("1 Cor", "13:4–7"))
        #expect(VerseAccessoryView.split("John 3:16") == ("John", "3:16"))
        #expect(VerseAccessoryView.split("Jude") == ("Jude", ""))
    }

    // MARK: Texts the app supplies

    /// A translation with no text in the shipped list.
    struct FakeSource: ChapterTextSource {
        var info: TranslationInfo
        var isSearchable: Bool { false }
        let text: [Int: VerseText]
        func contains(_ chapter: ChapterRef) -> Bool { true }
        func verseCount(_ chapter: ChapterRef) -> Int { 176 }
        func layout(for chapter: ChapterRef) throws -> ChapterLayout { ChapterLayout(blocks: []) }
        func verses(in range: VerseRange) throws -> [VerseText] {
            text.keys.sorted().filter { range.start.key <= $0 && $0 <= range.end.key }.compactMap { text[$0] }
        }
        func search(_ query: String, limit: Int) throws -> [BibleStore.SearchHit] { [] }
    }

    static func fake(id: String, license: String = "Public domain", every text: (VerseRef) -> (String, [NSRange])) -> FakeSource {
        var verses: [Int: VerseText] = [:]
        for verse in DailyVerseLibrary.catalog?.verses ?? [] {
            guard let range = verse.range else { continue }
            var ref = range.start
            while ref <= range.end {
                let (words, red) = text(ref)
                verses[ref.key] = VerseText(ref: ref, text: words, red: red)
                ref = VerseRef(ref.book, ref.chapter, ref.verse + 1)
                if ref.verse > 176 { break }
            }
        }
        return FakeSource(info: TranslationInfo(id: id, name: id, abbreviation: id, copyright: license, license: license),
                          text: verses)
    }

    @Test func ownTextsCoverTwoWeeksWithParagraphMarksDroppedAndRedLettersInScalars() throws {
        // "¶ 😀 Jesus said, “Come.”" — a paragraph mark, then a character outside the BMP before the
        // red span: UTF-16 offsets in, Unicode-scalar offsets out.
        let source = Self.fake(id: "TESTONLY") { ref in
            let words = "¶ 😀 \(ref.verse) said, “Come.”"
            let red = (words as NSString).range(of: "“Come.”")
            return (words, [red])
        }
        let texts = try #require(DailyVerseLibrary.ownTexts(from: source))
        #expect(texts.count >= 13 && texts.count <= 14, "two weeks of days (a passage can repeat)")
        let today = try #require(DailyVerseLibrary.verse(on: .now))
        let daily = try #require(texts[today.ref])
        #expect(!daily.text.contains("¶"))
        #expect(daily.text.hasPrefix("😀 "))
        for range in daily.redRanges {
            let scalars = Array(daily.text.unicodeScalars)
            #expect(String(String.UnicodeScalarView(scalars[range])) == "“Come.”")
        }
    }

    @Test func noOwnTextsForAListedTranslationOrOneThatMayNotBeStored() {
        let listed = Self.fake(id: "KJV") { _ in ("x", []) }
        #expect(DailyVerseLibrary.ownTexts(from: listed) == nil, "the list already carries the KJV")
        let unlisted = Self.fake(id: "TESTONLY") { _ in ("x", []) }
        #expect(DailyVerseLibrary.ownTexts(from: unlisted) != nil)
    }
}
