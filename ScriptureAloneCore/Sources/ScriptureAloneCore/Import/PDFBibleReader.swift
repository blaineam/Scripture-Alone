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
        for (index, runs) in pages.enumerated() {
            let (document, stillOutside) = scan(runs, page: index, body: body, rare: rare, outside: outside, lastVerse: &lastVerse)
            outside = stillOutside
            assembler.consume(document, path: "page \(index + 1)")
        }
        assembler.finish()
        guard !assembler.bible.isEmpty else { throw BibleImportError.noScriptureFound }
        return assembler.bible
    }

    /// Turns one page's runs into a flow. `outside` is true while the reader is in front or back
    /// matter: set by a large title that isn't a book, cleared by one that is.
    func scan(_ runs: [Run], page: Int, body: Double, rare: Set<Double>, outside: Bool,
              lastVerse: inout Int) -> (ScannedDocument, Bool) {
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

        for (index, run) in runs.enumerated() {
            let text = run.text.replacingOccurrences(of: "\u{AD}", with: "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty, !Self.isPrinterSlug(text) else { continue }
            let ratio = run.size / body
            let next = runs.dropFirst(index + 1).first { !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

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
                let isLabel = Self.isCallerLetters(text) && ratio < 0.58
                    && next.map { Self.isReference($0.text) } == true
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
                if Self.isCallerLetters(text) {
                    if options.footnotes {
                        document.flow.append(.noteMarker(id: Self.noteID(page: page, label: text), label: text))
                    }
                    // A caller sits in a word break; the break survives it.
                    let after = next?.text.first
                    if run.text.first?.isWhitespace == true || run.text.last?.isWhitespace == true
                        || after == "\u{AD}" || after?.isWhitespace == true {
                        document.flow.append(.text(" ", red: false))
                    }
                } else if let number = Self.number(text), Self.followsOn(number, after: lastVerse) {
                    lastVerse = number
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
                if Self.number(text) != nil || Self.isRunningHead(text) { continue }
                if options.headings { appendHeading(Self.headingText(text), to: &document) }
                continue
            }

            // Body-sized. A run of nothing but a number is a verse number: a font change (bold,
            // or a different face) is what split it from the words around it.
            // …if it is the number a verse would have here. Measures and dates set in another font
            // ("75 feet", "7 1/2") are numbers too, and go back into the text.
            if let number = Self.number(text), number < 200, Self.followsOn(number, after: lastVerse) {
                lastVerse = number
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
            document.flow.append(.text(Self.bodyText(run.text), red: false))
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
    static func runs(on page: PDFPage?) -> [Run] {
        guard let page, let all = page.selection(for: page.bounds(for: .cropBox)) else { return [] }
        struct Piece { var rect: CGRect; var text: NSAttributedString }
        var pieces = all.selectionsByLine().compactMap { line -> Piece? in
            guard let text = line.attributedString, text.length > 0,
                  !text.string.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
            return Piece(rect: line.bounds(for: page), text: text)
        }
        guard !pieces.isEmpty else { return [] }
        let smallCaps = smallCapitalNames(on: page)
        if !smallCaps.isEmpty {
            pieces = pieces.map { Piece(rect: $0.rect, text: restoringSmallCapitals($0.text, in: $0.rect, occurrences: smallCaps)) }
        }
        pieces = mergedStackedDigits(pieces.map { ($0.rect, $0.text) }).map { Piece(rect: $0.0, text: $0.1) }
        let left = pieces.map(\.rect.minX).min() ?? 0
        let right = pieces.map(\.rect.maxX).max() ?? 0
        // The gutter is where the fewest lines cross, near the middle — not the middle itself: a
        // left-hand page's columns sit off-centre on the sheet.
        let narrow = pieces.filter { $0.rect.width < (right - left) * 0.6 }
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
        let twoColumns = rightOnly > 2 && open.count * 2 >= crossing.count
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
                let r = piece.rect
                if let l = text(in: CGRect(x: r.minX - 1, y: r.minY, width: middle - r.minX + 1, height: r.height)) {
                    split.append(Piece(rect: CGRect(x: r.minX, y: r.minY, width: middle - r.minX, height: r.height), text: l))
                }
                if let rt = text(in: CGRect(x: middle, y: r.minY, width: r.maxX - middle + 1, height: r.height)) {
                    split.append(Piece(rect: CGRect(x: middle, y: r.minY, width: r.maxX - middle, height: r.height), text: rt))
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
            // two or three).
            let sameLine = pieces.filter { other in
                let overlap = min(other.rect.maxY, piece.rect.maxY) - max(other.rect.minY, piece.rect.minY)
                return other.rect.width >= 40 && overlap > min(other.rect.height, piece.rect.height) * 0.3
            }
            let before = sameLine.filter { $0.rect.maxX <= piece.rect.minX + 1 }.map { piece.rect.minX - $0.rect.maxX }.min()
            let after = sameLine.filter { $0.rect.minX >= piece.rect.maxX - 1 }
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

        var runs: [Run] = []
        var previous: Piece?
        for piece in ordered {
            // Further along the same line: a space where there's a visible gap, else nothing.
            if let previous, abs(previous.rect.midY - piece.rect.midY) <= 2, piece.rect.minX >= previous.rect.maxX - 1,
               var last = runs.popLast() {
                if last.text.hasSuffix("\n") { last.text.removeLast() }
                if piece.rect.minX - previous.rect.maxX > 1.5 { last.text += " " }
                runs.append(last)
            }
            let string = piece.text.string as NSString
            piece.text.enumerateAttributes(in: NSRange(location: 0, length: piece.text.length)) { attributes, range, _ in
                let size = (attributes[.font] as? PlatformFont).map { Double($0.pointSize) } ?? 0
                runs.append(Run(text: string.substring(with: range), size: size, ownLine: false))
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
            lineStart = endsLine
        }
        return runs
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
            let boxes = letters.map { page.selection(for: NSRange(location: $0, length: 1))?.bounds(for: page) ?? .zero }
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
    static func mergedStackedDigits(_ pieces: [(CGRect, NSAttributedString)]) -> [(CGRect, NSAttributedString)] {
        func size(_ text: NSAttributedString) -> Double {
            (text.attribute(.font, at: 0, effectiveRange: nil) as? PlatformFont).map { Double($0.pointSize) } ?? 0
        }
        let sizes = pieces.map { size($0.1) }.sorted()
        let typical = sizes.isEmpty ? 0 : sizes[sizes.count / 2]
        var result = pieces
        var index = 0
        while index < result.count {
            let (rect, text) = result[index]
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
                result[index] = (rect.union(result[below].0), merged)
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
        return ScriptureLabels.heading(words)?.book != nil
    }

    /// The imposition slug a printer leaves on every page ("Bible.indb 11 10/26/17 8:59 PM").
    static func isPrinterSlug(_ text: String) -> Bool {
        text.firstMatch(of: /\.(indb|indd|qxp|pdf)\b/) != nil
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
        text.replacing(/(\p{L}*f) (\p{Ll}+)/) { match in
            let tail = String(match.output.2)
            return isFragment(tail) ? "\(match.output.1)\(tail)" : String(match.output.0)
        }
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

    /// Replaces each line break with nothing (inside a word) or a space (between words).
    func resolveLineBreaks(in runs: [PDFBibleReader.Run]) -> [PDFBibleReader.Run] {
        var runs = runs
        // A ligature is often a run of its own, so its break can fall between runs too.
        for index in runs.indices.dropLast() {
            let text = runs[index].text.replacingOccurrences(of: "\u{AD}", with: "")
            guard text.hasSuffix(" "), Self.endsInLigature(String(text.dropLast()).lowercased()) else { continue }
            let tail = String(runs[index + 1].text.replacingOccurrences(of: "\u{AD}", with: "").prefix { $0.isLetter })
            guard let first = tail.first, first.isLowercase, isFragment(tail) else { continue }
            while runs[index].text.last?.isWhitespace == true { runs[index].text.removeLast() }
        }
        for index in runs.indices {
            var text = runs[index].text
            guard text.contains("\n") else {
                runs[index].text = repairingLigatures(text)
                continue
            }
            let following = runs[(index + 1)...].first { !$0.text.isEmpty }?.text ?? ""
            var result = ""
            let parts = text.components(separatedBy: "\n")
            for (position, part) in parts.enumerated() {
                result += part
                guard position < parts.count - 1 else { break }
                let next = position + 1 < parts.count - 1 || !parts[position + 1].isEmpty ? parts[position + 1] : following
                result += separator(after: part, before: next)
            }
            text = result
            runs[index].text = repairingLigatures(text)
        }
        return runs
    }

    private func separator(after line: String, before next: String) -> String {
        let clean = line.replacingOccurrences(of: "\u{AD}", with: "")
        // A real hyphen at the end of a line between letters: a compound, kept ("three-year-" / "old").
        if clean.hasSuffix("-"), let first = next.first, first.isLetter { return "" }
        if line.hasSuffix("\u{AD}") { return "" }
        guard let last = clean.last, last.isLetter,
              let head = next.replacingOccurrences(of: "\u{AD}", with: "").first, head.isLetter,
              !next.hasPrefix("\u{AD}") else { return " " }
        let tail = String(clean.reversed().prefix { $0.isLetter }.reversed())
        let lead = String(next.replacingOccurrences(of: "\u{AD}", with: "").prefix { $0.isLetter })
        return joins(tail, lead) ? "" : " "
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
