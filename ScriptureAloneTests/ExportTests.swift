import Foundation
import Testing
import ScriptureAloneCore
@testable import Scripture_Alone

/// What leaves the app when notes are exported: the file names, the order, and the files written.
struct ExportTests {
    func note(_ title: String, _ anchors: [VerseRange], created: Double) -> KeepsakeNote {
        KeepsakeNote(title: title, body: "", anchors: anchors, createdAt: Date(timeIntervalSince1970: created),
                     updatedAt: Date(timeIntervalSince1970: created))
    }

    @Test func notesExportInBibleOrderWithUnanchoredNotesLastOldestFirst() {
        let notes = [
            note("loose new", [], created: 300),
            note("John", [VerseRange(VerseRef(.john, 3, 16))], created: 100),
            note("loose old", [], created: 200),
            note("Genesis", [VerseRange(VerseRef(.genesis, 1, 1))], created: 400),
            note("John again", [VerseRange(VerseRef(.john, 3, 16))], created: 50),
        ]
        #expect(notes.canonicallySorted.map(\.title) == ["Genesis", "John again", "John", "loose old", "loose new"])
    }

    @Test(arguments: [
        ("Romans 8: No Condemnation", "Romans 8- No Condemnation"),
        ("a/b\\c*d?e\"f<g>h|i", "a-b-c-d-e-f-g-h-i"),
        ("line\nbreak", "line-break"),
        ("  padded  ", "padded"),
    ])
    func fileNamesLoseWhatFileSystemsRefuse(raw: String, expected: String) {
        #expect(ExportStaging.safeName(raw) == expected)
    }

    @Test func anEmptyNameFallsBackToExport() {
        #expect(ExportStaging.safeName("   ") == String(localized: "Export", comment: "Fallback file name for an export with no title"))
    }

    @Test func stagingWritesAFileAndAFolder() throws {
        let file = try ExportStaging.write(ExportFile(.file(Data("hello".utf8))), named: "Notes.md")
        defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        #expect(file.lastPathComponent == "Notes.md")
        #expect(try String(contentsOf: file, encoding: .utf8) == "hello")

        let folder = try ExportStaging.write(ExportFile(.folder(["a.md": Data("A".utf8), "b.md": Data("B".utf8)])),
                                             named: "My Notes")
        defer { try? FileManager.default.removeItem(at: folder.deletingLastPathComponent()) }
        let names = try FileManager.default.contentsOfDirectory(atPath: folder.path).sorted()
        #expect(names == ["a.md", "b.md"])
        #expect(try String(contentsOf: folder.appending(path: "b.md"), encoding: .utf8) == "B")

        // Each export gets a fresh folder, so two exports with one name never collide.
        let again = try ExportStaging.write(ExportFile(.file(Data("x".utf8))), named: "Notes.md")
        defer { try? FileManager.default.removeItem(at: again.deletingLastPathComponent()) }
        #expect(again != file)
        #expect(try String(contentsOf: file, encoding: .utf8) == "hello")
    }

    /// The Markdown folder export: one file per note, named for the note, every name distinct.
    @Test func markdownFolderNamesAreDistinctAndSafe() {
        let notes = [
            note("Same", [VerseRange(VerseRef(.john, 1, 1))], created: 1),
            note("Same", [VerseRange(VerseRef(.john, 1, 2))], created: 2),
            note("What/now?", [], created: 3),
        ]
        let files = NotesTextExport.markdownFiles(notes, options: .init(), verseText: { _ in nil })
        #expect(files.count == 3)
        #expect(Set(files.map(\.name)).count == 3)
        for file in files {
            #expect(file.name.hasSuffix(".md"))
            #expect(!file.name.contains("/"))
        }
    }
}
