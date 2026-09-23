import Foundation

// The merge rules behind the app's synced settings (`SettingsSync` in the app target), kept here,
// free of `UserDefaults` and `NSUbiquitousKeyValueStore`, so they are unit-tested on their own.
//
// The model: every synced setting is one entry — a value (or none: "reset to the default") and the
// time it was last changed, and by which device. The whole set travels as one small, versioned JSON
// blob. Each device keeps a *ledger* of the entries it last agreed with; comparing the live settings
// against the ledger is what tells a change made here from a value that has simply been sitting
// there. Conflicts are settled per setting, newest change wins.

/// How a setting is stored in `UserDefaults`, which decides how its value is read back out.
public enum SyncedSettingKind: String, Sendable, Codable {
    case bool, int, double, string, strings, ints, data
}

/// One synced setting: its `UserDefaults` key and type.
public struct SyncedSetting: Sendable, Hashable {
    public let key: String
    public let kind: SyncedSettingKind
    /// Kept separately for each kind of device (phone, pad, mac) rather than shared by all of them:
    /// a reinstall on the phone gets the phone's text size back, and a Mac never shrinks it.
    public let perDeviceKind: Bool

    public init(_ key: String, _ kind: SyncedSettingKind, perDeviceKind: Bool = false) {
        self.key = key
        self.kind = kind
        self.perDeviceKind = perDeviceKind
    }

    /// The name the setting goes by in the blob.
    public func blobKey(deviceKind: String) -> String {
        perDeviceKind ? "\(key)@\(deviceKind)" : key
    }
}

/// A setting's value, typed, so a `Bool` never comes back as `1` and a `Double` never as an `Int`.
public enum SyncedSettingValue: Equatable, Sendable {
    case bool(Bool)
    case int(Int)
    case double(Double)
    case string(String)
    case strings([String])
    case ints([Int])
    case data(Data)

    /// Reads a `UserDefaults` object as `kind`. Nil for anything that isn't that kind — a value of
    /// the wrong type is never synced, and so never spread to another device.
    public init?(propertyList: Any, kind: SyncedSettingKind) {
        switch kind {
        case .bool:
            guard let number = propertyList as? NSNumber else { return nil }
            self = .bool(number.boolValue)
        case .int:
            guard let number = propertyList as? NSNumber else { return nil }
            self = .int(number.intValue)
        case .double:
            guard let number = propertyList as? NSNumber else { return nil }
            self = .double(number.doubleValue)
        case .string:
            guard let value = propertyList as? String else { return nil }
            self = .string(value)
        case .strings:
            guard let value = propertyList as? [String] else { return nil }
            self = .strings(value)
        case .ints:
            guard let value = propertyList as? [Int] else { return nil }
            self = .ints(value)
        case .data:
            guard let value = propertyList as? Data else { return nil }
            self = .data(value)
        }
    }

    /// The object to hand `UserDefaults.set(_:forKey:)`.
    public var propertyList: Any {
        switch self {
        case .bool(let value): value
        case .int(let value): value
        case .double(let value): value
        case .string(let value): value
        case .strings(let value): value
        case .ints(let value): value
        case .data(let value): value
        }
    }

    var kind: SyncedSettingKind {
        switch self {
        case .bool: .bool
        case .int: .int
        case .double: .double
        case .string: .string
        case .strings: .strings
        case .ints: .ints
        case .data: .data
        }
    }
}

extension SyncedSettingValue: Codable {
    private enum CodingKeys: String, CodingKey { case type = "t", value = "v" }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        switch try container.decode(SyncedSettingKind.self, forKey: .type) {
        case .bool: self = .bool(try container.decode(Bool.self, forKey: .value))
        case .int: self = .int(try container.decode(Int.self, forKey: .value))
        case .double: self = .double(try container.decode(Double.self, forKey: .value))
        case .string: self = .string(try container.decode(String.self, forKey: .value))
        case .strings: self = .strings(try container.decode([String].self, forKey: .value))
        case .ints: self = .ints(try container.decode([Int].self, forKey: .value))
        case .data: self = .data(try container.decode(Data.self, forKey: .value))
        }
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(kind, forKey: .type)
        switch self {
        case .bool(let value): try container.encode(value, forKey: .value)
        case .int(let value): try container.encode(value, forKey: .value)
        case .double(let value): try container.encode(value, forKey: .value)
        case .string(let value): try container.encode(value, forKey: .value)
        case .strings(let value): try container.encode(value, forKey: .value)
        case .ints(let value): try container.encode(value, forKey: .value)
        case .data(let value): try container.encode(value, forKey: .value)
        }
    }
}

/// A setting as synced: its value — nil when it was reset to the default — and when, and where, it
/// last changed.
public struct SyncedSettingEntry: Codable, Equatable, Sendable {
    public var value: SyncedSettingValue?
    /// Seconds since 1970.
    public var modified: Double
    /// The device that made the change; breaks a tie between two changes made in the same instant.
    public var origin: String

    public init(value: SyncedSettingValue?, modified: Double, origin: String) {
        self.value = value
        self.modified = modified
        self.origin = origin
    }

    /// Whether this change is newer than `other`.
    func supersedes(_ other: SyncedSettingEntry) -> Bool {
        modified != other.modified ? modified > other.modified : origin > other.origin
    }
}

/// The synced settings as stored: one versioned JSON object, well under the key-value store's
/// 1 MB and 1,024-key limits (a few KB for every setting the app has).
public struct SyncedSettingsBlob: Codable, Equatable, Sendable {
    public static let currentVersion = 1

    public var version: Int
    public var entries: [String: SyncedSettingEntry]

    public init(version: Int = Self.currentVersion, entries: [String: SyncedSettingEntry]) {
        self.version = version
        self.entries = entries
    }

    public func encoded() throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys
        return try encoder.encode(self)
    }

    public static func decode(_ data: Data) throws -> SyncedSettingsBlob {
        try JSONDecoder().decode(SyncedSettingsBlob.self, from: data)
    }
}

public enum SettingsSyncEngine {

    /// What one pass decided.
    public struct Outcome: Equatable, Sendable {
        /// Settings to change on this device, by `UserDefaults` key; `.some(nil)` removes one, which
        /// puts it back to its default.
        public var localWrites: [String: SyncedSettingValue?]
        /// The entries this device now agrees with — save it for the next pass.
        public var ledger: [String: SyncedSettingEntry]
        /// The blob to store, or nil when the stored one is already right.
        public var push: SyncedSettingsBlob?
    }

    /// This device's current values for `settings`, by blob key. Only the listed settings are ever
    /// read — anything else in `UserDefaults` (caches, fingerprints, one-shot flags) cannot leak in.
    public static func snapshot(of settings: [SyncedSetting], deviceKind: String,
                                read: (String) -> Any?) -> [String: SyncedSettingValue] {
        var out: [String: SyncedSettingValue] = [:]
        for setting in settings {
            guard let raw = read(setting.key),
                  let value = SyncedSettingValue(propertyList: raw, kind: setting.kind) else { continue }
            out[setting.blobKey(deviceKind: deviceKind)] = value
        }
        return out
    }

    /// Reconciles this device's settings with the stored blob.
    ///
    /// - `local`: this device's values now (`snapshot`).
    /// - `ledger`: the entries this device last agreed with; nil when it never has — a fresh
    ///   install, or the first launch of a version that syncs. Then whatever the blob holds wins,
    ///   which is what puts a reinstalled app back the way the reader left it; a setting the blob
    ///   doesn't have yet is contributed from here.
    /// - `remote`: the stored blob, or nil when there is none.
    ///
    /// With a ledger, a value that differs from the ledger's is a change made here, stamped `now`;
    /// one that matches keeps the ledger's time. Each setting then goes to the newer of this
    /// device's entry and the blob's. Running it again with its own outcome changes nothing, which
    /// is what keeps an applied change from echoing back out as a new one.
    public static func reconcile(settings: [SyncedSetting], deviceKind: String,
                                 local: [String: SyncedSettingValue],
                                 ledger: [String: SyncedSettingEntry]?,
                                 remote: SyncedSettingsBlob?,
                                 now: Double, origin: String) -> Outcome {
        let keys = Dictionary(settings.map { ($0.blobKey(deviceKind: deviceKind), $0.key) },
                              uniquingKeysWith: { first, _ in first })
        let stored = remote?.entries ?? [:]

        // This device's side of each setting.
        var mine: [String: SyncedSettingEntry] = [:]
        for blobKey in keys.keys {
            let current = local[blobKey]
            if let ledger {
                if let prior = ledger[blobKey] {
                    mine[blobKey] = prior.value == current
                        ? prior
                        : SyncedSettingEntry(value: current, modified: now, origin: origin)
                } else if let current {
                    mine[blobKey] = SyncedSettingEntry(value: current, modified: now, origin: origin)
                }
            } else if stored[blobKey] == nil, let current {
                mine[blobKey] = SyncedSettingEntry(value: current, modified: now, origin: origin)
            }
        }

        // Newest wins, setting by setting. Entries this device doesn't use — another kind of
        // device's text size, a setting from a later version — are carried through untouched.
        var merged = stored
        for (blobKey, entry) in mine {
            if let theirs = merged[blobKey], !entry.supersedes(theirs) { continue }
            merged[blobKey] = entry
        }

        var writes: [String: SyncedSettingValue?] = [:]
        var newLedger: [String: SyncedSettingEntry] = [:]
        for (blobKey, defaultsKey) in keys {
            guard let entry = merged[blobKey] else { continue }
            newLedger[blobKey] = entry
            if entry.value != local[blobKey] { writes[defaultsKey] = .some(entry.value) }
        }

        let push: SyncedSettingsBlob?
        if let remote {
            push = merged == remote.entries
                ? nil
                : SyncedSettingsBlob(version: max(remote.version, SyncedSettingsBlob.currentVersion), entries: merged)
        } else {
            push = merged.isEmpty ? nil : SyncedSettingsBlob(entries: merged)
        }
        return Outcome(localWrites: writes, ledger: newLedger, push: push)
    }
}
