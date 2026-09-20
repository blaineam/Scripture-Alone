import SwiftUI
import UniformTypeIdentifiers
import ScriptureAloneCore

/// Every translation on the device, and the two ways to add one.
struct TranslationsView: View {
    @Environment(ReaderModel.self) private var model
    @Environment(ImportedLibrary.self) private var library
    @Environment(\.dismiss) private var dismiss

    @State private var showFileImporter = false
    @State private var showCatalog = false
    @State private var showKeys = false
    @State private var importing: String?
    @State private var failure: String?
    @State private var finished: BibleImportResult?
    @State private var pendingRemoval: ImportedLibrary.Entry?

    var body: some View {
        NavigationStack {
            Form {
                Section("Included") {
                    ForEach(model.bundledTranslations) { entry in
                        row(name: entry.name, abbreviation: entry.id, note: nil)
                    }
                }

                let online = model.translations.filter(\.isOnline)
                if !online.isEmpty {
                    Section {
                        ForEach(online) { entry in
                            row(name: entry.name, abbreviation: entry.id, note: "Read over the network")
                        }
                    } header: {
                        Text("Online")
                    } footer: {
                        Text("These need a connection. What you read is cached up to the publisher's limit, and they can't be searched offline.")
                    }
                }

                if !library.entries.isEmpty {
                    Section("Added by You") {
                        ForEach(library.entries) { entry in
                            row(name: entry.info.name, abbreviation: entry.info.abbreviation,
                                note: entry.info.copyright)
                                .swipeActions {
                                    Button("Remove", systemImage: "trash", role: .destructive) {
                                        pendingRemoval = entry
                                    }
                                }
                        }
                    }
                }

                Section {
                    Button("Browse Free Translations…", systemImage: "globe") { showCatalog = true }
                    Button("Import a File…", systemImage: "folder") { showFileImporter = true }
                    Button("Online Translations…", systemImage: "key") { showKeys = true }
                } header: {
                    Text("Add a Translation")
                } footer: {
                    Text("Free translations come from eBible.org, and nothing is downloaded until you choose one. A file can be a USFM zip or an ePub you own — anything copy-protected is refused. The ESV, CSB, NASB and NKJV can't be given away by anyone, so they're read over the network with your own free key.")
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Translations")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
            .overlay { if let importing { ImportingOverlay(name: importing) } }
        }
        .sheet(isPresented: $showKeys) {
            OnlineKeysView()
                .environment(model)
                #if os(macOS)
                .frame(minWidth: 520, minHeight: 560)
                #endif
        }
        .sheet(isPresented: $showCatalog) {
            CatalogView { translation, url in
                await runImport(url: url, name: translation.title, identity: translation.importIdentity)
            }
        }
        .fileImporter(isPresented: $showFileImporter,
                      allowedContentTypes: [.zip, .epub, UTType(filenameExtension: "usfm") ?? .data]) { result in
            switch result {
            case .success(let url): Task { await runImport(url: url, name: url.lastPathComponent) }
            case .failure(let error): failure = error.localizedDescription
            }
        }
        .alert("That translation couldn't be added", isPresented: .constant(failure != nil)) {
            Button("OK") { failure = nil }
        } message: {
            Text(failure ?? "")
        }
        .sheet(item: $finished) { result in
            ImportSummaryView(result: result)
        }
        .confirmationDialog("Remove this translation?",
                            isPresented: .constant(pendingRemoval != nil), titleVisibility: .visible) {
            Button("Remove", role: .destructive) {
                if let pendingRemoval {
                    // Never leave the reader pointing at a store that no longer exists.
                    if model.translationID == pendingRemoval.info.id {
                        model.selectTranslation(ReaderModel.defaultTranslation)
                    }
                    library.remove(pendingRemoval)
                    model.refreshTranslations(imported: library.entries.map { ($0.info, $0.url) })
                }
                pendingRemoval = nil
            }
            Button("Cancel", role: .cancel) { pendingRemoval = nil }
        } message: {
            Text("Your highlights and notes stay; they're kept by verse, not by translation.")
        }
    }

    private func row(name: String, abbreviation: String, note: String?) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack {
                Text(name)
                Spacer()
                Text(abbreviation).font(.caption.monospaced()).foregroundStyle(.secondary)
            }
            if let note, !note.isEmpty {
                Text(note).font(.caption2).foregroundStyle(.secondary).lineLimit(2)
            }
        }
    }

    private func runImport(url: URL, name: String,
                           identity: ImportedTranslationIdentity? = nil) async {
        importing = name
        defer { importing = nil }
        do {
            let result = try await library.importFile(at: url, named: name, as: identity)
            model.refreshTranslations(imported: library.entries.map { ($0.info, $0.url) })
            finished = result
        } catch {
            failure = error.localizedDescription
        }
    }
}

private struct ImportingOverlay: View {
    let name: String

    var body: some View {
        ZStack {
            Color.black.opacity(0.25).ignoresSafeArea()
            VStack(spacing: 12) {
                ProgressView()
                Text("Reading \(name)…").font(.callout)
                Text("This takes a few seconds for a whole Bible.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            .padding(24)
            .background(.regularMaterial, in: .rect(cornerRadius: 16))
        }
    }
}

/// What the import actually got. Shown always — a clean import is one line, and an incomplete one
/// says so rather than pretending.
private struct ImportSummaryView: View {
    let result: BibleImportResult
    @Environment(\.dismiss) private var dismiss

    private var gaps: [ImportCoverageReport.BookCoverage] {
        result.report.books.filter { !$0.isComplete }
    }


    /// Says what is actually absent. A book can be flagged with every chapter present — the WEB
    /// omits verses like Luke 17:36 — and "24 of 24 chapters" under a "Gaps" heading reads as a
    /// bug rather than as the truth.
    static func summary(of book: ImportCoverageReport.BookCoverage) -> String {
        if !book.missingChapters.isEmpty {
            return "\(book.chaptersFound) of \(book.chaptersExpected) chapters"
        }
        let refs = book.chaptersWithGaps.flatMap { chapter in
            chapter.missingVerses.map { "\(chapter.chapter):\($0)" }
        }
        if refs.isEmpty { return "\(book.versesFound) verses" }
        let shown = refs.prefix(4).joined(separator: ", ")
        let extra = refs.count - min(refs.count, 4)
        let tail = extra > 0 ? " and \(extra) more" : ""
        return "Not in this file: \(shown)\(tail)"
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LabeledContent("Translation", value: result.identity.name)
                    LabeledContent("Books", value: "\(result.report.books.count)")
                    LabeledContent("Verses", value: result.report.totalVerses.formatted())
                } footer: {
                    Text(result.identity.copyright)
                }

                if gaps.isEmpty && result.report.booksMissing.isEmpty {
                    Section {
                        Label("Every book read cleanly.", systemImage: "checkmark.circle")
                            .foregroundStyle(.green)
                    }
                } else {
                    Section {
                        ForEach(gaps) { book in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(book.book.name)
                                Text(Self.summary(of: book))
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        if !result.report.booksMissing.isEmpty {
                            Text("Not in this file: \(result.report.booksMissing.map(\.name).joined(separator: ", "))")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    } header: {
                        Text("Gaps")
                    } footer: {
                        Text("Some translations genuinely omit verses, and some files are simply incomplete. You can read what imported either way.")
                    }
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Added")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}

extension BibleImportResult: @retroactive Identifiable {
    public var id: String { identity.id }
}
