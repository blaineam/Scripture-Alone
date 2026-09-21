import SwiftUI
import SwiftData
import ScriptureAloneCore

struct WatchHomeView: View {
    @Environment(WatchBible.self) private var bible
    @Query private var favorites: [Favorite]
    @Query private var notes: [Note]

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
                    row("Favorites", systemImage: "heart.fill", tint: .red, count: favorites.count)
                }
                NavigationLink(value: WatchRoute.notes) {
                    row("Notes", systemImage: "note.text", tint: .orange, count: notes.count)
                }
                NavigationLink(value: WatchRoute.books) {
                    row("Read", systemImage: "book.fill", tint: .accentColor, count: nil)
                }
                NavigationLink(value: WatchRoute.translations) {
                    HStack {
                        Label("Translation", systemImage: "character.book.closed.fill")
                        Spacer()
                        Text(bible.translation).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .navigationTitle("Scripture Alone")
    }

    private func row(_ title: LocalizedStringKey, systemImage: String, tint: Color, count: Int?) -> some View {
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

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label("Verse of the Day", systemImage: "sun.horizon.fill")
                .font(.caption2.weight(.semibold))
                .textCase(.uppercase)
                .foregroundStyle(.secondary)
            Text(range.display)
                .font(.headline)
                .foregroundStyle(.tint)
            Text(verse.text(in: bible.translation))
                .font(.body)
                .lineLimit(4)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
        .accessibilityHint("Opens the verse")
    }
}
