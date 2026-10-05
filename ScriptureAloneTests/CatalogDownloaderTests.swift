import Foundation
import Testing
@testable import ScriptureAloneCore
@testable import Scripture_Alone

/// The eBible.org download, against a stubbed server: what lands on disk, the progress reported,
/// and what is left behind when it fails.
struct CatalogDownloaderTests {
    static func translation(_ id: String) -> CatalogTranslation {
        CatalogTranslation(id: id, languageCode: "eng", languageName: "English", languageNameInEnglish: "English",
                           title: id, shortTitle: id, copyright: "public domain", isRedistributable: true,
                           otBooks: 39, ntBooks: 27, otVerses: 23145, ntVerses: 7957, textDirection: "ltr", script: "Latin")
    }

    nonisolated final class Progress: @unchecked Sendable {
        private let lock = NSLock()
        private var values: [Double?] = []
        func add(_ value: Double?) { lock.lock(); values.append(value); lock.unlock() }
        var all: [Double?] { lock.lock(); defer { lock.unlock() }; return values }
    }

    /// Files a download with this id left in the temporary directory.
    static func leftovers(_ id: String) -> [String] {
        ((try? FileManager.default.contentsOfDirectory(atPath: FileManager.default.temporaryDirectory.path)) ?? [])
            .filter { $0.hasPrefix("\(id)-") }
    }

    @Test func downloadsTheZipToATemporaryFileAndReportsProgress() async throws {
        let id = "test\(UUID().uuidString.prefix(8))"
        let body = Data((0..<140_000).map { UInt8($0 % 251) })
        let translation = Self.translation(id)
        let (session, requests) = StubURLProtocol.session([translation.downloadURL.absoluteString: .init(body: body)])
        let progress = Progress()
        let file = try await CatalogDownloader().download(translation, session: session) { progress.add($0) }
        defer { try? FileManager.default.removeItem(at: file) }

        #expect(try Data(contentsOf: file) == body)
        #expect(file.pathExtension == "zip")
        #expect(file.lastPathComponent.hasPrefix("\(id)-"))
        #expect(StubURLProtocol.requests(requests) == ["https://ebible.org/Scriptures/\(id)_usfm.zip"])
        let reported = progress.all.compactMap { $0 }
        #expect(reported.last == 1)
        #expect(reported == reported.sorted(), "progress never goes backwards")
        #expect(reported.allSatisfy { (0...1).contains($0) })
    }

    @Test func aServerErrorIsReportedAndLeavesNothingBehind() async {
        let id = "test\(UUID().uuidString.prefix(8))"
        let translation = Self.translation(id)
        let (session, _) = StubURLProtocol.session([translation.downloadURL.absoluteString: .init(status: 503, body: Data("busy".utf8))])
        await #expect {
            _ = try await CatalogDownloader().download(translation, session: session) { _ in }
        } throws: { error in
            guard case CatalogDownloader.Failure.http(503) = error else { return false }
            return true
        }
        #expect(Self.leftovers(id).isEmpty)
    }

    @Test func anEmptyDownloadIsAnErrorAndLeavesNothingBehind() async {
        let id = "test\(UUID().uuidString.prefix(8))"
        let translation = Self.translation(id)
        let (session, _) = StubURLProtocol.session([translation.downloadURL.absoluteString: .init(body: Data())])
        await #expect {
            _ = try await CatalogDownloader().download(translation, session: session) { _ in }
        } throws: { error in
            guard case CatalogDownloader.Failure.empty = error else { return false }
            return true
        }
        #expect(Self.leftovers(id).isEmpty)
    }

    /// A connection lost half way through must not leave a truncated zip in the temporary
    /// directory for every failed attempt.
    @Test func aDroppedConnectionFailsAndLeavesNoPartialFile() async {
        let id = "test\(UUID().uuidString.prefix(8))"
        let translation = Self.translation(id)
        let (session, _) = StubURLProtocol.session([
            translation.downloadURL.absoluteString: .init(body: Data(repeating: 7, count: 140_000), dropsAfterBody: true),
        ])
        await #expect(throws: (any Error).self) {
            _ = try await CatalogDownloader().download(translation, session: session) { _ in }
        }
        #expect(Self.leftovers(id).isEmpty, "partial download left behind: \(Self.leftovers(id))")
    }

    @Test func withoutAContentLengthProgressIsIndeterminateUntilDone() async throws {
        let id = "test\(UUID().uuidString.prefix(8))"
        let translation = Self.translation(id)
        let (session, _) = StubURLProtocol.session([
            translation.downloadURL.absoluteString: .init(body: Data(repeating: 1, count: 140_000), statesLength: false),
        ])
        let progress = Progress()
        let file = try await CatalogDownloader().download(translation, session: session) { progress.add($0) }
        defer { try? FileManager.default.removeItem(at: file) }
        #expect(progress.all.last == .some(1))
        #expect(progress.all.dropLast().allSatisfy { $0 == nil })
    }

    @Test func failuresExplainThemselves() {
        #expect(CatalogDownloader.Failure.http(404).errorDescription?.contains("404") == true)
        #expect(CatalogDownloader.Failure.empty.errorDescription?.isEmpty == false)
    }
}
