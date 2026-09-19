import SwiftUI
import SwiftData
import ScriptureAloneCore

extension View {
    /// Live family sharing for one window: refreshes shared Bibles and the owner's share status
    /// on launch and foreground, keeps the owner's mirror current while sharing is on, pushes
    /// fresh fetches into an open shared Bible, and welcomes a just-accepted invitation.
    func familySharing(session: LegacySession) -> some View { modifier(FamilySharingSupport(session: session)) }

    /// Pull-to-refresh, only while a live share is open.
    func liveShareRefreshable(_ source: LegacySession.Source) -> some View { modifier(LiveShareRefreshable(source: source)) }
}

extension LegacySession {
    /// Reads a family member's live-shared Bible through the keepsake reader.
    func openLive(_ entry: SharedBibleLibrary.Entry, model: ReaderModel) {
        open(entry.keepsake, model: model, source: .live(entry.id), favorites: entry.snapshot.favoriteList)
    }
}

private struct AcceptedShare: Identifiable {
    let id: String
}

private struct FamilySharingSupport: ViewModifier {
    let session: LegacySession
    @Environment(ReaderModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase
    #if DEBUG
    @State private var debugSheet: DebugSheet?
    #endif

    func body(content: Content) -> some View {
        let library = SharedBibleLibrary.shared
        content
            .background { FamilyMirrorSync() }
            .task {
                await refreshAll()
                #if DEBUG
                stageDebugScene()
                #endif
            }
            .onChange(of: scenePhase) { _, phase in
                switch phase {
                case .active: Task { await refreshAll() }
                case .background: Task { await FamilySharingOwner.shared.flush() }
                default: break
                }
            }
            .onChange(of: library.entries) {
                // A fetch landed while a shared Bible is open: show the newer marks.
                guard case .live(let id) = session.source, let entry = library.entry(id) else { return }
                session.refresh(entry.keepsake, favorites: entry.snapshot.favoriteList)
            }
            .sheet(item: Binding(get: { library.justAccepted.map(AcceptedShare.init) },
                                 set: { if $0 == nil { library.justAccepted = nil } })) { accepted in
                SharedBibleWelcome(id: accepted.id)
                    .environment(session)
                    .environment(model)
            }
            #if DEBUG
            .sheet(item: $debugSheet) { sheet in
                Group {
                    switch sheet {
                    case .library: LegacySettingsView()
                    case .owner: NavigationStack { FamilySharingView() }
                    }
                }
                .environment(LegacyLibrary.shared)
                .environment(session)
                .environment(model)
            }
            #endif
    }

    private func refreshAll() async {
        async let shared: Void = SharedBibleLibrary.shared.refresh()
        async let owner: Void = FamilySharingOwner.shared.refresh()
        _ = await (shared, owner)
    }

    #if DEBUG
    private enum DebugSheet: String, Identifiable {
        case library, owner
        var id: String { rawValue }
    }

    private func stageDebugScene() {
        switch FamilyDebug.scene {
        case "reader":
            if let entry = SharedBibleLibrary.shared.entries.first {
                session.openLive(entry, model: model)
                // Dad's note on 3:1–8 and his highlight on 3:16–17.
                model.show(ChapterRef(.john, 3), verse: FamilyDebug.verse ?? 8)
            }
        case "library": debugSheet = .library
        case "owner", "owner-bottom": debugSheet = .owner
        default: break
        }
    }
    #endif
}

private struct LiveShareRefreshable: ViewModifier {
    let source: LegacySession.Source

    func body(content: Content) -> some View {
        if case .live = source {
            content.refreshable { await SharedBibleLibrary.shared.refresh() }
        } else {
            content
        }
    }
}

/// Watches the owner's library and hands it to the mirror — only while sharing is on.
private struct FamilyMirrorSync: View {
    @Environment(ReaderModel.self) private var model
    @Query private var highlights: [Highlight]
    @Query private var notes: [Note]
    @Query private var favorites: [Favorite]
    @AppStorage("legacy.ownerName") private var ownerName = ""
    @AppStorage("legacy.dedication") private var dedication = ""
    @AppStorage("legacy.translation") private var translation = ""

    var body: some View {
        let sharing = FamilySharingOwner.shared.isSharing
        Color.clear
            .frame(width: 0, height: 0)
            .accessibilityHidden(true)
            .task(id: sharing ? signature : 0) {
                guard sharing else { return }
                FamilySharingOwner.shared.mirror(FamilyShareInput.make(
                    highlights: highlights, notes: notes, favorites: favorites,
                    ownerName: ownerName, dedication: dedication,
                    translation: translation.isEmpty ? model.translationID : translation))
            }
    }

    /// Everything a participant would see. `.task(id:)` restarts when it changes.
    private var signature: Int {
        var hasher = Hasher()
        hasher.combine(ownerName)
        hasher.combine(dedication)
        hasher.combine(translation)
        for highlight in highlights { hasher.combine(highlight.verseKey); hasher.combine(highlight.colorName); hasher.combine(highlight.createdAt) }
        for note in notes {
            hasher.combine(note.uuid); hasher.combine(note.title); hasher.combine(note.body)
            hasher.combine(note.anchorsRaw); hasher.combine(note.updatedAt)
        }
        for favorite in favorites { hasher.combine(favorite.uuid); hasher.combine(favorite.rangeRaw); hasher.combine(favorite.createdAt) }
        return hasher.finalize() | 1
    }
}

/// Maps the owner's SwiftData models to the mirror's plain values. Slide photos are never
/// included.
enum FamilyShareInput {
    static func make(highlights: [Highlight], notes: [Note], favorites: [Favorite],
                     ownerName: String, dedication: String, translation: String) -> FamilyMirrorInput {
        FamilyMirrorInput(
            profile: FamilyOwnerProfile(bibleID: LegacyIdentity.bibleID, ownerName: ownerName,
                                        dedication: dedication, preferredTranslation: translation),
            highlights: highlights.map { KeepsakeHighlight(verse: $0.verseKey, color: $0.colorName, createdAt: $0.createdAt) },
            notes: notes.map(\.exportValue),
            favorites: favorites.map { FamilyFavorite(id: $0.uuid, start: $0.startKey, end: $0.endKey, createdAt: $0.createdAt) })
    }
}
