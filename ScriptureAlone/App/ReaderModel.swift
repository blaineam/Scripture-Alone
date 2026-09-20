import Foundation
import Observation
import ScriptureAloneCore

/// A translation the reader can choose: bundled, imported, or read over the network.
struct TranslationEntry: Identifiable, Hashable {
    enum Source: Hashable {
        /// A SQLite store on disk — bundled or imported.
        case local(URL)
        /// Fetched from a publisher's API with the reader's own key. `id` is the provider's own
        /// identifier for the translation.
        case online(provider: OnlineProvider, remoteID: String)
        /// A signed, encrypted package — read through the same protocol as everything else.
        case package
    }

    let id: String
    let name: String
    let source: Source

    var url: URL? {
        if case .local(let url) = source { return url }
        return nil
    }

    var isOnline: Bool {
        if case .online = source { return true }
        return false
    }

    var isPackage: Bool {
        if case .package = source { return true }
        return false
    }

    init(id: String, name: String, url: URL) {
        self.init(id: id, name: name, source: .local(url))
    }

    init(id: String, name: String, source: Source) {
        self.id = id
        self.name = name
        self.source = source
    }
}

enum ReaderModelError: LocalizedError {
    case noOnlineLoader
    var errorDescription: String? { "This translation needs a key. Add one in Manage Translations." }
}

@Observable
final class ReaderModel {
    static let defaultTranslation = "ASV"

    /// Bundled plus whatever the reader has imported.
    private(set) var translations: [TranslationEntry]
    /// Just the ones that ship in the app, for the Translations screen.
    private(set) var bundledTranslations: [TranslationEntry]
    private(set) var store: BibleStore?
    private(set) var location: ChapterRef
    private(set) var layout: ChapterLayout?
    private(set) var loadError: String?
    /// Verse keys the user has tapped.
    var selection: Set<Int> = []
    /// One-shot: the reader scrolls this verse to the top, then clears it.
    var scrollTarget: Int?
    /// The verse currently at the top of the screen.
    private(set) var topVerse: Int?
    private(set) var recent: [ChapterRef] = []
    /// Words and phrases the user has searched for, most recent first.
    private(set) var recentSearches: [String] = []

    private var stores: [String: BibleStore] = [:]
    /// Set while an online chapter is being fetched, so the reader can say so instead of
    /// showing an empty page.
    private(set) var isFetching = false
    /// The encrypted package in use, when the current text is one.
    private(set) var packageSource: TranslationPackage?

    /// What the reader is actually drawing from, whatever kind it is. Everything that needs verse
    /// text — selection, quotation, listening, compare, notes — asks this rather than `store`.
    var source: (any ChapterTextSource)? { packageSource ?? store }

    /// The online translation in use, when the current text comes from an API rather than a file.
    private(set) var onlineTranslation: (entry: TranslationEntry, info: TranslationInfo)?
    /// Supplied by the app so the model needn't know about keychains or providers.
    var onlineLoader: (@MainActor (TranslationEntry, ChapterRef) async throws -> BibleStore)?
    @ObservationIgnored private var fetchTask: Task<Void, Never>?
    private let defaults = UserDefaults.standard
    private let cloud = NSUbiquitousKeyValueStore.default

    init() {
        let bundled = ["ASV", "BSB", "KJV"].compactMap { id -> TranslationEntry? in
            guard let url = Bundle.main.url(forResource: id, withExtension: "sqlite") else { return nil }
            let names = ["ASV": "American Standard Version", "BSB": "Berean Standard Bible", "KJV": "King James Version"]
            return TranslationEntry(id: id, name: names[id] ?? id, url: url)
        }
        translations = bundled
        bundledTranslations = bundled

        // Where we left off: the synced position wins so a Mac picks up where the phone stopped.
        cloud.synchronize()
        let savedKey = (cloud.object(forKey: "position") as? Int) ?? defaults.integer(forKey: "position")
        let saved = VerseRef(key: savedKey)
        location = saved.map(\.chapterKey) ?? ChapterRef(.john, 1)
        recent = (defaults.array(forKey: "recent") as? [Int] ?? []).compactMap { VerseRef(key: $0)?.chapterKey }
        recentSearches = defaults.stringArray(forKey: "recentSearches") ?? []

        let preferred = defaults.string(forKey: "translation") ?? Self.defaultTranslation
        selectTranslation(translations.contains { $0.id == preferred } ? preferred : Self.defaultTranslation)
        if let saved, saved.verse > 1 { scrollTarget = saved.key }
    }

    /// Adds the imported translations to the pickers. Called after an import or a removal, so
    /// the toolbar menu and the Translations screen agree without either owning the other's list.
    func refreshTranslations(imported: [(TranslationInfo, URL)]) {
        importedEntries = imported.map { TranslationEntry(id: $0.0.id, name: $0.0.name, url: $0.1) }
        rebuildTranslations()
    }

    private var importedEntries: [TranslationEntry] = []

    private func rebuildTranslations() {
        let added = (importedEntries + onlineEntries).sorted {
            $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
        translations = bundledTranslations + added
        // A translation that has gone away — a deleted import, a removed key — must not stay
        // selected, or the reader is left staring at a chapter that can never load.
        if !translations.contains(where: { $0.id == translationID }) {
            selectTranslation(Self.defaultTranslation)
        }
    }

    /// Online translations, as configured by the reader's keys. Kept apart from imports so a
    /// key removal doesn't disturb files on disk, and vice versa.
    fileprivate(set) var onlineEntries: [TranslationEntry] = []

    func setOnlineTranslations(_ entries: [TranslationEntry]) {
        onlineEntries = entries
        rebuildTranslations()
    }

    /// A store for any translation, fetching and caching first when it lives behind an API.
    /// Used by Compare, which needs a second translation without disturbing the one being read.
    func onlineStore(for entry: TranslationEntry, chapter: ChapterRef) async throws -> BibleStore {
        guard let onlineLoader else { throw ReaderModelError.noOnlineLoader }
        return try await onlineLoader(entry, chapter)
    }

    /// Reads from a signed, encrypted package. Registered as a translation like any other, so
    /// everything downstream treats it as one.
    func setPackageTranslation(_ package: TranslationPackage?) {
        guard let package else { return }
        let entry = TranslationEntry(id: package.info.id, name: package.info.name, source: .package)
        if !translations.contains(where: { $0.id == entry.id }) {
            onlineEntries.append(entry)
            rebuildTranslations()
        }
        packageSource = package
        store = nil
        onlineTranslation = nil
        defaults.set(entry.id, forKey: "translation")
        load()
    }

    /// Search the translation being read, whatever kind it is.
    ///
    /// A bundled or imported store has an FTS5 index. An online translation cannot be indexed on
    /// the device — its terms cap what may be kept — so the provider does the searching, which
    /// costs one request and is why this is async.
    func search(_ query: String) async -> [BibleStore.SearchHit] {
        if let entry = translations.first(where: { $0.id == translationID }), entry.isOnline,
           let onlineSearch {
            return (try? await onlineSearch(entry, query)) ?? []
        }
        guard let source else { return [] }
        return (try? source.search(query, limit: 300)) ?? []
    }

    /// Supplied by the app, like `onlineLoader`, so the model needn't know about keys.
    var onlineSearch: (@MainActor (TranslationEntry, String) async throws -> [BibleStore.SearchHit])?

    var translationID: String {
        onlineTranslation?.entry.id ?? source?.info.id ?? Self.defaultTranslation
    }

    /// What the reader is reading, for attribution and for the rules about what may leave the
    /// device. An online translation has no store, but it still has a licence.
    var translationInfo: TranslationInfo? { onlineTranslation?.info ?? source?.info }

    func selectTranslation(_ id: String) {
        guard let entry = translations.first(where: { $0.id == id }) else { return }
        if case .online(let provider, _) = entry.source {
            store = nil
            packageSource = nil
            layout = nil
            loadError = nil
            onlineTranslation = (entry, TranslationInfo(id: entry.id, name: entry.name,
                                                        abbreviation: entry.id,
                                                        copyright: provider.copyrightNotice,
                                                        license: provider.licenseSummary))
            defaults.set(id, forKey: "translation")
            load()
            return
        }
        onlineTranslation = nil
        if case .package = entry.source {
            packageSource = EncryptedDemoLibrary.shared.package
            store = nil
            defaults.set(id, forKey: "translation")
            load()
            return
        }
        packageSource = nil
        guard let url = entry.url else { return }
        do {
            let store = try stores[id] ?? BibleStore(url: url)
            stores[id] = store
            self.store = store
            defaults.set(id, forKey: "translation")
            if let top = topVerse { scrollTarget = top }
            load()
        } catch {
            loadError = error.localizedDescription
        }
    }

    func store(for id: String) -> BibleStore? {
        if let store = stores[id] { return store }
        guard let entry = translations.first(where: { $0.id == id }), let url = entry.url,
              let store = try? BibleStore(url: url) else { return nil }
        stores[id] = store
        return store
    }

    // MARK: Navigation

    func go(to passage: Passage) {
        let clamped = passage.clamped
        show(clamped.chapter, verse: clamped.startVerse)
    }

    func go(to verse: VerseRef) { show(verse.chapterKey, verse: verse.verse) }

    func show(_ chapter: ChapterRef, verse: Int? = nil) {
        if chapter != location {
            selection.removeAll()
            remember(location)
        }
        location = chapter
        topVerse = nil
        load()
        if let verse, verse > 1 {
            scrollTarget = VerseRef(chapter.book, chapter.chapter, verse).key
        }
        savePosition(VerseRef(chapter.book, chapter.chapter, verse ?? 1))
    }

    func next() { if let next = location.next { show(next) } }
    func previous() { if let previous = location.previous { show(previous) } }

    private func load() {
        if let online = onlineTranslation {
            loadOnline(online.entry)
            return
        }
        guard let source else { return }
        do {
            layout = try source.layout(for: location)
            loadError = nil
        } catch {
            layout = nil
            loadError = error.localizedDescription
        }
    }

    /// Fetches a chapter the app is not allowed to ship. The previous chapter stays on screen
    /// until the new one arrives, so turning a page doesn't blank the reader on a slow network.
    private func loadOnline(_ entry: TranslationEntry) {
        guard let onlineLoader else {
            loadError = "This translation needs a key. Add one in Manage Translations."
            layout = nil
            return
        }
        fetchTask?.cancel()
        let chapter = location
        isFetching = true
        fetchTask = Task { @MainActor [weak self] in
            defer { self?.isFetching = false }
            do {
                // The loader returns the cache store with this chapter in it, so from here on the
                // online translation behaves like any other: selection, quoting, listening and
                // searching what has been read all work against a real store.
                let store = try await onlineLoader(entry, chapter)
                guard !Task.isCancelled, let self, self.location == chapter else { return }
                self.store = store
                self.layout = try store.layout(for: chapter)
                self.loadError = nil
            } catch {
                guard !Task.isCancelled, let self, self.location == chapter else { return }
                self.layout = nil
                self.loadError = error.localizedDescription
            }
        }
    }

    func updateTopVerse(_ key: Int) {
        topVerse = key
        if let ref = VerseRef(key: key) { savePosition(ref) }
    }

    private func savePosition(_ ref: VerseRef) {
        defaults.set(ref.key, forKey: "position")
        cloud.set(ref.key, forKey: "position")
    }

    private func remember(_ chapter: ChapterRef) {
        recent.removeAll { $0 == chapter }
        recent.insert(chapter, at: 0)
        recent = Array(recent.prefix(12))
        defaults.set(recent.map { VerseRef($0.book, $0.chapter, 1).key }, forKey: "recent")
    }

    // MARK: Recent searches

    /// Kept when a search is acted on — opening a result — so passing phrases typed and abandoned
    /// don't fill the list.
    func rememberSearch(_ query: String) {
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard text.count >= 2 else { return }
        recentSearches.removeAll { $0.compare(text, options: [.caseInsensitive, .diacriticInsensitive]) == .orderedSame }
        recentSearches.insert(text, at: 0)
        recentSearches = Array(recentSearches.prefix(12))
        saveRecentSearches()
    }

    func forgetSearch(_ query: String) {
        recentSearches.removeAll { $0 == query }
        saveRecentSearches()
    }

    func clearRecentSearches() {
        recentSearches = []
        saveRecentSearches()
    }

    private func saveRecentSearches() {
        defaults.set(recentSearches, forKey: "recentSearches")
    }

    // MARK: Selection

    func toggle(_ verseKey: Int) {
        if selection.contains(verseKey) { selection.remove(verseKey) } else { selection.insert(verseKey) }
    }

    var selectedRanges: [VerseRange] {
        guard let source else { return [] }
        return VerseRange.ranges(from: selection) { source.verseCount($0) }
    }

    /// "“For God so loved…” John 3:16 ASV" — numbered verses when more than one.
    func quotation(for ranges: [VerseRange]) -> String {
        guard let store = source else { return "" }
        let blocks = ranges.compactMap { range -> String? in
            guard let verses = try? store.verses(in: range), !verses.isEmpty else { return nil }
            let text = verses.count == 1
                ? verses[0].text
                : verses.map { "\($0.ref.verse) \($0.text)" }.joined(separator: " ")
            return "\(text)\n— \(range.display) (\(store.info.abbreviation))"
        }
        return blocks.joined(separator: "\n\n")
    }
}
