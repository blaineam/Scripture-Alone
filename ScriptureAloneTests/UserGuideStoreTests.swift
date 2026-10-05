import Foundation
import Testing
import ScriptureAloneCore
@testable import Scripture_Alone

/// The User Guide download against a stubbed release: the first fetch, the checksum gate, the
/// newer-edition swap, and failures that end in Try Again rather than a spinner.
@MainActor
struct UserGuideStoreTests {
    let root = URL.temporaryDirectory.appending(path: "UserGuideStoreTests-\(UUID().uuidString)", directoryHint: .isDirectory)

    static func package(title: String) -> Data {
        let json = """
        {"schema":1,"language":"en","title":"\(title)","contents":"Contents",
         "cover":{"eyebrow":"User Guide","title":"Scripture Alone","subtitle":"s","edition":"v1","images":[]},
         "chapters":[{"title":"Welcome","summary":"s","lede":[{"text":"Hello"}],"blocks":[]}]}
        """
        return ZipArchive.write([ZipArchive.Entry(name: "guide.json", data: Data(json.utf8))])
    }

    static func index(_ packages: [String: Data], schema: Int = 1) -> Data {
        let entries = packages.mapValues { ["sha256": UserGuidePackage.sha256(of: $0), "size": $0.count] as [String: Any] }
        return try! JSONSerialization.data(withJSONObject: ["schema": schema, "packages": entries])
    }

    func store(_ routes: [String: StubURLProtocol.Response], device: UserGuidePackage.Device = .iphone) -> (UserGuideStore, String) {
        let (session, id) = StubURLProtocol.session(routes)
        return (UserGuideStore(language: "en", device: device, root: root, session: session), id)
    }

    @Test func theFirstOpenDownloadsVerifiesAndKeepsTheGuide() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let package = Self.package(title: "Guide v1")
        let (store, id) = store([
            UserGuidePackage.indexURL.absoluteString: .init(body: Self.index(["en": package])),
            UserGuidePackage.packageURL(language: "en").absoluteString: .init(body: package),
        ])
        #expect(store.phase == .idle)
        store.open()
        #expect(await eventually { store.phase == .ready })
        #expect(store.guide?.title == "Guide v1")
        #expect(store.downloadSize == package.count)
        #expect(try String(contentsOf: UserGuideStore.hashFile(in: store.directory), encoding: .utf8)
                == UserGuidePackage.sha256(of: package))

        // The next launch reads the copy on disk without fetching a package again.
        let (relaunched, second) = self.store([
            UserGuidePackage.indexURL.absoluteString: .init(body: Self.index(["en": package])),
        ])
        relaunched.open()
        #expect(relaunched.phase == .ready, "a held copy shows at once")
        #expect(relaunched.guide?.title == "Guide v1")
        #expect(await eventually { StubURLProtocol.requests(second).contains(UserGuidePackage.indexURL.absoluteString) })
        #expect(!StubURLProtocol.requests(second).contains(UserGuidePackage.packageURL(language: "en").absoluteString),
                "an unchanged guide is not downloaded again")
        #expect(StubURLProtocol.requests(id).count == 2)
    }

    @Test func aNewerEditionReplacesTheHeldCopy() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let v1 = Self.package(title: "Guide v1"), v2 = Self.package(title: "Guide v2")
        let (first, _) = store([
            UserGuidePackage.indexURL.absoluteString: .init(body: Self.index(["en": v1])),
            UserGuidePackage.packageURL(language: "en").absoluteString: .init(body: v1),
        ])
        first.open()
        #expect(await eventually { first.phase == .ready })

        let (second, _) = store([
            UserGuidePackage.indexURL.absoluteString: .init(body: Self.index(["en": v2])),
            UserGuidePackage.packageURL(language: "en").absoluteString: .init(body: v2),
        ])
        second.open()
        #expect(second.guide?.title == "Guide v1")
        #expect(await eventually { second.guide?.title == "Guide v2" })
    }

    @Test func aPackageThatFailsItsChecksumIsNeverInstalled() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let real = Self.package(title: "Real"), tampered = Self.package(title: "Tampered")
        let (store, _) = store([
            UserGuidePackage.indexURL.absoluteString: .init(body: Self.index(["en": real])),
            UserGuidePackage.packageURL(language: "en").absoluteString: .init(body: tampered),
        ])
        store.open()
        #expect(await eventually { store.phase == .failed })
        #expect(store.guide == nil)
        #expect(UserGuidePackage.load(from: store.directory) == nil)
    }

    @Test func aMissingReleaseEndsInTryAgainNotASpinner() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let (broken, id) = store([:])       // every request 404s
        broken.open()
        #expect(await eventually { broken.phase == .failed })
        #expect(broken.guide == nil)
        broken.retry()
        #expect(broken.phase == .downloading(nil))
        #expect(await eventually { broken.phase == .failed })
        #expect(StubURLProtocol.requests(id).count == 2, "each attempt asks for the index once, and stops there")
    }

    /// The iPad reads its own edition when the release has one, the iPhone's when it doesn't yet.
    @Test func aDeviceEditionIsPreferredWhenPublished() async throws {
        defer { try? FileManager.default.removeItem(at: root) }
        let plain = Self.package(title: "iPhone"), ipad = Self.package(title: "iPad")
        let (store, _) = store([
            UserGuidePackage.indexURL.absoluteString: .init(body: Self.index(["en": plain, "ipad-en": ipad])),
            UserGuidePackage.packageURL(language: "en").absoluteString: .init(body: plain),
            UserGuidePackage.packageURL(language: "ipad-en").absoluteString: .init(body: ipad),
        ], device: .ipad)
        #expect(store.edition == "ipad-en")
        store.open()
        #expect(await eventually { store.phase == .ready })
        #expect(store.guide?.title == "iPad")
    }

    @Test func installRefusesAMismatchedHash() async {
        await #expect(throws: UserGuideStore.InstallError.self) {
            _ = try await UserGuideStore.install(Self.package(title: "x"), sha256: String(repeating: "0", count: 64),
                                                 into: root.appending(path: "en"))
        }
    }
}
