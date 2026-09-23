import SwiftUI
import UniformTypeIdentifiers
import ScriptureAloneCore

extension UTType {
    nonisolated static let markdownText = UTType("net.daringfireball.markdown") ?? UTType(filenameExtension: "md", conformingTo: .plainText) ?? .plainText
}

extension Note {
    /// The note as plain values, for export and keepsakes.
    var exportValue: KeepsakeNote {
        KeepsakeNote(id: uuid, title: title, body: body, anchors: anchors,
                     createdAt: createdAt, updatedAt: updatedAt, origin: origin)
    }
}

extension Array where Element == KeepsakeNote {
    /// Bible order by first passage; notes without passages last, oldest first.
    var canonicallySorted: [KeepsakeNote] {
        sorted { a, b in
            switch (a.anchors.first, b.anchors.first) {
            case let (x?, y?) where x != y: return x < y
            case (nil, _?): return false
            case (_?, nil): return true
            default: return a.createdAt < b.createdAt
            }
        }
    }
}

extension ReaderModel {
    /// Verse text for export in a given translation. Long ranges (a note on a whole chapter)
    /// are shortened to their opening verses so an export stays about the notes.
    func exportVerseText(translation: String) -> NotesTextExport.VerseText {
        let store = store(for: translation)
        return { range in
            guard let store, let verses = try? store.verses(in: range), !verses.isEmpty else { return nil }
            let limit = 20
            let shown = verses.count > limit ? Array(verses.prefix(5)) : verses
            if shown.count == 1 && verses.count == 1 { return verses[0].text }
            var text = shown.map { "\($0.ref.verse) \($0.text)" }.joined(separator: " ")
            if shown.count < verses.count { text += " …" }
            return text
        }
    }
}

/// A file or folder handed to `.fileExporter`. Holds plain data (Sendable) and builds the
/// file wrapper when SwiftUI asks for it.
nonisolated struct ExportFile: FileDocument {
    static let readableContentTypes: [UTType] = [.pdf, .plainText, .markdownText, .folder, .scriptureLegacy, .data]

    enum Contents: Sendable {
        case file(Data)
        case folder([String: Data])
    }

    let contents: Contents

    init(_ contents: Contents) { self.contents = contents }

    init(configuration: ReadConfiguration) throws {
        if configuration.file.isDirectory {
            contents = .folder((configuration.file.fileWrappers ?? [:]).compactMapValues(\.regularFileContents))
        } else {
            contents = .file(configuration.file.regularFileContents ?? Data())
        }
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { wrapper }

    var wrapper: FileWrapper {
        switch contents {
        case .file(let data):
            FileWrapper(regularFileWithContents: data)
        case .folder(let files):
            FileWrapper(directoryWithFileWrappers: files.mapValues { FileWrapper(regularFileWithContents: $0) })
        }
    }
}

/// Writes an export into a fresh temporary folder, for sharing and saving.
enum ExportStaging {
    static func write(_ file: ExportFile, named name: String) throws -> URL {
        let wrapper = file.wrapper
        let folder = URL.temporaryDirectory.appending(path: "Export-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let url = folder.appending(path: name)
        try wrapper.write(to: url, options: .atomic, originalContentsURL: nil)
        return url
    }

    /// Replaces characters file systems refuse.
    static func safeName(_ name: String) -> String {
        let forbidden = CharacterSet(charactersIn: "/\\:*?\"<>|").union(.controlCharacters).union(.newlines)
        let cleaned = name.unicodeScalars.map { forbidden.contains($0) ? "-" : String($0) }.joined()
            .trimmingCharacters(in: .whitespaces)
        return cleaned.isEmpty ? String(localized: "Export", comment: "Fallback file name for an export with no title") : cleaned
    }
}

/// Identifies a set of notes for `.sheet(item:)`.
struct ExportSelection: Identifiable {
    let id = UUID()
    let notes: [KeepsakeNote]
}
