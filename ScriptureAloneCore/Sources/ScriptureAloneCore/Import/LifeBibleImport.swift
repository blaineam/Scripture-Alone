// Importing is an iPhone, iPad and Mac feature; the watch has no file picker. See `ZipReader`,
// which this uses and which cannot compile for arm64_32 anyway.
#if !os(watchOS)
import Foundation

/// Reads a `LifeBibleData.zip` — the export produced by Life Bible (formerly Tecarta Bible) under
/// Settings → Advanced → Export your data — so years of someone's study notes can move here.
///
/// **What the export actually is.** Not JSON, not CSV: a folder of HTML pages meant for a person to
/// read, and the structure has to be recovered from them.
///
/// - `verse-notes.html` — one `<p>` per note. The first line is the reference, then `<br>`, then
///   the note's own HTML.
/// - `highlights.html` — `<br>`-separated lines of `reference  #rrggbb`, optionally prefixed
///   `underline ` and optionally suffixed `word: N` / `words: N-M`.
/// - `saves-in-this-folder.html` — `<br>`-separated references, one per saved verse.
/// - every other `.html` — a journal entry, whose title is its file name and whose body is the page.
/// - Folders in the app become directories in the archive, so a journal file may sit at any depth.
///
/// **References are display strings, not identifiers.** They read `Genesis 3:1 NKJV`, and three
/// things about that matter. The space between the book and the chapter is a **non-breaking**
/// space (U+00A0), so splitting on `" "` finds nothing. The trailing token is the translation the
/// note was written against, which is information about the note rather than part of the
/// reference. And the book name is whatever that translation calls it, so it has to go through the
/// same fuzzy matcher the reader's own "jump to passage" field uses rather than a fixed table.
///
/// **Nothing is guessed.** A line whose reference cannot be resolved is not dropped and not
/// approximated: it is collected in `unresolved` so the reader can be told exactly what did not
/// come across. Losing a note silently would be worse than not importing at all.
public struct LifeBibleImport: Sendable, Equatable {

    /// A highlight, reduced to what this app can hold: one verse, one of five colors.
    public struct Highlight: Sendable, Equatable {
        public let verse: VerseRef
        /// Matches `HighlightColor`'s raw values: yellow, green, blue, pink, purple.
        public let color: String
        /// The translation the highlight was made in, kept for the import summary.
        public let translation: String?
    }

    /// A note. `range` is nil for a journal entry, which is attached to no verse.
    public struct Note: Sendable, Equatable {
        public let range: VerseRange?
        public let title: String
        public let body: String
        public let translation: String?
    }

    /// The color names this import can produce — `HighlightColor`'s raw values in the app.
    public static let HighlightColorNames: Set<String> = ["yellow", "green", "blue", "pink", "purple"]

    public var highlights: [Highlight] = []
    public var verseNotes: [Note] = []
    public var journals: [Note] = []
    public var saved: [VerseRange] = []
    /// Lines whose reference could not be resolved, verbatim, so they can be shown rather than lost.
    public var unresolved: [String] = []

    public var isEmpty: Bool {
        highlights.isEmpty && verseNotes.isEmpty && journals.isEmpty && saved.isEmpty
    }

    public var total: Int {
        highlights.count + verseNotes.count + journals.count + saved.count
    }

    public enum Failure: LocalizedError, Equatable {
        case notAnArchive
        case notALifeBibleExport
        case nothingToImport

        public var errorDescription: String? {
            switch self {
            case .notAnArchive:
                "That file isn't a zip archive."
            case .notALifeBibleExport:
                "That doesn't look like a Life Bible export. Look for LifeBibleData.zip, from "
                    + "Settings → Advanced → Export your data."
            case .nothingToImport:
                "That export has no notes, highlights or saved verses in it."
            }
        }
    }

    // MARK: Reading the archive

    public static func read(archive data: Data) throws -> LifeBibleImport {
        let zip: ZipReader
        do { zip = try ZipReader(data: data) } catch { throw Failure.notAnArchive }

        var result = LifeBibleImport()
        var sawKnownFile = false

        for entry in zip.entries where entry.name.lowercased().hasSuffix(".html") {
            // Directory entries and macOS resource forks are not content.
            let file = entry.name.split(separator: "/").map(String.init)
            guard let leaf = file.last, !file.contains("__MACOSX") else { continue }
            guard let bytes = try? zip.data(for: entry),
                  let html = String(data: bytes, encoding: .utf8) else { continue }

            switch leaf.lowercased() {
            case "verse-notes.html":
                sawKnownFile = true
                result.readVerseNotes(html)
            case "highlights.html":
                sawKnownFile = true
                result.readHighlights(html)
            case "saves-in-this-folder.html":
                sawKnownFile = true
                result.readSaves(html)
            default:
                // A journal entry. Its folder path is kept in the title so a reader who organised
                // their journal into folders can still tell entries apart.
                let folders = file.dropFirst().dropLast().filter { $0 != "LifeBibleData" }
                result.readJournal(html, leaf: leaf, folders: Array(folders))
            }
        }

        guard sawKnownFile || !result.journals.isEmpty else { throw Failure.notALifeBibleExport }
        guard !result.isEmpty else { throw Failure.nothingToImport }
        return result
    }

    // MARK: The four shapes

    mutating func readVerseNotes(_ html: String) {
        for block in Self.paragraphs(in: Self.body(of: html)) {
            // Reference, <br>, then the note. Only the first break separates them; the rest belong
            // to the note's own text.
            let pieces = Self.lines(in: block)
            guard let head = pieces.first else { continue }
            let rest = pieces.dropFirst().joined(separator: "\n")
            guard let reference = Self.reference(in: head) else {
                if !Self.text(of: block).isEmpty { unresolved.append(Self.text(of: head)) }
                continue
            }
            let body = Self.text(of: rest)
            guard !body.isEmpty else { continue }
            verseNotes.append(Note(range: reference.range, title: reference.display,
                                   body: body, translation: reference.translation))
        }
    }

    mutating func readHighlights(_ html: String) {
        for line in Self.lines(in: Self.body(of: html)) {
            let text = Self.text(of: line)
            guard !text.isEmpty else { continue }
            // "underline " is a style Life Bible has and this app does not; the highlight still
            // comes across, in the nearest color, rather than being dropped for want of a style.
            var remainder = text
            if remainder.lowercased().hasPrefix("underline ") { remainder.removeFirst("underline ".count) }
            // Trailing "word: 3" / "words: 3-7" are Life Bible's own word offsets within a verse.
            // They cannot be honoured here — this app highlights whole verses — so they are cut.
            if let cut = remainder.range(of: #"\s+words?:\s*\d+(-\d+)?$"#, options: .regularExpression) {
                remainder.removeSubrange(cut)
            }
            guard let hash = remainder.lastIndex(of: "#") else { unresolved.append(text); continue }
            let hex = String(remainder[remainder.index(after: hash)...])
                .trimmingCharacters(in: .whitespaces)
            let head = String(remainder[..<hash])
            guard let reference = Self.reference(in: head), let range = reference.range else {
                unresolved.append(text)
                continue
            }
            let color = Self.nearestColor(toHex: hex)
            // One row per verse: a Life Bible highlight spanning verses exports as one line each,
            // but a range would still be meaningful and is expanded rather than truncated.
            for key in stride(from: range.start.key, through: range.end.key, by: 1) {
                guard let verse = VerseRef(key: key) else { continue }
                highlights.append(Highlight(verse: verse, color: color,
                                            translation: reference.translation))
            }
        }
    }

    mutating func readSaves(_ html: String) {
        for line in Self.lines(in: Self.body(of: html)) {
            let text = Self.text(of: line)
            guard !text.isEmpty else { continue }
            guard let reference = Self.reference(in: text), let range = reference.range else {
                unresolved.append(text)
                continue
            }
            saved.append(range)
        }
    }

    mutating func readJournal(_ html: String, leaf: String, folders: [String]) {
        let body = Self.text(of: Self.body(of: html))
        guard !body.isEmpty else { return }
        // The file name is a slug of the title the reader gave it. The title is usually repeated as
        // the entry's first line, which is a better source: it kept its capitals and punctuation.
        let slug = leaf.replacingOccurrences(of: ".html", with: "", options: [.caseInsensitive])
        let fromSlug = slug.replacingOccurrences(of: "-", with: " ")
        let firstLine = body.split(separator: "\n", maxSplits: 1).first.map(String.init) ?? ""
        let title: String
        if !firstLine.isEmpty, firstLine.count <= 120,
           Self.slugify(firstLine).hasPrefix(Self.slugify(fromSlug).prefix(24)) {
            title = firstLine.trimmingCharacters(in: CharacterSet(charactersIn: " ."))
        } else {
            title = fromSlug.capitalized
        }
        let prefixed = folders.isEmpty ? title : folders.joined(separator: " › ") + " › " + title
        journals.append(Note(range: nil, title: prefixed, body: body, translation: nil))
    }

    // MARK: References

    struct Reference {
        var range: VerseRange?
        var display: String
        var translation: String?
    }

    /// Pulls `Genesis 3:1 NKJV` apart, tolerating the non-breaking space and the trailing
    /// translation, and resolves it with the same parser the reader's own passage field uses.
    static func reference(in raw: String) -> Reference? {
        var text = text(of: raw)
            .replacingOccurrences(of: "\u{00A0}", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return nil }

        // A non-versified resource exports "Book, para. 4", which names nothing in a Bible.
        if text.range(of: #",\s*para\.\s*\d+"#, options: .regularExpression) != nil { return nil }

        // The translation is the last token when it looks like an abbreviation — all caps and
        // digits, two or more characters: CSB, NKJV, NASB95, NIV84. A book name never does.
        var translation: String?
        if let space = text.lastIndex(of: " ") {
            let tail = String(text[text.index(after: space)...])
            if tail.count >= 2, tail.count <= 10,
               tail.allSatisfy({ $0.isUppercase || $0.isNumber }), tail.contains(where: \.isLetter) {
                translation = tail
                text = String(text[..<space]).trimmingCharacters(in: .whitespaces)
            }
        }
        guard let passage = ReferenceParser.parse(text)?.clamped else { return nil }
        // No store here to ask for verse counts, and an import must not invent an end verse it
        // cannot check — a whole-chapter reference becomes its first verse, which is where the
        // reader will be taken.
        let range = passage.range { chapter in passage.endVerse ?? passage.startVerse ?? 1 }
        return Reference(range: range, display: text, translation: translation)
    }

    // MARK: Colors

    /// Life Bible's five palette colors, plus any custom color a reader picked, mapped to the
    /// nearest of this app's five.
    ///
    /// Matched by **hue**, not by distance in RGB. That distinction is the whole of it: Life Bible's
    /// palette is pale (`#cae1fe`) where this app's is saturated (`#7fb8f0`), so straight RGB
    /// distance is dominated by lightness and files a pale blue under purple — which is simply the
    /// wrong colour, and the reader would see their blue highlights come back the wrong shade.
    /// Hue is what someone means when they say they highlighted a verse in blue.
    ///
    /// A colour with almost no hue — Life Bible's grey — is matched instead to the palest colour
    /// here, since there is no grey to match it to and any answer is a compromise.
    static func nearestColor(toHex hex: String) -> String {
        let ours: [(name: String, hex: UInt32)] = [
            ("yellow", 0xF7D154), ("green", 0x8CD48A), ("blue", 0x7FB8F0),
            ("pink", 0xF29BB8), ("purple", 0xB9A2EC),
        ]
        guard let value = UInt32(hex.trimmingCharacters(in: CharacterSet(charactersIn: "# ")),
                                 radix: 16) else { return "yellow" }
        let target = hueAndSaturation(value)
        // Under about 15% saturation there is no hue worth comparing.
        guard target.saturation >= 0.15 else {
            return ours.min { hueAndSaturation($0.hex).saturation < hueAndSaturation($1.hex).saturation }?
                .name ?? "yellow"
        }
        let closest = ours.min { a, b in
            hueDistance(hueAndSaturation(a.hex).hue, target.hue)
                < hueDistance(hueAndSaturation(b.hex).hue, target.hue)
        }
        return closest?.name ?? "yellow"
    }

    /// Degrees around the colour wheel, and how far from grey — enough for matching, without
    /// pulling a colour framework into a parser.
    static func hueAndSaturation(_ rgb: UInt32) -> (hue: Double, saturation: Double) {
        let r = Double((rgb >> 16) & 0xFF) / 255, g = Double((rgb >> 8) & 0xFF) / 255
        let b = Double(rgb & 0xFF) / 255
        let high = max(r, g, b), low = min(r, g, b), spread = high - low
        guard spread > 0, high > 0 else { return (0, 0) }
        let hue: Double = switch high {
        case r: 60 * (((g - b) / spread).truncatingRemainder(dividingBy: 6))
        case g: 60 * ((b - r) / spread + 2)
        default: 60 * ((r - g) / spread + 4)
        }
        return (hue < 0 ? hue + 360 : hue, spread / high)
    }

    /// Around the wheel, so 350° and 10° are twenty degrees apart rather than three hundred.
    static func hueDistance(_ a: Double, _ b: Double) -> Double {
        let d = abs(a - b).truncatingRemainder(dividingBy: 360)
        return min(d, 360 - d)
    }

    // MARK: HTML, reduced to text

    static func body(of html: String) -> String {
        guard let open = html.range(of: "<body", options: .caseInsensitive),
              let close = html.range(of: ">", range: open.upperBound..<html.endIndex) else { return html }
        let rest = html[close.upperBound...]
        if let end = rest.range(of: "</body>", options: .caseInsensitive) {
            return String(rest[..<end.lowerBound])
        }
        return String(rest)
    }

    static func paragraphs(in html: String) -> [String] {
        let pattern = #"(?is)<p\b[^>]*>(.*?)</p>"#
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return [] }
        let all = NSRange(html.startIndex..., in: html)
        return regex.matches(in: html, range: all).compactMap {
            Range($0.range(at: 1), in: html).map { String(html[$0]) }
        }
    }

    static func lines(in html: String) -> [String] {
        html.replacingOccurrences(of: #"(?i)<br\s*/?>"#, with: "\n", options: .regularExpression)
            .components(separatedBy: "\n")
    }

    /// Tags out, entities decoded, whitespace tidied — without pulling in a whole HTML parser for
    /// four files whose markup this app produced a reader for, not a browser.
    static func text(of html: String) -> String {
        var out = html.replacingOccurrences(of: #"(?i)<br\s*/?>"#, with: "\n", options: .regularExpression)
        out = out.replacingOccurrences(of: #"(?is)<(script|style)\b.*?</\1>"#, with: "",
                                       options: .regularExpression)
        out = out.replacingOccurrences(of: #"(?i)</(p|div|li|h[1-6]|blockquote)>"#, with: "\n",
                                       options: .regularExpression)
        out = out.replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
        // Numeric references first, and generally: the export writes `&#39;` for an apostrophe and
        // `&#47;` for a slash, so a date typed as 06/11/22 arrives as `06&#47;11&#47;22`. A fixed
        // table of named entities would have let that through into someone's note, which is how it
        // was found — in a real note, on screen, rather than in a test.
        out = decodeNumericEntities(in: out)
        for (entity, character) in [("&nbsp;", " "), ("&amp;", "&"), ("&lt;", "<"), ("&gt;", ">"),
                                    ("&quot;", "\""), ("&apos;", "'"),
                                    ("&rsquo;", "’"), ("&lsquo;", "‘"), ("&ldquo;", "“"),
                                    ("&rdquo;", "”"), ("&mdash;", "—"), ("&ndash;", "–")] {
            out = out.replacingOccurrences(of: entity, with: character, options: .caseInsensitive)
        }
        out = out.replacingOccurrences(of: "\u{00A0}", with: " ")
        // Collapse runs of blank lines, and trailing space on each line.
        let lines = out.components(separatedBy: "\n")
            .map { $0.trimmingCharacters(in: .whitespaces) }
        var kept: [String] = []
        for line in lines {
            if line.isEmpty, kept.last?.isEmpty ?? true { continue }
            kept.append(line)
        }
        return kept.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// `&#39;` and `&#x27;` — every numeric character reference, rather than the handful someone
    /// thought to list. `&amp;` is deliberately left to the named pass that follows, so that a
    /// literal `&amp;#39;` in a note stays literal instead of being decoded twice.
    static func decodeNumericEntities(in text: String) -> String {
        guard text.contains("&#"),
              let regex = try? NSRegularExpression(pattern: "&#(x?)([0-9A-Fa-f]{1,6});") else { return text }
        var out = ""
        var last = text.startIndex
        for match in regex.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
            guard let whole = Range(match.range, in: text),
                  let hexFlag = Range(match.range(at: 1), in: text),
                  let digits = Range(match.range(at: 2), in: text) else { continue }
            let radix = text[hexFlag].isEmpty ? 10 : 16
            guard let value = UInt32(text[digits], radix: radix), let scalar = Unicode.Scalar(value)
            else { continue }
            out += text[last..<whole.lowerBound] + String(Character(scalar))
            last = whole.upperBound
        }
        out += text[last...]
        return out
    }

    static func slugify(_ text: String) -> String {
        text.lowercased().map { $0.isLetter || $0.isNumber ? String($0) : "-" }.joined()
    }
}
#endif
