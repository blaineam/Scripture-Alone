import Foundation

/// One translation the reader's API.Bible key can reach.
public struct APIBibleTranslation: Identifiable, Hashable, Sendable {
    public let id: String            // opaque, e.g. "de4e12af7f28f599-02"
    public let name: String
    public let abbreviation: String
    public let language: String
    public let copyright: String

    public init(id: String, name: String, abbreviation: String, language: String, copyright: String) {
        self.id = id
        self.name = name
        self.abbreviation = abbreviation
        self.language = language
        self.copyright = copyright
    }
}

/// Reads passages from the American Bible Society's API.Bible.
///
/// Where Crossway serves one translation, API.Bible serves many — CSB, NASB, NKJV, NIV and others
/// — under a single licence the reader accepts when they register. Their free Starter plan is for
/// non-commercial use, allows 5,000 requests a *month* (not a day, unlike Crossway's), and lets
/// the reader pick three copyrighted translations on their own dashboard.
///
/// Which three is therefore a property of the key, not of this app: Bible ids are opaque strings,
/// so the app asks the key what it can reach rather than hard-coding anything.
public struct APIBibleClient: Sendable {
    public static let base = URL(string: "https://api.scripture.api.bible/v1")!
    public static let signupURL = URL(string: "https://api.bible/")!

    public enum Failure: LocalizedError, Equatable {
        case unauthorized
        case notAvailable(String)
        case rateLimited
        case http(Int)
        case empty(String)

        public var errorDescription: String? {
            switch self {
            case .unauthorized:
                "API.Bible didn't accept that key. Check it on api.bible."
            case .notAvailable(let name):
                "\(name) isn't one of the translations your key can read. Choose it on api.bible first."
            case .rateLimited:
                "You've used your API.Bible requests for this month."
            case .http(let code):
                "API.Bible returned HTTP \(code)."
            case .empty(let reference):
                "API.Bible returned nothing for \(reference)."
            }
        }
    }

    private let key: String
    private let bibleID: String

    public init(key: String, bibleID: String = "") {
        self.key = key
        self.bibleID = bibleID
    }

    /// Every translation this key may read, so the reader picks from what they actually have.
    public func availableTranslations(session: URLSession = .shared) async throws -> [APIBibleTranslation] {
        let data = try await get(Self.base.appending(path: "bibles"), session: session, reference: "the Bible list")
        let decoded = try JSONDecoder().decode(BiblesResponse.self, from: data)
        return decoded.data.map {
            APIBibleTranslation(id: $0.id, name: $0.name ?? $0.abbreviation ?? $0.id,
                                abbreviation: $0.abbreviationLocal ?? $0.abbreviation ?? "",
                                language: $0.language?.name ?? "",
                                copyright: $0.copyright ?? "Used by permission via API.Bible.")
        }
    }

    public func chapter(_ chapter: ChapterRef, session: URLSession = .shared) async throws -> [VerseText] {
        // Chapter ids are "<USFM book code>.<number>" — the same three-letter codes `Canon`
        // already carries, so nothing new has to be mapped.
        let chapterID = "\(chapter.book.code).\(chapter.chapter)"
        var components = URLComponents(
            url: Self.base.appending(path: "bibles/\(bibleID)/chapters/\(chapterID)"),
            resolvingAgainstBaseURL: false)!
        components.queryItems = [
            .init(name: "content-type", value: "text"),
            .init(name: "include-verse-numbers", value: "true"),
            .init(name: "include-chapter-numbers", value: "false"),
            .init(name: "include-notes", value: "false"),
            .init(name: "include-titles", value: "false"),
        ]
        let data = try await get(components.url!, session: session, reference: chapter.display)
        let decoded = try JSONDecoder().decode(ChapterResponse.self, from: data)
        let verses = BracketVerseParser.parse(decoded.data.content, in: chapter)
        guard !verses.isEmpty else { throw Failure.empty(chapter.display) }
        return verses
    }

    private func get(_ url: URL, session: URLSession, reference: String) async throws -> Data {
        var request = URLRequest(url: url)
        request.timeoutInterval = 20
        request.setValue(key, forHTTPHeaderField: "api-key")
        let (data, response) = try await session.data(for: request)
        if let http = response as? HTTPURLResponse {
            switch http.statusCode {
            case 200..<300: break
            case 401: throw Failure.unauthorized
            case 403: throw Failure.notAvailable(reference)
            case 429: throw Failure.rateLimited
            case let code: throw Failure.http(code)
            }
        }
        return data
    }

    // MARK: Wire format


    /// API.Bible's own search, for the same reason Crossway's is used: the text cannot be indexed
    /// on the device, so the provider does the searching.
    public func search(_ query: String, limit: Int = 100,
                       session: URLSession = .shared) async throws -> [BibleStore.SearchHit] {
        var components = URLComponents(
            url: Self.base.appending(path: "bibles/\(bibleID)/search"),
            resolvingAgainstBaseURL: false)!
        components.queryItems = [
            .init(name: "query", value: query),
            .init(name: "limit", value: String(min(limit, 100))),
            .init(name: "sort", value: "canonical"),
        ]
        let data = try await get(components.url!, session: session, reference: query)
        let decoded = try JSONDecoder().decode(SearchResponse.self, from: data)
        return (decoded.data.verses ?? []).compactMap { verse in
            guard let passage = ReferenceParser.parse(verse.reference) else { return nil }
            return BibleStore.SearchHit(ref: passage.firstVerse, text: verse.text)
        }
    }

    private struct SearchResponse: Decodable {
        struct Verse: Decodable { let reference: String; let text: String }
        struct Payload: Decodable { let verses: [Verse]? }
        let data: Payload
    }

    private struct BiblesResponse: Decodable {
        struct Entry: Decodable {
            struct Language: Decodable { let name: String? }
            let id: String
            let name: String?
            let abbreviation: String?
            let abbreviationLocal: String?
            let copyright: String?
            let language: Language?
        }
        let data: [Entry]
    }

    private struct ChapterResponse: Decodable {
        struct Payload: Decodable { let content: String }
        let data: Payload
    }
}
