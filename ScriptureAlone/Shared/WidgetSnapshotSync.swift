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

    private func write() {
        guard let store = model.store else { return }
        // The Verse of the Day widget honors the reader's red-letter setting.
        if AppGroup.defaults?.object(forKey: SettingsKey.redLetters) as? Bool != redLetters {
            AppGroup.defaults?.set(redLetters, forKey: SettingsKey.redLetters)
            WidgetCenter.shared.reloadTimelines(ofKind: "VerseOfDay")
        }
        let snapshot = VerseSnapshot.build(
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
        // The generation date changes every time; compare content so an idle library doesn't
        // churn widget reloads.
        if let previous = AppGroup.readSnapshot(), previous.translation == snapshot.translation, previous.items == snapshot.items {
            return
        }
        if AppGroup.write(snapshot) {
            WidgetCenter.shared.reloadAllTimelines()
        }
    }
}
