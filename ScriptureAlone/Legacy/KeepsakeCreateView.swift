import SwiftUI
import SwiftData
import ScriptureAloneCore

/// Makes a Keepsake Bible: a file holding a copy of every highlight and note, to give to family.
struct KeepsakeCreateView: View {
    @Environment(ReaderModel.self) private var model
    @Query private var highlights: [Highlight]
    @Query private var notes: [Note]

    @AppStorage("legacy.ownerName") private var ownerName = ""
    @AppStorage("legacy.dedication") private var dedication = ""
    @AppStorage("legacy.translation") private var translation = ""
    @State private var protect = false
    @State private var passphrase = ""
    @State private var hint = ""
    @State private var working = false
    @State private var made: MadeKeepsake?
    @State private var saving = false
    @State private var failure: String?

    struct MadeKeepsake {
        let url: URL
        let document: ExportFile
        let name: String
        let protected: Bool
    }

    var body: some View {
        Form {
            Section {
                Text("A Keepsake Bible is a copy of your highlights and notes that your family can open in Scripture Alone and read as you marked it — the way a well-worn Bible gets passed down. It’s a single file you keep and give however you like: AirDrop, Messages, a USB drive, or with your papers.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }

            Section {
                TextField("Your name, as family knows you", text: $ownerName, prompt: Text("Dad, Grandma Ruth, Pastor Jim"))
                    #if os(iOS)
                    .textContentType(.name)
                    #endif
                VStack(alignment: .leading, spacing: 4) {
                    Text("Dedication").font(.caption).foregroundStyle(.secondary)
                    TextEditor(text: $dedication)
                        .frame(minHeight: 90)
                        .scrollContentBackground(.hidden)
                        .accessibilityLabel("Dedication")
                }
                Picker("Translation", selection: $translation) {
                    ForEach(model.translations) { Text("\($0.id) — \($0.name)").tag($0.id) }
                }
            } header: {
                Text("From You")
            } footer: {
                Text("The dedication opens their copy — a few words to whoever reads it next. The translation is the one it opens in.")
            }

            Section("What’s Included") {
                LabeledContent("Highlights", value: "\(uniqueHighlightCount)")
                LabeledContent("Notes", value: "\(notes.count)")
                if let span = dateSpan { LabeledContent("From", value: span) }
            }

            Section {
                Toggle("Protect with a Passphrase", isOn: $protect.animation())
                if protect {
                    // Shown, not hidden: it has to be written down exactly, and two secure
                    // fields would invite a generated password nobody in the family could know.
                    TextField("Passphrase", text: $passphrase)
                        .font(.body.monospaced())
                        .autocorrectionDisabled()
                        #if os(iOS)
                        .textInputAutocapitalization(.never)
                        #endif
                        .privacySensitive()
                    TextField("Hint (optional, shown to anyone)", text: $hint)
                }
            } header: {
                Text("Privacy")
            } footer: {
                Text(protect
                     ? "Write the passphrase down exactly as shown and keep it with the file — for example, with your will. Without it, no one can open this keepsake: not your family, and not us. There is no reset."
                     : "Without a passphrase, anyone who has the file can read it, like a Bible on a shelf. That’s usually what you want for family. Add one if the file might travel somewhere less private.")
            }

            Section {
                if let made {
                    ShareLink(item: made.url) {
                        Label("Share…", systemImage: "square.and.arrow.up")
                    }
                    Button { saving = true } label: { Label("Save to Files…", systemImage: "folder") }
                } else {
                    Button(action: create) {
                        HStack {
                            Text("Create Keepsake")
                            if working { Spacer(); ProgressView() }
                        }
                    }
                    .bold()
                    .disabled(!canCreate)
                }
            } footer: {
                if let made {
                    if made.protected {
                        Text("\(made.name) is ready, protected with your passphrase. It’s a snapshot of today; make a new one whenever you like, and it will replace the older copy when your family opens it.",
                             comment: "%@ is a keepsake file's name")
                    } else {
                        Text("\(made.name) is ready. It’s a snapshot of today; make a new one whenever you like, and it will replace the older copy when your family opens it.",
                             comment: "%@ is a keepsake file's name")
                    }
                }
            }

            if let failure {
                Section { Text(failure).foregroundStyle(.red) }
            }
        }
        .formStyle(.grouped)
        .scrollDismissesKeyboard(.interactively)
        .navigationTitle("Create a Keepsake")
        .onAppear {
            if translation.isEmpty || !model.translations.contains(where: { $0.id == translation }) {
                translation = model.translationID
            }
        }
        .onChange(of: ownerName) { made = nil }
        .onChange(of: dedication) { made = nil }
        .onChange(of: translation) { made = nil }
        .onChange(of: protect) { made = nil }
        .onChange(of: passphrase) { made = nil }
        .fileExporter(isPresented: $saving, document: made?.document, contentType: .scriptureLegacy,
                      defaultFilename: made?.name) { result in
            if case .failure(let error) = result { failure = error.localizedDescription }
        }
    }

    private var canCreate: Bool {
        guard !working, uniqueHighlightCount + notes.count > 0 else { return false }
        return !protect || !passphrase.trimmingCharacters(in: .whitespaces).isEmpty
    }

    /// One row per verse: the newest color wins, as in the reader.
    private var latestHighlights: [KeepsakeHighlight] {
        var byVerse: [Int: Highlight] = [:]
        for highlight in highlights {
            if let existing = byVerse[highlight.verseKey], existing.createdAt > highlight.createdAt { continue }
            byVerse[highlight.verseKey] = highlight
        }
        return byVerse.values.sorted { $0.verseKey < $1.verseKey }
            .map { KeepsakeHighlight(verse: $0.verseKey, color: $0.colorName, createdAt: $0.createdAt) }
    }

    private var uniqueHighlightCount: Int { Set(highlights.map(\.verseKey)).count }

    private var dateSpan: String? {
        let dates = highlights.map(\.createdAt) + notes.map(\.createdAt)
        guard let first = dates.min(), let last = dates.max() else { return nil }
        let a = first.formatted(.dateTime.month(.wide).year())
        let b = last.formatted(.dateTime.month(.wide).year())
        return a == b ? a : "\(a) to \(b)"
    }

    private func create() {
        failure = nil
        working = true
        let name = ownerName.trimmingCharacters(in: .whitespacesAndNewlines)
        let words = dedication.trimmingCharacters(in: .whitespacesAndNewlines)
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
        var keepsake = Keepsake(
            manifest: KeepsakeManifest(bibleID: LegacyIdentity.bibleID,
                                       ownerName: name.isEmpty ? nil : name,
                                       dedication: words.isEmpty ? nil : words,
                                       preferredTranslation: translation,
                                       generator: "Scripture Alone \(version)".trimmingCharacters(in: .whitespaces)),
            highlights: latestHighlights,
            notes: notes.map(\.exportValue).canonicallySorted)
        keepsake.refreshSummary()
        let snapshot = keepsake
        let phrase = protect ? passphrase : nil
        let hintText = protect ? hint : nil
        let fileName = ExportStaging.safeName(keepsake.manifest.displayTitle) + "." + KeepsakeArchive.fileExtension
        let protected = protect

        Task {
            // Passphrase key derivation is deliberately slow; keep it off the main thread.
            let result = await Task.detached(priority: .userInitiated) { () -> Result<Data, any Error> in
                Result { try KeepsakeArchive.encode(snapshot, passphrase: phrase, hint: hintText) }
            }.value
            working = false
            switch result {
            case .success(let data):
                let file = ExportFile(.file(data))
                do {
                    let url = try ExportStaging.write(file, named: fileName)
                    made = MadeKeepsake(url: url, document: file, name: fileName, protected: protected)
                } catch {
                    failure = error.localizedDescription
                }
            case .failure(let error):
                failure = error.localizedDescription
            }
        }
    }
}
