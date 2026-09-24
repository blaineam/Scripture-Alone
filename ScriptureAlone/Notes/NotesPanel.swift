import SwiftUI
import SwiftData
import ScriptureAloneCore

/// Every note, highlight and favorite, searchable, and narrowed to the book or chapter on screen.
/// Sits beside the text on iPad and Mac, and as a sheet on iPhone.
struct NotesPanel: View {
    @Environment(ReaderModel.self) private var model
    @Environment(\.modelContext) private var context
    @Binding var path: [UUID]
    @Query(sort: \Note.updatedAt, order: .reverse) private var notes: [Note]
    @State private var search = ""
    @State private var scope = Scope.notes
    @State private var place = Place.all
    @State private var slideCapture = SlideCapture()
    @State private var exportSelection: ExportSelection?
    @State private var showLegacy = false

    /// What the panel lists.
    enum Scope: String, CaseIterable, Identifiable {
        case notes, highlights, favorites
        var id: String { rawValue }

        var title: String {
            switch self {
            case .notes: String(localized: "Notes", comment: "Notes panel: list the reader's notes")
            case .highlights: String(localized: "Highlights", comment: "Notes panel: list the reader's highlighted verses")
            case .favorites: String(localized: "Favorites", comment: "Notes list filter")
            }
        }
    }

    /// Where in the Bible: everywhere, the book on screen, or its chapter.
    enum Place: String, CaseIterable, Identifiable {
        case all, book, chapter
        var id: String { rawValue }

        var title: String {
            switch self {
            case .all: String(localized: "All Books", comment: "Notes panel filter: notes, highlights or favorites anywhere in the Bible")
            case .book: String(localized: "This Book", comment: "Notes panel filter: only the book on screen")
            case .chapter: String(localized: "This Chapter", comment: "Notes list filter")
            }
        }

        /// Whether a passage (KJV keys, as marks are stored) falls in this place, for the chapter on screen.
        func contains(_ range: VerseRange, location: ChapterRef) -> Bool {
            switch self {
            case .all: true
            case .book: range.start.book == location.book || range.end.book == location.book
            case .chapter: range.overlaps(location)
            }
        }
    }

    private var searchPrompt: LocalizedStringKey {
        switch scope {
        case .notes: "Search notes or a passage"
        case .highlights: "Search highlights or a passage"
        case .favorites: "Search favorites or a passage"
        }
    }

    private var filtered: [Note] {
        notes.filter { note in
            (place == .all || note.anchors.contains { place.contains($0, location: model.location) }) && matches(note)
        }
    }

    private func matches(_ note: Note) -> Bool {
        let term = search.trimmingCharacters(in: .whitespaces)
        guard !term.isEmpty else { return true }
        if let passage = ReferenceParser.parse(term), term.rangeOfCharacter(from: .decimalDigits) != nil,
           let store = model.source {
            // Typed in the reader's own numbering; anchors are stored as KJV keys.
            let range = store.numbering.kjvRange(passage.range { store.verseCount($0) })
            return note.anchors.contains { $0.start <= range.end && range.start <= $0.end }
        }
        return [note.title, note.body, note.anchorSummary].contains { $0.localizedStandardContains(term) }
    }

    var body: some View {
        NavigationStack(path: $path) {
            List {
                VStack(spacing: 8) {
                    Picker("Show", selection: $scope) {
                        ForEach(Scope.allCases) { Text($0.title).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    Picker("Where", selection: $place) {
                        ForEach(Place.allCases) { Text($0.title).tag($0) }
                    }
                    .pickerStyle(.segmented)
                }
                .listRowSeparator(.hidden)

                if scope == .favorites {
                    FavoritesSection(search: search, place: place)
                } else if scope == .highlights {
                    HighlightsSection(search: search, place: place)
                } else {
                    ForEach(filtered) { note in
                        NavigationLink(value: note.uuid) { NoteRow(note: note) }
                    }
                    .onDelete { offsets in
                        for index in offsets { context.delete(filtered[index]) }
                    }
                }
            }
            .overlay {
                if scope == .notes, filtered.isEmpty {
                    ContentUnavailableView {
                        Label(search.isEmpty ? "No Notes Yet" : "No Matches", systemImage: "note.text")
                    } description: {
                        Text(search.isEmpty
                             ? "Tap verses in the text, then the pencil, to start a note on a passage — or scan this Sunday’s sermon slide."
                             : "Try a word or a passage like Rom 8.")
                    }
                }
            }
            .searchable(text: $search, prompt: searchPrompt)
            .navigationTitle("Notes")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button { newNote() } label: { Label("New Note", systemImage: "square.and.pencil") }
                }
                ToolbarItem(placement: .primaryAction) {
                    SlideCaptureMenu(capture: slideCapture)
                }
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button("Export All Notes…", systemImage: "square.and.arrow.up") {
                            exportSelection = ExportSelection(notes: notes.map(\.exportValue).canonicallySorted)
                        }
                        .disabled(notes.isEmpty)
                        if filtered.count != notes.count {
                            Button("Export \(filtered.count) Shown…", systemImage: "line.3.horizontal.decrease") {
                                exportSelection = ExportSelection(notes: filtered.map(\.exportValue).canonicallySorted)
                            }
                            .disabled(filtered.isEmpty)
                        }
                        Divider()
                        Button("Keepsake & Export…", systemImage: "book.closed") { showLegacy = true }
                    } label: {
                        Label("Export", systemImage: "square.and.arrow.up")
                    }
                }
            }
            .sheet(item: $exportSelection) { selection in
                NotesExportSheet(notes: selection.notes, title: selection.notes.count == 1 ? selection.notes[0].displayTitle : String(localized: "Notes", comment: "Heading of an exported notes document"))
            }
            .sheet(isPresented: $showLegacy) {
                LegacySettingsView()
                    #if os(macOS)
                    .frame(minWidth: 520, minHeight: 600)
                    #endif
            }
            .slideCapture(slideCapture) { note in path = [note.uuid] }
            #if DEBUG
            .task {
                // The sermon-notes screenshot: a sample slide through the photo-import path.
                guard ScreenshotScene.current == .sermonNotes, let data = ScreenshotScene.sampleSlideData else { return }
                try? await Task.sleep(for: .milliseconds(900))
                await slideCapture.accept(data: data)
            }
            #endif
            // "Show my favorites" from Siri, Shortcuts or a link picks the scope once.
            .onChange(of: AppCommandCenter.shared.notesScope, initial: true) { _, request in
                guard let request else { return }
                scope = request == .favorites ? .favorites : .notes
                place = .all
                AppCommandCenter.shared.notesScope = nil
            }
            .navigationDestination(for: UUID.self) { id in
                if let note = notes.first(where: { $0.uuid == id }) {
                    NoteEditor(note: note)
                        .environment(model)
                } else {
                    ContentUnavailableView("Note Deleted", systemImage: "trash")
                }
            }
        }
    }

    private func newNote() {
        let ranges = model.selection.isEmpty
            ? [model.numbering.kjvRange(VerseRange(VerseRef(model.location.book, model.location.chapter, 1),
                          VerseRef(model.location.book, model.location.chapter, max(1, model.source?.verseCount(model.location) ?? 1))))]
            : model.selectedRanges
        let note = Note(anchors: ranges)
        context.insert(note)
        model.selection.removeAll()
        path = [note.uuid]
    }
}

private struct NoteRow: View {
    let note: Note

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(note.displayTitle).font(.headline).lineLimit(1)
                if note.origin == "camera" {
                    Image(systemName: "camera.viewfinder").font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Text(note.updatedAt, format: .dateTime.month(.abbreviated).day())
                    .font(.caption).foregroundStyle(.secondary)
            }
            if !note.anchors.isEmpty {
                Text(note.anchorSummary).font(.caption.weight(.medium)).foregroundStyle(.tint).lineLimit(1)
            }
            if !note.body.isEmpty {
                Text(note.body).font(.callout).foregroundStyle(.secondary).lineLimit(2)
            }
        }
        .padding(.vertical, 2)
    }
}
