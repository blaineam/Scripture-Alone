import Foundation
import Testing
@testable import ScriptureAloneCore

@Suite struct SettingsSyncTests {
    private let settings = [
        SyncedSetting("reader.accent", .string),
        SyncedSetting("reader.theme", .string),
        SyncedSetting("reader.redLetters", .bool),
        SyncedSetting("reader.fontSize", .double, perDeviceKind: true),
        SyncedSetting("recent", .ints),
        SyncedSetting("onlineTranslations", .data),
    ]

    private func reconcile(local: [String: SyncedSettingValue],
                           ledger: [String: SyncedSettingEntry]?,
                           remote: SyncedSettingsBlob?,
                           now: Double = 1_000,
                           origin: String = "phone-A",
                           deviceKind: String = "phone") -> SettingsSyncEngine.Outcome {
        SettingsSyncEngine.reconcile(settings: settings, deviceKind: deviceKind, local: local,
                                     ledger: ledger, remote: remote, now: now, origin: origin)
    }

    private func entry(_ value: SyncedSettingValue?, _ modified: Double, _ origin: String = "mac-B") -> SyncedSettingEntry {
        SyncedSettingEntry(value: value, modified: modified, origin: origin)
    }

    // MARK: Reinstall

    @Test func freshInstallRestoresEverythingTheBlobHolds() {
        let remote = SyncedSettingsBlob(entries: [
            "reader.accent": entry(.string("forest"), 500),
            "reader.theme": entry(.string("sepia"), 400),
            "reader.redLetters": entry(.bool(false), 300),
            "reader.fontSize@phone": entry(.double(23.5), 200),
            "onlineTranslations": entry(.data(Data("[{\"id\":\"NIV\"}]".utf8)), 100),
        ])
        let outcome = reconcile(local: [:], ledger: nil, remote: remote)
        #expect(outcome.localWrites["reader.accent"] == .some(.string("forest")))
        #expect(outcome.localWrites["reader.theme"] == .some(.string("sepia")))
        #expect(outcome.localWrites["reader.redLetters"] == .some(.bool(false)))
        #expect(outcome.localWrites["reader.fontSize"] == .some(.double(23.5)))
        #expect(outcome.localWrites["onlineTranslations"] == .some(.data(Data("[{\"id\":\"NIV\"}]".utf8))))
        // Nothing new to say: the blob already holds exactly this.
        #expect(outcome.push == nil)
        #expect(outcome.ledger.count == 5)
    }

    @Test func firstLaunchOfASyncingVersionDefersToTheBlobButContributesWhatItLacks() {
        let remote = SyncedSettingsBlob(entries: ["reader.accent": entry(.string("forest"), 500)])
        let outcome = reconcile(local: ["reader.accent": .string("sunrise"), "reader.theme": .string("dark")],
                                ledger: nil, remote: remote)
        #expect(outcome.localWrites == ["reader.accent": .some(.string("forest"))])
        #expect(outcome.push?.entries["reader.theme"] == entry(.string("dark"), 1_000, "phone-A"))
        #expect(outcome.push?.entries["reader.accent"]?.value == .string("forest"))
    }

    @Test func nothingAnywhereMeansNothingToDo() {
        let outcome = reconcile(local: [:], ledger: nil, remote: nil)
        #expect(outcome.localWrites.isEmpty)
        #expect(outcome.push == nil)
        #expect(outcome.ledger.isEmpty)
    }

    @Test func aFirstDeviceSeedsTheBlob() {
        let outcome = reconcile(local: ["reader.accent": .string("forest")], ledger: nil, remote: nil)
        #expect(outcome.localWrites.isEmpty)
        #expect(outcome.push?.entries == ["reader.accent": entry(.string("forest"), 1_000, "phone-A")])
    }

    // MARK: Last writer wins

    @Test func aLocalChangeIsStampedAndPushed() {
        let ledger = ["reader.accent": entry(.string("forest"), 500)]
        let remote = SyncedSettingsBlob(entries: ledger)
        let outcome = reconcile(local: ["reader.accent": .string("ocean")], ledger: ledger, remote: remote)
        #expect(outcome.localWrites.isEmpty)
        #expect(outcome.push?.entries["reader.accent"] == entry(.string("ocean"), 1_000, "phone-A"))
    }

    @Test func aNewerRemoteChangeReplacesAnUnchangedLocalValue() {
        let ledger = ["reader.accent": entry(.string("forest"), 500)]
        let remote = SyncedSettingsBlob(entries: ["reader.accent": entry(.string("ocean"), 900)])
        let outcome = reconcile(local: ["reader.accent": .string("forest")], ledger: ledger, remote: remote)
        #expect(outcome.localWrites == ["reader.accent": .some(.string("ocean"))])
        #expect(outcome.push == nil)
    }

    @Test func aStaleDeviceDoesNotClobberANewerChange() {
        // This device changed its accent at 1,000 while offline; another device changed it at 2,000.
        let ledger = ["reader.accent": entry(.string("forest"), 500)]
        let remote = SyncedSettingsBlob(entries: ["reader.accent": entry(.string("ocean"), 2_000)])
        let outcome = reconcile(local: ["reader.accent": .string("rose")], ledger: ledger, remote: remote, now: 1_000)
        #expect(outcome.localWrites == ["reader.accent": .some(.string("ocean"))])
        #expect(outcome.push == nil)
    }

    @Test func conflictsAreSettledPerSettingNotPerBlob() {
        let ledger = ["reader.accent": entry(.string("forest"), 500), "reader.theme": entry(.string("light"), 500)]
        let remote = SyncedSettingsBlob(entries: ["reader.accent": entry(.string("forest"), 500),
                                                  "reader.theme": entry(.string("black"), 900)])
        let outcome = reconcile(local: ["reader.accent": .string("rose"), "reader.theme": .string("light")],
                                ledger: ledger, remote: remote, now: 1_000)
        #expect(outcome.localWrites == ["reader.theme": .some(.string("black"))])
        #expect(outcome.push?.entries["reader.accent"]?.value == .string("rose"))
        #expect(outcome.push?.entries["reader.theme"]?.value == .string("black"))
    }

    @Test func aTieIsBrokenTheSameWayOnEveryDevice() {
        let a = entry(.string("rose"), 1_000, "phone-A")
        let b = entry(.string("ocean"), 1_000, "mac-B")
        #expect(a.supersedes(b) != b.supersedes(a))
    }

    @Test func aResetTravelsAsARemoval() {
        let ledger = ["reader.theme": entry(.string("sepia"), 500)]
        let reset = reconcile(local: [:], ledger: ledger, remote: SyncedSettingsBlob(entries: ledger))
        let tombstone = entry(nil, 1_000, "phone-A")
        #expect(reset.push?.entries["reader.theme"] == tombstone)

        // …and the other device goes back to its default.
        let other = reconcile(local: ["reader.theme": .string("sepia")], ledger: ledger,
                              remote: reset.push, origin: "mac-B")
        #expect(other.localWrites == ["reader.theme": SyncedSettingValue?.none])
    }

    // MARK: Echoes

    @Test func applyingAnOutcomeAndRunningAgainIsANoOp() {
        let remote = SyncedSettingsBlob(entries: ["reader.accent": entry(.string("ocean"), 900),
                                                  "recent": entry(.ints([43003, 1001]), 900)])
        let first = reconcile(local: ["reader.theme": .string("dark")], ledger: nil, remote: remote)
        // Apply what it said to do, the way the app does.
        var local: [String: SyncedSettingValue] = ["reader.theme": .string("dark")]
        for (key, value) in first.localWrites { local[key] = value }
        let stored = first.push ?? remote

        // The change notification for our own writes, and the store echoing our own blob back.
        let second = reconcile(local: local, ledger: first.ledger, remote: stored, now: 5_000)
        #expect(second.localWrites.isEmpty)
        #expect(second.push == nil)
        #expect(second.ledger == first.ledger)
    }

    @Test func aRemoteChangeThatMatchesThisDeviceIsNotPushedBack() {
        let ledger = ["reader.accent": entry(.string("forest"), 500)]
        let remote = SyncedSettingsBlob(entries: ["reader.accent": entry(.string("ocean"), 900)])
        let applied = reconcile(local: ["reader.accent": .string("forest")], ledger: ledger, remote: remote)
        let again = reconcile(local: ["reader.accent": .string("ocean")], ledger: applied.ledger, remote: remote, now: 9_999)
        #expect(again.push == nil)
        #expect(again.localWrites.isEmpty)
    }

    // MARK: What syncs

    @Test func deviceLocalKeysAreNeverRead() {
        let defaults: [String: Any] = [
            "reader.accent": "forest",
            "reader.redLetters": false,
            "reader.fontSize": 21.0,
            "spotlight.indexed.notes": "fingerprint",
            "listen.voice": "com.apple.voice.premium.en-US.Zoe",
            "debug.cloudKitSchemaBootstrapped.v1": true,
            "family.sharedSubscription": true,
        ]
        let snapshot = SettingsSyncEngine.snapshot(of: settings, deviceKind: "phone") { defaults[$0] }
        #expect(Set(snapshot.keys) == ["reader.accent", "reader.redLetters", "reader.fontSize@phone"])
        #expect(snapshot["reader.redLetters"] == .bool(false))

        let outcome = reconcile(local: snapshot, ledger: nil, remote: nil)
        #expect(Set(outcome.push.map { Array($0.entries.keys) } ?? []) == ["reader.accent", "reader.redLetters", "reader.fontSize@phone"])
    }

    @Test func blobEntriesThisDeviceDoesNotUseAreLeftAlone() {
        // Another kind of device's text size, and a setting only a later version knows.
        let remote = SyncedSettingsBlob(entries: ["reader.fontSize@mac": entry(.double(26), 700),
                                                  "reader.someLaterSetting": entry(.bool(true), 700)])
        let outcome = reconcile(local: ["reader.fontSize@phone": .double(19)], ledger: [:], remote: remote)
        #expect(outcome.localWrites.isEmpty)
        #expect(outcome.push?.entries["reader.fontSize@mac"]?.value == .double(26))
        #expect(outcome.push?.entries["reader.someLaterSetting"]?.value == .bool(true))
        #expect(outcome.push?.entries["reader.fontSize@phone"]?.value == .double(19))
        #expect(outcome.ledger.keys.sorted() == ["reader.fontSize@phone"])
    }

    @Test func aValueOfTheWrongTypeIsNotSynced() {
        let defaults: [String: Any] = ["reader.redLetters": "yes", "reader.accent": 3, "recent": ["x"]]
        let snapshot = SettingsSyncEngine.snapshot(of: settings, deviceKind: "phone") { defaults[$0] }
        #expect(snapshot.isEmpty)
    }

    // MARK: The blob

    @Test func theBlobRoundTripsEveryKindExactly() throws {
        let blob = SyncedSettingsBlob(entries: [
            "b": entry(.bool(true), 1), "i": entry(.int(-4), 2), "d": entry(.double(1.35), 3),
            "s": entry(.string("Grüße 🌅"), 4), "ss": entry(.strings(["love", "grace"]), 5),
            "ii": entry(.ints([1001001, 66022021]), 6), "data": entry(.data(Data([0, 1, 255])), 7),
            "gone": entry(nil, 8),
        ])
        let decoded = try SyncedSettingsBlob.decode(try blob.encoded())
        #expect(decoded == blob)
        #expect(try blob.encoded() == decoded.encoded())
    }

    @Test func propertyListsComeBackAsTheirKind() {
        #expect(SyncedSettingValue(propertyList: NSNumber(value: true), kind: .bool) == .bool(true))
        #expect(SyncedSettingValue(propertyList: NSNumber(value: 19), kind: .double) == .double(19))
        #expect(SyncedSettingValue(propertyList: [43003016] as [Any], kind: .ints) == .ints([43003016]))
        #expect(SyncedSettingValue(propertyList: Data([1]), kind: .string) == nil)
    }
}
