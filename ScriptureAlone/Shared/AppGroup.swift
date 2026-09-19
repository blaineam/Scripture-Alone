import Foundation
import ScriptureAloneCore

/// The App Group the app shares with its widgets. The app writes a small JSON snapshot here;
/// widgets only ever read it. The SwiftData store is never shared with an extension.
///
/// Compiled into the app (default MainActor isolation) and the widget extension (timeline
/// providers run off the main actor), so everything here is explicitly nonisolated.
nonisolated enum AppGroup {
    static let identifier = "group.com.blainemiller.ScriptureAlone"

    static var containerURL: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: identifier)
    }

    static var snapshotURL: URL? { containerURL?.appending(path: VerseSnapshot.fileName) }

    /// Shared preferences (the Favorites widget's "next" nudge).
    static var defaults: UserDefaults? { UserDefaults(suiteName: identifier) }

    static func readSnapshot() -> VerseSnapshot? {
        guard let url = snapshotURL, let data = try? Data(contentsOf: url) else { return nil }
        return try? VerseSnapshot.decode(data)
    }

    /// Writes atomically; returns false when the group container isn't available (an unsigned
    /// build without the entitlement) or the bytes are unchanged, so callers can skip a reload.
    @discardableResult
    static func write(_ snapshot: VerseSnapshot) -> Bool {
        guard let url = snapshotURL, let data = try? snapshot.encoded() else { return false }
        if let existing = try? Data(contentsOf: url), existing == data { return false }
        do {
            try data.write(to: url, options: [.atomic])
            return true
        } catch {
            print("Scripture Alone: couldn’t write the widget snapshot (\(error.localizedDescription))")
            return false
        }
    }
}
