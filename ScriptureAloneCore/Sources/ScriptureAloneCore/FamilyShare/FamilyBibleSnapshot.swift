import Foundation

/// A participant's local copy of someone's live-shared Bible: what the shared zone held at the
/// last fetch. Kept as a file so it reads offline, and never mixed into the participant's own
/// SwiftData store.
public struct FamilyBibleSnapshot: Codable, Sendable, Equatable {
    public var profile: FamilyOwnerProfile?
    /// Keyed by record name, so incremental changes and deletions apply directly.
    public var highlights: [String: KeepsakeHighlight] = [:]
    public var notes: [String: KeepsakeNote] = [:]
    public var favorites: [String: FamilyFavorite] = [:]

    public init(profile: FamilyOwnerProfile? = nil) {
        self.profile = profile
    }

    public var isEmpty: Bool { highlights.isEmpty && notes.isEmpty && favorites.isEmpty }

    /// Applies one fetch's worth of zone changes. Records that don't decode are skipped;
    /// deleting a name that isn't here is harmless.
    public mutating func apply(changed: [FamilyMirrorRecord], deleted: [String]) {
        for name in deleted {
            highlights[name] = nil
            notes[name] = nil
            favorites[name] = nil
            if name == FamilyMirror.profileRecordName { profile = nil }
        }
        for record in changed {
            switch FamilyMirror.item(from: record) {
            case .profile(let value): profile = value
            case .highlight(let value): highlights[record.name] = value
            case .note(let value): notes[record.name] = value
            case .favorite(let value): favorites[record.name] = value
            case nil: continue
            }
        }
    }

    /// Favorites, newest first.
    public var favoriteList: [FamilyFavorite] { favorites.values.sorted { $0.createdAt > $1.createdAt } }

    /// The snapshot as a keepsake, so the keepsake reader can show it — and so a participant
    /// can keep it once sharing ends. `fallbackID` names the Bible when no profile arrived yet.
    public func keepsake(fallbackID: UUID, createdAt: Date = .now, generator: String = "Scripture Alone") -> Keepsake {
        var manifest = KeepsakeManifest(bibleID: profile?.bibleID ?? fallbackID,
                                        ownerName: profile?.ownerName,
                                        dedication: profile?.dedication,
                                        preferredTranslation: profile?.preferredTranslation,
                                        generator: generator, createdAt: createdAt)
        manifest.exportID = UUID()
        var keepsake = Keepsake(manifest: manifest,
                                highlights: highlights.values.sorted { ($0.verse, $0.createdAt) < ($1.verse, $1.createdAt) },
                                notes: notes.values.sorted { $0.id.uuidString < $1.id.uuidString })
        keepsake.refreshSummary()
        return keepsake
    }
}
