// Importing is an iPhone, iPad and Mac feature; PDFKit isn't on the watch.
#if canImport(PDFKit) && !os(watchOS)
import Foundation
import PDFKit
#if canImport(UIKit)
import UIKit
private typealias PlatformFont = UIFont
#else
import AppKit
private typealias PlatformFont = NSFont
#endif

/// Reads the text of a typeset Bible out of a PDF — the kind a publisher exports from its page
/// layout, with a text layer.
///
/// A PDF has no markup, only glyphs in fonts at sizes. What survives extraction is the size of
/// each run of text, and a break wherever the font changes. That is enough, because a Bible is
/// typeset by a small set of rules that hold across publishers: chapter numbers are drop caps far
/// larger than the text, verse numbers are set in their own (bold or superscript) font, section
/// headings are smaller or capitalised, footnote callers are small letters, and the notes
/// themselves sit in small type at the foot of the page, each opening with its caller and a
/// chapter:verse reference. Every size below is judged against the file's own body size — the size
/// most of its characters are set in — never against a figure from one publisher.
///
/// The result is the same flow the ePub scanner produces (`ScannedDocument`), one per page, so
/// the assembler does the rest exactly as it does for an ePub: a drop cap starts verse 1, a book
/// title changes book, headings land above the verse they introduce, and a page with no verse
/// markup (front matter, maps, a concordance) contributes nothing.
struct PDFBibleReader {
    let options: BibleTextExtractor.Options

    struct Run {
        var text: String
        var size: Double
        /// The run begins a line of its own and ends one.
        var ownLine: Bool
        /// The run ends a line of print. Kept when `Lexicon` resolves the break itself.
        var endsLine = false
    }

    // MARK: - Reading

    func extract(from document: PDFDocument) throws -> ExtractedBible {
        let raw = (0..<document.pageCount).map { Self.runs(on: document.page(at: $0)) }
        let lexicon = Lexicon(raw.flatMap { $0 })
        let pages = raw.map { lexicon.resolveLineBreaks(in: $0) }
        let body = Self.bodySize(pages.flatMap { $0 })
        guard body > 0 else { throw BibleImportError.noScriptureFound }
        let rare = Self.rareSizes(pages.flatMap { $0 }, body: body)

        var assembler = Assembler(options: options)
        var outside = true   // before the first book title, and after anything that isn't one
        var lastVerse = 0
        var chapterOpen = false
        for (index, runs) in pages.enumerated() {
            let (document, stillOutside) = scan(runs, page: index, body: body, rare: rare, outside: outside,
                                                lastVerse: &lastVerse, chapterOpen: &chapterOpen)
            outside = stillOutside
            assembler.consume(document, path: "page \(index + 1)")
        }
        assembler.finish()
        guard !assembler.bible.isEmpty else { throw BibleImportError.noScriptureFound }
        return assembler.bible
    }

    /// Turns one page's runs into a flow. `outside` is true while the reader is in front or back
    /// matter: set by a large title that isn't a book, cleared by one that is. `chapterOpen` is
    /// true between a chapter's number and its first words, where a "1" printed after a heading
    /// (a psalm's title) numbers the verse the chapter's number already began.
    func scan(_ runs: [Run], page: Int, body: Double, rare: Set<Double>, outside: Bool,
              lastVerse: inout Int, chapterOpen: inout Bool) -> (ScannedDocument, Bool) {
        var document = ScannedDocument()
        var outside = outside
        var chapterSinceTitle = true
        var inNotes = false
        var noteID: String?
        var noteText = ""

        func closeNote() {
            if let noteID {
                let trimmed = DocumentScanner.collapse(noteText).trimmingCharacters(in: .whitespaces)
                if !trimmed.isEmpty { document.notes[noteID] = trimmed }
            }
            noteID = nil
            noteText = ""
        }

        var skipThrough = -1
        for (index, run) in runs.enumerated() where index > skipThrough {
            let text = run.text.replacingOccurrences(of: "\u{AD}", with: "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty, !Self.isPrinterSlug(text) else { continue }
            let ratio = run.size / body
            let next = runs.dropFirst(index + 1).first { !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

            // A short line that opens a little larger than the text, in a face of its own: an
            // acrostic letter and its name, a heading set as one line in two fonts — or in two
            // sizes, neither of them the text's ("S" + "hin / " + "Sin").
            if !outside, ratio > 1.04, ratio < 1.45, !run.ownLine, !run.endsLine,
               let end = runs.indices.dropFirst(index + 1).first(where: { runs[$0].endsLine }), end - index <= 4 {
                let line = runs[index...end].map(\.text).joined()
                let words = line.split(whereSeparator: \.isWhitespace)
                let offBody = runs[index...end].allSatisfy { other in
                    let size = other.size / body
                    return size < 0.97 || size > 1.04 || !other.text.contains(where: \.isLetter)
                }
                if (words.count <= 3 && end - index <= 2) || offBody, line.count <= 32, !line.contains(where: \.isNumber) {
                    if options.headings { appendHeading(Self.headingText(line), to: &document) }
                    skipThrough = end
                    continue
                }
            }

            // Titles: a book begins, or something that isn't scripture does.
            if ratio >= 1.45 {
                // A drop-cap initial: the first letter of the text, set large. In a book of one
                // chapter it is where chapter 1 begins, since no number is printed.
                if text.count == 1, text.first?.isLetter == true {
                    guard !outside else { continue }
                    if !chapterSinceTitle {
                        var marker = Marker(shapes: [.numberClass], chapter: 1)
                        marker.isChapter = true
                        document.chapterMarkers += 1
                        document.flow.append(.marker(marker))
                        chapterSinceTitle = true
                        lastVerse = 1
                    }
                    document.flow.append(.text(text, red: false))
                    continue
                }
                // A two-digit drop cap can come out as two runs ("1", "0"): digits with nothing
                // between them are one number.
                if text.count <= 2, text.allSatisfy(\.isNumber), case .marker(let previous) = document.flow.last,
                   previous.isChapter, let first = previous.chapter, first < 100, let merged = Int("\(first)\(text)") {
                    var marker = previous
                    marker.chapter = merged
                    document.flow[document.flow.count - 1] = .marker(marker)
                    continue
                }
                if let number = Self.number(text) {
                    guard !outside else { continue }
                    var marker = Marker(shapes: [.numberClass], chapter: number)
                    marker.isChapter = true
                    document.chapterMarkers += 1
                    document.flow.append(.marker(marker))
                    chapterSinceTitle = true
                    lastVerse = 1
                    chapterOpen = true
                } else if let place = ScriptureLabels.heading(text), let book = place.book {
                    outside = false
                    chapterSinceTitle = false
                    lastVerse = 0
                    document.flow.append(.place(book, chapter: place.chapter))
                } else if let place = ScriptureLabels.heading(text), let chapter = place.chapter, !outside {
                    var marker = Marker(shapes: [.numberClass], chapter: chapter)
                    marker.isChapter = true
                    document.chapterMarkers += 1
                    document.flow.append(.marker(marker))
                } else if !text.contains(where: \.isNumber) {
                    outside = true
                }
                continue
            }
            guard !outside else { continue }

            // The notes at the foot of the page: a tiny caller, then "15:4", then the note.
            if ratio < 0.8 {
                // In a book of one chapter a note names only its verse ("17"), in note-sized type.
                let isLabel = Self.isCallerLetters(text) && ratio < 0.58
                    && next.map { Self.isReference($0.text) || (Self.number($0.text) != nil && $0.size / body < 0.8) } == true
                if isLabel {
                    closeNote()
                    inNotes = true
                    noteID = Self.noteID(page: page, label: text)
                    continue
                }
                if inNotes, noteID != nil {
                    if !(noteText.isEmpty && Self.isReference(text)) { noteText += run.text }
                    continue
                }
                // In the text: a small letter is a footnote caller, a small number a superscript
                // verse number. Anything else this small (a running head, a page number) goes.
                // Two notes on one word are two callers, "l,m".
                let callers = text.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
                if callers.count > 1 ? callers.allSatisfy(Self.isCallerLetters) : Self.isCallerLetters(text) {
                    if options.footnotes {
                        for label in callers {
                            document.flow.append(.noteMarker(id: Self.noteID(page: page, label: label), label: label))
                        }
                    }
                    // A caller sits in a word break; the break survives it.
                    let after = next?.text.first
                    if run.text.first?.isWhitespace == true || run.text.last?.isWhitespace == true
                        || after == "\u{AD}" || after?.isWhitespace == true {
                        document.flow.append(.text(" ", red: false))
                    }
                } else if let number = Self.number(text), number == 1, lastVerse == 1, chapterOpen {
                    continue
                } else if let number = Self.number(text), Self.followsOn(number, after: lastVerse) {
                    lastVerse = number
                    chapterOpen = false
                    document.shapeCounts[.superscript, default: 0] += 1
                    document.flow.append(.marker(Marker(shapes: [.superscript], verse: number)))
                }
                continue
            }
            // Body-sized type ends a note: the notes of one column sit between that column's text
            // and the next column's.
            if inNotes {
                closeNote()
                inNotes = false
            }

            // Smaller than the text but not note-sized: headings, and the running heads and page
            // numbers every page carries, which aren't content.
            if ratio < 0.97 {
                if text.allSatisfy(\.isNumber) || Self.isRunningHead(text) { continue }
                if options.headings { appendHeading(Self.headingText(text), to: &document) }
                continue
            }

            // Body-sized. A run of nothing but a number is a verse number: a font change (bold,
            // or a different face) is what split it from the words around it.
            // …if it is the number a verse would have here. Measures and dates set in another font
            // ("75 feet", "7 1/2") are numbers too, and go back into the text.
            // A whole number before a fraction ("10 1/2 feet") is a measure, set apart only by the
            // fraction's font.
            let measure = next.map { $0.text.replacingOccurrences(of: "\u{AD}", with: "").trimmingCharacters(in: .whitespaces).firstMatch(of: /^\d+\s*[\/\x{2044}]\s*\d/) != nil } == true
            if Self.number(text) == 1, lastVerse == 1, chapterOpen { continue }
            if let number = Self.number(text), number < 200, !measure, Self.followsOn(number, after: lastVerse) {
                lastVerse = number
                chapterOpen = false
                document.shapeCounts[.numberClass, default: 0] += 1
                document.flow.append(.marker(Marker(shapes: [.numberClass], verse: number)))
                continue
            }
            // A line of its own in a size the text almost never uses, or a larger one: a heading
            // ("BOOK I (Psalms 1–41)", an acrostic letter).
            if run.ownLine, ratio > 1.04 || rare.contains(Self.rounded(run.size)) {
                if options.headings { appendHeading(Self.headingText(text), to: &document) }
                continue
            }
            let words = Self.bodyText(run.text)
            if words.contains(where: \.isLetter) { chapterOpen = false }
            document.flow.append(.text(words, red: false))
        }
        closeNote()
        return (document, outside)
    }

    /// A heading set over two lines arrives as two; it is one heading.
    private func appendHeading(_ text: String, to document: inout ScannedDocument) {
        if case .heading(let previous) = document.flow.last {
            document.flow[document.flow.count - 1] = .heading(previous + " " + text)
        } else {
            document.flow.append(.heading(text))
        }
    }

    // MARK: - Runs

    /// A page's text in reading order, as runs of one font.
    ///
    /// PDFKit's text order and its line grouping both follow height, so on a two-column page the
    /// columns come back interleaved — sometimes as one "line" running across the gutter. So the
    /// page is laid out again from positions. It is two-column when the strip down its middle is
    /// empty beside most lines; then a line that straddles the empty gutter is cut into the text
    /// on each side of it (PDFKit answers what text lies in any rectangle). A line with text in
    /// the gutter spans the page (a book title, a heading across it) and divides the page into
    /// bands; each band is read left column first, top to bottom, then the right. Every line ends
    /// in "\n", for `Lexicon` to decide whether the break fell between words or inside one.
    static func runs(on page: PDFPage?, trace: ((String) -> Void)? = nil) -> [Run] {
        // The text and its fonts come from the page's own attributed string, which is the same on
        // every platform; PDFKit's selections are asked only where each line and glyph sits, and
        // are checked against the text they should cover (`selection(on:covering:)`), because one
        // platform's selections start a character early and repeat line ends.
        guard let page, let attributed = page.attributedString, attributed.length > 0 else { return [] }
        let string = attributed.string as NSString
        struct Piece {
            var rect: CGRect
            var text: NSAttributedString
            var range: NSRange
            /// The largest type in the piece.
            var size: CGFloat {
                var size: CGFloat = 0
                text.enumerateAttribute(.font, in: NSRange(location: 0, length: text.length)) { value, _, _ in
                    size = max(size, (value as? PlatformFont)?.pointSize ?? 0)
                }
                return size
            }
        }
        var pieces: [Piece] = []
        var location = 0
        while location < string.length {
            let line = string.lineRange(for: NSRange(location: location, length: 0))
            location = line.location + line.length
            var trimmed = line
            while trimmed.length > 0, let last = UnicodeScalar(string.character(at: trimmed.location + trimmed.length - 1)),
                  CharacterSet.newlines.contains(last) {
                trimmed.length -= 1
            }
            // A line of text can run on over two rows of print (a word hyphenated at the end of a
            // row stays on its line); each row is placed on its own.
            for var row in rows(of: trimmed, in: attributed, on: page) {
                // Spaces at a row's ends have no place of their own to measure.
                while row.length > 0, let scalar = UnicodeScalar(string.character(at: row.location)),
                      CharacterSet.whitespaces.contains(scalar) {
                    row = NSRange(location: row.location + 1, length: row.length - 1)
                }
                while row.length > 0, let scalar = UnicodeScalar(string.character(at: row.location + row.length - 1)),
                      CharacterSet.whitespaces.contains(scalar) {
                    row.length -= 1
                }
                guard !string.substring(with: row).replacingOccurrences(of: "\u{AD}", with: "").trimmingCharacters(in: .whitespaces).isEmpty,
                      var rect = bounds(on: page, covering: row, in: string), !rect.isEmpty else { continue }
                // A long line's box is measured two characters in from each end; its ends are where
                // its first and last glyphs are — a verse number the text layer ran onto the end
                // of a line from across the gutter ("Ger" + "42") included.
                let printing = (row.location..<(row.location + row.length)).filter { isPrinting(string.character(at: $0)) }
                if row.length > 6, let first = printing.first, let last = printing.last {
                    for index in Set([first, last]) {
                        // One glyph's box: a selection of one character can come back as its
                        // whole line's.
                        let size = (attributed.attribute(.font, at: index, effectiveRange: nil) as? PlatformFont)?.pointSize ?? 0
                        guard let box = glyphBox(at: index, in: string, on: page), !box.isEmpty, box.width < size * 1.5,
                              abs(box.midY - rect.midY) < max(rect.height, box.height) / 2 else { continue }
                        rect = CGRect(x: min(rect.minX, box.minX), y: rect.minY,
                                      width: max(rect.maxX, box.maxX) - min(rect.minX, box.minX), height: rect.height)
                    }
                }
                var text = normalizedLine(attributed.attributedSubstring(from: row))
                // A row that ends between two letters of one line of text ends inside a word the
                // typesetter hyphenated ("distinguish" / "ing"), whose hyphen the text layer
                // dropped: it ends in a soft hyphen, as a break inside a word does.
                let end = row.location + row.length
                if end < trimmed.location + trimmed.length, row.length > 0,
                   let last = UnicodeScalar(string.character(at: end - 1)), Character(last).isLetter,
                   let next = UnicodeScalar(string.character(at: end)), Character(next).isLetter {
                    let marked = NSMutableAttributedString(attributedString: text)
                    marked.append(NSAttributedString(string: "\u{AD}", attributes: text.attributes(at: max(0, text.length - 1), effectiveRange: nil)))
                    text = marked
                }
                pieces.append(Piece(rect: rect, text: text, range: row))
            }
        }
        guard !pieces.isEmpty else { return [] }
        let smallCaps = smallCapitalNames(on: page)
        if !smallCaps.isEmpty {
            pieces = pieces.map { Piece(rect: $0.rect, text: restoringSmallCapitals($0.text, in: $0.rect, occurrences: smallCaps), range: $0.range) }
        }
        pieces = mergedStackedDigits(pieces.map { ($0.rect, $0.text, $0.range) }).map { Piece(rect: $0.0, text: $0.1, range: $0.2) }
        // The columns are the text's: rows in the page's own body type. A printer's slug in the
        // margin or a line of notes across the foot says nothing about where the gutter is.
        let typical: CGFloat = {
            var counts: [Double: Int] = [:]
            for piece in pieces { counts[rounded(Double(piece.size)), default: 0] += piece.text.length }
            return CGFloat(counts.max { $0.value != $1.value ? $0.value < $1.value : $0.key > $1.key }?.key ?? 0)
        }()
        let isBody = { (piece: Piece) in abs(piece.size - typical) <= typical * 0.1 }
        let body = pieces.contains(where: isBody) ? pieces.filter(isBody) : pieces
        let left = body.map(\.rect.minX).min() ?? 0
        let right = body.map(\.rect.maxX).max() ?? 0
        // The gutter is where the fewest lines cross, near the middle — not the middle itself: a
        // left-hand page's columns sit off-centre on the sheet.
        let narrow = body.filter { $0.rect.width < (right - left) * 0.6 }
        let middle: CGFloat = {
            let center = (left + right) / 2
            var best = center
            var fewest = Int.max
            var x = left + (right - left) * 0.3
            while x <= left + (right - left) * 0.7 {
                let crossing = narrow.filter { $0.rect.minX < x && $0.rect.maxX > x }.count
                if crossing < fewest || (crossing == fewest && abs(x - center) < abs(best - center)) {
                    fewest = crossing
                    best = x
                }
                x += 1
            }
            return best
        }()

        func text(in rect: CGRect) -> NSAttributedString? {
            guard rect.width > 0.5, let selection = page.selection(for: rect), let text = selection.attributedString,
                  !text.string.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
            return text
        }
        func gutterIsEmpty(_ rect: CGRect) -> Bool {
            text(in: CGRect(x: middle - 1.5, y: rect.minY + rect.height * 0.25, width: 3, height: rect.height * 0.5)) == nil
        }
        // Only a piece wide enough to hold text from both columns is a candidate for cutting; a
        // drop cap or verse number sitting on the gutter line is one thing, kept whole.
        let crossing = pieces.indices.filter {
            pieces[$0].rect.minX < middle - 2 && pieces[$0].rect.maxX > middle + 2 && pieces[$0].rect.width > 40
        }
        let open = crossing.filter { gutterIsEmpty(pieces[$0].rect) }
        let rightOnly = pieces.filter { $0.rect.minX >= middle }.count
        // Judged by the text's own lines: notes set across the foot of the page cross an empty
        // gutter above them, and say nothing about whether the text runs in columns.
        let bodyCrossing = crossing.filter { isBody(pieces[$0]) }
        let twoColumns = rightOnly > 2 && bodyCrossing.filter { open.contains($0) }.count * 2 >= bodyCrossing.count
        var spanning = Set<Int>()
        if twoColumns {
            var split: [Piece] = []
            for index in pieces.indices {
                let piece = pieces[index]
                guard open.contains(index) else {
                    if crossing.contains(index) { spanning.insert(split.count) }
                    split.append(piece)
                    continue
                }
                // Cut at the first character that sits right of the gutter.
                let r = piece.rect
                let cut = firstIndex(in: piece.range, of: string, on: page) { $0.minX >= middle }
                let leftLength = cut - piece.range.location
                if leftLength > 0 {
                    split.append(Piece(rect: CGRect(x: r.minX, y: r.minY, width: middle - r.minX, height: r.height),
                                       text: piece.text.attributedSubstring(from: NSRange(location: 0, length: min(leftLength, piece.text.length))),
                                       range: NSRange(location: piece.range.location, length: leftLength)))
                }
                if leftLength < piece.text.length {
                    split.append(Piece(rect: CGRect(x: middle, y: r.minY, width: r.maxX - middle, height: r.height),
                                       text: piece.text.attributedSubstring(from: NSRange(location: leftLength, length: piece.text.length - leftLength)),
                                       range: NSRange(location: cut, length: piece.range.length - leftLength)))
                }
            }
            pieces = split
        } else {
            spanning = Set(pieces.indices)
        }

        // Which column each piece reads in. Usually the side of the gutter it starts on — but a
        // verse number hung out into the gutter beside a line belongs with the line it numbers:
        // a narrow piece goes with its nearest neighbour on the same line.
        let isRight: [Bool] = pieces.indices.map { index in
            let piece = pieces[index]
            guard piece.rect.width < 40 else { return piece.rect.minX >= middle }
            // Its neighbours are the lines beside it: overlapping it vertically (a drop cap spans
            // two or three), and text rather than another hung number — a short line ("Do not
            // steal.") is one too.
            let sameLine = pieces.indices.filter { $0 != index }.map { pieces[$0] }.filter { other in
                let overlap = min(other.rect.maxY, piece.rect.maxY) - max(other.rect.minY, piece.rect.minY)
                return (other.rect.width >= 40 || number(other.text.string) == nil)
                    && overlap > min(other.rect.height, piece.rect.height) * 0.3
            }
            let before = sameLine.filter { $0.rect.maxX <= piece.rect.minX + 1 }.map { piece.rect.minX - $0.rect.maxX }.min()
            // A verse number goes with the nearer line; words (the short last line of a column,
            // "are also") only with a line they sit right up against.
            let isNumber = number(piece.text.string) != nil
            let after = sameLine.filter { $0.rect.minX >= piece.rect.maxX - 1 && (isNumber || $0.rect.minX - piece.rect.maxX < piece.size * 1.5) }
                .min { $0.rect.minX < $1.rect.minX }
            if let after, before.map({ after.rect.minX - piece.rect.maxX < $0 }) ?? true {
                return after.rect.minX >= middle
            }
            return piece.rect.minX >= middle
        }

        // Reading order: bands split at spanning pieces, each band left column then right.
        // By the top of each piece: a drop cap is centred on the lines it spans, but its top
        // is level with the first of them.
        let order = pieces.indices.sorted { pieces[$0].rect.maxY > pieces[$1].rect.maxY }
        var ordered: [Piece] = []
        var band: [Int] = []
        func flush() {
            let lines = { (column: [Int]) in
                column.map { pieces[$0] }
                    .sorted { abs($0.rect.maxY - $1.rect.maxY) > 2 ? $0.rect.maxY > $1.rect.maxY : $0.rect.minX < $1.rect.minX }
            }
            ordered += lines(band.filter { !isRight[$0] })
            ordered += lines(band.filter { isRight[$0] })
            band = []
        }
        for index in order {
            if twoColumns && spanning.contains(index) {
                flush()
                ordered.append(pieces[index])
            } else {
                band.append(index)
            }
        }
        flush()
        if let trace {
            trace("middle=\(middle) twoColumns=\(twoColumns) crossing=\(crossing.count) open=\(open.count) rightOnly=\(rightOnly)")
            for piece in ordered {
                let index = pieces.firstIndex { $0.range == piece.range && $0.rect == piece.rect } ?? -1
                let r = piece.rect
                trace(String(format: "  [%6.1f %6.1f %6.1f %6.1f] %@%@ ", r.minX, r.minY, r.maxX, r.maxY,
                             index >= 0 && isRight[index] ? "R" : "L", spanning.contains(index) ? "S" : " ")
                      + piece.text.string.replacingOccurrences(of: "\u{AD}", with: "~"))
            }
        }

        var runs: [Run] = []
        var previous: Piece?
        for piece in ordered {
            // Further along the same line: a space where there's a visible gap, else nothing.
            if let previous, abs(previous.rect.midY - piece.rect.midY) <= 2, piece.rect.minX >= previous.rect.maxX - 1,
               var last = runs.popLast() {
                if last.text.hasSuffix("\n") { last.text.removeLast() }
                // Two pieces of one row come from different lines of text, so they never continue
                // one word: a space between them, unless punctuation closes up to what came before
                // ("Elijah?" + "”") or opens onto what follows.
                let before = last.text.replacingOccurrences(of: "\u{AD}", with: "").last
                let after = piece.text.string.replacingOccurrences(of: "\u{AD}", with: "").first
                // "LORD’" + "s": a possessive whose name was set in small capitals.
                let possessive = before.map { "’'".contains($0) } == true
                    && piece.text.string.replacingOccurrences(of: "\u{AD}", with: "").firstMatch(of: /^s(?!\p{L})/) != nil
                let closes = possessive || after.map { "”’),.;:!?]".contains($0) } ?? true
                let opens = before.map { "“‘([ ".contains($0) || $0.isWhitespace } ?? true
                if !closes && !opens { last.text += " " }
                runs.append(last)
            }
            let string = piece.text.string as NSString
            var first = true
            piece.text.enumerateAttributes(in: NSRange(location: 0, length: piece.text.length)) { attributes, range, _ in
                let size = (attributes[.font] as? PlatformFont).map { Double($0.pointSize) } ?? 0
                var text = string.substring(with: range)
                // A word set in a face of its own at the end of a line ("forever." + "Selah") is
                // spaced from it on the page, not in the text: a capital straight after the end of a
                // word or a sentence in another font begins a new word.
                if !first, let before = runs.last?.text.replacingOccurrences(of: "\u{AD}", with: "").last,
                   before.isLowercase || ".,;:!?".contains(before),
                   let after = text.replacingOccurrences(of: "\u{AD}", with: "").first, after.isUppercase {
                    text = " " + text
                }
                first = false
                runs.append(Run(text: text, size: size, ownLine: false))
            }
            if var last = runs.popLast() {
                last.text = last.text.trimmingCharacters(in: .newlines) + "\n"
                runs.append(last)
            }
            previous = piece
        }
        var lineStart = true
        for index in runs.indices {
            let endsLine = runs[index].text.hasSuffix("\n")
            runs[index].ownLine = lineStart && endsLine
            runs[index].endsLine = endsLine
            lineStart = endsLine
        }
        return runs
    }

    /// Where the text in `range` sits, checked against the text itself. PDFKit's selections don't
    /// always cover the range asked for, differently on different platforms: one runs on past the
    /// end of a line into the next, another starts a character early. So the range and its near
    /// neighbours are tried, and a selection that spills onto the next row is cut back to its
    /// first row, keeping whichever covers exactly the text wanted.
    static func bounds(on page: PDFPage, covering range: NSRange, in string: NSString) -> CGRect? {
        // Spaces and soft hyphens differ between platforms and don't move anything; a newline
        // does, so it has to match.
        let squash = { (text: String) in text.filter { ($0.isNewline || !$0.isWhitespace) && $0 != "\u{AD}" } }
        let expected = squash(string.substring(with: range))
        // A selection touching either end of a line can spill onto the row before or after it.
        // Two characters in from each end never does, and the box moves by no more than those.
        if range.length > 6, let inner = page.selection(for: NSRange(location: range.location + 2, length: range.length - 4)) {
            let got = squash(inner.string ?? "")
            if !got.isEmpty, !got.contains(where: \.isNewline), expected.contains(got) {
                return inner.bounds(for: page)
            }
        }
        let candidates = [range,
                          NSRange(location: range.location + 1, length: range.length),
                          NSRange(location: range.location, length: range.length - 1)]
        var fallback: CGRect?
        for candidate in candidates where candidate.length > 0 && candidate.location + candidate.length <= string.length {
            guard let selection = page.selection(for: candidate) else { continue }
            let got = squash(selection.string ?? "")
            if got == expected { return selection.bounds(for: page) }
            // Ran on into the next row: its first row is the text wanted.
            if got.hasPrefix(expected), got.dropFirst(expected.count).first?.isNewline == true,
               let row = selection.selectionsByLine().first, squash(row.string ?? "") == expected {
                return row.bounds(for: page)
            }
            // One character short and not spilling: the right place.
            if fallback == nil, !got.isEmpty, !got.contains(where: \.isNewline), expected.hasPrefix(got),
               expected.count - got.count <= 1 {
                fallback = selection.bounds(for: page)
            }
        }
        return fallback ?? page.selection(for: range)?.bounds(for: page)
    }

    /// Whether a character of the text prints: not a space, a line break or a soft hyphen.
    static func isPrinting(_ character: unichar) -> Bool {
        guard let scalar = UnicodeScalar(character) else { return false }
        return scalar != "\u{AD}" && !CharacterSet.whitespacesAndNewlines.contains(scalar)
    }

    /// Where one character sits.
    static func glyphBox(at index: Int, in string: NSString, on page: PDFPage) -> CGRect? {
        bounds(on: page, covering: NSRange(location: index, length: 1), in: string)
    }

    /// Splits a line of text into the rows of print it covers: while its box is taller than its
    /// type, cut at the first character that sits a row lower than the line's first.
    static func rows(of range: NSRange, in attributed: NSAttributedString, on page: PDFPage) -> [NSRange] {
        let string = attributed.string as NSString
        var rows: [NSRange] = []
        var rest = range
        // A drop cap opens the first lines of a chapter, and the text layer can run those lines
        // together behind it, the second before the first. It is a row of its own, and the rest
        // is measured by the text's own size.
        var sizes: [(NSRange, CGFloat)] = []
        attributed.enumerateAttribute(.font, in: range) { value, run, _ in
            sizes.append((run, (value as? PlatformFont)?.pointSize ?? 0))
        }
        if sizes.count > 1, let lead = sizes.first, lead.1 > 0,
           let restSize = sizes.dropFirst().map(\.1).max(), restSize > 0, lead.1 >= restSize * 1.45,
           string.substring(with: lead.0).trimmingCharacters(in: .whitespaces).allSatisfy({ $0.isNumber || $0.isLetter }) {
            rows.append(lead.0)
            rest = NSRange(location: lead.0.location + lead.0.length, length: range.location + range.length - lead.0.location - lead.0.length)
        }
        for _ in 0..<4 {
            guard rest.length > 1, let rect = bounds(on: page, covering: rest, in: string) else { break }
            var size: CGFloat = 0
            attributed.enumerateAttribute(.font, in: rest) { value, _, _ in
                size = max(size, (value as? PlatformFont)?.pointSize ?? 0)
            }
            // The first and last glyphs that print: a soft hyphen or a space has no place of its own.
            let printing = (rest.location..<(rest.location + rest.length)).filter { isPrinting(string.character(at: $0)) }
            guard size > 0, let first = printing.first, let last = printing.last,
                  let firstBox = glyphBox(at: first, in: string, on: page) else { break }
            // A box taller than its type spans rows. So does a line whose last glyph sits on another
            // row than its first — the box of a long line is measured from inside its ends, which
            // can miss a verse number run on after a printer's slug.
            let lastBox = last == first ? firstBox : glyphBox(at: last, in: string, on: page)
            let apart = lastBox.map { abs($0.midY - firstBox.midY) > size * 0.6 } ?? false
            guard rect.height > size * 1.7 || apart else { break }
            // Up or down: a line of text can join print from anywhere on the page (a printer's
            // slug at the foot, run together with a verse at the top of a column).
            // By the middle of each glyph, or its top: a row set beside a drop cap can come back in
            // one box with the drop cap's rows, as tall as they are together.
            let cut = firstIndex(in: rest, of: string, on: page) {
                abs($0.midY - firstBox.midY) > size * 0.6 || abs($0.maxY - firstBox.maxY) > size * 0.6
            }
            guard cut > rest.location, cut < rest.location + rest.length else { break }
            rows.append(NSRange(location: rest.location, length: cut - rest.location))
            rest = NSRange(location: cut, length: rest.location + rest.length - cut)
        }
        rows.append(rest)
        return rows
    }


    /// The first index in `range` whose glyph satisfies `test`, by binary search along the line
    /// (characters on one line run left to right). Blank characters take their neighbour's place.
    static func firstIndex(in range: NSRange, of string: NSString, on page: PDFPage, where test: (CGRect) -> Bool) -> Int {
        var low = range.location, high = range.location + range.length
        while low < high {
            let mid = (low + high) / 2
            var probe = mid
            var box: CGRect?
            while probe < range.location + range.length {
                if let found = glyphBox(at: probe, in: string, on: page), !found.isEmpty, found.width > 0 { box = found; break }
                probe += 1
            }
            if let box, !test(box) { low = probe + 1 } else { high = mid }
        }
        return low
    }

    /// One platform's text puts a space after every soft hyphen ("be\u{AD} gin\u{AD} ning"); a soft
    /// hyphen inside a word is followed by the rest of the word, not a space.
    static func normalizedLine(_ text: NSAttributedString) -> NSAttributedString {
        let string = text.string as NSString
        guard string.range(of: "\u{AD} ").location != NSNotFound,
              let pattern = try? NSRegularExpression(pattern: #"(?<=\p{L})\x{00AD} (?=\p{L})"#) else { return text }
        let result = NSMutableAttributedString(attributedString: text)
        for match in pattern.matches(in: text.string, range: NSRange(location: 0, length: string.length)).reversed() {
            result.replaceCharacters(in: NSRange(location: match.range.location + 1, length: 1), with: "")
        }
        return result
    }

    /// Where "Lord" sits on the page, and whether it is set in small capitals — LORD, the way a
    /// Bible prints the divine name. Extraction gives small capitals back as lowercase, but their
    /// widths give them away: a lowercase r is narrow beside its o (about three quarters of its
    /// width), a small-capital R is as wide as the O. The measure is relative to the word itself,
    /// so it holds for any typeface and size.
    static func smallCapitalNames(on page: PDFPage) -> [(rect: CGRect, isSmall: Bool)] {
        guard let string = page.string as NSString?, string.length > 0,
              let pattern = try? NSRegularExpression(pattern: #"(?<![\p{L}])L\x{00AD}?ord(?![\p{L}])"#) else { return [] }
        var found: [(CGRect, Bool)] = []
        for match in pattern.matches(in: string as String, range: NSRange(location: 0, length: string.length)) {
            let range = match.range
            let letters = (range.location..<(range.location + range.length)).filter { string.character(at: $0) != 0xAD }
            let boxes = letters.map { glyphBox(at: $0, in: string, on: page) ?? .zero }
            guard boxes.count == 4, boxes[1].width > 0 else { continue }
            let rect = boxes.reduce(CGRect.null) { $0.union($1) }
            found.append((rect, boxes[2].width / boxes[1].width > 0.88))
        }
        return found
    }

    /// Capitalises the small-capital names inside one piece of a line. The k-th name in the
    /// piece's text is the k-th found inside its rectangle, left to right.
    static func restoringSmallCapitals(_ text: NSAttributedString, in rect: CGRect,
                                       occurrences: [(rect: CGRect, isSmall: Bool)]) -> NSAttributedString {
        let inside = occurrences.filter { rect.insetBy(dx: -1, dy: -2).intersects($0.rect) && $0.rect.midY >= rect.minY - 1 && $0.rect.midY <= rect.maxY + 1 }
            .sorted { $0.rect.minX < $1.rect.minX }
        guard inside.contains(where: \.isSmall),
              let pattern = try? NSRegularExpression(pattern: #"(?<![\p{L}])L\x{00AD}?ord(?![\p{L}])"#) else { return text }
        let string = text.string as NSString
        let matches = pattern.matches(in: text.string, range: NSRange(location: 0, length: string.length))
        guard matches.count == inside.count else { return text }
        let result = NSMutableAttributedString(attributedString: text)
        for (match, occurrence) in zip(matches, inside) where occurrence.isSmall {
            result.replaceCharacters(in: match.range, with: string.substring(with: match.range).uppercased())
        }
        return result
    }

    /// A drop cap of two digits is sometimes set as two glyphs, one above the other ("1" over
    /// "0"). Large digit-only pieces in the same place, one just below the other, are one number.
    static func mergedStackedDigits(_ pieces: [(CGRect, NSAttributedString, NSRange)]) -> [(CGRect, NSAttributedString, NSRange)] {
        func size(_ text: NSAttributedString) -> Double {
            (text.attribute(.font, at: 0, effectiveRange: nil) as? PlatformFont).map { Double($0.pointSize) } ?? 0
        }
        let sizes = pieces.map { size($0.1) }.sorted()
        let typical = sizes.isEmpty ? 0 : sizes[sizes.count / 2]
        var result = pieces
        var index = 0
        while index < result.count {
            let (rect, text, range) = result[index]
            let digits = text.string.trimmingCharacters(in: .whitespacesAndNewlines)
            let big = size(text)
            guard !digits.isEmpty, digits.count <= 2, digits.allSatisfy(\.isNumber), big > typical * 1.8 else {
                index += 1
                continue
            }
            if let below = result.indices.first(where: { other in
                other != index && abs(size(result[other].1) - big) < 0.5
                    && result[other].1.string.trimmingCharacters(in: .whitespacesAndNewlines).allSatisfy(\.isNumber)
                    && abs(result[other].0.minX - rect.minX) < big * 0.6
                    && result[other].0.maxY <= rect.minY + big * 0.3 && rect.minY - result[other].0.maxY < big * 0.6
            }) {
                let merged = NSMutableAttributedString(attributedString: text)
                merged.mutableString.setString(digits + result[below].1.string.trimmingCharacters(in: .whitespacesAndNewlines))
                result[index] = (rect.union(result[below].0), merged, range)
                result.remove(at: below)
                continue
            }
            index += 1
        }
        return result
    }

    /// The size most characters are set in.
    static func bodySize(_ runs: [Run]) -> Double {
        var counts: [Double: Int] = [:]
        for run in runs { counts[rounded(run.size), default: 0] += run.text.count }
        return counts.max { $0.value < $1.value }?.key ?? 0
    }

    /// Body-band sizes used for almost nothing — a heading style that happens to be near the text size.
    static func rareSizes(_ runs: [Run], body: Double) -> Set<Double> {
        var counts: [Double: Int] = [:]
        for run in runs { counts[rounded(run.size), default: 0] += run.text.count }
        let total = counts.values.reduce(0, +)
        return Set(counts.filter { $0.key / body >= 0.97 && $0.key / body <= 1.04 && Double($0.value) < Double(total) * 0.002 }.keys)
    }

    static func rounded(_ size: Double) -> Double { (size * 10).rounded() / 10 }

    // MARK: - Text

    /// Words as the page prints them. Soft hyphens are typesetting, not text, except that a line
    /// broken at one joins without a space; a hard hyphen at a line's end is a real one
    /// ("three-year-" / "old"). Small capitals come out as "L\u{AD}ord": the divine names they set are
    /// restored to capitals, which is how every digital Bible writes them.
    static func bodyText(_ raw: String) -> String {
        var text = raw
        for (small, capital) in [("L\u{AD}ord", "LORD"), ("G\u{AD}od", "GOD"), ("Y\u{AD}ah", "YAH"),
                                 ("L\u{AD}ORD", "LORD"), ("G\u{AD}OD", "GOD")] {
            text = text.replacingOccurrences(of: small, with: capital)
        }
        text = text.replacingOccurrences(of: "\u{AD}\n", with: "")
        text = text.replacingOccurrences(of: "\u{AD}", with: "")
        // Control characters a text layer leaves in (a tab leader's backspace) aren't text.
        text.unicodeScalars.removeAll { $0.properties.generalCategory == .control && $0 != "\n" && $0 != "\t" }
        text = text.replacing(/(\p{L})-\n(?=\p{L})/) { "\($0.output.1)-" }
        return DocumentScanner.collapse(text)
    }

    static func headingText(_ raw: String) -> String {
        DocumentScanner.collapse(bodyText(raw)).trimmingCharacters(in: .whitespaces)
    }

    /// Whether a number is plausibly the next verse: a little ahead of the last (translations
    /// omit a verse here and there), or a new count starting where none has begun.
    static func followsOn(_ number: Int, after last: Int) -> Bool {
        (number > last && number <= last + 4) || (last == 0 && number <= 2)
    }

    static func number(_ text: String) -> Int? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.count <= 3, trimmed.allSatisfy(\.isNumber), let value = Int(trimmed), value > 0 else { return nil }
        return value
    }

    static func isCallerLetters(_ text: String) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return !trimmed.isEmpty && trimmed.count <= 2 && trimmed.allSatisfy { $0.isLetter && $0.isLowercase }
            || DocumentScanner.isFootnoteLabel(trimmed) && trimmed.count <= 2
    }

    /// "15:4", "3:16–18", "Ps 3:2" at the head of a note.
    static func isReference(_ text: String) -> Bool {
        text.trimmingCharacters(in: .whitespacesAndNewlines).firstMatch(of: /^(\p{L}+\.?\s)?\d{1,3}:\d{1,3}/) != nil
    }

    /// "GENESIS 2-3 2", "3 GENESIS 3–4": a book name with a chapter span and a page number.
    static func isRunningHead(_ text: String) -> Bool {
        guard text.contains(where: \.isNumber) else { return false }
        // Case is not a test: small capitals can come out of a PDF as "NUMbERS".
        let words = text.replacing(/[\d\-–—:,]/, with: " ").trimmingCharacters(in: .whitespaces.union(.controlCharacters))
        guard !words.isEmpty, text.count <= 48 else { return false }
        if ScriptureLabels.heading(words)?.book != nil { return true }
        // A numbered book ("235 1 SAMUEL 2-3"): the numeral just before the name is the name's.
        guard let named = text.firstMatch(of: /(?:^|\s)([1-3]\s+\p{L}[\p{L}\s]*\p{L})/) else { return false }
        return ScriptureLabels.heading(String(named.output.1))?.book != nil
    }

    /// The imposition slug a printer leaves on every page ("Bible.indb 11 10/26/17 8:59 PM") —
    /// its file name, or its time stamp when that comes apart from the name.
    static func isPrinterSlug(_ text: String) -> Bool {
        text.firstMatch(of: /\.(indb|indd|qxp|pdf)\b/) != nil
            || text.wholeMatch(of: /\d{1,2}\/\d{1,2}\/\d{2,4}\s+\d{1,2}:\d{2}\s*[AP]M/) != nil
    }

    static func noteID(page: Int, label: String) -> String {
        "p\(page)-\(label.trimmingCharacters(in: .whitespacesAndNewlines))"
    }

    // MARK: - Identity

    /// The first pages' text, where a title page and copyright page are.
    static func frontMatter(of document: PDFDocument, pages: Int = 8) -> String {
        (0..<min(pages, document.pageCount)).compactMap { document.page(at: $0)?.string }.joined(separator: "\n")
    }
}

/// The document's own vocabulary, for deciding what a line break was.
///
/// Typeset text hyphenates words across lines, and extraction drops the hyphen: one line ends
/// "Phari", the next begins "sees". It also breaks between words: "were" / "created". Nothing on
/// the page tells the two apart, but the rest of the book does — a Bible repeats its words, and
/// "Pharisees" appears whole on some line where it wasn't broken, while "werecreated" never does.
/// That makes the rule language-independent: no dictionary, only the file itself.
struct Lexicon {
    private var words: [String: Int] = [:]
    private var pairs: [String: Int] = [:]
    /// Compounds written with a hyphen inside a line ("three-year"), so a break at their hyphen
    /// keeps it.
    private var hyphenated: [String: Int] = [:]
    /// First halves of those compounds ("beth" of "Beth-shemesh"): a name that opens many.
    private var compoundHeads: [String: Int] = [:]
    /// Words seen other than straight after an f-ligature ("off er", "suf ering").
    private(set) var standalone: [String: Int] = [:]
    /// Words seen straight after one ending in "f".
    private var afterF: [String: Int] = [:]

    init(_ runs: [PDFBibleReader.Run]) {
        for run in runs {
            let lines = run.text.replacingOccurrences(of: "\u{AD}", with: "").components(separatedBy: "\n")
            for line in lines {
                let tokens = Self.tokens(line)
                // The first and last word of a line may be halves; only whole ones count.
                guard tokens.count > 2 else { continue }
                let inner = tokens.dropFirst().dropLast()
                for word in inner { words[word, default: 0] += 1 }
                for (a, b) in zip(inner, inner.dropFirst()) { pairs[a + " " + b, default: 0] += 1 }
                // A line's first word may be the tail of a broken one, so only words with a word
                // before them on the same line count.
                for (previous, word) in zip(tokens, tokens.dropFirst()) {
                    if Self.endsInLigature(previous) { afterF[word, default: 0] += 1 } else { standalone[word, default: 0] += 1 }
                }
                // Each joint of a chain: "three-year-old" is "three-year" and "year-old".
                for match in line.matches(of: /\p{L}+(?:-\p{L}+)+/) {
                    let parts = match.output.lowercased().split(separator: "-").map(String.init)
                    for (a, b) in zip(parts, parts.dropFirst()) {
                        hyphenated[a + "-" + b, default: 0] += 1
                        compoundHeads[a, default: 0] += 1
                    }
                }
            }
        }
    }

    /// Extraction opens a gap after an "f" wherever the font joins it to the next letter — a
    /// ligature ("ff", "fi", "fl") or a kerned pair.
    static func endsInLigature(_ word: String) -> Bool {
        word.hasSuffix("f")
    }

    /// Extraction breaks words at f-ligatures: "offered" comes out "off ered". The piece after
    /// the break is joined back when it never stands as a word anywhere else in the book.
    func repairingLigatures(_ text: String) -> String {
        // Soft hyphens can sit anywhere in the tail ("off i\u{AD}cials"). The tail is only looked
        // at, so it can be the head of the next ("of selfi sh").
        text.replacing(/(\p{L}*f[il]?) (?=(\x{AD}?\p{Ll}(?:\p{Ll}|\x{AD})*))/) { match in
            let head = String(match.output.1), tail = String(match.output.2)
            return ligatureJoins(head, tail.replacingOccurrences(of: "\u{AD}", with: "")) ? head : head + " "
        }
    }

    /// Whether `head` and `tail`, a gap between them, are one word broken at a ligature. A word
    /// doesn't end in "fi" or "fl", so a gap after those is always the ligature's ("profi t",
    /// "certifi cate"). After "ff" the tail has to be a fragment ("diff erently"); after a single
    /// "f", seen so more than once too, as "of" is a word before a rare one ("of grinding").
    func ligatureJoins(_ head: String, _ tail: String) -> Bool {
        let head = head.lowercased(), tail = tail.lowercased()
        guard let first = tail.first, first.isLetter, first.isLowercase else { return false }
        if head.hasSuffix("fi") || head.hasSuffix("fl") { return true }
        // "off ice": a tail that follows "ff" more often than not.
        if head.hasSuffix("ff") { return (standalone[tail] ?? 0) < (afterF[tail] ?? 0) }
        return head.hasSuffix("f") && isFragment(tail) && (afterF[tail] ?? 0) >= 2
    }

    /// A piece of a word broken after an "f": it follows an "f" far more often than it stands
    /// alone ("er" 100 to 9; "the" 49 to 40,000).
    func isFragment(_ tail: String) -> Bool {
        let key = tail.lowercased()
        return (standalone[key] ?? 0) * 5 < (afterF[key] ?? 0)
    }

    static func tokens(_ text: String) -> [String] {
        text.lowercased().split { !$0.isLetter }.map(String.init)
    }

    /// Whether "first" at a line's end and "second" at the next line's start are one word.
    func joins(_ first: String, _ second: String) -> Bool {
        let a = first.lowercased(), b = second.lowercased()
        let joined = words[a + b] ?? 0
        guard joined > 0 else { return false }
        // Both halves are words too ("some" / "one"): the more common reading wins.
        return joined >= (pairs[a + " " + b] ?? 0)
    }

    /// Whether a hyphen that ends a line between `first` and `second` is the typesetter's (the
    /// word is one: "Phari-" / "sees") rather than the word's own ("three-" / "year"). A break the
    /// book never shows either way is the typesetter's — unless its first half opens compounds
    /// elsewhere and its second is a word of its own, as in "Beth-" / "haran".
    func hyphenIsTypesetting(_ first: String, _ second: String) -> Bool {
        let a = first.lowercased(), b = second.lowercased()
        let joined = words[a + b] ?? 0
        let kept = hyphenated[a + "-" + b] ?? 0
        if joined > 0 || kept > 0 { return joined >= kept }
        return (compoundHeads[a] ?? 0) == 0 || (words[b] ?? 0) == 0
    }

    /// Replaces each line break with nothing (inside a word) or a space (between words).
    func resolveLineBreaks(in runs: [PDFBibleReader.Run]) -> [PDFBibleReader.Run] {
        var runs = runs
        // A ligature is often a run of its own, so its break can fall between runs too, the gap
        // at the end of one or the start of the next.
        for index in runs.indices.dropLast() {
            let text = runs[index].text.replacingOccurrences(of: "\u{AD}", with: "")
            let next = runs[index + 1].text.replacingOccurrences(of: "\u{AD}", with: "")
            guard text.hasSuffix(" ") != next.hasPrefix(" ") else { continue }
            let head = String(text.trimmingCharacters(in: .whitespaces).reversed().prefix { $0.isLetter }.reversed())
            let tail = String(next.trimmingCharacters(in: .whitespaces).prefix { $0.isLetter })
            guard !head.isEmpty, ligatureJoins(head, tail) else { continue }
            while runs[index].text.last?.isWhitespace == true { runs[index].text.removeLast() }
            while runs[index + 1].text.first?.isWhitespace == true { runs[index + 1].text.removeFirst() }
        }
        // The line so far: a run can hold no more than its end ("-", in a font of its own).
        var line = ""
        for index in runs.indices {
            var text = runs[index].text
            guard text.contains("\n") else {
                runs[index].text = repairingLigatures(text)
                line += text
                continue
            }
            let following = runs[(index + 1)...].first { !$0.text.isEmpty }?.text ?? ""
            var result = ""
            let parts = text.components(separatedBy: "\n")
            for (position, part) in parts.enumerated() {
                result += part
                line = position == 0 ? line + part : part
                guard position < parts.count - 1 else { break }
                let next = position + 1 < parts.count - 1 || !parts[position + 1].isEmpty ? parts[position + 1] : following
                switch separator(after: line, before: next) {
                case .dropHyphen: if result.hasSuffix("-") { result.removeLast() }
                case .text(let separator): result += separator
                }
            }
            text = result
            runs[index].text = repairingLigatures(text)
        }
        return runs
    }

    private enum Separator { case text(String), dropHyphen }

    private func separator(after line: String, before next: String) -> Separator {
        let clean = line.replacingOccurrences(of: "\u{AD}", with: "")
        let head = next.replacingOccurrences(of: "\u{AD}", with: "")
        if line.hasSuffix("\u{AD}") { return .text("") }
        // A hyphen at the end of a line between letters: the typesetter's ("Phari-" / "sees"), or
        // the word's own ("three-year-" / "old").
        if clean.hasSuffix("-"), head.first?.isLetter == true {
            let before = clean.dropLast()
            guard before.last?.isLetter == true else { return .text("") }
            let tail = String(before.reversed().prefix { $0.isLetter }.reversed())
            let lead = String(head.prefix { $0.isLetter })
            return line.hasSuffix("-") && hyphenIsTypesetting(tail, lead) ? .dropHyphen : .text("")
        }
        // Nothing between an opening quote or bracket and what it opens, or between what closes
        // and its closing mark.
        if let last = clean.last, "“‘([".contains(last) { return .text("") }
        if let first = head.first, "”’)]".contains(first) { return .text("") }
        guard let last = clean.last, last.isLetter, let first = head.first, first.isLetter,
              !next.hasPrefix("\u{AD}") else { return .text(" ") }
        let tail = String(clean.reversed().prefix { $0.isLetter }.reversed())
        let lead = String(head.prefix { $0.isLetter })
        return joins(tail, lead) ? .text("") : .text(" ")
    }
}

extension Marker {
    init(shapes: Set<VerseMarkupShape>, chapter: Int? = nil, verse: Int? = nil) {
        self.init()
        self.shapes = shapes
        self.chapter = chapter
        self.verse = verse
    }
}
#endif
