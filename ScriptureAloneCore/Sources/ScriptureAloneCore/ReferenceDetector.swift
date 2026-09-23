import Foundation

/// Finds scripture references inside free text — sermon slides captured with the camera,
/// pasted notes — without guessing at ordinary words.
public enum ReferenceDetector {
    public struct Match: Hashable, Sendable {
        public let passage: Passage
        /// The matched characters, as an NSRange into the original string.
        public let range: NSRange
    }

    private static let pattern: NSRegularExpression = {
        let ordinal = #"(?:(?:[1-3]|iii|ii|i|first|second|third|1st|2nd|3rd)\s*)?"#
        // Only Song of Solomon/Songs spans words; a general "x of y" would swallow "book of John".
        let name = #"(?:song\s+of\s+(?:solomon|songs)|[a-z]+)"#
        let numbers = #"(\d{1,3})(?:\s*[:.]\s*(\d{1,3}))?(?:\s*[-–—]\s*(\d{1,3})(?:\s*[:.]\s*(\d{1,3}))?)?"#
        let p = #"\b("# + ordinal + name + #")\.?\s*"# + numbers + #"(?!\d)"#
        return try! NSRegularExpression(pattern: p, options: [.caseInsensitive])
    }()

    /// References written with the big-8 languages' book names (`BookNames`): "Jean 10:11–18",
    /// "Psaumes 23:1–6", "Joh 3,16", "约翰福音 10:11", "ヨハネ傳福音書3章16節", "요한복음 3장 16절". The
    /// names are listed outright, longest first — Chinese and Japanese have no word breaks for a
    /// general pattern to use — and only a whole listed name or abbreviation counts, never a prefix,
    /// so ordinary words in a slide's prose are not taken for books.
    private static let localizedPattern: NSRegularExpression = {
        var names = Set<String>()
        for rows in BookNames.table.values {
            for (name, abbreviation, aliases) in rows { names.insert(name); names.insert(abbreviation); names.formUnion(aliases) }
        }
        let alternation = names.filter { !$0.isEmpty }.sorted { $0.count > $1.count }
            .map { NSRegularExpression.escapedPattern(for: $0).replacingOccurrences(of: " ", with: #"\s*"#) }
            .joined(separator: "|")
        let numbers = #"(\d{1,3})(?:\s*(?:[:.,：]|[章장])\s*(\d{1,3})\s*[節节절]?)?(?:\s*[-–—〜～~]\s*(\d{1,3})(?:\s*[:.,：]\s*(\d{1,3}))?)?"#
        return try! NSRegularExpression(pattern: #"(?<![\p{L}])("# + alternation + #")\.?\s*"# + numbers + #"(?!\d)"#)
    }()

    public static func detect(in text: String) -> [Match] {
        let english = detectEnglish(in: text)
        let ns = text as NSString
        var matches = english
        var location = 0
        while location < ns.length,
              let m = localizedPattern.firstMatch(in: text, range: NSRange(location: location, length: ns.length - location)) {
            location = m.range.upperBound
            // A short abbreviation ("Is", "Am", "Jn", "约") needs a verse, as in English — "is 5",
            // "am 3" are ordinary words. An English match on the same characters wins ("Job 3").
            let name = ns.substring(with: m.range(at: 1))
            let hasVerse = m.range(at: 3).location != NSNotFound
            guard name.count > 2 || hasVerse,
                  !english.contains(where: { NSIntersectionRange($0.range, m.range).length > 0 }),
                  let passage = ReferenceParser.parseAnyLanguage(ns.substring(with: m.range)) else { continue }
            matches.append(Match(passage: passage, range: m.range))
        }
        return matches.sorted { $0.range.location < $1.range.location }
    }

    private static func detectEnglish(in text: String) -> [Match] {
        let ns = text as NSString
        var matches: [Match] = []
        var location = 0
        while location < ns.length,
              let m = pattern.firstMatch(in: text, range: NSRange(location: location, length: ns.length - location)) {
            if let match = validate(m, in: ns) {
                matches.append(match)
                location = m.range.upperBound
            } else {
                // "also 1 Pet. 2:25" first matches as book "also", chapter 1. Retry after the first
                // word so the real reference isn't swallowed.
                let firstWord = ns.range(of: #"^\S+"#, options: .regularExpression, range: m.range)
                location = firstWord.location == NSNotFound ? m.range.upperBound : firstWord.upperBound
            }
        }
        return matches
    }

    private static func validate(_ m: NSTextCheckingResult, in ns: NSString) -> Match? {
        let bookText = ns.substring(with: m.range(at: 1))
        let token = BookInfo.normalize(ReferenceParser.normalizeOrdinals(bookText.lowercased()))
        // Only exact names/abbreviations count here — no prefix guessing inside prose.
        guard let book = BookID.allCases.first(where: { $0.info.aliases.contains(token) }) else { return nil }
        let hasVerse = m.range(at: 3).location != NSNotFound
        // Two-letter abbreviations ("Jn", "Ps") need a verse to count.
        if token.count <= 2, !hasVerse { return nil }
        guard let passage = ReferenceParser.parse(ns.substring(with: m.range)), passage.book == book,
              passage.startChapter <= book.chapterCount else { return nil }
        return Match(passage: passage, range: m.range)
    }
}
