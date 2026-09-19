import Foundation

// A "Keepsake Bible" keepsake: a read-only snapshot of someone's highlights and notes, meant to
// be handed to family. See docs/heir-mode.md for the file format.

/// Everything in a keepsake once opened.
public struct Keepsake: Codable, Sendable, Equatable, Identifiable {
    public var manifest: KeepsakeManifest
    public var highlights: [KeepsakeHighlight]
    public var notes: [KeepsakeNote]

    public var id: UUID { manifest.bibleID }

    public init(manifest: KeepsakeManifest, highlights: [KeepsakeHighlight], notes: [KeepsakeNote]) {
        self.manifest = manifest
        self.highlights = highlights
        self.notes = notes
    }

    /// Fills in the manifest's counts and date range from the contents.
    public mutating func refreshSummary() {
        manifest.counts = .init(highlights: highlights.count, notes: notes.count)
        let dates = highlights.map(\.createdAt) + notes.flatMap { [$0.createdAt, $0.updatedAt] }
        if let first = dates.min(), let last = dates.max() {
            manifest.dateRange = .init(start: first, end: last)
        } else {
            manifest.dateRange = nil
        }
    }
}

public struct KeepsakeManifest: Codable, Sendable, Equatable {
    public static let formatIdentifier = "com.blainemiller.scripturealone.legacy"
    /// The version this code writes.
    public static let currentVersion = 1
    /// The newest `minimumReaderVersion` this code understands.
    public static let supportedReaderVersion = 1

    public struct Counts: Codable, Sendable, Equatable {
        public var highlights: Int
        public var notes: Int
        public init(highlights: Int, notes: Int) {
            self.highlights = highlights
            self.notes = notes
        }
    }

    public struct DateRange: Codable, Sendable, Equatable {
        public var start: Date
        public var end: Date
        public init(start: Date, end: Date) {
            self.start = start
            self.end = end
        }
    }

    public struct Encryption: Codable, Sendable, Equatable {
        public var algorithm: String
        public var kdf: String
        public var iterations: Int
        /// Base64.
        public var salt: String
        /// The archive entry holding the sealed contents.
        public var payload: String
    }

    public var format: String = formatIdentifier
    /// The version of the writer that made the file.
    public var formatVersion: Int = currentVersion
    /// The oldest reader that can open the file. Writers bump this only for changes old
    /// readers would get wrong; additions alone never bump it (old readers ignore unknown keys).
    public var minimumReaderVersion: Int = 1
    /// Unique per export.
    public var exportID: UUID = UUID()
    /// Stable for one person's Bible across exports, so a newer keepsake replaces an older one.
    public var bibleID: UUID = UUID()
    public var createdAt: Date = .now
    public var generator: String = "Scripture Alone"
    public var ownerName: String?
    public var dedication: String?
    /// Translation abbreviation the owner read in ("ASV").
    public var preferredTranslation: String?
    public var dateRange: DateRange?
    public var counts: Counts?
    /// Present only in the outer manifest of a protected keepsake.
    public var encryption: Encryption?
    /// Shown before the passphrase is asked for. Stored in the clear.
    public var passphraseHint: String?

    public init(bibleID: UUID = UUID(), ownerName: String? = nil, dedication: String? = nil,
                preferredTranslation: String? = nil, generator: String = "Scripture Alone", createdAt: Date = .now) {
        self.bibleID = bibleID
        self.ownerName = ownerName
        self.dedication = dedication
        self.preferredTranslation = preferredTranslation
        self.generator = generator
        self.createdAt = createdAt
    }

    public var isEncrypted: Bool { encryption != nil }

    /// "Dad's Bible", or "A Keepsake Bible" when unnamed.
    public var displayTitle: String {
        guard let name = ownerName?.trimmingCharacters(in: .whitespacesAndNewlines), !name.isEmpty else { return "A Keepsake Bible" }
        return name.hasSuffix("s") || name.hasSuffix("S") ? "\(name)’ Bible" : "\(name)’s Bible"
    }

    // Tolerant decoding: every field but `format` may be missing, and unknown keys are ignored.
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        format = try c.decode(String.self, forKey: .format)
        formatVersion = try c.decodeIfPresent(Int.self, forKey: .formatVersion) ?? 1
        minimumReaderVersion = try c.decodeIfPresent(Int.self, forKey: .minimumReaderVersion) ?? 1
        exportID = try c.decodeIfPresent(UUID.self, forKey: .exportID) ?? UUID()
        bibleID = try c.decodeIfPresent(UUID.self, forKey: .bibleID) ?? exportID
        createdAt = try c.decodeIfPresent(Date.self, forKey: .createdAt) ?? .now
        generator = try c.decodeIfPresent(String.self, forKey: .generator) ?? ""
        ownerName = try c.decodeIfPresent(String.self, forKey: .ownerName)
        dedication = try c.decodeIfPresent(String.self, forKey: .dedication)
        preferredTranslation = try c.decodeIfPresent(String.self, forKey: .preferredTranslation)
        dateRange = try? c.decodeIfPresent(DateRange.self, forKey: .dateRange)
        counts = try? c.decodeIfPresent(Counts.self, forKey: .counts)
        encryption = try c.decodeIfPresent(Encryption.self, forKey: .encryption)
        passphraseHint = try c.decodeIfPresent(String.self, forKey: .passphraseHint)
    }
}

public struct KeepsakeHighlight: Codable, Sendable, Equatable {
    public var verse: Int
    /// "yellow", "green", "blue", "pink", "purple". Readers show unknown names as yellow.
    public var color: String
    public var createdAt: Date

    public init(verse: Int, color: String, createdAt: Date) {
        self.verse = verse
        self.color = color
        self.createdAt = createdAt
    }

    enum CodingKeys: String, CodingKey { case verse, color, createdAt, reference }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        verse = try c.decode(Int.self, forKey: .verse)
        color = try c.decodeIfPresent(String.self, forKey: .color) ?? "yellow"
        createdAt = try c.decodeIfPresent(Date.self, forKey: .createdAt) ?? .distantPast
    }

    public func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(verse, forKey: .verse)
        try c.encode(color, forKey: .color)
        try c.encode(createdAt, forKey: .createdAt)
        // For people reading the JSON by hand; ignored when reading.
        try c.encodeIfPresent(VerseRef(key: verse)?.display, forKey: .reference)
    }
}

public struct KeepsakeNote: Codable, Sendable, Equatable, Identifiable {
    public struct Passage: Codable, Sendable, Equatable {
        public var start: Int
        public var end: Int

        public init(start: Int, end: Int) {
            self.start = start
            self.end = end
        }

        public var range: VerseRange? {
            guard let a = VerseRef(key: start), let b = VerseRef(key: end) else { return nil }
            return VerseRange(a, b)
        }

        enum CodingKeys: String, CodingKey { case start, end, reference }

        public init(from decoder: any Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            start = try c.decode(Int.self, forKey: .start)
            end = try c.decodeIfPresent(Int.self, forKey: .end) ?? start
        }

        public func encode(to encoder: any Encoder) throws {
            var c = encoder.container(keyedBy: CodingKeys.self)
            try c.encode(start, forKey: .start)
            try c.encode(end, forKey: .end)
            try c.encodeIfPresent(range?.display, forKey: .reference)
        }
    }

    public var id: UUID
    public var title: String
    public var body: String
    public var passages: [Passage]
    public var createdAt: Date
    public var updatedAt: Date
    /// "manual" or "camera".
    public var origin: String

    public init(id: UUID = UUID(), title: String, body: String, anchors: [VerseRange],
                createdAt: Date, updatedAt: Date, origin: String = "manual") {
        self.id = id
        self.title = title
        self.body = body
        self.passages = anchors.map { Passage(start: $0.start.key, end: $0.end.key) }
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.origin = origin
    }

    enum CodingKeys: String, CodingKey { case id, title, body, passages, createdAt, updatedAt, origin }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(UUID.self, forKey: .id) ?? UUID()
        title = try c.decodeIfPresent(String.self, forKey: .title) ?? ""
        body = try c.decodeIfPresent(String.self, forKey: .body) ?? ""
        passages = try c.decodeIfPresent([Passage].self, forKey: .passages) ?? []
        createdAt = try c.decodeIfPresent(Date.self, forKey: .createdAt) ?? .distantPast
        updatedAt = try c.decodeIfPresent(Date.self, forKey: .updatedAt) ?? createdAt
        origin = try c.decodeIfPresent(String.self, forKey: .origin) ?? "manual"
    }

    /// Valid ranges, in canonical order.
    public var anchors: [VerseRange] { passages.compactMap(\.range).sorted() }

    public var displayTitle: String {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty { return trimmed }
        return anchors.first?.display ?? "Untitled Note"
    }

    public var anchorSummary: String { anchors.map(\.display).joined(separator: " · ") }

    public func touches(_ chapter: ChapterRef) -> Bool { anchors.contains { $0.overlaps(chapter) } }
}

public enum KeepsakeError: Error, Equatable, LocalizedError {
    case notAKeepsake
    case damaged(String)
    case newerVersion(Int)
    case passphraseRequired
    case wrongPassphrase

    public var errorDescription: String? {
        switch self {
        case .notAKeepsake:
            "This file isn’t a Keepsake Bible keepsake."
        case .damaged(let detail):
            "This keepsake appears to be damaged (\(detail)). If you have another copy, try that one."
        case .newerVersion:
            "This keepsake was made by a newer version of Scripture Alone. Update the app to open it."
        case .passphraseRequired:
            "This keepsake is protected with a passphrase."
        case .wrongPassphrase:
            "That passphrase doesn’t open this keepsake. Check for capital letters and spaces, and try again."
        }
    }
}
