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
    /// The context in the reader's language (docs/localization.md): rows of the `translations`
    /// table (Tools/translate_context.py) for this language, by kind, keyed by the English source —
    /// or by OpenBible id for places. Empty in English, or for a database without the table.
    private let translated: [String: [String: String]]
    /// The language the context is shown in, or nil for English.
    public let language: String?

    /// The table's language for the app's current language: an exact match ("pt-BR"), else the
    /// language alone ("fr" for "fr-CA"); nil for English and anything untranslated.
    public static var appLanguage: String? {
        let tag = Bundle.main.preferredLocalizations.first ?? "en"
        return tag.hasPrefix("en") ? nil : tag
    }

    public init(url: URL, language: String? = ContextStore.appLanguage) throws {
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

        var translated: [String: [String: String]] = [:]
        var matched: String?
        if let language {
            var available: [String] = []
            var probe: OpaquePointer?
            if sqlite3_prepare_v2(handle, "SELECT DISTINCT lang FROM translations", -1, &probe, nil) == SQLITE_OK, let probe {
                while sqlite3_step(probe) == SQLITE_ROW { available.append(Self.string(probe, 0)) }
                sqlite3_finalize(probe)
            }
            let code = language.split(separator: "-").first.map(String.init)
            matched = available.first { $0 == language }
                ?? available.first { $0.split(separator: "-").first.map(String.init) == code && !(code == "zh" && language.contains("Hant")) }
            if let matched {
                var stmt: OpaquePointer?
                if sqlite3_prepare_v2(handle, "SELECT kind, source, text FROM translations WHERE lang = ?1", -1, &stmt, nil) == SQLITE_OK,
                   let stmt {
                    sqlite3_bind_text(stmt, 1, matched, -1, unsafeBitCast(-1, to: sqlite3_destructor_type.self))
                    while sqlite3_step(stmt) == SQLITE_ROW {
                        translated[Self.string(stmt, 0), default: [:]][Self.string(stmt, 1)] = Self.string(stmt, 2)
                    }
                    sqlite3_finalize(stmt)
                }
            }
        }
        self.translated = translated
        self.language = matched
    }

    deinit { sqlite3_close(db) }

    // MARK: Translation

    /// Prose in the reader's language, or the English it was given.
    func tr(_ english: String) -> String { translated["string"]?[english] ?? english }
    func tr(_ english: String?) -> String? { english.map { tr($0) } }

    /// A person's name as the reader's Bible spells it (chart kings, tribes, prophets…).
    func person(_ english: String) -> String { translated["person"]?[english] ?? tr(english) }

    func localized(_ place: Place) -> Place {
        guard language != nil else { return place }
        return Place(id: place.id, openBibleID: place.openBibleID,
                     name: translated["place"]?[place.openBibleID] ?? place.name,
                     modernName: place.modernName.isEmpty ? "" : translated["modern"]?[place.modernName] ?? place.modernName,
                     kind: place.kind, type: place.type, longitude: place.longitude, latitude: place.latitude,
                     confidence: place.confidence, precision: place.precision, alternatives: place.alternatives,
                     mentions: place.mentions, fromOpenStreetMap: place.fromOpenStreetMap)
    }

    /// A chart's JSON body with its reader-facing text translated — the same fields the translator
    /// takes (Tools/translate_context.py). Feasts get a language-neutral `seasonGroup` first, since
    /// the chart sorts them by season and the season text is about to stop being English; and their
    /// English New Testament quotation (`ntText`) is dropped, so the chart shows that verse from the
    /// reader's own Bible instead.
    func localizedBody(_ body: Data) -> Data {
        guard var root = try? JSONSerialization.jsonObject(with: body) else { return body }
        let prose: Set<String> = ["date", "dates", "debate", "meaning", "note", "reign", "season", "short", "sources",
                                  "sub", "subtitle", "summary", "text", "title", "years", "name"]
        let names: Set<String> = ["mother", "prophets"]
        func walk(_ node: Any, field: String?, siblings: [String: Any]?) -> Any {
            if var dict = node as? [String: Any] {
                if let season = dict["season"] as? String {
                    dict["seasonGroup"] = season.hasPrefix("March") || season.hasPrefix("May") ? "spring"
                        : season.hasPrefix("September") ? "autumn" : nil
                }
                if language != nil { dict["ntText"] = nil }
                for (key, value) in dict where key != "seasonGroup" { dict[key] = walk(value, field: key, siblings: dict) }
                return dict
            }
            if let list = node as? [Any] { return list.map { walk($0, field: field, siblings: siblings) } }
            guard let text = node as? String, let field, language != nil else { return node }
            let isPerson = names.contains(field)
                || (field == "name" && siblings.map { $0["reign"] != nil || $0["birth"] != nil || $0["mother"] != nil } == true)
            if isPerson { return person(text) }
            return prose.contains(field) ? tr(text) : text
        }
        root = walk(root, field: nil, siblings: nil)
        return (try? JSONSerialization.data(withJSONObject: root)) ?? body
    }

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
                let place = localized(Self.place(stmt, offset: 1))
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
            try rows("SELECT \(Self.placeColumns) FROM places p WHERE p.id = ?1", bind: [id]) { result = self.localized(Self.place($0, offset: 0)) }
            return result
        }
    }

    /// The most-mentioned places, for the overview map.
    public func prominentPlaces(limit: Int = 400) throws -> [Place] {
        try locked {
            var result: [Place] = []
            try rows("SELECT \(Self.placeColumns) FROM places p ORDER BY p.mentions DESC, p.name LIMIT ?1", bind: [limit]) {
                result.append(self.localized(Self.place($0, offset: 0)))
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
            // In another language, the reader types the name their Bible uses ("Jérusalem", "耶路撒冷").
            let translatedMatch = language == nil ? "" : """
                 OR p.obid IN (SELECT source FROM translations WHERE lang = ?4 AND kind = 'place' AND text LIKE ?1)
                """
            try rows("""
                SELECT \(Self.placeColumns) FROM places p WHERE p.name LIKE ?1 OR p.modern LIKE ?1\(translatedMatch)
                ORDER BY (p.name LIKE ?2) DESC, p.mentions DESC LIMIT ?3
                """, bind: [pattern, term + "%", limit] + (language.map { [$0] } ?? [])) {
                result.append(self.localized(Self.place($0, offset: 0)))
            }
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
                                  name: tr(Self.string(stmt, 2)), shortName: tr(Self.string(stmt, 3)),
                                  start: Self.optionalInt(stmt, 4), end: Self.optionalInt(stmt, 5),
                                  dates: tr(Self.string(stmt, 6)), color: Self.string(stmt, 7),
                                  summary: tr(Self.string(stmt, 8)), debate: tr(Self.string(stmt, 9))))
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
                    note = tr(Self.optionalString(stmt, 3))
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
                                            order: Int(sqlite3_column_int(stmt, 2)), name: tr(Self.string(stmt, 3)),
                                            year: Self.optionalInt(stmt, 4), date: tr(Self.optionalString(stmt, 5)),
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
                                        title: tr(Self.string(stmt, 3)), subtitle: tr(Self.string(stmt, 4)),
                                        sources: tr(Self.string(stmt, 5)), scope: scope.compactMap(BookID.init(rawValue:)),
                                        body: localizedBody(Data(Self.string(stmt, 7).utf8))))
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
                result.append(MapLabel(text: tr(Self.string(stmt, 0)), subtitle: tr(Self.optionalString(stmt, 1)),
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
