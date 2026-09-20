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

    func chapter(_ entry: TranslationEntry, _ chapter: ChapterRef) async throws -> [VerseText] {
        guard case .online(let provider, let remoteID) = entry.source else {
            throw Failure.unsupported(entry.name)
        }
        guard let key = keys.key(for: provider) else { throw Failure.needsKey(provider) }
        switch provider {
        case .crossway:
            return try await ESVClient(key: key).chapter(chapter)
        case .apiBible:
            return try await APIBibleClient(key: key, bibleID: remoteID).chapter(chapter)
        }
    }
}
