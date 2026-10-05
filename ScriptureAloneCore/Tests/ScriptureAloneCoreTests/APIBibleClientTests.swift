import Foundation
import Testing
@testable import ScriptureAloneCore

/// API.Bible over a stubbed transport: no network, no account. The stub answers by the request's
/// `api-key` header, so every test uses its own key and the suite can run in parallel.
struct APIBibleClientTests {
    let john3 = ChapterRef(.john, 3)

    // MARK: Translations a key can read

    @Test func listsTheTranslationsTheKeyCanRead() async throws {
        let key = StubURLProtocol.uniqueKey()
        StubURLProtocol.register(key) { _ in
            (200, Data("""
            {"data":[
              {"id":"de4e12af7f28f599-02","name":"King James (Authorised) Version","abbreviation":"engKJV",
               "abbreviationLocal":"KJV","copyright":"Public domain","language":{"name":"English"}},
              {"id":"bare-01"}
            ]}
            """.utf8))
        }
        let list = try await APIBibleClient(key: key).availableTranslations(session: StubURLProtocol.session)
        #expect(StubURLProtocol.lastRequest(key)?.url?.path == "/v1/bibles")
        #expect(list.count == 2)
        #expect(list[0] == APIBibleTranslation(id: "de4e12af7f28f599-02", name: "King James (Authorised) Version",
                                               abbreviation: "KJV", language: "English", copyright: "Public domain"))
        // An entry with nothing but an id still lists, named by its id, with the standard notice.
        #expect(list[1].name == "bare-01")
        #expect(list[1].abbreviation == "")
        #expect(list[1].copyright == "Used by permission via API.Bible.")
    }

    @Test func sendsTheKeyInTheHeaderNeverTheURL() async throws {
        let key = StubURLProtocol.uniqueKey()
        StubURLProtocol.register(key) { _ in (200, Data(#"{"data":[]}"#.utf8)) }
        _ = try await APIBibleClient(key: key).availableTranslations(session: StubURLProtocol.session)
        let request = try #require(StubURLProtocol.lastRequest(key))
        #expect(request.value(forHTTPHeaderField: "api-key") == key)
        #expect(!(request.url?.absoluteString.contains(key) ?? true), "a key in a URL ends up in logs")
    }

    // MARK: Status codes

    @Test(arguments: [
        (401, APIBibleClient.Failure.unauthorized),
        (429, APIBibleClient.Failure.rateLimited),
        (500, APIBibleClient.Failure.http(500)),
        (404, APIBibleClient.Failure.http(404)),
    ])
    func mapsHTTPFailures(status: Int, expected: APIBibleClient.Failure) async {
        let key = StubURLProtocol.uniqueKey()
        StubURLProtocol.register(key) { _ in (status, Data()) }
        await #expect(throws: expected) {
            _ = try await APIBibleClient(key: key, bibleID: "x").chapter(john3, session: StubURLProtocol.session)
        }
    }

    /// 403 means the reader's key doesn't include this translation — said in terms of what they asked for.
    @Test func forbiddenNamesWhatWasAskedFor() async {
        let key = StubURLProtocol.uniqueKey()
        StubURLProtocol.register(key) { _ in (403, Data()) }
        await #expect(throws: APIBibleClient.Failure.notAvailable(john3.display)) {
            _ = try await APIBibleClient(key: key, bibleID: "x").chapter(john3, session: StubURLProtocol.session)
        }
    }

    @Test func everyFailureHasAMessage() {
        let failures: [APIBibleClient.Failure] = [.unauthorized, .notAvailable("NIV"), .rateLimited, .http(502), .empty("John 3")]
        for failure in failures {
            #expect(!(failure.errorDescription ?? "").isEmpty)
        }
        #expect(APIBibleClient.Failure.http(502).errorDescription?.contains("502") == true)
        #expect(APIBibleClient.Failure.notAvailable("NIV").errorDescription?.contains("NIV") == true)
    }

    // MARK: Chapters

    @Test func requestsTheChapterByUSFMCodeAsHTML() async throws {
        let key = StubURLProtocol.uniqueKey()
        StubURLProtocol.register(key) { _ in
            (200, Self.chapterJSON("""
            <p class="p"><span data-number="16" data-sid="JHN 3:16" class="v">16</span>For God so loved the world. </p>\
            <p class="p"><span data-number="17" data-sid="JHN 3:17" class="v">17</span>Not to condemn. </p>
            """))
        }
        let passage = try await APIBibleClient(key: key, bibleID: "bible-7").chapter(john3, session: StubURLProtocol.session)
        let url = try #require(StubURLProtocol.lastRequest(key)?.url)
        #expect(url.path == "/v1/bibles/bible-7/chapters/JHN.3")
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        let query = Dictionary(uniqueKeysWithValues: items.map { ($0.name, $0.value ?? "") })
        #expect(query["content-type"] == "html")
        #expect(query["include-verse-numbers"] == "true")
        #expect(query["include-notes"] == "false")
        #expect(passage.verses.map(\.ref) == [VerseRef(.john, 3, 16), VerseRef(.john, 3, 17)])
        #expect(passage.verses.first?.text == "For God so loved the world.")
    }

    @Test func anEmptyChapterIsAnErrorNotABlankPage() async {
        let key = StubURLProtocol.uniqueKey()
        StubURLProtocol.register(key) { _ in (200, Self.chapterJSON("")) }
        await #expect(throws: APIBibleClient.Failure.empty(john3.display)) {
            _ = try await APIBibleClient(key: key, bibleID: "b").chapter(john3, session: StubURLProtocol.session)
        }
    }

    // MARK: Search

    @Test func searchHitsAreParsedReferencesAndUnparseableOnesAreDropped() async throws {
        let key = StubURLProtocol.uniqueKey()
        StubURLProtocol.register(key) { _ in
            (200, Data("""
            {"data":{"verses":[
              {"reference":"John 3:16","text":"For God so loved the world"},
              {"reference":"Not a reference at all","text":"dropped"},
              {"reference":"1 John 2:15","text":"Love not the world"}
            ]}}
            """.utf8))
        }
        let hits = try await APIBibleClient(key: key, bibleID: "b").search("loved the world", limit: 500,
                                                                          session: StubURLProtocol.session)
        let items = URLComponents(url: try #require(StubURLProtocol.lastRequest(key)?.url),
                                  resolvingAgainstBaseURL: false)?.queryItems ?? []
        #expect(items.first { $0.name == "query" }?.value == "loved the world")
        // The provider caps a page at 100; asking for more must not be sent through.
        #expect(items.first { $0.name == "limit" }?.value == "100")
        #expect(hits.map(\.ref) == [VerseRef(.john, 3, 16), VerseRef(.firstJohn, 2, 15)])
        #expect(hits.first?.text == "For God so loved the world")
    }

    @Test func searchWithNoVersesIsEmpty() async throws {
        let key = StubURLProtocol.uniqueKey()
        StubURLProtocol.register(key) { _ in (200, Data(#"{"data":{}}"#.utf8)) }
        let hits = try await APIBibleClient(key: key, bibleID: "b").search("zzz", session: StubURLProtocol.session)
        #expect(hits.isEmpty)
    }

    static func chapterJSON(_ html: String) -> Data {
        try! JSONSerialization.data(withJSONObject: ["data": ["content": html]])
    }
}

/// A URLProtocol that answers from handlers registered per API key.
final class StubURLProtocol: URLProtocol, @unchecked Sendable {
    typealias Handler = @Sendable (URLRequest) -> (Int, Data)

    private static let lock = NSLock()
    nonisolated(unsafe) private static var handlers: [String: Handler] = [:]
    nonisolated(unsafe) private static var requests: [String: URLRequest] = [:]

    static func lastRequest(_ key: String) -> URLRequest? {
        lock.lock(); defer { lock.unlock() }
        return requests[key]
    }

    static func uniqueKey() -> String { "test-key-\(UUID().uuidString)" }

    static func register(_ key: String, _ handler: @escaping Handler) {
        lock.lock(); defer { lock.unlock() }
        handlers[key] = handler
    }

    static let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubURLProtocol.self]
        return URLSession(configuration: configuration)
    }()

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let key = request.value(forHTTPHeaderField: "api-key") ?? ""
        Self.lock.lock()
        let handler = Self.handlers[key]
        Self.requests[key] = request
        Self.lock.unlock()
        guard let handler else {
            client?.urlProtocol(self, didFailWithError: URLError(.cannotConnectToHost))
            return
        }
        let (status, data) = handler(request)
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
