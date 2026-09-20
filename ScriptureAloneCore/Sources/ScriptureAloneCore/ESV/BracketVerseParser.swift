import Foundation

/// Splits plain scripture text into verses by its bracketed verse numbers.
///
/// Both providers return prose with verses marked `[3]`: Crossway's ESV API and the American
/// Bible Society's API.Bible. One parser, one set of tests, rather than two that drift.
public enum BracketVerseParser {
    /// Splits a provider's plain text into verses.
    ///
    /// Both APIs mark verses as `[3]`, and the first verse of a chapter is marked with the chapter
    /// number instead — "[1:1]" style does not appear, but a chapter opening reads `[1] In the
    /// beginning`. Text before the first marker belongs to the previous verse (a passage that
    /// starts mid-sentence), and is dropped here because we always request whole chapters.
    public static func parse(_ text: String, in chapter: ChapterRef) -> [VerseText] {
        var verses: [VerseText] = []
        var number: Int?
        var buffer = ""

        func flush() {
            guard let n = number else { buffer = ""; return }
            // Collapse the provider's whitespace, including its line breaks.
            //
            // Both APIs return poetry with hard newlines and leading spaces — the shape a terminal
            // would print. Rendering that literally gives ragged breaks mid-verse and stray
            // indents, because the reader lays text out itself and wraps to its own measure and
            // type size. Those line breaks are real structure, but whitespace is the wrong channel
            // to carry it: a poetic line and a wrapped one look the same, and guessing wrong
            // prints scripture in the wrong shape. So the text is normalised to prose here, and
            // structure is left to a format that names its blocks rather than implies them.
            let body = buffer.split(whereSeparator: \.isWhitespace).joined(separator: " ")
            if !body.isEmpty {
                verses.append(VerseText(ref: VerseRef(chapter.book, chapter.chapter, n), text: body, red: []))
            }
            buffer = ""
        }

        var rest = Substring(text)
        while let open = rest.firstIndex(of: "[") {
            guard let close = rest[open...].firstIndex(of: "]") else { break }
            let label = rest[rest.index(after: open)..<close]
            // "[3]" is a verse; "[1:1]" or anything else is left in the text.
            guard let parsed = Int(label.trimmingCharacters(in: .whitespaces)) else {
                buffer += rest[rest.startIndex...close]
                rest = rest[rest.index(after: close)...]
                continue
            }
            buffer += rest[rest.startIndex..<open]
            flush()
            number = parsed
            rest = rest[rest.index(after: close)...]
        }
        buffer += rest
        flush()
        return verses
    }
}
