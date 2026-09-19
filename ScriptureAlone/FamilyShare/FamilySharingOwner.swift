import Foundation
import CloudKit
import Observation
import ScriptureAloneCore

/// The owner's side of "Share with Family": whether their Bible is shared, who with, and the
/// mirror that keeps family's copy current. Nothing here runs until the owner turns sharing on
/// (or one of their other devices already has — an iCloud key-value flag says so).
@Observable
final class FamilySharingOwner {
    static let shared = FamilySharingOwner()

    enum Status: Equatable {
        /// Not checked yet this launch.
        case unknown
        case off
        case working
        case on
        /// This build or account can't use CloudKit.
        case unavailable(String)
    }

    enum SyncState: Equatable {
        case idle
        case syncing
        case upToDate(Date)
        case problem(String)
    }

    private(set) var status = Status.unknown
    private(set) var participants: [FamilyParticipant] = []
    private(set) var sync = SyncState.idle
    private(set) var lastError: String?
    private(set) var share: CKShare?

    private var uploader: FamilyMirrorUploader?
    private var lastInput: FamilyMirrorInput?

    var isSharing: Bool { status == .on }

    /// Whether this person has turned sharing on here or on another of their devices.
    var mightBeSharing: Bool {
        NSUbiquitousKeyValueStore.default.bool(forKey: FamilyCloud.ownerSharingKey)
            || UserDefaults.standard.bool(forKey: FamilyCloud.ownerSharingKey)
    }

    private init() {
        #if DEBUG
        if FamilyDebug.fakeOwner {
            status = .on
            participants = FamilyDebug.participants
            sync = .upToDate(.now.addingTimeInterval(-90))
        }
        #endif
    }

    private func setFlag(_ on: Bool) {
        NSUbiquitousKeyValueStore.default.set(on, forKey: FamilyCloud.ownerSharingKey)
        UserDefaults.standard.set(on, forKey: FamilyCloud.ownerSharingKey)
    }

    private func makeUploader(_ container: CKContainer) -> FamilyMirrorUploader {
        if let uploader { return uploader }
        let made = FamilyMirrorUploader(database: container.privateCloudDatabase, zoneID: FamilyCloud.ownerZoneID) { outcome in
            await FamilySharingOwner.shared.apply(outcome)
        }
        uploader = made
        return made
    }

    // MARK: Status

    /// Looks for the zone's share in the owner's private database. Cheap (one record), and
    /// skipped entirely for people who have never shared.
    func refresh(force: Bool = false) async {
        #if DEBUG
        if FamilyDebug.fakeOwner { return }
        #endif
        guard force || mightBeSharing || status == .on else {
            if status == .unknown { status = .off }
            return
        }
        guard let container = FamilyCloud.container else {
            status = .unavailable("Family sharing needs a signed build with iCloud.")
            return
        }
        do {
            let record = try await container.privateCloudDatabase.record(for: FamilyCloud.shareID(in: FamilyCloud.ownerZoneID))
            guard let share = record as? CKShare else { throw CKError(.unknownItem) }
            adopt(share)
            status = .on
            setFlag(true)
            _ = makeUploader(container)
            if let lastInput { mirror(lastInput) }
        } catch let error as CKError where [.unknownItem, .zoneNotFound, .userDeletedZone].contains(error.code) {
            // Stopped here or on another device.
            await sharingEnded()
        } catch {
            // Offline or signed out: keep what we knew.
            if status == .unknown { status = mightBeSharing ? .on : .off }
            lastError = FamilyCloud.describe(error)
        }
    }

    private func adopt(_ share: CKShare) {
        self.share = share
        participants = share.participants.enumerated().map { FamilyParticipant($1, index: $0) }
            .filter { $0.status != .removed }
            .sorted { ($0.status == .owner ? 0 : 1, $0.name) < ($1.status == .owner ? 0 : 1, $1.name) }
    }

    // MARK: Start and stop

    /// Creates the zone and its zone-wide, invite-only, read-only share, then uploads everything.
    /// Only ever called from the owner tapping "Start Sharing".
    func start(profile: FamilyOwnerProfile, input: FamilyMirrorInput) async -> Bool {
        #if DEBUG
        if FamilyDebug.fakeOwner { status = .on; return true }
        #endif
        guard let container = FamilyCloud.container else {
            status = .unavailable("Family sharing needs a signed build with iCloud.")
            return false
        }
        status = .working
        lastError = nil
        do {
            guard try await container.accountStatus() == .available else { throw CKError(.notAuthenticated) }
            let database = container.privateCloudDatabase
            let zone = CKRecordZone(zoneID: FamilyCloud.ownerZoneID)
            let zoneResult = try await database.modifyRecordZones(saving: [zone], deleting: [])
            if case .failure(let error)? = zoneResult.saveResults[zone.zoneID] { throw error }

            let share = CKShare(recordZoneID: zone.zoneID)
            share[CKShare.SystemFieldKey.title] = KeepsakeManifest(ownerName: profile.ownerName).displayTitle as NSString
            // Invite-only: nobody can open it with just the link.
            share.publicPermission = .none
            let profileRecord = FamilyMirror.record(for: profile).ckRecord(in: zone.zoneID)
            let saved = try await database.modifyRecords(saving: [share, profileRecord], deleting: [], savePolicy: .allKeys, atomically: true)
            guard case .success(let record)? = saved.saveResults[share.recordID], let savedShare = record as? CKShare else {
                if case .failure(let error)? = saved.saveResults[share.recordID] { throw error }
                throw CKError(.internalError)
            }
            adopt(savedShare)
            status = .on
            setFlag(true)
            let uploader = makeUploader(container)
            await uploader.startFresh()
            lastInput = input
            await uploader.schedule(input, after: .zero)
            return true
        } catch {
            status = .off
            lastError = FamilyCloud.describe(error)
            return false
        }
    }

    /// Deletes the zone — and with it the share and every mirrored record. Family keep the last
    /// copy their device fetched, marked as ended.
    func stop() async {
        #if DEBUG
        if FamilyDebug.fakeOwner { status = .off; participants = []; return }
        #endif
        guard let container = FamilyCloud.container else { return }
        status = .working
        do {
            let result = try await container.privateCloudDatabase.modifyRecordZones(saving: [], deleting: [FamilyCloud.ownerZoneID])
            if case .failure(let error)? = result.deleteResults[FamilyCloud.ownerZoneID],
               (error as? CKError)?.code != .zoneNotFound {
                throw error
            }
            await sharingEnded()
        } catch {
            status = .on
            lastError = FamilyCloud.describe(error)
        }
    }

    /// The share is gone (stopped here, in the sharing sheet, or on another device).
    func sharingEnded() async {
        setFlag(false)
        share = nil
        participants = []
        sync = .idle
        status = .off
        await uploader?.forget()
    }

    /// An error from the system sharing sheet.
    func note(_ error: any Error) {
        lastError = FamilyCloud.describe(error)
    }

    /// The system sharing sheet stopped sharing: it deleted the share; remove the zone too.
    func sharingSheetStopped() {
        Task { await stop() }
    }

    // MARK: Mirror

    /// Called whenever the owner's library changes while sharing is on. Debounced in the uploader.
    func mirror(_ input: FamilyMirrorInput) {
        lastInput = input
        guard status == .on, let container = FamilyCloud.container else { return }
        let uploader = makeUploader(container)
        Task { await uploader.schedule(input) }
    }

    /// Send pending changes now (the app is going to the background).
    func flush() async {
        await uploader?.flush()
    }

    private func apply(_ outcome: FamilyMirrorUploader.Outcome) async {
        switch outcome {
        case .syncing: sync = .syncing
        case .upToDate(let date): sync = .upToDate(date)
        case .failed(let message): sync = .problem(message)
        case .zoneMissing: await sharingEnded()
        }
    }
}
