import SwiftUI
import SwiftData
import ScriptureAloneCore

/// One passage, large and legible, with the notes on it (read-only), a favorite toggle and
/// Speak. The Digital Crown scrolls it.
struct WatchVerseView: View {
    @Environment(WatchBible.self) private var bible
    @Environment(\.modelContext) private var context
    @Query(sort: \Note.updatedAt, order: .reverse) private var allNotes: [Note]
    @Query private var favorites: [Favorite]
    @State private var speaker = VerseSpeaker()
    let range: VerseRange

    private var notes: [Note] { allNotes.filter { $0.anchors.contains { $0.intersects(range) } } }
    private var favorite: Favorite? { favorites.first { $0.rangeRaw == range.storageString } }

    var body: some View {
        let verses = bible.verses(range)
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Text(range.display)
                    .font(.headline)
                    .foregroundStyle(.tint)
                    .accessibilityAddTraits(.isHeader)
                if verses.isEmpty {
                    Text("This passage isn’t in the watch’s ASV.")
                        .foregroundStyle(.secondary)
                }
                ForEach(verses, id: \.ref) { verse in
                    WatchVerseText(verse: verse, numbered: verses.count > 1)
                }
                HStack {
                    Button {
                        speaker.toggle(verses.map(\.text).joined(separator: " "))
                    } label: {
                        Label(speaker.isSpeaking ? "Stop" : "Speak",
                              systemImage: speaker.isSpeaking ? "stop.fill" : "speaker.wave.2.fill")
                    }
                    .disabled(verses.isEmpty)
                    Button(action: toggleFavorite) {
                        Image(systemName: favorite == nil ? "heart" : "heart.fill")
                            .foregroundStyle(favorite == nil ? AnyShapeStyle(.primary) : AnyShapeStyle(.red))
                    }
                    .accessibilityLabel(favorite == nil ? "Add to Favorites" : "Remove from Favorites")
                    .frame(maxWidth: 56)
                }
                if !notes.isEmpty {
                    Text("Notes")
                        .font(.headline)
                        .padding(.top, 6)
                        .accessibilityAddTraits(.isHeader)
                    ForEach(notes) { note in
                        NavigationLink(value: WatchRoute.note(note.uuid)) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(note.displayTitle).font(.body.weight(.semibold)).lineLimit(2)
                                if !note.body.isEmpty {
                                    Text(note.body).font(.footnote).foregroundStyle(.secondary).lineLimit(3)
                                }
                            }
                        }
                    }
                }
                NavigationLink(value: WatchRoute.chapter(range.start.chapterKey, focus: range.start.verse)) {
                    Label("Read \(range.start.chapterKey.display)", systemImage: "book")
                }
                Text("American Standard Version")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .navigationTitle(range.start.book.abbreviation)
        .onDisappear { speaker.stop() }
    }

    private func toggleFavorite() {
        if let favorite {
            context.delete(favorite)
        } else {
            context.insert(Favorite(range: range))
        }
    }
}

/// A verse with the words of Christ in red and an optional small verse number.
struct WatchVerseText: View {
    let verse: VerseText
    var numbered = true
    var highlight: Color?

    var body: some View {
        let red = verse.red.compactMap { Range($0, in: verse.text) }
            .map { verse.text.unicodeScalars.distance(from: verse.text.unicodeScalars.startIndex, to: $0.lowerBound)
                ..< verse.text.unicodeScalars.distance(from: verse.text.unicodeScalars.startIndex, to: $0.upperBound) }
        let body = Text(VerseStyling.attributed(verse.text, red: red, redColor: Color(hex: 0xFF7A6B)))
        Group {
            if numbered {
                Text("\(Text("\(verse.ref.verse)").font(.footnote.weight(.bold)).foregroundStyle(.secondary)) \(body)")
            } else {
                body
            }
        }
        .font(.body)
        .fixedSize(horizontal: false, vertical: true)
        .padding(.horizontal, highlight == nil ? 0 : 4)
        .background(highlight.map { $0.opacity(0.28) } ?? .clear, in: .rect(cornerRadius: 4))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(numbered ? "Verse \(verse.ref.verse). \(verse.text)" : verse.text)
    }
}
