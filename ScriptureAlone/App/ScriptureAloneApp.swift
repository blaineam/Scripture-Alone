import SwiftUI
import SwiftData

@main
struct ScriptureAloneApp: App {
    private let container = DataStore.makeContainer()

    var body: some Scene {
        WindowGroup {
            RootView()
        }
        .modelContainer(container)
        #if os(macOS)
        .defaultSize(width: 1080, height: 820)
        #endif

        // Maps, timeline and charts, to keep open beside the text (iPad and Mac).
        WindowGroup("Maps & Timeline", id: ContextViewerRequest.windowID, for: ContextViewerRequest.self) { $request in
            ContextViewerWindow(request: request)
        }
        #if os(macOS)
        .defaultSize(width: 760, height: 680)
        #endif
    }
}

/// Each window keeps its own place in the text.
private struct RootView: View {
    @State private var model = ReaderModel()

    var body: some View {
        ReaderView()
            .environment(model)
            #if os(macOS)
            .frame(minWidth: 520, minHeight: 480)
            #endif
    }
}
