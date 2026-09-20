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

    init() {
        #if DEBUG && os(iOS) && !targetEnvironment(simulator)
        // One-time: create every CloudKit record type in the development environment so the
        // schema can be deployed to production (see CloudKitSchemaBootstrap).
        CloudKitSchemaBootstrap.runIfNeeded()
        #endif
    }

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
    @State private var library = ImportedLibrary()
    @State private var onlineKeys = OnlineTranslationKeys()

    var body: some View {
        ReaderView()
            .shareSupport()
            .legacySupport()
            .widgetSnapshotSync()
            .environment(model)
            .environment(library)
            .environment(onlineKeys)
            // Translations the reader added are part of the picker from the first frame.
            .task {
                model.refreshTranslations(imported: library.entries.map { ($0.info, $0.url) })
                // A Crossway key is enough to offer the ESV; API.Bible's picks are remembered
                // when they are made, so they come back here without a network call and without
                // the reader having to open the keys screen again.
                model.setOnlineTranslations(OnlineCatalog.restored(keys: onlineKeys))
                let loader = OnlineTextLoader(keys: onlineKeys)
                model.onlineLoader = { entry, chapter in try await loader.chapter(entry, chapter) }
                model.onlineSearch = { entry, query in try await loader.search(entry, query) }
            }
            #if os(macOS)
            .frame(minWidth: 520, minHeight: 480)
            #endif
    }
}
