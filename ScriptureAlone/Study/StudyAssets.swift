import BackgroundAssets
import Foundation
import Observation
import System   // FilePath, for reading inside a pack
import ScriptureAloneCore

/// The study databases a reader opts into, delivered as Apple-hosted asset packs.
///
/// **Why not On-Demand Resources.** ODR ties its packs to an app *version*: Apple's own guidance is
/// that "resources are tied to a specific app version as part of the app's submission, and the
/// system does not have any notion of understanding whether the files in an asset pack are
/// identical across app versions." So every update re-downloaded 55 MB of databases that had not
/// changed a byte. It is also deprecated as of iOS 27, with `NSBundleResourceRequest` carrying
/// "Use Background Assets instead."
///
/// **Why the files are copied out.** Background Assets hands back `Data` or a file descriptor —
/// there is no API for the path of a file inside a pack, and SQLite needs a path. So a pack is
/// downloaded once, its database copied into Application Support, and the pack then released. The
/// copy is what the app opens from then on, which has a second benefit worth more than the
/// mechanism: the database is now ordinary app-container data, so it survives every future update
/// and is never fetched twice. A reader downloads the commentary once, for good.
///
/// Reading is never one of these. All three translations, the maps, the timeline and the cross
/// references are inside the app, so a fresh install reads scripture offline with no network.
enum StudyPack: String, CaseIterable, Sendable {
    case commentary
    case interlinear

    /// The asset-pack identifier as uploaded to App Store Connect. Must match `assetPackID` in
    /// `Tools/asset-packs/<name>.json`. Plain names, not reverse-DNS: App Store Connect rejects an
    /// identifier containing dots, and the identifier is scoped to the app already.
    var id: String { rawValue }

    /// The file inside the pack, and the name it keeps once copied out.
    var file: String {
        switch self {
        case .commentary: "Study.sqlite"
        case .interlinear: "Interlinear.sqlite"
        }
    }

    var title: String {
        switch self {
        case .commentary: "Commentary"
        case .interlinear: "Original Languages"
        }
    }

    /// What the reader is waiting for, in their terms.
    var explanation: String {
        switch self {
        case .commentary:
            "Calvin, Gill and Jamieson-Fausset-Brown — about 44 MB, downloaded once and kept."
        case .interlinear:
            "The Hebrew and Greek behind every word, with a lexicon — about 11 MB, downloaded once "
                + "and kept."
        }
    }
}

@MainActor
@Observable
final class StudyAssetLibrary {
    static let shared = StudyAssetLibrary()

    enum State: Equatable {
        case absent
        case downloading(Double)
        case ready
        case failed(String)
    }

    private(set) var states: [StudyPack: State] = [:]

    /// Where a database lives once it has been copied out of its pack. Application Support rather
    /// than Caches: the system may evict Caches under pressure, and a reader who downloaded 44 MB
    /// of commentary should not silently lose it.
    static func installedURL(for pack: StudyPack) -> URL {
        let base = URL.applicationSupportDirectory.appending(path: "Study")
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base.appending(path: pack.file)
    }

    private init() {
        for pack in StudyPack.allCases {
            states[pack] = url(of: pack) == nil ? .absent : .ready
        }
    }

    func state(of pack: StudyPack) -> State { states[pack] ?? .absent }

    func isReady(_ pack: StudyPack) -> Bool { state(of: pack) == .ready }

    /// The database's path, or nil when it has not been installed yet.
    func url(of pack: StudyPack) -> URL? {
        let url = Self.installedURL(for: pack)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    /// Downloads the pack if the database isn't already installed, copies it out, and releases the
    /// pack. Safe to call every time the feature is opened: an installed database returns at once.
    @discardableResult
    func ensure(_ pack: StudyPack) async -> Bool {
        if url(of: pack) != nil { states[pack] = .ready; return true }
        if case .downloading = state(of: pack) { return false }

        states[pack] = .downloading(0)
        let progress = Task { [weak self] in
            for await update in AssetPackManager.shared.statusUpdates(forAssetPackWithID: pack.id) {
                guard let self else { return }
                // `Progress`, not a fraction — read `fractionCompleted` off it.
                if case .downloading(_, let progress) = update,
                   case .downloading = self.state(of: pack) {
                    self.states[pack] = .downloading(progress.fractionCompleted)
                }
            }
        }
        defer { progress.cancel() }

        do {
            let assetPack = try await AssetPackManager.shared.assetPack(withID: pack.id)
            try await AssetPackManager.shared.ensureLocalAvailability(of: assetPack)
            try install(pack)
            // The database is ours now; the pack's copy is 44 MB of duplicate.
            try? await AssetPackManager.shared.remove(assetPackWithID: pack.id)
            states[pack] = .ready
            return true
        } catch {
            states[pack] = .failed(Self.message(for: error, pack: pack))
            return false
        }
    }

    /// Copies the database out of the pack, writing beside the destination and moving into place so
    /// a cancelled or failed copy can never leave a half-written database to be opened.
    private func install(_ pack: StudyPack) throws {
        let destination = Self.installedURL(for: pack)
        let temporary = destination.deletingLastPathComponent()
            .appending(path: ".\(pack.file).partial")
        try? FileManager.default.removeItem(at: temporary)

        let source = try AssetPackManager.shared.descriptor(for: FilePath(pack.file),
                                                            searchingInAssetPackWithID: pack.id)
        defer { try? source.close() }
        FileManager.default.createFile(atPath: temporary.path, contents: nil)
        let sink = try FileHandle(forWritingTo: temporary)
        defer { try? sink.close() }

        // Streamed in chunks: a 44 MB database read whole would be 44 MB of resident memory for as
        // long as the write takes, on a device that may have little to spare.
        let chunk = 4 * 1024 * 1024
        var buffer = [UInt8](repeating: 0, count: chunk)
        while true {
            let read = try buffer.withUnsafeMutableBytes { try source.read(into: $0) }
            if read == 0 { break }
            try sink.write(contentsOf: Data(buffer[0..<read]))
        }
        try? sink.close()
        try? FileManager.default.removeItem(at: destination)
        try FileManager.default.moveItem(at: temporary, to: destination)
        // Someone else's 44 MB of commentary is not worth backing up to iCloud; it can be fetched
        // again for nothing.
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var mutable = destination
        try? mutable.setResourceValues(values)
    }

    /// Removes an installed database. For a reader reclaiming space.
    func remove(_ pack: StudyPack) {
        try? FileManager.default.removeItem(at: Self.installedURL(for: pack))
        states[pack] = .absent
    }

    private static func message(for error: any Error, pack: StudyPack) -> String {
        // The pack isn't on App Store Connect for this build — the one failure a reader can do
        // nothing about, so it says so rather than offering a retry that cannot succeed.
        if case ManagedBackgroundAssetsError.assetPackNotFound = error {
            return "\(pack.title) isn't available for this version of the app yet."
        }
        // Reachability comes through as a URL error, not a Background Assets one; `BAError`'s own
        // codes are all about scheduling and allowances, none of which a reader can act on.
        let nsError = error as NSError
        if nsError.domain == NSURLErrorDomain,
           [NSURLErrorNotConnectedToInternet, NSURLErrorNetworkConnectionLost,
            NSURLErrorTimedOut].contains(nsError.code) {
            return "\(pack.title) needs a connection to download."
        }
        return "\(pack.title) couldn't be downloaded. \(error.localizedDescription)"
    }
}
