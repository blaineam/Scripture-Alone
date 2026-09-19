import Foundation
import Observation
import ScriptureAloneCore

/// The bundled translations. Licensed translations will be added here as agreements land.
struct TranslationEntry: Identifiable, Hashable {
    let id: String
    let name: String
    let url: URL
}

@Observable
final class ReaderModel {
    static let defaultTranslation = "ASV"

    let translations: [TranslationEntry]
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
    private let defaults = UserDefaults.standard
    private let cloud = NSUbiquitousKeyValueStore.default

    init() {
        let bundled = ["ASV", "BSB", "KJV"].compactMap { id -> TranslationEntry? in
            guard let url = Bundle.main.url(forResource: id, withExtension: "sqlite") else { return nil }
            let names = ["ASV": "American Standard Version", "BSB": "Berean Standard Bible", "KJV": "King James Version"]
            return TranslationEntry(id: id, name: names[id] ?? id, url: url)
        }
        translations = bundled

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

    var translationID: String { store?.info.id ?? Self.defaultTranslation }

    func selectTranslation(_ id: String) {
        guard let entry = translations.first(where: { $0.id == id }) else { return }
        do {
            let store = try stores[id] ?? BibleStore(url: entry.url)
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
        guard let entry = translations.first(where: { $0.id == id }), let store = try? BibleStore(url: entry.url) else { return nil }
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
        guard let store else { return }
        do {
            layout = try store.layout(for: location)
            loadError = nil
        } catch {
            layout = nil
            loadError = error.localizedDescription
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
        guard let store else { return [] }
        return VerseRange.ranges(from: selection) { store.verseCount($0) }
    }

    /// "“For God so loved…” John 3:16 ASV" — numbered verses when more than one.
    func quotation(for ranges: [VerseRange]) -> String {
        guard let store else { return "" }
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
