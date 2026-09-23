import Foundation
import ScriptureAloneCore
#if os(iOS)
import UIKit
#else
import AppKit
#endif

/// Keeps the reader's settings in iCloud, so a reinstall — or a new iPhone, iPad or Mac — opens the
/// way they left it: theme, accent colour, typeface, the API.Bible translations they picked, and the
/// rest of the list below.
///
/// `UserDefaults` stays the only thing the app reads. `@AppStorage` and every direct read keep
/// working unchanged; this object mirrors the settings below into one JSON blob in the iCloud
/// key-value store (`NSUbiquitousKeyValueStore`) and applies the blob back. The merge rules —
/// restore on a fresh install, newest change wins per setting, no echoes — are
/// `SettingsSyncEngine` in ScriptureAloneCore, where they are unit-tested.
///
/// Not here: notes, highlights and favourites (SwiftData + CloudKit), the reading position and the
/// keepsake and family-sharing flags (already their own key-value-store keys: `position`,
/// `legacy.bibleID`, `family.*`), and the API keys — a key is a credential, so it lives in the
/// keychain as an iCloud Keychain item (`OnlineTranslationKeys`) and never in this blob.
///
/// ## Every `UserDefaults` key, classified
///
/// | Key | | Why |
/// |---|---|---|
/// | `reader.theme`, `reader.accent`, `reader.fontFamily`, `reader.lineSpacing`, `reader.layout` | SYNC | reader style |
/// | `reader.redLetters`, `reader.verseNumbers`, `reader.headings`, `reader.footnotes` | SYNC | reader style |
/// | `reader.fontSize` | SYNC per device kind | a phone and a Mac want different sizes; each gets its own back |
/// | `reader.compareWith`, `reader.autoScrollSpeed` | SYNC | reader choices |
/// | `translation` | SYNC per device kind | the Bibles offered follow each device's languages, so another kind of device's pick may not exist here |
/// | `recent`, `recentSearches` | SYNC | recent chapters and searches (12 each) |
/// | `study.commentarySource` | SYNC | study preference |
/// | `listen.speed`, `listen.continue` | SYNC | read-aloud preferences |
/// | `listen.voice`, `listen.studioVoice`, `listen.engine` | local | system voices differ per device; Studio needs Mi Speaks installed here |
/// | `share.template`, `share.aspect`, `share.fontFamily`, `share.alignment`, `share.redLetters`, `share.verseNumbers`, `share.wordmark` | SYNC | share-card design |
/// | `spotlight.notes`, `spotlight.favorites` | SYNC | the reader's Spotlight toggles |
/// | `spotlight.indexed.notes`, `spotlight.indexed.favorites` | local | fingerprints of this device's index |
/// | `legacy.ownerName`, `legacy.dedication`, `legacy.translation` | SYNC | keepsake defaults |
/// | `onlineTranslations` | SYNC | the API.Bible translations picked — public ids and names, no key |
/// | `position` | own KVS key | synced by `ReaderModel` already |
/// | `legacy.bibleID`, `family.owner.sharing`, `family.participant.active` | own KVS keys | synced already |
/// | `family.sharedSubscription`, `debug.cloudKitSchemaBootstrapped.v1` | local | one-shot, per install |
/// | `watch.translationChangedAt` | local | this phone's hand-off with its paired watch |
/// | `settingsSync.ledger`, `settingsSync.origin` | local | this object's own bookkeeping |
/// | App Group `reader.redLetters` | derived | rewritten from the synced value by `WidgetSnapshotSync` |
///
/// A setting added to the app is local until it is added to `settings`.
///
/// ## Timing
///
/// `start()` runs in `App.init`, before the first view reads a setting. It reads the key-value
/// store's on-device cache and never waits on the network. On a reinstall the blob usually arrives
/// a moment later (`didChangeExternallyNotification`); the settings are applied then — `@AppStorage`
/// views update on their own, and `restoredNotification` lets the reader pick up the restored
/// translation and API.Bible picks. Until this install has seen a blob it pushes nothing for the
/// first `firstPushDelay`, so values the app writes for itself at first launch can't overwrite
/// the reader's own settings in iCloud before those have had a chance to arrive.
@MainActor
final class SettingsSync {
    static let shared = SettingsSync()

    static let settings: [SyncedSetting] = [
        SyncedSetting(SettingsKey.theme, .string),
        SyncedSetting(SettingsKey.accent, .string),
        SyncedSetting(SettingsKey.fontFamily, .string),
        SyncedSetting(SettingsKey.fontSize, .double, perDeviceKind: true),
        SyncedSetting(SettingsKey.lineSpacing, .double),
        SyncedSetting(SettingsKey.layout, .string),
        SyncedSetting(SettingsKey.redLetters, .bool),
        SyncedSetting(SettingsKey.verseNumbers, .bool),
        SyncedSetting(SettingsKey.headings, .bool),
        SyncedSetting(SettingsKey.footnotes, .bool),
        SyncedSetting(SettingsKey.compareTranslation, .string),
        SyncedSetting(SettingsKey.autoScrollSpeed, .double),
        SyncedSetting(SettingsKey.listenSpeed, .double),
        SyncedSetting(SettingsKey.listenContinue, .bool),
        SyncedSetting("translation", .string, perDeviceKind: true),
        SyncedSetting("recent", .ints),
        SyncedSetting("recentSearches", .strings),
        SyncedSetting("study.commentarySource", .string),
        SyncedSetting(ShareSettingsKey.template, .string),
        SyncedSetting(ShareSettingsKey.aspect, .string),
        SyncedSetting(ShareSettingsKey.family, .string),
        SyncedSetting(ShareSettingsKey.alignment, .string),
        SyncedSetting(ShareSettingsKey.redLetters, .bool),
        SyncedSetting(ShareSettingsKey.verseNumbers, .bool),
        SyncedSetting(ShareSettingsKey.wordmark, .bool),
        SyncedSetting(SpotlightSettingsKey.notes, .bool),
        SyncedSetting(SpotlightSettingsKey.favorites, .bool),
        SyncedSetting("legacy.ownerName", .string),
        SyncedSetting("legacy.dedication", .string),
        SyncedSetting("legacy.translation", .string),
        SyncedSetting(OnlineCatalog.storageKey, .data),
    ]

    /// The key-value-store key holding the blob.
    static let blobKey = "settings.v1"
    private static let ledgerKey = "settingsSync.ledger"
    private static let originKey = "settingsSync.origin"
    private static let firstPushDelay: Duration = .seconds(20)

    /// Posted after settings arrived from iCloud and were applied. `userInfo["keys"]` is the
    /// `UserDefaults` keys changed; `userInfo["initial"]` is true for the restore onto an install
    /// that had never synced — a reinstall or a new device.
    static let restoredNotification = Notification.Name("SettingsSync.restored")

    private let defaults = UserDefaults.standard
    private let cloud = NSUbiquitousKeyValueStore.default
    private var started = false
    private var startedAt = ContinuousClock.now
    private var pendingLocal: Task<Void, Never>?
    private var deferredPush: Task<Void, Never>?
    private var observers: [NSObjectProtocol] = []

    /// This device's kind, for the settings kept per kind of device.
    private let deviceKind: String = {
        #if os(iOS)
        if ProcessInfo.processInfo.isiOSAppOnMac { return "mac" }
        return UIDevice.current.userInterfaceIdiom == .pad ? "pad" : "phone"
        #else
        return "mac"
        #endif
    }()

    /// A random id for this install, which only breaks ties between two changes made in the same
    /// instant. Deliberately not a device identifier.
    private lazy var origin: String = {
        if let id = defaults.string(forKey: Self.originKey) { return id }
        let id = UUID().uuidString
        defaults.set(id, forKey: Self.originKey)
        return id
    }()

    func start() {
        guard !started else { return }
        started = true
        startedAt = .now
        #if DEBUG
        seedFromLaunchArguments()
        #endif
        // Loads the on-device cache; the network is never waited on.
        cloud.synchronize()
        reconcile(from: .launch)

        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: NSUbiquitousKeyValueStore.didChangeExternallyNotification,
                                            object: cloud, queue: .main) { [weak self] note in
            let reason = note.userInfo?[NSUbiquitousKeyValueStoreChangeReasonKey] as? Int
            let keys = note.userInfo?[NSUbiquitousKeyValueStoreChangedKeysKey] as? [String] ?? []
            MainActor.assumeIsolated { self?.cloudChanged(reason: reason, keys: keys) }
        })
        observers.append(center.addObserver(forName: UserDefaults.didChangeNotification,
                                            object: defaults, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.localChanged() }
        })
        #if os(iOS)
        let resign = UIApplication.willResignActiveNotification
        #else
        let resign = NSApplication.willResignActiveNotification
        #endif
        observers.append(center.addObserver(forName: resign, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.flush() }
        })
    }

    // MARK: Changes

    private enum Trigger { case launch, local, cloud, deferred }

    /// A setting changed here — or anything else in `UserDefaults` did; the reading position is
    /// saved as the reader scrolls. Coalesced, and cheap: one pass reads a few dozen keys.
    private func localChanged() {
        pendingLocal?.cancel()
        pendingLocal = Task { [weak self] in
            try? await Task.sleep(for: .seconds(1))
            guard !Task.isCancelled else { return }
            self?.pendingLocal = nil
            self?.reconcile(from: .local)
        }
    }

    /// Leaving the foreground: don't let a change wait out the coalescing delay.
    private func flush() {
        guard pendingLocal != nil else { return }
        pendingLocal?.cancel()
        pendingLocal = nil
        reconcile(from: .local)
    }

    private func cloudChanged(reason: Int?, keys: [String]) {
        guard reason == nil || keys.isEmpty || keys.contains(Self.blobKey) else { return }
        switch reason {
        case NSUbiquitousKeyValueStoreAccountChange:
            // Another iCloud account: its settings are the ones to join, as on a fresh install.
            defaults.removeObject(forKey: Self.ledgerKey)
        case NSUbiquitousKeyValueStoreQuotaViolationChange:
            debugLog("over the key-value store quota")
        default:
            break
        }
        reconcile(from: .cloud)
    }

    // MARK: The pass

    private func reconcile(from trigger: Trigger) {
        var remote: SyncedSettingsBlob?
        if let data = cloud.data(forKey: Self.blobKey) {
            guard let blob = try? SyncedSettingsBlob.decode(data) else {
                // Written in a format a later version introduced. Leave it — and this device's
                // settings — alone rather than overwrite what can't be read.
                debugLog("can't read the stored settings; leaving them alone")
                return
            }
            remote = blob
        }
        let ledger = defaults.data(forKey: Self.ledgerKey)
            .flatMap { try? JSONDecoder().decode([String: SyncedSettingEntry].self, from: $0) }
        let local = SettingsSyncEngine.snapshot(of: Self.settings, deviceKind: deviceKind) {
            defaults.object(forKey: $0)
        }
        let outcome = SettingsSyncEngine.reconcile(settings: Self.settings, deviceKind: deviceKind, local: local,
                                                   ledger: ledger, remote: remote,
                                                   now: Date().timeIntervalSince1970, origin: origin)

        for (key, value) in outcome.localWrites {
            if let value { defaults.set(value.propertyList, forKey: key) } else { defaults.removeObject(forKey: key) }
        }
        // Seeded once a blob has been seen: before that, this install's values must not be dated
        // as if the reader had just chosen them.
        if remote != nil || ledger != nil, ledger != outcome.ledger,
           let data = try? JSONEncoder().encode(outcome.ledger) {
            defaults.set(data, forKey: Self.ledgerKey)
        }

        if let push = outcome.push {
            if remote == nil, ledger == nil, ContinuousClock.now - startedAt < Self.firstPushDelay {
                schedulePush()
            } else if let data = try? push.encoded() {
                cloud.set(data, forKey: Self.blobKey)
                debugLog("pushed \(push.entries.count) settings (\(data.count) bytes) after \(trigger)")
            }
        }

        if !outcome.localWrites.isEmpty {
            let keys = Array(outcome.localWrites.keys)
            debugLog("applied \(keys.sorted()) after \(trigger)")
            NotificationCenter.default.post(name: Self.restoredNotification, object: nil,
                                            userInfo: ["keys": keys, "initial": ledger == nil])
        }
    }

    private func schedulePush() {
        guard deferredPush == nil else { return }
        let wait = Self.firstPushDelay - (ContinuousClock.now - startedAt)
        deferredPush = Task { [weak self] in
            try? await Task.sleep(for: wait)
            self?.deferredPush = nil
            self?.reconcile(from: .deferred)
        }
    }

    private func debugLog(_ message: @autoclosure () -> String) {
        #if DEBUG
        print("SettingsSync: \(message())")
        #endif
    }

    #if DEBUG
    /// `-SettingsSyncSeed <base64 JSON blob>` puts a blob in the key-value store before the first
    /// pass — the reinstall restore, testable in a simulator, which has no iCloud account.
    /// `-SettingsSyncFresh YES` forgets this install's ledger first, as a reinstall would.
    private func seedFromLaunchArguments() {
        if defaults.bool(forKey: "SettingsSyncFresh") { defaults.removeObject(forKey: Self.ledgerKey) }
        guard let base64 = defaults.string(forKey: "SettingsSyncSeed"),
              let data = Data(base64Encoded: base64),
              (try? SyncedSettingsBlob.decode(data)) != nil else { return }
        cloud.set(data, forKey: Self.blobKey)
        debugLog("seeded the key-value store with \(data.count) bytes")
    }
    #endif
}
