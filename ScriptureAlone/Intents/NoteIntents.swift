import AppIntents
import Foundation
import ScriptureAloneCore
import SwiftUI

// MARK: Notes

struct CreateNoteIntent: AppIntent {
    static var title: LocalizedStringResource {
        LocalizedStringResource("Create Note", comment: "App Intent title")
    }
    static var description: IntentDescription {
        IntentDescription(LocalizedStringResource("Adds a note, attached to a passage if you name one. It syncs to your other devices like any note.",
                                                  comment: "App Intent description"))
    }
    static var parameterSummary: some ParameterSummary {
        Summary("Create a note on \(\.$passage)") {
            \.$noteTitle
            \.$text
        }
    }

    @Parameter(title: LocalizedStringResource("Title", comment: "App Intent parameter: a note's title"))
    var noteTitle: String?

    @Parameter(title: LocalizedStringResource("Text", comment: "App Intent parameter: a note's text"),
               inputOptions: String.IntentInputOptions(multiline: true),
               requestValueDialog: IntentDialog(LocalizedStringResource("What should the note say?", comment: "Siri asks for a note's text")))
    var text: String

    @Parameter(title: LocalizedStringResource("Passage", comment: "App Intent parameter: a Bible reference like John 3:16"))
    var passage: String?

    init() {}

    @MainActor
    func perform() async throws -> some ReturnsValue<NoteEntity> & ProvidesDialog {
        var anchors: [VerseRange] = []
        if let passage, !passage.trimmingCharacters(in: .whitespaces).isEmpty {
            // Typed in the reader's own numbering; stored as KJV keys, like every note.
            guard let source = IntentLibrary.currentSource else { throw ScriptureIntentError.passageNotFound(passage) }
            anchors = try IntentLibrary.resolve(passage, in: source).map(\.kjv)
        }
        let note = Note(title: noteTitle?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "", body: text, anchors: anchors)
        let context = IntentLibrary.context
        context.insert(note)
        try context.save()
        let entity = NoteEntity(note)
        return .result(value: entity,
                       dialog: IntentDialog(LocalizedStringResource("Saved “\(entity.title)”.",
                                                                    comment: "Siri confirms a new note. %@ is its title.")))
    }
}

struct FindNotesIntent: AppIntent {
    static var title: LocalizedStringResource {
        LocalizedStringResource("Find Notes", comment: "App Intent title")
    }
    static var description: IntentDescription {
        IntentDescription(LocalizedStringResource("Finds notes by words or by a passage, like the search in the Notes panel.",
                                                  comment: "App Intent description"))
    }
    static var parameterSummary: some ParameterSummary { Summary("Find notes matching \(\.$query)") }

    @Parameter(title: LocalizedStringResource("Search For", comment: "App Intent parameter: words or a passage to find notes by"))
    var query: String?

    init() {}

    /// Siri shows the notes it found, not just how many: the first few, each opening in the app.
    @MainActor
    func perform() async throws -> some ReturnsValue<[NoteEntity]> & ProvidesDialog & ShowsSnippetView {
        let found = NotesSearch.matching(query ?? "").map { NoteEntity($0) }
        return .result(value: found,
                       dialog: IntentDialog(LocalizedStringResource("Found \(found.count) notes.",
                                                                    comment: "Siri reports how many notes matched. %lld is the count.")),
                       view: NotesSnippet(notes: Array(found.prefix(5)), more: max(0, found.count - 5)))
    }
}

/// The notes a Find Notes request turned up, as Siri and Shortcuts show them.
struct NotesSnippet: View {
    let notes: [NoteEntity]
    let more: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(notes, id: \.id) { note in
                Button(intent: OpenNoteIntent(target: note)) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(note.title).font(.headline).lineLimit(1)
                        if !note.passages.isEmpty {
                            Text(note.passages).font(.footnote.weight(.semibold)).foregroundStyle(.tint).lineLimit(1)
                        }
                        if !note.excerpt.isEmpty {
                            Text(note.excerpt).font(.footnote).foregroundStyle(.secondary).lineLimit(2)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(.plain)
            }
            if more > 0 {
                Text("And \(more) more in the Notes panel.",
                     comment: "Under the notes Siri shows: how many more matched. %lld is the count.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .padding()
    }
}

/// Opens a note in the reader — what a Spotlight result for a note does.
struct OpenNoteIntent: OpenIntent {
    static var title: LocalizedStringResource {
        LocalizedStringResource("Open Note", comment: "App Intent title")
    }
    static var description: IntentDescription {
        IntentDescription(LocalizedStringResource("Opens a note in Scripture Alone.", comment: "App Intent description"))
    }

    @Parameter(title: LocalizedStringResource("Note", comment: "App Intent parameter: a note"))
    var target: NoteEntity

    init() {}

    init(target: NoteEntity) {
        self.target = target
    }

    @MainActor
    func perform() async throws -> some IntentResult {
        AppCommandCenter.shared.post(.link(.note(target.id)))
        return .result()
    }
}

/// Opens the app and starts a note — on a passage, or on the chapter being read.
struct NewNoteIntent: AppIntent {
    static var title: LocalizedStringResource {
        LocalizedStringResource("New Note", comment: "App Intent title")
    }
    static var description: IntentDescription {
        IntentDescription(LocalizedStringResource("Opens Scripture Alone to a new note on a passage, or on the chapter you’re reading.",
                                                  comment: "App Intent description"))
    }
    static var supportedModes: IntentModes { .foreground(.immediate) }
    static var parameterSummary: some ParameterSummary { Summary("New note on \(\.$passage)") }

    @Parameter(title: LocalizedStringResource("Passage", comment: "App Intent parameter: a Bible reference like John 3:16"))
    var passage: String?

    init() {}

    @MainActor
    func perform() async throws -> some IntentResult {
        var anchors: [VerseRange] = []
        if let passage, !passage.trimmingCharacters(in: .whitespaces).isEmpty, let source = IntentLibrary.currentSource {
            anchors = try IntentLibrary.resolve(passage, in: source).map(\.kjv)
        }
        AppCommandCenter.shared.post(.newNote(anchors))
        return .result()
    }
}

// MARK: Favorites

/// A passage as the favorite rows it would be: one per range, like the heart in the selection bar.
@MainActor
private func favoriteRanges(_ passage: String) throws -> [VerseRange] {
    guard let source = IntentLibrary.currentSource else { throw ScriptureIntentError.passageNotFound(passage) }
    return try IntentLibrary.resolve(passage, in: source).map(\.kjv)
}

struct AddToFavoritesIntent: AppIntent {
    static var title: LocalizedStringResource {
        LocalizedStringResource("Add to Favorites", comment: "App Intent title")
    }
    static var description: IntentDescription {
        IntentDescription(LocalizedStringResource("Favorites a passage, for your widgets, your watch and the Favorites list.",
                                                  comment: "App Intent description"))
    }
    static var parameterSummary: some ParameterSummary { Summary("Add \(\.$passage) to favorites") }

    @Parameter(title: LocalizedStringResource("Passage", comment: "App Intent parameter: a Bible reference like John 3:16"),
               requestValueDialog: IntentDialog(LocalizedStringResource("Which passage?", comment: "Siri asks for a Bible reference")))
    var passage: String

    init() {}

    @MainActor
    func perform() async throws -> some ReturnsValue<[FavoriteVerseEntity]> & ProvidesDialog {
        let ranges = try favoriteRanges(passage)
        let context = IntentLibrary.context
        let stored = Set(IntentLibrary.favorites().map(\.rangeRaw))
        for range in ranges where !stored.contains(range.storageString) { context.insert(Favorite(range: range)) }
        try context.save()
        let reference = ranges.map(IntentLibrary.display).joined(separator: ", ")
        let source = IntentLibrary.currentSource
        return .result(value: ranges.compactMap { FavoriteVerseEntity(range: $0, source: source) },
                       dialog: IntentDialog(LocalizedStringResource("Added \(reference) to your favorites.",
                                                                    comment: "Siri confirms. %@ is a passage reference.")))
    }
}

struct RemoveFromFavoritesIntent: AppIntent {
    static var title: LocalizedStringResource {
        LocalizedStringResource("Remove from Favorites", comment: "App Intent title")
    }
    static var description: IntentDescription {
        IntentDescription(LocalizedStringResource("Removes a passage from your favorites.", comment: "App Intent description"))
    }
    static var parameterSummary: some ParameterSummary { Summary("Remove \(\.$passage) from favorites") }

    @Parameter(title: LocalizedStringResource("Passage", comment: "App Intent parameter: a Bible reference like John 3:16"),
               requestValueDialog: IntentDialog(LocalizedStringResource("Which passage?", comment: "Siri asks for a Bible reference")))
    var passage: String

    init() {}

    @MainActor
    func perform() async throws -> some ReturnsValue<Bool> & ProvidesDialog {
        let ranges = try favoriteRanges(passage)
        let raws = Set(ranges.map(\.storageString))
        let context = IntentLibrary.context
        let matching = IntentLibrary.favorites().filter { raws.contains($0.rangeRaw) }
        for favorite in matching { context.delete(favorite) }
        try context.save()
        let reference = ranges.map(IntentLibrary.display).joined(separator: ", ")
        let dialog = matching.isEmpty
            ? LocalizedStringResource("\(reference) wasn’t one of your favorites.", comment: "Siri reply. %@ is a passage reference.")
            : LocalizedStringResource("Removed \(reference) from your favorites.", comment: "Siri confirms. %@ is a passage reference.")
        return .result(value: !matching.isEmpty, dialog: IntentDialog(dialog))
    }
}

struct IsFavoriteIntent: AppIntent {
    static var title: LocalizedStringResource {
        LocalizedStringResource("Is Favorite", comment: "App Intent title: checks whether a passage is a favorite")
    }
    static var description: IntentDescription {
        IntentDescription(LocalizedStringResource("Checks whether a passage is one of your favorites.", comment: "App Intent description"))
    }
    static var parameterSummary: some ParameterSummary { Summary("Is \(\.$passage) a favorite") }

    @Parameter(title: LocalizedStringResource("Passage", comment: "App Intent parameter: a Bible reference like John 3:16"),
               requestValueDialog: IntentDialog(LocalizedStringResource("Which passage?", comment: "Siri asks for a Bible reference")))
    var passage: String

    init() {}

    @MainActor
    func perform() async throws -> some ReturnsValue<Bool> & ProvidesDialog {
        let ranges = try favoriteRanges(passage)
        // The heart's rule: a favorite when every range is one.
        let stored = Set(IntentLibrary.favorites().map(\.rangeRaw))
        let isFavorite = !ranges.isEmpty && ranges.allSatisfy { stored.contains($0.storageString) }
        let reference = ranges.map(IntentLibrary.display).joined(separator: ", ")
        let dialog = isFavorite
            ? LocalizedStringResource("Yes, \(reference) is one of your favorites.", comment: "Siri reply. %@ is a passage reference.")
            : LocalizedStringResource("No, \(reference) isn’t one of your favorites.", comment: "Siri reply. %@ is a passage reference.")
        return .result(value: isFavorite, dialog: IntentDialog(dialog))
    }
}

/// Opens a favorite in the reader, selected — what a Spotlight result for a favorite does.
struct OpenFavoriteIntent: OpenIntent {
    static var title: LocalizedStringResource {
        LocalizedStringResource("Open Favorite", comment: "App Intent title")
    }
    static var description: IntentDescription {
        IntentDescription(LocalizedStringResource("Opens a favorite verse in Scripture Alone.", comment: "App Intent description"))
    }

    @Parameter(title: LocalizedStringResource("Favorite", comment: "App Intent parameter: a favorite verse"))
    var target: FavoriteVerseEntity

    init() {}

    @MainActor
    func perform() async throws -> some IntentResult {
        if let range = target.range { AppCommandCenter.shared.post(.link(.open([range]))) }
        return .result()
    }
}
