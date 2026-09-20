import Foundation

/// Which translations the app offers as a built-in choice.
///
/// A reader may import any text they hold a legal copy of — that is their business, and the
/// importer asks no questions beyond "is this a Bible". What is *offered in the app* is a
/// different thing: it is a recommendation, and the app should only recommend what it can stand
/// behind.
///
/// So the English list is an allowlist, decided by name rather than by a rule. Every automatic
/// test was tried and each one either admits something it shouldn't or excludes something it
/// should keep: book count alone admits the Apocrypha, a "literal" name means nothing, and no
/// field in eBible's catalogue describes a translation's textual basis or its tradition.
public enum CatalogCuration {
    /// The English translations the app offers, by eBible's own identifier.
    ///
    /// The standard applied: the complete 66-book Protestant canon, translated from the Hebrew and
    /// Greek, in a tradition a Reformed Baptist reader would recognise as a Bible rather than an
    /// adaptation.
    public static let englishAllowlist: Set<String> = [
        "eng-asv",      // American Standard Version (1901)
        "engasvbt",     // American Standard Version, Byzantine Text
        "engbsb",       // Berean Standard Bible
        "engDBY",       // Darby Translation
        "enggnv",       // Geneva Bible 1599
        "engkjvcpb",    // KJV Cambridge Paragraph Bible
        "eng-kjv2006",  // King James (Authorized) Version
        "englsv",       // Literal Standard Version
        "engmsb",       // Majority Standard Bible
        "engnet",       // NET Bible
        "engwebster",   // Noah Webster Bible
        "engwebp",      // World English Bible
        "engwebpb",     // World English Bible, British Edition
        "eng-web",      // World English Bible, Classic
        "engwebu",      // World English Bible, Updated
        "engylt",       // Young's Literal Translation
    ]

    /// English editions deliberately left out, and why. Kept as documentation rather than as a
    /// blocklist — the allowlist above is what the code consults — so that a future reader can see
    /// these were considered and decided, not missed.
    ///
    /// - Apocrypha, so not the Protestant canon: `engDRA` (Douay-Rheims 1899),
    ///   `eng-kjv` (KJV + Apocrypha), `eng-rv` (Revised Version with Apocrypha),
    ///   `eng-webbe` (WEB British with Deuterocanon).
    /// - Translated from the Latin Vulgate rather than the Hebrew and Greek:
    ///   `engwyc2017`, `engwyc2018` (Wycliffe's Bible with Modern Spelling).
    /// - A different tradition than this app serves: `engojb` (The Orthodox Jewish Bible),
    ///   `engwmb`, `engwmbb` (World Messianic Bible and its British edition).
    /// - Simplified, adapted, or built for translators rather than for reading:
    ///   `engBBE` (Bible in Basic English), `engfbv` (Free Bible Version),
    ///   `eng-t4t` (Translation for Translators), `engULB` (Unlocked Literal Bible).
    public static let englishExcluded: Set<String> = [
        "engDRA", "eng-kjv", "eng-rv", "eng-webbe",
        "engwyc2017", "engwyc2018",
        "engojb", "engwmb", "engwmbb",
        "engBBE", "engfbv", "eng-t4t", "engULB",
    ]

    /// Whether the app offers this translation as a curated choice.
    ///
    /// English is decided by name. No other language is: nobody here can assess the textual basis
    /// of a translation in a language they do not read, and pretending otherwise would be worse
    /// than saying so. Those are offered separately and labelled as uncurated.
    public static func isCurated(_ translation: CatalogTranslation) -> Bool {
        guard translation.isCompleteCanon else { return false }
        guard translation.languageCode.lowercased() == "eng" else { return false }
        return englishAllowlist.contains(translation.id)
    }

    /// A complete Bible in a language the app cannot vouch for, one way or the other.
    public static func isUncurated(_ translation: CatalogTranslation) -> Bool {
        translation.isCompleteCanon && translation.languageCode.lowercased() != "eng"
    }
}
