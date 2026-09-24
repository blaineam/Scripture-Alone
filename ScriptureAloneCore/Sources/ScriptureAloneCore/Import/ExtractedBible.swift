// Importing is an iPhone, iPad and Mac feature: the watch has no file picker and no
// catalogue. It is also 32-bit (arm64_32), where the ZIP64 sentinel 0xFFFF_FFFF does not
// fit in an Int at all — so this code is not merely unused there, it cannot compile.
#if !os(watchOS)
import Foundation

/// A run of styled text, in Unicode scalars — the same unit `ChapterLayout.Span` uses.
public struct ScalarSpan: Sendable, Hashable, Codable {
    public var start: Int
    public var length: Int

    public init(start: Int, length: Int) {
        self.start = start
        self.length = length
    }
}

/// A styled run inside a fragment. The three styles are the ones the reader already draws:
/// words of Christ, supplied words (the KJV's italics) and small caps (LORD).
public struct StyledSpan: Sendable, Hashable, Codable {
    public enum Style: String, Sendable, Hashable, Codable {
        case wordsOfChrist = "r"
        case supplied = "i"
        case smallCaps = "c"
    }

    public var start: Int
    public var length: Int
    public var style: Style

    public init(start: Int, length: Int, style: Style) {
        self.start = start
        self.length = length
        self.style = style
    }

    public var scalarSpan: ScalarSpan { ScalarSpan(start: start, length: length) }
}

/// A footnote marker: where it sits in the text (Unicode scalars) and what it said, when the file
/// puts the note body where we can see it.
public struct ExtractedFootnote: Sendable, Hashable, Codable {
    public var position: Int
    public var text: String

    public init(position: Int, text: String) {
        self.position = position
        self.text = text
    }
}

/// One verse's worth of text inside one paragraph. A verse that runs across two paragraphs has two
/// fragments; a paragraph holding five verses has five.
public struct ExtractedFragment: Sendable, Hashable, Codable {
    public var verse: Int
    /// The verse number is printed at the start of this fragment.
    public var numbered: Bool
    public var text: String
    /// Words of Christ, supplied words and small caps, when the file marks them.
    public var spans: [StyledSpan]
    public var footnotes: [ExtractedFootnote]

    public init(verse: Int, numbered: Bool, text: String, spans: [StyledSpan] = [], footnotes: [ExtractedFootnote] = []) {
        self.verse = verse
        self.numbered = numbered
        self.text = text
        self.spans = spans
        self.footnotes = footnotes
    }

    public var red: [ScalarSpan] {
        spans.filter { $0.style == .wordsOfChrist }.map(\.scalarSpan)
    }
}

/// A block in reading order: a section heading, or a paragraph of verse fragments.
public struct ExtractedBlock: Sendable, Hashable, Codable {
    /// Raw values are the layout kinds `ChapterLayout` already decodes.
    public enum Kind: String, Sendable, Hashable, Codable {
        case heading = "s1"
        case subheading = "s2"
        case majorSection = "ms"
        case parallel = "r"
        case acrostic = "qa"
        case paragraph = "p"
        case continuation = "m"
        case embedded = "pmo"
        case centered = "pc"
        case list1 = "li1"
        case list2 = "li2"
        case poetry1 = "q1"
        case poetry2 = "q2"
        case selah = "qr"
        case title = "d"
        /// A blank line between stanzas. Carries no text and is never dropped as empty.
        case stanzaBreak = "b"

        public var isHeading: Bool {
            [.heading, .subheading, .majorSection, .parallel, .acrostic].contains(self)
        }
    }

    public var kind: Kind
    /// Set for heading kinds.
    public var heading: String?
    public var fragments: [ExtractedFragment]

    public init(kind: Kind, heading: String? = nil, fragments: [ExtractedFragment] = []) {
        self.kind = kind
        self.heading = heading
        self.fragments = fragments
    }

    public var isEmpty: Bool {
        if kind == .stanzaBreak { return false }
        return kind.isHeading ? (heading ?? "").isEmpty
            : fragments.allSatisfy { $0.text.isEmpty && !$0.numbered && $0.footnotes.isEmpty }
    }
}

/// One verse's whole text, joined across however many paragraphs carried it. This is what search,
/// speech and sharing read, and what the `verses` table stores.
public struct ExtractedVerse: Sendable, Hashable, Codable {
    public var ref: VerseRef
    public var text: String
    public var red: [ScalarSpan]

    public init(ref: VerseRef, text: String, red: [ScalarSpan] = []) {
        self.ref = ref
        self.text = text
        self.red = red
    }
}

/// Something the engine noticed while reading. These ride into the coverage report so an import
/// that went 40% right says so out loud.
public struct ImportNote: Sendable, Hashable, Codable {
    public enum Severity: String, Sendable, Hashable, Codable, Comparable {
        case info, warning, problem

        private var rank: Int {
            switch self {
            case .info: 0
            case .warning: 1
            case .problem: 2
            }
        }

        public static func < (lhs: Severity, rhs: Severity) -> Bool { lhs.rank < rhs.rank }
    }

    public var severity: Severity
    public var message: String

    public init(_ severity: Severity, _ message: String) {
        self.severity = severity
        self.message = message
    }
}

/// Everything one ePub yielded: verses, the layout blocks that print them, and what went wrong.
public struct ExtractedBible: Sendable {
    /// Chapters in the order they were met, so the reader's blocks stay in reading order.
    public private(set) var chapterOrder: [ChapterRef] = []
    public private(set) var blocks: [ChapterRef: [ExtractedBlock]] = [:]
    public private(set) var verses: [VerseRef: ExtractedVerse] = [:]
    public internal(set) var notes: [ImportNote] = []
    /// Chapters whose verse numbers went backwards — the report names them.
    public internal(set) var outOfOrderChapters: Set<ChapterRef> = []
    /// Verses a source combined into one (USFM `\v 1-2`): the absent number maps to the verse
    /// that holds the text, so the report calls it combined rather than missing.
    public internal(set) var bridgedVerses: [VerseRef: VerseRef] = [:]
    /// How each spine file's verse markup was recognised, for the report and for debugging a file
    /// that came out empty.
    public internal(set) var shapesByDocument: [String: VerseMarkupShape] = [:]

    public var books: [BookID] {
        var seen: Set<BookID> = []
        return chapterOrder.compactMap { seen.insert($0.book).inserted ? $0.book : nil }.sorted()
    }

    func hasVerses(in book: BookID) -> Bool {
        chapterOrder.contains { $0.book == book }
    }

    public var verseCount: Int { verses.count }
    public var isEmpty: Bool { verses.isEmpty }

    public func blocks(for chapter: ChapterRef) -> [ExtractedBlock] { blocks[chapter] ?? [] }

    /// Highest verse number recorded in a chapter (what the `chapters.verses` column stores).
    public func highestVerse(in chapter: ChapterRef) -> Int {
        verses.keys.filter { $0.chapterKey == chapter }.map(\.verse).max() ?? 0
    }

    public func verseNumbers(in chapter: ChapterRef) -> [Int] {
        verses.keys.filter { $0.chapterKey == chapter }.map(\.verse).sorted()
    }

    mutating func append(_ block: ExtractedBlock, to chapter: ChapterRef) {
        if blocks[chapter] == nil {
            blocks[chapter] = []
            chapterOrder.append(chapter)
        }
        blocks[chapter]?.append(block)
    }

    /// Adds text to a verse. `separate` is true when this chunk starts a new fragment, which is
    /// the only time a joining space is inserted — mid-fragment runs (`\add word\add*s`) must not
    /// gain one. This is the rule `Tools/build_bibles.py` uses.
    mutating func appendVerseText(_ text: String, red: [ScalarSpan], to ref: VerseRef, separate: Bool = true) {
        guard !text.isEmpty else { return }
        var existing = verses[ref] ?? ExtractedVerse(ref: ref, text: "")
        var addition = text
        var dropped = 0
        if existing.text.isEmpty {
            while addition.hasPrefix(" ") {
                addition.removeFirst()
                dropped += 1
            }
        } else if separate, !existing.text.hasSuffix(" "), !addition.hasPrefix(" ") {
            existing.text += " "
        }
        guard !addition.isEmpty else {
            verses[ref] = existing
            return
        }
        let offset = existing.text.unicodeScalars.count
        existing.text += addition
        existing.red += red.compactMap { span in
            let start = span.start - dropped
            if start >= 0 { return ScalarSpan(start: offset + start, length: span.length) }
            let length = span.length + start
            return length > 0 ? ScalarSpan(start: offset, length: length) : nil
        }
        verses[ref] = existing
    }

    /// Trims trailing space and merges touching red spans, so stored text matches what the bundled
    /// translations look like.
    mutating func tidy() {
        for (ref, verse) in verses {
            var tidied = verse
            let trimmed = tidied.text.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed != tidied.text {
                let lead = tidied.text.unicodeScalars.count - String(tidied.text.drop(while: \.isWhitespace)).unicodeScalars.count
                tidied.text = trimmed
                let limit = trimmed.unicodeScalars.count
                tidied.red = tidied.red.compactMap { span in
                    let start = span.start - lead
                    guard start < limit else { return nil }
                    return ScalarSpan(start: max(0, start), length: min(span.length, limit - max(0, start)))
                }
            }
            tidied.red = Self.merge(tidied.red)
            verses[ref] = tidied
        }
        for chapter in chapterOrder {
            var kept: [ExtractedBlock] = []
            for block in blocks[chapter] ?? [] {
                var tidied = block
                if tidied.kind.isHeading {
                    tidied.heading = tidied.heading?.trimmingCharacters(in: .whitespacesAndNewlines)
                }
                if !tidied.kind.isHeading, tidied.kind != .stanzaBreak {
                    tidied.fragments = tidied.fragments
                        .map(Self.trimTrailing)
                        .filter { !$0.text.isEmpty || $0.numbered || !$0.footnotes.isEmpty }
                }
                if tidied.isEmpty { continue }
                // A stanza break that leads a chapter, or follows another, prints nothing.
                if tidied.kind == .stanzaBreak, kept.isEmpty || kept.last?.kind == .stanzaBreak { continue }
                kept.append(tidied)
            }
            while kept.last?.kind == .stanzaBreak { kept.removeLast() }
            blocks[chapter] = kept
        }
        chapterOrder = chapterOrder.filter { !(blocks[$0] ?? []).isEmpty }
    }

    /// Merges touching runs of the same style.
    /// Drops trailing whitespace and pulls the spans and footnote positions back inside the text.
    static func trimTrailing(_ fragment: ExtractedFragment) -> ExtractedFragment {
        var trimmed = fragment
        while let last = trimmed.text.last, last.isWhitespace { trimmed.text.removeLast() }
        let limit = trimmed.text.unicodeScalars.count
        trimmed.spans = trimmed.spans.compactMap { span in
            guard span.start < limit else { return nil }
            return StyledSpan(start: span.start, length: min(span.length, limit - span.start), style: span.style)
        }
        trimmed.footnotes = trimmed.footnotes.map { ExtractedFootnote(position: min($0.position, limit), text: $0.text) }
        return trimmed
    }

    static func merge(_ spans: [StyledSpan]) -> [StyledSpan] {
        var merged: [StyledSpan] = []
        for style in [StyledSpan.Style.wordsOfChrist, .supplied, .smallCaps] {
            let runs = merge(spans.filter { $0.style == style }.map(\.scalarSpan))
            merged += runs.map { StyledSpan(start: $0.start, length: $0.length, style: style) }
        }
        return merged.sorted { ($0.start, $0.length) < ($1.start, $1.length) }
    }

    static func merge(_ spans: [ScalarSpan]) -> [ScalarSpan] {
        let sorted = spans.filter { $0.length > 0 }.sorted { $0.start < $1.start }
        var merged: [ScalarSpan] = []
        for span in sorted {
            if var last = merged.last, span.start <= last.start + last.length {
                last.length = max(last.length, span.start + span.length - last.start)
                merged[merged.count - 1] = last
            } else {
                merged.append(span)
            }
        }
        return merged
    }
}

/// How one spine document numbered its verses. Detected per file, never assumed for the book:
/// publishers mix shapes between front matter, the Gospels and the Psalms in one product.
public enum VerseMarkupShape: String, Sendable, Hashable, Codable, CaseIterable {
    /// `id="ESV_Gen.1.1"`, `id="csb-Gen-1-1"`, `id="MAT.5.3"` — a full reference on the element.
    case referenceIdentifier
    /// `id="v1"`, `id="verse-3"` — a chapter-relative verse anchor.
    case verseAnchor
    /// `class="verse-num"`, `class="vnum"`, `class="v-num"` — a class naming the number.
    case numberClass
    /// `<sup>3</sup>` — a superscript holding nothing but digits.
    case superscript
    /// `<b>3</b>`, `<span class="b">3</span>` — a bold number. The weakest evidence of all, so it
    /// wins only in a file with nothing better.
    case boldNumber
    /// USFM `\c` / `\v` markers — unambiguous, so no detection is needed.
    case usfmMarkers
    /// Nothing recognisable.
    case none

    public var label: String {
        switch self {
        case .referenceIdentifier: "reference ids"
        case .verseAnchor: "verse anchors"
        case .numberClass: "numbered classes"
        case .superscript: "superscript numbers"
        case .boldNumber: "bold numbers"
        case .usfmMarkers: "USFM markers"
        case .none: "no verse markup"
        }
    }
}
#endif
