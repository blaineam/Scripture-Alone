import Foundation
import Testing
import ScriptureAloneCore
@testable import Scripture_Alone

/// The app side of settings sync: what reaches `UserDefaults` from iCloud and what leaves this
/// device. The merge rules themselves are `SettingsSyncEngine`, tested in ScriptureAloneCore; these
/// cover the wiring around them — the ledger, the first-launch hold, the unreadable blob, the
/// account switch and the list of what is synced at all.
@MainActor
@Suite(.serialized)
final class SettingsSyncTests {
    final class FakeCloud: SettingsCloudStore {
        var values: [String: Any] = [:]
        var syncs = 0
        func synchronize() -> Bool { syncs += 1; return true }
        func data(forKey key: String) -> Data? { values[key] as? Data }
        func set(_ value: Any?, forKey key: String) { values[key] = value }
        var blob: SyncedSettingsBlob? { data(forKey: SettingsSync.blobKey).flatMap { try? SyncedSettingsBlob.decode($0) } }
    }

    let suiteName = "SettingsSyncTests-\(UUID().uuidString)"
    let defaults: UserDefaults
    let cloud = FakeCloud()

    init() {
        defaults = UserDefaults(suiteName: suiteName)!
    }

    deinit {
        UserDefaults().removePersistentDomain(forName: suiteName)
    }

    func makeSync(_ kind: String = "phone") -> SettingsSync {
        SettingsSync(defaults: defaults, cloud: cloud, deviceKind: kind)
    }

    func remote(_ entries: [String: SyncedSettingValue], modified: Double = 1_000) throws {
        let blob = SyncedSettingsBlob(entries: entries.mapValues { SyncedSettingEntry(value: $0, modified: modified, origin: "other-device") })
        cloud.set(try blob.encoded(), forKey: SettingsSync.blobKey)
    }

    // MARK: Restore

    @Test func aFreshInstallTakesTheReadersSettingsFromICloud() throws {
        try remote([
            SettingsKey.theme: .string("sepia"),
            SettingsKey.redLetters: .bool(false),
            "translation@phone": .string("KJV"),
            "translation@mac": .string("BSB"),
            "recent": .ints([43_003_001, 19_023_001]),
        ])
        defaults.set("light", forKey: SettingsKey.theme)   // what the app wrote for itself at first launch

        nonisolated final class Box: @unchecked Sendable { var notes: [Notification] = [] }
        let box = Box()
        let token = NotificationCenter.default.addObserver(forName: SettingsSync.restoredNotification, object: nil, queue: nil) {
            box.notes.append($0)
        }
        defer { NotificationCenter.default.removeObserver(token) }

        makeSync("phone").reconcile(from: .launch)

        #expect(defaults.string(forKey: SettingsKey.theme) == "sepia", "the reader's choice beats a first-launch default")
        #expect(defaults.object(forKey: SettingsKey.redLetters) as? Bool == false)
        #expect(defaults.string(forKey: "translation") == "KJV", "a phone takes the phone's translation, not the Mac's")
        #expect(defaults.array(forKey: "recent") as? [Int] == [43_003_001, 19_023_001])

        let note = try #require(box.notes.first)
        #expect(note.userInfo?["initial"] as? Bool == true)
        let changed: Set<String> = Set(note.userInfo?["keys"] as? [String] ?? [])
        #expect(changed.isSuperset(of: [SettingsKey.theme, "translation"]))
    }

    /// Until this install has seen a blob it pushes nothing straight away — its first-launch values
    /// must not overwrite the reader's settings in iCloud before those have had a chance to arrive.
    @Test func aFreshInstallWithNothingInICloudDoesNotPushAtOnce() {
        defaults.set("dark", forKey: SettingsKey.theme)
        makeSync().reconcile(from: .launch)
        #expect(cloud.data(forKey: SettingsSync.blobKey) == nil)
    }

    // MARK: Pushing changes

    @Test func aChangeMadeHereIsPushedAndNewestWins() throws {
        try remote([SettingsKey.theme: .string("sepia")], modified: 1_000)
        let sync = makeSync()
        sync.reconcile(from: .launch)                       // restore: the ledger now exists

        defaults.set("black", forKey: SettingsKey.theme)
        defaults.set(1.25, forKey: SettingsKey.listenSpeed)
        sync.reconcile(from: .local)

        let blob = try #require(cloud.blob)
        #expect(blob.entries[SettingsKey.theme]?.value == .string("black"))
        #expect((blob.entries[SettingsKey.theme]?.modified ?? 0) > 1_000)
        #expect(blob.entries[SettingsKey.listenSpeed]?.value == .double(1.25))

        // Nothing changed: nothing is written again (no echo).
        let before = cloud.data(forKey: SettingsSync.blobKey)
        sync.reconcile(from: .local)
        #expect(cloud.data(forKey: SettingsSync.blobKey) == before)
    }

    /// Device-local settings — a system voice that exists only on this device, Spotlight's index
    /// fingerprints, sync bookkeeping, the reading position (synced on its own key) — never travel.
    @Test func localOnlySettingsAreNeverPushed() throws {
        try remote([SettingsKey.theme: .string("sepia")])
        let sync = makeSync()
        sync.reconcile(from: .launch)
        defaults.set("com.apple.voice.premium.en-US.Zoe", forKey: SettingsKey.listenVoice)
        defaults.set("miSpeaks", forKey: SettingsKey.listenEngine)
        defaults.set("abc", forKey: "spotlight.indexed.notes")
        defaults.set(43_003_016, forKey: "position")
        defaults.set("dark", forKey: SettingsKey.theme)
        sync.reconcile(from: .local)

        let keys = Set(try #require(cloud.blob).entries.keys)
        #expect(keys.contains(SettingsKey.theme))
        for local in [SettingsKey.listenVoice, SettingsKey.listenEngine, SettingsKey.listenStudioVoice,
                      "spotlight.indexed.notes", "position", "settingsSync.ledger", "settingsSync.origin"] {
            #expect(!keys.contains(local), "\(local) is device-local")
        }
    }

    @Test func theSyncedListHasNoCredentialsAndNoDuplicates() {
        let keys = SettingsSync.settings.map(\.key)
        #expect(Set(keys).count == keys.count)
        for key in keys {
            let lowered = key.lowercased()
            #expect(!lowered.contains("apikey") && !lowered.contains("api.key") && !lowered.contains("token")
                    && !lowered.contains("password"), "\(key) looks like a credential; keys live in the keychain")
        }
        // Text size and translation are per kind of device; nothing else is.
        let perKind = Set(SettingsSync.settings.filter(\.perDeviceKind).map(\.key))
        #expect(perKind == [SettingsKey.fontSize, "translation"])
    }

    // MARK: What can't be read, and another account

    @Test func anUnreadableBlobIsLeftAloneAndSoAreTheLocalSettings() {
        let unreadable = Data("{\"version\": 99, \"entries\": 5}".utf8)
        cloud.set(unreadable, forKey: SettingsSync.blobKey)
        defaults.set("dark", forKey: SettingsKey.theme)
        let sync = makeSync()
        sync.reconcile(from: .launch)
        defaults.set("black", forKey: SettingsKey.theme)
        sync.reconcile(from: .local)
        #expect(cloud.data(forKey: SettingsSync.blobKey) == unreadable, "a blob from a later version is never overwritten")
        #expect(defaults.string(forKey: SettingsKey.theme) == "black")
    }

    @Test func switchingICloudAccountsJoinsTheNewAccountsSettings() throws {
        try remote([SettingsKey.theme: .string("sepia")], modified: 1_000)
        let sync = makeSync()
        sync.reconcile(from: .launch)
        defaults.set("dark", forKey: SettingsKey.theme)
        sync.reconcile(from: .local)                         // "dark", stamped now: newer than anything below

        // The other account's blob is older, but this device has never agreed with it: it wins,
        // exactly as on a fresh install.
        try remote([SettingsKey.theme: .string("light")], modified: 500)
        sync.cloudChanged(reason: NSUbiquitousKeyValueStoreAccountChange, keys: [])
        #expect(defaults.string(forKey: SettingsKey.theme) == "light")
    }

    @Test func aChangeFromAnotherDeviceArrivesHere() throws {
        try remote([SettingsKey.theme: .string("sepia")], modified: 1_000)
        let sync = makeSync()
        sync.reconcile(from: .launch)
        try remote([SettingsKey.theme: .string("black")], modified: Date().timeIntervalSince1970 + 60)
        sync.cloudChanged(reason: NSUbiquitousKeyValueStoreServerChange, keys: [SettingsSync.blobKey])
        #expect(defaults.string(forKey: SettingsKey.theme) == "black")
    }

    /// A notification about other keys only (the reading position, family flags) is not a reason
    /// to run a pass.
    @Test func changesToOtherKeysAreIgnored() throws {
        try remote([SettingsKey.theme: .string("sepia")], modified: 1_000)
        let sync = makeSync()
        sync.reconcile(from: .launch)
        try remote([SettingsKey.theme: .string("black")], modified: Date().timeIntervalSince1970 + 60)
        sync.cloudChanged(reason: NSUbiquitousKeyValueStoreServerChange, keys: ["position"])
        #expect(defaults.string(forKey: SettingsKey.theme) == "sepia")
    }
}
