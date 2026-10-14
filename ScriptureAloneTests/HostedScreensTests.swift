import SwiftUI
import SwiftData
import Testing
import ScriptureAloneCore
@testable import Scripture_Alone

/// The app's screens drawn for real, in light and dark: each must lay out at a real size with the
/// reader's environment around it. They exercise the view bodies, their bindings and their queries
/// against an in-memory library.
@MainActor
@Suite(.serialized)
struct HostedScreensTests {
    let container: ModelContainer
    let model: ReaderModel

    init() throws {
        container = try ViewHost.container()
        ViewHost.seed(container)
        model = ViewHost.readerModel()
    }

    func draw<V: View>(_ view: V, dark: Bool = false, size: CGSize = CGSize(width: 820, height: 900)) async -> HostedView {
        await ViewHost.render(ViewHost.inReader(view, model: model, container: container), size: size, dark: dark)
    }

    @Test func readerDrawsTheChapter() async {
        for dark in [false, true] {
            let hosted = await draw(ReaderView(), dark: dark)
            #expect(hosted.isDrawn)
            #expect(hosted.distinctColors > 1, "the reader painted the page")
        }
    }

    @Test func passagePickerDraws() async {
        #expect(await draw(PassagePicker()).isDrawn)
        #expect(await draw(PassagePicker(initialQuery: "love")).isDrawn)
    }

    @Test func translationsDraw() async {
        #expect(await draw(TranslationsView()).isDrawn)
    }

    @Test func appearanceAndCompareDraw() async {
        #expect(await draw(AppearanceView()).isDrawn)
        #expect(await draw(CompareView()).isDrawn)
    }
}
