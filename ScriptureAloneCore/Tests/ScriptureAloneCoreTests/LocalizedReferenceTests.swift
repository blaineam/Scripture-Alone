import Foundation
import Testing
@testable import ScriptureAloneCore

/// Passages typed in the big-8 languages (docs/localization.md). The language is passed explicitly
/// rather than set with `BookNames.use`, so these can run alongside the English parser tests.
@Suite struct LocalizedReferenceTests {
    func parsed(_ text: String, _ language: String?) -> (BookID, Int, Int?)? {
        ReferenceParser.parse(text, language: language).map { ($0.book, $0.startChapter, $0.startVerse) }
    }

    @Test func nativeNamesInEachLanguage() {
        let cases: [(String, String, BookID, Int, Int?)] = [
            ("Jean 3:16", "fr", .john, 3, 16),
            ("Psaumes 51:12", "fr", .psalms, 51, 12),
            ("Génesis 1:1", "es", .genesis, 1, 1),
            ("San Mateo 5:3", "es", .matthew, 5, 3),
            ("Mateo 5", "es", .matthew, 5, nil),
            ("1. Mose 1,1", "de", .genesis, 1, 1),
            ("Johannes 3:16", "de", .john, 3, 16),
            ("2. Korinther 5:17", "de", .secondCorinthians, 5, 17),
            ("João 3:16", "pt", .john, 3, 16),
            ("Giovanni 3:16", "it", .john, 3, 16),
            ("约翰福音3:16", "zh-Hans", .john, 3, 16),
            ("约翰福音 3章16节", "zh-Hans", .john, 3, 16),
            ("요한복음 3:16", "ko", .john, 3, 16),
            ("요한복음 3장 16절", "ko", .john, 3, 16),
            ("ヨハネ傳福音書3章16節", "ja", .john, 3, 16),
            ("ヨハネによる福音書 3：16", "ja", .john, 3, 16),   // the modern name, full-width colon
            ("民数記 6:24", "ja", .numbers, 6, 24),
        ]
        for (text, language, book, chapter, verse) in cases {
            let result = parsed(text, language)
            #expect(result?.0 == book && result?.1 == chapter && result?.2 == verse, "\(text) [\(language)] → \(String(describing: result))")
        }
    }

    @Test func standardAbbreviations() {
        #expect(parsed("Jn 3:16", "fr")?.0 == .john)
        #expect(parsed("Ps 23", "fr")?.0 == .psalms)
        #expect(parsed("Hch 2:38", "es")?.0 == .acts)
        #expect(parsed("Offb 21:4", "de")?.0 == .revelation)
        #expect(parsed("创 1:1", "zh-Hans")?.0 == .genesis)
        #expect(parsed("林前 13:4", "zh-Hans")?.0 == .firstCorinthians)
        #expect(parsed("창 1:1", "ko")?.0 == .genesis)
        #expect(parsed("고전 13:4", "ko")?.0 == .firstCorinthians)
        #expect(parsed("1コリ 13:4", "ja")?.0 == .firstCorinthians)
    }

    @Test func anAmbiguousAbbreviationMeansTheReadersOwn() {
        // "Es" is Isaiah (Ésaïe) in French and Exodus (Esodo) in Italian.
        #expect(parsed("Es 53:5", "fr")?.0 == .isaiah)
        #expect(parsed("Es 20:3", "it")?.0 == .exodus)
    }

    @Test func englishStillWorksEverywhere() {
        #expect(parsed("jn 3 16", "fr")?.0 == .john)
        #expect(parsed("Rom 8:28", "ko")?.0 == .romans)
        #expect(parsed("jn 3 16", nil)?.0 == .john)
    }

    @Test func otherLanguagesUnambiguousNamesWorkToo() {
        // A Spanish reader typing a French reference still lands on it.
        #expect(parsed("Jean 3:16", "es")?.0 == .john)
    }

    @Test func germanCommaListsStillSplit() {
        let list = ReferenceParser.parseList("Joh 3,16; Röm 8,28", language: "de")
        #expect(list.map(\.book) == [.john, .romans])
        #expect(list.map(\.startVerse) == [16, 28])
    }

    @Test func listsWithFullWidthSeparators() {
        let list = ReferenceParser.parseList("罗马书 8:28；约翰福音 3:16", language: "zh-Hans")
        #expect(list.map(\.book) == [.romans, .john])
    }

    @Test func namesComeFromEachLanguagesBible() {
        #expect(BookNames.name(.john, in: "fr") == "Jean")
        #expect(BookNames.name(.john, in: "zh-Hans") == "约翰福音")
        #expect(BookNames.name(.firstChronicles, in: "de") == "1. Chronik")   // the source misspells it
        #expect(BookNames.name(.john, in: nil) == nil)
        #expect(BookNames.tableKey(for: "zh-Hant-TW") == nil)
        #expect(BookNames.tableKey(for: "pt-BR") == "pt")
        #expect(BookNames.tableKey(for: "fr-CA") == "fr")
    }
}
