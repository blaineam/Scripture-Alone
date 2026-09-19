import SwiftUI
import SwiftData

@main
struct ScriptureAloneApp: App {
    private let container = DataStore.makeContainer()
    // Accepts family-sharing invitations (CKShare metadata), which SwiftUI has no hook for.
    #if os(iOS)
    @UIApplicationDelegateAdaptor(FamilyShareAppDelegate.self) private var familyShareDelegate
    #else
    @NSApplicationDelegateAdaptor(FamilyShareAppDelegate.self) private var familyShareDelegate
    #endif

    var body: some Scene {
        WindowGroup {
            RootView()
        }
        .modelContainer(container)
        #if os(macOS)
        .commands { ImportFromDevicesCommands() }
        #endif
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
            .shareSupport()
            .legacySupport()
            .widgetSnapshotSync()
            .environment(model)
            #if os(macOS)
            .frame(minWidth: 520, minHeight: 480)
            #endif
    }
}
