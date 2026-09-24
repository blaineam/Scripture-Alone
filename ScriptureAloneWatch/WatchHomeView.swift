import SwiftUI
import SwiftData
import ScriptureAloneCore

struct WatchHomeView: View {
    @Environment(WatchBible.self) private var bible
    @Query private var favorites: [Favorite]
    @Query private var notes: [Note]
    @Query private var highlights: [Highlight]

    var body: some View {
        List {
            if let verse = DailyVerseLibrary.verse(), let range = verse.range {
                Section {
                    NavigationLink(value: WatchRoute.verse(range)) {
                        VerseOfDayCard(verse: verse, range: range)
                    }
                }
            }
            Section {
                NavigationLink(value: WatchRoute.favorites) {
                    row("Favorites", systemImage: "heart.fill", tint: AnyShapeStyle(.red), count: favorites.count)
                }
                NavigationLink(value: WatchRoute.notes) {
                    row("Notes", systemImage: "note.text", tint: AnyShapeStyle(.orange), count: notes.count)
                }
                NavigationLink(value: WatchRoute.highlights) {
                    row("Highlights", systemImage: "highlighter", tint: AnyShapeStyle(.yellow),
                        count: Set(highlights.map(\.verseKey)).count)
                }
                NavigationLink(value: WatchRoute.books) {
                    row("Read", systemImage: "book.fill", tint: AnyShapeStyle(.tint), count: nil)
                }
                NavigationLink(value: WatchRoute.translations) {
                    HStack {
                        Label("Translation", systemImage: "character.book.closed.fill")
                        Spacer()
                        Text(bible.translationAbbreviation).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .navigationTitle("Scripture Alone")
    }

    private func row(_ title: LocalizedStringKey, systemImage: String, tint: AnyShapeStyle, count: Int?) -> some View {
        HStack {
            Label {
                Text(title)
            } icon: {
                Image(systemName: systemImage).foregroundStyle(tint)
            }
            Spacer()
            if let count, count > 0 {
                Text(count, format: .number)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
        }
    }
}

private struct VerseOfDayCard: View {
    @Environment(WatchBible.self) private var bible
    let verse: DailyVerse
    let range: VerseRange

    /// In the translation the watch is reading: the daily list carries the bundled Bibles; any
    /// other (an import the phone sent) reads from its own edition, the list's ASV only failing that.
    private var text: String {
        if verse.text[bible.translation] != nil { return verse.text(in: bible.translation) }
        let own = bible.text(range).replacingOccurrences(of: "¶ ", with: "")
        return own.isEmpty ? verse.text(in: bible.translation) : own
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label("Verse of the Day", systemImage: "sun.horizon.fill")
                .font(.caption2.weight(.semibold))
                .textCase(.uppercase)
                .foregroundStyle(.secondary)
            Text(range.display)
                .font(.headline)
                .foregroundStyle(.tint)
            Text(text)
                .font(.body)
                .lineLimit(4)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
        .accessibilityHint("Opens the verse")
    }
}
