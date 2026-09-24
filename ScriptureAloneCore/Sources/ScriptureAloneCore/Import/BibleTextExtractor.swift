// Importing is an iPhone, iPad and Mac feature: the watch has no file picker and no
// catalogue. It is also 32-bit (arm64_32), where the ZIP64 sentinel 0xFFFF_FFFF does not
// fit in an Int at all — so this code is not merely unused there, it cannot compile.
#if !os(watchOS)
import Foundation

/// Turns an ePub's spine into `(book, chapter, verse, text)` rows.
///
/// There is no single way publishers mark verses, so nothing is assumed globally. Each spine
/// document is scanned once into a flow of blocks, text runs and *candidate* markers; each
/// candidate records every shape that would explain it; and the shape that explains the most
/// candidates in **that file** wins. A product whose front matter, Gospels and Psalms are marked
/// three different ways therefore reads correctly, and a file whose `<sup>`s hold footnote letters
/// is not mistaken for a file whose `<sup>`s hold verse numbers.
///
/// Shapes handled (`VerseMarkupShape`):
/// - `referenceIdentifier` — `id="ESV_Gen.1.1"`, `id="csb-Gen-1-1"`, `id="MAT.5.3"`, `id="1Cor.13.4"`
/// - `verseAnchor` — `id="v1"`, `id="verse-3"`
/// - `numberClass` — `class="verse-num"`, `class="vnum"`, `class="v-num"`, `class="verse"`
/// - `superscript` — `<sup>3</sup>` holding nothing but a number
///
/// Chapters come from `<h1>`/`<h2>` headings that name a book and/or number, from reference ids,
/// from a chapter-number class, and — when a file declares nothing — from carrying the previous
/// file's place forward, which is what a chapter split across two spine files needs.
public struct BibleTextExtractor: Sendable {
    public struct Options: Sendable {
        /// Keep words-of-Christ spans the ePub marks with a class. The reader already renders them.
        public var redLetters: Bool
        /// Keep section headings ("The Beatitudes") as heading blocks.
        public var headings: Bool
        /// Keep footnote markers, with the note text when the ePub puts it in the same file.
        public var footnotes: Bool

        public init(redLetters: Bool = true, headings: Bool = true, footnotes: Bool = true) {
            self.redLetters = redLetters
            self.headings = headings
            self.footnotes = footnotes
        }
    }

    public var options: Options

    public init(options: Options = Options()) {
        self.options = options
    }

    /// Reads every XHTML document in the spine, in order.
    public func extract(from package: EPUBPackage) throws -> ExtractedBible {
        var assembler = Assembler(options: options)
        let scanner = DocumentScanner(options: options,
                                      styledRedClasses: DocumentScanner.redClasses(inStylesheets: package.stylesheets))
        for item in package.spine {
            let source: String
            do {
                source = try package.document(item)
            } catch let error as BibleImportError {
                assembler.bible.notes.append(ImportNote(.warning, String(localized: "\(item.path) could not be read: \(error.localizedDescription)", bundle: .module, comment: "Import problem. %1$@ is a file name; %2$@ is an error message.")))
                continue
            }
            let document = scanner.scan(source)
            assembler.consume(document, path: item.path)
        }
        assembler.finish()
        guard !assembler.bible.isEmpty else { throw BibleImportError.noScriptureFound }
        return assembler.bible
    }

    /// One document, for tests and for callers that already hold the XHTML.
    public func extract(documents: [(path: String, xhtml: String)]) throws -> ExtractedBible {
        var assembler = Assembler(options: options)
        for document in documents {
            assembler.consume(DocumentScanner(options: options).scan(document.xhtml), path: document.path)
        }
        assembler.finish()
        guard !assembler.bible.isEmpty else { throw BibleImportError.noScriptureFound }
        return assembler.bible
    }
}

// MARK: - Flow

/// What one scanned document turned into: a linear flow, plus the footnote bodies it carried.
struct ScannedDocument: Sendable {
    var flow: [FlowItem] = []
    var notes: [String: String] = [:]
    var shapeCounts: [VerseMarkupShape: Int] = [:]
    /// Anchors that carry their own id and link into another file — the back-link a note body
    /// uses to return to its caller. A document full of them is a notes page, not scripture.
    var backLinks = 0
    /// Chapter numbers recognised by class or id. A file of drop-cap chapters is scripture even
    /// when it prints no other number.
    var chapterMarkers = 0
}

enum FlowItem: Sendable {
    case blockStart(ExtractedBlock.Kind)
    case blockEnd
    case text(String, red: Bool)
    case marker(Marker)
    case heading(String)
    case noteMarker(id: String?, label: String)
    /// The document said which book this is outside its text — `<section title="Romans">`.
    case place(BookID, chapter: Int?)
}

/// A candidate verse or chapter number, with every shape that would explain it.
struct Marker: Sendable {
    var shapes: Set<VerseMarkupShape> = []
    var book: BookID?
    var chapter: Int?
    var verse: Int?
    /// The last number of a bridged marker ("1-2"), whose text belongs to `verse`.
    var through: Int?
    var isChapter: Bool = false
}

// MARK: - Scanning one document

/// Walks one XHTML document into a flow. This pass makes no decision about which markup shape the
/// file uses; it only records what it saw.
struct DocumentScanner: Sendable {
    let options: BibleTextExtractor.Options
    /// Classes the file's own stylesheets color red — words of Christ under whatever name the
    /// publisher chose ("sgc-7", "jesus").
    var styledRedClasses: Set<String> = []

    init(options: BibleTextExtractor.Options, styledRedClasses: Set<String> = []) {
        self.options = options
        self.styledRedClasses = styledRedClasses
    }

    /// Elements whose subtree is never scripture.
    static let skippedElements: Set<String> = ["head", "script", "style", "nav", "svg", "figure", "figcaption"]
    /// `epub:type` values whose subtree is never scripture.
    static let skippedTypes: Set<String> = ["toc", "landmarks", "loi", "lot", "pagebreak", "page-list", "titlepage", "cover", "colophon"]
    /// `epub:type` values that hold a note body.
    static let noteTypes: Set<String> = ["footnote", "rearnote", "endnote", "note", "annotation"]
    static let blockElements: Set<String> = ["p", "div", "li", "blockquote", "h1", "h2", "h3", "h4", "h5", "h6",
                                             "section", "tr", "td", "dd", "dt", "pre", "ul", "ol", "table", "body"]
    static let redClasses: Set<String> = ["wj", "woc", "red", "redletter", "red-letter", "redletters", "red-letters",
                                          "wordsofchrist", "words-of-christ", "christ", "jesus-words", "sc-wj"]

    private enum Capture {
        case candidate(Marker)
        case heading
        case note(String)
        case noteReference(id: String?)
    }

    private struct Frame {
        var name: String
        var capture: Capture?
        var isSkipped: Bool
        var isRed: Bool
        var startedBlock: Bool
        var isExternalLink = false
        /// Where this block's own items begin, so a block that turns out to be only a link to
        /// another file ("Book of 2 Chronicles ⇨") can be taken back out.
        var blockMark: (depth: Int, count: Int)?
    }

    /// Text seen inside one block, split by whether it sat inside a link to another file.
    private struct BlockText {
        var linked = 0
        var plain = 0
    }

    func scan(_ xhtml: String) -> ScannedDocument {
        var document = ScannedDocument()
        var stack: [Frame] = []
        var buffers: [[FlowItem]] = [[]]          // innermost capture buffer is last
        var skipDepth = 0
        var redDepth = 0
        var externalLinkDepth = 0
        var noteDepth = 0
        var blockTexts: [BlockText] = []
        // Text before the first element (a byte-order mark, stray bytes before `<html>`) is not in
        // the document at all. Kept, it would be carried onto the previous file's last verse.
        var seenElement = false

        func emit(_ item: FlowItem) {
            guard skipDepth == 0 else { return }
            buffers[buffers.count - 1].append(item)
        }

        for event in XMLScanner.scan(xhtml) {
            switch event {
            case .start(let tag):
                seenElement = true
                var frame = Frame(name: tag.name, capture: nil, isSkipped: false, isRed: false, startedBlock: false)
                let epubType = tag.epubType
                let classes = Set(tag.classes)

                if Self.skippedElements.contains(tag.name)
                    || epubType.split(separator: " ").contains(where: { Self.skippedTypes.contains(String($0)) })
                    || classes.contains("toc") || classes.contains("footnotes")
                    || classes.contains(where: Self.isNeverScriptureClass) {
                    frame.isSkipped = true
                    skipDepth += 1
                    stack.append(frame)
                    continue
                }
                if skipDepth > 0 {
                    stack.append(frame)
                    continue
                }
                // A line break separates words: "children.<br/>These" is two sentences, not one word.
                if tag.name == "br" {
                    emit(.text(" ", red: redDepth > 0))
                    stack.append(frame)
                    continue
                }

                if options.redLetters, !classes.isDisjoint(with: Self.redClasses) || !classes.isDisjoint(with: styledRedClasses)
                    || epubType.contains("x-woc") || Self.isRed(style: tag.attribute("style")) {
                    frame.isRed = true
                    redDepth += 1
                }

                // A note body: captured aside from the text, keyed by id so markers can find it.
                if epubType.split(separator: " ").contains(where: { Self.noteTypes.contains(String($0)) })
                    || (tag.name == "aside" && !classes.isDisjoint(with: ["footnote", "note", "fn"])) {
                    frame.capture = .note(tag.attribute("id") ?? "")
                    noteDepth += 1
                    buffers.append([])
                    stack.append(frame)
                    continue
                }

                // A footnote marker: an anchor pointing at a note.
                if tag.name == "a", isNoteReference(tag) {
                    frame.capture = .noteReference(id: fragment(of: tag.attribute("href")))
                    buffers.append([])
                    stack.append(frame)
                    continue
                }
                if tag.name == "a", let href = tag.attribute("href"), !href.hasPrefix("#"), !href.isEmpty {
                    if noteDepth == 0, tag.attribute("id") != nil, href.contains("#") { document.backLinks += 1 }
                    frame.isExternalLink = true
                    externalLinkDepth += 1
                }

                // The file's own statement of which book it holds, when it makes one.
                if tag.name == "section" || tag.name == "body",
                   let title = tag.attribute("title"), let place = ScriptureLabels.heading(title), let book = place.book {
                    emit(.place(book, chapter: place.chapter))
                }

                if Self.blockElements.contains(tag.name) {
                    emit(.blockEnd)
                    emit(.blockStart(blockKind(tag)))
                    frame.startedBlock = true
                    if skipDepth == 0 {
                        frame.blockMark = (buffers.count - 1, buffers[buffers.count - 1].count)
                        blockTexts.append(BlockText())
                    }
                }

                if isHeading(tag) {
                    frame.capture = .heading
                    buffers.append([])
                    stack.append(frame)
                    continue
                }

                if var marker = candidate(tag) {
                    // A reference id is a fact about the element, not a guess, so it is emitted
                    // even when the element holds the verse text rather than the number.
                    if marker.shapes.contains(.referenceIdentifier) || marker.shapes.contains(.verseAnchor),
                       !isNumberLike(tag) {
                        marker.shapes.remove(.numberClass)
                        marker.shapes.remove(.superscript)
                        marker.shapes.remove(.boldNumber)
                        record(&document, marker)
                        emit(.marker(marker))
                        stack.append(frame)
                        continue
                    }
                    frame.capture = .candidate(marker)
                    buffers.append([])
                    stack.append(frame)
                    continue
                }
                stack.append(frame)

            case .end(let name):
                guard let index = stack.lastIndex(where: { $0.name == name }) else { continue }
                // Close everything the document forgot to close.
                while stack.count > index {
                    let frame = stack.removeLast()
                    if frame.isSkipped {
                        skipDepth = max(0, skipDepth - 1)
                        continue
                    }
                    if frame.isRed { redDepth = max(0, redDepth - 1) }
                    if frame.isExternalLink { externalLinkDepth = max(0, externalLinkDepth - 1) }
                    if case .note = frame.capture { noteDepth = max(0, noteDepth - 1) }
                    if let capture = frame.capture, buffers.count > 1 {
                        let captured = buffers.removeLast()
                        close(capture, captured: captured, into: &document, emit: emit)
                    }
                    if let mark = frame.blockMark, let text = blockTexts.popLast() {
                        // A paragraph that is nothing but a link to another file is navigation.
                        if text.plain == 0, text.linked > 0, mark.depth == buffers.count - 1,
                           mark.count <= buffers[mark.depth].count,
                           !buffers[mark.depth][mark.count...].contains(where: \.isMarker) {
                            buffers[mark.depth].removeSubrange(mark.count...)
                        }
                    }
                    if frame.startedBlock { emit(.blockEnd) }
                }

            case .text(let text):
                guard skipDepth == 0, seenElement else { continue }
                let collapsed = Self.collapse(text)
                guard !collapsed.isEmpty else { continue }
                if !blockTexts.isEmpty, collapsed != " " {
                    if externalLinkDepth > 0 {
                        blockTexts[blockTexts.count - 1].linked += collapsed.count
                    } else {
                        blockTexts[blockTexts.count - 1].plain += collapsed.count
                    }
                }
                emit(.text(collapsed, red: redDepth > 0))
            }
        }
        while buffers.count > 1 {
            let captured = buffers.removeLast()
            buffers[buffers.count - 1].append(contentsOf: captured)
        }
        document.flow = buffers[0]
        return document
    }

    /// Decides what a captured element actually was, now that its contents are known.
    private func close(_ capture: Capture, captured: [FlowItem], into document: inout ScannedDocument,
                       emit: (FlowItem) -> Void) {
        let text = Self.plainText(captured)
        switch capture {
        case .candidate(var marker):
            if let parsed = Self.verseNumber(in: text) {
                if marker.isChapter {
                    marker.chapter = marker.chapter ?? parsed.number
                } else {
                    marker.verse = marker.verse ?? parsed.number
                    marker.through = marker.through ?? parsed.through
                }
                record(&document, marker)
                emit(.marker(marker))
                return
            }
            // A superscript footnote letter ("said<sup>b</sup>") whose note is not linked: it is
            // neither a verse number nor text, so it goes, rather than reading "saidb".
            if marker.shapes.contains(.superscript), Self.isFootnoteLabel(text) { return }
            // Not a number after all: it was an ordinary span that happened to be called "verse".
            for item in captured { emit(item) }
        case .heading:
            // A "title" that holds a chapter or verse number is where the chapter starts — a
            // psalm's number and superscription, or its first line — not a heading above it.
            if captured.contains(where: \.isMarker) {
                for item in captured { emit(item) }
                return
            }
            let trimmed = text.trimmingCharacters(in: .whitespaces)
            if !trimmed.isEmpty { emit(.heading(trimmed)) }
        case .note(let id):
            let trimmed = text.trimmingCharacters(in: .whitespaces)
            if !id.isEmpty, !trimmed.isEmpty { document.notes[id] = trimmed }
        case .noteReference(let id):
            guard options.footnotes else { return }
            emit(.noteMarker(id: id, label: text.trimmingCharacters(in: .whitespaces)))
        }
    }

    private func record(_ document: inout ScannedDocument, _ marker: Marker) {
        guard !marker.isChapter else {
            // Only a number the typesetter styled as a chapter number: ids like "genesis1" also
            // label introductions and outlines, which are not scripture.
            if marker.shapes.contains(.numberClass) { document.chapterMarkers += 1 }
            return
        }
        for shape in marker.shapes {
            document.shapeCounts[shape, default: 0] += 1
        }
    }

    // MARK: Element questions

    private func isHeading(_ tag: XMLTag) -> Bool {
        if ["h1", "h2", "h3", "h4", "h5", "h6"].contains(tag.name) { return true }
        let classes = tag.classes
        return classes.contains { name in
            name.contains("heading") || name.contains("subhead") || name.contains("title")
                || name == "speaker" || name.hasPrefix("speaker-") || name == "psalm-book"
                || name == "acrostic" || name.hasPrefix("acrostic-")
        }
    }

    /// Classes whose subtree is never the translation's text: cross-reference callers, study notes,
    /// essay boxes set into the text, and image captions.
    static func isNeverScriptureClass(_ name: String) -> Bool {
        let squashed = name.replacingOccurrences(of: "-", with: "").replacingOccurrences(of: "_", with: "")
        return squashed.contains("crossref") || squashed == "xref" || squashed.hasPrefix("xref")
            || squashed.contains("studynote") || squashed.contains("sidebar")
            || (squashed.hasSuffix("box") && squashed.count <= 12)
            || squashed == "image" || squashed.contains("caption") || squashed.contains("illustration")
    }

    private func blockKind(_ tag: XMLTag) -> ExtractedBlock.Kind {
        let classes = tag.classes
        if classes.contains(where: { $0.hasPrefix("q2") || $0.contains("line2") || $0.contains("indent2")
                || ($0.contains("poet") && $0.contains("indent")) }) {
            return .poetry2
        }
        if classes.contains(where: { $0.hasPrefix("q1") || $0.contains("poet") || $0.contains("line1") || $0 == "line" || $0 == "stanza" }) {
            return .poetry1
        }
        switch tag.name {
        case "li": return .list1
        case "blockquote": return .embedded
        default: return .paragraph
        }
    }

    /// Does this element's class or tag suggest it holds only a number?
    private func isNumberLike(_ tag: XMLTag) -> Bool {
        if tag.name == "sup" || tag.name == "b" || tag.name == "strong" { return true }
        return tag.classes.contains { $0.contains("num") || $0 == "v" || $0 == "vn" || $0 == "b" || $0 == "bold" }
    }

    /// Every shape that would explain this element as a verse or chapter marker.
    private func candidate(_ tag: XMLTag) -> Marker? {
        var marker = Marker()
        let classes = tag.classes

        if let id = tag.attribute("id") ?? tag.attribute("data-id"), let parsed = ScriptureLabels.identifier(id) {
            switch parsed {
            case .verse(let book, let chapter, let verse):
                marker.shapes.insert(.referenceIdentifier)
                marker.book = book
                marker.chapter = chapter
                marker.verse = verse
            case .chapter(let book, let chapter):
                marker.shapes.insert(.referenceIdentifier)
                marker.book = book
                marker.chapter = chapter
                marker.isChapter = true
            case .verseNumber(let verse):
                marker.shapes.insert(.verseAnchor)
                marker.verse = verse
            case .chapterNumber(let chapter):
                marker.shapes.insert(.verseAnchor)
                marker.chapter = chapter
                marker.isChapter = true
            }
        }
        if classes.contains(where: Self.isChapterNumberClass) {
            marker.shapes.insert(.numberClass)
            marker.isChapter = true
        } else if classes.contains(where: Self.isVerseNumberClass) {
            marker.shapes.insert(.numberClass)
        }
        if tag.name == "sup", !marker.isChapter {
            marker.shapes.insert(.superscript)
        }
        if !marker.isChapter, tag.name == "b" || tag.name == "strong" || classes.contains(where: { ["b", "bold", "strong"].contains($0) }) {
            marker.shapes.insert(.boldNumber)
        }
        return marker.shapes.isEmpty ? nil : marker
    }

    static func isVerseNumberClass(_ name: String) -> Bool {
        if ["v", "vn", "vnum", "vnumber", "verse", "verses", "versenum", "verseno", "versenumber", "vers"].contains(name) {
            return true
        }
        // "verse-num", "v-num", "verse_number", "vNum1", "bibleverse-number"
        let squashed = name.replacingOccurrences(of: "-", with: "").replacingOccurrences(of: "_", with: "")
        if squashed.hasPrefix("verse") || squashed.hasPrefix("vnum") || squashed.hasPrefix("vno") { return true }
        return squashed.contains("versenum") || squashed.contains("verseno")
    }

    static func isChapterNumberClass(_ name: String) -> Bool {
        let squashed = name.replacingOccurrences(of: "-", with: "").replacingOccurrences(of: "_", with: "")
        return ["c", "cnum", "chapnum", "chapternum", "chapternumber", "chapter", "chap"].contains(squashed)
            || squashed.hasPrefix("chapnum") || squashed.hasPrefix("chapternum")
    }

    private func isNoteReference(_ tag: XMLTag) -> Bool {
        let epubType = tag.epubType
        if epubType.contains("noteref") { return true }
        let classes = tag.classes
        if classes.contains(where: { $0.contains("note") || $0 == "fn" || $0.hasPrefix("fn-") || $0.contains("footnote") }) {
            return true
        }
        guard let href = tag.attribute("href") else { return false }
        let target = fragment(of: href)?.lowercased() ?? ""
        return target.hasPrefix("note") || target.hasPrefix("fn") || target.hasPrefix("footnote")
    }

    private func fragment(of href: String?) -> String? {
        guard let href, let hash = href.firstIndex(of: "#") else { return nil }
        let value = String(href[href.index(after: hash)...])
        return value.isEmpty ? nil : value
    }

    // MARK: Red letters

    /// Class names whose CSS rule sets a red text color: `.wj { color: #c00 }`,
    /// `span.sgc-7 { color: rgb(200, 30, 30) }`. Rules with descendant or state selectors are
    /// ignored — only a rule the class alone decides.
    static func redClasses(inStylesheets sheets: [String]) -> Set<String> {
        var found: Set<String> = []
        for sheet in sheets {
            let css = sheet.replacing(/\/\*[\s\S]*?\*\//, with: "")
            for rule in css.split(separator: "}") {
                let parts = rule.split(separator: "{", maxSplits: 1)
                guard parts.count == 2, isRed(style: String(parts[1])) else { continue }
                for selector in parts[0].split(separator: ",") {
                    let trimmed = selector.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !trimmed.contains(" "), !trimmed.contains(":"), !trimmed.contains(">"),
                          let dot = trimmed.lastIndex(of: ".") else { continue }
                    let name = trimmed[trimmed.index(after: dot)...]
                    if !name.isEmpty, name.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }) {
                        found.insert(String(name).lowercased())
                    }
                }
            }
        }
        return found
    }

    /// Does a declaration block set the text color to something a reader would call red?
    static func isRed(style: String?) -> Bool {
        guard let style else { return false }
        for declaration in style.lowercased().split(separator: ";") {
            let pair = declaration.split(separator: ":", maxSplits: 1)
            guard pair.count == 2, pair[0].trimmingCharacters(in: .whitespaces) == "color" else { continue }
            let value = pair[1].trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "!important", with: "")
                .trimmingCharacters(in: .whitespaces)
            if ["red", "darkred", "firebrick", "crimson", "maroon", "brown"].contains(value) { return true }
            if let rgb = rgb(value) {
                return rgb.r >= 0x80 && rgb.g <= rgb.r / 2 && rgb.b <= rgb.r / 2
            }
        }
        return false
    }

    private static func rgb(_ value: String) -> (r: Int, g: Int, b: Int)? {
        if value.hasPrefix("#") {
            var hex = String(value.dropFirst())
            if hex.count == 3 { hex = hex.map { "\($0)\($0)" }.joined() }
            guard hex.count == 6, let n = Int(hex, radix: 16) else { return nil }
            return (n >> 16 & 0xFF, n >> 8 & 0xFF, n & 0xFF)
        }
        guard value.hasPrefix("rgb"), let open = value.firstIndex(of: "("), let close = value.firstIndex(of: ")") else { return nil }
        let numbers = value[value.index(after: open)..<close].split(separator: ",").compactMap {
            Int($0.trimmingCharacters(in: .whitespaces))
        }
        return numbers.count >= 3 ? (numbers[0], numbers[1], numbers[2]) : nil
    }

    // MARK: Text helpers

    /// Collapses every whitespace run to one space, keeping the leading/trailing one that separates
    /// inline elements.
    static func collapse(_ raw: String) -> String {
        var output = ""
        output.reserveCapacity(raw.count)
        var pendingSpace = false
        for character in raw {
            // A soft hyphen is a hint for where a line may break, not text. Left in, "be\u{00AD}ginning"
            // no longer matches a search for "beginning" (FTS5's tokenizer splits on it).
            if character == "\u{00AD}" { continue }
            if character.isWhitespace || character == "\u{00A0}" {
                pendingSpace = true
                continue
            }
            if pendingSpace {
                output.append(" ")
                pendingSpace = false
            }
            output.append(character)
        }
        if pendingSpace { output.append(" ") }
        return output
    }

    static func plainText(_ items: [FlowItem]) -> String {
        var output = ""
        for item in items {
            if case .text(let text, _) = item { output += text }
            if case .heading(let text) = item { output += text }
        }
        return output
    }

    /// "12", "[12]", "1-2", "1–2" — a verse number, and the last number of a bridged pair. A
    /// dangling separator ("7–", a bridge the typesetter split across two superscripts) is the
    /// number alone.
    static func verseNumber(in raw: String) -> (number: Int, through: Int?)? {
        let separators: Set<Character> = ["-", "\u{2010}", "\u{2011}", "\u{2012}", "\u{2013}", "\u{2014}"]
        if let split = raw.firstIndex(where: { separators.contains($0) }),
           let first = number(in: String(raw[raw.startIndex..<split])) {
            let rest = String(raw[raw.index(after: split)...])
            if let last = number(in: rest), last > first, last - first < 20 {
                return (first, last)
            }
            if rest.trimmingCharacters(in: numberTrim).isEmpty {
                return (first, nil)
            }
        }
        return number(in: raw).map { ($0, nil) }
    }

    /// A footnote or cross-reference caller printed as a superscript: "a", "b", "aa", "[c]", "*",
    /// "†". Ordinal endings ("1<sup>st</sup>") are not callers.
    static func isFootnoteLabel(_ raw: String) -> Bool {
        let label = raw.trimmingCharacters(in: CharacterSet(charactersIn: " \u{00A0}[]()\n\t"))
        guard !label.isEmpty, label.count <= 3 else { return false }
        // "*", "†", "✞": a caller made only of symbols.
        if !label.contains(where: { $0.isLetter || $0.isNumber }) { return true }
        guard label.count <= 2, label.allSatisfy({ $0.isASCII && $0.isLetter }) else { return false }
        return !["st", "nd", "rd", "th"].contains(label.lowercased())
    }

    static let numberTrim = CharacterSet(charactersIn: " \u{00A0}[](){}.,:;·•*\u{200B}\n\t")

    /// "12", " 12 ", "[12]", "12.", "12 " -> 12. Anything else -> nil.
    static func number(in raw: String) -> Int? {
        let stripped = raw.trimmingCharacters(in: numberTrim)
        guard !stripped.isEmpty, stripped.count <= 3, stripped.allSatisfy(\.isNumber), let value = Int(stripped),
              value > 0, value < 1000 else { return nil }
        return value
    }
}

extension FlowItem {
    var isMarker: Bool {
        if case .marker = self { return true }
        return false
    }
}

// MARK: - Assembling documents into a Bible

/// Walks each scanned document's flow with the winning markup shape and lays the text into
/// chapters, blocks, fragments and verses.
struct Assembler {
    let options: BibleTextExtractor.Options
    var bible = ExtractedBible()

    private var book: BookID?
    private var chapter: Int?
    private var verse: Int?
    private var block: ExtractedBlock?
    private var blockKind: ExtractedBlock.Kind = .paragraph
    private var fragmentIndex: Int?
    private var outOfOrder: Set<ChapterRef> = []
    private var bridged: [VerseRef: VerseRef] = [:]
    private var lastVerseNumber = 0
    private var skippedText = 0
    /// A chapter number was just set; the text that follows it, before any verse number, is
    /// verse 1 — the drop-cap layout, where "1" is never printed.
    private var awaitingFirstVerse = false
    /// The current verse 1 was begun by `awaitingFirstVerse`, so a "1" that follows is the same verse.
    private var verseIsImplicit = false
    /// Section headings seen since the last verse. They belong above the verse that follows, which
    /// may be in the next chapter: "The Fall" comes before the "3" that starts Genesis 3.
    private var pendingHeadings: [String] = []
    /// A chapter number printed early, ahead of the last verse of the chapter before it (one
    /// publisher sets "8" before John 7:53). That verse is filed under its own chapter, and the
    /// new chapter resumes at its verse 1.
    private var deferredChapter: Int?

    init(options: BibleTextExtractor.Options) {
        self.options = options
    }

    var chapterRef: ChapterRef? {
        guard let book, let chapter, chapter > 0 else { return nil }
        return ChapterRef(book, chapter)
    }

    mutating func consume(_ document: ScannedDocument, path: String) {
        let shape = Self.winningShape(document.shapeCounts)
        bible.shapesByDocument[path] = shape
        closeBlock()

        // A file that declares nothing continues the previous one — that is what a chapter split
        // across two spine files looks like. A file name that names a book is a weak hint, used
        // only when the file itself says nothing.
        let hint = Self.hint(path: path)
        var documentDeclaredPlace = false
        awaitingFirstVerse = false

        // A document with no verse markup (an introduction, an outline, study notes) or whose
        // paragraphs link back to another file (a page of footnotes or cross references) holds no
        // scripture. It may still say which book comes next, and nothing else is taken from it.
        var evidence = (document.shapeCounts[shape] ?? 0) + document.chapterMarkers
        // Bold and superscript numbers are everywhere — a concordance is full of them. They count
        // only when they run the way verse numbers do: each one after the last, or back to 1.
        if shape == .boldNumber || shape == .superscript, !Self.runsLikeVerses(document.flow, shape: shape) {
            evidence = document.chapterMarkers
        }
        guard evidence > 0, document.backLinks < evidence else {
            for item in document.flow {
                switch item {
                case .place(let newBook, _) where newBook != book && !bible.hasVerses(in: newBook):
                    move(to: newBook, chapter: nil)
                case .heading(let text):
                    if let place = ScriptureLabels.heading(text), let newBook = place.book, newBook != book {
                        move(to: newBook, chapter: place.chapter)
                    }
                default:
                    continue
                }
            }
            return
        }

        // The book the file's own title named. An element id that names another book is a label
        // that happens to parse ("john3" wrapping 3 John), not a move.
        var titledBook: BookID?

        for item in document.flow {
            switch item {
            case .place(let newBook, let newChapter):
                titledBook = newBook
                // A file's title is a label someone typed; a title naming a book already read is
                // a copy-paste slip (one publisher's Exodus 28–40 is titled "Genesis"), not a return.
                if newBook != book, !bible.hasVerses(in: newBook) {
                    move(to: newBook, chapter: newChapter)
                    pendingHeadings = []
                }
                documentDeclaredPlace = true
            case .blockStart(let kind):
                closeBlock()
                blockKind = kind
            case .blockEnd:
                closeBlock()
            case .heading(let text):
                if let place = ScriptureLabels.heading(text), place.book != nil || place.chapter != nil {
                    move(to: place.book, chapter: place.chapter)
                    documentDeclaredPlace = true
                } else if options.headings, book != nil {
                    pendingHeadings.append(text)
                }
            case .marker(var marker):
                if let titledBook, let named = marker.book, named != titledBook {
                    if marker.isChapter { continue }
                    marker.book = nil
                    marker.chapter = nil
                }
                let accepted = marker.isChapter || marker.shapes.contains(shape)
                if marker.isChapter {
                    move(to: marker.book, chapter: marker.chapter)
                    documentDeclaredPlace = true
                    awaitingFirstVerse = book != nil && chapter != nil && verse == nil
                    continue
                }
                guard accepted else {
                    // A rejected marker can still tell us where we are, when nothing else has.
                    if book == nil, let markerBook = marker.book {
                        move(to: markerBook, chapter: marker.chapter)
                        documentDeclaredPlace = true
                    }
                    continue
                }
                if marker.book != nil || (marker.chapter != nil && marker.chapter != chapter) {
                    move(to: marker.book, chapter: marker.chapter)
                    documentDeclaredPlace = true
                }
                if book == nil, let hintBook = hint.book {
                    move(to: hintBook, chapter: hint.chapter)
                }
                start(verse: marker.verse, through: marker.through)
            case .text(let text, let red):
                if awaitingFirstVerse, verse == nil, !text.trimmingCharacters(in: .whitespaces).isEmpty {
                    start(verse: 1)
                    verseIsImplicit = true
                }
                append(text, red: red && options.redLetters)
            case .noteMarker(let id, let label):
                guard options.footnotes,
                      let body = Self.footnoteBody(id.flatMap { document.notes[$0] }, label: label) else { continue }
                if awaitingFirstVerse, verse == nil {
                    start(verse: 1)
                    verseIsImplicit = true
                }
                addFootnote(body)
            }
        }
        closeBlock()
        if !documentDeclaredPlace, bible.shapesByDocument[path] == VerseMarkupShape.none, hint.book != nil {
            // A file we could not read at all, whose name promised a book, is worth saying aloud.
            bible.notes.append(ImportNote(.info, String(localized: "\(path) named a book but carried no verse markup.", bundle: .module, comment: "Import note. %@ is a file name inside the ePub.")))
        }
    }

    mutating func finish() {
        closeBlock()
        bible.tidy()
        bible.outOfOrderChapters = outOfOrder
        bible.bridgedVerses = bridged
        if !bridged.isEmpty {
            bible.notes.append(ImportNote(.info, String(localized: "\(bridged.count) verse(s) are printed combined with the verse before them.", bundle: .module, comment: "Import note. %lld is a number of verses.")))
        }
        for chapter in outOfOrder.sorted() {
            bible.notes.append(ImportNote(.warning, String(localized: "\(chapter.display): verse numbers ran out of order.", bundle: .module, comment: "Import problem. %@ is a chapter reference, e.g. “John 3”.")))
        }
        if skippedText > 0 {
            bible.notes.append(ImportNote(.warning,
                                          String(localized: "\(skippedText) run(s) of text were dropped because no book or verse was in scope.", bundle: .module, comment: "Import problem. %lld is a number of passages of text.")))
        }
    }

    static func winningShape(_ counts: [VerseMarkupShape: Int]) -> VerseMarkupShape {
        // Ties go to the most explicit shape: an id that names book, chapter and verse beats a
        // class, which beats a bare superscript.
        let order: [VerseMarkupShape] = [.referenceIdentifier, .verseAnchor, .numberClass, .superscript, .boldNumber]
        var best = VerseMarkupShape.none
        var bestCount = 0
        for shape in order {
            let count = counts[shape] ?? 0
            if count > bestCount {
                best = shape
                bestCount = count
            }
        }
        return best
    }

    static func runsLikeVerses(_ flow: [FlowItem], shape: VerseMarkupShape) -> Bool {
        var previous = 0
        var steps = 0
        var total = 0
        for case .marker(let marker) in flow where !marker.isChapter && marker.shapes.contains(shape) {
            guard let number = marker.verse else { continue }
            total += 1
            // The first number may start anywhere: a file can open mid-chapter.
            if total == 1 || number == previous + 1 || number == 1 || number == previous + 2 { steps += 1 }
            previous = marker.through ?? number
        }
        return total > 0 && Double(steps) / Double(total) >= 0.6
    }

    /// What a footnote marker should carry, or nil when it carries nothing worth keeping.
    ///
    /// A caller whose note cannot be found is dropped rather than kept as its own label ("a",
    /// "[✞]"). A note that opens by naming the passage it discusses ("ROMANS 1:1 Paul.",
    /// "3:16 For God") is a study Bible's commentary, not the translation's own footnote.
    static func footnoteBody(_ note: String?, label: String) -> String? {
        let caller = label.trimmingCharacters(in: .whitespaces)
        guard var body = note?.trimmingCharacters(in: .whitespaces), !body.isEmpty else {
            return caller.count > 3 ? caller : nil
        }
        // "1 Or brothers" under a caller "1": the note repeats its own label.
        if !caller.isEmpty, body.hasPrefix(caller + " ") {
            body = String(body.dropFirst(caller.count + 1)).trimmingCharacters(in: .whitespaces)
        }
        guard !body.isEmpty, !isCommentary(body) else { return nil }
        return body
    }

    static func isCommentary(_ body: String) -> Bool {
        let head = String(body.prefix(48))
        if head.firstMatch(of: /^\d{1,3}:\d{1,3}/) != nil { return true }
        let tokens = ScriptureLabels.normalizedTokens(head)
        guard tokens.count >= 2 else { return false }
        for width in 1...min(3, tokens.count - 1) where tokens[width].allSatisfy(\.isNumber) {
            let phrase = ReferenceParser.normalizeOrdinals(tokens[0..<width].joined(separator: " "))
            if ScriptureLabels.exactBook(BookInfo.normalize(phrase)) != nil { return true }
        }
        return false
    }

    static func hint(path: String) -> (book: BookID?, chapter: Int?) {
        let file = path.split(separator: "/").last.map(String.init) ?? path
        let stem = file.split(separator: ".").first.map(String.init) ?? file
        guard let place = ScriptureLabels.fileStem(stem) else { return (nil, nil) }
        return (place.book, place.chapter)
    }

    // MARK: Position

    private mutating func move(to newBook: BookID?, chapter newChapter: Int?) {
        closeBlock()
        deferredChapter = nil
        if let newBook, newBook != book {
            book = newBook
            chapter = newChapter ?? 1
            verse = nil
            lastVerseNumber = 0
            return
        }
        if let newChapter, newChapter != chapter {
            chapter = newChapter
            verse = nil
            lastVerseNumber = 0
        } else if newBook != nil, chapter == nil {
            chapter = newChapter ?? 1
        }
    }

    private mutating func start(verse number: Int?, through: Int? = nil) {
        awaitingFirstVerse = false
        guard let number, let book else {
            if verse == nil { skippedText += 1 }
            return
        }
        if verseIsImplicit {
            verseIsImplicit = false
            // The "1" printed after a chapter number that already began verse 1.
            if number == 1, verse == 1 { return }
        }
        if chapter == nil { chapter = 1 }
        if let resume = deferredChapter {
            if number == 1 || number < lastVerseNumber {
                closeBlock()
                chapter = resume
                lastVerseNumber = 0
                deferredChapter = nil
            }
        } else if number > 1, lastVerseNumber == 0, let current = chapter, current > 1, bible.highestVerse(in: ChapterRef(book, current)) == 0,
                  bible.highestVerse(in: ChapterRef(book, current - 1)) == number - 1 {
            closeBlock()
            deferredChapter = current
            chapter = current - 1
            lastVerseNumber = number - 1
        }
        // A verse number we already have means the file moved on to the next chapter without
        // saying so — the common shape when chapter numbers are drop-caps we did not recognise.
        if let current = chapterRef, bible.verses[VerseRef(book, current.chapter, number)] != nil {
            closeBlock()
            chapter = (chapter ?? 1) + 1
            lastVerseNumber = 0
        } else if number < lastVerseNumber, let current = chapterRef {
            outOfOrder.insert(current)
        }
        if !pendingHeadings.isEmpty, let current = chapterRef {
            closeBlock()
            for heading in pendingHeadings {
                bible.append(ExtractedBlock(kind: .heading, heading: heading), to: current)
            }
            pendingHeadings = []
        }
        verse = number
        lastVerseNumber = number
        if let through, let current = chapterRef {
            for extra in (number + 1)...through {
                bridged[VerseRef(book, current.chapter, extra)] = VerseRef(book, current.chapter, number)
            }
        }
        openBlockIfNeeded()
        block?.fragments.append(ExtractedFragment(verse: number, numbered: true, text: ""))
        fragmentIndex = (block?.fragments.count ?? 1) - 1
    }

    // MARK: Text

    private mutating func openBlockIfNeeded() {
        if block == nil { block = ExtractedBlock(kind: blockKind) }
    }

    private mutating func closeBlock() {
        defer {
            block = nil
            fragmentIndex = nil
        }
        guard var finished = block, let chapterRef else { return }
        finished.fragments = finished.fragments.map { fragment in
            var trimmed = fragment
            trimmed.text = Self.trimTrailing(trimmed.text)
            return trimmed
        }
        guard !finished.isEmpty else { return }
        bible.append(finished, to: chapterRef)
    }

    private mutating func append(_ text: String, red: Bool) {
        guard let book, let verse, let chapter else {
            if !text.trimmingCharacters(in: .whitespaces).isEmpty { skippedText += 1 }
            return
        }
        openBlockIfNeeded()
        if fragmentIndex == nil || block!.fragments.isEmpty {
            block!.fragments.append(ExtractedFragment(verse: verse, numbered: false, text: ""))
            fragmentIndex = block!.fragments.count - 1
        }
        let index = fragmentIndex!
        var chunk = text
        if block!.fragments[index].text.isEmpty || block!.fragments[index].text.hasSuffix(" ") {
            while chunk.hasPrefix(" ") { chunk.removeFirst() }
        }
        guard !chunk.isEmpty else { return }
        let startsFragment = block!.fragments[index].text.isEmpty
        let start = block!.fragments[index].text.unicodeScalars.count
        block!.fragments[index].text += chunk
        var spans: [ScalarSpan] = []
        if red {
            block!.fragments[index].spans.append(StyledSpan(start: start, length: chunk.unicodeScalars.count,
                                                            style: .wordsOfChrist))
            block!.fragments[index].spans = ExtractedBible.merge(block!.fragments[index].spans)
            spans = [ScalarSpan(start: 0, length: chunk.unicodeScalars.count)]
        }
        bible.appendVerseText(chunk, red: spans, to: VerseRef(book, chapter, verse), separate: startsFragment)
    }

    private mutating func addFootnote(_ body: String) {
        guard !body.isEmpty, let index = fragmentIndex, var current = block, index < current.fragments.count else { return }
        let position = current.fragments[index].text.unicodeScalars.count
        current.fragments[index].footnotes.append(ExtractedFootnote(position: position, text: body))
        block = current
    }

    static func trimTrailing(_ text: String) -> String {
        var trimmed = text
        while let last = trimmed.last, last.isWhitespace { trimmed.removeLast() }
        return trimmed
    }
}
#endif
