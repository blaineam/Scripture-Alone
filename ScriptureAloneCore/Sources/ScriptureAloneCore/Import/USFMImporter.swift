import Foundation

/// What a USFM zip says about itself: eBible.org ships `copr.htm` (the copyright page) and often a
/// DBL `metadata.xml`. For an eBible text the licence line is frequently the only thing standing
/// between the app and a licence violation, so it is read first and carried all the way into the
/// store's `meta` row.
public struct USFMMetadata: Sendable, Hashable, Codable {
    public var title: String?
    public var abbreviation: String?
    public var copyright: String?
    public var license: String?
    public var language: String?
    public var identifier: String?

    public init(title: String? = nil, abbreviation: String? = nil, copyright: String? = nil,
                license: String? = nil, language: String? = nil, identifier: String? = nil) {
        self.title = title
        self.abbreviation = abbreviation
        self.copyright = copyright
        self.license = license
        self.language = language
        self.identifier = identifier
    }
}

/// A zip of USFM books, opened for reading. Same refusal-first rule as `EPUBPackage`: a protected
/// archive is refused on its entry names alone, before anything is decompressed.
public struct USFMPackage: Sendable {
    /// The `.usfm`/`.sfm` entries, in archive order (which eBible numbers canonically).
    public let files: [String]
    public let metadata: USFMMetadata

    private let zip: ZipReader

    public init(url: URL) throws {
        let data: Data
        do {
            data = try Data(contentsOf: url, options: [.mappedIfSafe])
        } catch {
            throw BibleImportError.unreadableFile(error.localizedDescription)
        }
        try self.init(data: data)
    }

    public init(data: Data) throws {
        try self.init(zip: ZipReader(data: data))
    }

    init(zip: ZipReader) throws {
        if let evidence = EPUBPackage.protectionEvidence(names: zip.names) {
            throw BibleImportError.protectedByDRM(evidence)
        }
        if zip.entries.contains(where: \.isEncrypted) {
            throw BibleImportError.protectedByDRM(.zipEntryEncryption)
        }
        self.zip = zip
        files = zip.names
            .filter { name in
                let lowered = name.lowercased()
                return lowered.hasSuffix(".usfm") || lowered.hasSuffix(".sfm")
            }
            .sorted()
        guard !files.isEmpty else {
            throw BibleImportError.unsupportedFormat("the archive holds no USFM files")
        }
        metadata = Self.readMetadata(zip)
    }

    public func source(of file: String) throws -> String {
        EPUBPackage.text(try zip.data(for: file))
    }

    // MARK: - Metadata

    static func readMetadata(_ zip: ZipReader) -> USFMMetadata {
        var metadata = USFMMetadata()
        if let name = zip.names.first(where: { $0.lowercased().hasSuffix("metadata.xml") }),
           let xml = try? EPUBPackage.text(zip.data(for: name)) {
            readDBLMetadata(xml, into: &metadata)
        }
        for candidate in zip.names where Self.isCopyrightPage(candidate) {
            guard let html = try? EPUBPackage.text(zip.data(for: candidate)) else { continue }
            let text = plainText(html)
            guard !text.isEmpty else { continue }
            if metadata.copyright == nil { metadata.copyright = String(text.prefix(1500)) }
            if metadata.license == nil { metadata.license = licenseLine(text) }
            break
        }
        if metadata.license == nil, let copyright = metadata.copyright {
            metadata.license = licenseLine(copyright)
        }
        return metadata
    }

    static func isCopyrightPage(_ name: String) -> Bool {
        let leaf = (name.split(separator: "/").last.map(String.init) ?? name).lowercased()
        return leaf == "copr.htm" || leaf == "copr.html" || leaf == "copyright.htm" || leaf == "copyright.html"
    }

    /// Digital Bible Library metadata: name, abbreviation and the copyright statement.
    static func readDBLMetadata(_ xml: String, into metadata: inout USFMMetadata) {
        var path: [String] = []
        var capturing: String?
        var buffer = ""

        func commit() {
            guard let field = capturing else { return }
            let value = plainWhitespace(buffer).trimmingCharacters(in: .whitespacesAndNewlines)
            capturing = nil
            buffer = ""
            guard !value.isEmpty else { return }
            switch field {
            case "name": metadata.title = metadata.title ?? value
            case "abbreviation": metadata.abbreviation = metadata.abbreviation ?? value
            case "statement": metadata.copyright = metadata.copyright ?? value
            case "language": metadata.language = metadata.language ?? value
            case "identifier": metadata.identifier = metadata.identifier ?? value
            default: break
            }
        }

        for event in XMLScanner.scan(xml) {
            switch event {
            case .start(let tag):
                path.append(tag.name)
                let inIdentification = path.contains("identification")
                let name = tag.name
                if inIdentification, name == "name" || name == "namelocal" {
                    commit()
                    capturing = "name"
                } else if inIdentification, name == "abbreviation" || name == "abbreviationlocal" {
                    commit()
                    capturing = "abbreviation"
                } else if name == "statementcontent" || name == "fullstatement" {
                    if metadata.copyright == nil {
                        commit()
                        capturing = "statement"
                    }
                } else if name == "iso" || (name == "code" && path.contains("language")) {
                    commit()
                    capturing = "language"
                }
            case .text(let text):
                if capturing != nil { buffer += text }
            case .end(let name):
                if capturing != nil { commit() }
                if let index = path.lastIndex(of: name) { path.removeSubrange(index...) }
            }
        }
        commit()
    }

    /// The recognisable licence, when the copyright page states one.
    static func licenseLine(_ text: String) -> String? {
        let lowered = text.lowercased()
        if lowered.contains("public domain") { return "Public domain" }
        if let range = lowered.range(of: "creative commons") {
            let tail = text[range.lowerBound...].prefix(90)
            return plainWhitespace(String(tail))
        }
        for marker in ["cc by-sa", "cc by-nc-nd", "cc by-nc-sa", "cc by-nc", "cc by-nd", "cc by"] where lowered.contains(marker) {
            return marker.uppercased()
        }
        if lowered.contains("all rights reserved") { return "All rights reserved" }
        return nil
    }

    static func plainText(_ html: String) -> String {
        var output = ""
        for event in XMLScanner.scan(html) {
            switch event {
            case .text(let text): output += text
            case .start(let tag) where ["p", "br", "div", "li", "tr", "h1", "h2", "h3"].contains(tag.name): output += "\n"
            default: break
            }
        }
        return plainWhitespace(output).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func plainWhitespace(_ text: String) -> String {
        var output = ""
        var pending = false
        for character in text {
            if character.isWhitespace {
                pending = !output.isEmpty
                continue
            }
            if pending {
                output.append(" ")
                pending = false
            }
            output.append(character)
        }
        return output
    }
}

/// Reads a zip of USFM books — the shape eBible.org publishes — into the same rows the ePub path
/// produces, so both front-ends feed one `ImportedBibleBuilder`.
///
/// This is a port of `Tools/build_bibles.py`, which is what compiles the bundled ASV, BSB and KJV,
/// so an imported translation renders identically to a bundled one: the same heading and paragraph
/// kinds, the same poetry levels, the same footnote placement, the same red-letter spans, and the
/// same rule for joining a verse that runs across two paragraphs.
///
/// Two USFM shapes need a decision, and here they are:
/// - **`\v 1-2`** (a bridged verse): the text is stored under the *first* number, which is what the
///   printed text does, and the other numbers are recorded as combined with it — the coverage
///   report says "combined", not "missing".
/// - **`\v 1a`** (a partial verse): the letter is dropped and the number used. A number that has
///   already appeared in the chapter continues that verse with an unnumbered fragment, so
///   `\v 1a … \v 1b …` prints one verse number, not two.
public struct USFMImporter: Sendable {
    public var options: BibleTextExtractor.Options

    public init(options: BibleTextExtractor.Options = BibleTextExtractor.Options()) {
        self.options = options
    }

    public func extract(from package: USFMPackage) throws -> ExtractedBible {
        var files: [(name: String, usfm: String)] = []
        var unreadable: [ImportNote] = []
        for file in package.files {
            do {
                files.append((file, try package.source(of: file)))
            } catch let error as BibleImportError {
                unreadable.append(ImportNote(.warning, "\(file) could not be read: \(error.localizedDescription)"))
            }
        }
        var bible = try extract(files: files)
        bible.notes.insert(contentsOf: unreadable, at: 0)
        return bible
    }

    /// Parses USFM sources already in hand.
    public func extract(files: [(name: String, usfm: String)]) throws -> ExtractedBible {
        var bible = ExtractedBible()
        var outOfOrder: Set<ChapterRef> = []
        var bridged: [VerseRef: VerseRef] = [:]
        var notes: [ImportNote] = []

        // Canonical order, whatever order the archive stored them in.
        var parsed: [(book: BookID, name: String, source: String)] = []
        for file in files {
            guard let code = USFMBookParser.bookCode(in: file.usfm) else {
                notes.append(ImportNote(.warning, "\(file.name): no \\id marker, so it was skipped."))
                continue
            }
            guard let book = USFMBookParser.book(forCode: code) else {
                notes.append(ImportNote(.warning, "\(file.name): unknown book code “\(code)”, so it was skipped."))
                continue
            }
            parsed.append((book, file.name, file.usfm))
        }
        for entry in parsed.sorted(by: { $0.book < $1.book }) {
            var parser = USFMBookParser(options: options, book: entry.book)
            parser.parse(entry.source, into: &bible)
            outOfOrder.formUnion(parser.outOfOrder)
            bridged.merge(parser.bridged) { first, _ in first }
            bible.shapesByDocument[entry.name] = .usfmMarkers
            notes.append(contentsOf: parser.notes)
        }

        bible.tidy()
        bible.outOfOrderChapters = outOfOrder
        bible.bridgedVerses = bridged
        bible.notes.append(contentsOf: notes)
        for chapter in outOfOrder.sorted() {
            bible.notes.append(ImportNote(.warning, "\(chapter.display): verse numbers ran out of order."))
        }
        if !bridged.isEmpty {
            bible.notes.append(ImportNote(.info, "\(bridged.count) verse(s) are printed combined with the verse before them."))
        }
        guard !bible.isEmpty else { throw BibleImportError.noScriptureFound }
        return bible
    }
}

// MARK: - The parser

/// One USFM book. A direct port of the `Parser` class in `Tools/build_bibles.py`.
struct USFMBookParser {
    let options: BibleTextExtractor.Options
    let book: BookID

    var outOfOrder: Set<ChapterRef> = []
    var bridged: [VerseRef: VerseRef] = [:]
    var notes: [ImportNote] = []

    private var chapter = 0
    private var verse = 0
    private var block: ExtractedBlock?
    private var fragmentIndex: Int?
    private var styles: [StyledSpan.Style] = []
    private var inWord = false
    private var inReference = false
    private var note: [String]?
    private var noteField = ""
    private var noteIsCrossReference = false
    private var skipText = false
    private var pendingNumber = false
    private var seenVerses: Set<Int> = []
    private var lastVerse = 0

    /// Markers whose text is file metadata, never shown.
    static let skipped: Set<String> = ["id", "usfm", "ide", "h", "toc1", "toc2", "toc3",
                                       "mt", "mt1", "mt2", "mt3", "rem", "sts", "cl", "cp", "va", "vp"]
    /// Markers that carry only a heading string.
    static let headings: [String: ExtractedBlock.Kind] = [
        "s": .heading, "s1": .heading, "s2": .subheading, "s3": .subheading,
        "ms": .majorSection, "ms1": .majorSection, "mr": .parallel, "r": .parallel,
        "qa": .acrostic, "sp": .subheading,
    ]
    /// Markers that carry verse text.
    static let textBlocks: [String: ExtractedBlock.Kind] = [
        "p": .paragraph, "m": .continuation, "nb": .continuation, "pmo": .embedded, "pm": .embedded,
        "pc": .centered, "pi": .embedded, "pi1": .embedded, "mi": .embedded,
        "li": .list1, "li1": .list1, "li2": .list2,
        "q": .poetry1, "q1": .poetry1, "q2": .poetry2, "q3": .poetry2, "qr": .selah, "qc": .centered,
        "d": .title,
    ]
    static let characterStyles: [String: StyledSpan.Style] = ["wj": .wordsOfChrist, "add": .supplied, "nd": .smallCaps]

    init(options: BibleTextExtractor.Options, book: BookID) {
        self.options = options
        self.book = book
    }

    /// The `\id` code at the top of a USFM file.
    static func bookCode(in source: String) -> String? {
        guard let range = source.range(of: "\\id") else { return nil }
        let tail = source[range.upperBound...].prefix(40)
        let token = tail.split(whereSeparator: { $0.isWhitespace }).first.map(String.init)
        guard let token, !token.isEmpty else { return nil }
        return String(token.prefix(3)).uppercased()
    }

    /// Maps a USFM code onto the canon the app already carries (`BookInfo.code`).
    static func book(forCode code: String) -> BookID? {
        let wanted = code.uppercased()
        if let book = BookID.allCases.first(where: { $0.code == wanted }) { return book }
        // A handful of files use older three-letter codes the canon lists as an alias.
        return ScriptureLabels.exactBook(wanted.lowercased())
    }

    // MARK: Driving

    mutating func parse(_ source: String, into bible: inout ExtractedBible) {
        let characters = Array(source.replacingOccurrences(of: "\u{FEFF}", with: ""))
        var index = 0
        var text = ""

        while index < characters.count {
            guard characters[index] == "\\" else {
                text.append(characters[index])
                index += 1
                continue
            }
            addText(text, into: &bible)
            text = ""

            var cursor = index + 1
            if cursor < characters.count, characters[cursor] == "+" { cursor += 1 }
            var name = ""
            while cursor < characters.count, characters[cursor].isLetter {
                name.append(characters[cursor])
                cursor += 1
            }
            while cursor < characters.count, characters[cursor].isNumber {
                name.append(characters[cursor])
                cursor += 1
            }
            guard !name.isEmpty else {
                text.append("\\")
                index += 1
                continue
            }
            var closing = false
            if cursor < characters.count, characters[cursor] == "*" {
                closing = true
                cursor += 1
            }
            index = cursor
            marker(name.lowercased(), closing: closing, characters: characters, index: &index, into: &bible)
        }
        addText(text, into: &bible)
        closeBlock(into: &bible)
    }

    private mutating func marker(_ name: String, closing: Bool, characters: [Character], index: inout Int,
                                 into bible: inout ExtractedBible) {
        // Footnotes and cross references.
        if name == "f" || name == "x" || name == "fe" {
            if closing {
                finishNote(into: &bible)
                // USFM only eats the space after an *opening* marker; the space after \f* belongs
                // to the sentence.
                return
            }
            note = []
            noteField = "caller"
            noteIsCrossReference = name == "x"
            skipOneSpace(characters, &index)
            return
        }
        if note != nil {
            if name.hasPrefix("f") || name.hasPrefix("x") {
                noteField = closing ? "ft" : name
            } else if name == "ref" {
                inReference = !closing
            }
            if !closing { skipOneSpace(characters, &index) }
            return
        }

        if closing {
            if let style = Self.characterStyles[name], let position = styles.lastIndex(of: style) {
                styles.remove(at: position)
            } else if name == "w" {
                inWord = false
            } else if name == "ref" {
                inReference = false
            }
            return
        }

        skipText = false
        if Self.skipped.contains(name) {
            skipText = true
            closeBlock(into: &bible)
            return
        }
        if name == "c" {
            closeBlock(into: &bible)
            chapter = number(characters, &index) ?? chapter + 1
            verse = 0
            lastVerse = 0
            seenVerses = []
            // Red letters can run across a chapter boundary; the other styles cannot.
            styles = styles.filter { $0 == .wordsOfChrist }
            skipOneSpace(characters, &index)
            return
        }
        if name == "v" {
            let parsed = verseNumber(characters, &index)
            skipOneSpace(characters, &index)
            if let parsed { start(verse: parsed.number, through: parsed.through) }
            return
        }
        if let kind = Self.headings[name] {
            startBlock(kind, into: &bible)
            skipOneSpace(characters, &index)
            return
        }
        if name == "b" {
            startBlock(.stanzaBreak, into: &bible)
            skipOneSpace(characters, &index)
            return
        }
        if let kind = Self.textBlocks[name] {
            startBlock(kind, into: &bible)
            skipOneSpace(characters, &index)
            return
        }
        if let style = Self.characterStyles[name] {
            if style != .wordsOfChrist || options.redLetters { styles.append(style) }
            skipOneSpace(characters, &index)
            return
        }
        if name == "w" {
            inWord = true
            skipOneSpace(characters, &index)
            return
        }
        if name == "ref" {
            inReference = true
            skipOneSpace(characters, &index)
            return
        }
        // Unknown character markers (tl, it, qs, bk, …) simply pass their text through.
        skipOneSpace(characters, &index)
    }

    // MARK: Cursor helpers

    private func skipOneSpace(_ characters: [Character], _ index: inout Int) {
        if index < characters.count, characters[index].isWhitespace { index += 1 }
    }

    private func number(_ characters: [Character], _ index: inout Int) -> Int? {
        while index < characters.count, characters[index] == " " { index += 1 }
        var digits = ""
        while index < characters.count, characters[index].isNumber {
            digits.append(characters[index])
            index += 1
        }
        return Int(digits)
    }

    /// `\v 1`, `\v 1-2`, `\v 1–2`, `\v 1a`. Returns the number and, for a bridge, its last number.
    private func verseNumber(_ characters: [Character], _ index: inout Int) -> (number: Int, through: Int?)? {
        guard let first = number(characters, &index) else { return nil }
        var suffix = ""
        while index < characters.count, !characters[index].isWhitespace {
            suffix.append(characters[index])
            index += 1
        }
        guard !suffix.isEmpty else { return (first, nil) }
        let separators: Set<Character> = ["-", "\u{2010}", "\u{2011}", "\u{2012}", "\u{2013}", "\u{2014}", ","]
        guard let mark = suffix.first, separators.contains(mark) else { return (first, nil) }
        let tail = suffix.dropFirst().prefix { $0.isNumber }
        guard let last = Int(tail), last > first, last - first < 20 else { return (first, nil) }
        return (first, last)
    }

    // MARK: Structure

    private mutating func startBlock(_ kind: ExtractedBlock.Kind, into bible: inout ExtractedBible) {
        closeBlock(into: &bible)
        if kind.isHeading, !options.headings {
            // Drop the heading's own text too, rather than letting it fall into a verse.
            skipText = true
            return
        }
        block = ExtractedBlock(kind: kind, heading: kind.isHeading ? "" : nil)
        fragmentIndex = nil
    }

    private mutating func closeBlock(into bible: inout ExtractedBible) {
        defer {
            block = nil
            fragmentIndex = nil
        }
        guard let finished = block, chapter > 0 else { return }
        guard !finished.isEmpty else { return }
        bible.append(finished, to: ChapterRef(book, chapter))
    }

    private mutating func ensureTextBlock(into bible: inout ExtractedBible) {
        if block == nil || block!.kind.isHeading || block!.kind == .stanzaBreak {
            startBlock(.continuation, into: &bible)
        }
    }

    private mutating func start(verse number: Int, through: Int?) {
        if let through {
            for extra in (number + 1)...through {
                bridged[VerseRef(book, chapter, extra)] = VerseRef(book, chapter, number)
            }
        }
        if number < lastVerse, chapter > 0 { outOfOrder.insert(ChapterRef(book, chapter)) }
        let repeated = seenVerses.contains(number)
        verse = number
        lastVerse = number
        seenVerses.insert(number)
        pendingVerseIsNumbered = !repeated
        pendingVerseStart = true
    }

    private var pendingVerseIsNumbered = true
    private var pendingVerseStart = false

    private mutating func startFragment(numbered: Bool, into bible: inout ExtractedBible) {
        ensureTextBlock(into: &bible)
        var wantsNumber = numbered
        if numbered, block?.kind == .title {
            // A superscription ("A Psalm of David.") prints unnumbered; the number moves to the
            // first line of the psalm.
            pendingNumber = true
            wantsNumber = false
        } else if pendingNumber, block?.kind != .title {
            wantsNumber = true
            pendingNumber = false
        }
        block?.fragments.append(ExtractedFragment(verse: verse, numbered: wantsNumber, text: ""))
        fragmentIndex = (block?.fragments.count ?? 1) - 1
    }

    // MARK: Text

    private mutating func addText(_ raw: String, into bible: inout ExtractedBible) {
        guard !skipText, !raw.isEmpty else { return }
        var source = raw
        if inWord || inReference, let bar = source.firstIndex(of: "|") {
            source = String(source[..<bar])
        }
        let text = DocumentScanner.collapse(source)
        guard !text.isEmpty else { return }

        if note != nil {
            if noteField != "fr" && noteField != "caller" { note?.append(text) }
            return
        }
        if block != nil, block!.kind.isHeading {
            var addition = text
            if block!.heading == nil { block!.heading = "" }
            if block!.heading!.isEmpty || block!.heading!.hasSuffix(" ") {
                while addition.hasPrefix(" ") { addition.removeFirst() }
            }
            block!.heading! += addition
            return
        }
        if text.trimmingCharacters(in: .whitespaces).isEmpty {
            let current = fragmentIndex.flatMap { block?.fragments[$0].text }
            if current == nil || current!.isEmpty || current!.hasSuffix(" ") { return }
        }
        if verse == 0, block?.kind != .title { return }

        if pendingVerseStart {
            pendingVerseStart = false
            startFragment(numbered: pendingVerseIsNumbered, into: &bible)
        }
        if fragmentIndex == nil { startFragment(numbered: false, into: &bible) }
        guard let index = fragmentIndex, var current = block, index < current.fragments.count else { return }

        var addition = text
        if current.fragments[index].text.isEmpty || current.fragments[index].text.hasSuffix(" ") {
            while addition.hasPrefix(" ") { addition.removeFirst() }
        }
        guard !addition.isEmpty else { return }
        let startsFragment = current.fragments[index].text.isEmpty
        let start = current.fragments[index].text.unicodeScalars.count
        let length = addition.unicodeScalars.count
        current.fragments[index].text += addition
        for style in Set(styles) {
            current.fragments[index].spans.append(StyledSpan(start: start, length: length, style: style))
        }
        current.fragments[index].spans = ExtractedBible.merge(current.fragments[index].spans)
        block = current

        if verse > 0, chapter > 0, block?.kind != .title {
            let red = styles.contains(.wordsOfChrist) ? [ScalarSpan(start: 0, length: length)] : []
            bible.appendVerseText(addition, red: red, to: VerseRef(book, chapter, verse), separate: startsFragment)
        }
    }

    private mutating func finishNote(into bible: inout ExtractedBible) {
        defer {
            note = nil
            noteField = ""
        }
        guard options.footnotes, !noteIsCrossReference, let parts = note else { return }
        let body = USFMPackage.plainWhitespace(parts.joined()).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty, let index = fragmentIndex, var current = block, index < current.fragments.count else { return }
        let position = current.fragments[index].text.unicodeScalars.count
        current.fragments[index].footnotes.append(ExtractedFootnote(position: position, text: body))
        block = current
    }
}
