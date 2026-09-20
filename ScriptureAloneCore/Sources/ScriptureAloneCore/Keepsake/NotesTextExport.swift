import Foundation

/// Markdown and plain-text renderings of notes, for export. Pure, so it's testable here; the
/// app supplies verse text through `verseText` and does the PDF itself.
public enum NotesTextExport {
    public struct Options: Sendable {
        /// Heading for a multi-note document ("Notes", "Dad’s Notes").
        public var title: String
        /// Translation abbreviation shown after quoted passages ("ASV"), or nil to omit verse text.
        public var translation: String?
        /// The publisher's copyright line, when the quoted translation requires one. It is
        /// written at the end of the document: a file of quotations that leaves the device
        /// without its attribution is the thing publishers' permissions actually forbid.
        public var notice: String?

        public init(title: String = "Notes", translation: String? = nil, notice: String? = nil) {
            self.title = title
            self.translation = translation
            self.notice = notice
        }
    }

    public typealias VerseText = (VerseRange) -> String?

    /// The publisher's line, at the foot of an export that quotes their text.
    static func noticeBlock(_ options: Options, markdown: Bool) -> String {
        guard let notice = options.notice?.trimmingCharacters(in: .whitespacesAndNewlines),
              !notice.isEmpty else { return "" }
        return markdown ? "\n---\n\n\(notice)\n" : "\n\(String(repeating: "—", count: 24))\n\(notice)\n"
    }


    // MARK: Markdown

    /// One Markdown document holding every note.
    public static func markdown(_ notes: [KeepsakeNote], options: Options, verseText: VerseText) -> String {
        if notes.count == 1 { return markdown(note: notes[0], headingLevel: 1, options: options, verseText: verseText) }
        var parts = ["# \(options.title)", exportedLine(count: notes.count, options: options)]
        for note in notes {
            parts.append("---")
            parts.append(markdown(note: note, headingLevel: 2, options: options, verseText: verseText))
        }
        return parts.joined(separator: "\n\n") + "\n" + noticeBlock(options, markdown: true)
    }

    /// One Markdown document per note, with unique, file-system-safe names.
    public static func markdownFiles(_ notes: [KeepsakeNote], options: Options, verseText: VerseText) -> [(name: String, contents: String)] {
        var used: Set<String> = []
        return notes.map { note in
            let base = fileName(for: note)
            var name = "\(base).md"
            var n = 2
            while used.contains(name.lowercased()) {
                name = "\(base) \(n).md"
                n += 1
            }
            used.insert(name.lowercased())
            return (name, markdown(note: note, headingLevel: 1, options: options, verseText: verseText) + "\n")
        }
    }

    public static func markdown(note: KeepsakeNote, headingLevel: Int, options: Options, verseText: VerseText) -> String {
        var lines = [String(repeating: "#", count: headingLevel) + " " + note.displayTitle]
        if !note.anchors.isEmpty {
            lines.append("")
            lines.append("**\(note.anchorSummary)**")
        }
        if options.translation != nil {
            for range in note.anchors {
                guard let text = verseText(range), !text.isEmpty else { continue }
                lines.append("")
                for line in text.split(separator: "\n", omittingEmptySubsequences: false) {
                    lines.append("> " + line)
                }
                lines.append("> — \(range.display)\(options.translation.map { " (\($0))" } ?? "")")
            }
        }
        let body = note.body.trimmingCharacters(in: .whitespacesAndNewlines)
        if !body.isEmpty {
            lines.append("")
            lines.append(body)
        }
        lines.append("")
        lines.append("*\(dateLine(note))*")
        return lines.joined(separator: "\n")
    }

    // MARK: Plain text

    public static func plainText(_ notes: [KeepsakeNote], options: Options, verseText: VerseText) -> String {
        var blocks: [String] = []
        if notes.count > 1 {
            blocks.append(options.title.uppercased() + "\n" + exportedLine(count: notes.count, options: options))
        }
        for note in notes {
            var lines = [note.displayTitle]
            if !note.anchors.isEmpty { lines.append(note.anchorSummary) }
            if options.translation != nil {
                for range in note.anchors {
                    guard let text = verseText(range), !text.isEmpty else { continue }
                    lines.append("")
                    lines.append("    “" + text.replacingOccurrences(of: "\n", with: "\n    ") + "”")
                    lines.append("    — \(range.display)\(options.translation.map { " (\($0))" } ?? "")")
                }
            }
            let body = note.body.trimmingCharacters(in: .whitespacesAndNewlines)
            if !body.isEmpty {
                lines.append("")
                lines.append(body)
            }
            lines.append("")
            lines.append(dateLine(note))
            blocks.append(lines.joined(separator: "\n"))
        }
        return blocks.joined(separator: "\n\n" + String(repeating: "—", count: 24) + "\n\n") + "\n"
            + noticeBlock(options, markdown: false)
    }

    // MARK: Helpers

    public static func dateLine(_ note: KeepsakeNote) -> String {
        let created = note.createdAt.formatted(date: .long, time: .omitted)
        let edited = note.updatedAt.formatted(date: .long, time: .omitted)
        return created == edited ? "Written \(created)" : "Written \(created) · Edited \(edited)"
    }

    static func exportedLine(count: Int, options: Options) -> String {
        let noun = count == 1 ? "note" : "notes"
        var line = "\(count) \(noun), exported \(Date.now.formatted(date: .long, time: .omitted))"
        if let translation = options.translation { line += " · Scripture quoted from the \(translation)" }
        return line
    }

    /// "2026-09-18 Romans 8 sermon" — dated so a folder sorts by when notes were written.
    public static func fileName(for note: KeepsakeNote) -> String {
        let c = Calendar.current.dateComponents([.year, .month, .day], from: note.createdAt)
        let day = String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
        let forbidden = CharacterSet(charactersIn: "/\\:*?\"<>|\n\r\t").union(.controlCharacters)
        var title = note.displayTitle.unicodeScalars.map { forbidden.contains($0) ? "-" : String($0) }.joined()
        title = title.trimmingCharacters(in: .whitespacesAndNewlines.union(CharacterSet(charactersIn: ".-")))
        if title.count > 80 { title = String(title.prefix(80)).trimmingCharacters(in: .whitespaces) }
        return title.isEmpty ? day : "\(day) \(title)"
    }
}
