import Foundation
import SwiftData
import ScriptureAloneCore
#if os(iOS)
import UIKit
#endif

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
        makeContainer(suspensionGuard: SystemSuspensionGuard())
    }

    /// Opening the store reads the WAL under a SQLite file lock, and once the app has an app group
    /// SwiftData keeps the store in the shared container. iOS kills a process that is suspended
    /// while it holds a lock there (RUNNINGBOARD 0xdead10cc), and a launch the system backgrounds
    /// mid-open (prewarm, an intent, a widget-driven wake) does exactly that. So the open runs
    /// inside a suspension guard — on iOS a background task — which keeps the process running until
    /// the store is loaded and the lock released.
    static func makeContainer<G: SuspensionGuard>(suspensionGuard: G) -> ModelContainer {
        let token = suspensionGuard.begin("Scripture Alone store open")
        defer { suspensionGuard.end(token) }
        return openContainer()
    }

    private static func openContainer() -> ModelContainer {
        var inMemory = ProcessInfo.processInfo.arguments.contains("-inMemoryStore")
        #if DEBUG
        inMemory = inMemory || UITestMode.isOn
        #endif
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
        return try! ModelContainer(for: schema, configurations: inMemoryConfiguration())
    }

    /// A store in memory only, and never mirrored to iCloud. Left at the default (`.automatic`) an
    /// in-memory store still gets a CloudKit mirror under the iCloud entitlement; with no iCloud
    /// account that mirror fails to set up and takes the store's connections with it, and the next
    /// save raises "No eligible connection available" — an exception `try?` cannot catch.
    static func inMemoryConfiguration() -> ModelConfiguration {
        ModelConfiguration(schema: schema, isStoredInMemoryOnly: true, cloudKitDatabase: .none)
    }
}

/// Keeps the process from being suspended while a file lock is held.
nonisolated protocol SuspensionGuard {
    associatedtype Token
    func begin(_ name: String) -> Token
    func end(_ token: Token)
}

/// A background task on iOS (it must start on the main thread, which is where the app's launch and
/// its intents reach the store); nothing on the Mac, where the system doesn't suspend an app this way,
/// or on the watch.
nonisolated struct SystemSuspensionGuard: SuspensionGuard {
    #if os(iOS)
    func begin(_ name: String) -> UIBackgroundTaskIdentifier {
        guard Thread.isMainThread else { return .invalid }
        return MainActor.assumeIsolated { UIApplication.shared.beginBackgroundTask(withName: name) }
    }

    func end(_ token: UIBackgroundTaskIdentifier) {
        guard token != .invalid else { return }
        MainActor.assumeIsolated { UIApplication.shared.endBackgroundTask(token) }
    }
    #else
    func begin(_ name: String) {}
    func end(_ token: Void) {}
    #endif
}
