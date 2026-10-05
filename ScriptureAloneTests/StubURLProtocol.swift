import Foundation

/// Serves canned HTTP responses to a URLSession made by `session(_:)`. Each session carries its own
/// id in a header, so parallel tests that request the same URLs never see each other's answers. No
/// request ever leaves the process.
nonisolated final class StubURLProtocol: URLProtocol, @unchecked Sendable {
    struct Response: Sendable {
        var status: Int = 200
        var body: Data = Data()
        /// Drop the connection after sending `body` (a download cut off half way).
        var dropsAfterBody = false
        /// Leave out Content-Length, as a chunked response does.
        var statesLength = true
    }

    private static let header = "X-Stub-Session"
    private static let lock = NSLock()
    nonisolated(unsafe) private static var routes: [String: [String: Response]] = [:]
    nonisolated(unsafe) private static var hits: [String: [String]] = [:]

    /// A session answering `routes` (absolute URL string → response); anything else is a 404.
    static func session(_ routes: [String: Response]) -> (URLSession, id: String) {
        let id = UUID().uuidString
        lock.lock(); self.routes[id] = routes; lock.unlock()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubURLProtocol.self]
        configuration.httpAdditionalHeaders = [header: id]
        return (URLSession(configuration: configuration), id)
    }

    static func requests(_ id: String) -> [String] {
        lock.lock(); defer { lock.unlock() }
        return hits[id] ?? []
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let id = request.value(forHTTPHeaderField: Self.header) ?? ""
        let url = request.url?.absoluteString ?? ""
        Self.lock.lock()
        let response = Self.routes[id]?[url] ?? Response(status: 404)
        Self.hits[id, default: []].append(url)
        Self.lock.unlock()
        let declared = response.dropsAfterBody ? response.body.count * 2 : response.body.count
        let http = HTTPURLResponse(url: request.url!, statusCode: response.status, httpVersion: "HTTP/1.1",
                                   headerFields: response.statesLength ? ["Content-Length": "\(declared)"] : [:])!
        client?.urlProtocol(self, didReceive: http, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: response.body)
        if response.dropsAfterBody {
            client?.urlProtocol(self, didFailWithError: URLError(.networkConnectionLost))
        } else {
            client?.urlProtocolDidFinishLoading(self)
        }
    }

    override func stopLoading() {}
}

/// Waits for a condition the code under test reaches on its own task, without a fixed sleep:
/// checks after every short pause and gives up at the deadline.
@MainActor
func eventually(timeout: Duration = .seconds(10), _ condition: () -> Bool) async -> Bool {
    let clock = ContinuousClock()
    let deadline = clock.now + timeout
    while !condition() {
        if clock.now > deadline { return false }
        try? await Task.sleep(for: .milliseconds(10))
    }
    return true
}
