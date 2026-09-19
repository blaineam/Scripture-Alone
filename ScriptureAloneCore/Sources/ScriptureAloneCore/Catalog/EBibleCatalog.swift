import Foundation

/// One translation offered by eBible.org.
public struct CatalogTranslation: Identifiable, Hashable, Sendable {
    /// eBible's own id, which is also the download filename ("engwebp").
    public let id: String
    public let languageCode: String
    /// "English", as the language calls itself.
    public let languageName: String
    /// "English" in English — the two differ for most of the catalogue.
    public let languageNameInEnglish: String
    public let title: String
    public let shortTitle: String
    public let copyright: String
    /// eBible's own flag. False means the publisher allows eBible to show it but not to pass it on.
    public let isRedistributable: Bool
    public let otBooks: Int
    public let ntBooks: Int
    public let otVerses: Int
    public let ntVerses: Int
    public let textDirection: String
    /// "Latin", "Han", "Arabic" — eBible's own script name, which is how the two Chinese
    /// written forms are told apart.
    public let script: String

    public var bookCount: Int { otBooks + ntBooks }
    public var verseCount: Int { otVerses + ntVerses }
    public var isRightToLeft: Bool { textDirection.lowercased() == "rtl" }

    /// Whole-Bible translations say so; the rest are mostly New Testaments.
    public var scope: String {
        switch (otBooks, ntBooks) {
        case (39..., 27...): "Complete Bible"
        case (0, 1...): "New Testament"
        case (1..., 0): "Old Testament"
        default: "\(bookCount) book\(bookCount == 1 ? "" : "s")"
        }
    }

    public var downloadURL: URL {
        URL(string: "https://ebible.org/Scriptures/\(id)_usfm.zip")!
    }
}

/// Reads eBible.org's published catalogue of freely-licensed translations.
///
/// Nothing here runs on its own: the app has no background fetches and makes no network request
/// until someone taps to look. The catalogue is a single CSV eBible publishes for this purpose.
public struct EBibleCatalog: Sendable {
    public static let catalogURL = URL(string: "https://ebible.org/Scriptures/translations.csv")!

    public enum Failure: LocalizedError {
        case http(Int)
        case malformed(String)

        public var errorDescription: String? {
            switch self {
            case .http(let code):
                "eBible.org couldn't be reached (HTTP \(code)). Try again later."
            case .malformed(let what):
                "eBible.org's catalogue couldn't be read (\(what))."
            }
        }
    }

    public init() {}

    public func fetch(session: URLSession = .shared) async throws -> [CatalogTranslation] {
        var request = URLRequest(url: Self.catalogURL)
        request.timeoutInterval = 30
        request.cachePolicy = .reloadRevalidatingCacheData
        let (data, response) = try await session.data(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw Failure.http(http.statusCode)
        }
        guard let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) else {
            throw Failure.malformed("not text")
        }
        return try Self.parse(csv: text)
    }

    /// Parses the published CSV. Columns are addressed by header name, not position: eBible has
    /// added columns before, and a positional reader would silently start reporting the wrong
    /// field rather than failing.
    public static func parse(csv: String) throws -> [CatalogTranslation] {
        var rows = CSV.rows(in: csv).makeIterator()
        guard let header = rows.next() else { throw Failure.malformed("empty") }
        let index = Dictionary(uniqueKeysWithValues: header.enumerated().map { ($1, $0) })
        for required in ["translationId", "languageCode", "title", "Copyright", "Redistributable", "downloadable"]
        where index[required] == nil {
            throw Failure.malformed("no \(required) column")
        }
        func field(_ row: [String], _ name: String) -> String {
            guard let i = index[name], i < row.count else { return "" }
            return row[i]
        }
        func number(_ row: [String], _ name: String) -> Int { Int(field(row, name)) ?? 0 }
        func flag(_ row: [String], _ name: String) -> Bool { field(row, name).lowercased() == "true" }

        var out: [CatalogTranslation] = []
        while let row = rows.next() {
            guard !row.isEmpty, !field(row, "translationId").isEmpty else { continue }
            // Only what eBible says may be passed on, and only what it actually hosts a file for.
            guard flag(row, "downloadable"), flag(row, "Redistributable") else { continue }
            let entry = CatalogTranslation(
                id: field(row, "translationId"),
                languageCode: field(row, "languageCode"),
                languageName: field(row, "languageName"),
                languageNameInEnglish: field(row, "languageNameInEnglish"),
                title: field(row, "title"),
                shortTitle: field(row, "shortTitle"),
                copyright: field(row, "Copyright"),
                isRedistributable: true,
                otBooks: number(row, "OTbooks"), ntBooks: number(row, "NTbooks"),
                otVerses: number(row, "OTverses"), ntVerses: number(row, "NTverses"),
                textDirection: field(row, "textDirection"),
                script: field(row, "script"))
            // A row with no verses at all is a placeholder, not a translation.
            guard entry.verseCount > 0 else { continue }
            out.append(entry)
        }
        return out
    }
}

/// A CSV reader that handles quoted fields, embedded commas and doubled quotes — eBible's
/// copyright column contains all three.
enum CSV {
    static func rows(in text: String) -> [[String]] {
        var rows: [[String]] = []
        var row: [String] = []
        var field = ""
        var quoted = false
        var iterator = text.makeIterator()
        var pending: Character?

        func endField() { row.append(field); field = "" }
        func endRow() {
            endField()
            if !(row.count == 1 && row[0].isEmpty) { rows.append(row) }
            row = []
        }

        while let c = pending ?? iterator.next() {
            pending = nil
            if quoted {
                if c == "\"" {
                    if let next = iterator.next() {
                        if next == "\"" { field.append("\"") } else { quoted = false; pending = next }
                    } else {
                        quoted = false
                    }
                } else {
                    field.append(c)
                }
                continue
            }
            switch c {
            case "\"": quoted = true
            case ",": endField()
            // "\r\n" is ONE Character in Swift — a grapheme cluster — so matching "\r" and "\n"
            // separately misses a CRLF file entirely and silently glues every row into one.
            case "\n", "\r\n", "\r": endRow()
            default: field.append(c)
            }
        }
        if !field.isEmpty || !row.isEmpty { endRow() }
        return rows
    }
}
