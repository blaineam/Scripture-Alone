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

    public static func detect(in text: String) -> [Match] {
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
