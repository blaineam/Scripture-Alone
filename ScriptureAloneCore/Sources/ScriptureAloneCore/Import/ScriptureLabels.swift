// Importing is an iPhone, iPad and Mac feature: the watch has no file picker and no
// catalogue. It is also 32-bit (arm64_32), where the ZIP64 sentinel 0xFFFF_FFFF does not
// fit in an Int at all — so this code is not merely unused there, it cannot compile.
#if !os(watchOS)
import Foundation

/// Reading book names and references out of the strings publishers actually ship: chapter
/// headings ("The Gospel According to St. John", "PSALM 23") and element ids
/// ("ESV_Gen.1.1", "csb-Gen-1-1", "v12").
///
/// Book matching goes through the canon and abbreviation tables the app already has
/// (`Canon.swift`, `ReferenceParser`), so an ePub spelling the reader already understands
/// in the search bar is understood here too.
enum ScriptureLabels {
    /// What an element id turned out to name.
    enum Identifier: Sendable, Hashable {
        case verse(BookID, chapter: Int, verse: Int)
        case chapter(BookID, chapter: Int)
        /// `id="v3"` — a verse number with no book or chapter of its own.
        case verseNumber(Int)
        /// `id="ch2"` — a chapter number with no book of its own.
        case chapterNumber(Int)
    }

    /// Words that decorate a book title without naming it.
    private static let noise: Set<String> = [
        "the", "a", "book", "books", "gospel", "gospels", "according", "st", "saint", "holy",
        "bible", "epistle", "epistles", "letter", "letters", "general", "paul", "pauls", "apostle",
        "apostles", "jesus", "christ", "unto", "by", "chapter", "chapters",
    ]

    private static let verseWords: Set<String> = ["v", "vs", "ver", "vers", "verse", "versenum", "verseno"]
    private static let chapterWords: Set<String> = ["c", "ch", "chap", "chapter", "chapternum"]

    // MARK: - Headings

    /// A chapter heading: the book it names, the chapter number it carries, or both.
    /// Returns nil when the text is a section heading ("The Creation of the World") rather than a
    /// book or chapter title.
    static func heading(_ raw: String) -> (book: BookID?, chapter: Int?)? {
        var tokens = normalizedTokens(raw)
        guard !tokens.isEmpty, tokens.count <= 12 else { return nil }

        var chapter: Int?
        if let last = tokens.last, last.allSatisfy(\.isNumber), let value = Int(last), value > 0, value <= 200 {
            // A leading ordinal ("1 John") is part of the name, not a chapter number.
            if tokens.count > 1 || raw.trimmingCharacters(in: .whitespacesAndNewlines).allSatisfy({ $0.isNumber }) {
                chapter = value
                tokens.removeLast()
            }
        }
        guard let book = book(tokens: tokens) else {
            return chapter.map { (book: nil, chapter: $0) }
        }
        return (book: book, chapter: chapter)
    }

    /// The book a run of words names, trying the phrase as written and again with the connecting
    /// words removed, so both "Song of Solomon" and "First Epistle of John" resolve.
    ///
    /// The match must account for every word that is left after the decoration is stripped. That is
    /// what keeps a section heading like "Job’s Complaint" from being read as the book of Job.
    static func book(tokens: [String]) -> BookID? {
        let cleaned = tokens.filter { !noise.contains($0) }
        guard !cleaned.isEmpty else { return nil }
        let candidates = [cleaned, cleaned.filter { $0 != "of" && $0 != "to" }]
        var best: (book: BookID, width: Int)?
        for candidate in candidates {
            guard let match = longestMatch(candidate), match.width == candidate.count else { continue }
            if best == nil || match.width > best!.width { best = match }
        }
        if let best { return best.book }
        // Last resort: the app's own prefix matching, which knows every abbreviation the
        // search bar accepts. Kept narrow so prose headings do not become books.
        guard cleaned.count <= 2, let token = cleaned.last, token.count >= 3 else { return nil }
        let joined = ReferenceParser.normalizeOrdinals(cleaned.joined(separator: " "))
        return ReferenceParser.books(matching: joined).first
    }

    /// The longest run of adjacent words that is exactly a book name or abbreviation.
    private static func longestMatch(_ tokens: [String]) -> (book: BookID, width: Int)? {
        var best: (book: BookID, width: Int)?
        for start in tokens.indices {
            for end in stride(from: tokens.count, to: start, by: -1) {
                let width = end - start
                if let current = best, width <= current.width { break }
                let phrase = ReferenceParser.normalizeOrdinals(tokens[start..<end].joined(separator: " "))
                guard let book = exactBook(BookInfo.normalize(phrase)) else { continue }
                best = (book, width)
                break
            }
        }
        return best
    }

    /// Exact alias or USFM code — never a prefix, so "the" cannot become Titus.
    static func exactBook(_ token: String) -> BookID? {
        guard token.count >= 2 else { return nil }
        if let book = BookID.allCases.first(where: { $0.info.aliases.contains(token) }) { return book }
        return BookID.allCases.first { $0.code.lowercased() == token }
    }

    /// A book (and chapter) named by a file name: "gen01.xhtml", "01_Genesis.xhtml", "Genesis.xhtml".
    static func fileStem(_ stem: String) -> (book: BookID?, chapter: Int?)? {
        if let parsed = identifier(stem) {
            switch parsed {
            case .verse(let book, let chapter, _): return (book, chapter)
            case .chapter(let book, let chapter): return (book, chapter)
            case .chapterNumber(let chapter): return (nil, chapter)
            case .verseNumber: return nil
            }
        }
        var tokens = normalizedTokens(stem)
        // A zero-padded or large leading number is a sort key ("01_Genesis"); a bare 1, 2 or 3 is
        // an ordinal that belongs to the name ("1_John").
        while let first = tokens.first, first.allSatisfy(\.isNumber),
              first.count > 1 || (Int(first) ?? 0) > 3 {
            tokens.removeFirst()
        }
        guard !tokens.isEmpty else { return nil }
        return heading(tokens.joined(separator: " "))
    }

    static func normalizedTokens(_ raw: String) -> [String] {
        let lowered = raw.lowercased()
        var token = ""
        var tokens: [String] = []
        for character in lowered {
            if character.isLetter || character.isNumber {
                token.append(character)
            } else if !token.isEmpty {
                tokens.append(token)
                token = ""
            }
        }
        if !token.isEmpty { tokens.append(token) }
        return tokens
    }

    // MARK: - Identifiers

    /// Reads an element id. Handles `ESV_Gen.1.1`, `csb-Gen-1-1`, `MAT.5.3`, `1Cor.13.4`,
    /// `Gen.1`, `v12`, `verse-3`, `ch2`.
    static func identifier(_ raw: String) -> Identifier? {
        let parts = idComponents(raw)
        guard parts.count >= 2 else { return nil }

        func bookToken(at index: Int) -> BookID? {
            guard index >= 0, index < parts.count, !parts[index].isDigits else { return nil }
            let word = parts[index].text
            if index > 0, parts[index - 1].isDigits, parts[index - 1].text.count == 1,
               let ordinal = Int(parts[index - 1].text), (1...3).contains(ordinal),
               let book = exactBook(parts[index - 1].text + word) {
                return book
            }
            return exactBook(word)
        }

        let last = parts[parts.count - 1]
        guard last.isDigits, let lastValue = Int(last.text), lastValue > 0, lastValue < 1000 else { return nil }

        if parts.count >= 3, parts[parts.count - 2].isDigits,
           let chapter = Int(parts[parts.count - 2].text), chapter > 0, chapter < 1000,
           let book = bookToken(at: parts.count - 3) {
            return .verse(book, chapter: chapter, verse: lastValue)
        }
        if parts.count >= 2, !parts[parts.count - 2].isDigits {
            let word = parts[parts.count - 2].text
            if let book = bookToken(at: parts.count - 2) {
                return .chapter(book, chapter: lastValue)
            }
            if verseWords.contains(word) { return .verseNumber(lastValue) }
            if chapterWords.contains(word) { return .chapterNumber(lastValue) }
        }
        return nil
    }

    struct IDComponent: Sendable {
        var text: String
        var isDigits: Bool
    }

    /// Splits on separators and on letter/digit boundaries: "ESV_1Cor.13.4" -> ESV, 1, Cor, 13, 4.
    static func idComponents(_ raw: String) -> [IDComponent] {
        var parts: [IDComponent] = []
        var current = ""
        var currentIsDigits = false
        func flush() {
            if !current.isEmpty { parts.append(IDComponent(text: current, isDigits: currentIsDigits)) }
            current = ""
        }
        for character in raw.lowercased() {
            if character.isNumber {
                if !current.isEmpty && !currentIsDigits { flush() }
                currentIsDigits = true
                current.append(character)
            } else if character.isLetter {
                if !current.isEmpty && currentIsDigits { flush() }
                currentIsDigits = false
                current.append(character)
            } else {
                flush()
            }
        }
        flush()
        return parts
    }
}
#endif
