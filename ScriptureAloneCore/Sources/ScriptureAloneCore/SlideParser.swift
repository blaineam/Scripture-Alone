import Foundation

/// One line of text recognized on a photographed sermon slide.
public struct SlideLine: Hashable, Sendable {
    public var text: String
    /// Normalized to the image (0...1), origin at the TOP-left, y growing downward.
    public var box: Box
    /// Recognizer confidence, 0...1.
    public var confidence: Double

    public struct Box: Hashable, Sendable {
        public var x: Double
        public var y: Double
        public var width: Double
        public var height: Double

        public init(x: Double, y: Double, width: Double, height: Double) {
            self.x = x
            self.y = y
            self.width = width
            self.height = height
        }

        public var midY: Double { y + height / 2 }
        public var maxY: Double { y + height }
        public var maxX: Double { x + width }
    }

    public init(_ text: String, x: Double, y: Double, width: Double, height: Double, confidence: Double = 1) {
        self.text = text
        self.box = Box(x: x, y: y, width: width, height: height)
        self.confidence = confidence
    }
}

/// What a slide says: the sermon title, the passages it cites, and the rest of its lines.
public struct SlideReading: Hashable, Sendable {
    public var title: String
    public var passages: [Passage]
    public var bodyLines: [String]

    public init(title: String, passages: [Passage], bodyLines: [String]) {
        self.title = title
        self.passages = passages
        self.bodyLines = bodyLines
    }
}

/// Turns recognized slide text into a note: the most prominent line becomes the title, every
/// scripture reference becomes an anchor, and the remaining meaningful lines become the body.
/// Pure logic — the recognizer that produces `SlideLine`s lives in the app.
public enum SlideParser {
    public static func read(_ rawLines: [SlideLine]) -> SlideReading {
        let lines = readingOrder(rawLines
            .filter { $0.confidence >= 0.3 }
            .map { line in
                var line = line
                line.text = normalize(line.text)
                return line
            }
            .filter { !$0.text.isEmpty })

        // References are found in the whole slide, lines joined, so one broken across two
        // lines ("1 Corinthians" / "13:4–7") is still found.
        var joined = ""
        var spans: [NSRange] = []
        for line in lines {
            if !joined.isEmpty { joined += "\n" }
            let start = (joined as NSString).length
            joined += line.text
            spans.append(NSRange(location: start, length: (line.text as NSString).length))
        }
        let matches = ReferenceDetector.detect(in: joined)
        let passages = dedupe(matches.map(\.passage))

        // What is left of each line once its references are removed.
        let residuals: [String] = spans.enumerated().map { index, span in
            let cuts = matches.map(\.range)
                .map { NSIntersectionRange($0, span) }
                .filter { $0.length > 0 }
            guard !cuts.isEmpty else { return lines[index].text }
            var text = lines[index].text as NSString
            for cut in cuts.sorted(by: { $0.location > $1.location }) {
                text = text.replacingCharacters(in: NSRange(location: cut.location - span.location, length: cut.length),
                                                with: " ") as NSString
            }
            return stripReferenceLabels(text as String)
        }

        struct Candidate {
            let index: Int
            let text: String
            let line: SlideLine
            let kind: Kind
        }

        let candidates: [Candidate] = lines.indices.map { index in
            let text = residuals[index]
            return Candidate(index: index, text: text, line: lines[index], kind: classify(text))
        }

        // Title: the tallest content line, favoring lines nearer the top.
        let titleable = candidates.filter { $0.kind == .content && isTitleLike($0.text) }
        var titleIndices: [Int] = []
        if let best = titleable.max(by: { titleScore($0.line) < titleScore($1.line) }) {
            titleIndices = [best.index]
            // Multi-line titles: neighbors of similar height, stacked tightly, overlapping horizontally.
            func joins(_ a: SlideLine, _ b: SlideLine) -> Bool {
                let ratio = min(a.box.height, b.box.height) / max(a.box.height, b.box.height)
                let gap = max(b.box.y - a.box.maxY, a.box.y - b.box.maxY)
                let overlap = min(a.box.maxX, b.box.maxX) - max(a.box.x, b.box.x)
                return ratio > 0.8 && gap < max(a.box.height, b.box.height) * 0.9 && overlap > 0
            }
            var upper = best.index
            while upper > 0, let previous = titleable.first(where: { $0.index == upper - 1 }),
                  joins(previous.line, lines[upper]), titleIndices.count < 3 {
                upper -= 1
                titleIndices.insert(upper, at: 0)
            }
            var lower = best.index
            while let next = titleable.first(where: { $0.index == lower + 1 }),
                  joins(lines[lower], next.line), titleIndices.count < 3 {
                lower += 1
                titleIndices.append(lower)
            }
        }
        let title = cleanTitle(joinLines(titleIndices.map { residuals[$0] }))

        var seen = Set<String>()
        if !title.isEmpty { seen.insert(foldKey(title)) }
        var body: [String] = []
        for candidate in candidates where candidate.kind == .content && !titleIndices.contains(candidate.index) {
            let key = foldKey(candidate.text)
            guard !key.isEmpty, !seen.contains(key) else { continue }
            seen.insert(key)
            body.append(candidate.text)
        }
        return SlideReading(title: title, passages: passages, bodyLines: body)
    }

    /// Convenience for plain text (one line per row, no geometry): the first content line is the title.
    public static func read(text: String) -> SlideReading {
        let rows = text.split(whereSeparator: \.isNewline).map(String.init)
        let count = Double(max(rows.count, 1))
        return read(rows.enumerated().map { index, row in
            SlideLine(row, x: 0.1, y: Double(index) / count, width: 0.8, height: 0.5 / count)
        })
    }

    // MARK: - Ranges

    /// Resolves passages against a translation's verse counts: chapters and verses are clamped
    /// to what exists (OCR reads "Psalm 23:1-66" sometimes), and duplicates drop out.
    public static func ranges(for passages: [Passage], verseCount: (ChapterRef) -> Int) -> [VerseRange] {
        var result: [VerseRange] = []
        for passage in passages {
            let p = passage.clamped
            let startCount = verseCount(ChapterRef(p.book, p.startChapter))
            let endCount = verseCount(ChapterRef(p.book, p.endChapter))
            guard startCount > 0, endCount > 0 else { continue }
            let startVerse = min(max(1, p.startVerse ?? 1), startCount)
            let endVerse = min(max(1, p.endVerse ?? endCount), endCount)
            let range = VerseRange(VerseRef(p.book, p.startChapter, startVerse), VerseRef(p.book, p.endChapter, endVerse))
            if !result.contains(range) { result.append(range) }
        }
        return result
    }

    /// Adds a slide's ranges to a note's, dropping any range another one already covers
    /// ("John 10:11" once "John 10:11–15" is there).
    public static func merging(_ existing: [VerseRange], _ added: [VerseRange]) -> [VerseRange] {
        var all: [VerseRange] = []
        for range in existing + added where !all.contains(range) { all.append(range) }
        return all.filter { range in
            !all.contains { other in other != range && other.start <= range.start && range.end <= other.end }
        }
    }

    /// Appends slide lines to an existing note body as bullets, skipping lines the body already
    /// has (a later slide often repeats the sermon title or the point before it).
    public static func append(heading: String?, lines: [String], to body: String, title: String = "") -> String {
        var existing = Set(body.split(whereSeparator: \.isNewline).map { foldKey(String($0)) })
        if !title.isEmpty { existing.insert(foldKey(title)) }
        var additions: [String] = []
        if let heading, !heading.trimmingCharacters(in: .whitespaces).isEmpty, !existing.contains(foldKey(heading)) {
            additions.append(heading.trimmingCharacters(in: .whitespaces))
        }
        for line in lines {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            guard !trimmed.isEmpty, !existing.contains(foldKey(trimmed)),
                  !additions.contains(where: { foldKey($0) == foldKey(trimmed) }) else { continue }
            additions.append(bullet + trimmed)
        }
        guard !additions.isEmpty else { return body }
        let trimmedBody = body.trimmingCharacters(in: .whitespacesAndNewlines)
        let block = additions.joined(separator: "\n")
        return trimmedBody.isEmpty ? block : trimmedBody + "\n\n" + block
    }

    public static let bullet = "• "

    /// Whether a slide line is already in the note (its title or a line of its body) — a later
    /// slide's running header, for instance.
    public static func noteAlreadyHas(_ line: String, title: String, body: String) -> Bool {
        let key = foldKey(line)
        guard !key.isEmpty else { return true }
        if key == foldKey(title) { return true }
        return body.split(whereSeparator: \.isNewline).contains { foldKey(String($0)) == key }
    }

    /// The body a new note starts with.
    public static func body(for lines: [String]) -> String {
        lines.map { bullet + $0 }.joined(separator: "\n")
    }

    // MARK: - Normalizing OCR text

    static func normalize(_ raw: String) -> String {
        var s = raw
            .replacingOccurrences(of: "\u{2018}", with: "'")
            .replacingOccurrences(of: "\u{2019}", with: "'")
            .replacingOccurrences(of: "\u{201C}", with: "\"")
            .replacingOccurrences(of: "\u{201D}", with: "\"")
            .replacingOccurrences(of: "\u{2010}", with: "-")
            .replacingOccurrences(of: "\u{2011}", with: "-")
            .replacingOccurrences(of: "\u{2012}", with: "–")
            .replacingOccurrences(of: "\u{2212}", with: "-")
            .replacingOccurrences(of: "\u{FF1A}", with: ":")
            .replacingOccurrences(of: "\u{00A0}", with: " ")
        // "Romans 8, vv. 1-17" / "Romans 8 verses 1–17" → "Romans 8:1-17"
        s = s.replacingOccurrences(of: #"(\d{1,3})\s*,?\s*\b(?:vv?|verses?|vs)\.?\s*(\d{1,3})"#,
                                   with: "$1:$2", options: [.regularExpression, .caseInsensitive])
        // "(Jn 10:3)" often loses its J to the parenthesis ("(n 10:3)"), and a J at the start of a
        // line reads as "In". Only with chapter:verse after it — "in 3 ways" stays as it is.
        s = s.replacingOccurrences(of: #"\((?:n|In|ln)\.?\s*(?=\d{1,3}\s*:\s*\d)"#, with: "(Jn ", options: .regularExpression)
        s = s.replacingOccurrences(of: #"^(?:In|ln)\.?\s+(?=\d{1,3}\s*:\s*\d)"#, with: "Jn ", options: .regularExpression)
        // OCR reads the colon of "3:16" as a semicolon when there is no space around it.
        s = s.replacingOccurrences(of: #"(?<=[A-Za-z.]\s?\d{1,3});(?=\d)"#, with: ":", options: .regularExpression)
        s = s.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
        return s.trimmingCharacters(in: .whitespaces)
    }

    /// "Text: " / "Scripture —" / "·" / "&" left behind once references are cut out.
    static func stripReferenceLabels(_ text: String) -> String {
        var s = text
        s = s.replacingOccurrences(
            of: #"(?i)\b(?:scriptures?|texts?|readings?|passages?|key verses?|focal passage|see also|also|and|cf)\b\s*:?"#,
            with: " ", options: .regularExpression)
        s = trimSeparators(s)
        // Only separators or a stray letter left: the line was all references.
        return s.filter(\.isLetter).count < 2 ? "" : s
    }

    static func trimSeparators(_ text: String) -> String {
        var s = text.replacingOccurrences(of: #"\s*[·•|;,&/]\s*(?=[·•|;,&/]|$)"#, with: "", options: .regularExpression)
        s = s.replacingOccurrences(of: #"\(\s*\)|\[\s*\]"#, with: "", options: .regularExpression)
        s = s.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
        let edges = CharacterSet.whitespaces.union(CharacterSet(charactersIn: "·•|;,:&/-–—("))
        s = s.trimmingCharacters(in: edges)
        if s.hasSuffix(")") && !s.contains("(") { s.removeLast() }
        return s.trimmingCharacters(in: edges)
    }

    // MARK: - Classifying lines

    private enum Kind { case content, noise }

    private static func classify(_ text: String) -> Kind {
        let letters = text.filter(\.isLetter).count
        // A Chinese, Japanese or Korean word is whole in two or three characters (恩典, 好牧人,
        // 선한 목자), so the length floors below that suit Latin text would throw a title away.
        let cjk = text.unicodeScalars.contains { (0x3040...0x30FF).contains($0.value)
            || (0x3400...0x9FFF).contains($0.value) || (0xAC00...0xD7AF).contains($0.value) }
        if letters < (cjk ? 2 : 3) { return .noise }
        let lower = text.lowercased()
        func has(_ pattern: String) -> Bool { lower.range(of: pattern, options: .regularExpression) != nil }

        if has(#"https?://|www\.|\.(com|org|net|church|tv|io)\b|@[a-z0-9_]{2,}|#[a-z]{3,}"#) { return .noise }
        // TODO(l10n): the noise patterns here (copyright, welcome/announcements) and the month and
        // weekday names in `months`/`weekdays` are English only; slides in other languages will keep
        // those lines as content until per-language keyword lists are added.
        if has(#"\bccli\b|copyright|©|all rights reserved|license\s*#|streaming license"#) { return .noise }
        if has(#"^(welcome|welcome home|welcome to\b.*|good morning|glad you'?re here|please silence.*|let'?s worship|let us pray|announcements?|offering|giving|prayer requests?|connect card.*|wi-?fi.*|text .* to \d+)[!. ]*$"#) { return .noise }
        if isDateOrTime(lower) { return .noise }
        if has(#"^(slide|page)?\s*\d+\s*(/|of)\s*\d+$"#) { return .noise }
        if isChurchName(text) { return .noise }
        if !cjk && letters < 4 && text.count <= 4 { return .noise }
        return .content
    }

    private static let months = "jan(uary)?|feb(ruary)?|mar(ch)?|apr(il)?|may|june?|july?|aug(ust)?|sep(t|tember)?|oct(ober)?|nov(ember)?|dec(ember)?"
    private static let weekdays = "sun(day)?|mon(day)?|tue(s|sday)?|wed(nesday)?|thu(rs|rsday)?|fri(day)?|sat(urday)?"

    private static func isDateOrTime(_ lower: String) -> Bool {
        // Strip every date/time token; if (almost) nothing is left, the line was a date.
        var s = lower
        let tokens = [
            #"\b(\#(weekdays))\b,?"#,
            #"\b(\#(months))\.?\s+\d{1,2}(st|nd|rd|th)?,?(\s+\d{4})?"#,
            #"\b\d{1,2}(st|nd|rd|th)?\s+(\#(months))\.?(\s+\d{4})?"#,
            #"\b\d{1,2}[/.-]\d{1,2}([/.-]\d{2,4})?\b"#,
            #"\b\d{1,2}(:\d{2})?\s*(am|pm|a\.m\.|p\.m\.)"#,
            #"\b(19|20)\d{2}\b"#,
            #"\b(morning|evening)\s+(worship|service)\b"#,
            #"\b(at|on|the|of)\b"#,
        ]
        guard lower.range(of: tokens[0] + "|" + tokens[1] + "|" + tokens[2] + "|" + tokens[3] + "|" + tokens[4],
                          options: .regularExpression) != nil else { return false }
        for token in tokens { s = s.replacingOccurrences(of: token, with: " ", options: .regularExpression) }
        // A single leftover word is usually a clipped weekday ("unday, September 14") or a
        // label ("Easter"); anything longer is a real sentence that mentions a date.
        let leftover = s.split(whereSeparator: { !$0.isLetter }).filter { $0.count > 1 }
        return s.filter(\.isLetter).count < 3 || (leftover.count == 1 && leftover[0].count <= 9)
    }

    private static func isChurchName(_ text: String) -> Bool {
        let words = text.split(separator: " ")
        guard words.count <= 7 else { return false }
        let lower = text.lowercased()
        let places = #"(church|chapel|fellowship|cathedral|parish|ministries|tabernacle|congregation|assembly|basilica|kirk)"#
        if lower.range(of: #"\b\#(places)$"#, options: .regularExpression) != nil { return true }
        if lower.range(of: #"\b\#(places) (of|at|in|on)\b"#, options: .regularExpression) != nil,
           lower.range(of: #"^(the )?(church|chapel)"#, options: .regularExpression) == nil { return true }
        if lower.range(of: #"^(st\.?|saint) [a-z']+('s)?\b.*\b(lutheran|anglican|episcopal|catholic|presbyterian|methodist|baptist|orthodox)"#,
                       options: .regularExpression) != nil { return true }
        return false
    }

    /// Speaker bylines, series labels and the like can still be body lines, but not the title.
    private static func isTitleLike(_ text: String) -> Bool {
        let lower = text.lowercased()
        if lower.range(of: #"^(pastor|pr\.|rev\.?|reverend|dr\.?|father|fr\.|elder|bishop|speaker|preacher|guest speaker)\b"#,
                       options: .regularExpression) != nil { return false }
        if lower.range(of: #"^(week|part|session|message)\s+\d+\b"#, options: .regularExpression) != nil { return false }
        if lower.range(of: #"^(series|sermon series)\s*:"#, options: .regularExpression) != nil { return false }
        if lower.range(of: #"^\d+[.)]\s"#, options: .regularExpression) != nil { return false }
        return text.split(separator: " ").count <= 14
    }

    private static func titleScore(_ line: SlideLine) -> Double {
        // Height dominates; the top half of the slide gets a nudge.
        line.box.height * (1.15 - 0.3 * min(max(line.box.midY, 0), 1))
    }

    // MARK: - Titles

    private static func joinLines(_ parts: [String]) -> String {
        var result = ""
        for part in parts where !part.isEmpty {
            if result.hasSuffix("-"), let first = part.first, first.isLowercase {
                result.removeLast()
                result += part
            } else {
                result += result.isEmpty ? part : " " + part
            }
        }
        return result
    }

    static func cleanTitle(_ raw: String) -> String {
        var s = raw.replacingOccurrences(of: #"(?i)^(sermon|message|title|today'?s message|this week)\s*:\s*"#,
                                         with: "", options: .regularExpression)
        s = s.trimmingCharacters(in: .whitespaces)
        if s.count > 2, let first = s.first, let last = s.last, "\"'".contains(first), "\"'".contains(last) {
            s = String(s.dropFirst().dropLast()).trimmingCharacters(in: .whitespaces)
        }
        let letters = s.filter(\.isLetter)
        if letters.count >= 4, letters.allSatisfy({ $0.isUppercase }) { s = titleCase(s) }
        return s
    }

    private static let minorWords: Set<String> = [
        "a", "an", "and", "as", "at", "but", "by", "for", "in", "nor", "of", "on", "or", "the", "to", "up", "with",
    ]

    static func titleCase(_ text: String) -> String {
        let words = text.lowercased().split(separator: " ", omittingEmptySubsequences: false).map(String.init)
        return words.enumerated().map { index, word in
            if index > 0, index < words.count - 1, minorWords.contains(word) { return word }
            // Roman numerals in series titles stay as they are.
            if word.range(of: #"^(i|ii|iii|iv|v|vi|vii|viii|ix|x)[:.]?$"#, options: .regularExpression) != nil,
               index > 0 { return word.uppercased() }
            guard let first = word.firstIndex(where: \.isLetter) else { return word }
            return word.replacingCharacters(in: first...first, with: word[first].uppercased())
        }.joined(separator: " ")
    }

    // MARK: - Helpers

    private static func foldKey(_ text: String) -> String {
        text.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
            .replacingOccurrences(of: #"^\s*(•|-|\*)\s*"#, with: "", options: .regularExpression)
            .filter { $0.isLetter || $0.isNumber }
    }

    private static func dedupe(_ passages: [Passage]) -> [Passage] {
        var result: [Passage] = []
        for passage in passages where !result.contains(passage) { result.append(passage) }
        return result
    }

    /// Top-to-bottom rows, left-to-right within a row.
    static func readingOrder(_ lines: [SlideLine]) -> [SlideLine] {
        let sorted = lines.sorted { $0.box.midY < $1.box.midY }
        var rows: [[SlideLine]] = []
        for line in sorted {
            if let last = rows.last?.last, abs(last.box.midY - line.box.midY) < min(last.box.height, line.box.height) * 0.5 {
                rows[rows.count - 1].append(line)
            } else {
                rows.append([line])
            }
        }
        return rows.flatMap { $0.sorted { $0.box.x < $1.box.x } }
    }
}
