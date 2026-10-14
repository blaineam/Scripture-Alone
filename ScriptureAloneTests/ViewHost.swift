import SwiftUI
import SwiftData
import ScriptureAloneCore
@testable import Scripture_Alone
#if os(macOS)
import AppKit
typealias PlatformView = NSView
typealias PlatformWindow = NSWindow
#else
import UIKit
typealias PlatformView = UIView
typealias PlatformWindow = UIWindow
#endif

/// Hosts a SwiftUI view for real — an in-memory store, the reader's environment objects and a window
/// of the platform's own — so its body, modifiers and lifecycle actually run under test.
///
/// Nothing here reaches the network, iCloud or the reader's own data: the store is in memory and the
/// environment objects are the ones `RootView` builds.
@MainActor
enum ViewHost {
    /// The three SwiftData models, in memory only.
    static func container() throws -> ModelContainer {
        try ModelContainer(for: DataStore.schema, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
    }

    /// A little library: a favorite, highlights and notes, like the demo the screenshot rig seeds.
    @discardableResult
    static func seed(_ container: ModelContainer) -> ModelContext {
        let context = container.mainContext
        context.insert(Favorite(range: VerseRange(VerseRef(.john, 3, 16))))
        context.insert(Favorite(range: VerseRange(VerseRef(.romans, 8, 38), VerseRef(.romans, 8, 39))))
        context.insert(Highlight(verseKey: VerseRef(.john, 3, 16).key, color: .yellow))
        context.insert(Highlight(verseKey: VerseRef(.john, 3, 17).key, color: .blue))
        context.insert(Note(title: "Born again", body: "Nicodemus comes by night.",
                            anchors: [VerseRange(VerseRef(.john, 3, 1), VerseRef(.john, 3, 21))]))
        context.insert(Note(title: "Hinge", body: "No condemnation.",
                            anchors: [VerseRange(VerseRef(.romans, 8, 1))]))
        try? context.save()
        return context
    }

    /// A fresh, empty directory under the temporary directory, so no test touches the reader's own files.
    static func scratchDirectory(_ name: String) -> URL {
        let url = FileManager.default.temporaryDirectory.appending(path: "sa-view-host-\(name)-\(UUID().uuidString)", directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    /// The reader's model on the shipped ASV, open on John 3.
    static func readerModel(_ chapter: ChapterRef = ChapterRef(.john, 3)) -> ReaderModel {
        let model = ReaderModel()
        model.selectTranslation("ASV", remember: false)
        model.show(chapter)
        return model
    }

    /// `RootView`'s environment around `content`.
    static func inReader<V: View>(_ content: V, model: ReaderModel, container: ModelContainer,
                                  legacy: LegacyLibrary? = nil, session: LegacySession = LegacySession()) -> some View {
        content
            .shareSupport()
            .environment(model)
            .environment(ImportedLibrary())
            .environment(OnlineTranslationKeys())
            .environment(legacy ?? LegacyLibrary(directory: scratchDirectory("keepsakes")))
            .environment(session)
            .environment(StudyModel())
            .modelContainer(container)
    }

    /// Lays `view` out in a window of `size`, lets pending tasks run, and returns what was drawn.
    @discardableResult
    static func render<V: View>(_ view: V, size: CGSize = CGSize(width: 820, height: 900),
                                dark: Bool = false, settle: Int = 6) async -> HostedView {
        let root = view.environment(\.colorScheme, dark ? .dark : .light)
        #if os(macOS)
        let host = NSHostingView(rootView: root)
        host.frame = CGRect(origin: .zero, size: size)
        let window = NSWindow(contentRect: host.frame, styleMask: [.titled], backing: .buffered, defer: false)
        window.contentView = host
        window.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
        host.layoutSubtreeIfNeeded()
        for _ in 0..<settle {
            await Task.yield()
            host.layoutSubtreeIfNeeded()
            host.displayIfNeeded()
        }
        return HostedView(view: host, window: window)
        #else
        let controller = UIHostingController(rootView: root)
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        let window = scene.map { UIWindow(windowScene: $0) }
        window?.frame = CGRect(origin: .zero, size: size)
        window?.rootViewController = controller
        window?.isHidden = false
        controller.view.frame = CGRect(origin: .zero, size: size)
        controller.view.layoutIfNeeded()
        for _ in 0..<settle {
            await Task.yield()
            controller.view.layoutIfNeeded()
        }
        return HostedView(view: controller.view, window: window)
        #endif
    }
}

/// What a hosting run drew: the platform view and its size.
@MainActor
struct HostedView {
    let view: PlatformView
    let window: PlatformWindow?
    var size: CGSize { view.bounds.size }
    var isLaidOut: Bool { view.bounds.width > 0 && view.bounds.height > 0 }

    /// The view as pixels.
    var image: CGImage? {
        #if os(macOS)
        guard let rep = view.bitmapImageRepForCachingDisplay(in: view.bounds) else { return nil }
        view.cacheDisplay(in: view.bounds, to: rep)
        return rep.cgImage
        #else
        let renderer = UIGraphicsImageRenderer(bounds: view.bounds)
        return renderer.image { _ in view.drawHierarchy(in: view.bounds, afterScreenUpdates: true) }.cgImage
        #endif
    }

    /// How many different colors a sample of the drawn pixels holds: 1 for a blank page, many for
    /// text, icons and fills. A view that laid out but drew nothing reads 0 or 1.
    var distinctColors: Int {
        guard let image, let data = image.dataProvider?.data, let bytes = CFDataGetBytePtr(data) else { return 0 }
        let bytesPerPixel = max(image.bitsPerPixel / 8, 1)
        let total = CFDataGetLength(data) / bytesPerPixel
        guard total > 0 else { return 0 }
        var seen = Set<UInt32>()
        let step = max(total / 20_000, 1)
        var pixel = 0
        while pixel < total && seen.count < 64 {
            let offset = pixel * bytesPerPixel
            var value: UInt32 = 0
            for channel in 0..<min(bytesPerPixel, 4) { value = value << 8 | UInt32(bytes[offset + channel]) }
            seen.insert(value)
            pixel += step
        }
        return seen.count
    }

    /// Laid out and actually painted something.
    var isDrawn: Bool { isLaidOut && distinctColors > 1 }
}
