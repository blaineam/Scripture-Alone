import Foundation
import Observation
import ScriptureAloneCore

/// The study databases that download when a reader first asks for them.
///
/// Reading is never on demand: all three translations, the maps, the timeline and the cross
/// references are inside the app, so a fresh install reads scripture offline with no network at
/// all. These two are the heavy study aids — commentary is 43.6 MB and the interlinear is 10.7 MB
/// — and together they are more than half the download for features many readers never open.
///
/// The system may purge a downloaded pack when storage runs low, so nothing here assumes that a
/// pack fetched once stays fetched. Every access re-requests it; a present pack resolves
/// immediately, and a purged one downloads again.
enum OnDemandPack: String, CaseIterable, Sendable {
    case commentary
    case interlinear

    var tag: String { rawValue }

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

    /// The resource inside the pack, so a caller can check before asking.
    var resourceName: (name: String, extension: String) {
        switch self {
        case .commentary: ("Study", "sqlite")
        case .interlinear: ("Interlinear", "sqlite")
        }
    }
}

@MainActor
@Observable
final class OnDemandLibrary {
    static let shared = OnDemandLibrary()

    enum State: Equatable {
        case absent
        case downloading(Double)
        case ready
        case failed(String)
    }

    private(set) var states: [OnDemandPack: State] = [:]
    /// Held for the life of the app: releasing the request tells the system the pack may be purged,
    /// and a store with an open file handle to a purged file is a crash waiting to happen.
    private var requests: [OnDemandPack: NSBundleResourceRequest] = [:]

    private init() {
        for pack in OnDemandPack.allCases {
            states[pack] = Bundle.main.url(forResource: pack.resourceName.name,
                                           withExtension: pack.resourceName.extension) == nil
                ? .absent : .ready
        }
    }

    func state(of pack: OnDemandPack) -> State { states[pack] ?? .absent }

    func isReady(_ pack: OnDemandPack) -> Bool { state(of: pack) == .ready }

    /// Makes a pack available, downloading it if the system doesn't have it.
    ///
    /// Safe to call every time the feature is opened: `conditionallyBeginAccessingResources`
    /// answers immediately when the pack is already on the device, so the common case costs
    /// nothing and the purged case quietly downloads again.
    @discardableResult
    func ensure(_ pack: OnDemandPack) async -> Bool {
        if case .downloading = state(of: pack) { return false }
        let request = requests[pack] ?? NSBundleResourceRequest(tags: [pack.tag])
        requests[pack] = request

        if await request.conditionallyBeginAccessingResources() {
            states[pack] = .ready
            return true
        }

        states[pack] = .downloading(0)
        let observation = request.progress.observe(\.fractionCompleted) { [weak self] progress, _ in
            Task { @MainActor in
                guard let self, case .downloading = self.state(of: pack) else { return }
                self.states[pack] = .downloading(progress.fractionCompleted)
            }
        }
        defer { observation.invalidate() }

        do {
            try await request.beginAccessingResources()
            states[pack] = .ready
            return true
        } catch {
            requests[pack] = nil
            states[pack] = .failed(Self.message(for: error, pack: pack))
            return false
        }
    }

    /// The URL of a pack's database, or nil when it isn't on the device yet.
    func url(of pack: OnDemandPack) -> URL? {
        Bundle.main.url(forResource: pack.resourceName.name, withExtension: pack.resourceName.extension)
    }

    private static func message(for error: any Error, pack: OnDemandPack) -> String {
        let code = (error as NSError).code
        // NSBundleOnDemandResourceOutOfSpaceError / ...ExceededMaximumSizeError have unhelpful
        // default descriptions; a reader needs to know which of the two it is.
        switch code {
        case NSBundleOnDemandResourceOutOfSpaceError:
            return "There isn't enough space for \(pack.title). Free some up and try again."
        case NSBundleOnDemandResourceInvalidTagError:
            return "\(pack.title) isn't available in this build."
        default:
            return "\(pack.title) couldn't be downloaded. \(error.localizedDescription)"
        }
    }
}
