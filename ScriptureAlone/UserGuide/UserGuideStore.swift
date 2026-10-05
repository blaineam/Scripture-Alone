import Foundation
import Observation
import SwiftUI
import ScriptureAloneCore

/// The User Guide's one copy on this device: downloaded the first time the reader opens the guide
/// (never at launch), kept in Application Support, and replaced in the background when the
/// `user-guide` release publishes a newer package.
///
/// Packages come from the project's GitHub release through an ephemeral session — no cookies, no
/// cache, nothing that identifies the reader — and are installed only when their SHA-256 matches
/// the release's index.
@Observable
final class UserGuideStore {
    enum Phase: Equatable {
        /// Nothing asked for yet.
        case idle
        /// Fetching a first copy; the fraction done when it is known.
        case downloading(Double?)
        case ready
        /// The first copy couldn't be fetched. The guide offers Try Again — never a spinner forever.
        case failed
    }

    static let shared = UserGuideStore()

    /// The package language: the app's own language, or English.
    let language: String
    /// This device's edition of it (`en`, `ipad-en`, `mac-en`): the guide speaks of the reader's
    /// own device.
    let edition: String
    private(set) var phase = Phase.idle
    private(set) var guide: UserGuide?
    /// The package's size from the index, shown while it downloads.
    private(set) var downloadSize: Int?

    @ObservationIgnored private let root: URL
    @ObservationIgnored private let session: URLSession
    @ObservationIgnored private var task: Task<Void, Never>?
    /// The index is asked once per launch, the first time the guide is opened.
    @ObservationIgnored private var checkedForUpdate = false
    @ObservationIgnored private var images: [String: Image] = [:]

    init(language: String = UserGuideStore.preferredLanguage,
         device: UserGuidePackage.Device = UserGuideStore.currentDevice,
         root: URL = UserGuideStore.defaultRoot,
         session: URLSession = UserGuideStore.makeSession()) {
        self.language = language
        self.edition = UserGuidePackage.edition(language: language, device: device)
        self.root = root
        self.session = session
    }

    /// Where this edition's copy is unpacked.
    var directory: URL { root.appending(path: edition, directoryHint: .isDirectory) }

    /// Called when the guide is shown: shows a held copy at once (and looks for a newer one
    /// behind it), or fetches the first copy.
    func open() {
        if guide == nil, let held = UserGuidePackage.load(from: directory) {
            guide = held
            phase = .ready
        }
        if guide != nil {
            refreshInBackground()
        } else if task == nil {
            download()
        }
    }

    func retry() {
        guard task == nil else { return }
        download()
    }

    /// A framed screenshot from the package's `images/`.
    func image(named name: String) -> Image? {
        if let cached = images[name] { return cached }
        let url = directory.appending(path: "images").appending(path: name)
        #if os(iOS)
        guard let platform = UIImage(contentsOfFile: url.path) else { return nil }
        let image = Image(uiImage: platform)
        #else
        guard let platform = NSImage(contentsOf: url) else { return nil }
        let image = Image(nsImage: platform)
        #endif
        images[name] = image
        return image
    }

    // MARK: Fetching

    private func download() {
        phase = .downloading(nil)
        task = Task {
            defer { task = nil }
            do {
                let index = try await Self.fetchIndex(session: session)
                let name = UserGuidePackage.entryName(edition: edition, language: language, index: index)
                guard let entry = index.packages[name] else { throw URLError(.fileDoesNotExist) }
                downloadSize = entry.size
                let data = try await Self.fetch(UserGuidePackage.packageURL(language: name), session: session,
                                                expected: entry.size) { fraction in
                    Task { @MainActor in self.progressed(fraction) }
                }
                let installed = try await Self.install(data, sha256: entry.sha256, into: directory)
                images = [:]
                guide = installed
                phase = .ready
                checkedForUpdate = true
            } catch {
                phase = .failed
            }
        }
    }

    private func progressed(_ fraction: Double) {
        if case .downloading = phase { phase = .downloading(fraction) }
    }

    /// Swaps in a newer package when the index lists one. Quiet: a failure keeps the copy held.
    private func refreshInBackground() {
        guard !checkedForUpdate, task == nil else { return }
        checkedForUpdate = true
        let held = try? String(contentsOf: Self.hashFile(in: directory), encoding: .utf8)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        task = Task {
            defer { task = nil }
            do {
                let index = try await Self.fetchIndex(session: session)
                let name = UserGuidePackage.entryName(edition: edition, language: language, index: index)
                guard UserGuidePackage.needsUpdate(held: held, index: index, language: name),
                      let entry = index.packages[name] else { return }
                let data = try await Self.fetch(UserGuidePackage.packageURL(language: name), session: session,
                                                expected: entry.size) { _ in }
                let installed = try await Self.install(data, sha256: entry.sha256, into: directory)
                images = [:]
                guide = installed
            } catch {
                // Keep reading the copy on hand; the next launch asks again.
            }
        }
    }

    enum InstallError: Error { case checksumMismatch }

    /// Checks a downloaded package against the index, unpacks it over the held copy and records
    /// its hash beside it.
    @concurrent
    nonisolated static func install(_ data: Data, sha256: String, into directory: URL) async throws -> UserGuide {
        guard UserGuidePackage.verify(data, sha256: sha256) else { throw InstallError.checksumMismatch }
        let guide = try UserGuidePackage.unpack(data, into: directory)
        try Data(sha256.lowercased().utf8).write(to: hashFile(in: directory), options: .atomic)
        // It downloads again if lost, so it stays out of device backups.
        var root = directory.deletingLastPathComponent()
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? root.setResourceValues(values)
        return guide
    }

    nonisolated static func hashFile(in directory: URL) -> URL {
        directory.appending(path: ".sha256")
    }

    @concurrent
    nonisolated static func fetchIndex(session: URLSession) async throws -> UserGuidePackage.Index {
        let data = try await fetch(UserGuidePackage.indexURL, session: session, expected: nil) { _ in }
        return try JSONDecoder().decode(UserGuidePackage.Index.self, from: data)
    }

    @concurrent
    nonisolated static func fetch(_ url: URL, session: URLSession, expected: Int?,
                                  progress: @escaping @Sendable (Double) -> Void) async throws -> Data {
        #if DEBUG
        // `-userGuideOffline YES`: every fetch fails as it would with no connection.
        if UserDefaults.standard.bool(forKey: "userGuideOffline") || UITestMode.isOn { throw URLError(.notConnectedToInternet) }
        #endif
        var request = URLRequest(url: url)
        request.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        request.httpShouldHandleCookies = false
        let (bytes, response) = try await session.bytes(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw URLError(.badServerResponse)
        }
        let total = expected ?? Int(http.expectedContentLength)
        var data = Data()
        if total > 0 { data.reserveCapacity(total) }
        var reported = 0
        for try await byte in bytes {
            data.append(byte)
            if total > 0, data.count - reported >= 32_768 {
                reported = data.count
                progress(min(1, Double(data.count) / Double(total)))
            }
        }
        return data
    }

    // MARK: Defaults

    nonisolated static var preferredLanguage: String {
        #if DEBUG
        // `-userGuideLanguage ja`, for checking a package's layout.
        if let forced = UserDefaults.standard.string(forKey: "userGuideLanguage"),
           UserGuidePackage.languages.contains(forced) {
            return forced
        }
        #endif
        return UserGuidePackage.language(for: Bundle.main.preferredLocalizations)
    }

    /// The device the guide speaks to: the Mac edition on a Mac (this app, or an iPhone/iPad build
    /// running there), the iPad edition on an iPad, the iPhone edition otherwise.
    static var currentDevice: UserGuidePackage.Device {
        #if os(macOS)
        return .mac
        #else
        if ProcessInfo.processInfo.isiOSAppOnMac || ProcessInfo.processInfo.isMacCatalystApp { return .mac }
        return UIDevice.current.userInterfaceIdiom == .pad ? .ipad : .iphone
        #endif
    }

    nonisolated static var defaultRoot: URL {
        URL.applicationSupportDirectory.appending(path: "UserGuide", directoryHint: .isDirectory)
    }

    /// No cookies, no cache, no stored credentials: nothing that could tell one reader from another.
    nonisolated static func makeSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.httpCookieAcceptPolicy = .never
        configuration.urlCredentialStorage = nil
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        configuration.waitsForConnectivity = false
        // A dead connection ends in Try Again, not an endless wait.
        configuration.timeoutIntervalForRequest = 20
        configuration.timeoutIntervalForResource = 90
        return URLSession(configuration: configuration)
    }
}
