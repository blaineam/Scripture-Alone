import SwiftUI
import SwiftData
import ScriptureAloneCore

/// The "Keepsake & Export" row, for a settings list.
struct LegacyAndExportRow: View {
    @Environment(LegacySession.self) private var session
    @Environment(\.dismiss) private var dismissContainer
    @State private var showing = false

    var body: some View {
        Button { showing = true } label: {
            Label("Keepsake & Export", systemImage: "book.closed")
        }
        .sheet(isPresented: $showing) {
            LegacySettingsView()
                #if os(macOS)
                .frame(minWidth: 520, minHeight: 600)
                #endif
        }
        .onChange(of: session.reading?.id) { _, opened in
            // Opening a keepsake from here: step back to the text so it can be read.
            if opened != nil { dismissContainer() }
        }
    }
}

/// Keepsakes to give, keepsakes received, and exporting notes.
struct LegacySettingsView: View {
    @Environment(ReaderModel.self) private var model
    @Environment(LegacyLibrary.self) private var library
    @Environment(LegacySession.self) private var session
    @Environment(\.dismiss) private var dismiss
    @Query(sort: \Note.updatedAt, order: .reverse) private var notes: [Note]

    @State private var importing = false
    @State private var importRequest: KeepsakeImportRequest?
    @State private var exportingNotes = false
    @State private var importingLifeBible = false
    @State private var pendingRemoval: LegacyLibrary.Entry?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    NavigationLink {
                        KeepsakeCreateView()
                    } label: {
                        Label("Create a Keepsake", systemImage: "gift")
                    }
                } header: {
                    Text("Your Keepsake Bible")
                } footer: {
                    Text("Give your family a copy of your highlights and notes — a digital version of the Bible you’ve marked over the years. It’s a file you hand over yourself; nothing is sent anywhere.")
                }

                FamilySharingSettingsSection()

                SharedBiblesSection { entry in
                    session.openLive(entry, model: model)
                    dismiss()
                }

                Section {
                    ForEach(library.entries) { entry in
                        Button {
                            open(entry)
                        } label: {
                            KeepsakeRow(entry: entry, isOpen: session.source == .keepsake && session.reading?.id == entry.id)
                        }
                        .buttonStyle(.plain)
                        .contextMenu {
                            Button("Open", systemImage: "book") { open(entry) }
                            ShareLink(item: library.fileURL(for: entry.id)) { Label("Share a Copy", systemImage: "square.and.arrow.up") }
                            Button("Remove…", systemImage: "trash", role: .destructive) { pendingRemoval = entry }
                        }
                        .swipeActions {
                            Button("Remove", systemImage: "trash") { pendingRemoval = entry }.tint(.red)
                        }
                    }
                    Button {
                        importing = true
                    } label: {
                        Label("Open a Keepsake File…", systemImage: "tray.and.arrow.down")
                    }
                } header: {
                    Text("Keepsakes You’ve Been Given")
                } footer: {
                    Text(library.entries.isEmpty
                         ? "When someone gives you a Keepsake Bible, open the file here — or tap it in Messages, Mail or Files. It stays on this device, apart from your own notes."
                         : "Tap one to read it. Their highlights and notes appear in the text, just as they left them.")
                }

                Section {
                    Button {
                        importingLifeBible = true
                    } label: {
                        Label("Bring Notes From Another App…", systemImage: "square.and.arrow.down")
                    }
                } header: {
                    Text("Coming From Somewhere Else")
                } footer: {
                    Text("If you've been reading in Life Bible — the app formerly called Tecarta Bible — your notes, highlights and saved verses can come with you.",
                         comment: "“Life Bible” and “Tecarta Bible” are app names.")
                }

                Section {
                    Button {
                        exportingNotes = true
                    } label: {
                        Label("Export All Notes…", systemImage: "square.and.arrow.up")
                    }
                    // An export carries the verses the notes are about, so it is the translation's
                    // terms that decide, not the notes'.
                    .disabled(notes.isEmpty || !model.rights.allowNotesExport)
                } header: {
                    Text("Export")
                } footer: {
                    Text(model.rights.allowNotesExport
                         ? "Your notes as a PDF to print or keep, as Markdown, or as plain text — with the verses they’re about."
                         : "\(model.translationInfo?.abbreviation ?? String(localized: "This translation", comment: "Stands in for a translation's abbreviation in “%@ doesn’t allow its text to be exported.”")) doesn’t allow its text to be exported. Switch to another translation to export your notes with the verses they’re about.")
                }

                Section("How This Works") {
                    Text("A keepsake is a snapshot. Notes you write afterwards aren’t in it — when you’d like your family to have them, make a new keepsake. Opening a newer one replaces the older copy on their device.")
                    Text("Keepsakes are ordinary files: a ZIP archive of readable text. Even without this app, the words stay recoverable.")
                }
                .font(.callout)
                .foregroundStyle(.secondary)
            }
            .formStyle(.grouped)
            .refreshable { await SharedBibleLibrary.shared.refresh() }
            .navigationTitle("Keepsake & Export")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.scriptureLegacy, .data]) { result in
                if case .success(let url) = result { importRequest = KeepsakeImportRequest(url: url) }
            }
            .sheet(item: $importRequest) { request in
                KeepsakeImportSheet(url: request.url)
            }
            .sheet(isPresented: $importingLifeBible) { LifeBibleImportView() }
            .sheet(isPresented: $exportingNotes) {
                NotesExportSheet(notes: notes.map(\.exportValue).canonicallySorted, title: String(localized: "Notes", comment: "Heading of an exported notes document"))
            }
            .confirmationDialog(pendingRemoval.map { String(localized: "Remove \($0.title)?", comment: "%@ is the title of a keepsake or shared Bible") }
                                ?? String(localized: "Remove this keepsake?"),
                                isPresented: Binding(get: { pendingRemoval != nil }, set: { if !$0 { pendingRemoval = nil } }),
                                titleVisibility: .visible, presenting: pendingRemoval) { entry in
                Button("Remove from This Device", role: .destructive) {
                    if session.reading?.id == entry.id { session.close(model: model) }
                    library.remove(entry.id)
                }
            } message: { _ in
                Text("The keepsake will be removed from this device. If you want it again later, you’ll need the original file.")
            }
        }
    }

    private func open(_ entry: LegacyLibrary.Entry) {
        guard let keepsake = library.keepsake(entry.id) else { return }
        session.open(keepsake, model: model)
        dismiss()
    }
}

private struct KeepsakeRow: View {
    let entry: LegacyLibrary.Entry
    let isOpen: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "book.closed.fill")
                .font(.title2)
                .foregroundStyle(.tint)
                .frame(width: 30)
            VStack(alignment: .leading, spacing: 3) {
                Text(entry.title).font(.system(.headline, design: .serif))
                if let dedication = entry.manifest.dedication, !dedication.isEmpty {
                    Text(dedication).font(.system(.subheadline, design: .serif).italic()).foregroundStyle(.secondary).lineLimit(2)
                }
                Text(detail).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if isOpen {
                Text("Open").font(.caption.weight(.semibold)).foregroundStyle(.tint)
            }
        }
        .contentShape(Rectangle())
        .padding(.vertical, 2)
    }

    private var detail: String {
        var parts: [String] = []
        if let counts = entry.manifest.counts {
            parts.append(String(localized: "\(counts.highlights) highlights · \(counts.notes) notes", comment: "Keepsake contents. %1$lld is a number of highlights; %2$lld a number of notes."))
        }
        let made = entry.manifest.createdAt.formatted(date: .abbreviated, time: .omitted)
        parts.append(String(localized: "made \(made)", comment: "When a keepsake was made. %@ is a date."))
        return parts.joined(separator: " · ")
    }
}
