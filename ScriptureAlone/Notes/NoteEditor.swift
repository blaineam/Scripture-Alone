import SwiftUI
import SwiftData
import ScriptureAloneCore

struct NoteEditor: View {
    @Environment(ReaderModel.self) private var model
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @Bindable var note: Note
    @State private var passageText = ""
    @State private var confirmDelete = false
    @FocusState private var focus: Field?

    enum Field { case title, body, passage }

    var body: some View {
        Form {
            Section {
                TextField("Title", text: $note.title, axis: .vertical)
                    .font(.title2.weight(.semibold))
                    .focused($focus, equals: .title)
            }

            Section("Passages") {
                ForEach(note.anchors, id: \.self) { range in
                    HStack {
                        Button {
                            model.go(to: range.start)
                        } label: {
                            Label(range.display, systemImage: "book")
                        }
                        .buttonStyle(.borderless)
                        Spacer()
                        Button {
                            note.anchors.removeAll { $0 == range }
                            touch()
                        } label: {
                            Image(systemName: "minus.circle.fill").foregroundStyle(.secondary)
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("Remove \(range.display)")
                    }
                }
                HStack {
                    TextField("Add a passage, e.g. Rom 8:1-17", text: $passageText)
                        .focused($focus, equals: .passage)
                        .autocorrectionDisabled()
                        .onSubmit(addTypedPassages)
                    if !model.selection.isEmpty {
                        Button("Add Selection") {
                            note.anchors += model.selectedRanges
                            model.selection.removeAll()
                            touch()
                        }
                        .buttonStyle(.borderless)
                    }
                }
            }

            Section("Note") {
                TextEditor(text: $note.body)
                    .frame(minHeight: 220)
                    .focused($focus, equals: .body)
                    .scrollContentBackground(.hidden)
            }

            Section {
                LabeledContent("Created", value: note.createdAt.formatted(date: .abbreviated, time: .shortened))
                LabeledContent("Edited", value: note.updatedAt.formatted(date: .abbreviated, time: .shortened))
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
        }
        .formStyle(.grouped)
        .navigationTitle(note.displayTitle)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    ShareLink(item: exportText) { Label("Share Note", systemImage: "square.and.arrow.up") }
                    Button(role: .destructive) { confirmDelete = true } label: { Label("Delete Note", systemImage: "trash") }
                } label: {
                    Label("More", systemImage: "ellipsis.circle")
                }
            }
        }
        .confirmationDialog("Delete this note?", isPresented: $confirmDelete) {
            Button("Delete Note", role: .destructive) {
                context.delete(note)
                dismiss()
            }
        } message: {
            Text("It will be removed from all your devices.")
        }
        .onChange(of: note.title) { touch() }
        .onChange(of: note.body) { touch() }
        .onAppear {
            if note.title.isEmpty && note.body.isEmpty { focus = .title }
        }
    }

    private func addTypedPassages() {
        guard let store = model.store else { return }
        let ranges = ReferenceParser.parseList(passageText).map { $0.clamped.range { store.verseCount($0) } }
        guard !ranges.isEmpty else { return }
        note.anchors += ranges
        passageText = ""
        touch()
    }

    private func touch() { note.updatedAt = .now }

    /// Markdown-flavored plain text: title, passages, body.
    private var exportText: String {
        var lines = ["# \(note.displayTitle)"]
        if !note.anchors.isEmpty { lines.append(note.anchorSummary) }
        if !note.body.isEmpty { lines.append(""); lines.append(note.body) }
        return lines.joined(separator: "\n")
    }
}
