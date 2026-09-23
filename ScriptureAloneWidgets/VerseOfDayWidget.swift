import SwiftUI
import WidgetKit
import ScriptureAloneCore

struct VerseOfDayWidget: Widget {
    static let kind = "VerseOfDay"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: Self.kind, provider: VerseOfDayProvider()) { entry in
            VerseOfDayView(entry: entry)
        }
        .configurationDisplayName("Verse of the Day")
        .description("A beloved passage each day, in the translation you read.")
        .supportedFamilies(Self.families)
    }

    static var families: [WidgetFamily] {
        #if os(iOS)
        [.systemSmall, .systemMedium, .systemLarge, .accessoryCircular, .accessoryRectangular, .accessoryInline]
        #else
        [.systemSmall, .systemMedium, .systemLarge]
        #endif
    }
}

struct VerseOfDayView: View {
    @Environment(\.widgetFamily) private var family
    let entry: VerseEntry

    var body: some View {
        Group {
            switch family {
            case .systemSmall, .systemMedium, .systemLarge, .systemExtraLarge:
                HomeVerseCard(entry: entry, family: family)
            default:
                VerseAccessoryView(entry: entry)
                    .containerBackground(.clear, for: .widget)
            }
        }
        .widgetURL(entry.url)
    }
}

/// The verse on a warm page. In tinted and clear (Liquid Glass) home screens WidgetKit drops
/// the background and tints: the reference is the accented element, and red letters give way
/// to the system's monochrome so nothing turns into an unreadable blob.
struct HomeVerseCard: View {
    @Environment(\.colorScheme) private var scheme
    @Environment(\.widgetRenderingMode) private var renderingMode
    /// Mirrored from the reader's setting by the app (WidgetSnapshotSync).
    @AppStorage("reader.redLetters", store: AppGroup.defaults) private var redLetters = true
    let entry: VerseEntry
    let family: WidgetFamily

    private var palette: VerseStyling.Palette { VerseStyling.palette(scheme) }
    private var fullColor: Bool { renderingMode == .fullColor }

    var body: some View {
        VStack(alignment: .leading, spacing: family == .systemSmall ? 6 : 10) {
            header
            Text(VerseStyling.attributed(entry.text, red: entry.verse.redRanges(in: entry.translation),
                                         redColor: fullColor && redLetters ? palette.wordsOfChrist : nil))
                .font(.system(textStyle, design: .serif))
                .foregroundStyle(fullColor ? palette.ink : .primary)
                .lineSpacing(family == .systemLarge ? 4 : 2)
                .minimumScaleFactor(0.55)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            footer
        }
        .containerBackground(for: .widget) {
            LinearGradient(colors: [palette.pageTop, palette.pageBottom], startPoint: .top, endPoint: .bottom)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Verse of the Day, \(entry.reference). \(entry.text)")
    }

    private var textStyle: Font.TextStyle {
        switch family {
        case .systemSmall: .footnote
        case .systemMedium: .callout
        default: .title3
        }
    }

    @ViewBuilder private var header: some View {
        if family == .systemSmall {
            Text(entry.shortReference)
                .font(.system(.subheadline, design: .serif).weight(.semibold))
                .foregroundStyle(fullColor ? palette.accent : .primary)
                .widgetAccentable()
                .lineLimit(1)
        } else {
            HStack(spacing: 6) {
                Image(systemName: "sun.horizon.fill")
                Text("Verse of the Day")
                if family == .systemLarge {
                    Text("·")
                    Text(entry.verse.localizedTheme).lineLimit(1)
                }
            }
            .font(.caption.weight(.semibold))
            .textCase(.uppercase)
            .foregroundStyle(fullColor ? palette.secondaryInk : .secondary)
            .widgetAccentable()
        }
    }

    @ViewBuilder private var footer: some View {
        if family != .systemSmall {
            HStack(alignment: .firstTextBaseline) {
                Text(entry.reference)
                    .font(.system(.subheadline, design: .serif).weight(.semibold))
                    .foregroundStyle(fullColor ? palette.accent : .primary)
                    .widgetAccentable()
                Spacer()
                Text(entry.translation)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(fullColor ? palette.secondaryInk : .secondary)
            }
            .lineLimit(1)
        }
    }
}

#Preview("Small", as: .systemSmall) {
    VerseOfDayWidget()
} timeline: {
    VerseEntry.placeholder
}

#Preview("Large", as: .systemLarge) {
    VerseOfDayWidget()
} timeline: {
    VerseOfDayProvider().entries(from: .now).first ?? .placeholder
}

#if os(iOS)
#Preview("Lock Screen", as: .accessoryRectangular) {
    VerseOfDayWidget()
} timeline: {
    VerseEntry.placeholder
}
#endif
