import Foundation
import Testing
import ScriptureAloneCore
@testable import Scripture_Alone

/// Keepsake Bibles someone has been given: kept as files of their own, apart from the reader's notes,
/// one per Bible however many times it is re-sent, and shown in the reader with the giver's marks.
@MainActor
struct LegacyTests {
    let directory = URL.temporaryDirectory.appending(path: "LegacyTests-\(UUID().uuidString)", directoryHint: .isDirectory)

    func note(_ title: String, _ body: String, _ anchors: [VerseRange]) -> KeepsakeNote {
        let date = Date(timeIntervalSince1970: 1_750_000_000)
        return KeepsakeNote(title: title, body: body, anchors: anchors, createdAt: date, updatedAt: date)
    }

    func keepsake(owner: String, bibleID: UUID = UUID(), created: Double = 1_700_000_000,
                  highlights: [KeepsakeHighlight] = [], notes: [KeepsakeNote] = []) -> Keepsake {
        var manifest = KeepsakeManifest(bibleID: bibleID, ownerName: owner, createdAt: Date(timeIntervalSince1970: created))
        manifest.exportID = UUID()
        var keepsake = Keepsake(manifest: manifest, highlights: highlights, notes: notes)
        keepsake.refreshSummary()
        return keepsake
    }

    @Test func keepsakesArePlainFilesThatSurviveARelaunch() throws {
        defer { try? FileManager.default.removeItem(at: directory) }
        let library = LegacyLibrary(directory: directory)
        #expect(library.entries.isEmpty)
        let dad = keepsake(owner: "Dad", notes: [note("Psalm 23", "Read at Grandma's", [VerseRange(VerseRef(.psalms, 23, 1))])])
        #expect(try library.add(dad).isAdded)
        try library.add(keepsake(owner: "Anna"))

        // A new library over the same folder — the next launch — finds both, sorted by title.
        let reopened = LegacyLibrary(directory: directory)
        #expect(reopened.entries.map(\.title) == [keepsake(owner: "Anna").manifest.displayTitle,
                                                  dad.manifest.displayTitle])
        #expect(reopened.keepsake(dad.id)?.notes.first?.body == "Read at Grandma's")
        #expect(FileManager.default.fileExists(atPath: reopened.fileURL(for: dad.id).path))
    }

    @Test func aNewerExportOfTheSameBibleReplacesTheOldOne() throws {
        defer { try? FileManager.default.removeItem(at: directory) }
        let library = LegacyLibrary(directory: directory)
        let id = UUID()
        try library.add(keepsake(owner: "Dad", bibleID: id, created: 1_700_000_000))
        let result = try library.add(keepsake(owner: "Dad", bibleID: id, created: 1_800_000_000,
                                              highlights: [KeepsakeHighlight(verse: VerseRef(.john, 3, 16).key, color: "blue", createdAt: .now)]))
        guard case .replaced(let previous) = result else { Issue.record("expected a replacement"); return }
        #expect(previous == Date(timeIntervalSince1970: 1_700_000_000))
        #expect(library.entries.count == 1)
        #expect(library.keepsake(id)?.highlights.count == 1)
    }

    @Test func removingOneKeepsakeLeavesTheOthers() throws {
        defer { try? FileManager.default.removeItem(at: directory) }
        let library = LegacyLibrary(directory: directory)
        let a = keepsake(owner: "A"), b = keepsake(owner: "B")
        try library.add(a)
        try library.add(b)
        library.remove(a.id)
        #expect(library.entries.map(\.id) == [b.id])
        #expect(!FileManager.default.fileExists(atPath: library.fileURL(for: a.id).path))
        #expect(LegacyLibrary(directory: directory).entries.map(\.id) == [b.id])
    }

    /// The stored copy is the unprotected one: the passphrase opened it once, on import, and
    /// "Save a Copy" must hand back a file that opens without it.
    @Test func theStoredCopyOpensWithoutThePassphrase() throws {
        defer { try? FileManager.default.removeItem(at: directory) }
        let original = keepsake(owner: "Mum", notes: [note("t", "b", [])])
        let sealed = try KeepsakeArchive.encode(original, passphrase: "rosebud", iterations: 1_000)
        #expect(throws: KeepsakeError.wrongPassphrase) { try KeepsakeArchive.decode(sealed, passphrase: "wrong") }
        let opened = try KeepsakeArchive.decode(sealed, passphrase: "rosebud")

        let library = LegacyLibrary(directory: directory)
        try library.add(opened)
        let copy = try Data(contentsOf: library.fileURL(for: original.id))
        #expect(try KeepsakeArchive.peek(copy).isEncrypted == false)
        #expect(try KeepsakeArchive.decode(copy).notes.map(\.body) == ["b"])
    }

    @Test func damagedFilesInTheFolderAreIgnored() throws {
        defer { try? FileManager.default.removeItem(at: directory) }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try Data("not a zip".utf8).write(to: directory.appending(path: "\(UUID().uuidString).scripturelegacy"))
        try Data("x".utf8).write(to: directory.appending(path: "notes.txt"))
        let library = LegacyLibrary(directory: directory)
        #expect(library.entries.isEmpty)
        try library.add(keepsake(owner: "Dad"))
        #expect(library.entries.count == 1)
    }

    // MARK: Marks in the reader

    @Test func marksShowTheNewestHighlightAndNoteMarkersAtTheirLastVerse() {
        let john3 = ChapterRef(.john, 3)
        let old = Date(timeIntervalSince1970: 1), new = Date(timeIntervalSince1970: 2)
        let spanning = note("Across", "", [VerseRange(VerseRef(.john, 3, 35), VerseRef(.john, 4, 2))])
        let inside = note("Inside", "", [VerseRange(VerseRef(.john, 3, 16), VerseRef(.john, 3, 17))])
        let elsewhere = note("Elsewhere", "", [VerseRange(VerseRef(.romans, 8, 1))])
        let gift = keepsake(owner: "Dad", highlights: [
            KeepsakeHighlight(verse: VerseRef(.john, 3, 16).key, color: "blue", createdAt: new),
            KeepsakeHighlight(verse: VerseRef(.john, 3, 16).key, color: "pink", createdAt: old),
            KeepsakeHighlight(verse: VerseRef(.john, 3, 18).key, color: "ultraviolet", createdAt: old),
            KeepsakeHighlight(verse: VerseRef(.john, 4, 1).key, color: "green", createdAt: old),
        ], notes: [spanning, inside, elsewhere])

        let marks = gift.marks(for: john3, verseCount: 36)
        #expect(marks.highlights[VerseRef(.john, 3, 16).key] == "blue", "the newer highlight wins")
        #expect(marks.highlights[VerseRef(.john, 3, 18).key] == HighlightColor.yellow.rawValue, "an unknown colour shows as yellow")
        #expect(marks.highlights[VerseRef(.john, 4, 1).key] == nil, "another chapter's highlight")
        #expect(marks.notes[VerseRef(.john, 3, 17).key] == [inside.id.uuidString])
        #expect(marks.notes[VerseRef(.john, 3, 36).key] == [spanning.id.uuidString], "a note running on is marked at this chapter's end")
        #expect(!marks.notes.values.joined().contains(elsewhere.id.uuidString))
    }
}

extension LegacyLibrary.AddResult {
    var isAdded: Bool { if case .added = self { true } else { false } }
}
