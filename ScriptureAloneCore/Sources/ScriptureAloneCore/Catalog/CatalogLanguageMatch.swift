import Foundation

/// Picks the translations worth showing someone first, from their own device settings.
///
/// eBible identifies languages by ISO 639-3 ("eng", "spa", "cmn"); Apple hands us BCP-47 tags
/// ("en-US", "zh-Hans-CN"). The bridge is `Locale.LanguageCode.identifier(.alpha3)`, so a device
/// set to Spanish surfaces Spanish Bibles without anyone choosing a language by hand.
public enum CatalogLanguageMatch {
    /// The reader's languages in ISO 639-3, most-preferred first, de-duplicated.
    ///
    /// Reads the *preferred language list*, not just the current locale: someone whose phone is
    /// in English but who also reads Tagalog has both, in their own order of preference.
    public static func preferredLanguageCodes(
        _ identifiers: [String] = Locale.preferredLanguages
    ) -> [String] {
        var seen = Set<String>()
        var out: [String] = []
        for identifier in identifiers {
            let language = Locale.Language(identifier: identifier)
            guard let code = language.languageCode else { continue }
            // Already three letters (ISO 639-3) or convertible from two.
            guard let alpha3 = code.identifier(.alpha3)?.lowercased() else { continue }
            guard !alpha3.isEmpty, seen.insert(alpha3).inserted else { continue }
            out.append(alpha3)
        }
        return out
    }

    /// The written form the reader expects, when their language has more than one — "Hans" or
    /// "Hant" for Chinese. Nil when the tag doesn't say and the language doesn't need it.
    public static func preferredScript(_ identifier: String = Locale.preferredLanguages.first ?? "") -> String? {
        let language = Locale.Language(identifier: identifier)
        if let script = language.script?.identifier, !script.isEmpty { return script }
        // A bare "zh" means neither form in particular; Locale can still resolve a default.
        guard let code = language.languageCode?.identifier else { return nil }
        let maximal = Locale.Language(identifier: code).maximalIdentifier
        return Locale.Language(identifier: maximal).script?.identifier
    }

    /// Splits the catalogue into what this reader probably wants and everything else.
    ///
    /// Both halves are ordered the same way — complete Bibles before New Testaments, then by
    /// size — but the first half is also ordered by how high the language sits in the reader's
    /// own preference list, so a bilingual device sees its first language first.
    public static func split(
        _ entries: [CatalogTranslation],
        preferred codes: [String] = preferredLanguageCodes(),
        script: String? = preferredScript()
    ) -> (mine: [CatalogTranslation], other: [CatalogTranslation]) {
        // First position wins. `preferredLanguageCodes()` never repeats a code, but a caller may,
        // and `uniqueKeysWithValues:` would trap on it.
        let rank = Dictionary(codes.enumerated().map { ($1, $0) }, uniquingKeysWith: { first, _ in first })
        var mine: [CatalogTranslation] = []
        var other: [CatalogTranslation] = []
        for entry in entries {
            if rank[entry.languageCode.lowercased()] != nil { mine.append(entry) } else { other.append(entry) }
        }
        mine.sort { a, b in
            let ra = rank[a.languageCode.lowercased()] ?? .max
            let rb = rank[b.languageCode.lowercased()] ?? .max
            if ra != rb { return ra < rb }
            // Within one language, the reader's own script wins — a Simplified Chinese device
            // should not have to scroll past Traditional editions to find its own.
            if let script {
                let sa = entry(a, matches: script), sb = entry(b, matches: script)
                if sa != sb { return sa }
            }
            return isBigger(a, b)
        }
        other.sort(by: isBigger)
        return (mine, other)
    }

    /// Everything, ordered with the reader's languages first. For a single list rather than
    /// two sections.
    public static func ordered(
        _ entries: [CatalogTranslation],
        preferred codes: [String] = preferredLanguageCodes(),
        script: String? = preferredScript()
    ) -> [CatalogTranslation] {
        let parts = split(entries, preferred: codes, script: script)
        return parts.mine + parts.other
    }

    private static func entry(_ entry: CatalogTranslation, matches script: String) -> Bool {
        entry.script.caseInsensitiveCompare(script) == .orderedSame
    }

    /// Complete Bibles first, then the most complete text, then a stable name order.
    private static func isBigger(_ a: CatalogTranslation, _ b: CatalogTranslation) -> Bool {
        if a.bookCount != b.bookCount { return a.bookCount > b.bookCount }
        if a.verseCount != b.verseCount { return a.verseCount > b.verseCount }
        return a.title.localizedCaseInsensitiveCompare(b.title) == .orderedAscending
    }
}
