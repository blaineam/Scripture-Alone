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

    /// A whole Bible: all 39 Old Testament books and all 27 New Testament books.
    ///
    /// The catalogue is mostly New Testaments and portions — 1,291 entries collapse to 214 once
    /// this is required. The app offers only complete Bibles, so nobody downloads what turns out
    /// to be four gospels.
    public var isCompleteCanon: Bool { otBooks >= 39 && ntBooks >= 27 }
    public var verseCount: Int { otVerses + ntVerses }
    public var isRightToLeft: Bool { textDirection.lowercased() == "rtl" }

    /// Whole-Bible translations say so; the rest are mostly New Testaments.
    public var scope: String {
        switch (otBooks, ntBooks) {
        case (39..., 27...): String(localized: "Complete Bible", bundle: .module, comment: "Scope of a downloadable translation")
        case (0, 1...): String(localized: "New Testament", bundle: .module, comment: "Scope of a downloadable translation")
        case (1..., 0): String(localized: "Old Testament", bundle: .module, comment: "Scope of a downloadable translation")
        default: String(localized: "\(bookCount) books", bundle: .module, comment: "Scope of a downloadable translation. %lld is the number of books of the Bible it contains.")
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
                String(localized: "eBible.org couldn't be reached (HTTP \(code)). Try again later.", bundle: .module, comment: "Error. %lld is an HTTP status code.")
            case .malformed(let what):
                String(localized: "eBible.org's catalogue couldn't be read (\(what)).", bundle: .module, comment: "Error. %@ is a technical detail.")
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
        return try Self.parse(csv: Self.decode(data))
    }

    /// UTF-8, falling back to Latin-1. Latin-1 maps every byte to a character, so this cannot fail
    /// and there is no "not text" error to report: a response that isn't the catalogue (an HTML
    /// error page, say) is caught by `parse` instead, which finds none of the columns it needs.
    static func decode(_ data: Data) -> String {
        if let text = String(data: data, encoding: .utf8) { return text }
        var latin1 = String.UnicodeScalarView()
        latin1.append(contentsOf: data.map { Unicode.Scalar($0) })
        return String(latin1)
    }

    /// Parses the published CSV. Columns are addressed by header name, not position: eBible has
    /// added columns before, and a positional reader would silently start reporting the wrong
    /// field rather than failing.
    public static func parse(csv: String) throws -> [CatalogTranslation] {
        var rows = CSV.rows(in: csv).makeIterator()
        guard let header = rows.next() else { throw Failure.malformed("empty") }
        // First occurrence wins. `uniqueKeysWithValues:` traps on a repeated name, and this is a
        // file somebody else publishes: a repeated column must not be able to crash the app.
        let index = Dictionary(header.enumerated().map { ($1, $0) }, uniquingKeysWith: { first, _ in first })
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
