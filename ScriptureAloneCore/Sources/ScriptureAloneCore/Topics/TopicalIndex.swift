import Foundation
import SQLite3

/// A topic in Nave's Topical Bible: "Abraham", "Care", "Prayer".
public struct IndexTopic: Hashable, Sendable, Identifiable {
    public let id: Int
    public let name: String

    public init(id: Int, name: String) {
        self.id = id
        self.name = name
    }

    /// The letter it files under in an A–Z list.
    public var initial: String { name.first.map { String($0).uppercased() } ?? "#" }
}

/// One line of a topic: a heading ("Remedy for") with the passages it lists, and the topics it
/// sends the reader on to. A line at `level` 1 sits under the level-0 line before it.
public struct IndexEntry: Hashable, Sendable, Identifiable {
    /// The line's position in its topic.
    public let id: Int
    /// May be empty: a line that only lists passages, or only points elsewhere.
    public let label: String
    public let level: Int
    /// KJV keys, like everything the app stores.
    public let passages: [VerseRange]
    public let seeAlso: [IndexTopic]
}

/// Read-only access to Nave's Topical Bible (Orville J. Nave, 1896; public domain), built by
/// `Tools/build_topics.py` into `Topics.sqlite`: some 5,300 topics, 78,000 references.
///
/// It is an English book — its topics are English words and its lines English prose — so the app
/// shows it only to readers using the app in English, as it does the commentaries. The topic names
/// are read once, when it opens (they are what the directory lists and searches); a topic's lines
/// are compressed and read when it is opened.
public final class TopicalIndex: @unchecked Sendable {
    public let topics: [IndexTopic]
    /// "Nave’s Topical Bible".
    public let name: String
    /// "Public domain".
    public let license: String
    public let attribution: String
    public let sourceURL: URL?
    private let db: OpaquePointer
    private let lock = NSLock()
    private let byID: [Int: IndexTopic]
    /// Names folded for search, in `topics` order.
    private let searchNames: [String]

    public init(url: URL) throws {
        var handle: OpaquePointer?
        let uri = "file:\(url.path(percentEncoded: true))?immutable=1"
        guard sqlite3_open_v2(uri, &handle, SQLITE_OPEN_READONLY | SQLITE_OPEN_URI | SQLITE_OPEN_NOMUTEX, nil) == SQLITE_OK,
              let handle else {
            let message = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "unknown error"
            sqlite3_close(handle)
            throw StudyStoreError.open(message)
        }
        db = handle
        var meta: [String: String] = [:]
        try BibleStore.rows(db, "SELECT key, value FROM meta") { stmt in
            meta[BibleStore.string(stmt, 0)] = BibleStore.string(stmt, 1)
        }
        name = meta["name"] ?? "Nave’s Topical Bible"
        license = meta["license"] ?? ""
        attribution = meta["attribution"] ?? ""
        sourceURL = meta["url"].flatMap(URL.init(string:))
        var topics: [IndexTopic] = []
        try BibleStore.rows(db, "SELECT id, name FROM topics ORDER BY id") { stmt in
            topics.append(IndexTopic(id: Int(sqlite3_column_int64(stmt, 0)), name: BibleStore.string(stmt, 1)))
        }
        self.topics = topics
        byID = Dictionary(uniqueKeysWithValues: topics.map { ($0.id, $0) })
        searchNames = topics.map { TopicSearch.normalize($0.name) }
    }

    deinit { sqlite3_close(db) }

    public func topic(id: Int) -> IndexTopic? { byID[id] }

    /// A topic by its name, ignoring case — how a life theme names the topics beside it.
    public func topic(named name: String) -> IndexTopic? {
        let wanted = TopicSearch.normalize(name)
        return searchNames.firstIndex(of: wanted).map { topics[$0] }
    }

    /// Topics whose names answer a search, best first; ties keep A–Z order.
    public func search(_ query: String, limit: Int = 50) -> [IndexTopic] {
        let needle = TopicSearch.normalize(query)
        guard needle.count >= 2 else { return [] }
        let scored = searchNames.enumerated().compactMap { index, name -> (score: Int, index: Int)? in
            let score = TopicSearch.score(name, against: needle)
            return score > 0 ? (score, index) : nil
        }
        return scored.sorted { ($0.score, -$0.index) > ($1.score, -$1.index) }.prefix(limit).map { topics[$0.index] }
    }

    /// The topic's lines, in the book's order.
    public func entries(for topic: IndexTopic) throws -> [IndexEntry] {
        let body: Data = try locked {
            var data = Data()
            try BibleStore.rows(db, "SELECT body FROM topics WHERE id = ?1", bind: [topic.id]) { stmt in
                let count = Int(sqlite3_column_bytes(stmt, 0))
                if count > 0, let pointer = sqlite3_column_blob(stmt, 0) { data = Data(bytes: pointer, count: count) }
            }
            return data
        }
        guard let json = StudyStore.inflate(body) else { return [] }
        return Self.decodeEntries(Data(json.utf8)) { self.byID[$0] }
    }

    /// `[[label, level, [start, end, …], [topic id, …]], …]` — see Tools/build_topics.py.
    static func decodeEntries(_ json: Data, topic: (Int) -> IndexTopic?) -> [IndexEntry] {
        guard let rows = try? JSONSerialization.jsonObject(with: json) as? [[Any]] else { return [] }
        return rows.enumerated().compactMap { index, row in
            guard row.count == 4, let label = row[0] as? String, let level = row[1] as? Int,
                  let keys = row[2] as? [Int], let see = row[3] as? [Int] else { return nil }
            let passages = stride(from: 0, to: keys.count - 1, by: 2).compactMap { i -> VerseRange? in
                guard let a = VerseRef(key: keys[i]), let b = VerseRef(key: keys[i + 1]) else { return nil }
                return VerseRange(a, b)
            }
            return IndexEntry(id: index, label: label, level: level, passages: passages, seeAlso: see.compactMap(topic))
        }
    }

    private func locked<T>(_ body: () throws -> T) rethrows -> T {
        lock.lock()
        defer { lock.unlock() }
        return try body()
    }
}
