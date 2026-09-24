import SwiftUI
import UniformTypeIdentifiers
import ScriptureAloneCore

/// Every translation on the device, and the two ways to add one.
struct TranslationsView: View {
    @Environment(ReaderModel.self) private var model
    @Environment(ImportedLibrary.self) private var library
    @Environment(OnlineTranslationKeys.self) private var onlineKeys
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
                            row(name: entry.name, abbreviation: entry.id, note: String(localized: "Read over the network", comment: "Note under an online translation's name"))
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
                                // Swiping isn't discoverable, and a Mac has no swipe without a
                                // trackpad: the same action on a long press or right-click.
                                .contextMenu {
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
                    Text("Free translations come from eBible.org, and nothing is downloaded until you choose one. A file can be a USFM zip, an ePub or a PDF you own — anything copy-protected is refused. Translations that can't be given away are read over the network with your own free key.")
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
                .environment(onlineKeys)
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
                      allowedContentTypes: [.zip, .epub, .pdf, UTType(filenameExtension: "usfm") ?? .data]) { result in
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
                Text("A whole Bible can take a minute or two, longer with study notes or from a PDF.")
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

    /// Books with real gaps. Verses the translation itself leaves out are listed on their own,
    /// not as damage (`ImportCoverageReport.textualVariants`).
    private var gaps: [ImportCoverageReport.BookCoverage] {
        result.report.booksWithRealGaps
    }


    /// Says what is actually absent. A book can be flagged with every chapter present — a translation
    /// may omit a verse like Luke 17:36 — and "24 of 24 chapters" under a "Gaps" heading reads as a
    /// bug rather than as the truth.
    static func summary(of book: ImportCoverageReport.BookCoverage) -> String {
        if !book.missingChapters.isEmpty {
            return String(localized: "\(book.chaptersFound) of \(book.chaptersExpected) chapters", comment: "Import coverage for a book. Chapters found, then chapters expected.")
        }
        let refs = book.chaptersWithGaps.flatMap { chapter in
            chapter.missingVerses.map { "\(chapter.chapter):\($0)" }
        }
        if refs.isEmpty { return String(localized: "\(book.versesFound) verses", comment: "Import coverage for a book. %lld is a number of verses.") }
        let shown = refs.prefix(4).joined(separator: ", ")
        let extra = refs.count - min(refs.count, 4)
        if extra > 0 {
            return String(localized: "Not in this file: \(shown) and \(extra) more", comment: "Import coverage. %1$@ is a list of verse references like “17:36, 23:17”; %2$lld is how many more are missing.")
        }
        return String(localized: "Not in this file: \(shown)", comment: "Import coverage. %@ is a list of verse references like “17:36, 23:17”.")
    }

    /// Which publisher's terms copying and sharing will follow, so a reader can see the importer
    /// recognised the translation — or that it didn't, and the cautious default applies.
    private var quotingTerms: String {
        let info = TranslationInfo(id: result.identity.id, name: result.identity.name,
                                   abbreviation: result.identity.abbreviation,
                                   copyright: result.identity.copyright, license: result.identity.license)
        if info.isPublicDomain { return String(localized: "Public domain — no limit", comment: "Import summary: an imported translation that needs no permission to quote.") }
        guard let terms = info.publisherTerms else {
            return String(localized: "Not recognised — up to \(info.rights.maxQuotationVerses) verses", comment: "Import summary: the translation's publisher terms weren't recognised, so a cautious verse limit applies. %lld is a number of verses.")
        }
        if let limit = terms.maxVerses {
            return String(localized: "Publisher’s terms — up to \(limit) verses", comment: "Import summary: quoting follows the publisher's own published terms. %lld is a number of verses.")
        }
        _ = terms
        return String(localized: "Publisher’s terms — no verse limit", comment: "Import summary: quoting follows the publisher's own published terms, which set no verse count.")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LabeledContent("Translation", value: result.identity.name)
                    LabeledContent("Books", value: "\(result.report.books.count)")
                    LabeledContent("Verses", value: result.report.totalVerses.formatted())
                    LabeledContent("Quoting", value: quotingTerms)
                } footer: {
                    Text(result.identity.copyright)
                }

                let omitted = result.report.omittedByTranslation
                if !omitted.isEmpty {
                    Section {
                        ForEach(omitted, id: \.book) { entry in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(entry.book.name)
                                Text(verbatim: entry.verses.map { "\($0.chapter):\($0.verse)" }.joined(separator: ", "))
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    } header: {
                        Text("Left Out by This Translation")
                    } footer: {
                        Text("These verses aren’t in the oldest manuscripts. Most modern translations print them only as footnotes, so nothing is missing from your file.")
                    }
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
