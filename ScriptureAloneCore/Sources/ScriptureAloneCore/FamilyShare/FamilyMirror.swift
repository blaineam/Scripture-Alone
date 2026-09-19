import Foundation
import CryptoKit

// Live family sharing: the owner's highlights, notes and favorites are mirrored into a custom
// CloudKit zone ("FamilyBible") in their private database and shared read-only with named family
// members through a zone-wide CKShare. This file is the pure half — record mapping, stable IDs,
// fingerprints and the incremental diff — so it can be unit-tested without CloudKit.
// See docs/heir-mode.md ("Live family sharing").

/// A value stored in one field of a mirror record. Deliberately small: CloudKit stores these as
/// String, Int64 and Date.
public enum FamilyFieldValue: Sendable, Equatable, Hashable, Codable {
    case string(String)
    case int(Int)
    case date(Date)

    /// Canonical text for fingerprints. Dates are whole milliseconds, which is what CloudKit keeps.
    var canonical: String {
        switch self {
        case .string(let s): "s:" + s
        case .int(let i): "i:\(i)"
        case .date(let d): "d:\(FamilyMirror.milliseconds(d))"
        }
    }

    public var string: String? { if case .string(let s) = self { s } else { nil } }
    public var int: Int? { if case .int(let i) = self { i } else { nil } }
    public var date: Date? { if case .date(let d) = self { d } else { nil } }
}

/// One record in the owner's FamilyBible zone, independent of CloudKit.
public struct FamilyMirrorRecord: Sendable, Equatable, Hashable {
    public var type: String
    /// Stable per item, so re-uploads replace instead of duplicate.
    public var name: String
    public var fields: [String: FamilyFieldValue]

    public init(type: String, name: String, fields: [String: FamilyFieldValue]) {
        self.type = type
        self.name = name
        self.fields = fields
    }

    /// Changes whenever anything a participant would see changes; stored on the record as `fp`
    /// so a second owner device can rebuild what has been uploaded from the zone itself.
    public var fingerprint: String {
        let body = fields.keys.sorted().map { "\($0)=\(fields[$0]!.canonical)" }.joined(separator: "\u{1F}")
        let digest = SHA256.hash(data: Data("\(type)\u{1E}\(name)\u{1E}\(body)".utf8))
        return digest.prefix(16).map { String(format: "%02x", $0) }.joined()
    }
}

/// A favorited passage, as the mirror carries it.
public struct FamilyFavorite: Codable, Sendable, Equatable, Hashable, Identifiable {
    public var id: UUID
    public var start: Int
    public var end: Int
    public var createdAt: Date

    public init(id: UUID, start: Int, end: Int, createdAt: Date) {
        self.id = id
        self.start = start
        self.end = end
        self.createdAt = createdAt
    }

    public var range: VerseRange? {
        guard let a = VerseRef(key: start), let b = VerseRef(key: end) else { return nil }
        return VerseRange(a, b)
    }
}

/// Who the shared Bible belongs to — from the owner's Keepsake Bible settings.
public struct FamilyOwnerProfile: Codable, Sendable, Equatable, Hashable {
    /// The owner's stable `bibleID` (the same one their keepsakes carry), so a live share and a
    /// keepsake from the same person are recognised as the same Bible.
    public var bibleID: UUID
    public var ownerName: String?
    public var dedication: String?
    public var preferredTranslation: String?

    public init(bibleID: UUID, ownerName: String? = nil, dedication: String? = nil, preferredTranslation: String? = nil) {
        self.bibleID = bibleID
        self.ownerName = ownerName
        self.dedication = dedication
        self.preferredTranslation = preferredTranslation
    }
}

/// Everything the owner's device wants the zone to hold right now.
public struct FamilyMirrorInput: Sendable, Equatable {
    public var profile: FamilyOwnerProfile
    public var highlights: [KeepsakeHighlight]
    public var notes: [KeepsakeNote]
    public var favorites: [FamilyFavorite]

    public init(profile: FamilyOwnerProfile, highlights: [KeepsakeHighlight], notes: [KeepsakeNote], favorites: [FamilyFavorite]) {
        self.profile = profile
        self.highlights = highlights
        self.notes = notes
        self.favorites = favorites
    }
}

/// A decoded mirror record.
public enum FamilyMirrorItem: Sendable, Equatable {
    case profile(FamilyOwnerProfile)
    case highlight(KeepsakeHighlight)
    case note(KeepsakeNote)
    case favorite(FamilyFavorite)
}

public enum FamilyMirror {
    /// The custom zone in the owner's private database. Every owner uses the same name; in a
    /// participant's shared database zones are told apart by their owner.
    public static let zoneName = "FamilyBible"
    /// Bumped only if the record layout changes in a way old readers would misread.
    public static let schemaVersion = 1
    /// Field holding a record's fingerprint.
    public static let fingerprintField = "fp"
    /// CloudKit accepts at most 400 items per modify request; stay well under it.
    public static let batchSize = 300

    public enum RecordType {
        public static let profile = "FamilyProfile"
        public static let highlight = "FamilyHighlight"
        public static let note = "FamilyNote"
        public static let favorite = "FamilyFavorite"
        public static let all = [profile, highlight, note, favorite]
    }

    public static let profileRecordName = "profile"

    public static func highlightName(_ verse: Int) -> String { "h-\(verse)" }
    public static func noteName(_ id: UUID) -> String { "n-\(id.uuidString)" }
    public static func favoriteName(_ id: UUID) -> String { "f-\(id.uuidString)" }

    static func milliseconds(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }
    static func rounded(_ date: Date) -> Date { Date(timeIntervalSince1970: Double(milliseconds(date)) / 1000) }

    // MARK: Mapping

    /// The records the zone should hold for `input`. Duplicates that CloudKit merges can leave
    /// behind are collapsed first: one highlight per verse (newest wins, as in the reader), one
    /// note or favorite per id (latest edit wins).
    public static func records(for input: FamilyMirrorInput) -> [FamilyMirrorRecord] {
        var result = [record(for: input.profile)]

        var byVerse: [Int: KeepsakeHighlight] = [:]
        for highlight in input.highlights {
            if let existing = byVerse[highlight.verse], existing.createdAt > highlight.createdAt { continue }
            byVerse[highlight.verse] = highlight
        }
        result += byVerse.keys.sorted().map { record(for: byVerse[$0]!) }

        var notes: [UUID: KeepsakeNote] = [:]
        for note in input.notes {
            if let existing = notes[note.id], existing.updatedAt > note.updatedAt { continue }
            notes[note.id] = note
        }
        result += notes.values.sorted { $0.id.uuidString < $1.id.uuidString }.map(record(for:))

        var favorites: [UUID: FamilyFavorite] = [:]
        for favorite in input.favorites {
            if let existing = favorites[favorite.id], existing.createdAt > favorite.createdAt { continue }
            favorites[favorite.id] = favorite
        }
        result += favorites.values.sorted { $0.id.uuidString < $1.id.uuidString }.map(record(for:))
        return result
    }

    public static func record(for profile: FamilyOwnerProfile) -> FamilyMirrorRecord {
        var fields: [String: FamilyFieldValue] = [
            "bibleID": .string(profile.bibleID.uuidString),
            "schemaVersion": .int(schemaVersion),
        ]
        if let name = profile.ownerName.nonEmpty { fields["ownerName"] = .string(name) }
        if let dedication = profile.dedication.nonEmpty { fields["dedication"] = .string(dedication) }
        if let translation = profile.preferredTranslation.nonEmpty { fields["translation"] = .string(translation) }
        return FamilyMirrorRecord(type: RecordType.profile, name: profileRecordName, fields: fields)
    }

    public static func record(for highlight: KeepsakeHighlight) -> FamilyMirrorRecord {
        FamilyMirrorRecord(type: RecordType.highlight, name: highlightName(highlight.verse), fields: [
            "verse": .int(highlight.verse),
            "color": .string(highlight.color),
            "createdAt": .date(highlight.createdAt),
        ])
    }

    public static func record(for note: KeepsakeNote) -> FamilyMirrorRecord {
        FamilyMirrorRecord(type: RecordType.note, name: noteName(note.id), fields: [
            "title": .string(note.title),
            "body": .string(note.body),
            // "start-end" verse keys, comma separated — the same form Note.anchorsRaw uses.
            "passages": .string(note.passages.map { "\($0.start)-\($0.end)" }.joined(separator: ",")),
            "createdAt": .date(note.createdAt),
            "updatedAt": .date(note.updatedAt),
            "origin": .string(note.origin),
        ])
    }

    public static func record(for favorite: FamilyFavorite) -> FamilyMirrorRecord {
        FamilyMirrorRecord(type: RecordType.favorite, name: favoriteName(favorite.id), fields: [
            "start": .int(favorite.start),
            "end": .int(favorite.end),
            "createdAt": .date(favorite.createdAt),
        ])
    }

    /// Reads a record back. Tolerant like the keepsake format: missing fields get defaults,
    /// unknown fields and unknown record types are ignored (nil).
    public static func item(from record: FamilyMirrorRecord) -> FamilyMirrorItem? {
        let f = record.fields
        switch record.type {
        case RecordType.profile:
            guard let raw = f["bibleID"]?.string, let id = UUID(uuidString: raw) else { return nil }
            return .profile(FamilyOwnerProfile(bibleID: id, ownerName: f["ownerName"]?.string,
                                               dedication: f["dedication"]?.string,
                                               preferredTranslation: f["translation"]?.string))
        case RecordType.highlight:
            guard let verse = f["verse"]?.int ?? Int(record.name.dropFirst(2)), VerseRef(key: verse) != nil else { return nil }
            return .highlight(KeepsakeHighlight(verse: verse, color: f["color"]?.string ?? "yellow",
                                                createdAt: f["createdAt"]?.date ?? .distantPast))
        case RecordType.note:
            guard let id = UUID(uuidString: String(record.name.dropFirst(2))) else { return nil }
            let passages = (f["passages"]?.string ?? "").split(separator: ",").compactMap { VerseRange(storageString: String($0)) }
            let created = f["createdAt"]?.date ?? .distantPast
            return .note(KeepsakeNote(id: id, title: f["title"]?.string ?? "", body: f["body"]?.string ?? "",
                                      anchors: passages, createdAt: created,
                                      updatedAt: f["updatedAt"]?.date ?? created,
                                      origin: f["origin"]?.string ?? "manual"))
        case RecordType.favorite:
            guard let id = UUID(uuidString: String(record.name.dropFirst(2))),
                  let start = f["start"]?.int, VerseRef(key: start) != nil else { return nil }
            let end = f["end"]?.int.flatMap { VerseRef(key: $0) != nil ? $0 : nil } ?? start
            return .favorite(FamilyFavorite(id: id, start: min(start, end), end: max(start, end),
                                            createdAt: f["createdAt"]?.date ?? .distantPast))
        default:
            return nil
        }
    }
}

/// What to send to bring the zone from `uploaded` to `desired`.
public struct FamilyMirrorDiff: Sendable, Equatable {
    public var saves: [FamilyMirrorRecord]
    /// Record names to delete.
    public var deletions: [String]

    public var isEmpty: Bool { saves.isEmpty && deletions.isEmpty }

    /// - Parameter uploaded: record name → fingerprint of what the zone holds.
    public static func compute(desired: [FamilyMirrorRecord], uploaded: [String: String]) -> FamilyMirrorDiff {
        var saves: [FamilyMirrorRecord] = []
        var wanted = Set<String>()
        for record in desired {
            wanted.insert(record.name)
            if uploaded[record.name] != record.fingerprint { saves.append(record) }
        }
        let deletions = uploaded.keys.filter { !wanted.contains($0) }.sorted()
        return FamilyMirrorDiff(saves: saves, deletions: deletions)
    }

    /// Splits into requests CloudKit will accept. The profile (if present) goes first so a
    /// participant sees whose Bible it is before its contents arrive.
    public func batches(size: Int = FamilyMirror.batchSize) -> [FamilyMirrorDiff] {
        precondition(size > 0)
        let ordered = saves.sorted { a, b in
            (a.type == FamilyMirror.RecordType.profile ? 0 : 1, a.name) < (b.type == FamilyMirror.RecordType.profile ? 0 : 1, b.name)
        }
        var result: [FamilyMirrorDiff] = []
        var index = 0
        while index < ordered.count {
            let slice = Array(ordered[index..<min(index + size, ordered.count)])
            result.append(FamilyMirrorDiff(saves: slice, deletions: []))
            index += size
        }
        index = 0
        while index < deletions.count {
            let slice = Array(deletions[index..<min(index + size, deletions.count)])
            if let last = result.last, last.saves.count + last.deletions.count + slice.count <= size {
                result[result.count - 1].deletions += slice
            } else {
                result.append(FamilyMirrorDiff(saves: [], deletions: slice))
            }
            index += size
        }
        return result
    }
}

private extension Optional where Wrapped == String {
    var nonEmpty: String? {
        guard let value = self?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else { return nil }
        return value
    }
}
