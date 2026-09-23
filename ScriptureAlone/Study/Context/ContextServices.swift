import SwiftUI
import Observation
import ScriptureAloneCore

/// The bundled context data, opened once for the app.
@MainActor
final class ContextLibrary {
    static let shared = ContextLibrary()

    let store: ContextStore?
    let basemap: Basemap?
    private(set) lazy var paths: BasemapPaths? = basemap.map { BasemapPaths($0) }
    private(set) lazy var labels: [MapLabel] = (try? store?.labels()) ?? []
    private(set) lazy var eras: [Era] = (try? store?.eras()) ?? []
    private(set) lazy var events: [TimelineEvent] = (try? store?.events()) ?? []
    private(set) lazy var charts: [ChartInfo] = (try? store?.charts()) ?? []
    /// The most-mentioned places, drawn faintly for orientation.
    private(set) lazy var prominentPlaces: [Place] = (try? store?.prominentPlaces(limit: 500)) ?? []

    private init() {
        store = Bundle.main.url(forResource: "Context", withExtension: "sqlite").flatMap { try? ContextStore(url: $0) }
        basemap = Bundle.main.url(forResource: "Basemap", withExtension: "bin")
            .flatMap { try? Data(contentsOf: $0, options: .mappedIfSafe) }
            .flatMap { try? Basemap(data: $0) }
    }

    func time(for chapter: ChapterRef) -> ChapterTime? { try? store?.time(for: chapter) }
    func places(in chapter: ChapterRef) -> [PlaceMention] { (try? store?.places(in: chapter)) ?? [] }
    func events(in chapter: ChapterRef) -> [TimelineEvent] { (try? store?.events(in: chapter)) ?? [] }
    func charts(for book: BookID) -> [ChartInfo] { charts.filter { $0.scope.contains(book) } }
    func era(id: String) -> Era? { eras.first { $0.id == id } }
}

/// Links the reader to Study context shown elsewhere (a sheet, a panel or another window):
/// the reader publishes where it is; context views ask it to jump to a passage.
@MainActor
@Observable
final class ReadingFocus {
    static let shared = ReadingFocus()

    struct JumpRequest: Equatable {
        let id = UUID()
        let verse: VerseRef
    }

    /// The chapter most recently shown by a reader.
    var chapter: ChapterRef?
    private(set) var jumpRequest: JumpRequest?

    func jump(to verse: VerseRef) { jumpRequest = JumpRequest(verse: verse) }
}

/// Plain verse text for previews, in the reader's current translation.
@MainActor
final class VersePreview {
    static let shared = VersePreview()
    private var stores: [String: BibleStore] = [:]
    private var cache: [Int: String] = [:]
    private var cachedTranslation = ""

    private var translation: String { UserDefaults.standard.string(forKey: "translation") ?? ReaderModel.defaultTranslation }

    func text(for key: Int) -> String? {
        let id = translation
        if id != cachedTranslation {
            cache.removeAll()
            cachedTranslation = id
        }
        if let text = cache[key] { return text }
        guard let ref = VerseRef(key: key), let source = source(id) else { return nil }
        let text = (try? source.verses(in: VerseRange(ref)))?.first?.text
        cache[key] = text
        return text
    }

    /// The reader's translation, whatever kind it is — a sealed package, a bundled store, or an
    /// import. Falls back to any translation the app can open rather than to a fixed filename,
    /// since the default translation ships sealed and has no `.sqlite` to fall back to.
    private func source(_ id: String) -> (any ChapterTextSource)? {
        if let package = SealedTranslations.shared.package(id) { return package }
        if let store = stores[id] { return store }
        if let url = Bundle.main.url(forResource: id, withExtension: "sqlite"),
           let store = try? BibleStore(url: url) {
            stores[id] = store
            return store
        }
        if let package = SealedTranslations.identifiers.lazy
            .compactMap({ SealedTranslations.shared.package($0) }).first { return package }
        for fallback in ["BSB", "KJV"] {
            if let store = stores[fallback] { return store }
            if let url = Bundle.main.url(forResource: fallback, withExtension: "sqlite"),
               let store = try? BibleStore(url: url) {
                stores[fallback] = store
                return store
            }
        }
        return nil
    }
}

/// What a context viewer window (or sheet) opens to.
struct ContextViewerRequest: Codable, Hashable {
    static let windowID = "context-viewer"

    enum Tab: String, Codable, CaseIterable, Identifiable {
        case overview, map, timeline, charts
        var id: String { rawValue }
        var title: String {
            switch self {
            case .overview: String(localized: "Overview", comment: "Tab of the Bible context viewer")
            case .map: String(localized: "Map", comment: "Tab of the Bible context viewer")
            case .timeline: String(localized: "Timeline", comment: "Tab of the Bible context viewer")
            case .charts: String(localized: "Charts", comment: "Tab of the Bible context viewer")
            }
        }
        var symbol: String {
            switch self {
            case .overview: "text.page"
            case .map: "map"
            case .timeline: "calendar.day.timeline.left"
            case .charts: "tablecells"
            }
        }
    }

    var chapter: ChapterRef
    var tab: Tab = .map
    var chartID: String?
}

extension EnvironmentValues {
    /// Set inside a context viewer so its Overview switches tabs instead of opening another viewer.
    @Entry var contextViewerHandler: ContextViewerHandler?
}

struct ContextViewerHandler {
    let open: @MainActor (ContextViewerRequest) -> Void
}

extension Color {
    /// "#RRGGBB"
    init(contextHex hex: String) {
        let value = UInt32(hex.trimmingCharacters(in: CharacterSet(charactersIn: "#")), radix: 16) ?? 0x888888
        self.init(red: Double((value >> 16) & 0xFF) / 255, green: Double((value >> 8) & 0xFF) / 255,
                  blue: Double(value & 0xFF) / 255)
    }
}

extension VerseRange {
    /// "Acts 13:1–14:28" shortened within a known book: "13:1–14:28".
    var chapterVerseDisplay: String {
        let full = display
        let prefix = start.book.name + " "
        return full.hasPrefix(prefix) ? String(full.dropFirst(prefix.count)) : full
    }
}

extension VerseRef {
    /// "13:51"
    var chapterVerse: String { "\(chapter):\(verse)" }
}
