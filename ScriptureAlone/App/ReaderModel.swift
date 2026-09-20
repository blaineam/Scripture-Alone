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

    /// The chapter `layout` was built for.
    ///
    /// An online translation fetches, so there is a window where `location` has already moved to
    /// the chapter the reader asked for and `layout` still holds the one they were reading. Drawn
    /// as-is, that is the previous chapter's verses under the new chapter's reference — the right
    /// address over the wrong words, which for scripture is the worst way to be wrong. Carrying the
    /// chapter alongside the layout lets the reader refuse to draw a mismatch instead of trusting
    /// that one never happens.
    private(set) var layoutChapter: ChapterRef?

    /// The only way `layout` is written, so the two can never disagree.
    private func setLayout(_ value: ChapterLayout?, for chapter: ChapterRef?) {
        layout = value
        layoutChapter = value == nil ? nil : chapter
    }
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
    ///
    /// Installing it retries a chapter that failed for want of it. Order still matters at launch
    /// and the app gets it right — but "the reader is told their key is missing because two lines
    /// ran in the wrong order" is a bad enough failure that it should not be possible to
    /// reintroduce by rearranging startup.
    var onlineLoader: (@MainActor (TranslationEntry, ChapterRef) async throws -> BibleStore)? {
        didSet {
            guard onlineLoader != nil, onlineTranslation != nil, layout == nil else { return }
            loadError = nil
            load()
        }
    }
    @ObservationIgnored private var fetchTask: Task<Void, Never>?
    private let defaults = UserDefaults.standard
    private let cloud = NSUbiquitousKeyValueStore.default

    init() {
        // A sealed translation is a bundled translation. The American Standard Version ships as a
        // signed, encrypted package rather than a database, and appears here exactly like the two
        // that don't — which is the point: the reader is never asked to care.
        let names = ["ASV": "American Standard Version", "BSB": "Berean Standard Bible",
                     "KJV": "King James Version"]
        let bundled = ["ASV", "BSB", "KJV"].compactMap { id -> TranslationEntry? in
            if SealedTranslations.shared.package(id) != nil {
                return TranslationEntry(id: id, name: names[id] ?? id, source: .package)
            }
            guard let url = Bundle.main.url(forResource: id, withExtension: "sqlite") else { return nil }
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

        // Only the bundled translations exist this early: imports arrive from the library and
        // online ones from the reader's keys, both after the first frame. So a reader whose
        // translation is an import or an API translation will not find it here — and the fallback
        // must not be mistaken for a choice. Writing it to `defaults` was exactly that mistake:
        // it destroyed the preference before the real entry could arrive, so the selection could
        // never come back, on this launch or any later one.
        let preferred = defaults.string(forKey: "translation") ?? Self.defaultTranslation
        if translations.contains(where: { $0.id == preferred }) {
            selectTranslation(preferred)
        } else {
            // Hold the wish. `rebuildTranslations` grants it the moment the entry shows up.
            awaitedTranslation = preferred
            let fallback = [Self.defaultTranslation, translations.first?.id]
                .compactMap { $0 }
                .first { id in translations.contains { $0.id == id } }
            if let fallback { selectTranslation(fallback, remember: false) }
        }
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

        // The reader's own choice, now that it can be honoured. This is the other half of the
        // fallback in `init`: imports and online keys register after the first frame, so the
        // translation someone was last reading usually becomes available a moment after launch.
        if let wanted = awaitedTranslation, translations.contains(where: { $0.id == wanted }) {
            selectTranslation(wanted)
            return
        }

        // A translation that has gone away — a deleted import, a removed key, a sealed package
        // whose key would not unwrap — must not stay selected, or the reader is left staring at a
        // chapter that can never load.
        if !translations.contains(where: { $0.id == translationID }) {
            let fallback = translations.contains { $0.id == Self.defaultTranslation }
                ? Self.defaultTranslation
                : translations.first?.id
            // `remember: false` again: if the reader's translation is merely not back yet, this
            // must not become their new preference.
            if let fallback { selectTranslation(fallback, remember: awaitedTranslation == nil) }
        }
    }

    /// A translation the reader chose that the app could not offer yet.
    ///
    /// Nil once it has been honoured or once something else was deliberately chosen. It is not
    /// persisted itself — `defaults["translation"]` is the durable record, and the whole point of
    /// this property is that the record survives a launch that cannot yet satisfy it.
    private var awaitedTranslation: String?

    /// Online translations, as configured by the reader's keys. Kept apart from imports so a
    /// key removal doesn't disturb files on disk, and vice versa.
    private(set) var onlineEntries: [TranslationEntry] = []

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

    /// - Parameter remember: false when the app is falling back rather than the reader choosing.
    ///   A fallback must not overwrite what they asked for, or their choice is lost for good.
    func selectTranslation(_ id: String, remember: Bool = true) {
        guard let entry = translations.first(where: { $0.id == id }) else { return }
        if remember { awaitedTranslation = nil }
        let persist = remember
        if case .online(let provider, _) = entry.source {
            store = nil
            packageSource = nil
            setLayout(nil, for: nil)
            loadError = nil
            onlineTranslation = (entry, TranslationInfo(id: entry.id, name: entry.name,
                                                        abbreviation: entry.id,
                                                        copyright: provider.copyrightNotice,
                                                        license: provider.licenseSummary))
            if persist { defaults.set(id, forKey: "translation") }
            load()
            return
        }
        onlineTranslation = nil
        if case .package = entry.source {
            // A locked device at a cold background launch can leave the key unreadable; try once
            // more before telling the reader the translation is broken.
            if SealedTranslations.shared.package(id) == nil { SealedTranslations.shared.reopen(id) }
            guard let package = SealedTranslations.shared.package(id) else {
                loadError = SealedTranslations.shared.failure(id) ?? "\(entry.name) couldn't be opened."
                setLayout(nil, for: nil)
                return
            }
            packageSource = package
            store = nil
            if persist { defaults.set(id, forKey: "translation") }
            if let top = topVerse { scrollTarget = top }
            load()
            return
        }
        packageSource = nil
        guard let url = entry.url else { return }
        do {
            let store = try stores[id] ?? BibleStore(url: url)
            stores[id] = store
            self.store = store
            if persist { defaults.set(id, forKey: "translation") }
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

    /// Any translation the app can read, by id, whatever kind it is. Compare and an incoming share
    /// link both need to open a translation that isn't the one being read.
    func source(for id: String) -> (any ChapterTextSource)? {
        SealedTranslations.shared.package(id) ?? store(for: id)
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
        let chapter = location
        do {
            setLayout(try source.layout(for: chapter), for: chapter)
            loadError = nil
        } catch {
            setLayout(nil, for: nil)
            loadError = error.localizedDescription
        }
    }

    /// Fetches a chapter the app is not allowed to ship.
    ///
    /// While it is in flight the reader shows that it is loading rather than the chapter it was
    /// showing before. Keeping the old text up was the earlier behaviour and it read better on a
    /// slow network — but the toolbar has already moved to the new reference, so what a reader
    /// actually saw was Psalm 90's text titled Psalm 91. A moment of "loading" is a small cost
    /// against attributing the wrong words to a verse.
    ///
    /// Re-reading the chapter already on screen — a retry, a translation change — keeps its text,
    /// since there is no mismatch to create.
    private func loadOnline(_ entry: TranslationEntry) {
        guard let onlineLoader else {
            loadError = "This translation needs a key. Add one in Manage Translations."
            setLayout(nil, for: nil)
            return
        }
        fetchTask?.cancel()
        let chapter = location
        if layoutChapter != chapter { setLayout(nil, for: nil) }
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
                self.setLayout(try store.layout(for: chapter), for: chapter)
                self.loadError = nil
            } catch {
                guard !Task.isCancelled, let self, self.location == chapter else { return }
                self.setLayout(nil, for: nil)
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

    /// What the translation being read permits. A packaged translation carries its publisher's own
    /// answer; everything else derives one from its licence line. One question, one answer, asked
    /// the same way whatever kind of translation is open.
    var rights: TranslationRights { translationInfo?.rights ?? .publicDomain }

    /// How many verses the current selection would quote.
    func verseCount(in ranges: [VerseRange]) -> Int {
        guard let source else { return 0 }
        return ranges.reduce(0) { $0 + ((try? source.verses(in: $1))?.count ?? 0) }
    }

    /// Whether this selection may leave the device at all, under this translation's terms.
    func mayQuote(_ ranges: [VerseRange]) -> Bool {
        rights.mayQuote(verseCount: verseCount(in: ranges))
    }

    /// "“For God so loved…” John 3:16 ASV" — numbered verses when more than one.
    ///
    /// Returns nothing when the selection is larger than the translation's quotation limit. The
    /// gate lives here, at the one place text is turned into something quotable, rather than at
    /// each button that might carry it away.
    func quotation(for ranges: [VerseRange]) -> String {
        guard mayQuote(ranges) else { return "" }
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
