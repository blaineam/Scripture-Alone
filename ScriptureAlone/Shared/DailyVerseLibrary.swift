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

    /// The next two weeks' Verse of the Day in `store`, for a translation this list doesn't carry
    /// (it has the bundled Bibles' text already) — what the widgets and complications show for an
    /// imported one (`VerseSnapshot.daily`). Only one whose terms let it be
    /// stored; red letters as the widget draws them.
    static func ownTexts(from store: BibleStore) -> [String: VerseSnapshot.DailyText]? {
        let info = store.info
        guard info.rights.allowOfflineStorage, !(catalog?.translations.contains(info.id) ?? false) else { return nil }
        var result: [String: VerseSnapshot.DailyText] = [:]
        var date = Date.now
        for _ in 0..<14 {
            if let verse = verse(on: date), let range = verse.range,
               let verses = try? store.verses(in: range), !verses.isEmpty {
                var text = ""
                var red: [[Int]] = []
                for item in verses {
                    var words = item.text
                    var shift = 0
                    if words.hasPrefix("¶ ") { words.removeFirst(2); shift = 2 }
                    if !text.isEmpty { text += " " }
                    let base = text.unicodeScalars.count
                    for span in item.red {
                        let utf16 = Array(item.text.utf16)
                        guard span.location >= shift, span.location + span.length <= utf16.count else { continue }
                        let start = String(decoding: utf16[shift..<span.location], as: UTF16.self)
                        let run = String(decoding: utf16[span.location..<(span.location + span.length)], as: UTF16.self)
                        red.append([base + start.unicodeScalars.count, run.unicodeScalars.count])
                    }
                    text += words
                }
                result[verse.ref] = VerseSnapshot.DailyText(text: text, red: red)
            }
            date = DailyVerseCatalog.nextMidnight(after: date)
        }
        return result.isEmpty ? nil : result
    }
}
