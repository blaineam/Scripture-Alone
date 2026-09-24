import SwiftUI
import SwiftData
import WidgetKit
import ScriptureAloneCore

extension View {
    /// Keeps the widgets' snapshot current: whenever favorites, highlights or notes change —
    /// here or arriving from iCloud — or the translation changes, rewrite the App Group JSON
    /// and ask WidgetKit to reload.
    func widgetSnapshotSync() -> some View { modifier(WidgetSnapshotSync()) }
}

private struct WidgetSnapshotSync: ViewModifier {
    @Environment(ReaderModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.modelContext) private var context
    @Query private var favorites: [Favorite]
    @Query private var highlights: [Highlight]
    @Query private var notes: [Note]
    @AppStorage(SettingsKey.redLetters) private var redLetters = true

    /// Cheap fingerprint of everything the snapshot shows. `.task(id:)` restarts on change.
    private var signature: Int {
        var hasher = Hasher()
        hasher.combine(model.translationID)
        hasher.combine(redLetters)
        for favorite in favorites { hasher.combine(favorite.rangeRaw); hasher.combine(favorite.createdAt) }
        for highlight in highlights { hasher.combine(highlight.verseKey); hasher.combine(highlight.colorName); hasher.combine(highlight.createdAt) }
        for note in notes { hasher.combine(note.title); hasher.combine(note.anchorsRaw); hasher.combine(note.updatedAt) }
        return hasher.finalize()
    }

    func body(content: Content) -> some View {
        content
            .task { DemoLibrary.seedIfRequested(context) }
            .task(id: signature) {
                // Coalesce bursts (a multi-verse highlight, an iCloud import) into one write.
                try? await Task.sleep(for: .seconds(1))
                guard !Task.isCancelled else { return }
                write()
            }
            .onChange(of: scenePhase) { _, phase in
                if phase == .background { write() }
            }
    }

    /// The next two weeks' Verse of the Day in `store`, for a translation the widget doesn't carry
    /// (the daily list has the bundled Bibles' text already). Only one whose terms let it be
    /// stored; red letters as the widget draws them.
    private static func dailyTexts(from store: BibleStore) -> [String: VerseSnapshot.DailyText]? {
        let info = store.info
        guard info.rights.allowOfflineStorage, !(DailyVerseLibrary.catalog?.translations.contains(info.id) ?? false) else { return nil }
        var result: [String: VerseSnapshot.DailyText] = [:]
        var date = Date.now
        for _ in 0..<14 {
            if let verse = DailyVerseLibrary.verse(on: date), let range = verse.range,
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

    private func write() {
        guard let store = model.store else { return }
        // The Verse of the Day widget honors the reader's red-letter setting.
        if AppGroup.defaults?.object(forKey: SettingsKey.redLetters) as? Bool != redLetters {
            AppGroup.defaults?.set(redLetters, forKey: SettingsKey.redLetters)
            WidgetCenter.shared.reloadTimelines(ofKind: "VerseOfDay")
        }
        var snapshot = VerseSnapshot.build(
            favorites: favorites.compactMap { favorite in favorite.range.map { ($0, favorite.createdAt) } },
            highlights: highlights.map { .init(verseKey: $0.verseKey, color: $0.colorName, date: $0.createdAt) },
            notes: notes.map { .init(title: $0.title, anchors: $0.anchors, date: $0.updatedAt) },
            translation: store.info.id,
            generatedAt: .now,
            verseCount: { store.verseCount($0) },
            text: { range in
                // Long note ranges only need their opening for a widget.
                let capped = VerseRange(range.start, min(range.end, VerseRef(range.start.book, range.start.chapter,
                                                                              range.start.verse + 12)))
                return ((try? store.verses(in: capped)) ?? []).map(\.text)
                    .joined(separator: " ")
                    .replacingOccurrences(of: "¶ ", with: "")
            })
        snapshot.abbreviation = store.info.abbreviation
        snapshot.daily = Self.dailyTexts(from: store)
        // The generation date changes every time; compare content so an idle library doesn't
        // churn widget reloads.
        if let previous = AppGroup.readSnapshot(), previous.translation == snapshot.translation, previous.items == snapshot.items,
           previous.abbreviation == snapshot.abbreviation, previous.daily == snapshot.daily {
            return
        }
        if AppGroup.write(snapshot) {
            WidgetCenter.shared.reloadAllTimelines()
        }
    }
}
