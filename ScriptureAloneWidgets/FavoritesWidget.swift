import SwiftUI
import WidgetKit
import AppIntents
import ScriptureAloneCore

// MARK: Configuration

nonisolated enum VerseSource: String, AppEnum {
    case everything, favorites, highlights, notes

    static var typeDisplayRepresentation: TypeDisplayRepresentation { "Verses" }

    static var caseDisplayRepresentations: [VerseSource: DisplayRepresentation] {
        [.everything: "Favorites, Highlights & Notes",
         .favorites: "Favorites",
         .highlights: "Highlights",
         .notes: "Notes"]
    }

    var kinds: Set<VerseSnapshot.Item.Kind> {
        switch self {
        case .everything: Set(VerseSnapshot.Item.Kind.allCases)
        case .favorites: [.favorite]
        case .highlights: [.highlight]
        case .notes: [.note]
        }
    }
}

struct FavoritesConfigurationIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource { "Favorites & Notes" }
    static var description: IntentDescription {
        IntentDescription("Rotates through the verses you’ve favorited, highlighted or written notes on.")
    }

    @Parameter(title: "Show", default: .everything)
    var source: VerseSource

    init() {}
    init(source: VerseSource) { self.source = source }
}

/// The widget's "next" button: nudges the rotation forward one verse.
struct ShowNextVerseIntent: AppIntent {
    static var title: LocalizedStringResource { "Show Next Verse" }
    static var isDiscoverable: Bool { false }
    static let nudgeKey = "widget.favorites.nudge"

    func perform() async throws -> some IntentResult {
        let defaults = AppGroup.defaults
        defaults?.set((defaults?.integer(forKey: Self.nudgeKey) ?? 0) + 1, forKey: Self.nudgeKey)
        return .result()
    }
}

// MARK: Timeline

nonisolated struct FavoritesEntry: TimelineEntry {
    let date: Date
    let item: VerseSnapshot.Item?
    let position: Int
    let total: Int
    let source: VerseSource

    var url: URL? { item?.verseRange.map(ScriptureLink.url(for:)) }

    static let sample = FavoritesEntry(
        date: .now,
        item: VerseSnapshot.Item(kind: .favorite, range: VerseRange(VerseRef(.romans, 8, 38), VerseRef(.romans, 8, 39)),
                                 text: "For I am persuaded, that neither death, nor life, nor angels, nor principalities, nor things present, nor things to come, nor powers, nor height, nor depth, nor any other creature, shall be able to separate us from the love of God, which is in Christ Jesus our Lord.",
                                 date: .now),
        position: 0, total: 12, source: .everything)
}

/// A new verse every three hours through the day (and on each tap of "next"); timelines are
/// rebuilt at local midnight and whenever the app rewrites the snapshot.
nonisolated struct FavoritesProvider: AppIntentTimelineProvider {
    static let slotHours = 3

    func placeholder(in context: Context) -> FavoritesEntry { .sample }

    func snapshot(for configuration: FavoritesConfigurationIntent, in context: Context) async -> FavoritesEntry {
        let entry = entries(for: configuration.source, from: .now).first
        if context.isPreview, entry?.item == nil { return .sample }
        return entry ?? .sample
    }

    func timeline(for configuration: FavoritesConfigurationIntent, in context: Context) async -> Timeline<FavoritesEntry> {
        let now = Date.now
        return Timeline(entries: entries(for: configuration.source, from: now),
                        policy: .after(DailyVerseCatalog.nextMidnight(after: now)))
    }

    func entries(for source: VerseSource, from now: Date, calendar: Calendar = .current) -> [FavoritesEntry] {
        let items = AppGroup.readSnapshot()?.items(of: source.kinds) ?? []
        guard !items.isEmpty else {
            return [FavoritesEntry(date: now, item: nil, position: 0, total: 0, source: source)]
        }
        let nudge = AppGroup.defaults?.integer(forKey: ShowNextVerseIntent.nudgeKey) ?? 0
        let midnight = DailyVerseCatalog.nextMidnight(after: now, calendar: calendar)
        var dates = [now]
        var slot = calendar.dateInterval(of: .hour, for: now)?.start ?? now
        while true {
            slot = slot.addingTimeInterval(3600)
            guard slot < midnight else { break }
            if (calendar.component(.hour, from: slot)) % Self.slotHours == 0 { dates.append(slot) }
        }
        return dates.map { date in
            let index = VerseSnapshot.rotationIndex(at: date, count: items.count, slotHours: Self.slotHours,
                                                    nudge: nudge, calendar: calendar)
            return FavoritesEntry(date: date, item: items[index], position: index, total: items.count, source: source)
        }
    }
}

// MARK: Widget

struct FavoritesWidget: Widget {
    static let kind = "FavoritesAndNotes"

    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: Self.kind, intent: FavoritesConfigurationIntent.self, provider: FavoritesProvider()) { entry in
            FavoritesView(entry: entry)
        }
        .configurationDisplayName("Favorites & Notes")
        .description("Your favorited, highlighted and noted verses, one at a time.")
        .supportedFamilies(Self.families)
    }

    static var families: [WidgetFamily] {
        #if os(iOS)
        [.systemSmall, .systemMedium, .systemLarge, .accessoryRectangular, .accessoryInline]
        #else
        [.systemSmall, .systemMedium, .systemLarge]
        #endif
    }
}

struct FavoritesView: View {
    @Environment(\.widgetFamily) private var family
    let entry: FavoritesEntry

    var body: some View {
        Group {
            switch family {
            case .systemSmall, .systemMedium, .systemLarge, .systemExtraLarge:
                FavoritesCard(entry: entry, family: family)
            default:
                if let item = entry.item {
                    VerseAccessoryView(reference: item.reference, shortReference: item.verseRange?.abbreviatedDisplay ?? item.reference,
                                       text: item.text, symbol: FavoritesCard.symbol(for: item.kind))
                        .containerBackground(.clear, for: .widget)
                } else {
                    Label("Favorite a verse", systemImage: "heart")
                        .containerBackground(.clear, for: .widget)
                }
            }
        }
        .widgetURL(entry.url)
    }
}

struct FavoritesCard: View {
    @Environment(\.colorScheme) private var scheme
    @Environment(\.widgetRenderingMode) private var renderingMode
    let entry: FavoritesEntry
    let family: WidgetFamily

    private var palette: VerseStyling.Palette { VerseStyling.palette(scheme) }
    private var fullColor: Bool { renderingMode == .fullColor }

    static func symbol(for kind: VerseSnapshot.Item.Kind) -> String {
        switch kind {
        case .favorite: "heart.fill"
        case .highlight: "highlighter"
        case .note: "note.text"
        }
    }

    var body: some View {
        Group {
            if let item = entry.item {
                content(item)
            } else {
                empty
            }
        }
        .containerBackground(for: .widget) {
            LinearGradient(colors: [palette.pageTop, palette.pageBottom], startPoint: .top, endPoint: .bottom)
        }
    }

    private func content(_ item: VerseSnapshot.Item) -> some View {
        VStack(alignment: .leading, spacing: family == .systemSmall ? 6 : 8) {
            HStack(spacing: 6) {
                badge(item)
                Text(family == .systemSmall ? (item.verseRange?.abbreviatedDisplay ?? item.reference) : item.reference)
                    .font(.system(.subheadline, design: .serif).weight(.semibold))
                    .foregroundStyle(fullColor ? palette.accent : .primary)
                    .widgetAccentable()
                    .lineLimit(1)
                Spacer(minLength: 0)
            }
            if item.kind == .note, let title = item.noteTitle, family != .systemSmall {
                Text(title)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(fullColor ? palette.secondaryInk : .secondary)
                    .lineLimit(1)
            }
            Text(item.text)
                .font(.system(family == .systemLarge ? .title3 : family == .systemMedium ? .callout : .footnote, design: .serif))
                .foregroundStyle(fullColor ? palette.ink : .primary)
                .minimumScaleFactor(0.6)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            if family != .systemSmall {
                HStack {
                    Text("\(entry.position + 1) of \(entry.total)")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(fullColor ? palette.secondaryInk : .secondary)
                        .monospacedDigit()
                    Spacer()
                    if entry.total > 1 {
                        // Interactive: runs in the extension, then WidgetKit reloads the timeline.
                        Button(intent: ShowNextVerseIntent()) {
                            Label("Next", systemImage: "arrow.forward")
                                .font(.caption.weight(.semibold))
                        }
                        .buttonStyle(.bordered)
                        .buttonBorderShape(.capsule)
                        .tint(fullColor ? palette.accent : nil)
                        .accessibilityLabel("Show next verse")
                    }
                }
            }
        }
        .accessibilityElement(children: .contain)
    }

    @ViewBuilder private func badge(_ item: VerseSnapshot.Item) -> some View {
        switch item.kind {
        case .highlight:
            Circle()
                .fill(fullColor ? VerseStyling.highlight(item.color) : Color.primary)
                .frame(width: 10, height: 10)
                .accessibilityLabel("Highlight")
        case .favorite:
            Image(systemName: "heart.fill")
                .font(.caption)
                .foregroundStyle(fullColor ? AnyShapeStyle(Color.red) : AnyShapeStyle(.primary))
                .accessibilityLabel("Favorite")
        case .note:
            Image(systemName: "note.text")
                .font(.caption)
                .foregroundStyle(fullColor ? palette.accent : .primary)
                .accessibilityLabel("Note")
        }
    }

    private var empty: some View {
        VStack(alignment: .leading, spacing: 6) {
            Image(systemName: "heart")
                .font(.title2)
                .foregroundStyle(fullColor ? palette.accent : .primary)
                .widgetAccentable()
            Text(emptyMessage)
                .font(.system(.footnote, design: .serif))
                .foregroundStyle(fullColor ? palette.ink : .primary)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
    }

    private var emptyMessage: LocalizedStringKey {
        switch entry.source {
        case .highlights: "Highlight a verse in Scripture Alone and it will appear here."
        case .notes: "Write a note on a passage in Scripture Alone and it will appear here."
        default: "Favorite a verse in Scripture Alone — tap verses, then the heart — and it will appear here."
        }
    }
}

#Preview("Medium", as: .systemMedium) {
    FavoritesWidget()
} timeline: {
    FavoritesEntry.sample
    FavoritesEntry(date: .now, item: nil, position: 0, total: 0, source: .favorites)
}
