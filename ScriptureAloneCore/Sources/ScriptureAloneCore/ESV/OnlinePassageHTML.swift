// The layout types this builds live in `Import/`, which the watch cannot compile (arm64_32
// cannot hold the ZIP64 sentinel) — and the watch does not call these APIs anyway; it receives
// text through the CloudKit/WCSession snapshot. Same guard as `OnlineChapterCache`.
#if !os(watchOS)
import Foundation

/// A chapter fetched from a publisher's API, with its structure intact.
///
/// The app used to ask both APIs for plain text. Plain text is a *rendering* — it is what those
/// services produce for a terminal — and everything that makes scripture look like scripture is
/// thrown away in making it: the words of Christ, the poetry, the psalm titles, the paragraphs.
/// Two separate complaints traced back to that one decision: online translations had no red
/// letters at all, and psalms arrived as a run-on paragraph whose stray line breaks had to be
/// flattened because whitespace could not be told apart from wrapping.
///
/// Both services will state the structure outright if asked for HTML, so now they are.
public struct ParsedPassage: Sendable {
    /// Flat text per verse, with words of Christ as UTF-16 ranges — what search, quotation,
    /// listening and sharing all work from.
    public var verses: [VerseText] = []
    /// The chapter's shape: paragraphs, poetry lines, headings, psalm titles.
    public var blocks: [ExtractedBlock] = []

    public var isEmpty: Bool { verses.isEmpty }
}

// MARK: - A very small HTML scanner

/// Just enough HTML to read two known documents.
///
/// Not a general parser, and not trying to be: these are two services emitting markup they
/// generate themselves, and the alternative is a dependency to walk a handful of tags.
enum PassageHTML {
    enum Token {
        case open(tag: String, classes: [String], attributes: [String: String])
        case close(tag: String)
        case text(String)
    }

    static func scan(_ html: String) -> [Token] {
        var tokens: [Token] = []
        var text = ""
        var index = html.startIndex

        func flushText() {
            if !text.isEmpty { tokens.append(.text(decode(text))); text = "" }
        }

        while index < html.endIndex {
            guard html[index] == "<" else {
                text.append(html[index])
                index = html.index(after: index)
                continue
            }
            guard let end = html[index...].firstIndex(of: ">") else {
                text.append(contentsOf: html[index...])
                break
            }
            flushText()
            let inner = String(html[html.index(after: index)..<end])
            index = html.index(after: end)

            if inner.hasPrefix("/") {
                tokens.append(.close(tag: String(inner.dropFirst()).lowercased()))
                continue
            }
            let selfClosing = inner.hasSuffix("/")
            let body = selfClosing ? String(inner.dropLast()) : inner
            guard let name = body.split(whereSeparator: \.isWhitespace).first.map(String.init)
            else { continue }
            let attributes = self.attributes(in: body)
            let classes = (attributes["class"] ?? "").split(whereSeparator: \.isWhitespace).map(String.init)
            tokens.append(.open(tag: name.lowercased(), classes: classes, attributes: attributes))
            // `<br />` never has a closing tag; treating it as an open-only token keeps the
            // handlers from having to know which tags are void.
            if selfClosing { tokens.append(.close(tag: name.lowercased())) }
        }
        flushText()
        return tokens
    }

    static func attributes(in tag: String) -> [String: String] {
        var found: [String: String] = [:]
        let pattern = #"([\w-]+)\s*=\s*"([^"]*)""#
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return found }
        for match in regex.matches(in: tag, range: NSRange(tag.startIndex..., in: tag)) {
            guard let key = Range(match.range(at: 1), in: tag),
                  let value = Range(match.range(at: 2), in: tag) else { continue }
            found[String(tag[key]).lowercased()] = String(tag[value])
        }
        return found
    }

    /// Entities, including numeric ones. `&nbsp;` becomes an ordinary space: both services use
    /// runs of it for indentation, which the reader lays out itself.
    static func decode(_ text: String) -> String {
        var out = text
        if out.contains("&#") {
            let pattern = "&#(x?)([0-9A-Fa-f]{1,6});"
            if let regex = try? NSRegularExpression(pattern: pattern) {
                var result = ""
                var last = out.startIndex
                for match in regex.matches(in: out, range: NSRange(out.startIndex..., in: out)) {
                    guard let whole = Range(match.range, in: out),
                          let flag = Range(match.range(at: 1), in: out),
                          let digits = Range(match.range(at: 2), in: out),
                          let value = UInt32(out[digits], radix: out[flag].isEmpty ? 10 : 16),
                          let scalar = Unicode.Scalar(value) else { continue }
                    result += out[last..<whole.lowerBound] + String(Character(scalar))
                    last = whole.upperBound
                }
                result += out[last...]
                out = result
            }
        }
        for (entity, character) in [("&nbsp;", " "), ("&amp;", "&"), ("&lt;", "<"), ("&gt;", ">"),
                                    ("&quot;", "\""), ("&apos;", "'"), ("&rsquo;", "’"),
                                    ("&lsquo;", "‘"), ("&ldquo;", "“"), ("&rdquo;", "”"),
                                    ("&mdash;", "—"), ("&ndash;", "–"), ("&hellip;", "…")] {
            out = out.replacingOccurrences(of: entity, with: character)
        }
        return out
    }
}

// MARK: - Building blocks and verses

/// Accumulates fragments as tags come and go, then hands back a passage.
///
/// Shared by both dialects because the bookkeeping is the same either way: which verse we are in,
/// whether its number has been printed yet, which styles are open, and where one block ends.
struct PassageBuilder {
    let chapter: ChapterRef

    private var blocks: [ExtractedBlock] = []
    private var kind: ExtractedBlock.Kind = .paragraph
    private var fragments: [ExtractedFragment] = []
    private var text = ""
    private var spans: [StyledSpan] = []
    private var openStyles: [(style: StyledSpan.Style, start: Int)] = []
    private var verse = 0
    private var numbered = false

    init(chapter: ChapterRef) { self.chapter = chapter }

    /// Scalar count, because `StyledSpan` offsets are Unicode scalars.
    private var cursor: Int { text.unicodeScalars.count }

    mutating func beginBlock(_ kind: ExtractedBlock.Kind) {
        endFragment()
        endBlock()
        self.kind = kind
    }

    mutating func startVerse(_ number: Int, numbered: Bool = true) {
        endFragment()
        verse = number
        self.numbered = numbered
    }

    /// Continues a verse whose number was printed in an earlier block — API.Bible marks these
    /// with `data-vid`, and without it a poetry line would be attributed to no verse at all.
    mutating func continueVerse(_ number: Int) {
        if verse != number || !text.isEmpty { endFragment() }
        verse = number
        numbered = false
    }

    mutating func append(_ piece: String) {
        guard !piece.isEmpty else { return }
        // Collapse the runs of spaces both services use for indentation; the reader indents.
        var cleaned = piece.replacingOccurrences(of: "\u{00A0}", with: " ")
        cleaned = cleaned.replacingOccurrences(of: "[ \t\n]+", with: " ", options: .regularExpression)
        if text.isEmpty { cleaned = String(cleaned.drop(while: { $0 == " " })) }
        guard !cleaned.isEmpty else { return }
        if text.hasSuffix(" ") && cleaned.hasPrefix(" ") { cleaned.removeFirst() }
        text += cleaned
    }

    mutating func openStyle(_ style: StyledSpan.Style) {
        openStyles.append((style, cursor))
    }

    mutating func closeStyle(_ style: StyledSpan.Style) {
        guard let index = openStyles.lastIndex(where: { $0.style == style }) else { return }
        let opened = openStyles.remove(at: index)
        let length = cursor - opened.start
        if length > 0 { spans.append(StyledSpan(start: opened.start, length: length, style: style)) }
    }

    mutating func endFragment() {
        // Words of Christ are a *character* style, and USX allows one to run across a verse or a
        // paragraph boundary. Neither service does that in the chapters captured so far — both
        // close and reopen around a verse number — but a style that legally spans a boundary must
        // not be silently lost when it does. So anything still open is closed here, and carried
        // into the next fragment at its start.
        let carried = openStyles.map(\.style)
        for open in openStyles.reversed() { closeStyle(open.style) }
        openStyles = []
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        defer {
            text = ""
            spans = []
            openStyles = carried.map { ($0, 0) }   // still open, now at the head of what follows
        }
        // A heading or a psalm's superscription belongs to no verse. The bundled stores encode
        // those as verse 0 — the American Standard Version's Psalm 23 carries "A Psalm of David"
        // exactly that way — so the same convention is used here, and `finish()` keeps such blocks
        // out of the verse text while the layout still draws them.
        let belongsToNoVerse = kind.isHeading || kind == .title
        guard !trimmed.isEmpty, verse > 0 || belongsToNoVerse else { return }
        let number = belongsToNoVerse ? 0 : verse
        // Trimming the head would shift every span; only a trailing trim is safe here, and the
        // leading side is already handled in `append`.
        let kept = String(text.reversed().drop(while: { $0 == " " }).reversed())
        fragments.append(ExtractedFragment(verse: number, numbered: numbered && !belongsToNoVerse,
                                           text: kept, spans: spans))
        numbered = false
    }

    mutating func endBlock() {
        endFragment()
        guard !fragments.isEmpty || kind == .stanzaBreak else { return }
        blocks.append(ExtractedBlock(kind: kind, fragments: fragments))
        fragments = []
    }

    /// A block with no text of its own — a stanza break.
    mutating func emptyBlock(_ kind: ExtractedBlock.Kind) {
        endFragment()
        endBlock()
        blocks.append(ExtractedBlock(kind: kind, fragments: []))
        self.kind = .paragraph
    }

    mutating func finish() -> ParsedPassage {
        endBlock()
        var passage = ParsedPassage()
        passage.blocks = blocks

        // One `VerseText` per verse: the fragments joined, with the styles converted from scalar
        // offsets within a fragment to UTF-16 ranges within the whole verse — which is what
        // `VerseText.red` is, and what the reader's renderer expects.
        var order: [Int] = []
        var pieces: [Int: [ExtractedFragment]] = [:]
        for block in blocks where !block.kind.isHeading && block.kind != .title {
            for fragment in block.fragments where fragment.verse > 0 {
                if pieces[fragment.verse] == nil { order.append(fragment.verse) }
                pieces[fragment.verse, default: []].append(fragment)
            }
        }
        for number in order {
            guard let parts = pieces[number] else { continue }
            var whole = ""
            var red: [NSRange] = []
            for part in parts {
                if !whole.isEmpty { whole += " " }
                let offset = whole.utf16.count
                for span in part.spans where span.style == .wordsOfChrist {
                    // Scalar offsets to UTF-16, by measuring the prefix rather than assuming they
                    // agree — they do not, for anything outside the basic plane.
                    let scalars = Array(part.text.unicodeScalars)
                    guard span.start <= scalars.count, span.start + span.length <= scalars.count else { continue }
                    let before = String(String.UnicodeScalarView(scalars[0..<span.start]))
                    let inside = String(String.UnicodeScalarView(scalars[span.start..<(span.start + span.length)]))
                    red.append(NSRange(location: offset + before.utf16.count, length: inside.utf16.count))
                }
                whole += part.text
            }
            passage.verses.append(VerseText(ref: VerseRef(chapter.book, chapter.chapter, number),
                                            text: whole, red: red))
        }
        passage.verses.sort { $0.ref < $1.ref }
        return passage
    }
}

// MARK: - Crossway's ESV

/// Reads `api.esv.org/v3/passage/html`.
///
/// Its shape, from a real response: paragraphs are `<p>`; poetry is a `<p class="block-indent">`
/// holding `<span class="line">` and `<span class="indent line">` separated by `<br />`; a psalm's
/// superscription is `<h4 class="psalm-title">`; verse numbers are `<b class="verse-num">` (and
/// `<b class="chapter-num">` for the first verse of the chapter); and the words of Christ are
/// `<span class="woc">`, which also wraps the verse number when a red-letter passage runs through
/// it.
public enum ESVPassageHTML {
    public static func parse(_ html: String, in chapter: ChapterRef) -> ParsedPassage {
        var builder = PassageBuilder(chapter: chapter)
        var inPoetry = false
        var pendingLineKind: ExtractedBlock.Kind?
        var suppressText = false

        for token in PassageHTML.scan(html) {
            switch token {
            case .open(let tag, let classes, _):
                switch tag {
                case "p":
                    inPoetry = classes.contains("block-indent")
                    builder.beginBlock(inPoetry ? .poetry1 : .paragraph)
                case "h4", "h3":
                    builder.beginBlock(classes.contains("psalm-title") ? .title : .heading)
                case "span" where classes.contains("line"):
                    pendingLineKind = classes.contains("indent") ? .poetry2 : .poetry1
                    builder.beginBlock(pendingLineKind ?? .poetry1)
                case "span" where classes.contains("woc"):
                    builder.openStyle(.wordsOfChrist)
                case "b" where classes.contains("verse-num") || classes.contains("chapter-num"):
                    suppressText = true
                case "br":
                    // Line breaks inside poetry are handled by the line spans themselves.
                    break
                default:
                    break
                }
            case .close(let tag):
                switch tag {
                case "p", "h4", "h3": builder.endBlock(); inPoetry = false
                case "span":
                    // A woc span and a line span both close here; closing a style that is not
                    // open is a no-op, so this stays simple.
                    builder.closeStyle(.wordsOfChrist)
                case "b": suppressText = false
                default: break
                }
            case .text(let text):
                if suppressText {
                    // "3 " or "23:1 " — the trailing number is the verse.
                    let digits = text.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }
                    if let number = digits.last { builder.startVerse(number) }
                } else {
                    builder.append(text)
                }
            }
        }
        return builder.finish()
    }
}

// MARK: - API.Bible

/// Reads `api.scripture.api.bible` with `content-type=html`.
///
/// Its markup is USFM with the markers as class names, which is the same vocabulary this app's own
/// layout uses — `p`, `m`, `q1`, `q2`, `s1`, `d`, `b` map across directly. Verses are
/// `<span class="v" data-number="3">`, a paragraph continuing an earlier verse carries
/// `data-vid="PSA 23:1"`, and the words of Jesus are `<span class="wj">`.
public enum APIBiblePassageHTML {
    public static func parse(_ html: String, in chapter: ChapterRef) -> ParsedPassage {
        var builder = PassageBuilder(chapter: chapter)
        var suppressText = false

        for token in PassageHTML.scan(html) {
            switch token {
            case .open(let tag, let classes, let attributes):
                switch tag {
                case "p":
                    let marker = classes.first ?? "p"
                    if marker == "b" {
                        builder.emptyBlock(.stanzaBreak)
                    } else {
                        builder.beginBlock(ExtractedBlock.Kind(rawValue: marker) ?? .paragraph)
                        // An unnumbered continuation line names the verse it belongs to.
                        if let vid = attributes["data-vid"], let number = verseNumber(in: vid) {
                            builder.continueVerse(number)
                        }
                    }
                case "span" where classes.contains("v"):
                    let number = attributes["data-number"].flatMap { Int($0) }
                        ?? attributes["data-sid"].flatMap(verseNumber(in:))
                    if let number { builder.startVerse(number) }
                    suppressText = true                 // the span's text is the number itself
                case "span" where classes.contains("wj"):
                    builder.openStyle(.wordsOfChrist)
                case "span" where classes.contains("nd"):
                    builder.openStyle(.smallCaps)
                case "span" where classes.contains("add") || classes.contains("it"):
                    builder.openStyle(.supplied)
                default:
                    break
                }
            case .close(let tag):
                switch tag {
                case "p": builder.endBlock()
                case "span":
                    builder.closeStyle(.wordsOfChrist)
                    builder.closeStyle(.smallCaps)
                    builder.closeStyle(.supplied)
                    suppressText = false
                default: break
                }
            case .text(let text):
                if suppressText { continue }
                builder.append(text)
            }
        }
        return builder.finish()
    }

    /// "PSA 23:1" → 1.
    static func verseNumber(in sid: String) -> Int? {
        sid.split(separator: ":").last.flatMap { Int($0.trimmingCharacters(in: .whitespaces)) }
    }
}
#endif
