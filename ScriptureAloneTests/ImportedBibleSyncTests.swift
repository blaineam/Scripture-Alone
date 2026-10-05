import CloudKit
import Foundation
import Testing
@testable import Scripture_Alone

/// The pieces of imported-Bible sync that decide what touches the reader's files: a record name from
/// iCloud becomes a file name, and a fingerprint decides whether a 15 MB translation is downloaded
/// again. CKSyncEngine itself needs an account and is not exercised here.
struct ImportedBibleSyncTests {
    @Test(arguments: ["Imported-1A2B", "my_bible", "ASV2", "deutsch-1912", "Ü" /* a letter is a letter */])
    func namesAnImporterCouldHaveWrittenAreSafe(name: String) {
        #expect(ImportedBibleSync.isSafeName(name))
    }

    /// Everything that could climb out of the imports directory, or name something else in it.
    @Test(arguments: ["", "../ASV", "..", "a/b", "/etc/passwd", "name.sqlite", ".hidden", "a b", "a\\b",
                      "x\u{0000}y", String(repeating: "a", count: 129)])
    func pathsAndOddNamesAreRefused(name: String) {
        #expect(!ImportedBibleSync.isSafeName(name))
    }

    @Test func aStoreIsNamedByItsFileStem() {
        #expect(ImportedBibleSync.name(of: URL(filePath: "/x/Imported/Imported-ABC.sqlite")) == "Imported-ABC")
    }

    @Test func fingerprintIsTheSHA256OfTheFileAndFollowsItsBytes() throws {
        let folder = URL.temporaryDirectory.appending(path: "ImportedBibleSyncTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let a = folder.appending(path: "a.sqlite"), b = folder.appending(path: "b.sqlite")
        try Data("abc".utf8).write(to: a)
        try Data("abc".utf8).write(to: b)
        // SHA-256("abc"), the FIPS 180-2 test vector.
        #expect(ImportedBibleSync.fingerprint(of: a) == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        #expect(ImportedBibleSync.fingerprint(of: a) == ImportedBibleSync.fingerprint(of: b), "same bytes, same print")
        try Data("abd".utf8).write(to: b)
        #expect(ImportedBibleSync.fingerprint(of: a) != ImportedBibleSync.fingerprint(of: b))
        #expect(ImportedBibleSync.fingerprint(of: folder.appending(path: "missing.sqlite")) == nil)
    }

    /// Larger than the 1 MB read chunk, so the chunked read is what is being checked.
    @Test func fingerprintOfAMultiChunkFileMatchesAOneShotHash() throws {
        let url = URL.temporaryDirectory.appending(path: "fp-\(UUID().uuidString).sqlite")
        defer { try? FileManager.default.removeItem(at: url) }
        var bytes = Data(count: (1 << 20) * 2 + 12_345)
        for i in stride(from: 0, to: bytes.count, by: 4_093) { bytes[i] = UInt8(i % 251) }
        try bytes.write(to: url)
        let expected = SHA256Hex.of(bytes)
        #expect(ImportedBibleSync.fingerprint(of: url) == expected)
    }

    /// The ledger keeps a record's system fields so the next save carries the right change tag;
    /// they must come back as the same record.
    @Test func systemFieldsRoundTrip() throws {
        let zone = CKRecordZone.ID(zoneName: ImportedBibleSync.zoneName, ownerName: CKCurrentUserDefaultName)
        let record = CKRecord(recordType: ImportedBibleSync.recordType,
                              recordID: CKRecord.ID(recordName: "Imported-XYZ", zoneID: zone))
        record["name"] = "My Bible" as NSString
        let data = ImportedBibleSync.systemFields(of: record)
        let back = try #require(ImportedBibleSync.record(fromSystemFields: data))
        #expect(back.recordID == record.recordID)
        #expect(back.recordType == ImportedBibleSync.recordType)
        #expect(back["name"] == nil, "system fields only: no field values, no asset")
        #expect(ImportedBibleSync.record(fromSystemFields: Data("junk".utf8)) == nil)
    }
}

import CryptoKit
enum SHA256Hex {
    static func of(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
}
