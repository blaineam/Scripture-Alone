import Foundation
import CloudKit
import ScriptureAloneCore

/// Keeps the owner's FamilyBible zone in step with their highlights, notes and favorites, off
/// the main actor. Changes are debounced into one upload; each upload sends only what differs
/// from what the zone already holds (by stable record name and fingerprint), in batches CloudKit
/// accepts, with retries for transient failures. What has been uploaded is remembered on disk,
/// and rebuilt from the zone itself on a device that hasn't uploaded before.
actor FamilyMirrorUploader {
    nonisolated enum Outcome: Sendable, Equatable {
        case syncing
        case upToDate(Date)
        /// The zone is gone — sharing was stopped (perhaps on another device).
        case zoneMissing
        case failed(String)
    }

    private let database: CKDatabase
    private let zoneID: CKRecordZone.ID
    private let manifestURL: URL
    private let report: @Sendable (Outcome) async -> Void

    /// Record name → fingerprint of what the zone holds. Nil until known.
    private var uploaded: [String: String]?
    private var pending: FamilyMirrorInput?
    private var debounce: Task<Void, Never>?
    private var running = false

    init(database: CKDatabase, zoneID: CKRecordZone.ID, report: @escaping @Sendable (Outcome) async -> Void) {
        self.database = database
        self.zoneID = zoneID
        self.report = report
        manifestURL = FamilyCloud.directory("FamilyShare").appending(path: "uploaded.json")
        uploaded = (try? Data(contentsOf: manifestURL)).flatMap { try? JSONDecoder().decode([String: String].self, from: $0) }
    }

    /// Queues the latest state; bursts (a multi-verse highlight, an iCloud import) coalesce.
    func schedule(_ input: FamilyMirrorInput, after delay: Duration = .seconds(3)) {
        pending = input
        debounce?.cancel()
        debounce = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled else { return }
            await self?.flush()
        }
    }

    /// Uploads whatever is pending now (going to the background, pull-to-refresh).
    func flush() async {
        guard !running, let input = pending else { return }
        running = true
        pending = nil
        await report(.syncing)
        let outcome = await upload(input)
        running = false
        await report(outcome)
        // Something changed while uploading: go again shortly.
        if let next = pending, outcome != .zoneMissing { schedule(next, after: .seconds(1)) }
    }

    /// A fresh zone was just created: it holds nothing yet.
    func startFresh() {
        uploaded = [:]
        persist()
    }

    /// Sharing stopped: forget everything, including anything queued.
    func forget() {
        debounce?.cancel()
        pending = nil
        uploaded = nil
        try? FileManager.default.removeItem(at: manifestURL)
    }

    // MARK: Upload

    private func upload(_ input: FamilyMirrorInput) async -> Outcome {
        let desired = FamilyMirror.records(for: input)
        var attempt = 0
        while true {
            do {
                if uploaded == nil { uploaded = try await readZone() ; persist() }
                let diff = FamilyMirrorDiff.compute(desired: desired, uploaded: uploaded ?? [:])
                for batch in diff.batches() { try await send(batch) }
                return .upToDate(.now)
            } catch let error as CKError where error.code == .zoneNotFound || error.code == .userDeletedZone {
                uploaded = nil
                try? FileManager.default.removeItem(at: manifestURL)
                return .zoneMissing
            } catch {
                attempt += 1
                guard attempt <= 6, let delay = FamilyCloud.retryDelay(for: error, attempt: attempt) else {
                    return .failed(FamilyCloud.describe(error))
                }
                try? await Task.sleep(for: delay)
            }
        }
    }

    /// Sends one batch. Successes are recorded even when others fail, so a retry resends only
    /// what didn't make it.
    private func send(_ batch: FamilyMirrorDiff) async throws {
        let records = batch.saves.map { $0.ckRecord(in: zoneID) }
        let fingerprints = Dictionary(uniqueKeysWithValues: batch.saves.map { ($0.name, $0.fingerprint) })
        let deletions = batch.deletions.map { CKRecord.ID(recordName: $0, zoneID: zoneID) }
        let results: (saveResults: [CKRecord.ID: Result<CKRecord, any Error>], deleteResults: [CKRecord.ID: Result<Void, any Error>])
        do {
            // Every owner device derives the same records from the same synced library, so the
            // latest write simply wins: .allKeys overwrites without change-tag conflicts.
            results = try await database.modifyRecords(saving: records, deleting: deletions, savePolicy: .allKeys, atomically: false)
        } catch let error as CKError where error.code == .limitExceeded && batch.saves.count + batch.deletions.count > 1 {
            // Too big for one request (long notes): halve it.
            for half in batch.batches(size: max(1, (batch.saves.count + batch.deletions.count) / 2)) {
                try await send(half)
            }
            return
        }

        var firstError: (any Error)?
        for (id, result) in results.saveResults {
            switch result {
            case .success: uploaded?[id.recordName] = fingerprints[id.recordName]
            case .failure(let error): firstError = firstError ?? error
            }
        }
        for (id, result) in results.deleteResults {
            switch result {
            case .success: uploaded?[id.recordName] = nil
            case .failure(let error as CKError) where error.code == .unknownItem: uploaded?[id.recordName] = nil
            case .failure(let error): firstError = firstError ?? error
            }
        }
        persist()
        if let firstError { throw firstError }
    }

    /// What the zone already holds, from each record's stored fingerprint — for a device that
    /// hasn't uploaded before (sharing was started on the owner's other iPhone or iPad).
    private func readZone() async throws -> [String: String] {
        var result: [String: String] = [:]
        var token: CKServerChangeToken?
        var more = true
        while more {
            let changes = try await database.recordZoneChanges(inZoneWith: zoneID, since: token,
                                                               desiredKeys: [FamilyMirror.fingerprintField])
            for (id, modification) in changes.modificationResultsByID {
                guard id.recordName != CKRecordNameZoneWideShare, case .success(let change) = modification else { continue }
                // A record without a fingerprint is resent (or deleted if no longer wanted).
                result[id.recordName] = change.record.familyFingerprint ?? ""
            }
            for deletion in changes.deletions { result[deletion.recordID.recordName] = nil }
            token = changes.changeToken
            more = changes.moreComing
        }
        return result
    }

    private func persist() {
        guard let uploaded, let data = try? JSONEncoder().encode(uploaded) else { return }
        try? data.write(to: manifestURL, options: .atomic)
    }
}
