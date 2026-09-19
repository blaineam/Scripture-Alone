import Foundation
import ScriptureAloneCore

extension CatalogTranslation {
    /// What to call a translation downloaded from the catalogue.
    ///
    /// The engine's own guess comes from the file's `copr.htm`, which for eBible zips is a whole
    /// page of navigation and boilerplate — it produced "Imported Bible" with a wall of text for
    /// its copyright. The catalogue row has the publisher's actual title and a one-line licence,
    /// so when the download came from there, that wins.
    var importIdentity: ImportedTranslationIdentity {
        ImportedTranslationIdentity(
            id: id.uppercased(),
            name: title.isEmpty ? id : title,
            abbreviation: Self.abbreviation(shortTitle: shortTitle, id: id),
            copyright: copyright.isEmpty ? "Source: eBible.org" : copyright,
            license: copyright,
            source: "eBible.org")
    }

    /// A short tag for the toolbar.
    ///
    /// eBible's "shortTitle" is not short — it repeats the full name — so the tag comes from the
    /// title's initials, skipping the words nobody abbreviates. That lands on the abbreviations
    /// these translations are actually known by: World English Bible → WEB, ... British Edition →
    /// WEBBE, American Standard Version (1901) → ASV, Bible in Basic English → BBE.
    private static func abbreviation(shortTitle: String, id: String) -> String {
        let skip: Set<String> = ["in", "of", "the", "and", "a", "an", "with", "for", "to"]
        let words = shortTitle
            .replacingOccurrences(of: "(", with: " ")
            .replacingOccurrences(of: ")", with: " ")
            .split(whereSeparator: { !$0.isLetter && !$0.isNumber })
            .map(String.init)
            .filter { word in
                guard let first = word.first, first.isLetter else { return false }
                return !skip.contains(word.lowercased())
            }
        let initials = words.compactMap(\.first).map(String.init).joined().uppercased()
        if (2...6).contains(initials.count) { return initials }
        let letters = id.filter { $0.isLetter || $0.isNumber }
        return letters.isEmpty ? "IMPORT" : letters.uppercased()
    }
}
