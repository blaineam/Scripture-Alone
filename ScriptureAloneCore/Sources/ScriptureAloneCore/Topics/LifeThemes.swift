import Foundation

/// A heading in the Topics directory — "Worry & Hard Feelings", "Who God Is".
public struct LifeThemeGroup: Hashable, Sendable, Identifiable, Codable {
    public let id: String
    /// English; the catalog key for `localizedName`.
    public let name: String

    /// The name in the language the app is running in.
    public var localizedName: String { LifeThemeCatalog.localized(name) }
}

/// Something a reader may be going through — anxiety, grief, a new job — and passages that speak
/// to it, curated in `Data/topics/life-themes.json` and built by `Tools/build_topics.py`.
///
/// Passages are references only (KJV keys, the numbering everything in the app is stored in): the
/// words always come from the translation being read, so a theme works the same in a bundled,
/// imported, packaged or online translation, in any language.
public struct LifeTheme: Hashable, Sendable, Identifiable {
    /// Stable: never renamed, so anything that remembers a theme keeps finding it.
    public let id: String
    public let group: String
    /// English; the catalog key for `localizedName`.
    public let name: String
    /// English; the catalog key for `localizedDescription`.
    public let description: String
    /// English search words — "worried", "nervous", "panic".
    public let synonyms: [String]
    /// The passages, in the order they were chosen (the strongest first).
    public let passages: [VerseRange]
    /// Nave's Topical Bible topics that cover the same ground, by name (`TopicalIndex`).
    public let naveTopics: [String]

    public init(id: String, group: String, name: String, description: String, synonyms: [String],
                passages: [VerseRange], naveTopics: [String] = []) {
        self.id = id
        self.group = group
        self.name = name
        self.description = description
        self.synonyms = synonyms
        self.passages = passages
        self.naveTopics = naveTopics
    }

    public var localizedName: String { LifeThemeCatalog.localized(name) }
    public var localizedDescription: String { LifeThemeCatalog.localized(description) }

    /// Search words in the language the app is running in. The catalog holds each theme's as one
    /// comma-separated string, keyed by the English list.
    public var localizedSynonyms: [String] {
        let english = synonyms.joined(separator: ", ")
        let localized = LifeThemeCatalog.localized(english)
        guard localized != english else { return [] }
        return localized.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
    }
}

/// The curated life themes, from `LifeThemes.json` in this package's resources.
public struct LifeThemeCatalog: Sendable {
    public let groups: [LifeThemeGroup]
    /// In directory order: group by group, as the source file lists them.
    public let themes: [LifeTheme]

    /// The bundled catalog. Empty only if the resource is missing, which the tests forbid.
    public static let shared: LifeThemeCatalog = {
        guard let url = Bundle.module.url(forResource: "LifeThemes", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let catalog = try? LifeThemeCatalog(data: data) else { return LifeThemeCatalog(groups: [], themes: []) }
        return catalog
    }()

    public init(groups: [LifeThemeGroup], themes: [LifeTheme]) {
        self.groups = groups
        self.themes = themes
    }

    private struct File: Decodable {
        struct Theme: Decodable {
            let id, group, name, description: String
            let synonyms: [String]
            let refs: [String]
            let nave: [String]?
        }
        let version: Int
        let groups: [LifeThemeGroup]
        let themes: [Theme]
    }

    public init(data: Data) throws {
        let file = try JSONDecoder().decode(File.self, from: data)
        groups = file.groups
        themes = file.themes.map { theme in
            LifeTheme(id: theme.id, group: theme.group, name: theme.name, description: theme.description,
                      synonyms: theme.synonyms, passages: theme.refs.compactMap(VerseRange.init(storageString:)),
                      naveTopics: theme.nave ?? [])
        }
    }

    public func theme(id: String) -> LifeTheme? { themes.first { $0.id == id } }
    public func themes(in group: LifeThemeGroup) -> [LifeTheme] { themes.filter { $0.group == group.id } }

    /// Themes a search speaks to, best first: "anxious", "worried sick", "grief", "不安".
    ///
    /// Matches a theme's name and search words, in English and in the app's language. A whole
    /// word is the strongest match; then a word being typed ("anxi"); then a phrase inside what was
    /// typed ("I feel so alone" finds Loneliness). Nothing below two characters, or three in an
    /// alphabet where two letters are rarely a word.
    public func search(_ query: String, limit: Int = 3) -> [LifeTheme] {
        let needle = TopicSearch.normalize(query)
        guard needle.count >= TopicSearch.minimumLength(needle) else { return [] }
        let scored = themes.enumerated().compactMap { index, theme -> (score: Int, index: Int, theme: LifeTheme)? in
            let terms = [theme.name, theme.localizedName] + theme.synonyms + theme.localizedSynonyms
            let score = terms.map { TopicSearch.score(TopicSearch.normalize($0), against: needle) }.max() ?? 0
            return score > 0 ? (score, index, theme) : nil
        }
        return scored.sorted { ($0.score, -$0.index) > ($1.score, -$1.index) }.prefix(limit).map(\.theme)
    }

    /// A catalog string in the app's language, or the English it is keyed by.
    static func localized(_ english: String) -> String {
        Bundle.module.localizedString(forKey: english, value: english, table: nil)
    }
}

/// How the Topics directory matches what someone types against names and search words. Shared by
/// the life themes and Nave's index so the two rank alike.
public enum TopicSearch {
    /// Lowercased, accents and width folded, apostrophes dropped ("God’s" → "gods"), runs of
    /// anything that isn't a letter or digit made one space.
    public static func normalize(_ text: String) -> String {
        let folded = text.folding(options: [.caseInsensitive, .diacriticInsensitive, .widthInsensitive], locale: nil)
            .replacingOccurrences(of: "’", with: "").replacingOccurrences(of: "'", with: "")
        var result = ""
        var pendingSpace = false
        for character in folded {
            if character.isLetter || character.isNumber {
                if pendingSpace, !result.isEmpty { result.append(" ") }
                pendingSpace = false
                result.append(character)
            } else {
                pendingSpace = true
            }
        }
        return result
    }

    /// Two characters in Chinese, Japanese and Korean, where two make a word; three elsewhere.
    public static func minimumLength(_ text: String) -> Int {
        text.unicodeScalars.contains(where: isCJK) ? 2 : 3
    }

    static func isCJK(_ scalar: Unicode.Scalar) -> Bool {
        (0x3040...0x30FF).contains(scalar.value) || (0x3400...0x9FFF).contains(scalar.value)
            || (0xAC00...0xD7AF).contains(scalar.value)
    }

    /// How well a normalized term answers a normalized query; 0 for not at all.
    ///
    /// - 100: the term is the query.
    /// - 80: the query begins the term — a word still being typed.
    /// - 70: the term is a whole word or phrase inside the query ("feeling anxious" has "anxious").
    /// - 60: the query is a whole word of a longer term, or begins one ("health" in "mental health").
    /// Chinese, Japanese and Korean have no spaces between words, so there containment either way
    /// counts as 70.
    public static func score(_ term: String, against query: String) -> Int {
        guard !term.isEmpty, !query.isEmpty else { return 0 }
        if term == query { return 100 }
        if term.hasPrefix(query) { return 80 }
        if term.unicodeScalars.contains(where: isCJK) || query.unicodeScalars.contains(where: isCJK) {
            if term.count >= 2, query.contains(term) { return 70 }
            if query.count >= 2, term.contains(query) { return 60 }
            return 0
        }
        if term.count >= 3, " \(query) ".contains(" \(term) ") { return 70 }
        if term.split(separator: " ").contains(where: { $0.hasPrefix(query) }) { return 60 }
        return 0
    }
}
