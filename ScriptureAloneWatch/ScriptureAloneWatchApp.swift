import SwiftUI
import SwiftData
import ScriptureAloneCore

/// Scripture Alone on Apple Watch: today's verse, favorites, notes (read-only) and a simple
/// reader. Standalone — the ASV ships in the bundle, and favorites, highlights and notes sync
/// through the same private iCloud database as the phone, pulled by SwiftData's CloudKit
/// mirroring whenever the app runs. No phone round-trip, no server.
@main
struct ScriptureAloneWatchApp: App {
    private let container = DataStore.makeContainer()
    @State private var bible = WatchBible()

    var body: some Scene {
        WindowGroup {
            WatchRootView()
                .environment(bible)
        }
        .modelContainer(container)
    }
}

struct WatchRootView: View {
    @State private var path: [WatchRoute] = []
    @Environment(\.modelContext) private var context

    var body: some View {
        NavigationStack(path: $path) {
            WatchHomeView()
                .navigationDestination(for: WatchRoute.self) { route in
                    route.destination
                }
        }
        .task {
            DemoLibrary.seedIfRequested(context)
            openLaunchRoute()
        }
        // Complications and widgets link here: scripturealone://open?ref=<start>-<end>
        .onOpenURL { url in open(url) }
    }

    private func open(_ url: URL) {
        if let range = ScriptureLink.range(from: url) { path = [.verse(range)] }
    }

    /// DEBUG: `-watchRoute favorites|notes|books|<scripturealone:// URL>` opens a screen at
    /// launch, for simulator checks and screenshots (simctl can't open custom URLs on watchOS).
    private func openLaunchRoute() {
        #if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        guard let flag = arguments.firstIndex(of: "-watchRoute"), arguments.indices.contains(flag + 1) else { return }
        switch arguments[flag + 1] {
        case "favorites": path = [.favorites]
        case "notes": path = [.notes]
        case "books": path = [.books]
        case let value: if let url = URL(string: value) { open(url) }
        }
        #endif
    }
}

enum WatchRoute: Hashable {
    case verse(VerseRange)
    case chapter(ChapterRef, focus: Int?)
    case book(BookID)
    case books
    case favorites
    case notes
    case note(UUID)

    @ViewBuilder var destination: some View {
        switch self {
        case .verse(let range): WatchVerseView(range: range)
        case .chapter(let chapter, let focus): WatchChapterView(chapter: chapter, focus: focus)
        case .book(let book): WatchChaptersView(book: book)
        case .books: WatchBooksView()
        case .favorites: WatchFavoritesView()
        case .notes: WatchNotesView()
        case .note(let id): WatchNoteView(id: id)
        }
    }
}

/// The compact ASV the watch bundles (Tools/build_companion_data.py): verse text and red
/// letters without the phone's layout JSON or search index.
@Observable
final class WatchBible {
    static let translation = "ASV"
    let store: BibleStore?

    init() {
        store = Bundle.main.url(forResource: "ASV-Watch", withExtension: "sqlite").flatMap { try? BibleStore(url: $0) }
    }

    func verses(_ range: VerseRange) -> [VerseText] {
        (try? store?.verses(in: range)) ?? []
    }

    func text(_ range: VerseRange) -> String {
        verses(range).map(\.text).joined(separator: " ")
    }

    func verseCount(_ chapter: ChapterRef) -> Int { store?.verseCount(chapter) ?? 0 }

    func chapterRange(_ chapter: ChapterRef) -> VerseRange {
        VerseRange(VerseRef(chapter.book, chapter.chapter, 1),
                   VerseRef(chapter.book, chapter.chapter, max(1, verseCount(chapter))))
    }
}

/// The reader's highlight colors. The phone defines these with its reader styles; the watch
/// needs only the names (Models.swift stores them) and a swatch.
enum HighlightColor: String, CaseIterable, Identifiable {
    case yellow, green, blue, pink, purple
    var id: String { rawValue }
    var swatch: Color { VerseStyling.highlight(rawValue) }
}

extension VerseRange {
    func intersects(_ other: VerseRange) -> Bool { start <= other.end && other.start <= end }
}
