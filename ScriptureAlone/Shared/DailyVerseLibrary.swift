import Foundation
import ScriptureAloneCore

/// The bundled Verse of the Day list (DailyVerses.json, ~170 KB), shared by the app, the
/// widget extension, the watch app and its complications — none of them open a Bible database
/// just to show today's verse.
nonisolated enum DailyVerseLibrary {
    /// Decoded once per process; `static let` initialization is thread-safe.
    static let catalog: DailyVerseCatalog? = {
        guard let url = Bundle.main.url(forResource: DailyVerseCatalog.resourceName, withExtension: "json"),
              let data = try? Data(contentsOf: url) else { return nil }
        return try? DailyVerseCatalog(data: data)
    }()

    static func verse(on date: Date = .now, calendar: Calendar = .current) -> DailyVerse? {
        catalog?.verse(on: date, calendar: calendar)
    }

    /// A stand-in for previews and the widget gallery if the resource is somehow missing.
    static let placeholder = DailyVerse(
        ref: "19023001-19023001", theme: "The LORD is my shepherd",
        text: ["ASV": "Jehovah is my shepherd; I shall not want.",
               "BSB": "The LORD is my shepherd; I shall not want.",
               "KJV": "The LORD is my shepherd; I shall not want."])
}
