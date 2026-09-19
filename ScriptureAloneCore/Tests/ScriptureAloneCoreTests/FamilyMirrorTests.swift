import Foundation
import CloudKit
import Testing
@testable import ScriptureAloneCore

@Suite struct FamilyMirrorTests {
    static func date(_ seconds: Double) -> Date { Date(timeIntervalSince1970: seconds) }

    static let bibleID = UUID(uuidString: "6F1B7C9E-4C1D-4B0E-9A53-2E3C2D1A0B01")!
    static let noteID = UUID(uuidString: "11111111-2222-3333-4444-555555555555")!
    static let favoriteID = UUID(uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")!
    static let john316 = VerseRef(.john, 3, 16).key
    static let psalm23 = VerseRef(.psalms, 23, 1).key

    static func input() -> FamilyMirrorInput {
        FamilyMirrorInput(
            profile: FamilyOwnerProfile(bibleID: bibleID, ownerName: "Dad", dedication: "For Anna.", preferredTranslation: "ASV"),
            highlights: [
                KeepsakeHighlight(verse: john316, color: "yellow", createdAt: date(1_700_000_000.5)),
                KeepsakeHighlight(verse: psalm23, color: "blue", createdAt: date(1_710_000_000)),
            ],
            notes: [
                KeepsakeNote(id: noteID, title: "No condemnation", body: "Verse 1 is the hinge.",
                             anchors: [VerseRange(VerseRef(.romans, 8, 1), VerseRef(.romans, 8, 17)),
                                       VerseRange(VerseRef(.john, 3, 16))],
                             createdAt: date(1_720_000_000.25), updatedAt: date(1_730_000_000), origin: "camera"),
            ],
            favorites: [FamilyFavorite(id: favoriteID, start: VerseRef(.romans, 8, 38).key,
                                       end: VerseRef(.romans, 8, 39).key, createdAt: date(1_740_000_000))])
    }

    static func uploaded(_ records: [FamilyMirrorRecord]) -> [String: String] {
        Dictionary(uniqueKeysWithValues: records.map { ($0.name, $0.fingerprint) })
    }

    // MARK: Mapping

    @Test func recordNamesAreStable() {
        let records = FamilyMirror.records(for: Self.input())
        // Profile first, then highlights in verse order (Psalms before John), notes, favorites.
        #expect(records.map(\.name) == [
            "profile",
            "h-\(Self.psalm23)", "h-\(Self.john316)",
            "n-\(Self.noteID.uuidString)",
            "f-\(Self.favoriteID.uuidString)",
        ])
        #expect(FamilyMirror.records(for: Self.input()) == records, "mapping is deterministic")
    }

    @Test func everyItemRoundTrips() throws {
        let input = Self.input()
        var snapshot = FamilyBibleSnapshot()
        snapshot.apply(changed: FamilyMirror.records(for: input), deleted: [])
        #expect(snapshot.profile == input.profile)
        #expect(snapshot.highlights.values.sorted { $0.verse < $1.verse } == input.highlights.sorted { $0.verse < $1.verse })
        #expect(Array(snapshot.notes.values) == input.notes)
        #expect(Array(snapshot.favorites.values) == input.favorites)
    }

    @Test func duplicateHighlightsCollapseToNewest() {
        var input = Self.input()
        input.highlights.append(KeepsakeHighlight(verse: Self.john316, color: "pink", createdAt: Self.date(1_800_000_000)))
        input.highlights.append(KeepsakeHighlight(verse: Self.john316, color: "green", createdAt: Self.date(1_600_000_000)))
        let records = FamilyMirror.records(for: input).filter { $0.name == "h-\(Self.john316)" }
        #expect(records.count == 1)
        #expect(records.first?.fields["color"] == .string("pink"))
    }

    @Test func duplicateNotesKeepLatestEdit() {
        var input = Self.input()
        var older = input.notes[0]
        older.body = "stale"
        older.updatedAt = Self.date(1_000)
        input.notes.insert(older, at: 0)
        let records = FamilyMirror.records(for: input).filter { $0.type == FamilyMirror.RecordType.note }
        #expect(records.count == 1)
        #expect(records.first?.fields["body"] == .string("Verse 1 is the hinge."))
    }

    @Test func emptyProfileFieldsAreOmitted() {
        let record = FamilyMirror.record(for: FamilyOwnerProfile(bibleID: Self.bibleID, ownerName: "  ", dedication: nil))
        #expect(record.fields["ownerName"] == nil)
        #expect(record.fields["dedication"] == nil)
        #expect(record.fields["bibleID"] == .string(Self.bibleID.uuidString))
    }

    @Test func unknownAndBrokenRecordsAreIgnored() {
        #expect(FamilyMirror.item(from: FamilyMirrorRecord(type: "FamilyFuture", name: "x", fields: [:])) == nil)
        #expect(FamilyMirror.item(from: FamilyMirrorRecord(type: FamilyMirror.RecordType.note, name: "n-not-a-uuid", fields: [:])) == nil)
        #expect(FamilyMirror.item(from: FamilyMirrorRecord(type: FamilyMirror.RecordType.highlight, name: "h-5",
                                                           fields: ["verse": .int(5)])) == nil, "verse 5 is not a real verse")
        // Missing fields get defaults, like the keepsake format.
        let sparse = FamilyMirror.item(from: FamilyMirrorRecord(type: FamilyMirror.RecordType.highlight,
                                                                name: "h-\(Self.john316)", fields: [:]))
        #expect(sparse == .highlight(KeepsakeHighlight(verse: Self.john316, color: "yellow", createdAt: .distantPast)))
    }

    // MARK: Fingerprints and diff

    @Test func fingerprintTracksVisibleChanges() {
        let base = FamilyMirror.record(for: Self.input().notes[0])
        var edited = Self.input().notes[0]
        edited.body += "!"
        #expect(FamilyMirror.record(for: edited).fingerprint != base.fingerprint)
        // Sub-millisecond noise doesn't count: CloudKit wouldn't keep it anyway.
        var jitter = Self.input().notes[0]
        jitter.updatedAt = jitter.updatedAt.addingTimeInterval(0.0001)
        #expect(FamilyMirror.record(for: jitter).fingerprint == base.fingerprint)
        #expect(base.fingerprint.count == 32)
    }

    @Test func firstUploadSendsEverything() {
        let desired = FamilyMirror.records(for: Self.input())
        let diff = FamilyMirrorDiff.compute(desired: desired, uploaded: [:])
        #expect(diff.saves == desired)
        #expect(diff.deletions.isEmpty)
    }

    @Test func unchangedLibraryDiffsToNothing() {
        let desired = FamilyMirror.records(for: Self.input())
        #expect(FamilyMirrorDiff.compute(desired: desired, uploaded: Self.uploaded(desired)).isEmpty)
    }

    @Test func incrementalChangesOnlySendWhatChanged() {
        let before = FamilyMirror.records(for: Self.input())
        var input = Self.input()
        input.highlights[0].color = "green"                     // recolor John 3:16
        input.highlights.remove(at: 1)                          // unhighlight Psalm 23:1
        input.favorites.removeAll()                             // unfavorite Romans 8:38–39
        let newID = UUID()
        input.notes.append(KeepsakeNote(id: newID, title: "New", body: "", anchors: [],
                                        createdAt: Self.date(1_750_000_000), updatedAt: Self.date(1_750_000_000)))
        let diff = FamilyMirrorDiff.compute(desired: FamilyMirror.records(for: input), uploaded: Self.uploaded(before))
        #expect(Set(diff.saves.map(\.name)) == ["h-\(Self.john316)", "n-\(newID.uuidString)"])
        #expect(diff.deletions == ["f-\(Self.favoriteID.uuidString)", "h-\(Self.psalm23)"])
    }

    @Test func profileChangeIsItsOwnSave() {
        let before = FamilyMirror.records(for: Self.input())
        var input = Self.input()
        input.profile.dedication = "For Anna and Sam."
        let diff = FamilyMirrorDiff.compute(desired: FamilyMirror.records(for: input), uploaded: Self.uploaded(before))
        #expect(diff.saves.map(\.name) == ["profile"])
        #expect(diff.deletions.isEmpty)
    }

    @Test func batchesRespectTheLimitAndPutTheProfileFirst() {
        let notes = (0..<7).map { i in
            KeepsakeNote(id: UUID(), title: "\(i)", body: "", anchors: [], createdAt: Self.date(0), updatedAt: Self.date(0))
        }
        var input = Self.input()
        input.notes = notes
        let desired = FamilyMirror.records(for: input)
        let stale = (0..<4).map { "n-gone-\($0)" }
        var uploaded: [String: String] = [:]
        for name in stale { uploaded[name] = "x" }
        let diff = FamilyMirrorDiff.compute(desired: desired, uploaded: uploaded)
        let batches = diff.batches(size: 5)
        #expect(batches.allSatisfy { $0.saves.count + $0.deletions.count <= 5 })
        #expect(batches.first?.saves.first?.name == "profile")
        #expect(batches.flatMap(\.saves).count == desired.count)
        #expect(batches.flatMap(\.deletions).sorted() == stale.sorted())
    }

    // MARK: Participant snapshot

    @Test func snapshotAppliesDeletionsAndBecomesAKeepsake() {
        var snapshot = FamilyBibleSnapshot()
        snapshot.apply(changed: FamilyMirror.records(for: Self.input()), deleted: [])
        snapshot.apply(changed: [], deleted: ["h-\(Self.psalm23)", "f-\(Self.favoriteID.uuidString)", "n-unknown"])
        #expect(snapshot.highlights.count == 1)
        #expect(snapshot.favorites.isEmpty)
        #expect(snapshot.notes.count == 1)

        let keepsake = snapshot.keepsake(fallbackID: UUID(), createdAt: Self.date(1_790_000_000))
        #expect(keepsake.id == Self.bibleID, "a live share and a keepsake from the same person share a bibleID")
        #expect(keepsake.manifest.displayTitle == "Dad’s Bible")
        #expect(keepsake.manifest.dedication == "For Anna.")
        #expect(keepsake.manifest.counts == .init(highlights: 1, notes: 1))
        #expect(keepsake.highlights.map(\.verse) == [Self.john316])

        // Keeping it as a keepsake survives the file format.
        let decoded = try? KeepsakeArchive.decode(KeepsakeArchive.encode(keepsake))
        #expect(decoded?.notes == keepsake.notes)
    }

    @Test func snapshotWithoutProfileUsesFallbackID() {
        let fallback = UUID()
        var snapshot = FamilyBibleSnapshot()
        snapshot.apply(changed: [FamilyMirror.record(for: Self.input().highlights[0])], deleted: [])
        #expect(snapshot.keepsake(fallbackID: fallback).id == fallback)
        #expect(snapshot.keepsake(fallbackID: fallback).manifest.displayTitle == "A Keepsake Bible")
    }

    @Test func snapshotIsCodable() throws {
        var snapshot = FamilyBibleSnapshot()
        snapshot.apply(changed: FamilyMirror.records(for: Self.input()), deleted: [])
        let data = try JSONEncoder().encode(snapshot)
        #expect(try JSONDecoder().decode(FamilyBibleSnapshot.self, from: data) == snapshot)
    }

    // MARK: CloudKit bridge

    @Test func ckRecordRoundTrip() throws {
        let zone = CKRecordZone.ID(zoneName: FamilyMirror.zoneName, ownerName: CKCurrentUserDefaultName)
        for record in FamilyMirror.records(for: Self.input()) {
            let ck = record.ckRecord(in: zone)
            #expect(ck.recordType == record.type)
            #expect(ck.recordID.recordName == record.name)
            #expect(ck.recordID.zoneID == zone)
            #expect(ck.familyFingerprint == record.fingerprint)
            let back = try #require(FamilyMirrorRecord(ckRecord: ck))
            #expect(back.fingerprint == record.fingerprint, "\(record.name) survives CKRecord")
            #expect(FamilyMirror.item(from: back) == FamilyMirror.item(from: record))
        }
    }

    @Test func shareRecordsAreNotMirrorRecords() {
        let zone = CKRecordZone.ID(zoneName: FamilyMirror.zoneName, ownerName: CKCurrentUserDefaultName)
        let share = CKShare(recordZoneID: zone)
        #expect(FamilyMirrorRecord(ckRecord: share) == nil)
    }
}
