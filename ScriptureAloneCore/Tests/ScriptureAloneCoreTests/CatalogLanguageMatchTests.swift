import Foundation
import Testing
@testable import ScriptureAloneCore

struct CatalogLanguageMatchTests {
    static func entry(_ id: String, _ language: String, books: Int = 66, verses: Int = 31102,
                      script: String = "Latin", title: String? = nil) -> CatalogTranslation {
        CatalogTranslation(id: id, languageCode: language, languageName: language,
                           languageNameInEnglish: language, title: title ?? id, shortTitle: id,
                           copyright: "Public Domain", isRedistributable: true,
                           otBooks: books > 27 ? 39 : 0, ntBooks: min(books, 27),
                           otVerses: books > 27 ? verses - 7957 : 0,
                           ntVerses: books > 27 ? 7957 : verses,
                           textDirection: "ltr", script: script)
    }

    @Test func mapsAppleTagsToISO639_3() {
        #expect(CatalogLanguageMatch.preferredLanguageCodes(["en-US"]) == ["eng"])
        #expect(CatalogLanguageMatch.preferredLanguageCodes(["es-MX", "en-US"]) == ["spa", "eng"])
        #expect(CatalogLanguageMatch.preferredLanguageCodes(["de-DE"]) == ["deu"])
        #expect(CatalogLanguageMatch.preferredLanguageCodes(["ja-JP"]) == ["jpn"])
    }

    @Test func keepsThePreferenceOrderAndDropsDuplicates() {
        let codes = CatalogLanguageMatch.preferredLanguageCodes(["en-GB", "en-US", "fr-FR"])
        #expect(codes == ["eng", "fra"])
    }

    @Test func ignoresTagsWithNoLanguage() {
        #expect(CatalogLanguageMatch.preferredLanguageCodes(["", "und", "en-US"]).contains("eng"))
    }

    @Test func putsTheReadersLanguageFirst() {
        let all = [Self.entry("spaRV", "spa"), Self.entry("engWEB", "eng"), Self.entry("fraLSG", "fra")]
        let parts = CatalogLanguageMatch.split(all, preferred: ["eng"], script: "Latin")
        #expect(parts.mine.map(\.id) == ["engWEB"])
        #expect(Set(parts.other.map(\.id)) == ["spaRV", "fraLSG"])
    }

    @Test func ordersABilingualDeviceByItsOwnPreference() {
        let all = [Self.entry("fraLSG", "fra"), Self.entry("engWEB", "eng"), Self.entry("spaRV", "spa")]
        let mine = CatalogLanguageMatch.split(all, preferred: ["spa", "eng"], script: nil).mine
        #expect(mine.map(\.id) == ["spaRV", "engWEB"])
    }

    @Test func completeBiblesBeforeNewTestaments() {
        let all = [Self.entry("engNT", "eng", books: 27, verses: 7957),
                   Self.entry("engFull", "eng")]
        let mine = CatalogLanguageMatch.split(all, preferred: ["eng"], script: nil).mine
        #expect(mine.map(\.id) == ["engFull", "engNT"])
    }

    /// A Simplified Chinese device should not have to scroll past Traditional editions.
    @Test func prefersTheReadersScriptWithinOneLanguage() {
        let all = [Self.entry("cmnHant", "cmn", script: "Hant"), Self.entry("cmnHans", "cmn", script: "Hans")]
        let mine = CatalogLanguageMatch.split(all, preferred: ["cmn"], script: "Hans").mine
        #expect(mine.map(\.id) == ["cmnHans", "cmnHant"])

        let traditional = CatalogLanguageMatch.split(all, preferred: ["cmn"], script: "Hant").mine
        #expect(traditional.map(\.id) == ["cmnHant", "cmnHans"])
    }

    @Test func readsAScriptFromTheTag() {
        #expect(CatalogLanguageMatch.preferredScript("zh-Hans-CN") == "Hans")
        #expect(CatalogLanguageMatch.preferredScript("zh-Hant-TW") == "Hant")
        #expect(CatalogLanguageMatch.preferredScript("en-US") == "Latn")
    }

    /// An unmatched locale must still show the whole catalogue, not an empty screen.
    @Test func everythingSurvivesAnUnknownLocale() {
        let all = [Self.entry("engWEB", "eng"), Self.entry("spaRV", "spa")]
        let parts = CatalogLanguageMatch.split(all, preferred: ["xyz"], script: nil)
        #expect(parts.mine.isEmpty)
        #expect(parts.other.count == 2)
        #expect(CatalogLanguageMatch.ordered(all, preferred: ["xyz"], script: nil).count == 2)
    }

    @Test func orderedIsPreferredThenTheRest() {
        let all = [Self.entry("spaRV", "spa"), Self.entry("engWEB", "eng")]
        #expect(CatalogLanguageMatch.ordered(all, preferred: ["eng"], script: nil).map(\.id)
                == ["engWEB", "spaRV"])
    }

    /// A caller repeating a code must not crash; the code keeps its first, highest position.
    @Test func aRepeatedPreferredCodeKeepsItsFirstPosition() {
        let all = [Self.entry("engWEB", "eng"), Self.entry("spaRV", "spa")]
        let parts = CatalogLanguageMatch.split(all, preferred: ["spa", "eng", "spa"], script: nil)
        #expect(parts.mine.map(\.id) == ["spaRV", "engWEB"])
        #expect(parts.other.isEmpty)
    }
}
