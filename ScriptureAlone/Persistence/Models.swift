import Foundation
import SwiftData
import ScriptureAloneCore

// CloudKit-backed SwiftData rules: every property has a default, nothing is unique,
// relationships are optional. Verse keys are translation-independent, so highlights and
// notes follow the reader across translations.

/// One highlighted verse. A selection of several verses stores one row per verse, which
/// keeps overlapping edits from different devices simple to merge.
@Model
final class Highlight {
    var verseKey: Int = 0
    var colorName: String = HighlightColor.yellow.rawValue
    var createdAt: Date = Date.now

    init(verseKey: Int, color: HighlightColor) {
        self.verseKey = verseKey
        self.colorName = color.rawValue
        self.createdAt = .now
    }
}

/// A note attached to one or more verse ranges — a Sunday sermon on Romans 8:1–17,
/// or a thought tying John 3:16 to Numbers 21:8–9.
@Model
final class Note {
    var uuid: UUID = UUID()
    var title: String = ""
    var body: String = ""
    /// Ranges as "start-end" verse keys, comma separated.
    var anchorsRaw: String = ""
    /// Earliest anchored verse key, for canonical sorting.
    var firstVerseKey: Int = 0
    var createdAt: Date = Date.now
    var updatedAt: Date = Date.now
    /// "manual" or "camera".
    var origin: String = "manual"
    /// The slide photo a camera note came from — only when the user chose to keep it.
    @Attribute(.externalStorage) var slidePhoto: Data?

    init(title: String = "", body: String = "", anchors: [VerseRange] = [], origin: String = "manual") {
        self.uuid = UUID()
        self.title = title
        self.body = body
        self.origin = origin
        self.createdAt = .now
        self.updatedAt = .now
        self.anchors = anchors
    }

    var anchors: [VerseRange] {
        get { anchorsRaw.split(separator: ",").compactMap { VerseRange(storageString: String($0)) } }
        set {
            let sorted = newValue.sorted()
            anchorsRaw = sorted.map(\.storageString).joined(separator: ",")
            firstVerseKey = sorted.first?.start.key ?? 0
        }
    }

    var displayTitle: String {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty { return trimmed }
        return anchors.first?.display ?? String(localized: "Untitled Note")
    }

    var anchorSummary: String { anchors.map(\.display).joined(separator: " · ") }

    func touches(_ chapter: ChapterRef) -> Bool { anchors.contains { $0.overlaps(chapter) } }
}

/// A favorited verse or passage — one row per contiguous range, so "Romans 8:38–39" is one
/// favorite. Shown in the Notes panel, the Favorites widget and on Apple Watch.
@Model
final class Favorite {
    var uuid: UUID = UUID()
    /// "start-end" verse keys, like a note anchor.
    var rangeRaw: String = ""
    /// First verse key, for canonical sorting and range queries.
    var startKey: Int = 0
    var endKey: Int = 0
    var createdAt: Date = Date.now

    init(range: VerseRange) {
        self.uuid = UUID()
        self.rangeRaw = range.storageString
        self.startKey = range.start.key
        self.endKey = range.end.key
        self.createdAt = .now
    }

    var range: VerseRange? { VerseRange(storageString: rangeRaw) }
}

enum DataStore {
    static let schema = Schema([Highlight.self, Note.self, Favorite.self])

    /// Syncs through the user's private iCloud database when the app is signed with the
    /// iCloud entitlement; falls back to a local store (unsigned builds, no account).
    static func makeContainer() -> ModelContainer {
        let inMemory = ProcessInfo.processInfo.arguments.contains("-inMemoryStore")
        if !inMemory {
            do {
                return try ModelContainer(for: schema, configurations: ModelConfiguration(schema: schema, cloudKitDatabase: .automatic))
            } catch {
                print("Scripture Alone: iCloud store unavailable (\(error.localizedDescription)); using a local store.")
            }
            do {
                return try ModelContainer(for: schema, configurations: ModelConfiguration("Local", schema: schema, cloudKitDatabase: .none))
            } catch {
                print("Scripture Alone: local store unavailable (\(error.localizedDescription)); using memory.")
            }
        }
        // Last resort keeps the Bible readable even if storage is broken.
        return try! ModelContainer(for: schema, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
    }
}
