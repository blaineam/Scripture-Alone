import SwiftUI
import WidgetKit
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
                .modifier(WatchAccent())
                .environment(bible)
                .task { WatchPhoneLink.shared.activate(bible: bible) }
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

    /// DEBUG: `-watchRoute favorites|notes|highlights|books|<scripturealone:// URL>` opens a screen at
    /// launch, for simulator checks and screenshots (simctl can't open custom URLs on watchOS).
    private func openLaunchRoute() {
        #if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        guard let flag = arguments.firstIndex(of: "-watchRoute"), arguments.indices.contains(flag + 1) else { return }
        switch arguments[flag + 1] {
        case "favorites": path = [.favorites]
        case "notes": path = [.notes]
        case "highlights": path = [.highlights]
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
    case highlights
    case note(UUID)
    case translations

    @ViewBuilder var destination: some View {
        switch self {
        case .verse(let range): WatchVerseView(range: range)
        case .chapter(let chapter, let focus): WatchChapterView(chapter: chapter, focus: focus)
        case .book(let book): WatchChaptersView(book: book)
        case .books: WatchBooksView()
        case .favorites: WatchFavoritesView()
        case .notes: WatchNotesView()
        case .highlights: WatchHighlightsView()
        case .note(let id): WatchNoteView(id: id)
        case .translations: WatchTranslationsView()
        }
    }
}

/// The Bible the watch reads, in whichever translation the reader chose.
///
/// **Editions.** The watch bundles a compact edition of each translation the phone bundles — ASV,
/// BSB and KJV (`Tools/build_companion_data.py`): verse text and red letters, without the phone's
/// layout JSON or search index, about 4.5 MB each. A translation the reader imported on the phone
/// has no bundled edition, so the phone writes one with `WatchEdition` and sends it over
/// WatchConnectivity (`WatchPhoneLink`); it lands in `receivedDirectory` and reads through the very
/// same `BibleStore`.
///
/// **Which one is shown.** The most recent choice the watch can actually show wins: the reader
/// picking one here, or the phone reporting that they switched there. So switching to the KJV on
/// the phone moves the watch too, and picking the BSB on the watch keeps it until the phone
/// changes again. A phone choice the watch has no edition of — an online translation, whose terms
/// forbid storing it — is remembered but not applied, and the watch keeps what it had.
@Observable
final class WatchBible {
    /// Translations with a compact edition in the watch bundle. The phone skips sending these.
    nonisolated static let bundledIDs = ["ASV", "BSB", "KJV"]
    nonisolated static let fallback = "ASV"

    struct Edition: Identifiable, Hashable {
        let id: String
        let name: String
        /// What the reader calls it — an import's id is an internal name ("IMPORT-NN0XUW").
        let abbreviation: String
        let url: URL
        let bundled: Bool
    }

    /// The translation being read, as the reader calls it.
    var translationAbbreviation: String {
        editions.first { $0.id == translation }?.abbreviation ?? translation
    }

    private(set) var editions: [Edition] = []
    private(set) var translation: String = WatchBible.fallback
    private(set) var store: BibleStore?

    /// The phone's translation, even when the watch can't show it, for the picker to explain.
    private(set) var phoneTranslation: String?

    @ObservationIgnored private var stores: [String: BibleStore] = [:]
    @ObservationIgnored private let defaults = UserDefaults.standard

    init() { reloadEditions() }

    /// Editions the phone sent. Documents rather than Caches: the system may clear Caches, and a
    /// translation the reader chose should not silently revert to the ASV.
    nonisolated static var receivedDirectory: URL {
        let url = URL.documentsDirectory.appending(path: "Translations")
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    nonisolated static func receivedURL(for id: String) -> URL {
        receivedDirectory.appending(path: "\(id)-Watch.sqlite")
    }

    func reloadEditions() {
        let bundled = Self.bundledIDs.compactMap { id -> Edition? in
            guard let url = Bundle.main.url(forResource: "\(id)-Watch", withExtension: "sqlite"),
                  let store = open(id: id, url: url) else { return nil }
            return Edition(id: id, name: store.info.name, abbreviation: store.info.abbreviation, url: url, bundled: true)
        }
        let files = (try? FileManager.default.contentsOfDirectory(
            at: Self.receivedDirectory, includingPropertiesForKeys: nil)) ?? []
        let received = files
            .filter { $0.lastPathComponent.hasSuffix("-Watch.sqlite") }
            .compactMap { url -> Edition? in
                let id = String(url.lastPathComponent.dropLast("-Watch.sqlite".count))
                // A bundled translation always reads from the bundle; a stray copy is ignored.
                guard !Self.bundledIDs.contains(id), let store = open(id: id, url: url) else { return nil }
                return Edition(id: id, name: store.info.name, abbreviation: store.info.abbreviation, url: url, bundled: false)
            }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        editions = bundled + received
        phoneTranslation = defaults.string(forKey: Keys.phone)
        resolve()
    }

    /// The reader picked a translation on the watch.
    func choose(_ id: String) {
        defaults.set(id, forKey: Keys.choice)
        defaults.set(Date().timeIntervalSince1970, forKey: Keys.choiceAt)
        resolve()
    }

    /// The phone reported the translation the reader switched to, and when.
    func phoneChose(_ id: String, at changedAt: TimeInterval) {
        defaults.set(id, forKey: Keys.phone)
        defaults.set(changedAt, forKey: Keys.phoneAt)
        phoneTranslation = id
        resolve()
    }

    func removeReceived(_ edition: Edition) {
        guard !edition.bundled else { return }
        stores[edition.id] = nil
        try? FileManager.default.removeItem(at: edition.url)
        Self.forgetReceived(edition.id)
        reloadEditions()
    }

    /// Removes imported editions the phone no longer offers. Returns true when any went.
    func removeImports(notIn offered: Set<String>) -> Bool {
        let gone = editions.filter { !$0.bundled && Self.receivedImports().contains($0.id) && !offered.contains($0.id) }
        for edition in gone {
            stores[edition.id] = nil
            try? FileManager.default.removeItem(at: edition.url)
            Self.forgetReceived(edition.id)
        }
        if !gone.isEmpty { reloadEditions() }
        return !gone.isEmpty
    }

    // MARK: Received ledger

    /// What each received edition is: its version, and whether it is an import (which the phone's
    /// list governs) rather than a language Bible (which stays until removed here).
    private nonisolated struct Received: Codable {
        var versions: [String: String] = [:]
        var imports: Set<String> = []
    }

    private nonisolated static var ledgerURL: URL { receivedDirectory.appending(path: "received.json") }

    private nonisolated static func ledger() -> Received {
        (try? Data(contentsOf: ledgerURL)).flatMap { try? JSONDecoder().decode(Received.self, from: $0) } ?? Received()
    }

    private nonisolated static func save(_ ledger: Received) {
        if let data = try? JSONEncoder().encode(ledger) { try? data.write(to: ledgerURL, options: .atomic) }
    }

    nonisolated static func recordReceived(_ id: String, version: String?, isImport: Bool) {
        var ledger = ledger()
        ledger.versions[id] = version
        if isImport { ledger.imports.insert(id) } else { ledger.imports.remove(id) }
        save(ledger)
    }

    nonisolated static func forgetReceived(_ id: String) {
        var ledger = ledger()
        ledger.versions[id] = nil
        ledger.imports.remove(id)
        save(ledger)
    }

    nonisolated static func receivedVersions() -> [String: String] { ledger().versions }
    nonisolated static func receivedImports() -> Set<String> { ledger().imports }

    private func resolve() {
        let available = Set(editions.map(\.id))
        var candidates: [(id: String, at: TimeInterval)] = []
        if let id = defaults.string(forKey: Keys.choice), available.contains(id) {
            candidates.append((id, defaults.double(forKey: Keys.choiceAt)))
        }
        if let id = defaults.string(forKey: Keys.phone), available.contains(id) {
            candidates.append((id, defaults.double(forKey: Keys.phoneAt)))
        }
        let pick = candidates.max { $0.at < $1.at }?.id
            ?? (available.contains(Self.fallback) ? Self.fallback : editions.first?.id ?? Self.fallback)
        if pick != translation || store == nil {
            translation = pick
            store = editions.first { $0.id == pick }.flatMap { open(id: $0.id, url: $0.url) }
        }
        // Book names in the Bible's own language (a French edition from the phone reads "Jean").
        BookNames.use(language: store?.language ?? Locale.preferredLanguages.first)
        publishVerseOfDay()
    }

    /// Puts the translation the watch reads where the complications read it (the App Group), with
    /// the coming days' Verse of the Day for one the daily list doesn't carry — an import the
    /// phone sent — and reloads them when it changed.
    private func publishVerseOfDay() {
        guard let store else { return }
        let snapshot = VerseSnapshot(generatedAt: .now, translation: translation, items: [],
                                     abbreviation: store.info.abbreviation,
                                     daily: DailyVerseLibrary.ownTexts(from: store))
        if let previous = AppGroup.readSnapshot(), previous.translation == snapshot.translation,
           previous.abbreviation == snapshot.abbreviation, previous.daily == snapshot.daily { return }
        if AppGroup.write(snapshot) { WidgetCenter.shared.reloadAllTimelines() }
    }

    private func open(id: String, url: URL) -> BibleStore? {
        if let cached = stores[id] { return cached }
        let store = try? BibleStore(url: url)
        stores[id] = store
        return store
    }

    private enum Keys {
        static let choice = "watch.translation.choice"
        static let choiceAt = "watch.translation.choiceAt"
        static let phone = "watch.translation.phone"
        static let phoneAt = "watch.translation.phoneAt"
    }

    /// How the Bible on the watch numbers its verses against the KJV keys marks are stored under —
    /// identity for the ASV, BSB and KJV; a locale Bible sent from the phone carries its `kjv_map`.
    var numbering: VerseNumbering { store?.numbering ?? .identity }

    /// Verses by **KJV key** — a favorite, a note's passage, the verse of the day.
    func verses(_ range: VerseRange) -> [VerseText] {
        (try? store?.verses(in: range)) ?? []
    }

    /// A chapter as this Bible numbers it, for reading.
    func chapterVerses(_ chapter: ChapterRef) -> [VerseText] {
        (try? store?.nativeVerses(in: chapterRange(chapter))) ?? []
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

/// The reader's accent colour from the phone (`WatchLinkKeys.accent`), as the app's tint; the
/// asset catalog's accent until the phone has said.
struct WatchAccent: ViewModifier {
    nonisolated static let key = "watch.accent"
    @AppStorage(WatchAccent.key) private var hex = 0

    func body(content: Content) -> some View {
        if hex > 0 {
            let color = Color(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255,
                              blue: Double(hex & 0xFF) / 255)
            // Navigation titles keep the asset catalog's accent: watchOS draws them from it alone.
            content.tint(color)
        } else {
            content
        }
    }
}
