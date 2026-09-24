import SwiftUI
import SwiftData

@main
struct ScriptureAloneApp: App {
    private let container = DataStore.shared
    // Accepts family-sharing invitations (CKShare metadata), which SwiftUI has no hook for.
    #if os(iOS)
    @UIApplicationDelegateAdaptor(FamilyShareAppDelegate.self) private var familyShareDelegate
    #else
    @NSApplicationDelegateAdaptor(FamilyShareAppDelegate.self) private var familyShareDelegate
    #endif

    init() {
        // First, before any view or model reads a setting: puts back the reader's settings from
        // iCloud on a reinstall or a new device. Reads the on-device cache only; never waits.
        SettingsSync.shared.start()
        #if os(iOS)
        // Early, so the watch's report of which editions it holds is waiting by the time the
        // reader's translation is restored.
        WatchLink.shared.activate()
        #endif
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
            .spotlightSync()
            .environment(model)
            .environment(library)
            .environment(onlineKeys)
            // Translations the reader added are part of the picker from the first frame.
            .task {
                // The loader goes in FIRST. Registering the translations can immediately select
                // one — the reader's last translation is restored the moment it becomes available
                // — and selecting an online translation fetches it. With the loader still nil at
                // that point, the fetch failed and told the reader their translation "needs a
                // key", which was both wrong and alarming: the key was fine, the app simply was
                // not wired up yet. Switching away and back fixed it, which is the signature of
                // an ordering bug rather than a missing key.
                let loader = OnlineTextLoader(keys: onlineKeys)
                model.onlineLoader = { entry, chapter in try await loader.chapter(entry, chapter) }
                model.onlineSearch = { entry, query in try await loader.search(entry, query) }

                model.refreshTranslations(imported: library.entries.map { ($0.info, $0.url) })
                await ImportedBibleSync.shared.start()
                // A Crossway key is enough to offer the ESV; API.Bible's picks are remembered
                // when they are made, so they come back here without a network call and without
                // the reader having to open the keys screen again.
                model.setOnlineTranslations(OnlineCatalog.restored(keys: onlineKeys))
            }
            // Translations imported on another of the reader's devices, as they arrive.
            .onChange(of: library.entries, initial: true) {
                model.refreshTranslations(imported: library.entries.map { ($0.info, $0.url) })
                #if os(iOS)
                WatchLink.shared.importsChanged(library.entries.map {
                    TranslationEntry(id: $0.info.id, name: $0.info.name, url: $0.url, abbreviation: $0.info.abbreviation)
                })
                #endif
            }
            // Settings that arrived from iCloud after launch — on a reinstall, usually a moment
            // after the first frame. `@AppStorage` views follow on their own; the translation list
            // and, on the first restore only, the translation itself are the model's to pick up.
            .onReceive(NotificationCenter.default.publisher(for: SettingsSync.restoredNotification)) { note in
                let keys = note.userInfo?["keys"] as? [String] ?? []
                if keys.contains(OnlineCatalog.storageKey) {
                    model.setOnlineTranslations(OnlineCatalog.restored(keys: onlineKeys))
                }
                if note.userInfo?["initial"] as? Bool == true, keys.contains("translation"),
                   let id = UserDefaults.standard.string(forKey: "translation") {
                    model.restoreTranslation(id)
                }
            }
            #if os(macOS)
            .frame(minWidth: 520, minHeight: 480)
            #endif
    }
}
