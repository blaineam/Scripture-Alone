import Foundation
import SQLite3

/// Read-only access to the bundled Study context database (Context.sqlite, built by
/// Tools/build_context.py). Opened immutable; the lock serializes the shared connection,
/// so one store can be used from any thread.
public final class ContextStore: @unchecked Sendable {
    public let url: URL
    private let db: OpaquePointer
    private let lock = NSLock()
    private var cachedEras: [Era]?

    public init(url: URL) throws {
        self.url = url
        var handle: OpaquePointer?
        let uri = "file:\(url.path(percentEncoded: true))?immutable=1"
        guard sqlite3_open_v2(uri, &handle, SQLITE_OPEN_READONLY | SQLITE_OPEN_URI | SQLITE_OPEN_NOMUTEX, nil) == SQLITE_OK,
              let handle else {
            let message = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "unknown error"
            sqlite3_close(handle)
            throw BibleStoreError.open(message)
        }
        db = handle
    }

    deinit { sqlite3_close(db) }

    // MARK: Places

    private static let placeColumns = "p.id, p.obid, p.name, p.modern, p.kind, p.type, p.lon, p.lat, p.confidence, p.precision, p.alternatives, p.mentions, p.osm"

    /// Places the chapter mentions, in order of first mention, each with its verse keys.
    public func places(in chapter: ChapterRef) throws -> [PlaceMention] {
        try locked {
            var order: [Int] = []
            var places: [Int: Place] = [:]
            var verses: [Int: [Int]] = [:]
            try rows("""
                SELECT v.verse, \(Self.placeColumns) FROM place_verses v JOIN places p ON p.id = v.place
                WHERE v.verse BETWEEN ?1 AND ?2 ORDER BY v.verse, p.mentions DESC
                """, bind: [chapter.keyRange.lowerBound, chapter.keyRange.upperBound]) { stmt in
                let place = Self.place(stmt, offset: 1)
                if places[place.id] == nil {
                    places[place.id] = place
                    order.append(place.id)
                }
                verses[place.id, default: []].append(Int(sqlite3_column_int64(stmt, 0)))
            }
            return order.compactMap { id in places[id].map { PlaceMention(place: $0, verses: verses[id] ?? []) } }
        }
    }

    /// Every verse (key) that mentions the place, in canonical order.
    public func verses(mentioning placeID: Int) throws -> [Int] {
        try locked {
            var keys: [Int] = []
            try rows("SELECT verse FROM place_verses WHERE place = ?1 ORDER BY verse", bind: [placeID]) { stmt in
                keys.append(Int(sqlite3_column_int64(stmt, 0)))
            }
            return keys
        }
    }

    public func place(id: Int) throws -> Place? {
        try locked {
            var result: Place?
            try rows("SELECT \(Self.placeColumns) FROM places p WHERE p.id = ?1", bind: [id]) { result = Self.place($0, offset: 0) }
            return result
        }
    }

    /// The most-mentioned places, for the overview map.
    public func prominentPlaces(limit: Int = 400) throws -> [Place] {
        try locked {
            var result: [Place] = []
            try rows("SELECT \(Self.placeColumns) FROM places p ORDER BY p.mentions DESC, p.name LIMIT ?1", bind: [limit]) {
                result.append(Self.place($0, offset: 0))
            }
            return result
        }
    }

    /// Places whose name or modern name contains the text, most-mentioned first.
    public func searchPlaces(_ text: String, limit: Int = 60) throws -> [Place] {
        let term = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !term.isEmpty else { return [] }
        let pattern = "%" + term.replacingOccurrences(of: "%", with: "").replacingOccurrences(of: "_", with: "") + "%"
        return try locked {
            var result: [Place] = []
            try rows("""
                SELECT \(Self.placeColumns) FROM places p WHERE p.name LIKE ?1 OR p.modern LIKE ?1
                ORDER BY (p.name LIKE ?2) DESC, p.mentions DESC LIMIT ?3
                """, bind: [pattern, term + "%", limit]) { result.append(Self.place($0, offset: 0)) }
            return result
        }
    }

    // MARK: Timeline

    public func eras() throws -> [Era] {
        try locked {
            if let cachedEras { return cachedEras }
            var result: [Era] = []
            try rows("SELECT id, ord, name, short, start, end, dates, color, summary, debate FROM eras ORDER BY ord") { stmt in
                result.append(Era(id: Self.string(stmt, 0), order: Int(sqlite3_column_int(stmt, 1)),
                                  name: Self.string(stmt, 2), shortName: Self.string(stmt, 3),
                                  start: Self.optionalInt(stmt, 4), end: Self.optionalInt(stmt, 5),
                                  dates: Self.string(stmt, 6), color: Self.string(stmt, 7),
                                  summary: Self.string(stmt, 8), debate: Self.string(stmt, 9)))
            }
            cachedEras = result
            return result
        }
    }

    public func time(for chapter: ChapterRef) throws -> ChapterTime? {
        let all = try eras()
        return try locked {
            var eraIDs: [String] = []
            var year: Int?
            var basis = ChapterTime.Basis.events
            var note: String?
            try rows("SELECT era, year, basis, note FROM chapter_eras WHERE book = ?1 AND chapter = ?2 ORDER BY ord",
                     bind: [chapter.book.rawValue, chapter.chapter]) { stmt in
                eraIDs.append(Self.string(stmt, 0))
                if eraIDs.count == 1 {
                    year = Self.optionalInt(stmt, 1)
                    basis = ChapterTime.Basis(rawValue: Self.string(stmt, 2)) ?? .events
                    note = Self.optionalString(stmt, 3)
                }
            }
            let eras = eraIDs.compactMap { id in all.first { $0.id == id } }
            guard !eras.isEmpty else { return nil }
            return ChapterTime(eras: eras, year: year, basis: basis, note: note)
        }
    }

    /// All events in timeline order.
    public func events() throws -> [TimelineEvent] {
        try events(where: "1 = 1", bind: [])
    }

    /// Events whose passage overlaps the chapter.
    public func events(in chapter: ChapterRef) throws -> [TimelineEvent] {
        try events(where: "start_key <= ?2 AND end_key >= ?1",
                   bind: [chapter.keyRange.lowerBound, chapter.keyRange.upperBound])
    }

    private func events(where clause: String, bind: [Any]) throws -> [TimelineEvent] {
        let order = Dictionary(uniqueKeysWithValues: try eras().map { ($0.id, $0.order) })
        let events = try locked {
            var result: [TimelineEvent] = []
            try rows("SELECT id, era, ord, name, year, date, debated, start_key, end_key FROM events WHERE \(clause) ORDER BY ord",
                     bind: bind) { stmt in
                var range: VerseRange?
                if let start = Self.optionalInt(stmt, 7).flatMap(VerseRef.init(key:)),
                   let end = Self.optionalInt(stmt, 8).flatMap(VerseRef.init(key:)) {
                    range = VerseRange(start, end)
                }
                result.append(TimelineEvent(id: Int(sqlite3_column_int(stmt, 0)), eraID: Self.string(stmt, 1),
                                            order: Int(sqlite3_column_int(stmt, 2)), name: Self.string(stmt, 3),
                                            year: Self.optionalInt(stmt, 4), date: Self.optionalString(stmt, 5),
                                            debated: sqlite3_column_int(stmt, 6) != 0, range: range))
            }
            return result
        }
        return events.sorted { (order[$0.eraID] ?? 0, $0.order) < (order[$1.eraID] ?? 0, $1.order) }
    }

    // MARK: Charts and labels

    public func charts() throws -> [ChartInfo] {
        try locked {
            var result: [ChartInfo] = []
            try rows("SELECT id, ord, kind, title, subtitle, sources, scope, body FROM charts ORDER BY ord") { stmt in
                guard let kind = ChartKind(rawValue: Self.string(stmt, 2)) else { return }
                let scope = (try? JSONDecoder().decode([Int].self, from: Data(Self.string(stmt, 6).utf8))) ?? []
                result.append(ChartInfo(id: Self.string(stmt, 0), order: Int(sqlite3_column_int(stmt, 1)), kind: kind,
                                        title: Self.string(stmt, 3), subtitle: Self.string(stmt, 4),
                                        sources: Self.string(stmt, 5), scope: scope.compactMap(BookID.init(rawValue:)),
                                        body: Data(Self.string(stmt, 7).utf8)))
            }
            return result
        }
    }

    /// Charts suggested while reading this book.
    public func charts(for book: BookID) throws -> [ChartInfo] {
        try charts().filter { $0.scope.contains(book) }
    }

    public func labels() throws -> [MapLabel] {
        try locked {
            var result: [MapLabel] = []
            try rows("SELECT text, sub, lon, lat, min_scale, kind, angle FROM labels") { stmt in
                result.append(MapLabel(text: Self.string(stmt, 0), subtitle: Self.optionalString(stmt, 1),
                                       longitude: sqlite3_column_double(stmt, 2), latitude: sqlite3_column_double(stmt, 3),
                                       minimumScale: sqlite3_column_double(stmt, 4),
                                       kind: MapLabel.Kind(rawValue: Self.string(stmt, 5)) ?? .land,
                                       angle: sqlite3_column_double(stmt, 6)))
            }
            return result
        }
    }

    // MARK: SQLite helpers

    private func locked<T>(_ body: () throws -> T) rethrows -> T {
        lock.lock()
        defer { lock.unlock() }
        return try body()
    }

    private func rows(_ sql: String, bind: [Any] = [], _ row: (OpaquePointer) throws -> Void) throws {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else {
            throw BibleStoreError.query(String(cString: sqlite3_errmsg(db)))
        }
        defer { sqlite3_finalize(stmt) }
        let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
        for (index, value) in bind.enumerated() {
            let position = Int32(index + 1)
            switch value {
            case let int as Int: sqlite3_bind_int64(stmt, position, sqlite3_int64(int))
            case let string as String: sqlite3_bind_text(stmt, position, string, -1, transient)
            default: throw BibleStoreError.query("unsupported bind type")
            }
        }
        while true {
            let status = sqlite3_step(stmt)
            if status == SQLITE_ROW {
                try row(stmt)
            } else if status == SQLITE_DONE {
                return
            } else {
                throw BibleStoreError.query(String(cString: sqlite3_errmsg(db)))
            }
        }
    }

    private static func place(_ stmt: OpaquePointer, offset: Int32) -> Place {
        Place(id: Int(sqlite3_column_int64(stmt, offset)),
              openBibleID: string(stmt, offset + 1),
              name: string(stmt, offset + 2),
              modernName: string(stmt, offset + 3),
              kind: PlaceKind(rawValue: string(stmt, offset + 4)) ?? .site,
              type: string(stmt, offset + 5),
              longitude: sqlite3_column_double(stmt, offset + 6),
              latitude: sqlite3_column_double(stmt, offset + 7),
              confidence: Int(sqlite3_column_int(stmt, offset + 8)),
              precision: string(stmt, offset + 9),
              alternatives: Int(sqlite3_column_int(stmt, offset + 10)),
              mentions: Int(sqlite3_column_int(stmt, offset + 11)),
              fromOpenStreetMap: sqlite3_column_int(stmt, offset + 12) != 0)
    }

    private static func string(_ stmt: OpaquePointer, _ column: Int32) -> String {
        optionalString(stmt, column) ?? ""
    }

    private static func optionalString(_ stmt: OpaquePointer, _ column: Int32) -> String? {
        guard let cString = sqlite3_column_text(stmt, column) else { return nil }
        return String(cString: cString)
    }

    private static func optionalInt(_ stmt: OpaquePointer, _ column: Int32) -> Int? {
        sqlite3_column_type(stmt, column) == SQLITE_NULL ? nil : Int(sqlite3_column_int64(stmt, column))
    }
}
