import BackgroundAssets
import Foundation
import Observation
import System   // FilePath, for reading inside a pack
import ScriptureAloneCore

/// Content delivered as Apple-hosted Background Assets packs rather than inside the app.
///
/// **What ships where.** The app binary carries the code, the maps and timeline data, the cross
/// reference index's neighbours and the pinned signing key — nothing else heavy. The Bibles and
/// the study databases are packs:
///
/// | Pack | Policy | Why |
/// |---|---|---|
/// | ASV | `essential`, first installation | The default translation. It arrives with the install, so a
///   fresh install reads offline before it has ever reached a network. |
/// | BSB, KJV | `onDemand` | Fetched the first time the reader chooses one. |
/// | Commentary, Original Languages | `onDemand` | 55 MB many readers never open. |
///
/// **Why not On-Demand Resources.** ODR ties a pack to an app *version*: Apple's own guidance is
/// that "resources are tied to a specific app version as part of the app's submission, and the
/// system does not have any notion of understanding whether the files in an asset pack are
/// identical across app versions." Every update re-downloaded content that had not changed a byte.
/// ODR is also deprecated as of iOS 27, with `NSBundleResourceRequest` carrying "Use Background
/// Assets instead."
///
/// **Why the files are copied out.** Background Assets hands back `Data` or a file descriptor —
/// there is no API for the path of a file inside a pack, and both SQLite and the package reader need
/// a path. So a pack is downloaded once, its file copied into Application Support, and the pack then
/// released. The copy is ordinary app-container data from then on: it survives every future update
/// and is never fetched twice. That is also why the ASV's `essential` policy covers first
/// installation only — with `subsequentUpdate` too, releasing the pack would make every app update
/// download it again, which is precisely the problem this replaced.
enum AssetPack: String, CaseIterable, Sendable {
    case asv
    case bsb
    case kjv
    case commentary
    case interlinear

    /// The asset-pack identifier as uploaded to App Store Connect. Must match `assetPackID` in
    /// `Tools/asset-packs/<id>.json`. Plain names, not reverse-DNS: App Store Connect rejects an
    /// identifier containing dots, and the identifier is scoped to the app already.
    ///
    /// The study packs are `study-commentary` and `study-interlinear`, not `commentary` and
    /// `interlinear`: those two were archived while the databases were briefly bundled, and App
    /// Store Connect allows no change to an archived pack — not a new version, not unarchiving —
    /// so the old identifiers can never carry content again.
    var id: String {
        switch self {
        case .commentary: "study-commentary"
        case .interlinear: "study-interlinear"
        default: rawValue
        }
    }

    /// The pack for a bundled translation, or nil for anything else.
    init?(translationID: String) {
        switch translationID {
        case "ASV": self = .asv
        case "BSB": self = .bsb
        case "KJV": self = .kjv
        default: return nil
        }
    }

    /// The file inside the pack (at its root — the manifests set `fileDestination`), and the name
    /// it keeps once copied out.
    var file: String {
        switch self {
        case .asv: "ASV.sabible"
        case .bsb: "BSB.sqlite"
        case .kjv: "KJV.sqlite"
        case .commentary: "Study.sqlite"
        case .interlinear: "Interlinear.sqlite"
        }
    }

    var title: String {
        switch self {
        case .asv: "American Standard Version"
        case .bsb: "Berean Standard Bible"
        case .kjv: "King James Version"
        case .commentary: "Commentary"
        case .interlinear: "Original Languages"
        }
    }

    /// Roughly what the reader downloads, for the sheet that asks first.
    var megabytes: Int {
        switch self {
        case .asv: 16
        case .bsb: 15
        case .kjv: 15
        case .commentary: 44
        case .interlinear: 11
        }
    }

    /// What the reader is waiting for, in their terms.
    var explanation: String {
        switch self {
        case .asv, .bsb, .kjv:
            "About \(megabytes) MB, downloaded once and kept for reading offline."
        case .commentary:
            "Calvin, Gill and Jamieson-Fausset-Brown — about \(megabytes) MB, downloaded once and kept."
        case .interlinear:
            "The Hebrew and Greek behind every word, with a lexicon — about \(megabytes) MB, "
                + "downloaded once and kept."
        }
    }
}

@MainActor
@Observable
final class AssetLibrary {
    static let shared = AssetLibrary()

    enum State: Equatable {
        case absent
        case downloading(Double)
        case ready
        case failed(String)
    }

    private(set) var states: [AssetPack: State] = [:]

    /// Where a database lives once it has been copied out of its pack. Application Support rather
    /// than Caches: the system may evict Caches under pressure, and a reader who downloaded 44 MB
    /// of commentary should not silently lose it.
    static func installedURL(for pack: AssetPack) -> URL {
        let base = URL.applicationSupportDirectory.appending(path: "Assets")
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base.appending(path: pack.file)
    }

    private init() {
        for pack in AssetPack.allCases {
            states[pack] = url(of: pack) == nil ? .absent : .ready
        }
    }

    func state(of pack: AssetPack) -> State { states[pack] ?? .absent }

    func isReady(_ pack: AssetPack) -> Bool { state(of: pack) == .ready }

    /// The database's path, or nil when it has not been installed yet.
    func url(of pack: AssetPack) -> URL? {
        let url = Self.installedURL(for: pack)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    /// Downloads the pack if the database isn't already installed, copies it out, and releases the
    /// pack. Safe to call every time the feature is opened: an installed database returns at once.
    @discardableResult
    func ensure(_ pack: AssetPack) async -> Bool {
        if url(of: pack) != nil { states[pack] = .ready; return true }
        #if DEBUG
        if installFromBundle(pack) { return true }
        #endif
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
            // The file is ours now; keeping the pack as well would store it twice.
            try? await AssetPackManager.shared.remove(assetPackWithID: pack.id)
            states[pack] = .ready
            return true
        } catch {
            states[pack] = .failed(Self.message(for: error, pack: pack))
            return false
        }
    }

    /// Copies a pack's file out *synchronously* when the pack is already on the device.
    ///
    /// For the ASV at launch. It is an `essential` pack, so after installation it is local and no
    /// network is involved — which is what lets a fresh install read offline on its very first
    /// launch without the reader waiting on an asynchronous download. The copy is about 16 MB and
    /// happens once; every later launch finds the file and returns at once. Returns false when the
    /// pack is not local, and the caller falls back to `ensure`.
    @discardableResult
    func installIfLocal(_ pack: AssetPack) -> Bool {
        if url(of: pack) != nil { states[pack] = .ready; return true }
        do {
            try install(pack)
        } catch {
            #if DEBUG
            return installFromBundle(pack)
            #else
            return false
            #endif
        }
        states[pack] = .ready
        Task { try? await AssetPackManager.shared.remove(assetPackWithID: pack.id) }
        return true
    }

    #if DEBUG
    /// Development builds have no asset packs — those exist only for App Store and TestFlight
    /// builds — so a build run from Xcode would open with no Bible at all. A Debug-only build phase
    /// copies the pack files into the app bundle, and this installs from there.
    ///
    /// **Debug only, deliberately.** A fallback like this in Release would hide a broken asset-pack
    /// path behind a working bundled copy, so Release never has it and is validated against App
    /// Store Connect instead.
    private func installFromBundle(_ pack: AssetPack) -> Bool {
        let name = (pack.file as NSString).deletingPathExtension
        let ext = (pack.file as NSString).pathExtension
        guard let source = Bundle.main.url(forResource: name, withExtension: ext) else { return false }
        let destination = Self.installedURL(for: pack)
        try? FileManager.default.removeItem(at: destination)
        guard (try? FileManager.default.copyItem(at: source, to: destination)) != nil else { return false }
        states[pack] = .ready
        return true
    }
    #endif

    /// Copies the database out of the pack, writing beside the destination and moving into place so
    /// a cancelled or failed copy can never leave a half-written database to be opened.
    private func install(_ pack: AssetPack) throws {
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

    /// Forgets a failed translation download once the reader has moved on to another, so its
    /// "couldn't download" banner doesn't outlive the choice it was about.
    func clearFailedTranslations(except pack: AssetPack?) {
        for other in [AssetPack.bsb, .kjv] where other != pack {
            if case .failed = states[other] { states[other] = .absent }
        }
    }

    /// Removes an installed database. For a reader reclaiming space.
    func remove(_ pack: AssetPack) {
        try? FileManager.default.removeItem(at: Self.installedURL(for: pack))
        states[pack] = .absent
    }

    private static func message(for error: any Error, pack: AssetPack) -> String {
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
