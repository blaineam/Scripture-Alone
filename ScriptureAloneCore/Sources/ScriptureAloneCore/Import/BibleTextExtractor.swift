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
        for item in package.spine {
            let source: String
            do {
                source = try package.document(item)
            } catch let error as BibleImportError {
                assembler.bible.notes.append(ImportNote(.warning, "\(item.path) could not be read: \(error.localizedDescription)"))
                continue
            }
            let document = DocumentScanner(options: options).scan(source)
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
}

enum FlowItem: Sendable {
    case blockStart(ExtractedBlock.Kind)
    case blockEnd
    case text(String, red: Bool)
    case marker(Marker)
    case heading(String)
    case noteMarker(id: String?, label: String)
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
    }

    func scan(_ xhtml: String) -> ScannedDocument {
        var document = ScannedDocument()
        var stack: [Frame] = []
        var buffers: [[FlowItem]] = [[]]          // innermost capture buffer is last
        var skipDepth = 0
        var redDepth = 0

        func emit(_ item: FlowItem) {
            guard skipDepth == 0 else { return }
            buffers[buffers.count - 1].append(item)
        }

        for event in XMLScanner.scan(xhtml) {
            switch event {
            case .start(let tag):
                var frame = Frame(name: tag.name, capture: nil, isSkipped: false, isRed: false, startedBlock: false)
                let epubType = tag.epubType
                let classes = Set(tag.classes)

                if Self.skippedElements.contains(tag.name)
                    || epubType.split(separator: " ").contains(where: { Self.skippedTypes.contains(String($0)) })
                    || classes.contains("toc") || classes.contains("footnotes") {
                    frame.isSkipped = true
                    skipDepth += 1
                    stack.append(frame)
                    continue
                }
                if skipDepth > 0 {
                    stack.append(frame)
                    continue
                }

                if options.redLetters, !classes.isDisjoint(with: Self.redClasses) || epubType.contains("x-woc") {
                    frame.isRed = true
                    redDepth += 1
                }

                // A note body: captured aside from the text, keyed by id so markers can find it.
                if epubType.split(separator: " ").contains(where: { Self.noteTypes.contains(String($0)) })
                    || (tag.name == "aside" && !classes.isDisjoint(with: ["footnote", "note", "fn"])) {
                    frame.capture = .note(tag.attribute("id") ?? "")
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

                if Self.blockElements.contains(tag.name) {
                    emit(.blockEnd)
                    emit(.blockStart(blockKind(tag)))
                    frame.startedBlock = true
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
                    if let capture = frame.capture, buffers.count > 1 {
                        let captured = buffers.removeLast()
                        close(capture, captured: captured, into: &document, emit: emit)
                    }
                    if frame.startedBlock { emit(.blockEnd) }
                }

            case .text(let text):
                guard skipDepth == 0 else { continue }
                let collapsed = Self.collapse(text)
                guard !collapsed.isEmpty else { continue }
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
            // Not a number after all: it was an ordinary span that happened to be called "verse".
            for item in captured { emit(item) }
        case .heading:
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
        guard !marker.isChapter else { return }
        for shape in marker.shapes {
            document.shapeCounts[shape, default: 0] += 1
        }
    }

    // MARK: Element questions

    private func isHeading(_ tag: XMLTag) -> Bool {
        if ["h1", "h2", "h3", "h4", "h5", "h6"].contains(tag.name) { return true }
        let classes = tag.classes
        return classes.contains { name in
            name.contains("heading") || name.contains("subhead") || name.contains("section-title")
                || name == "sectiontitle" || name == "psalm-title" || name == "booktitle"
        }
    }

    private func blockKind(_ tag: XMLTag) -> ExtractedBlock.Kind {
        let classes = tag.classes
        if classes.contains(where: { $0.hasPrefix("q2") || $0.contains("line2") || $0.contains("indent2") }) { return .poetry2 }
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
        if tag.name == "sup" { return true }
        return tag.classes.contains { $0.contains("num") || $0 == "v" || $0 == "vn" }
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

    // MARK: Text helpers

    /// Collapses every whitespace run to one space, keeping the leading/trailing one that separates
    /// inline elements.
    static func collapse(_ raw: String) -> String {
        var output = ""
        output.reserveCapacity(raw.count)
        var pendingSpace = false
        for character in raw {
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

    /// "12", "[12]", "1-2", "1–2" — a verse number, and the last number of a bridged pair.
    static func verseNumber(in raw: String) -> (number: Int, through: Int?)? {
        let separators: Set<Character> = ["-", "\u{2010}", "\u{2011}", "\u{2012}", "\u{2013}", "\u{2014}"]
        if let split = raw.firstIndex(where: { separators.contains($0) }),
           let first = number(in: String(raw[raw.startIndex..<split])),
           let last = number(in: String(raw[raw.index(after: split)...])),
           last > first, last - first < 20 {
            return (first, last)
        }
        return number(in: raw).map { ($0, nil) }
    }

    /// "12", " 12 ", "[12]", "12.", "12 " -> 12. Anything else -> nil.
    static func number(in raw: String) -> Int? {
        let stripped = raw.trimmingCharacters(in: CharacterSet(charactersIn: " \u{00A0}[](){}.,:;·•*\u{200B}\n\t"))
        guard !stripped.isEmpty, stripped.count <= 3, stripped.allSatisfy(\.isNumber), let value = Int(stripped),
              value > 0, value < 1000 else { return nil }
        return value
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

        for item in document.flow {
            switch item {
            case .blockStart(let kind):
                closeBlock()
                blockKind = kind
            case .blockEnd:
                closeBlock()
            case .heading(let text):
                if let place = ScriptureLabels.heading(text), place.book != nil || place.chapter != nil {
                    move(to: place.book, chapter: place.chapter)
                    documentDeclaredPlace = true
                } else if options.headings, chapterRef != nil {
                    closeBlock()
                    bible.append(ExtractedBlock(kind: .heading, heading: text), to: chapterRef!)
                }
            case .marker(let marker):
                let accepted = marker.isChapter || marker.shapes.contains(shape)
                if marker.isChapter {
                    move(to: marker.book, chapter: marker.chapter)
                    documentDeclaredPlace = true
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
                append(text, red: red && options.redLetters)
            case .noteMarker(let id, let label):
                guard options.footnotes else { continue }
                let body = id.flatMap { document.notes[$0] } ?? label
                addFootnote(body)
            }
        }
        closeBlock()
        if !documentDeclaredPlace, bible.shapesByDocument[path] == VerseMarkupShape.none, hint.book != nil {
            // A file we could not read at all, whose name promised a book, is worth saying aloud.
            bible.notes.append(ImportNote(.info, "\(path) named a book but carried no verse markup."))
        }
    }

    mutating func finish() {
        closeBlock()
        bible.tidy()
        bible.outOfOrderChapters = outOfOrder
        bible.bridgedVerses = bridged
        if !bridged.isEmpty {
            bible.notes.append(ImportNote(.info, "\(bridged.count) verse(s) are printed combined with the verse before them."))
        }
        for chapter in outOfOrder.sorted() {
            bible.notes.append(ImportNote(.warning, "\(chapter.display): verse numbers ran out of order."))
        }
        if skippedText > 0 {
            bible.notes.append(ImportNote(.warning,
                                          "\(skippedText) run(s) of text were dropped because no book or verse was in scope."))
        }
    }

    static func winningShape(_ counts: [VerseMarkupShape: Int]) -> VerseMarkupShape {
        // Ties go to the most explicit shape: an id that names book, chapter and verse beats a
        // class, which beats a bare superscript.
        let order: [VerseMarkupShape] = [.referenceIdentifier, .verseAnchor, .numberClass, .superscript]
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

    static func hint(path: String) -> (book: BookID?, chapter: Int?) {
        let file = path.split(separator: "/").last.map(String.init) ?? path
        let stem = file.split(separator: ".").first.map(String.init) ?? file
        guard let place = ScriptureLabels.fileStem(stem) else { return (nil, nil) }
        return (place.book, place.chapter)
    }

    // MARK: Position

    private mutating func move(to newBook: BookID?, chapter newChapter: Int?) {
        closeBlock()
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
        guard let number, let book else {
            if verse == nil { skippedText += 1 }
            return
        }
        if chapter == nil { chapter = 1 }
        // A verse number we already have means the file moved on to the next chapter without
        // saying so — the common shape when chapter numbers are drop-caps we did not recognise.
        if let current = chapterRef, bible.verses[VerseRef(book, current.chapter, number)] != nil {
            chapter = (chapter ?? 1) + 1
            lastVerseNumber = 0
            closeBlock()
        } else if number < lastVerseNumber, let current = chapterRef {
            outOfOrder.insert(current)
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
