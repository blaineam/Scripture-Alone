import Foundation
import CloudKit

// The thin bridge between mirror records and CKRecord. Building and reading CKRecords needs no
// container or account, so this is unit-tested too.

extension FamilyMirrorRecord {
    /// A CKRecord for the owner's zone, carrying the fingerprint in `fp`.
    public func ckRecord(in zoneID: CKRecordZone.ID) -> CKRecord {
        let record = CKRecord(recordType: type, recordID: CKRecord.ID(recordName: name, zoneID: zoneID))
        for (key, value) in fields {
            switch value {
            case .string(let s): record[key] = s as NSString
            case .int(let i): record[key] = NSNumber(value: Int64(i))
            case .date(let d): record[key] = FamilyMirror.rounded(d) as NSDate
            }
        }
        record[FamilyMirror.fingerprintField] = fingerprint as NSString
        return record
    }

    /// Reads a fetched CKRecord. Returns nil for record types the mirror doesn't know
    /// (including the zone's CKShare).
    public init?(ckRecord record: CKRecord) {
        guard FamilyMirror.RecordType.all.contains(record.recordType) else { return nil }
        var fields: [String: FamilyFieldValue] = [:]
        for key in record.allKeys() where key != FamilyMirror.fingerprintField {
            switch record[key] {
            case let s as String: fields[key] = .string(s)
            case let n as NSNumber: fields[key] = .int(n.intValue)
            case let d as Date: fields[key] = .date(d)
            default: continue
            }
        }
        self.init(type: record.recordType, name: record.recordID.recordName, fields: fields)
    }
}

extension CKRecord {
    /// The fingerprint stored on a mirror record, if any.
    public var familyFingerprint: String? { self[FamilyMirror.fingerprintField] as? String }
}
