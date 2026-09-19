import SwiftUI
import WidgetKit
import ScriptureAloneCore

/// Watch face complications: the Verse of the Day reference ("Ps 23:1") in every accessory
/// family, with the opening words on the rectangular one. Tapping opens the watch app at the
/// passage.
///
/// No raster images, deliberately: accessory families reject images over a per-family size
/// cap (corner ≈ 82 pt, circular ≈ 122 pt) with a blank render. SF Symbols and text scale to
/// any family and tint cleanly — the reference is marked `widgetAccentable()` so tinted faces
/// color it and leave the rest in the face's secondary tone.
@main
struct ScriptureAloneComplications: WidgetBundle {
    var body: some Widget {
        VerseOfDayComplication()
    }
}

struct VerseOfDayComplication: Widget {
    static let kind = "VerseOfDayComplication"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: Self.kind, provider: VerseOfDayProvider()) { entry in
            VerseAccessoryView(entry: entry)
                .containerBackground(.clear, for: .widget)
                .widgetURL(entry.url)
        }
        .configurationDisplayName("Verse of the Day")
        .description("Today’s passage on your watch face.")
        .supportedFamilies(Self.families)
    }

    static var families: [WidgetFamily] {
        #if os(watchOS)
        [.accessoryCircular, .accessoryRectangular, .accessoryInline, .accessoryCorner]
        #else
        []
        #endif
    }
}

#if os(watchOS)
#Preview("Circular", as: .accessoryCircular) {
    VerseOfDayComplication()
} timeline: {
    VerseEntry.placeholder
}

#Preview("Rectangular", as: .accessoryRectangular) {
    VerseOfDayComplication()
} timeline: {
    VerseOfDayProvider().entries(from: .now).first ?? .placeholder
}

#Preview("Corner", as: .accessoryCorner) {
    VerseOfDayComplication()
} timeline: {
    VerseEntry.placeholder
}

#Preview("Inline", as: .accessoryInline) {
    VerseOfDayComplication()
} timeline: {
    VerseEntry.placeholder
}
#endif
