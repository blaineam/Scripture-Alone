import Foundation
import CloudKit
import ScriptureAloneCore
#if targetEnvironment(simulator)
import MachO
#elseif os(macOS)
import Security
#endif

/// Where live family sharing lives: the app's own CloudKit container. The owner's mirror is a
/// custom zone in their private database; participants read it from their shared database.
/// There is no server of ours anywhere in this path.
nonisolated enum FamilyCloud {
    static let containerID = "iCloud.com.blainemiller.ScriptureAlone"

    /// iCloud key-value flags, so an owner's other devices know to keep the mirror current and a
    /// participant's other devices know to look in their shared database.
    static let ownerSharingKey = "family.owner.sharing"
    static let participantKey = "family.participant.active"

    /// The owner's zone in their own private database.
    static var ownerZoneID: CKRecordZone.ID {
        CKRecordZone.ID(zoneName: FamilyMirror.zoneName, ownerName: CKCurrentUserDefaultName)
    }

    /// The zone-wide share's record ID in a zone.
    static func shareID(in zone: CKRecordZone.ID) -> CKRecord.ID {
        CKRecord.ID(recordName: CKRecordNameZoneWideShare, zoneID: zone)
    }

    /// Whether this build can talk to CloudKit at all. `CKContainer(identifier:)` raises an
    /// Objective-C exception (a crash) when the running binary isn't entitled to the container —
    /// which is the case for unsigned simulator and Mac builds. Device builds are always signed
    /// with the container, so only those two need checking.
    static let isEntitled: Bool = {
        #if DEBUG
        if FamilyDebug.isFaking { return false }
        #endif
        #if targetEnvironment(simulator)
        // Simulator builds carry their entitlements in the executable's __TEXT,__entitlements.
        guard let header = _dyld_get_image_header(0) else { return false }
        var size: UInt = 0
        let raw = header.withMemoryRebound(to: mach_header_64.self, capacity: 1) {
            getsectiondata($0, "__TEXT", "__entitlements", &size)
        }
        guard let raw, size > 0 else { return false }
        let text = String(decoding: UnsafeBufferPointer(start: raw, count: Int(size)), as: UTF8.self)
        return text.contains(containerID)
        #elseif os(macOS)
        guard let task = SecTaskCreateFromSelf(nil),
              let value = SecTaskCopyValueForEntitlement(task, "com.apple.developer.icloud-container-identifiers" as CFString, nil)
        else { return false }
        return (value as? [String])?.contains(containerID) ?? false
        #else
        return true
        #endif
    }()

    /// Nil when this build can't use CloudKit (see `isEntitled`).
    static var container: CKContainer? { isEntitled ? CKContainer(identifier: containerID) : nil }

    /// Errors CloudKit says are worth retrying, and how long to wait.
    static func retryDelay(for error: any Error, attempt: Int) -> Duration? {
        guard let error = error as? CKError else { return nil }
        switch error.code {
        case .networkUnavailable, .networkFailure, .serviceUnavailable, .requestRateLimited,
             .zoneBusy, .serverResponseLost, .operationCancelled:
            let suggested = error.retryAfterSeconds ?? pow(2, Double(min(attempt, 8)))
            return .seconds(min(max(suggested, 1), 300))
        default:
            return nil
        }
    }

    /// Plain-language text for the errors people can do something about.
    static func describe(_ error: any Error) -> String {
        guard let error = error as? CKError else { return error.localizedDescription }
        switch error.code {
        case .notAuthenticated: return String(localized: "Sign in to iCloud in Settings to use family sharing.")
        case .quotaExceeded: return String(localized: "Your iCloud storage is full, so recent changes can’t reach your family. Free up space in Settings → iCloud.")
        case .networkUnavailable, .networkFailure: return String(localized: "No connection right now. Changes will go out when you’re back online.")
        case .participantMayNeedVerification: return String(localized: "iCloud needs to confirm this invitation. Open the link again from Messages or Mail.")
        case .permissionFailure: return String(localized: "iCloud didn’t allow this. Check that you’re signed in to the Apple Account the invitation was sent to.")
        default: return error.localizedDescription
        }
    }

    static func archive(_ token: CKServerChangeToken?) -> Data? {
        token.flatMap { try? NSKeyedArchiver.archivedData(withRootObject: $0, requiringSecureCoding: true) }
    }

    static func unarchiveToken(_ data: Data?) -> CKServerChangeToken? {
        data.flatMap { try? NSKeyedUnarchiver.unarchivedObject(ofClass: CKServerChangeToken.self, from: $0) }
    }

    /// Application Support/<name>, created on first use.
    static func directory(_ name: String) -> URL {
        let url = URL.applicationSupportDirectory.appending(path: name, directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
}

/// Someone on the owner's share, as the settings screen lists them.
nonisolated struct FamilyParticipant: Identifiable, Hashable, Sendable {
    enum Status: Hashable { case owner, accepted, invited, removed, unknown }

    let id: String
    let name: String
    let detail: String?
    let status: Status

    var statusText: String {
        switch status {
        case .owner: String(localized: "You", comment: "Status of a person a Bible is shared with")
        case .accepted: String(localized: "Can view", comment: "Status of a person a Bible is shared with")
        case .invited: String(localized: "Invited — hasn’t opened it yet", comment: "Status of a person a Bible is shared with")
        case .removed: String(localized: "Removed", comment: "Status of a person a Bible is shared with")
        case .unknown: String(localized: "Waiting for iCloud", comment: "Status of a person a Bible is shared with")
        }
    }

    init(id: String, name: String, detail: String? = nil, status: Status) {
        self.id = id
        self.name = name
        self.detail = detail
        self.status = status
    }

    init(_ participant: CKShare.Participant, index: Int) {
        let identity = participant.userIdentity
        let lookup = identity.lookupInfo
        let contact = lookup?.emailAddress ?? lookup?.phoneNumber
        let formatted = identity.nameComponents.map { $0.formatted() }
        let name = formatted.flatMap { $0.isEmpty ? nil : $0 } ?? contact ?? String(localized: "Someone", comment: "Stands in for a family member whose name iCloud doesn't share")
        self.id = identity.userRecordID?.recordName ?? contact ?? "participant-\(index)"
        self.name = name
        self.detail = contact == name ? nil : contact
        if participant.role == .owner {
            status = .owner
        } else {
            switch participant.acceptanceStatus {
            case .accepted: status = .accepted
            case .pending: status = .invited
            case .removed: status = .removed
            default: status = .unknown
            }
        }
    }
}
