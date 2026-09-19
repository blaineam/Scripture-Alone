import SwiftUI
import SwiftData
import ScriptureAloneCore

/// Favorites synced from the phone (and any made on the watch), newest first.
struct WatchFavoritesView: View {
    @Environment(WatchBible.self) private var bible
    @Environment(\.modelContext) private var context
    @Query(sort: \Favorite.createdAt, order: .reverse) private var favorites: [Favorite]

    var body: some View {
        List {
            ForEach(favorites) { favorite in
                if let range = favorite.range {
                    NavigationLink(value: WatchRoute.verse(range)) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(range.display).font(.headline).foregroundStyle(.tint)
                            Text(bible.text(range)).font(.footnote).lineLimit(3)
                        }
                        .accessibilityElement(children: .combine)
                    }
                }
            }
            .onDelete { offsets in
                for index in offsets { context.delete(favorites[index]) }
            }
        }
        .overlay {
            if favorites.isEmpty {
                ContentUnavailableView("No Favorites",
                                       systemImage: "heart",
                                       description: Text("Favorite a verse here or on your iPhone, iPad or Mac. It syncs through iCloud."))
            }
        }
        .navigationTitle("Favorites")
    }
}

/// Every note, newest first. Notes are read-only on the watch.
struct WatchNotesView: View {
    @Query(sort: \Note.updatedAt, order: .reverse) private var notes: [Note]

    var body: some View {
        List(notes) { note in
            NavigationLink(value: WatchRoute.note(note.uuid)) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(note.displayTitle).font(.headline).lineLimit(2)
                    if !note.anchors.isEmpty {
                        Text(note.anchorSummary).font(.footnote).foregroundStyle(.tint).lineLimit(1)
                    }
                    Text(note.updatedAt, format: .dateTime.month(.abbreviated).day())
                        .font(.footnote).foregroundStyle(.secondary)
                }
                .accessibilityElement(children: .combine)
            }
        }
        .overlay {
            if notes.isEmpty {
                ContentUnavailableView("No Notes",
                                       systemImage: "note.text",
                                       description: Text("Notes you write on your iPhone, iPad or Mac appear here."))
            }
        }
        .navigationTitle("Notes")
    }
}

struct WatchNoteView: View {
    @Query private var notes: [Note]
    let id: UUID

    init(id: UUID) {
        self.id = id
        _notes = Query(filter: #Predicate<Note> { $0.uuid == id })
    }

    var body: some View {
        if let note = notes.first {
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    Text(note.displayTitle)
                        .font(.headline)
                        .accessibilityAddTraits(.isHeader)
                    ForEach(note.anchors, id: \.self) { anchor in
                        NavigationLink(value: WatchRoute.verse(anchor)) {
                            Label(anchor.display, systemImage: "book")
                                .font(.footnote.weight(.semibold))
                        }
                    }
                    if !note.body.isEmpty {
                        Text(note.body)
                            .font(.body)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Text("Edit notes on your iPhone, iPad or Mac.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .navigationTitle("Note")
        } else {
            ContentUnavailableView("Note Deleted", systemImage: "trash")
        }
    }
}
