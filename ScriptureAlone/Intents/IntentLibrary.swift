import AppIntents
import Foundation
import SwiftData
import ScriptureAloneCore

/// What Siri, Shortcuts and Spotlight read from: the translations already on this device, the
/// shared SwiftData store, and the rules for turning a passage someone typed into stored keys.
///
/// Read-only about translations: an intent never starts a download or a network request. The
/// sealed ASV is always here; the BSB, KJV and the big-8 Bibles count once their pack has arrived;
/// the reader's imports count; online translations do not.
@MainActor
enum IntentLibrary {
    /// A translation an intent can read right now.
    struct Available: Hashable {
        let id: String
        let name: String
    }

    private static var stores: [String: BibleStore] = [:]

    static func availableTranslations() -> [Available] {
        var result: [Available] = []
        for id in SealedTranslations.identifiers {
            if let package = SealedTranslations.shared.package(id) {
                result.append(Available(id: id, name: package.info.name))
            }
        }
        for pack in AssetPack.translations {
            guard let id = pack.translationID,
                  FileManager.default.fileExists(atPath: AssetLibrary.installedURL(for: pack).path) else { continue }
            result.append(Available(id: id, name: pack.title))
        }
        for (info, _) in importedStores() where !result.contains(where: { $0.id == info.id }) {
            result.append(Available(id: info.id, name: info.name))
        }
        return result
    }

    /// A translation by id, when it is on this device.
    static func source(for id: String) -> (any ChapterTextSource)? {
        if let package = SealedTranslations.shared.package(id) { return package }
        if let store = stores[id] { return store }
        var url: URL?
        if let pack = AssetPack(translationID: id), pack.translationID != nil {
            let installed = AssetLibrary.installedURL(for: pack)
            if FileManager.default.fileExists(atPath: installed.path) { url = installed }
        }
        if url == nil { url = importedStores().first(where: { $0.0.id == id })?.1 }
        guard let url, let store = try? BibleStore(url: url) else { return nil }
        stores[id] = store
        return store
    }

    /// The reader's translation when it is on the device, else the ASV.
    static var currentTranslationID: String {
        if let id = UserDefaults.standard.string(forKey: "translation"), source(for: id) != nil { return id }
        return ReaderModel.defaultTranslation
    }

    static var currentSource: (any ChapterTextSource)? { source(for: currentTranslationID) }

    private static func importedStores() -> [(TranslationInfo, URL)] {
        let files = (try? FileManager.default.contentsOfDirectory(at: ImportedLibrary.directory,
                                                                  includingPropertiesForKeys: nil)) ?? []
        return files.filter { $0.pathExtension == "sqlite" }.compactMap { url in
            (try? BibleStore(url: url)).map { ($0.info, url) }
        }
    }

    // MARK: Passages

    /// A passage both ways: `kjv` is what is stored and read (`verses(in:)`), `native` is how the
    /// translation numbers it, for showing the reference.
    struct Resolved {
        let kjv: VerseRange
        let native: VerseRange
    }

    /// Reads a passage the way the reader would type it — in the translation's own numbering, in any
    /// of the app's languages — or as OSIS (`John.3.16`, `urn:osis:John.3.16`) or stored keys, both
    /// KJV-numbered.
    static func resolve(_ text: String, in source: any ChapterTextSource) throws -> [Resolved] {
        let numbering = source.numbering
        let resolved: [Resolved]
        switch AppLink.reference(text) {
        case .open(let ranges):
            resolved = ranges.map { Resolved(kjv: $0, native: numbering.nativeRange($0) ?? $0) }
        case .osis(let passages):
            resolved = passages.map { passage in
                let kjv = passage.clamped.range { chapter in
                    let native = numbering.native(forKJV: VerseRef(chapter.book, chapter.chapter, 1).key)
                        .flatMap(VerseRef.init(key:))?.chapterKey ?? chapter
                    return max(1, source.verseCount(native))
                }
                return Resolved(kjv: kjv, native: numbering.nativeRange(kjv) ?? kjv)
            }
        case .passage(let passages):
            resolved = passages.map { passage in
                let native = passage.clamped.range { max(1, source.verseCount($0)) }
                return Resolved(kjv: numbering.kjvRange(native), native: native)
            }
        default:
            throw ScriptureIntentError.passageNotFound(text)
        }
        // A reference past the end of a chapter parses fine and holds nothing.
        let found = resolved.filter { !((try? source.verses(in: $0.kjv)) ?? []).isEmpty }
        guard !found.isEmpty else { throw ScriptureIntentError.passageNotFound(text) }
        return found
    }

    /// "“For God so loved…” — John 3:16 (ASV)", numbered verses when more than one — the same
    /// form the reader copies.
    static func quotation(_ passages: [Resolved], in source: any ChapterTextSource) -> String {
        passages.compactMap { passage -> String? in
            guard let verses = try? source.verses(in: passage.kjv), !verses.isEmpty else { return nil }
            return "\(text(of: verses))\n— \(passage.native.display) (\(source.info.abbreviation))"
        }
        .joined(separator: "\n\n")
    }

    static func text(of verses: [VerseText]) -> String {
        let text = verses.count == 1
            ? verses[0].text
            : verses.map { "\($0.ref.verse) \($0.text)" }.joined(separator: " ")
        return text.replacingOccurrences(of: "¶ ", with: "")
    }

    static func verseCount(_ passages: [Resolved], in source: any ChapterTextSource) -> Int {
        passages.reduce(0) { $0 + ((try? source.verses(in: $1.kjv))?.count ?? 0) }
    }

    /// A stored range as the reader's translation numbers it.
    static func display(_ range: VerseRange) -> String {
        (currentSource?.numbering.nativeRange(range) ?? range).display
    }

    // MARK: Store

    static var context: ModelContext { DataStore.shared.mainContext }

    static func notes() -> [Note] {
        (try? context.fetch(FetchDescriptor<Note>(sortBy: [SortDescriptor(\.updatedAt, order: .reverse)]))) ?? []
    }

    static func note(_ id: UUID) -> Note? {
        var descriptor = FetchDescriptor<Note>(predicate: #Predicate { $0.uuid == id })
        descriptor.fetchLimit = 1
        return try? context.fetch(descriptor).first
    }

    static func favorites() -> [Favorite] {
        (try? context.fetch(FetchDescriptor<Favorite>(sortBy: [SortDescriptor(\.createdAt, order: .reverse)]))) ?? []
    }
}

/// Why an intent could not do what it was asked, in words Siri can say.
nonisolated enum ScriptureIntentError: Error, CustomLocalizedStringResourceConvertible {
    case passageNotFound(String)
    case translationUnavailable(String)
    case quotationNotPermitted(String)
    case imageNotPermitted(String)
    case imageFailed
    case noteNotFound

    var localizedStringResource: LocalizedStringResource {
        switch self {
        case .passageNotFound(let text):
            LocalizedStringResource("Couldn’t find the passage “\(text)”. Try a reference like John 3:16.",
                                    comment: "Siri/Shortcuts error. %@ is what the person asked for.")
        case .translationUnavailable(let id):
            LocalizedStringResource("\(id) isn’t on this device. Open Scripture Alone to download it first.",
                                    comment: "Siri/Shortcuts error. %@ is a Bible translation abbreviation.")
        case .quotationNotPermitted(let id):
            LocalizedStringResource("The publisher of \(id) doesn’t allow quoting that much of it outside the app.",
                                    comment: "Siri/Shortcuts error. %@ is a Bible translation abbreviation.")
        case .imageNotPermitted(let id):
            LocalizedStringResource("The publisher of \(id) doesn’t allow verse images of that passage.",
                                    comment: "Siri/Shortcuts error. %@ is a Bible translation abbreviation.")
        case .imageFailed:
            LocalizedStringResource("The verse image couldn’t be made. Try again.",
                                    comment: "Siri/Shortcuts error when rendering a verse image fails.")
        case .noteNotFound:
            LocalizedStringResource("That note couldn’t be found. It may have been deleted.",
                                    comment: "Siri/Shortcuts error.")
        }
    }
}
