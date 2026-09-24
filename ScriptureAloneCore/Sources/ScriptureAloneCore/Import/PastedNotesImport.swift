#if !os(watchOS)
import Foundation

/// Reads notes out of text a reader pasted or a spreadsheet they exported, from any app at all.
///
/// **Why this is not a list of supported apps.** Olive Tree exports a CSV; Logos exports documents;
/// YouVersion exports nothing and tells people to copy their notes by hand. Writing a parser per
/// app would mean guessing at each vendor's columns from documentation rather than files — and a
/// reference parsed wrong attaches somebody's note to the wrong verse, silently. So this reads by
/// *structure*: a reference, and some text that belongs to it. That covers the exports this app has
/// never seen, and the next one too.
///
/// Two shapes are understood, told apart by looking rather than by asking:
///
/// - **A table** — CSV or tab-separated, with or without a header. The column holding references is
///   found by trying to parse them, not by its name; the note is whichever remaining column has the
///   most text; a column of colour names or hex is used for highlights when one is there.
/// - **Blocks of text** — a reference on its own line or at the start of one, then the note, with
///   blank lines between entries. This is what copying notes out of an app by hand produces.
///
/// A line that names no verse is never guessed at. It is returned in `unresolved` so a reader can
/// be shown exactly what did not come across.
public enum PastedNotesImport {

    /// - Parameter verseCount: how many verses a chapter has, so a highlighted range that crosses a
    ///   chapter break is walked through real verses (`LifeBibleImport.verses(in:verseCount:)`).
    ///   Without it, only the verses a reference names are highlighted.
    public static func parse(_ raw: String,
                             verseCount: ((ChapterRef) -> Int)? = nil) throws -> ImportedNotes {
        let text = raw.replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
            .replacingOccurrences(of: "\u{00A0}", with: " ")
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw NoteImportError.nothingRecognised
        }

        var result = looksLikeATable(text) ? readTable(text, verseCount: verseCount) : readBlocks(text)
        // Falling back rather than failing: a table whose references are in none of its columns is
        // better read as prose than reported as empty.
        if result.isEmpty, looksLikeATable(text) { result = readBlocks(text) }
        guard !result.isEmpty else { throw NoteImportError.nothingRecognised }
        return result
    }

    // MARK: Telling a table from prose

    /// A table has a separator appearing the same number of times on most rows. Prose does not —
    /// and a note with commas in it is prose, which is why this counts consistency rather than
    /// presence.
    ///
    /// Counted **outside quotes**, which is the whole subtlety: a CSV whose notes contain commas
    /// has a different raw comma count on every line, and counting naively decides it is prose and
    /// reads the entire export as one run-on block.
    static func looksLikeATable(_ text: String) -> Bool {
        for separator in [",", "\t", ";"].map({ Character($0) }) {
            let counts = rows(in: text, separator: separator).prefix(30)
                .map { separatorCount(in: $0, separator: separator) }
            guard counts.count >= 2, let first = counts.first, first > 0 else { continue }
            let agreeing = counts.filter { $0 == first }.count
            if Double(agreeing) / Double(counts.count) >= 0.8 { return true }
        }
        return false
    }

    /// Separators at the top level only — those inside a quoted field belong to the text.
    static func separatorCount(in line: String, separator: Character) -> Int {
        var count = 0
        var quoted = false
        for character in line {
            if character == "\"" { quoted.toggle() } else if character == separator, !quoted { count += 1 }
        }
        return count
    }

    /// Splits text into rows, keeping a quoted field together even when it contains newlines.
    /// A note someone wrote across three lines is one cell, not three rows.
    static func rows(in text: String, separator: Character) -> [String] {
        var out: [String] = []
        var current = ""
        var quoted = false
        for character in text {
            if character == "\"" { quoted.toggle() }
            if character == "\n", !quoted {
                if !current.trimmingCharacters(in: .whitespaces).isEmpty { out.append(current) }
                current = ""
            } else {
                current.append(character)
            }
        }
        if !current.trimmingCharacters(in: .whitespaces).isEmpty { out.append(current) }
        return out
    }

    // MARK: Tables

    static func readTable(_ text: String, verseCount: ((ChapterRef) -> Int)? = nil) -> ImportedNotes {
        var result = ImportedNotes()
        let separator = bestSeparator(text)
        let rows = rows(in: text, separator: separator)
            .map { fields(in: $0, separator: separator) }
            .filter { row in row.contains { !$0.trimmingCharacters(in: .whitespaces).isEmpty } }
        guard !rows.isEmpty else { return result }

        // Which column holds references, decided by parsing every column on every row and taking
        // whichever succeeds most often. A header row simply fails to parse and costs one row.
        let width = rows.map(\.count).max() ?? 0
        var successes = [Int](repeating: 0, count: width)
        for row in rows {
            for (index, field) in row.enumerated() where reference(in: field) != nil {
                successes[index] += 1
            }
        }
        guard let referenceColumn = successes.indices.max(by: { successes[$0] < successes[$1] }),
              successes[referenceColumn] > 0 else { return result }

        let colourColumn = (0..<width).first { column in
            column != referenceColumn
                && rows.filter { column < $0.count && colour(in: $0[column]) != nil }.count > rows.count / 2
        }
        // The note is the wordiest remaining column, measured over the whole table so one long
        // cell cannot decide it.
        let textColumn = (0..<width)
            .filter { $0 != referenceColumn && $0 != colourColumn }
            .max { a, b in totalLength(of: rows, column: a) < totalLength(of: rows, column: b) }

        for (index, row) in rows.enumerated() {
            guard referenceColumn < row.count,
                  let found = reference(in: row[referenceColumn]), let range = found.range else {
                let line = row.joined(separator: " ").trimmingCharacters(in: .whitespaces)
                // A header row is not a failure worth reporting to anybody.
                if !line.isEmpty, index > 0 { result.unresolved.append(line) }
                continue
            }
            let body = textColumn.flatMap { $0 < row.count ? row[$0] : nil }?
                .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let swatch = colourColumn.flatMap { $0 < row.count ? colour(in: row[$0]) : nil }

            if body.isEmpty {
                // A row that names a verse and says nothing about it is a highlight when it has a
                // colour, and a saved verse when it does not.
                if let swatch {
                    appendHighlights(range, colour: swatch, translation: found.translation,
                                     verseCount: verseCount, to: &result)
                } else {
                    result.saved.append(range)
                }
            } else {
                result.verseNotes.append(ImportedNotes.Note(range: range, title: found.display,
                                                            body: body, translation: found.translation))
                if let swatch {
                    appendHighlights(range, colour: swatch, translation: found.translation,
                                     verseCount: verseCount, to: &result)
                }
            }
        }
        return result
    }

    static func totalLength(of rows: [[String]], column: Int) -> Int {
        rows.reduce(0) { $0 + (column < $1.count ? $1[column].count : 0) }
    }

    static func bestSeparator(_ text: String) -> Character {
        var best: (Character, Int) = (",", 0)
        for separator in [",", "\t", ";"] as [Character] {
            let counts = rows(in: text, separator: separator).prefix(30)
                .map { separatorCount(in: $0, separator: separator) }
            guard let first = counts.first, first > 0 else { continue }
            let agreeing = counts.filter { $0 == first }.count
            if agreeing > best.1 { best = (separator, agreeing) }
        }
        return best.0
    }

    /// Splits one row, honouring quotes and doubled quotes — a note containing a comma is the
    /// ordinary case, not an edge one.
    static func fields(in line: String, separator: Character) -> [String] {
        var out: [String] = []
        var current = ""
        var quoted = false
        var index = line.startIndex
        while index < line.endIndex {
            let character = line[index]
            if character == "\"" {
                let next = line.index(after: index)
                if quoted, next < line.endIndex, line[next] == "\"" {
                    current.append("\"")
                    index = next
                } else {
                    quoted.toggle()
                }
            } else if character == separator, !quoted {
                out.append(current)
                current = ""
            } else {
                current.append(character)
            }
            index = line.index(after: index)
        }
        out.append(current)
        return out.map { $0.trimmingCharacters(in: .whitespaces) }
    }

    // MARK: Blocks of prose

    static func readBlocks(_ text: String) -> ImportedNotes {
        var result = ImportedNotes()
        // Entries are separated by blank lines. Where there are none, every line that starts with a
        // reference begins a new entry — which is what a hand-copied list looks like.
        var blocks = text.components(separatedBy: "\n\n").filter {
            !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
        if blocks.count <= 1 { blocks = splitOnReferenceLines(text) }

        for block in blocks {
            let lines = block.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
            guard let head = lines.first(where: { !$0.trimmingCharacters(in: .whitespaces).isEmpty })
            else { continue }

            // The reference may be the whole first line, or may lead it: "John 3:16 — God so loved".
            let (found, remainderOfHead) = splitLeadingReference(head)
            guard let found, let range = found.range else {
                result.unresolved.append(block.trimmingCharacters(in: .whitespacesAndNewlines)
                    .split(separator: "\n").first.map(String.init) ?? block)
                continue
            }
            let rest = lines.drop { $0 != head }.dropFirst().joined(separator: "\n")
            let body = ([remainderOfHead, rest].joined(separator: "\n"))
                .trimmingCharacters(in: .whitespacesAndNewlines)

            if body.isEmpty {
                result.saved.append(range)
            } else {
                result.verseNotes.append(ImportedNotes.Note(range: range, title: found.display,
                                                            body: body, translation: found.translation))
            }
        }
        return result
    }

    static func splitOnReferenceLines(_ text: String) -> [String] {
        var blocks: [String] = []
        var current: [String] = []
        for line in text.split(separator: "\n", omittingEmptySubsequences: false).map(String.init) {
            if splitLeadingReference(line).reference != nil, !current.isEmpty {
                blocks.append(current.joined(separator: "\n"))
                current = []
            }
            current.append(line)
        }
        if !current.isEmpty { blocks.append(current.joined(separator: "\n")) }
        return blocks
    }

    /// The longest leading run of words that still parses as a reference, and whatever follows it.
    /// Longest-first so "John 3:16-17" is not read as "John 3:16".
    static func splitLeadingReference(_ line: String) -> (reference: Reference?, rest: String) {
        let trimmed = line.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return (nil, "") }
        let words = trimmed.split(separator: " ", omittingEmptySubsequences: true).map(String.init)
        for count in stride(from: min(words.count, 6), through: 1, by: -1) {
            let head = words.prefix(count).joined(separator: " ")
            // Punctuation a person writes between a reference and their note.
            let cleaned = head.trimmingCharacters(in: CharacterSet(charactersIn: " —-–:·|"))
            guard let found = reference(in: cleaned) else { continue }
            let rest = words.dropFirst(count).joined(separator: " ")
                .trimmingCharacters(in: CharacterSet(charactersIn: " —-–:·|"))
            return (found, rest)
        }
        return (nil, trimmed)
    }

    // MARK: Shared pieces

    typealias Reference = LifeBibleImport.Reference

    /// The same reference reader the Life Bible import uses, so both sources treat "1 Chronicles
    /// 29:14 ABC" identically and there is one place to fix when one of them is wrong.
    static func reference(in raw: String) -> Reference? {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        // A bare number is not a reference, however willing a parser might be to read it as one.
        guard text.count >= 3, text.contains(where: \.isLetter) else { return nil }
        return LifeBibleImport.reference(in: text)
    }

    /// A colour cell, as a name or a hex value. Anything else is not a colour.
    ///
    /// Hex is accepted only where it cannot be a word: `#rrggbb`, or six bare hex digits with at
    /// least one digit among them (`b3e487`). Six bare *letters* are refused — "facade", "decade",
    /// "beaded" and "accede" are all valid hex and all ordinary English, and a column of notes made
    /// of such words must not be taken for a column of colours.
    static func colour(in raw: String) -> String? {
        let text = raw.trimmingCharacters(in: .whitespaces).lowercased()
        guard !text.isEmpty else { return nil }
        let prefixed = text.hasPrefix("#")
        let digits = prefixed ? String(text.dropFirst()) : text
        // ASCII only: `Character.isHexDigit` also admits full-width forms no hex parser reads.
        if digits.count == 6, digits.allSatisfy({ "0123456789abcdef".contains($0) }),
           prefixed || digits.contains(where: { "0123456789".contains($0) }) {
            return LifeBibleImport.nearestColor(toHex: digits)
        }
        // Names people and apps actually use, mapped onto the five this app has.
        let names: [String: String] = [
            "yellow": "yellow", "gold": "yellow", "orange": "yellow", "amber": "yellow",
            "green": "green", "olive": "green", "lime": "green", "teal": "green",
            "blue": "blue", "cyan": "blue", "aqua": "blue", "navy": "blue",
            "pink": "pink", "red": "pink", "rose": "pink", "magenta": "pink",
            "purple": "purple", "violet": "purple", "lavender": "purple", "grey": "purple",
            "gray": "purple",
        ]
        return names[text]
    }

    static func appendHighlights(_ range: VerseRange, colour: String, translation: String?,
                                 verseCount: ((ChapterRef) -> Int)? = nil,
                                 to result: inout ImportedNotes) {
        for verse in LifeBibleImport.verses(in: range, verseCount: verseCount) {
            result.highlights.append(ImportedNotes.Highlight(verse: verse, color: colour,
                                                             translation: translation))
        }
    }
}
#endif
