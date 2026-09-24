import SwiftUI
import WidgetKit
import ScriptureAloneCore

// Shared by the iOS/macOS widget extension and the watchOS complication extension. This file
// compiles for every platform, so anything watch-only (accessoryCorner, widgetLabel,
// widgetCurvesContent) stays behind `#if os(watchOS)` — the 27 SDK hard-errors otherwise.

nonisolated struct VerseEntry: TimelineEntry {
    let date: Date
    let verse: DailyVerse
    let translation: String
    /// The translation's label ("CSB"); its id can be an import's file name.
    var label: String = ""
    /// Today's passage in a translation the daily list doesn't carry, from the app (`VerseSnapshot.daily`).
    var own: VerseSnapshot.DailyText?

    var range: VerseRange? { verse.range }
    var reference: String { range?.display ?? "" }
    var shortReference: String { range?.abbreviatedDisplay ?? "" }
    var text: String { own?.text ?? verse.text(in: translation) }
    var redRanges: [Range<Int>] { own?.redRanges ?? verse.redRanges(in: translation) }
    /// The label shown: the reader's translation when the text is in it, else the one it fell back to.
    var shownTranslation: String {
        if own != nil || verse.text[translation] != nil { return label.isEmpty ? translation : label }
        return DailyVerseCatalog.fallbackTranslation
    }
    var url: URL? { range.map(ScriptureLink.url(for:)) }

    static var placeholder: VerseEntry {
        VerseEntry(date: .now, verse: DailyVerseLibrary.placeholder, translation: DailyVerseCatalog.fallbackTranslation)
    }
}

/// Today's passage, then one entry at each of the next local midnights. The pick is a pure
/// function of the date, so a week of entries is exact; the app still reloads timelines when
/// the reader switches translation.
nonisolated struct VerseOfDayProvider: TimelineProvider {
    static let days = 7

    /// What the app last wrote to the App Group: the phone's snapshot on iPhone, iPad and Mac, the
    /// watch app's on the watch (`WatchBible.publishVerseOfDay`) — each device its own translation.
    private var snapshot: VerseSnapshot? { AppGroup.readSnapshot() }

    func entries(from now: Date, calendar: Calendar = .current) -> [VerseEntry] {
        let snapshot = snapshot
        let translation = snapshot?.translation ?? DailyVerseCatalog.fallbackTranslation
        var entries: [VerseEntry] = []
        var date = now
        for _ in 0..<Self.days {
            let verse = DailyVerseLibrary.verse(on: date, calendar: calendar) ?? DailyVerseLibrary.placeholder
            entries.append(VerseEntry(date: date, verse: verse, translation: translation,
                                      label: snapshot?.abbreviation ?? translation, own: snapshot?.daily?[verse.ref]))
            date = DailyVerseCatalog.nextMidnight(after: date, calendar: calendar)
        }
        return entries
    }

    func placeholder(in context: Context) -> VerseEntry { .placeholder }

    func getSnapshot(in context: Context, completion: @escaping @Sendable (VerseEntry) -> Void) {
        completion(entries(from: .now).first ?? .placeholder)
    }

    func getTimeline(in context: Context, completion: @escaping @Sendable (Timeline<VerseEntry>) -> Void) {
        completion(Timeline(entries: entries(from: .now), policy: .atEnd))
    }
}

/// Lock Screen accessories (iOS) and complications (watchOS).
struct VerseAccessoryView: View {
    @Environment(\.widgetFamily) private var family
    let reference: String
    let shortReference: String
    let text: String
    var symbol = "book.closed.fill"

    var body: some View {
        switch family {
        #if os(iOS) || os(watchOS)
        case .accessoryCircular:
            circular
        case .accessoryInline:
            Label {
                Text("\(shortReference) · \(VerseStyling.openingWords(text, maxWords: 4))")
            } icon: {
                Image(systemName: symbol)
            }
        #endif
        #if os(watchOS)
        case .accessoryCorner:
            Image(systemName: symbol)
                .font(.title3.weight(.semibold))
                .widgetAccentable()
                .widgetLabel { Text(shortReference) }
                .accessibilityLabel(reference)
        #endif
        default:
            rectangular
        }
    }

    #if os(iOS) || os(watchOS)
    /// "Ps" over "23:1".
    private var circular: some View {
        let parts = Self.split(shortReference)
        return ZStack {
            AccessoryWidgetBackground()
            VStack(spacing: -1) {
                Text(parts.book)
                    .font(.system(size: 12, weight: .semibold, design: .serif))
                    .widgetAccentable()
                Text(parts.verses)
                    .font(.system(size: 15, weight: .bold, design: .rounded))
                    .monospacedDigit()
            }
            .lineLimit(1)
            .minimumScaleFactor(0.5)
            .padding(4)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(reference)
    }
    #endif

    private var rectangular: some View {
        VStack(alignment: .leading, spacing: 1) {
            Label(shortReference, systemImage: symbol)
                .font(.headline)
                .widgetAccentable()
                .lineLimit(1)
            Text(text)
                .font(.system(.caption, design: .serif))
                .lineLimit(2)
                .minimumScaleFactor(0.8)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(reference). \(text)")
    }

    /// "1 Cor 13:4–7" → ("1 Cor", "13:4–7").
    nonisolated static func split(_ reference: String) -> (book: String, verses: String) {
        guard let space = reference.lastIndex(of: " ") else { return (reference, "") }
        return (String(reference[..<space]), String(reference[reference.index(after: space)...]))
    }
}

extension VerseAccessoryView {
    init(entry: VerseEntry) {
        self.init(reference: entry.reference, shortReference: entry.shortReference, text: entry.text)
    }
}
