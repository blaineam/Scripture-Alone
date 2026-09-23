#if !os(watchOS)
import ScriptureAloneCore
import SwiftData
import SwiftUI
import UniformTypeIdentifiers

/// Brings a reader's notes across from Life Bible (formerly Tecarta Bible).
///
/// Someone arriving with years of study notes should not have to retype them, and the point of
/// this app's whole storage design — plain verse keys, ordinary text — is that notes from
/// elsewhere can land in it without ceremony.
///
/// Two things this screen takes seriously. It says what it found **before** writing anything, so
/// nobody discovers after the fact that a reference was misread. And running it twice does not
/// double a reader's library: everything checks for its own presence first, so a reader who is not
/// sure whether the import worked can simply do it again.
struct LifeBibleImportView: View {
    @Environment(\.modelContext) private var context
    @Environment(ReaderModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    @State private var picking = false
    @State private var found: ImportedNotes?
    @State private var pasting = false
    @State private var pasted = ""
    @State private var failure: String?
    @State private var outcome: Outcome?
    @State private var working = false

    struct Outcome: Equatable {
        var highlights = 0
        var notes = 0
        var journals = 0
        var favorites = 0
        var alreadyThere = 0
        var total: Int { highlights + notes + journals + favorites }
    }

    var body: some View {
        NavigationStack {
            Form {
                if let outcome {
                    finished(outcome)
                } else if let found {
                    preview(found)
                } else {
                    instructions
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Bring Your Notes")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
            .sheet(isPresented: $pasting) { pasteSheet }
            .fileImporter(isPresented: $picking, allowedContentTypes: [.zip, .commaSeparatedText, .plainText, .tabSeparatedText]) { result in
                switch result {
                case .success(let url): read(url)
                case .failure(let error): failure = error.localizedDescription
                }
            }
            .alert("That didn't work", isPresented: .constant(failure != nil)) {
                Button("OK") { failure = nil }
            } message: {
                Text(failure ?? "")
            }
        }
    }

    // MARK: Screens

    private var instructions: some View {
        Group {
            Section {
                Button("Choose a File…", systemImage: "doc.badge.plus") { picking = true }
            } header: {
                Text("From Life Bible")
            } footer: {
                Text("Life Bible — the app that used to be called Tecarta Bible — can export everything you've written. Your notes, highlights and saved verses come across; nothing is sent anywhere, and the file never leaves your device.")
            }

            Section {
                Button("Paste Notes From Any App…", systemImage: "doc.on.clipboard") { pasting = true }
            } header: {
                Text("From Anywhere Else")
            } footer: {
                // Deliberately not a list of supported apps: the parser reads a reference and the
                // text belonging to it, whatever produced them. Naming apps whose exports nobody
                // here has seen would be a promise made from documentation rather than from files.
                Text("Paste notes, or a CSV you exported. Each entry needs to start with a reference — “John 3:16” — so it can be attached to the right verse. Anything that doesn't name a verse is listed for you rather than guessed at.")
            }

            Section("How to get the file") {
                step(1, "Open Life Bible, or sign in at lifebible.com.")
                step(2, "Tap the menu, then Settings.")
                step(3, "Scroll to Advanced and tap “Export your data”.")
                step(4, "Save LifeBibleData.zip, then choose it above.")
                Label("Don't tap “Delete your account” — it sits just below Export.",
                      systemImage: "exclamationmark.triangle")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func step(_ number: Int, _ text: LocalizedStringKey) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text("\(number)").font(.caption.monospacedDigit().weight(.semibold))
                .foregroundStyle(.tint)
                .frame(width: 16, alignment: .trailing)
            Text(text)
        }
        .font(.callout)
    }

    @ViewBuilder
    private func preview(_ result: ImportedNotes) -> some View {
        Section {
            row("Notes on verses", result.verseNotes.count, "text.quote")
            row("Journal entries", result.journals.count, "book.closed")
            row("Highlights", result.highlights.count, "highlighter")
            row("Saved verses", result.saved.count, "heart")
        } header: {
            Text("Found in this file")
        } footer: {
            Text("Nothing has been added yet. Importing twice is safe — anything already here is left alone rather than duplicated.")
        }

        if !result.unresolved.isEmpty {
            Section {
                ForEach(result.unresolved.prefix(8), id: \.self) { line in
                    Text(line).font(.caption).foregroundStyle(.secondary)
                }
                if result.unresolved.count > 8 {
                    Text("…and \(result.unresolved.count - 8) more")
                        .font(.caption).foregroundStyle(.secondary)
                }
            } header: {
                Text("\(result.unresolved.count) couldn't be placed")
            } footer: {
                // Saying so plainly beats importing a best guess: a note filed against the wrong
                // verse is worse than one the reader knows to copy across by hand.
                Text("These name something this app can't find a verse for. They'll be skipped rather than guessed at, so you can copy them over yourself.")
            }
        }

        Section {
            Button {
                Task { await bring(result) }
            } label: {
                if working { ProgressView() } else { Text("Add \(result.total) Items") }
            }
            .disabled(working)
            Button("Choose a Different File") { found = nil }
        }
    }

    @ViewBuilder
    private func finished(_ outcome: Outcome) -> some View {
        Section {
            if outcome.total > 0 {
                row("Notes on verses", outcome.notes, "text.quote")
                row("Journal entries", outcome.journals, "book.closed")
                row("Highlights", outcome.highlights, "highlighter")
                row("Saved verses", outcome.favorites, "heart")
            }
            if outcome.alreadyThere > 0 {
                row("Already here", outcome.alreadyThere, "checkmark.circle")
            }
        } header: {
            Text(outcome.total > 0 ? "Brought across" : "Nothing new to add")
        } footer: {
            Text(outcome.total > 0
                 ? "They're in your notes and highlights now, and will sync to your other devices through your own iCloud."
                 : "Everything in that file was already here.")
        }
    }

    private func row(_ title: LocalizedStringKey, _ count: Int, _ symbol: String) -> some View {
        LabeledContent {
            Text("\(count)").monospacedDigit().foregroundStyle(count == 0 ? .secondary : .primary)
        } label: {
            Label(title, systemImage: symbol)
        }
    }

    private var pasteSheet: some View {
        NavigationStack {
            VStack(spacing: 0) {
                TextEditor(text: $pasted)
                    .font(.callout)
                    .padding(.horizontal, 12)
                    .overlay(alignment: .topLeading) {
                        if pasted.isEmpty {
                            Text("John 3:16 — the whole gospel in one verse\n\nRomans 8:28 — not that all things are good")
                                .font(.callout)
                                .foregroundStyle(.tertiary)
                                .padding(.horizontal, 17)
                                .padding(.vertical, 8)
                                .allowsHitTesting(false)
                        }
                    }
            }
            .navigationTitle("Paste Notes")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { pasting = false; pasted = "" }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Read") { readPasted() }
                        .disabled(pasted.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }

    // MARK: Work

    /// Chapter lengths from the translation open in the reader, so a highlight imported as a range
    /// across a chapter break covers the verses that exist rather than every number in between. With
    /// no translation open the importers fall back to highlighting only the verses a range names.
    private var verseCount: ((ChapterRef) -> Int)? {
        guard let source = model.source else { return nil }
        return { source.verseCount($0) }
    }

    private func readPasted() {
        do {
            found = try PastedNotesImport.parse(pasted, verseCount: verseCount)
            pasting = false
            pasted = ""
        } catch {
            failure = error.localizedDescription
        }
    }

    private func read(_ url: URL) {
        // A file chosen from Files or iCloud Drive is outside the sandbox until asked for.
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            let data = try Data(contentsOf: url)
            // A zip is a Life Bible export; anything else is text somebody exported from
            // elsewhere, read by shape rather than by which app wrote it.
            if url.pathExtension.lowercased() == "zip" {
                found = try LifeBibleImport.read(archive: data, verseCount: verseCount)
            } else if let text = String(data: data, encoding: .utf8)
                        ?? String(data: data, encoding: .isoLatin1) {
                found = try PastedNotesImport.parse(text, verseCount: verseCount)
            } else {
                failure = NoteImportError.nothingRecognised.localizedDescription
            }
        } catch {
            failure = error.localizedDescription
        }
    }

    @MainActor
    private func bring(_ result: ImportedNotes) async {
        working = true
        defer { working = false }
        var tally = Outcome()

        // Existing rows, read once. Checking each candidate against the database one query at a
        // time would be hundreds of round trips for a library this size.
        let existingHighlights = Set(
            ((try? context.fetch(FetchDescriptor<Highlight>())) ?? []).map(\.verseKey))
        let existingFavorites = Set(
            ((try? context.fetch(FetchDescriptor<Favorite>())) ?? []).map(\.rangeRaw))
        let existingNotes = Set(
            ((try? context.fetch(FetchDescriptor<Note>())) ?? [])
                .map { Self.fingerprint(title: $0.title, body: $0.body) })

        for highlight in result.highlights {
            guard !existingHighlights.contains(highlight.verse.key) else {
                tally.alreadyThere += 1
                continue
            }
            let color = HighlightColor(rawValue: highlight.color) ?? .yellow
            context.insert(Highlight(verseKey: highlight.verse.key, color: color))
            tally.highlights += 1
        }

        for note in result.verseNotes {
            let print = Self.fingerprint(title: note.title, body: note.body)
            guard !existingNotes.contains(print) else { tally.alreadyThere += 1; continue }
            let anchors = note.range.map { [$0] } ?? []
            context.insert(Note(title: note.title, body: note.body, anchors: anchors,
                                origin: Self.origin))
            tally.notes += 1
        }

        for entry in result.journals {
            let print = Self.fingerprint(title: entry.title, body: entry.body)
            guard !existingNotes.contains(print) else { tally.alreadyThere += 1; continue }
            context.insert(Note(title: entry.title, body: entry.body, anchors: [],
                                origin: Self.origin))
            tally.journals += 1
        }

        for range in result.saved {
            guard !existingFavorites.contains(range.storageString) else {
                tally.alreadyThere += 1
                continue
            }
            context.insert(Favorite(range: range))
            tally.favorites += 1
        }

        do {
            try context.save()
            outcome = tally
        } catch {
            failure = error.localizedDescription
        }
    }

    /// Marks a note as having come from elsewhere, alongside "manual" and "camera".
    static let origin = "lifebible"

    /// What makes two notes the same note, for the purpose of not importing one twice. Title and
    /// body, with whitespace normalised — not the verse, since a reader may well have written
    /// several notes on one verse, and not the date, which an import does not carry.
    static func fingerprint(title: String, body: String) -> String {
        func flatten(_ text: String) -> String {
            text.split(whereSeparator: \.isWhitespace).joined(separator: " ").lowercased()
        }
        return flatten(title) + "\u{1F}" + flatten(body)
    }
}
#endif
