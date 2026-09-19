import SwiftUI
import UniformTypeIdentifiers
import ScriptureAloneCore

/// Export notes as a PDF, Markdown (one file or a folder of files) or plain text, with each
/// note's passages quoted in a chosen translation.
struct NotesExportSheet: View {
    let notes: [KeepsakeNote]
    /// "Notes", "Dad’s Notes", or a single note's title.
    let title: String
    var preferredTranslation: String?

    @Environment(ReaderModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    enum Format: String, CaseIterable, Identifiable {
        case pdf, markdown, markdownFolder, plainText
        var id: String { rawValue }

        var title: String {
            switch self {
            case .pdf: "PDF"
            case .markdown: "Markdown"
            case .markdownFolder: "Markdown Folder"
            case .plainText: "Plain Text"
            }
        }

        var detail: String {
            switch self {
            case .pdf: "Typeset for reading and printing."
            case .markdown: "One file, for note apps and editors."
            case .markdownFolder: "A file for each note."
            case .plainText: "Simple text that opens anywhere."
            }
        }
    }

    @State private var format = Format.pdf
    @State private var includeVerses = true
    @State private var translation = ""
    @State private var exported: ExportedFile?
    @State private var saving = false
    @State private var failure: String?

    struct ExportedFile {
        let url: URL
        let document: ExportFile
        let type: UTType
        let name: String
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Format", selection: $format) {
                        ForEach(availableFormats) { option in
                            VStack(alignment: .leading) {
                                Text(option.title)
                                Text(option.detail).font(.caption).foregroundStyle(.secondary)
                            }
                            .tag(option)
                        }
                    }
                    #if os(iOS)
                    .pickerStyle(.inline)
                    #else
                    .pickerStyle(.radioGroup)
                    #endif
                    .labelsHidden()
                } header: {
                    Text(notes.count == 1 ? "Export “\(title)”" : "Export \(notes.count) Notes")
                }

                Section {
                    Toggle("Include Verse Text", isOn: $includeVerses)
                    if includeVerses {
                        Picker("Translation", selection: $translation) {
                            ForEach(model.translations) { Text("\($0.id) — \($0.name)").tag($0.id) }
                        }
                    }
                } footer: {
                    Text("Each note’s passages are quoted above what was written. Very long passages are shortened to their opening verses.")
                }

                if let exported {
                    Section {
                        ShareLink(item: exported.url) {
                            Label("Share…", systemImage: "square.and.arrow.up")
                        }
                        Button {
                            saving = true
                        } label: {
                            Label("Save to Files…", systemImage: "folder")
                        }
                    } header: {
                        Text("Ready")
                    } footer: {
                        Text(exported.name)
                    }
                } else {
                    Section {
                        Button("Prepare Export", action: prepare)
                            .bold()
                            .disabled(notes.isEmpty)
                    }
                }

                if let failure {
                    Section { Text(failure).foregroundStyle(.red) }
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Export Notes")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } }
            }
            .onChange(of: format) { exported = nil }
            .onChange(of: includeVerses) { exported = nil }
            .onChange(of: translation) { exported = nil }
            .onAppear {
                if translation.isEmpty {
                    let preferred = preferredTranslation.flatMap { id in model.translations.first { $0.id == id }?.id }
                    translation = preferred ?? model.translationID
                }
            }
            .fileExporter(isPresented: $saving, document: exported?.document, contentType: exported?.type ?? .data,
                          defaultFilename: exported?.name) { result in
                if case .failure(let error) = result { failure = error.localizedDescription }
            }
        }
        #if os(macOS)
        .frame(minWidth: 440, minHeight: 520)
        #endif
    }

    private var availableFormats: [Format] {
        notes.count == 1 ? [.pdf, .markdown, .plainText] : Format.allCases
    }

    private func prepare() {
        failure = nil
        let verseText: NotesTextExport.VerseText = includeVerses ? model.exportVerseText(translation: translation) : { _ in nil }
        let options = NotesTextExport.Options(title: title, translation: includeVerses ? translation : nil)
        let base = ExportStaging.safeName(title)
        let file: ExportFile
        let name: String
        let type: UTType
        switch format {
        case .pdf:
            let data = NotesPDFRenderer.render(notes, options: .init(title: title, subtitle: nil, translation: options.translation),
                                               verseText: verseText)
            (file, name, type) = (ExportFile(.file(data)), "\(base).pdf", .pdf)
        case .markdown:
            let text = NotesTextExport.markdown(notes, options: options, verseText: verseText)
            (file, name, type) = (ExportFile(.file(Data(text.utf8))), "\(base).md", .markdownText)
        case .markdownFolder:
            var files: [String: Data] = [:]
            for entry in NotesTextExport.markdownFiles(notes, options: options, verseText: verseText) {
                files[entry.name] = Data(entry.contents.utf8)
            }
            (file, name, type) = (ExportFile(.folder(files)), base, .folder)
        case .plainText:
            let text = NotesTextExport.plainText(notes, options: options, verseText: verseText)
            (file, name, type) = (ExportFile(.file(Data(text.utf8))), "\(base).txt", .plainText)
        }
        do {
            let url = try ExportStaging.write(file, named: name)
            exported = ExportedFile(url: url, document: file, type: type, name: name)
        } catch {
            failure = error.localizedDescription
        }
    }
}
