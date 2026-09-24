import SwiftUI
import ScriptureAloneCore

/// The Study mode tabs. `.context` (maps, timelines, charts) is drawn by `StudyContextView`.
enum StudyTab: String, CaseIterable, Identifiable {
    case crossReferences, commentary, context

    var id: String { rawValue }

    /// The tabs this reader gets: the bundled commentary is English-only, so it is offered only in
    /// English (`StudyLanguage`) — unless the reader imported study material of their own, which is
    /// in whatever language they chose.
    @MainActor static var available: [StudyTab] {
        StudyLanguage.isEnglish || !ImportedStudyLibrary.shared.isEmpty ? allCases : allCases.filter { $0 != .commentary }
    }

    var title: String {
        switch self {
        case .crossReferences: String(localized: "Cross References", comment: "Study panel tab")
        case .commentary: String(localized: "Commentary", comment: "Study panel tab")
        case .context: String(localized: "Context", comment: "Study panel tab")
        }
    }

    var shortTitle: String {
        switch self {
        case .crossReferences: String(localized: "References", comment: "Study panel tab, short form for narrow screens")
        case .commentary: String(localized: "Commentary", comment: "Study panel tab, short form for narrow screens")
        case .context: String(localized: "Context", comment: "Study panel tab, short form for narrow screens")
        }
    }

    var systemImage: String {
        switch self {
        case .crossReferences: "arrow.triangle.branch"
        case .commentary: "text.book.closed"
        case .context: "map"
        }
    }
}

/// Study content for the verse the reader tapped last. Sits beside the text on iPad and Mac
/// (an inspector) and in a resizable sheet on iPhone, which leaves the text tappable behind it.
struct StudyPanel: View {
    @Environment(ReaderModel.self) private var model
    @Environment(StudyModel.self) private var study
    /// As a sheet (iPhone) over the reader. The reader's selection bar stays beneath it: nothing of
    /// the reader's floats over the commentary or the maps.
    var isSheet = false

    @State private var detent: PresentationDetent = .fraction(0.45)
    @State private var showSources = false

    var body: some View {
        @Bindable var study = study
        NavigationStack {
            VStack(spacing: 0) {
                header
                Picker("Study", selection: $study.tab) {
                    ForEach(StudyTab.available) { tab in
                        Text(tab.shortTitle).tag(tab)
                    }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .padding(.horizontal)
                .padding(.bottom, 8)
                Divider()
                content
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .navigationTitle("Study")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button { showSources = true } label: { Label("About Study Resources", systemImage: "info.circle") }
                }
            }
            .navigationDestination(isPresented: $showSources) {
                StudySourcesView()
                    .environment(model)
                    .environment(study)
            }
        }
        .environment(\.openURL, OpenURLAction { url in
            guard let range = StudyLink.range(from: url) else { return .systemAction }
            study.jump(to: range, reader: model)
            return .handled
        })
        #if os(iOS)
        .presentationDetents([.fraction(0.45), .large], selection: $detent)
        .presentationBackgroundInteraction(.enabled(upThrough: .fraction(0.45)))
        .presentationContentInteraction(.scrolls)
        #endif
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            if let previous = study.history.last {
                Button { study.back(reader: model) } label: {
                    Label(previous.display, systemImage: "chevron.backward")
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("Back to \(previous.display)")
                .keyboardShortcut("[", modifiers: [.command, .option])
            }
            Spacer(minLength: 0)
            if let verse = study.verse {
                Text(verse.display)
                    .font(.headline)
                    .lineLimit(1)
                    .accessibilityAddTraits(.isHeader)
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 10)
    }

    /// Commentary is an on-demand pack; cross references and context are in the app, so only the
    /// Commentary tab waits on a download.
    @ViewBuilder
    private var commentaryDownload: some View {
        switch study.downloadState {
        case .downloading(let fraction):
            VStack(spacing: 12) {
                ProgressView(value: fraction).frame(maxWidth: 220)
                Text("Downloading \(AssetPack.commentary.title)…").font(.callout)
                Text(AssetPack.commentary.explanation)
                    .font(.caption).foregroundStyle(.secondary).multilineTextAlignment(.center)
            }
            .padding()
        case .failed(let message):
            ContentUnavailableView {
                Label("Couldn't Download Commentary", systemImage: "exclamationmark.triangle")
            } description: {
                Text(message)
            } actions: {
                Button("Try Again") { Task { await study.prepareStore() } }
            }
        default:
            ContentUnavailableView {
                Label(AssetPack.commentary.title, systemImage: "arrow.down.circle")
            } description: {
                Text(AssetPack.commentary.explanation)
            } actions: {
                Button("Download") { Task { await study.prepareStore() } }
            }
        }
    }

    /// The commentary pack offered in a single row, above an imported study Bible's notes.
    private var commentaryDownloadBar: some View {
        HStack(spacing: 10) {
            Image(systemName: "arrow.down.circle").foregroundStyle(.secondary)
            Text(AssetPack.commentary.title).font(.callout)
            Spacer(minLength: 8)
            switch study.downloadState {
            case .downloading(let fraction):
                ProgressView(value: fraction).frame(width: 90)
            case .failed:
                Button("Try Again") { Task { await study.prepareStore() } }
                    .buttonStyle(.bordered).controlSize(.small)
            default:
                Button("Download") { Task { await study.prepareStore() } }
                    .buttonStyle(.bordered).controlSize(.small)
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
        .background(.quaternary.opacity(0.5))
    }

    @ViewBuilder
    private var content: some View {
        if study.tab == .commentary, study.store == nil, ImportedStudyLibrary.shared.isEmpty {
            commentaryDownload
        } else if study.tab == .context {
            StudyContextView(chapter: study.verse?.chapterKey ?? model.location, verse: study.verse?.verse)
        } else if let verse = study.verse {
            switch study.tab {
            case .crossReferences: CrossReferencesView(verse: verse)
            case .commentary:
                VStack(spacing: 0) {
                    // An imported study Bible fills this tab on its own; the bundled commentators
                    // are still offered beside it until they are downloaded.
                    if StudyLanguage.isEnglish, study.store == nil { commentaryDownloadBar }
                    CommentaryView(verse: verse)
                }
            case .context: EmptyView()
            }
        } else {
            ContentUnavailableView {
                Label("Tap a Verse", systemImage: "hand.tap")
            } description: {
                Text("Tap any verse to see where else Scripture speaks to it, and what the old commentators said about it.")
            }
        }
    }
}

/// Links inside study content: `scripturealone-study://passage/43003016-43003018`.
enum StudyLink {
    static let scheme = "scripturealone-study"

    static func url(for range: VerseRange) -> URL? {
        URL(string: "\(scheme)://passage/\(range.storageString)")
    }

    static func range(from url: URL) -> VerseRange? {
        guard url.scheme == scheme, url.host() == "passage" else { return nil }
        return VerseRange(storageString: url.lastPathComponent)
    }
}
