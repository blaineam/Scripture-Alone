// Neither service is reached from the watch: it receives text through the CloudKit/WCSession
// snapshot, never over the network, and the types these build (`ParsedPassage`, the layout blocks)
// come from `Import/`, which arm64_32 cannot compile. Same guard as `OnlineChapterCache`.
#if !os(watchOS)
import Foundation

/// Reads passages from Crossway's ESV API.
///
/// The ESV is the one translation with no offline path: Crossway sells no file anyone may bundle,
/// and every retail ePub is DRM-protected. Their API is the legitimate route, and its terms shape
/// this whole type:
///
/// - **The key belongs to the reader, not the app.** Crossway's free tier allows 5,000 queries a
///   day *per key*. A single key shipped inside the app would be spent by a few dozen people and
///   would be abuse of a non-commercial allowance. So each reader registers their own at
///   api.esv.org and the app stores it on their device.
/// - **At most 500 verses may be cached**, which is why `ESVCache` evicts and why the ESV cannot
///   be searched or read offline the way a bundled translation can.
/// - **The copyright line must be shown** wherever the text is.
///
/// Nothing here runs unless the reader is looking at an ESV chapter.
public struct ESVClient: Sendable {
    /// Crossway's stated ceiling: "You can cache up to 500 verses."
    public static let cacheVerseLimit = 500
    public static let signupURL = URL(string: "https://api.esv.org/account/create-application/")!
    public static let requiredCopyright = """
        Scripture quotations are from the ESV® Bible (The Holy Bible, English Standard Version®), \
        © 2001 by Crossway, a publishing ministry of Good News Publishers. Used by permission. \
        All rights reserved.
        """

    public enum Failure: LocalizedError, Equatable {
        case noKey
        case unauthorized
        case rateLimited
        case http(Int)
        case empty(String)

        public var errorDescription: String? {
            switch self {
            case .noKey:
                String(localized: "Add your free ESV API key to read the ESV.", bundle: .module)
            case .unauthorized:
                String(localized: "Crossway didn't accept that API key. Check it at api.esv.org.", bundle: .module)
            case .rateLimited:
                String(localized: "You've reached Crossway's daily limit for your key. It resets tomorrow.", bundle: .module)
            case .http(let code):
                String(localized: "Crossway's API returned HTTP \(code).", bundle: .module, comment: "Error. %lld is an HTTP status code.")
            case .empty(let reference):
                String(localized: "Crossway returned nothing for \(reference).", bundle: .module, comment: "Error. %@ is a passage reference, e.g. “John 3”.")
            }
        }
    }

    private let key: String

    public init(key: String) { self.key = key }

    /// One chapter, with its structure: verses, the words of Christ, poetry lines, psalm titles.
    public func chapter(_ chapter: ChapterRef, session: URLSession = .shared) async throws -> ParsedPassage {
        var components = URLComponents(string: "https://api.esv.org/v3/passage/html/")!
        components.queryItems = [
            .init(name: "q", value: chapter.display),
            .init(name: "include-verse-numbers", value: "true"),
            .init(name: "include-headings", value: "false"),
            .init(name: "include-footnotes", value: "false"),
            .init(name: "include-short-copyright", value: "false"),
            .init(name: "include-passage-references", value: "false"),
            // The HTML endpoint states its structure instead of drawing it: words of Christ are
            // `<span class="woc">`, poetry is `<span class="line">` inside a `block-indent`
            // paragraph, a psalm's superscription is an `<h4 class="psalm-title">`. The text
            // endpoint has none of that — no red letters at all, and poetry only as whitespace
            // that cannot be told from wrapping.
            .init(name: "include-css-link", value: "false"),
            .init(name: "inline-styles", value: "false"),
            .init(name: "wrapping-div", value: "false"),
            .init(name: "include-book-titles", value: "false"),
            .init(name: "include-chapter-numbers", value: "true"),
            // Defaults to true, and drops an `<a class="mp3link">` into the passage heading.
            .init(name: "include-audio-link", value: "false"),
            .init(name: "include-crossrefs", value: "false"),
        ]
        var request = URLRequest(url: components.url!)
        request.timeoutInterval = 20
        request.setValue("Token \(key)", forHTTPHeaderField: "Authorization")

        let (data, response) = try await session.data(for: request)
        if let http = response as? HTTPURLResponse {
            switch http.statusCode {
            case 200..<300: break
            case 401, 403: throw Failure.unauthorized
            case 429: throw Failure.rateLimited
            case let code: throw Failure.http(code)
            }
        }
        let decoded = try JSONDecoder().decode(Response.self, from: data)
        let passage = ESVPassageHTML.parse(decoded.passages.joined(separator: "\n"), in: chapter)
        guard !passage.isEmpty else { throw Failure.empty(chapter.display) }
        return passage
    }


    /// Crossway's own search, so an online translation is searchable like any other.
    ///
    /// The whole text cannot be indexed on the device — Crossway's terms cap what may be cached at
    /// 500 verses — so the search happens at their end and costs one request.
    public func search(_ query: String, limit: Int = 100,
                       session: URLSession = .shared) async throws -> [BibleStore.SearchHit] {
        var components = URLComponents(string: "https://api.esv.org/v3/passage/search/")!
        components.queryItems = [
            .init(name: "q", value: query),
            .init(name: "page-size", value: String(min(limit, 100))),
        ]
        var request = URLRequest(url: components.url!)
        request.timeoutInterval = 20
        request.setValue("Token \(key)", forHTTPHeaderField: "Authorization")
        let (data, response) = try await session.data(for: request)
        if let http = response as? HTTPURLResponse {
            switch http.statusCode {
            case 200..<300: break
            case 401, 403: throw Failure.unauthorized
            case 429: throw Failure.rateLimited
            case let code: throw Failure.http(code)
            }
        }
        let decoded = try JSONDecoder().decode(SearchResponse.self, from: data)
        return decoded.results.compactMap { result in
            guard let passage = ReferenceParser.parse(result.reference) else { return nil }
            return BibleStore.SearchHit(ref: passage.firstVerse, text: result.content)
        }
    }

    private struct SearchResponse: Decodable {
        struct Result: Decodable { let reference: String; let content: String }
        let results: [Result]
    }

    private struct Response: Decodable { let passages: [String] }

    /// Kept as the name the ESV tests and callers already use.
    public static func parse(_ text: String, in chapter: ChapterRef) -> [VerseText] {
        BracketVerseParser.parse(text, in: chapter)
    }
}
#endif
