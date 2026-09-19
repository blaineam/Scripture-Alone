import Foundation
import Observation
import ScriptureAloneCore

/// The translations the reader has added themselves.
///
/// Imported texts live in Application Support, outside the iCloud-synced containers: an imported
/// translation belongs to the person who imported it, on the device they imported it to, and the
/// app never copies it anywhere — not to iCloud, not into a keepsake, not into a share link.
@MainActor
@Observable
final class ImportedLibrary {
    struct Entry: Identifiable, Hashable {
        let info: TranslationInfo
        let url: URL
        var id: String { info.id }
    }

    private(set) var entries: [Entry] = []
    /// Set while an import is running, for the sheet's progress.
    private(set) var busy: String?

    static let directoryName = "Translations"

    /// `~/Library/Application Support/Translations`, created on first use.
    static var directory: URL {
        let base = URL.applicationSupportDirectory.appending(path: directoryName)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base
    }

    init() { reload() }

    func reload() {
        let files = (try? FileManager.default.contentsOfDirectory(
            at: Self.directory, includingPropertiesForKeys: nil)) ?? []
        entries = files
            .filter { $0.pathExtension == "sqlite" }
            .compactMap { url in
                // A store that won't open is a half-written import; skip it rather than crash,
                // and leave the file for `remove` to clear.
                guard let store = try? BibleStore(url: url) else { return nil }
                return Entry(info: store.info, url: url)
            }
            .sorted { $0.info.name.localizedCaseInsensitiveCompare($1.info.name) == .orderedAscending }
    }

    /// Reads a file the reader picked and writes a store beside the others.
    ///
    /// The parse runs off the main actor: a whole Bible takes seconds, and this is called from a
    /// sheet that must stay responsive.
    func importFile(at url: URL, named name: String? = nil,
                    as identity: ImportedTranslationIdentity? = nil) async throws -> BibleImportResult {
        busy = name ?? url.deletingPathExtension().lastPathComponent
        defer { busy = nil }
        let directory = Self.directory
        // Files handed over by the document picker are outside our sandbox until asked.
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let result = try await Task.detached(priority: .userInitiated) {
            try BibleFileImporter().importBible(at: url, as: identity, into: directory)
        }.value
        reload()
        return result
    }

    func remove(_ entry: Entry) {
        try? FileManager.default.removeItem(at: entry.url)
        reload()
    }

    func contains(_ id: String) -> Bool { entries.contains { $0.info.id == id } }
}
