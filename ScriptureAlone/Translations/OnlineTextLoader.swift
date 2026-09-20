import Foundation
import ScriptureAloneCore

/// Fetches chapters for the translations the app may not ship.
///
/// The reader's key decides whether a provider is usable at all; without one the translation is
/// still listed, so the offer is visible, and choosing it explains what to do rather than failing
/// silently.
@MainActor
struct OnlineTextLoader {
    let keys: OnlineTranslationKeys

    enum Failure: LocalizedError {
        case needsKey(OnlineProvider)
        case unsupported(String)

        var errorDescription: String? {
            switch self {
            case .needsKey(let provider):
                switch provider {
                case .crossway:
                    "Add your free Crossway key in Manage Translations to read the ESV."
                case .apiBible:
                    "Add your free API.Bible key in Manage Translations to read this translation."
                }
            case .unsupported(let name):
                "\(name) can't be read yet."
            }
        }
    }

    /// Where each online translation's cache lives. One file per translation, outside every
    /// synced container: a cached chapter is a copy of someone else's text, held under their
    /// terms, and it belongs on this device only.
    static func cacheURL(for entry: TranslationEntry) -> URL {
        let base = URL.applicationSupportDirectory.appending(path: "OnlineTranslations")
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        let safe = entry.id.filter { $0.isLetter || $0.isNumber }
        return base.appending(path: (safe.isEmpty ? "ONLINE" : safe) + ".sqlite")
    }

    /// Searches an online translation at its provider.
    func search(_ entry: TranslationEntry, _ query: String) async throws -> [BibleStore.SearchHit] {
        guard case .online(let provider, let remoteID) = entry.source else { return [] }
        guard let key = keys.key(for: provider) else { throw Failure.needsKey(provider) }
        switch provider {
        case .crossway: return try await ESVClient(key: key).search(query)
        case .apiBible: return try await APIBibleClient(key: key, bibleID: remoteID).search(query)
        }
    }

    /// Fetches a chapter and files it in the translation's cache, returning the store the reader
    /// should now read from.
    ///
    /// The cache is a real store in the bundled schema, so once a chapter is in it every other
    /// feature — selecting verses, quoting, listening, searching what has been read — works on
    /// an online translation exactly as it does on a bundled one.
    func chapter(_ entry: TranslationEntry, _ chapter: ChapterRef) async throws -> BibleStore {
        guard case .online(let provider, let remoteID) = entry.source else {
            throw Failure.unsupported(entry.name)
        }
        let url = Self.cacheURL(for: entry)
        let translation = OnlineTranslation(id: entry.id, name: entry.name, abbreviation: entry.id,
                                            copyright: provider.copyrightNotice,
                                            license: provider.licenseSummary)

        // Already read, and still within the publisher's cache allowance: no request at all.
        if let cached = try? OnlineChapterCache(url: url, translation: translation), cached.contains(chapter) {
            try? cached.markRead(chapter)
            return try BibleStore(url: url, immutable: false)
        }

        guard let key = keys.key(for: provider) else { throw Failure.needsKey(provider) }
        let verses: [VerseText]
        switch provider {
        case .crossway:
            verses = try await ESVClient(key: key).chapter(chapter)
        case .apiBible:
            verses = try await APIBibleClient(key: key, bibleID: remoteID).chapter(chapter)
        }

        try await Task.detached(priority: .userInitiated) {
            let cache = try OnlineChapterCache(url: url, translation: translation)
            try cache.store(verses, for: chapter)
        }.value
        // The cache swaps the file underneath, so a store opened before the write cannot see the
        // new chapter — it is re-opened rather than reused.
        return try BibleStore(url: url, immutable: false)
    }
}
