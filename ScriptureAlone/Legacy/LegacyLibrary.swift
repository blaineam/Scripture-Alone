import Foundation
import Observation
import UniformTypeIdentifiers
import ScriptureAloneCore

extension UTType {
    /// A Legacy Bible keepsake (`.scripturelegacy`), declared in Info.plist.
    nonisolated static let scriptureLegacy = UTType(exportedAs: KeepsakeArchive.typeIdentifier, conformingTo: .data)
}

/// Keepsakes this person has been given, kept as plain files in Application Support —
/// deliberately outside SwiftData, so they never sync into or mix with the reader's own
/// highlights and notes. They are included in device backups.
@Observable
final class LegacyLibrary {
    static let shared = LegacyLibrary()

    struct Entry: Identifiable, Hashable {
        let id: UUID
        let manifest: KeepsakeManifest
        let addedAt: Date

        var title: String { manifest.displayTitle }

        static func == (lhs: Entry, rhs: Entry) -> Bool { lhs.id == rhs.id && lhs.manifest.exportID == rhs.manifest.exportID }
        func hash(into hasher: inout Hasher) { hasher.combine(id) }
    }

    enum AddResult {
        case added
        /// A newer (or older) export of a Bible already in the library replaced it.
        case replaced(previous: Date)
    }

    private(set) var entries: [Entry] = []
    private var keepsakes: [UUID: Keepsake] = [:]
    private let directory: URL

    init(directory: URL? = nil) {
        let base = directory ?? URL.applicationSupportDirectory.appending(path: "Keepsakes", directoryHint: .isDirectory)
        self.directory = base
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        reload()
    }

    func keepsake(_ id: UUID) -> Keepsake? { keepsakes[id] }

    func entry(_ id: UUID) -> Entry? { entries.first { $0.id == id } }

    /// The stored copy, unprotected — for "Save a Copy".
    func fileURL(for id: UUID) -> URL { directory.appending(path: "\(id.uuidString).\(KeepsakeArchive.fileExtension)") }

    @discardableResult
    func add(_ keepsake: Keepsake) throws -> AddResult {
        let previous = entry(keepsake.id)?.manifest.createdAt
        let data = try KeepsakeArchive.encode(keepsake)
        try data.write(to: fileURL(for: keepsake.id), options: [.atomic, .completeFileProtectionUnlessOpen])
        reload()
        return previous.map { .replaced(previous: $0) } ?? .added
    }

    func remove(_ id: UUID) {
        try? FileManager.default.removeItem(at: fileURL(for: id))
        reload()
    }

    private func reload() {
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.creationDateKey])) ?? []
        var loaded: [UUID: Keepsake] = [:]
        var list: [Entry] = []
        for file in files where file.pathExtension == KeepsakeArchive.fileExtension {
            guard let data = try? Data(contentsOf: file), let keepsake = try? KeepsakeArchive.decode(data) else { continue }
            let added = (try? file.resourceValues(forKeys: [.creationDateKey]).creationDate) ?? keepsake.manifest.createdAt
            loaded[keepsake.id] = keepsake
            list.append(Entry(id: keepsake.id, manifest: keepsake.manifest, addedAt: added))
        }
        keepsakes = loaded
        entries = list.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
    }
}

/// Per window: which keepsake, if any, the reader is looking through.
@Observable
final class LegacySession {
    private(set) var reading: Keepsake?
    private var translationBefore: String?

    func open(_ keepsake: Keepsake, model: ReaderModel) {
        if reading == nil { translationBefore = model.translationID }
        reading = keepsake
        model.selection.removeAll()
        if let preferred = keepsake.manifest.preferredTranslation,
           preferred != model.translationID,
           model.translations.contains(where: { $0.id == preferred }) {
            model.selectTranslation(preferred)
        }
    }

    func close(model: ReaderModel) {
        reading = nil
        if let before = translationBefore, before != model.translationID { model.selectTranslation(before) }
        translationBefore = nil
    }
}

/// A stable id for this person's own Bible, so each new keepsake they make replaces the older
/// one on a family member's device instead of piling up beside it.
enum LegacyIdentity {
    private static let key = "legacy.bibleID"

    static var bibleID: UUID {
        let cloud = NSUbiquitousKeyValueStore.default
        if let raw = cloud.string(forKey: key) ?? UserDefaults.standard.string(forKey: key), let id = UUID(uuidString: raw) {
            return id
        }
        let id = UUID()
        cloud.set(id.uuidString, forKey: key)
        UserDefaults.standard.set(id.uuidString, forKey: key)
        return id
    }
}

extension Keepsake {
    /// Highlight colors and note markers for one chapter, shaped like the reader's own.
    func marks(for chapter: ChapterRef, verseCount: Int) -> (highlights: [Int: String], notes: [Int: [String]]) {
        var colors: [Int: (String, Date)] = [:]
        for highlight in highlights where chapter.keyRange.contains(highlight.verse) {
            if let existing = colors[highlight.verse], existing.1 > highlight.createdAt { continue }
            let color = HighlightColor(rawValue: highlight.color)?.rawValue ?? HighlightColor.yellow.rawValue
            colors[highlight.verse] = (color, highlight.createdAt)
        }
        var markers: [Int: [String]] = [:]
        for note in notes {
            for anchor in note.anchors where anchor.overlaps(chapter) {
                let end = anchor.end.chapterKey == chapter
                    ? anchor.end.key
                    : VerseRef(chapter.book, chapter.chapter, max(1, verseCount)).key
                markers[end, default: []].append(note.id.uuidString)
            }
        }
        return (colors.mapValues(\.0), markers)
    }
}
