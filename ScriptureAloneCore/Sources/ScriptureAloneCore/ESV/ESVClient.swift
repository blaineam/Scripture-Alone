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
                "Add your free ESV API key to read the ESV."
            case .unauthorized:
                "Crossway didn't accept that API key. Check it at api.esv.org."
            case .rateLimited:
                "You've reached Crossway's daily limit for your key. It resets tomorrow."
            case .http(let code):
                "Crossway's API returned HTTP \(code)."
            case .empty(let reference):
                "Crossway returned nothing for \(reference)."
            }
        }
    }

    private let key: String

    public init(key: String) { self.key = key }

    /// One chapter, as verses. Asks for verse numbers and headings, and omits footnotes: the
    /// reader shows footnotes only for translations that ship their own.
    public func chapter(_ chapter: ChapterRef, session: URLSession = .shared) async throws -> [VerseText] {
        var components = URLComponents(string: "https://api.esv.org/v3/passage/text/")!
        components.queryItems = [
            .init(name: "q", value: chapter.display),
            .init(name: "include-verse-numbers", value: "true"),
            .init(name: "include-headings", value: "false"),
            .init(name: "include-footnotes", value: "false"),
            .init(name: "include-short-copyright", value: "false"),
            .init(name: "include-passage-references", value: "false"),
            .init(name: "indent-paragraphs", value: "0"),
            .init(name: "indent-poetry", value: "false"),
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
        let body = decoded.passages.joined(separator: "\n")
        let verses = BracketVerseParser.parse(body, in: chapter)
        guard !verses.isEmpty else { throw Failure.empty(chapter.display) }
        return verses
    }

    private struct Response: Decodable { let passages: [String] }

    /// Kept as the name the ESV tests and callers already use.
    public static func parse(_ text: String, in chapter: ChapterRef) -> [VerseText] {
        BracketVerseParser.parse(text, in: chapter)
    }
}
