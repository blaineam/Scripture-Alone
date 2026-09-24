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
    @discardableResult
    public mutating func inferRedLetters(from reference: (VerseRef) -> (text: String, red: [ScalarSpan])?) -> Int {
        guard !verses.values.contains(where: { !$0.red.isEmpty }) else { return 0 }
        var marked = 0
        for (ref, verse) in verses {
            guard let source = reference(ref), !source.red.isEmpty else { continue }
            let spans = RedLetterInference.redSpans(in: verse.text, reference: source.text, referenceRed: source.red)
            guard !spans.isEmpty else { continue }
            setRed(ExtractedBible.merge(spans), for: ref)
            marked += 1
        }
        guard marked > 0 else { return 0 }
        for chapter in chapterOrder {
            updateFragments(in: chapter) { fragment in
                let ref = VerseRef(chapter.book, chapter.chapter, fragment.verse)
                guard let source = reference(ref), !source.red.isEmpty else { return }
                let spans = RedLetterInference.redSpans(in: fragment.text, reference: source.text, referenceRed: source.red)
                guard !spans.isEmpty else { return }
                fragment.spans = ExtractedBible.merge(fragment.spans + spans.map { StyledSpan(start: $0.start, length: $0.length, style: .wordsOfChrist) })
            }
        }
        redLettersInferred = true
        return marked
    }
}
#endif
