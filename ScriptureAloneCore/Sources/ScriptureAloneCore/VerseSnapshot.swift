import Foundation

/// The compact, read-only picture of a reader's favorites, highlights and notes that the app
/// writes into its App Group container for the widgets. Widgets never open the SwiftData
/// store; they read this file.
public struct VerseSnapshot: Codable, Hashable, Sendable {
    public static let currentVersion = 1
    public static let fileName = "VerseSnapshot.json"

    public var version: Int
    public var generatedAt: Date
    /// Translation the texts are in (the reader's current one), e.g. "ASV".
    public var translation: String
    /// Newest first within each kind.
    public var items: [Item]

    public init(version: Int = VerseSnapshot.currentVersion, generatedAt: Date, translation: String, items: [Item]) {
        self.version = version
        self.generatedAt = generatedAt
        self.translation = translation
        self.items = items
    }

    public struct Item: Codable, Hashable, Sendable, Identifiable {
        public enum Kind: String, Codable, Sendable, CaseIterable {
            case favorite, highlight, note
        }

        public var kind: Kind
        /// Verse range storage form, "43003016-43003018".
        public var range: String
        public var startKey: Int
        public var endKey: Int
        /// "John 3:16–18"
        public var reference: String
        /// Plain text of the range in the snapshot's translation, trimmed to a widget's needs.
        public var text: String
        /// Highlight color name ("yellow", …) for highlights.
        public var color: String?
        /// Note title (or its first passage when untitled) for notes.
        public var noteTitle: String?
        public var date: Date

        public init(kind: Kind, range: VerseRange, reference: String? = nil, text: String,
                    color: String? = nil, noteTitle: String? = nil, date: Date) {
            self.kind = kind
            self.range = range.storageString
            self.startKey = range.start.key
            self.endKey = range.end.key
            self.reference = reference ?? range.display
            self.text = text
            self.color = color
            self.noteTitle = noteTitle
            self.date = date
        }

        public var id: String { "\(kind.rawValue):\(range):\(Int(date.timeIntervalSince1970))" }
        public var verseRange: VerseRange? { VerseRange(storageString: range) }
    }

    public func items(of kinds: Set<Item.Kind>) -> [Item] {
        items.filter { kinds.contains($0.kind) }
    }

    // MARK: Encoding

    public func encoded() throws -> Data {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(self)
    }

    public static func decode(_ data: Data) throws -> VerseSnapshot {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try decoder.decode(VerseSnapshot.self, from: data)
    }

    // MARK: Building

    public struct HighlightInput: Sendable {
        public let verseKey: Int
        public let color: String
        public let date: Date
        public init(verseKey: Int, color: String, date: Date) {
            self.verseKey = verseKey
            self.color = color
            self.date = date
        }
    }

    public struct NoteInput: Sendable {
        public let title: String
        public let anchors: [VerseRange]
        public let date: Date
        public init(title: String, anchors: [VerseRange], date: Date) {
            self.title = title
            self.anchors = anchors
            self.date = date
        }
    }

    /// Longest text kept per item. A large widget shows ~300 characters; the rest is waste.
    public static let maxTextLength = 420

    /// Builds a snapshot. Highlights are stored one row per verse, so neighboring verses of the
    /// same color collapse into one range (newest verse's date wins; the newest color wins when
    /// two devices colored one verse). Each note contributes its first passage. At most
    /// `limitPerKind` of each kind are kept, newest first.
    public static func build(favorites: [(range: VerseRange, date: Date)],
                             highlights: [HighlightInput],
                             notes: [NoteInput],
                             translation: String,
                             generatedAt: Date,
                             limitPerKind: Int = 60,
                             verseCount: (ChapterRef) -> Int,
                             text: (VerseRange) -> String) -> VerseSnapshot {
        var items: [Item] = []

        var seenFavorites = Set<VerseRange>()
        for favorite in favorites.sorted(by: { $0.date > $1.date }) where seenFavorites.insert(favorite.range).inserted {
            guard seenFavorites.count <= limitPerKind else { break }
            items.append(Item(kind: .favorite, range: favorite.range, text: trimmed(text(favorite.range)), date: favorite.date))
        }

        var newest: [Int: HighlightInput] = [:]
        for highlight in highlights where (newest[highlight.verseKey]?.date ?? .distantPast) <= highlight.date {
            newest[highlight.verseKey] = highlight
        }
        var groups: [(range: VerseRange, color: String, date: Date)] = []
        for (color, rows) in Dictionary(grouping: newest.values, by: \.color) {
            let dates = Dictionary(rows.map { ($0.verseKey, $0.date) }, uniquingKeysWith: max)
            for range in VerseRange.ranges(from: dates.keys, verseCount: verseCount) {
                let date = dates.filter { range.contains(key: $0.key) }.map(\.value).max() ?? .distantPast
                groups.append((range, color, date))
            }
        }
        groups.sort { ($0.date, $1.range) > ($1.date, $0.range) }
        for group in groups.prefix(limitPerKind) {
            items.append(Item(kind: .highlight, range: group.range, text: trimmed(text(group.range)),
                              color: group.color, date: group.date))
        }

        for note in notes.sorted(by: { $0.date > $1.date }).prefix(limitPerKind) {
            guard let first = note.anchors.sorted().first else { continue }
            let title = note.title.trimmingCharacters(in: .whitespacesAndNewlines)
            items.append(Item(kind: .note, range: first, text: trimmed(text(first)),
                              noteTitle: title.isEmpty ? first.display : title, date: note.date))
        }

        return VerseSnapshot(generatedAt: generatedAt, translation: translation, items: items)
    }

    static func trimmed(_ text: String) -> String {
        guard text.count > maxTextLength else { return text }
        let cut = text.prefix(maxTextLength)
        let atWord = cut.lastIndex(of: " ").map { cut[..<$0] } ?? cut
        return atWord.trimmingCharacters(in: .whitespacesAndNewlines.union(.punctuationCharacters)) + "…"
    }

    // MARK: Rotation

    /// Which item a rotating widget shows: advances every `slotHours` through the local day and
    /// continues day to day, plus a user nudge (the widget's "next" button).
    public static func rotationIndex(at date: Date, count: Int, slotHours: Int = 3, nudge: Int = 0,
                                     calendar: Calendar = .current) -> Int {
        guard count > 0 else { return 0 }
        let parts = calendar.dateComponents([.year, .month, .day, .hour], from: date)
        let day = DailyVerseCatalog.dayNumber(year: parts.year ?? 2000, month: parts.month ?? 1, day: parts.day ?? 1)
        let slotsPerDay = max(1, 24 / max(1, slotHours))
        let slot = day * slotsPerDay + (parts.hour ?? 0) / max(1, slotHours) + nudge
        return ((slot % count) + count) % count
    }
}

/// `scripturealone://open?ref=<startKey>-<endKey>` — the link widgets, complications and shared
/// links use to open the reader at a passage.
public enum ScriptureLink {
    public static let scheme = "scripturealone"
    public static let openHost = "open"

    public static func url(for range: VerseRange) -> URL {
        var components = URLComponents()
        components.scheme = scheme
        components.host = openHost
        components.queryItems = [URLQueryItem(name: "ref", value: range.storageString)]
        return components.url!
    }

    /// Accepts `ref=start-end` or a single verse key `ref=43003016`.
    public static func range(from url: URL) -> VerseRange? {
        guard url.scheme?.lowercased() == scheme,
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              components.host?.lowercased() == openHost,
              let ref = components.queryItems?.first(where: { $0.name == "ref" })?.value else { return nil }
        if let range = VerseRange(storageString: ref) { return range }
        return Int(ref).flatMap(VerseRef.init(key:)).map { VerseRange($0) }
    }
}

extension VerseRange {
    /// "Ps 23:1", "1 Cor 13:4–7", "Gen 1:1–2:3" — for complications and small widgets.
    public var abbreviatedDisplay: String {
        let head = "\(start.book.abbreviation) \(start.chapter):\(start.verse)"
        if start == end { return head }
        if start.book != end.book { return "\(head)–\(end.book.abbreviation) \(end.chapter):\(end.verse)" }
        if start.chapter != end.chapter { return "\(head)–\(end.chapter):\(end.verse)" }
        return "\(head)–\(end.verse)"
    }
}
