import Testing
@testable import ScriptureAloneCore

/// The allowlist is a promise, so it is pinned by tests rather than left to drift.
struct CatalogCurationTests {
    func entry(_ id: String, language: String = "eng", ot: Int = 39, nt: Int = 27) -> CatalogTranslation {
        CatalogTranslation(id: id, languageCode: language, languageName: language,
                           languageNameInEnglish: language, title: id, shortTitle: id,
                           copyright: "public domain", isRedistributable: true,
                           otBooks: ot, ntBooks: nt, otVerses: 23145, ntVerses: 7957,
                           textDirection: "ltr", script: "Latin")
    }

    @Test func theAllowlistIsOffered() {
        for id in ["eng-asv", "engbsb", "engylt", "englsv", "engnet", "eng-kjv2006", "engwebp"] {
            #expect(CatalogCuration.isCurated(entry(id)), "\(id) should be offered")
        }
    }

    /// Each of these was considered and excluded by name; a regression that admits one is a
    /// promise broken, not a cosmetic change.
    @Test func theExcludedAreNotOffered() {
        for id in CatalogCuration.englishExcluded {
            #expect(!CatalogCuration.isCurated(entry(id)), "\(id) must not be offered")
        }
    }

    @Test func apocryphaEditionsAreExcludedByName() {
        for id in ["engDRA", "eng-kjv", "eng-rv", "eng-webbe"] {
            #expect(CatalogCuration.englishExcluded.contains(id))
            #expect(!CatalogCuration.isCurated(entry(id)))
        }
    }

    @Test func aNewTestamentIsNeverCurated() {
        #expect(!CatalogCuration.isCurated(entry("engbsb", ot: 0, nt: 27)))
        #expect(!CatalogCuration.isUncurated(entry("spaRV", language: "spa", ot: 0, nt: 27)))
    }

    /// Other languages are offered, but as eBible's list rather than as the app's recommendation.
    @Test func otherLanguagesAreUncuratedNotHidden() {
        let spanish = entry("spaRV", language: "spa")
        #expect(!CatalogCuration.isCurated(spanish))
        #expect(CatalogCuration.isUncurated(spanish))
    }

    @Test func englishNotOnTheListIsOfferedNeitherWay() {
        let unknown = entry("engSomethingNew")
        #expect(!CatalogCuration.isCurated(unknown))
        #expect(!CatalogCuration.isUncurated(unknown), "English is decided by the allowlist only")
    }

    @Test func theTwoListsNeverOverlap() {
        #expect(CatalogCuration.englishAllowlist.isDisjoint(with: CatalogCuration.englishExcluded))
    }
}
