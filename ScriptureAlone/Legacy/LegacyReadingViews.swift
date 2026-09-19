import SwiftUI
import ScriptureAloneCore

/// "Reading Dad’s Bible · Read-only keepsake", above the text while a keepsake is open.
struct LegacyBanner: View {
    @Environment(LegacySession.self) private var session
    @Environment(LegacyLibrary.self) private var library
    @Environment(ReaderModel.self) private var model
    @State private var expanded = false

    var body: some View {
        if let keepsake = session.reading {
            let manifest = keepsake.manifest
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: "book.closed.fill")
                    .font(.title3)
                    .foregroundStyle(.tint)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 3) {
                    Text("Reading \(manifest.displayTitle)")
                        .font(.system(.subheadline, design: .serif).weight(.semibold))
                    if let dedication = manifest.dedication, !dedication.isEmpty {
                        Text(dedication)
                            .font(.system(.footnote, design: .serif).italic())
                            .lineLimit(expanded ? nil : 1)
                            .onTapGesture { withAnimation(.snappy) { expanded.toggle() } }
                    }
                    if case .live(let id) = session.source {
                        LiveShareCaption(id: id)
                    } else {
                        Text("Read-only keepsake")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer(minLength: 8)
                Button {
                    session.close(model: model)
                } label: {
                    Text("My Bible").font(.footnote.weight(.semibold))
                }
                .buttonStyle(.bordered)
                .buttonBorderShape(.capsule)
                .accessibilityLabel("Return to My Bible")
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .frame(maxWidth: 620)
            .glassEffect(.regular, in: .rect(cornerRadius: 20))
            .padding(.horizontal)
            .padding(.top, 4)
            .accessibilityElement(children: .contain)
            .onChange(of: library.entries) {
                // Removed from the library in another window: step back out.
                if session.source == .keepsake, library.entry(keepsake.id) == nil { session.close(model: model) }
            }
        }
    }
}

/// The keepsake's notes, read-only, in place of the reader's own Notes panel.
struct LegacyNotesPanel: View {
    let keepsake: Keepsake
    @Binding var path: [UUID]
    @Environment(ReaderModel.self) private var model
    @Environment(LegacySession.self) private var session
    @State private var search = ""
    @State private var scope = NotesPanel.Scope.all
    @State private var exporting = false

    private var notes: [KeepsakeNote] {
        keepsake.notes.sorted { $0.updatedAt > $1.updatedAt }.filter { note in
            (scope == .all || note.touches(model.location)) && matches(note)
        }
    }

    private func matches(_ note: KeepsakeNote) -> Bool {
        let term = search.trimmingCharacters(in: .whitespaces)
        guard !term.isEmpty else { return true }
        return [note.title, note.body, note.anchorSummary].contains { $0.localizedStandardContains(term) }
    }

    var body: some View {
        NavigationStack(path: $path) {
            List {
                Section {
                    Picker("Show", selection: $scope) {
                        ForEach(NotesPanel.Scope.allCases) { Text($0.rawValue).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .listRowSeparator(.hidden)
                } footer: {
                    Text("\(keepsake.manifest.displayTitle) · read-only")
                }
                if scope == .favorites {
                    LegacyFavoritesList(favorites: session.favorites, search: search)
                } else {
                    ForEach(notes) { note in
                        NavigationLink(value: note.id) { LegacyNoteRow(note: note) }
                    }
                }
            }
            .liveShareRefreshable(session.source)
            .overlay {
                if scope != .favorites, notes.isEmpty {
                    ContentUnavailableView(search.isEmpty ? "No Notes Here" : "No Matches", systemImage: "note.text",
                                           description: Text(search.isEmpty && scope == .chapter
                                                             ? "There are no notes on this chapter."
                                                             : "Try another word."))
                }
            }
            .searchable(text: $search, prompt: "Search these notes")
            .navigationTitle("Notes")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button { exporting = true } label: { Label("Export Notes", systemImage: "square.and.arrow.up") }
                        .disabled(keepsake.notes.isEmpty)
                }
            }
            .navigationDestination(for: UUID.self) { id in
                if let note = keepsake.notes.first(where: { $0.id == id }) {
                    LegacyNoteDetail(note: note)
                }
            }
            .sheet(isPresented: $exporting) {
                NotesExportSheet(notes: keepsake.notes.canonicallySorted,
                                 title: notesTitle,
                                 preferredTranslation: keepsake.manifest.preferredTranslation)
            }
        }
    }

    private var notesTitle: String {
        guard let name = keepsake.manifest.ownerName?.trimmingCharacters(in: .whitespaces), !name.isEmpty else { return "Notes" }
        return name.hasSuffix("s") ? "\(name)’ Notes" : "\(name)’s Notes"
    }
}

private struct LegacyNoteRow: View {
    let note: KeepsakeNote

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(note.displayTitle).font(.headline).lineLimit(1)
                Spacer()
                Text(note.updatedAt, format: .dateTime.year().month(.abbreviated).day())
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

/// One note, as it was written. Text can be selected and copied; nothing can be changed.
struct LegacyNoteDetail: View {
    let note: KeepsakeNote
    @Environment(ReaderModel.self) private var model
    @State private var copied = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(note.displayTitle)
                    .font(.system(.title2, design: .serif).weight(.semibold))
                    .textSelection(.enabled)
                if !note.anchors.isEmpty {
                    FlowPassages(anchors: note.anchors) { model.go(to: $0.start) }
                }
                if !note.body.isEmpty {
                    Text(note.body)
                        .font(.system(.body, design: .serif))
                        .lineSpacing(4)
                        .textSelection(.enabled)
                }
                Text(NotesTextExport.dateLine(note))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
        }
        .navigationTitle(note.displayTitle)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    copyText()
                } label: {
                    Label(copied ? "Copied" : "Copy Note", systemImage: copied ? "checkmark" : "doc.on.doc")
                }
            }
        }
    }

    private func copyText() {
        var text = note.displayTitle
        if !note.anchors.isEmpty { text += "\n" + note.anchorSummary }
        if !note.body.isEmpty { text += "\n\n" + note.body }
        #if os(iOS)
        UIPasteboard.general.string = text
        #else
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        #endif
        copied = true
        Task {
            try? await Task.sleep(for: .seconds(1.2))
            copied = false
        }
    }
}

private struct FlowPassages: View {
    let anchors: [VerseRange]
    let go: (VerseRange) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(anchors, id: \.self) { range in
                Button { go(range) } label: { Label(range.display, systemImage: "book") }
                    .buttonStyle(.borderless)
                    .font(.callout.weight(.medium))
            }
        }
    }
}

/// The popover for a note marker in a keepsake.
struct LegacyNotesPopover: View {
    let keepsake: Keepsake
    let ids: [String]
    let openNote: (UUID) -> Void

    var body: some View {
        let matching = keepsake.notes.filter { ids.contains($0.id.uuidString) }
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                ForEach(matching) { note in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(note.displayTitle).font(.headline)
                        Text(note.anchorSummary).font(.caption).foregroundStyle(.secondary)
                        if !note.body.isEmpty {
                            Text(note.body).font(.callout).lineLimit(10).textSelection(.enabled)
                        }
                        Button("Open Note") { openNote(note.id) }
                            .font(.callout.weight(.semibold))
                    }
                }
            }
            .padding()
        }
        .frame(idealWidth: 340, maxHeight: 420)
    }
}
