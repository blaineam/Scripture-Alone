import SwiftUI
import SwiftData
import CoreSpotlight
import AppIntents
import ScriptureAloneCore

/// The reader's Spotlight choices. Both are off until turned on: a note can be private, and this
/// app keeps what the reader writes to themselves unless they say otherwise.
enum SpotlightSettingsKey {
    static let notes = "spotlight.notes"
    static let favorites = "spotlight.favorites"
    /// Fingerprints of what was last put in (or taken out of) the index, so a launch with nothing
    /// changed does no work.
    static let indexedNotes = "spotlight.indexed.notes"
    static let indexedFavorites = "spotlight.indexed.favorites"
}

extension View {
    /// Keeps Spotlight in step with the reader's notes and favorites — only while the reader has
    /// turned each on in Settings, and emptied the moment they turn it off. Results are App Intents
    /// entities, so tapping one runs `OpenNoteIntent` / `OpenFavoriteIntent` and lands right on it.
    func spotlightSync() -> some View { modifier(SpotlightSync()) }
}

private struct SpotlightSync: ViewModifier {
    @Environment(ReaderModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase
    @Query(sort: \Note.updatedAt, order: .reverse) private var notes: [Note]
    @Query(sort: \Favorite.createdAt, order: .reverse) private var favorites: [Favorite]
    @AppStorage(SpotlightSettingsKey.notes) private var indexNotes = false
    @AppStorage(SpotlightSettingsKey.favorites) private var indexFavorites = false

    func body(content: Content) -> some View {
        content
            // `.task(id:)` restarts whenever what would be indexed changes — an edit here, a note
            // arriving from iCloud, a switch of translation (favorites carry its text), a toggle.
            .task(id: notesFingerprint) {
                try? await Task.sleep(for: .seconds(2))   // coalesce typing and sync bursts
                guard !Task.isCancelled else { return }
                await syncNotes()
            }
            .task(id: favoritesFingerprint) {
                try? await Task.sleep(for: .seconds(2))
                guard !Task.isCancelled else { return }
                await syncFavorites()
            }
            // Leaving the app ends a pending wait; write before it does.
            .onChange(of: scenePhase) { _, phase in
                guard phase == .background else { return }
                Task {
                    await syncNotes()
                    await syncFavorites()
                }
            }
    }

    // MARK: Fingerprints

    private var notesFingerprint: String {
        guard indexNotes else { return "off" }
        var hash = StableHash()
        hash.add(model.translationID)
        for note in notes {
            hash.add(note.uuid.uuidString)
            hash.add(note.title)
            hash.add(note.body)
            hash.add(note.anchorsRaw)
        }
        return "on-\(notes.count)-\(hash.value)"
    }

    private var favoritesFingerprint: String {
        guard indexFavorites else { return "off" }
        var hash = StableHash()
        hash.add(model.translationID)
        for favorite in favorites { hash.add(favorite.rangeRaw) }
        return "on-\(favorites.count)-\(hash.value)"
    }

    // MARK: Indexing

    private func syncNotes() async {
        let fingerprint = notesFingerprint
        let defaults = UserDefaults.standard
        guard defaults.string(forKey: SpotlightSettingsKey.indexedNotes) != fingerprint else { return }
        let index = CSSearchableIndex.default()
        do {
            // Rebuilt whole: a few hundred notes is cheap, and it can never leave a deleted note behind.
            try await index.deleteAppEntities(ofType: NoteEntity.self)
            if indexNotes {
                let numbering = model.numbering
                let entities = notes.map { NoteEntity($0, numbering: numbering) }
                if !entities.isEmpty { try await index.indexAppEntities(entities) }
            }
            defaults.set(fingerprint, forKey: SpotlightSettingsKey.indexedNotes)
        } catch {
            print("Scripture Alone: Spotlight notes index failed: \(error.localizedDescription)")
        }
    }

    private func syncFavorites() async {
        let fingerprint = favoritesFingerprint
        let defaults = UserDefaults.standard
        guard defaults.string(forKey: SpotlightSettingsKey.indexedFavorites) != fingerprint else { return }
        let index = CSSearchableIndex.default()
        do {
            try await index.deleteAppEntities(ofType: FavoriteVerseEntity.self)
            if indexFavorites {
                let source = model.source
                var seen = Set<String>()
                let entities = favorites.compactMap { favorite -> FavoriteVerseEntity? in
                    guard let range = favorite.range, seen.insert(range.storageString).inserted else { return nil }
                    return FavoriteVerseEntity(range: range, source: source)
                }
                if !entities.isEmpty { try await index.indexAppEntities(entities) }
            }
            defaults.set(fingerprint, forKey: SpotlightSettingsKey.indexedFavorites)
        } catch {
            print("Scripture Alone: Spotlight favorites index failed: \(error.localizedDescription)")
        }
    }
}

/// FNV-1a: stable across launches, unlike `Hasher`, so a stored fingerprint still means something
/// the next time the app opens.
private struct StableHash {
    private(set) var value: UInt64 = 0xcbf2_9ce4_8422_2325

    mutating func add(_ string: String) {
        for byte in string.utf8 {
            value ^= UInt64(byte)
            value = value &* 0x0000_0100_0000_01B3
        }
        value ^= 0xFF   // field separator
        value = value &* 0x0000_0100_0000_01B3
    }
}
