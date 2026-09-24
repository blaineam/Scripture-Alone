// Importing is an iPhone, iPad and Mac feature.
#if !os(watchOS)
import Foundation

/// Words of Christ for a translation whose file doesn't mark them, carried over word by word
/// from one that does.
///
/// Each verse is aligned with the same verse in a reference translation (a longest common
/// subsequence of its words). A word lines up with a red word, or sits between two words that
/// do, and is marked red. A verse that barely aligns — another language, a free rendering —
/// gets nothing: guessing wrong would put words in Christ's mouth. This is the rule
/// `Tools/build_bibles.py` uses to give one bundled text another's red letters.
enum RedLetterInference {
    struct Token {
        let range: Range<Int>   // unicode-scalar offsets
        let word: String
    }

    static func tokens(_ text: String) -> [Token] {
        var tokens: [Token] = []
        var start: Int?
        var word = ""
        for (offset, scalar) in text.unicodeScalars.enumerated() {
            let character = Character(scalar)
            if character.isLetter || character.isNumber || ((scalar == "'" || scalar == "\u{2019}") && start != nil) {
                if start == nil { start = offset }
                if character.isLetter || character.isNumber { word.append(character) }
            } else if let begun = start {
                tokens.append(Token(range: begun..<offset, word: word.lowercased()))
                start = nil
                word = ""
            }
        }
        if let begun = start { tokens.append(Token(range: begun..<text.unicodeScalars.count, word: word.lowercased())) }
        return tokens
    }

    /// Each of `target`'s words, with whether the reference word it lines up with is red — nil for
    /// a word that lines up with nothing. `matched` is the share of words that line up.
    static func alignment(of target: String, with reference: String, referenceRed: [ScalarSpan])
        -> (tokens: [Token], red: [Bool?], matched: Double) {
        let theirs = tokens(reference)
        let ours = tokens(target)
        guard !theirs.isEmpty, !ours.isEmpty else { return (ours, Array(repeating: nil, count: ours.count), 0) }
        let red = theirs.map { token in
            referenceRed.contains { $0.start < token.range.upperBound && $0.start + $0.length > token.range.lowerBound }
        }
        let n = ours.count, m = theirs.count
        var table = Array(repeating: Array(repeating: 0, count: m + 1), count: n + 1)
        for i in stride(from: n - 1, through: 0, by: -1) {
            for j in stride(from: m - 1, through: 0, by: -1) {
                table[i][j] = ours[i].word == theirs[j].word ? table[i + 1][j + 1] + 1 : max(table[i + 1][j], table[i][j + 1])
            }
        }
        var result = [Bool?](repeating: nil, count: n)
        var i = 0, j = 0
        while i < n && j < m {
            if ours[i].word == theirs[j].word {
                result[i] = red[j]
                i += 1
                j += 1
            } else if table[i + 1][j] >= table[i][j + 1] {
                i += 1
            } else {
                j += 1
            }
        }
        return (ours, result, Double(result.compactMap { $0 }.count) / Double(n))
    }

    /// Red spans for `target`, from `reference` and its red spans; empty when the two don't align.
    static func redSpans(in target: String, reference: String, referenceRed: [ScalarSpan]) -> [ScalarSpan] {
        guard !referenceRed.isEmpty else { return [] }
        let theirs = tokens(reference)
        let ours = tokens(target)
        guard !theirs.isEmpty, !ours.isEmpty else { return [] }
        let red = theirs.map { token in
            referenceRed.contains { $0.start < token.range.upperBound && $0.start + $0.length > token.range.lowerBound }
        }
        guard red.contains(true) else { return [] }

        // Longest common subsequence of words.
        let n = ours.count, m = theirs.count
        var table = Array(repeating: Array(repeating: 0, count: m + 1), count: n + 1)
        for i in stride(from: n - 1, through: 0, by: -1) {
            for j in stride(from: m - 1, through: 0, by: -1) {
                table[i][j] = ours[i].word == theirs[j].word ? table[i + 1][j + 1] + 1 : max(table[i + 1][j], table[i][j + 1])
            }
        }
        var aligned = [Int?](repeating: nil, count: n)
        var i = 0, j = 0
        while i < n && j < m {
            if ours[i].word == theirs[j].word {
                aligned[i] = j
                i += 1
                j += 1
            } else if table[i + 1][j] >= table[i][j + 1] {
                i += 1
            } else {
                j += 1
            }
        }
        // Too little in common to say which words are whose.
        let matched = aligned.compactMap { $0 }.count
        guard Double(matched) >= Double(n) * 0.5 else { return [] }

        var ourRed = aligned.map { $0.map { red[$0] } }
        for index in 0..<n where ourRed[index] == nil {
            let before = ourRed[..<index].last { $0 != nil } ?? nil
            let after = ourRed[(index + 1)...].first { $0 != nil } ?? nil
            ourRed[index] = before == true && after == true
        }

        var spans: [ScalarSpan] = []
        var runStart: Int?
        var runEnd = 0
        for (index, token) in ours.enumerated() {
            if ourRed[index] == true {
                if runStart == nil { runStart = token.range.lowerBound }
                runEnd = token.range.upperBound
            } else if let start = runStart {
                spans.append(ScalarSpan(start: start, length: runEnd - start))
                runStart = nil
            }
        }
        if let start = runStart { spans.append(ScalarSpan(start: start, length: runEnd - start)) }
        // A closing mark straight after the last red word belongs to what was said ("life.").
        let scalars = Array(target.unicodeScalars)
        return spans.map { span in
            var end = span.start + span.length
            while end < scalars.count, ".,;:!?".unicodeScalars.contains(scalars[end]) { end += 1 }
            return ScalarSpan(start: span.start, length: end - span.start)
        }
    }

    // MARK: Quotations

    /// One quotation in the target's own punctuation: the stretch of each verse it covers, marks
    /// included, in reading order. A quotation can run for chapters (the Sermon on the Mount).
    struct Quotation {
        var parts: [(verse: VerseRef, range: Range<Int>)] = []
        /// Speech quoted inside it, one level down — Paul telling what the Lord said to him.
        var inner: [Quotation] = []
    }

    /// The target's top-level quotations. English nests speech by alternating marks — “He said,
    /// ‘They answered, “…”’” — so the marks are followed as a stack: a single mark opens a level
    /// inside a quotation, a double mark inside a single one opens a deeper level, and only the
    /// double mark that closes the outermost level ends the quotation. A paragraph that goes on
    /// speaking reopens the quotation without closing it, so an opening double mark at the start
    /// of a verse continues it (and ends any nesting left open); one mid-verse, with a quotation
    /// still open, means a closing mark was missed, and starts a new quotation. A ’ between two letters is an apostrophe. A quotation still open at a
    /// chapter whose first words don't reopen it is closed there, so one missed mark can't run
    /// away with the book.
    static func quotations(in verses: [(ref: VerseRef, text: String)]) -> [Quotation] {
        enum Mark { case double, single }
        var result: [Quotation] = []
        var open: Quotation?
        var stack: [Mark] = []
        var partStart: Int?
        var nested: Quotation?
        var nestedStart: Int?
        var chapter: ChapterRef?
        func close(_ ref: VerseRef, at end: Int) {
            guard var quotation = open, let start = partStart else { return }
            quotation.parts.append((ref, start..<end))
            if let inner = nested, !inner.parts.isEmpty { quotation.inner.append(inner) }
            result.append(quotation)
            open = nil
            partStart = nil
            nested = nil
            nestedStart = nil
            stack = []
        }
        // A level opened or closed one down from the outermost: the edges of nested speech.
        func nestedOpened(at offset: Int) {
            if stack.count == 2 { nested = Quotation(); nestedStart = offset }
        }
        func nestedClosed(_ ref: VerseRef, at end: Int) {
            guard stack.count == 1, var inner = nested, let start = nestedStart else { return }
            inner.parts.append((ref, start..<end))
            open?.inner.append(inner)
            nested = nil
            nestedStart = nil
        }
        for (ref, text) in verses {
            let scalars = Array(text.unicodeScalars)
            if ref.chapterKey != chapter {
                chapter = ref.chapterKey
                let first = scalars.first { !CharacterSet.whitespaces.contains($0) }
                if let quotation = open, first != "\u{201C}", first != "\"" {
                    result.append(quotation)
                    open = nil
                    stack = []
                }
            }
            partStart = open == nil ? nil : 0
            nestedStart = nested == nil ? nil : 0
            for (offset, scalar) in scalars.enumerated() {
                let letterBefore = offset > 0 && CharacterSet.letters.contains(scalars[offset - 1])
                let letterAfter = offset + 1 < scalars.count && CharacterSet.letters.contains(scalars[offset + 1])
                switch scalar {
                case "\u{201C}":
                    let startsVerse = scalars[..<offset].allSatisfy { CharacterSet.whitespaces.contains($0) }
                    if open == nil {
                        open = Quotation()
                        partStart = offset
                        stack = [.double]
                    } else if startsVerse {
                        // A new paragraph going on speaking: whatever nesting was left open ends.
                        if let inner = nested, !inner.parts.isEmpty { open?.inner.append(inner) }
                        nested = nil
                        nestedStart = nil
                        stack = [.double]
                    } else if stack.last == .single {
                        stack.append(.double)
                        nestedOpened(at: offset)
                    } else {
                        // Mid-verse, with a quotation still open: a closing mark was missed, so
                        // the old quotation ends here and a new one begins.
                        close(ref, at: offset)
                        open = Quotation()
                        partStart = offset
                        stack = [.double]
                    }
                case "\u{201D}":
                    guard open != nil else { break }
                    while stack.last == .single {
                        stack.removeLast()
                        nestedClosed(ref, at: offset)
                    }
                    if !stack.isEmpty { stack.removeLast() }
                    nestedClosed(ref, at: offset + 1)
                    if stack.isEmpty { close(ref, at: offset + 1) }
                case "\u{2018}":
                    if open != nil {
                        stack.append(.single)
                        nestedOpened(at: offset)
                    }
                case "\u{2019}":
                    if open != nil, stack.last == .single, !(letterBefore && letterAfter) {
                        stack.removeLast()
                        nestedClosed(ref, at: offset + 1)
                    }
                case "\"":
                    if open != nil, stack.count <= 1 {
                        close(ref, at: offset + 1)
                    } else if open == nil {
                        open = Quotation()
                        partStart = offset
                        stack = [.double]
                    }
                default:
                    break
                }
            }
            if var inner = nested, let start = nestedStart, start < scalars.count {
                inner.parts.append((ref, start..<scalars.count))
                nested = inner
            }
            if var quotation = open, let start = partStart, start < scalars.count {
                quotation.parts.append((ref, start..<scalars.count))
                open = quotation
            }
        }
        if let open, !open.parts.isEmpty { result.append(open) }
        return result
    }

    /// Whether a quotation is Christ speaking: a vote of its words against the reference. A word
    /// that lines up counts as its reference word does, however loosely the rest of the verse is
    /// rendered; a word that lines up with nothing counts as the reference verse leans, when it
    /// leans clearly one way.
    static func isSpokenByChrist(_ quotation: Quotation, evidence: (VerseRef) -> VerseEvidence?) -> Bool {
        var red = 0, black = 0
        var quoteLeans: [Bool] = []
        for part in quotation.parts {
            guard let verse = evidence(part.verse) else { continue }
            if let lean = verse.quotationLean { quoteLeans.append(lean) }
            for (index, token) in verse.tokens.enumerated() where part.range.contains(token.range.lowerBound) {
                let vote = verse.red[index] ?? verse.wordLean(token.word) ?? verse.lean
                if vote == true { red += 1 } else if vote == false { black += 1 }
            }
        }
        // Nothing lines up at all ("Rise, and have no fear" / "Get up… Do not be afraid"): the
        // reference's own quotations in the same verses answer.
        if red == 0 && black == 0 { return !quoteLeans.isEmpty && !quoteLeans.contains(false) }
        return red >= 1 && Double(red) >= Double(red + black) * 0.6
    }

    /// What the reference says about one verse, for the vote.
    struct VerseEvidence {
        /// The target's words, and whether each lines up with a red reference word (nil: with none).
        let tokens: [Token]
        let red: [Bool?]
        /// The share of the target's words that line up with the reference.
        let matched: Double
        /// The reference verse is (almost) all red, or not red at all; nil when mixed.
        let lean: Bool?
        /// The reference verse's quotations are all red (true), none red (false), or mixed / none (nil).
        let quotationLean: Bool?
        let redWords: Set<String>
        let blackWords: Set<String>

        /// A word that didn't line up, judged by where it appears in the reference verse: only among
        /// its red words, or only among its others.
        func wordLean(_ word: String) -> Bool? {
            switch (redWords.contains(word), blackWords.contains(word)) {
            case (true, false): true
            case (false, true): false
            default: nil
            }
        }
    }

    static func evidence(for target: String, reference: String, referenceRed: [ScalarSpan]) -> VerseEvidence {
        let aligned = alignment(of: target, with: reference, referenceRed: referenceRed)
        let isRed = { (range: Range<Int>) in
            referenceRed.contains { $0.start < range.upperBound && $0.start + $0.length > range.lowerBound }
        }
        let words = tokens(reference)
        var redWords = Set<String>(), blackWords = Set<String>()
        for token in words { if isRed(token.range) { redWords.insert(token.word) } else { blackWords.insert(token.word) } }
        let share = words.isEmpty ? 0 : Double(words.filter { isRed($0.range) }.count) / Double(words.count)
        // The reference verse's own quotations, each judged by whether its words are red.
        let quoted = quotations(in: [(VerseRef(.genesis, 1, 1), reference)]).flatMap(\.parts).map { part in
            words.filter { part.range.contains($0.range.lowerBound) }
        }.filter { !$0.isEmpty }
        let verdicts = quoted.map { tokens in tokens.filter { isRed($0.range) }.count * 2 > tokens.count }
        let quotationLean: Bool? = verdicts.isEmpty ? nil : (verdicts.allSatisfy { $0 } ? true : (verdicts.allSatisfy { !$0 } ? false : nil))
        return VerseEvidence(tokens: aligned.tokens, red: aligned.red, matched: aligned.matched,
                             lean: share >= 0.9 ? true : (share == 0 ? false : nil),
                             quotationLean: quotationLean, redWords: redWords, blackWords: blackWords)
    }

    /// UTF-16 ranges (as `BibleStore` gives them) as unicode-scalar spans in the same text.
    static func scalarSpans(_ ranges: [NSRange], in text: String) -> [ScalarSpan] {
        var scalarAtUTF16: [Int: Int] = [:]
        var utf16 = 0
        for (index, scalar) in text.unicodeScalars.enumerated() {
            scalarAtUTF16[utf16] = index
            utf16 += scalar.utf16.count
        }
        scalarAtUTF16[utf16] = text.unicodeScalars.count
        return ranges.compactMap { range in
            guard let start = scalarAtUTF16[range.location], let end = scalarAtUTF16[range.location + range.length] else { return nil }
            return ScalarSpan(start: start, length: end - start)
        }
    }
}

extension ExtractedBible {
    /// Gives a translation with no red letters of its own the words of Christ from `reference`
    /// (text and red spans of the same verse in a translation that marks them). Returns how many
    /// verses were marked; a file that marks any red of its own is left exactly as it is.
    ///
    /// A book that prints its speech in quotation marks is read by its quotations: each is Christ
    /// speaking or not as a whole, decided by how its words line up with the reference — so the
    /// narration around a saying stays black however differently it is worded, and every word of
    /// a long discourse turns red even where the two translations part company. A book without
    /// quotation marks is matched word by word (`RedLetterInference.redSpans`).
    @discardableResult
    public mutating func inferRedLetters(from reference: (VerseRef) -> (text: String, red: [ScalarSpan])?) -> Int {
        guard !verses.values.contains(where: { !$0.red.isEmpty }) else { return 0 }
        let texts = verses.mapValues(\.text)
        var spansByVerse: [VerseRef: [ScalarSpan]] = [:]
        for book in books {
            let ordered = verses.values.filter { $0.ref.book == book }.sorted { $0.ref.key < $1.ref.key }
            // English-style marks only: „…“ and «…» close with what opens here, and a book in
            // another language won't line up with the reference well enough to vote anyway.
            let marks = ordered.flatMap { $0.text.unicodeScalars }
            let usesQuotes = marks.contains { $0 == "\u{201C}" || $0 == "\"" }
                && !marks.contains { $0 == "\u{201E}" || $0 == "\u{00AB}" || $0 == "\u{00BB}" }
            guard usesQuotes else {
                for verse in ordered {
                    guard let source = reference(verse.ref), !source.red.isEmpty else { continue }
                    let spans = RedLetterInference.redSpans(in: verse.text, reference: source.text, referenceRed: source.red)
                    if !spans.isEmpty { spansByVerse[verse.ref] = spans }
                }
                continue
            }
            var evidence: [VerseRef: RedLetterInference.VerseEvidence] = [:]
            func align(_ ref: VerseRef) -> RedLetterInference.VerseEvidence? {
                if let cached = evidence[ref] { return cached }
                guard let text = texts[ref], let source = reference(ref) else { return nil }
                let found = RedLetterInference.evidence(for: text, reference: source.text, referenceRed: source.red)
                evidence[ref] = found
                return found
            }
            // The same language as the reference, or near enough to vote: on average a third of
            // the words in its red verses line up. Otherwise nothing here is judged at all.
            let judged = ordered.compactMap { verse -> Double? in
                guard let source = reference(verse.ref), !source.red.isEmpty else { return nil }
                return align(verse.ref)?.matched
            }
            guard !judged.isEmpty, judged.reduce(0, +) / Double(judged.count) >= 0.33 else { continue }
            let quotations = RedLetterInference.quotations(in: ordered.map { ($0.ref, $0.text) })
            for quotation in quotations {
                // Someone else speaking may still quote Him ("the Lord said to me, ‘…’").
                let spoken = RedLetterInference.isSpokenByChrist(quotation, evidence: align)
                    ? [quotation] : quotation.inner.filter { RedLetterInference.isSpokenByChrist($0, evidence: align) }
                for part in spoken.flatMap(\.parts) {
                    spansByVerse[part.verse, default: []].append(ScalarSpan(start: part.range.lowerBound, length: part.range.count))
                }
            }
        }
        var marked = 0
        for (ref, spans) in spansByVerse {
            setRed(ExtractedBible.merge(spans), for: ref)
            marked += 1
        }
        guard marked > 0 else { return 0 }
        // The paragraphs the reader draws carry the same spans, found in each fragment's words.
        for chapter in chapterOrder {
            var cursor: [Int: Int] = [:]
            updateFragments(in: chapter) { fragment in
                let ref = VerseRef(chapter.book, chapter.chapter, fragment.verse)
                guard let spans = spansByVerse[ref], let text = texts[ref] else { return }
                let whole = Array(text.unicodeScalars)
                let own = Array(fragment.text.unicodeScalars)
                let lead = own.prefix { CharacterSet.whitespacesAndNewlines.contains($0) }.count
                let body = Array(own[lead...].reversed().drop { CharacterSet.whitespacesAndNewlines.contains($0) }.reversed())
                guard !body.isEmpty, let found = Self.find(body, in: whole, from: cursor[fragment.verse] ?? 0) else { return }
                cursor[fragment.verse] = found + body.count
                let local = spans.compactMap { span -> StyledSpan? in
                    let start = max(span.start, found), end = min(span.start + span.length, found + body.count)
                    guard end > start else { return nil }
                    return StyledSpan(start: start - found + lead, length: end - start, style: .wordsOfChrist)
                }
                fragment.spans = ExtractedBible.merge(fragment.spans + local)
            }
        }
        redLettersInferred = true
        return marked
    }

    private static func find(_ needle: [Unicode.Scalar], in haystack: [Unicode.Scalar], from start: Int) -> Int? {
        guard needle.count <= haystack.count, start <= haystack.count - needle.count else { return nil }
        for offset in start...(haystack.count - needle.count) where haystack[offset] == needle[0] {
            if Array(haystack[offset..<(offset + needle.count)]) == needle { return offset }
        }
        return nil
    }
}
#endif
