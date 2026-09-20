import SwiftUI
import ScriptureAloneCore

/// The free translations eBible.org publishes, with the reader's own languages first.
struct CatalogView: View {
    /// Called with the downloaded zip; the caller imports it and reports the result.
    let onPick: (CatalogTranslation, URL) async -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var state = LoadState.idle
    @State private var query = ""
    @State private var showEverything = false
    @State private var downloading: CatalogTranslation?
    @State private var progress: Double?

    private enum LoadState {
        case idle, loading
        case loaded(mine: [CatalogTranslation], other: [CatalogTranslation])
        case failed(String)
    }

    var body: some View {
        NavigationStack {
            content
                .navigationTitle("Free Translations")
                #if os(iOS)
                .navigationBarTitleDisplayMode(.inline)
                #endif
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                }
                .task { if case .idle = state { await load() } }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch state {
        case .idle, .loading:
            ProgressView("Asking eBible.org…")
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .failed(let message):
            ContentUnavailableView {
                Label("Couldn't reach eBible.org", systemImage: "wifi.exclamationmark")
            } description: {
                Text(message)
            } actions: {
                Button("Try Again") { Task { await load() } }
            }
        case .loaded(let mine, let other):
            list(mine: mine, other: other)
        }
    }

    private func list(mine: [CatalogTranslation], other: [CatalogTranslation]) -> some View {
        List {
            let searching = !query.trimmingCharacters(in: .whitespaces).isEmpty
            if searching {
                Section("Results") {
                    ForEach(matches(in: mine + other)) { row($0) }
                }
            } else {
                if !mine.isEmpty {
                    Section {
                        ForEach(mine) { row($0) }
                    } header: {
                        Text("Offered by Scripture Alone")
                    } footer: {
                        Text("Complete Bibles translated from the Hebrew and Greek.")
                    }
                }
                Section {
                    if showEverything {
                        ForEach(other) { row($0) }
                    } else {
                        Button("Other Languages (\(other.count.formatted()))") { showEverything = true }
                    }
                } header: {
                    Text("From eBible.org")
                } footer: {
                    Text("Complete Bibles in other languages, published by eBible.org. Scripture Alone doesn't vouch for these — nobody here reads every language — so read the publisher's own note before relying on one.")
                }
            }
        }
        .searchable(text: $query, prompt: "Language or name")
        .overlay { if let downloading { DownloadOverlay(name: downloading.title, progress: progress) } }
    }

    private func matches(in entries: [CatalogTranslation]) -> [CatalogTranslation] {
        let needle = query.trimmingCharacters(in: .whitespaces)
        return entries.filter {
            $0.title.localizedCaseInsensitiveContains(needle)
                || $0.languageName.localizedCaseInsensitiveContains(needle)
                || $0.languageNameInEnglish.localizedCaseInsensitiveContains(needle)
                || $0.shortTitle.localizedCaseInsensitiveContains(needle)
        }
    }

    private func row(_ translation: CatalogTranslation) -> some View {
        Button {
            Task { await download(translation) }
        } label: {
            VStack(alignment: .leading, spacing: 3) {
                Text(translation.title).foregroundStyle(.primary)
                Text("\(translation.languageNameInEnglish) · \(translation.scope)")
                    .font(.caption).foregroundStyle(.secondary)
                Text(translation.copyright)
                    .font(.caption2).foregroundStyle(.secondary).lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(downloading != nil)
    }

    private func load() async {
        state = .loading
        do {
            // Two lists, because they carry different promises. English is an allowlist the app
            // stands behind; everything else is every complete Bible eBible publishes, offered
            // without a judgement nobody here is qualified to make.
            let all = try await EBibleCatalog().fetch()
            let curated = all.filter(CatalogCuration.isCurated)
            let rest = all.filter(CatalogCuration.isUncurated)
            state = .loaded(mine: CatalogLanguageMatch.ordered(curated),
                            other: CatalogLanguageMatch.ordered(rest))
        } catch {
            state = .failed(error.localizedDescription)
        }
    }

    private func download(_ translation: CatalogTranslation) async {
        downloading = translation
        progress = nil
        defer { downloading = nil; progress = nil }
        do {
            let url = try await CatalogDownloader().download(translation) { fraction in
                Task { @MainActor in progress = fraction }
            }
            defer { try? FileManager.default.removeItem(at: url) }
            await onPick(translation, url)
            dismiss()
        } catch {
            state = .failed(error.localizedDescription)
        }
    }
}

private struct DownloadOverlay: View {
    let name: String
    let progress: Double?

    var body: some View {
        ZStack {
            Color.black.opacity(0.25).ignoresSafeArea()
            VStack(spacing: 12) {
                if let progress {
                    ProgressView(value: progress).frame(width: 180)
                } else {
                    ProgressView()
                }
                Text("Downloading \(name)…").font(.callout).multilineTextAlignment(.center)
            }
            .padding(24)
            .background(.regularMaterial, in: .rect(cornerRadius: 16))
            .padding()
        }
    }
}
