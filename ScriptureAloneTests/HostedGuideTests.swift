import SwiftUI
import Testing
import ScriptureAloneCore
@testable import Scripture_Alone

/// The User Guide, its first-launch card and the Settings row, drawn for real from a package built
/// in a scratch directory (never the downloaded one, never the network).
@MainActor
@Suite(.serialized)
struct HostedGuideTests {
    static func guideJSON() -> String {
        let blocks = UserGuideRenderTests.blocksJSON
        return """
        {"schema":1,"language":"en","title":"Scripture Alone","contents":"Contents",
         "cover":{"eyebrow":"User Guide","title":"Scripture Alone","subtitle":"Read, study, remember.","edition":"Edition 1",
                  "images":["a.png","b.png","c.png"]},
         "chapters":[
           {"title":"Welcome","summary":"Where to begin","lede":[{"text":"Hello and "},{"text":"welcome","bold":true}],"blocks":\(blocks)},
           {"title":"Reading","summary":"Pages and verses","lede":[{"text":"The reader."}],"blocks":[]}
         ]}
        """
    }

    func store(ready: Bool, device: UserGuidePackage.Device = .iphone) throws -> UserGuideStore {
        let root = ViewHost.scratchDirectory("guide")
        let (session, _) = StubURLProtocol.session([:])
        let store = UserGuideStore(language: "en", device: device, root: root, session: session)
        if ready {
            try FileManager.default.createDirectory(at: store.directory, withIntermediateDirectories: true)
            try Data(Self.guideJSON().utf8).write(to: store.directory.appending(path: "guide.json"))
            store.open()
        }
        return store
    }

    func draw<V: View>(_ view: V, dark: Bool = false, size: CGSize = CGSize(width: 820, height: 900)) async -> HostedView {
        await ViewHost.render(view, size: size, dark: dark)
    }

    @Test func theGuideDrawsWideAndNarrowInLightAndDark() async throws {
        let ready = try store(ready: true)
        #expect(ready.phase == .ready)
        #expect(await draw(UserGuideView(store: ready)).isDrawn)
        #expect(await draw(UserGuideView(store: ready), dark: true, size: CGSize(width: 390, height: 844)).isDrawn)
        let mac = try store(ready: true, device: .mac)
        #expect(await draw(UserGuideView(store: mac), size: CGSize(width: 1100, height: 900)).isDrawn)
    }

    @Test func theGuideShowsItsDownloadingAndFailedStates() async throws {
        let waiting = try store(ready: false)
        #expect(await draw(UserGuideView(store: waiting)).isDrawn)
        // No release answers (every URL is a 404), so the first fetch ends in Try Again.
        waiting.open()
        for _ in 0..<50 where waiting.phase != .failed { await Task.yield() }
        #expect(await draw(UserGuideView(store: waiting), dark: true).isDrawn)
    }

    @Test func theRowAndTheWelcomeCardDraw() async {
        #expect(await draw(List { UserGuideRow() }).isDrawn)
        var read = 0, skipped = 0
        let hosted = await draw(UserGuidePromptCard(read: { read += 1 }, skip: { skipped += 1 }))
        #expect(hosted.isDrawn)
        #expect(await draw(UserGuidePromptCard(read: {}, skip: {}), dark: true, size: CGSize(width: 390, height: 600)).isDrawn)
        #expect(read == 0 && skipped == 0, "drawing a card presses nothing")
    }
}
