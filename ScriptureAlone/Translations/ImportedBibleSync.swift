import Foundation
import CloudKit
import CryptoKit
import ScriptureAloneCore

/// Carries the translations a reader imported to their other devices, through their own private
/// iCloud database.
///
/// Each import is one record in a zone of its own — the store file as an asset, plus a fingerprint
/// of it — so a device downloads a translation only when it differs from the copy it already has.
/// Only the importer's own account ever sees it: this is the private database, never the shared or
/// public one, and nothing here makes a share. Removing an import on one device removes it on all.
///
/// Launch never waits on any of this. A translation arriving from iCloud appears in the picker when
/// it lands (`changedNotification`), exactly as if it had just been imported here.
actor ImportedBibleSync: CKSyncEngineDelegate {
    static let shared = ImportedBibleSync()

    /// Posted on the main actor when a translation arrived, changed or was removed from iCloud.
    nonisolated static let changedNotification = Notification.Name("ImportedBibleSync.changed")

    nonisolated static let zoneName = "ImportedBibles"
    nonisolated static let recordType = "ImportedBible"

    private enum Field {
        static let store = "store"
        static let fingerprint = "fingerprint"
        static let name = "name"
        static let abbreviation = "abbreviation"
    }

    /// What this device knows about the zone, kept across launches.
    private struct Ledger: Codable {
        /// Store name → fingerprint of the copy iCloud holds.
        var fingerprints: [String: String] = [:]
        /// Store name → the server record's system fields, so a save carries the right change tag.
        var systemFields: [String: Data] = [:]
    }

    private var engine: CKSyncEngine?
    private var ledger: Ledger
    private let stateURL: URL
    private let ledgerURL: URL

    private var zoneID: CKRecordZone.ID {
        CKRecordZone.ID(zoneName: Self.zoneName, ownerName: CKCurrentUserDefaultName)
    }

    private init() {
        let directory = FamilyCloud.directory("TranslationSync")
        stateURL = directory.appending(path: "engine-state.json")
        ledgerURL = directory.appending(path: "ledger.json")
        ledger = (try? Data(contentsOf: ledgerURL)).flatMap { try? JSONDecoder().decode(Ledger.self, from: $0) } ?? Ledger()
    }

    // MARK: - Starting

    /// Starts syncing, once per process. Safe to call from every window.
    func start() {
        guard engine == nil, let container = FamilyCloud.container else { return }
        let state = (try? Data(contentsOf: stateURL))
            .flatMap { try? JSONDecoder().decode(CKSyncEngine.State.Serialization.self, from: $0) }
        let engine = CKSyncEngine(CKSyncEngine.Configuration(database: container.privateCloudDatabase,
                                                             stateSerialization: state, delegate: self))
        self.engine = engine
        if state == nil {
            engine.state.add(pendingDatabaseChanges: [.saveZone(CKRecordZone(zoneID: zoneID))])
        }
        reconcile()
    }

    /// Queues whatever differs between this device's imports and what iCloud holds: an import
    /// made before sync existed, or while signed out; a removal made while the engine wasn't running.
    private func reconcile() {
        guard let engine else { return }
        let local = Self.localStores()
        var changes: [CKSyncEngine.PendingRecordZoneChange] = []
        for (name, url) in local where ledger.fingerprints[name] != Self.fingerprint(of: url) {
            changes.append(.saveRecord(recordID(name)))
        }
        for name in ledger.fingerprints.keys where local[name] == nil {
            changes.append(.deleteRecord(recordID(name)))
        }
        if !changes.isEmpty { engine.state.add(pendingRecordZoneChanges: changes) }
    }

    // MARK: - Local changes

    /// A store was written here — a new import, or a re-import over an old one.
    func storeWritten(at url: URL) {
        engine?.state.add(pendingRecordZoneChanges: [.saveRecord(recordID(Self.name(of: url)))])
    }

    /// A store was removed here.
    func storeRemoved(at url: URL) {
        engine?.state.add(pendingRecordZoneChanges: [.deleteRecord(recordID(Self.name(of: url)))])
    }

    // MARK: - CKSyncEngineDelegate

    func handleEvent(_ event: CKSyncEngine.Event, syncEngine: CKSyncEngine) async {
        switch event {
        case .stateUpdate(let update):
            if let data = try? JSONEncoder().encode(update.stateSerialization) {
                try? data.write(to: stateURL, options: .atomic)
            }

        case .accountChange(let change):
            switch change.changeType {
            case .signIn:
                reconcile()
            case .signOut, .switchAccounts:
                // The imports stay on the device; they simply stop being mirrored to an account
                // that is no longer this one's.
                ledger = Ledger()
                saveLedger()
            @unknown default:
                break
            }

        case .fetchedDatabaseChanges(let changes):
            if changes.deletions.contains(where: { $0.zoneID == zoneID }) {
                // The zone was erased (Settings ▸ iCloud ▸ Manage Storage). Keep the local copies;
                // forget what the cloud held, so nothing is removed on the strength of it.
                ledger = Ledger()
                saveLedger()
            }

        case .fetchedRecordZoneChanges(let changes):
            var changed = false
            for modification in changes.modifications where modification.record.recordType == Self.recordType {
                changed = apply(modification.record) || changed
            }
            for deletion in changes.deletions where deletion.recordType == Self.recordType {
                changed = removeLocal(deletion.recordID.recordName) || changed
            }
            saveLedger()
            if changed { await Self.announceChange() }

        case .sentRecordZoneChanges(let sent):
            var changed = false
            for record in sent.savedRecords {
                let name = record.recordID.recordName
                ledger.fingerprints[name] = record[Field.fingerprint] as? String
                ledger.systemFields[name] = Self.systemFields(of: record)
            }
            for id in sent.deletedRecordIDs {
                ledger.fingerprints[id.recordName] = nil
                ledger.systemFields[id.recordName] = nil
            }
            for failure in sent.failedRecordSaves {
                let id = failure.record.recordID
                switch failure.error.code {
                case .serverRecordChanged:
                    // Another device saved this translation first. Take theirs: an import is
                    // replaced whole, and the other device's is as new as ours.
                    if let server = failure.error.serverRecord { changed = apply(server) || changed }
                case .zoneNotFound:
                    syncEngine.state.add(pendingDatabaseChanges: [.saveZone(CKRecordZone(zoneID: zoneID))])
                    syncEngine.state.add(pendingRecordZoneChanges: [.saveRecord(id)])
                case .unknownItem:
                    ledger.systemFields[id.recordName] = nil
                    syncEngine.state.add(pendingRecordZoneChanges: [.saveRecord(id)])
                default:
                    // Network and rate-limit failures are retried by the engine itself.
                    break
                }
            }
            saveLedger()
            if changed { await Self.announceChange() }

        default:
            break
        }
    }

    func nextRecordZoneChangeBatch(_ context: CKSyncEngine.SendChangesContext,
                                   syncEngine: CKSyncEngine) async -> CKSyncEngine.RecordZoneChangeBatch? {
        let pending = syncEngine.state.pendingRecordZoneChanges.filter { context.options.scope.contains($0) }
        guard !pending.isEmpty else { return nil }
        return await CKSyncEngine.RecordZoneChangeBatch(pendingChanges: pending) { id in
            await self.record(for: id, engine: syncEngine)
        }
    }

    // MARK: - Records

    private func recordID(_ name: String) -> CKRecord.ID {
        CKRecord.ID(recordName: name, zoneID: zoneID)
    }

    /// The record to upload for a store, or nil when the store is gone (the save is then dropped).
    private func record(for id: CKRecord.ID, engine: CKSyncEngine) -> CKRecord? {
        let name = id.recordName
        guard let url = Self.localStores()[name], let info = try? BibleStore(url: url).info else {
            engine.state.remove(pendingRecordZoneChanges: [.saveRecord(id)])
            return nil
        }
        let record = ledger.systemFields[name].flatMap(Self.record(fromSystemFields:))
            ?? CKRecord(recordType: Self.recordType, recordID: id)
        record[Field.store] = CKAsset(fileURL: url)
        record[Field.fingerprint] = Self.fingerprint(of: url)
        record[Field.name] = info.name
        record[Field.abbreviation] = info.abbreviation
        return record
    }

    /// Brings a record's store onto this device. Returns true when the library changed.
    private func apply(_ record: CKRecord) -> Bool {
        let name = record.recordID.recordName
        ledger.systemFields[name] = Self.systemFields(of: record)
        let fingerprint = record[Field.fingerprint] as? String
        ledger.fingerprints[name] = fingerprint
        let target = Self.directory.appending(path: name + ".sqlite")
        if FileManager.default.fileExists(atPath: target.path), Self.fingerprint(of: target) == fingerprint {
            return false
        }
        guard Self.isSafeName(name), let asset = record[Field.store] as? CKAsset, let source = asset.fileURL else {
            return false
        }
        // Written beside the others under a name `ImportedLibrary` ignores, checked, then swapped in:
        // a half-downloaded or damaged file never replaces a good one.
        let staging = Self.directory.appending(path: ".\(name).incoming")
        try? FileManager.default.removeItem(at: staging)
        do {
            try FileManager.default.copyItem(at: source, to: staging)
            _ = try BibleStore(url: staging)
            if FileManager.default.fileExists(atPath: target.path) {
                _ = try FileManager.default.replaceItemAt(target, withItemAt: staging)
            } else {
                try FileManager.default.moveItem(at: staging, to: target)
            }
            return true
        } catch {
            try? FileManager.default.removeItem(at: staging)
            return false
        }
    }

    private func removeLocal(_ name: String) -> Bool {
        ledger.fingerprints[name] = nil
        ledger.systemFields[name] = nil
        guard Self.isSafeName(name) else { return false }
        let url = Self.directory.appending(path: name + ".sqlite")
        guard FileManager.default.fileExists(atPath: url.path) else { return false }
        try? FileManager.default.removeItem(at: url)
        return true
    }

    private func saveLedger() {
        if let data = try? JSONEncoder().encode(ledger) { try? data.write(to: ledgerURL, options: .atomic) }
    }

    @MainActor private static func announceChange() {
        NotificationCenter.default.post(name: changedNotification, object: nil)
    }

    // MARK: - Files

    private static var directory: URL { ImportedLibrary.directory }

    /// Store name (the file name without `.sqlite`) → file.
    private static func localStores() -> [String: URL] {
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        return Dictionary(files.filter { $0.pathExtension == "sqlite" }.map { (name(of: $0), $0) },
                          uniquingKeysWith: { first, _ in first })
    }

    private static func name(of url: URL) -> String { url.deletingPathExtension().lastPathComponent }

    /// A record name from iCloud becomes a file name; it must be one `BibleFileImporter` could
    /// have written, never a path.
    private static func isSafeName(_ name: String) -> Bool {
        !name.isEmpty && name.count <= 128
            && name.allSatisfy { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }
    }

    /// SHA-256 of the file, read in chunks so a whole Bible never sits in memory twice.
    static func fingerprint(of url: URL) -> String? {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        var hasher = SHA256()
        while let chunk = try? handle.read(upToCount: 1 << 20), !chunk.isEmpty {
            hasher.update(data: chunk)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    private static func systemFields(of record: CKRecord) -> Data {
        let coder = NSKeyedArchiver(requiringSecureCoding: true)
        record.encodeSystemFields(with: coder)
        coder.finishEncoding()
        return coder.encodedData
    }

    private static func record(fromSystemFields data: Data) -> CKRecord? {
        guard let coder = try? NSKeyedUnarchiver(forReadingFrom: data) else { return nil }
        coder.requiresSecureCoding = true
        defer { coder.finishDecoding() }
        return CKRecord(coder: coder)
    }
}
