import Foundation
import CloudKit
import Observation
import ScriptureAloneCore

/// Bibles family members share live with this person: fetched from their shared CloudKit
/// database, cached as files in Application Support/SharedBibles so they read offline, and —
/// like keepsakes — never written into this person's own SwiftData store.
@Observable
final class SharedBibleLibrary {
    static let shared = SharedBibleLibrary()

    struct Entry: Identifiable, Hashable, Codable {
        /// The zone owner's CloudKit user record name — one shared Bible per person.
        let id: String
        var snapshot: FamilyBibleSnapshot
        /// Names the Bible until its profile record arrives.
        var fallbackID: UUID
        /// The share's title, shown before the profile arrives.
        var shareTitle: String?
        var lastFetched: Date?
        /// Set when the owner stopped sharing or removed this person. The cached copy stays.
        var ended: Date?

        var title: String {
            if snapshot.profile != nil { return keepsakeManifest.displayTitle }
            return shareTitle ?? String(localized: "A Shared Bible", comment: "Title of a Bible someone shared, when it has no name")
        }

        var keepsakeManifest: KeepsakeManifest {
            KeepsakeManifest(bibleID: snapshot.profile?.bibleID ?? fallbackID, ownerName: snapshot.profile?.ownerName,
                             dedication: snapshot.profile?.dedication,
                             preferredTranslation: snapshot.profile?.preferredTranslation)
        }

        /// The snapshot in keepsake form, for the keepsake reader.
        var keepsake: Keepsake { snapshot.keepsake(fallbackID: fallbackID, createdAt: lastFetched ?? .now) }

        var isLive: Bool { ended == nil }

        static func == (lhs: Entry, rhs: Entry) -> Bool {
            lhs.id == rhs.id && lhs.snapshot == rhs.snapshot && lhs.lastFetched == rhs.lastFetched
                && lhs.ended == rhs.ended && lhs.shareTitle == rhs.shareTitle
        }
        func hash(into hasher: inout Hasher) { hasher.combine(id) }
    }

    private(set) var entries: [Entry] = []
    private(set) var refreshing = false
    private(set) var lastError: String?
    /// A share just accepted from an invitation, for the welcome sheet.
    var justAccepted: Entry.ID?

    private let directory: URL
    private var tokens: SharedBibleTokens

    private init() {
        directory = FamilyCloud.directory("SharedBibles")
        tokens = SharedBibleTokens.load(from: directory)
        #if DEBUG
        if let demo = FamilyDebug.sharedBible() {
            entries = [demo]
            return
        }
        #endif
        load()
    }

    func entry(_ id: Entry.ID) -> Entry? { entries.first { $0.id == id } }

    /// Whether this person has accepted a share here or on another of their devices.
    private var mightHaveShares: Bool {
        !entries.isEmpty || NSUbiquitousKeyValueStore.default.bool(forKey: FamilyCloud.participantKey)
    }

    // MARK: Accepting

    /// An invitation link was opened. Accepting adds the owner's zone to this person's shared
    /// database; nothing is sent anywhere else.
    func accept(_ metadata: CKShare.Metadata) async {
        guard let container = FamilyCloud.container else { return }
        let zoneID = metadata.share.recordID.zoneID
        do {
            if metadata.participantStatus != .accepted {
                _ = try await container.accept(metadata)
            }
            NSUbiquitousKeyValueStore.default.set(true, forKey: FamilyCloud.participantKey)
            if entry(zoneID.ownerName) == nil {
                var entry = Entry(id: zoneID.ownerName, snapshot: FamilyBibleSnapshot(), fallbackID: UUID())
                entry.shareTitle = metadata.share[CKShare.SystemFieldKey.title] as? String
                upsert(entry)
            } else if var entry = entry(zoneID.ownerName), entry.ended != nil {
                // Invited again after sharing ended: live once more.
                entry.ended = nil
                upsert(entry)
            }
            justAccepted = zoneID.ownerName
            await refresh()
        } catch {
            lastError = FamilyCloud.describe(error)
            // The welcome sheet explains what went wrong.
            justAccepted = zoneID.ownerName
        }
    }

    // MARK: Fetching

    /// Incremental fetch from the shared database (per-database and per-zone change tokens).
    /// Runs on launch, on returning to the foreground, on pull-to-refresh and on a silent push.
    func refresh() async {
        guard !refreshing, mightHaveShares, let container = FamilyCloud.container else { return }
        refreshing = true
        defer { refreshing = false }
        let fetcher = SharedBibleFetcher(database: container.sharedCloudDatabase)
        do {
            let result = try await fetcher.fetch(tokens: tokens, known: Set(entries.filter(\.isLive).map(\.id)))
            apply(result)
            lastError = nil
            if entries.contains(where: \.isLive) {
                FamilySharePush.register()
                await ensureSubscription(container)
            }
        } catch {
            lastError = FamilyCloud.describe(error)
        }
    }

    private func apply(_ result: SharedBibleFetcher.Result) {
        tokens.database = result.databaseToken
        let now = Date.now
        for zone in result.zones {
            var entry = entry(zone.ownerName) ?? Entry(id: zone.ownerName, snapshot: FamilyBibleSnapshot(), fallbackID: UUID())
            if zone.ended {
                entry.ended = entry.ended ?? now
                tokens.zones[zone.ownerName] = nil
            } else {
                if zone.reset { entry.snapshot = FamilyBibleSnapshot() }
                entry.snapshot.apply(changed: zone.changed, deleted: zone.deleted)
                entry.lastFetched = now
                entry.ended = nil
                tokens.zones[zone.ownerName] = zone.token
            }
            upsert(entry)
        }
        // Everything else still live was checked too (no changes).
        for index in entries.indices where entries[index].isLive && !result.zones.contains(where: { $0.ownerName == entries[index].id }) {
            entries[index].lastFetched = now
            save(entries[index])
        }
        tokens.save(to: directory)
    }

    /// Silent pushes when a shared Bible changes (stretch goal): one database subscription.
    private func ensureSubscription(_ container: CKContainer) async {
        let key = "family.sharedSubscription"
        guard !UserDefaults.standard.bool(forKey: key) else { return }
        let subscription = CKDatabaseSubscription(subscriptionID: FamilySharePush.subscriptionID)
        let info = CKSubscription.NotificationInfo()
        info.shouldSendContentAvailable = true
        subscription.notificationInfo = info
        do {
            _ = try await container.sharedCloudDatabase.save(subscription)
            UserDefaults.standard.set(true, forKey: key)
        } catch {
            // Not essential: launch, foreground and pull-to-refresh still fetch.
        }
    }

    // MARK: Keeping and removing

    /// Keeps the current copy as an ordinary keepsake (Legacy & Export), replacing an older
    /// keepsake of the same Bible.
    @discardableResult
    func keepAsKeepsake(_ id: Entry.ID) throws -> Keepsake? {
        guard let entry = entry(id) else { return nil }
        let keepsake = entry.keepsake
        try LegacyLibrary.shared.add(keepsake)
        return keepsake
    }

    /// Removes the local copy, and — while it's still live — leaves the share, so the owner's
    /// list shows this person as gone.
    func remove(_ id: Entry.ID) async {
        guard let entry = entry(id) else { return }
        if entry.isLive, let container = FamilyCloud.container {
            let zone = CKRecordZone.ID(zoneName: FamilyMirror.zoneName, ownerName: id)
            _ = try? await container.sharedCloudDatabase.deleteRecord(withID: FamilyCloud.shareID(in: zone))
        }
        entries.removeAll { $0.id == id }
        tokens.zones[id] = nil
        tokens.save(to: directory)
        try? FileManager.default.removeItem(at: fileURL(id))
        if entries.isEmpty { NSUbiquitousKeyValueStore.default.set(false, forKey: FamilyCloud.participantKey) }
    }

    // MARK: Storage

    private func upsert(_ entry: Entry) {
        if let index = entries.firstIndex(where: { $0.id == entry.id }) {
            if entries[index] != entry { entries[index] = entry }
        } else {
            entries.append(entry)
            entries.sort { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
        }
        save(entry)
    }

    private func fileURL(_ id: Entry.ID) -> URL {
        // Record names are CloudKit's ("_abc123…"), safe as file names; hash anything else.
        let safe = id.unicodeScalars.allSatisfy { CharacterSet.alphanumerics.contains($0) || $0 == "_" || $0 == "-" }
        return directory.appending(path: (safe ? id : String(id.hashValue, radix: 16)) + ".json")
    }

    private func save(_ entry: Entry) {
        #if DEBUG
        if FamilyDebug.isFaking { return }
        #endif
        guard let data = try? JSONEncoder().encode(entry) else { return }
        try? data.write(to: fileURL(entry.id), options: [.atomic, .completeFileProtectionUnlessOpen])
    }

    private func load() {
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        entries = files.filter { $0.pathExtension == "json" }
            .compactMap { try? JSONDecoder().decode(Entry.self, from: Data(contentsOf: $0)) }
            .sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
    }
}

/// Change tokens for the shared database and each shared zone, archived to one file.
nonisolated struct SharedBibleTokens: Codable, Sendable {
    var database: Data?
    var zones: [String: Data] = [:]

    static func load(from directory: URL) -> SharedBibleTokens {
        (try? Data(contentsOf: directory.appending(path: "tokens.plist")))
            .flatMap { try? PropertyListDecoder().decode(SharedBibleTokens.self, from: $0) } ?? SharedBibleTokens()
    }

    func save(to directory: URL) {
        guard let data = try? PropertyListEncoder().encode(self) else { return }
        try? data.write(to: directory.appending(path: "tokens.plist"), options: .atomic)
    }
}

/// The network half of a refresh, off the main actor.
actor SharedBibleFetcher {
    nonisolated struct ZoneResult: Sendable {
        let ownerName: String
        var changed: [FamilyMirrorRecord] = []
        var deleted: [String] = []
        var token: Data?
        /// The zone token had expired: `changed` is the whole zone.
        var reset = false
        /// The zone is gone: the owner stopped sharing or removed this person.
        var ended = false
    }

    nonisolated struct Result: Sendable {
        var databaseToken: Data?
        var zones: [ZoneResult]
    }

    private let database: CKDatabase

    init(database: CKDatabase) {
        self.database = database
    }

    func fetch(tokens: SharedBibleTokens, known: Set<String>) async throws -> Result {
        // 1. Which shared zones changed or went away since last time.
        var changedZones: [CKRecordZone.ID] = []
        var deletedOwners: Set<String> = []
        var databaseToken = FamilyCloud.unarchiveToken(tokens.database)
        var more = true
        var expiredOnce = false
        while more {
            do {
                let since = databaseToken
                let changes = try await withRetry { try await self.database.databaseChanges(since: since) }
                changedZones += changes.modifications.map(\.zoneID).filter { $0.zoneName == FamilyMirror.zoneName }
                deletedOwners.formUnion(changes.deletions.map(\.zoneID).filter { $0.zoneName == FamilyMirror.zoneName }.map(\.ownerName))
                databaseToken = changes.changeToken
                more = changes.moreComing
            } catch let error as CKError where error.code == .changeTokenExpired && !expiredOnce {
                expiredOnce = true
                databaseToken = nil
                changedZones = []
                deletedOwners = []
            }
        }
        // A participant who was removed may not see a zone deletion: check the known zones
        // are all still there (one request).
        if !known.isEmpty {
            let present = Set(try await withRetry { try await self.database.allRecordZones() }.map(\.zoneID.ownerName))
            deletedOwners.formUnion(known.subtracting(present))
        }
        // A share accepted but never fetched (no zone token yet) is fetched in full.
        for owner in known where tokens.zones[owner] == nil && !changedZones.contains(where: { $0.ownerName == owner }) {
            changedZones.append(CKRecordZone.ID(zoneName: FamilyMirror.zoneName, ownerName: owner))
        }

        // 2. Each changed zone's record changes since its own token.
        var zones: [ZoneResult] = deletedOwners.map { ZoneResult(ownerName: $0, ended: true) }
        for zoneID in changedZones where !deletedOwners.contains(zoneID.ownerName) {
            zones.append(try await fetchZone(zoneID, token: FamilyCloud.unarchiveToken(tokens.zones[zoneID.ownerName])))
        }
        return Result(databaseToken: FamilyCloud.archive(databaseToken), zones: zones)
    }

    private func fetchZone(_ zoneID: CKRecordZone.ID, token start: CKServerChangeToken?) async throws -> ZoneResult {
        var result = ZoneResult(ownerName: zoneID.ownerName)
        var token = start
        var more = true
        while more {
            do {
                let since = token
                let changes = try await withRetry { try await self.database.recordZoneChanges(inZoneWith: zoneID, since: since) }
                for (id, modification) in changes.modificationResultsByID {
                    guard case .success(let change) = modification,
                          let record = FamilyMirrorRecord(ckRecord: change.record) else { continue }
                    result.changed.removeAll { $0.name == id.recordName }
                    result.changed.append(record)
                }
                for deletion in changes.deletions {
                    result.changed.removeAll { $0.name == deletion.recordID.recordName }
                    result.deleted.append(deletion.recordID.recordName)
                }
                token = changes.changeToken
                more = changes.moreComing
            } catch let error as CKError where error.code == .changeTokenExpired && token != nil {
                // Start over from the whole zone.
                result = ZoneResult(ownerName: zoneID.ownerName, reset: true)
                token = nil
            } catch let error as CKError where [.zoneNotFound, .userDeletedZone, .unknownItem].contains(error.code) {
                return ZoneResult(ownerName: zoneID.ownerName, ended: true)
            }
        }
        result.token = FamilyCloud.archive(token)
        return result
    }

    private func withRetry<T: Sendable>(_ work: @Sendable () async throws -> T) async throws -> T {
        var attempt = 0
        while true {
            do { return try await work() } catch {
                attempt += 1
                guard attempt <= 4, let delay = FamilyCloud.retryDelay(for: error, attempt: attempt) else { throw error }
                try await Task.sleep(for: delay)
            }
        }
    }
}
