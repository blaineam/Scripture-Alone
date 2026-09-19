import SwiftUI
import ScriptureAloneCore

/// The Study mode tabs. `.context` (maps, timelines, charts) is drawn by `StudyContextView`.
enum StudyTab: String, CaseIterable, Identifiable {
    case crossReferences, commentary, context

    var id: String { rawValue }

    var title: String {
        switch self {
        case .crossReferences: "Cross References"
        case .commentary: "Commentary"
        case .context: "Context"
        }
    }

    var shortTitle: String {
        switch self {
        case .crossReferences: "References"
        case .commentary: "Commentary"
        case .context: "Context"
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
    /// As a sheet (iPhone) the panel covers the reader's selection bar, so it carries its own.
    var isSheet = false
    let onNote: () -> Void

    @State private var detent: PresentationDetent = .fraction(0.45)
    @State private var showSources = false

    var body: some View {
        @Bindable var study = study
        NavigationStack {
            VStack(spacing: 0) {
                header
                Picker("Study", selection: $study.tab) {
                    ForEach(StudyTab.allCases) { tab in
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
            }
            // An overlay, not a safe-area inset: the bar's natural width is wider than an
            // iPhone, and an inset would widen the whole panel to fit it.
            .overlay(alignment: .bottom) {
                if isSheet && !model.selection.isEmpty {
                    SelectionBar(onNote: onNote)
                        .padding(.horizontal)
                        .padding(.bottom, 8)
                }
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

    @ViewBuilder
    private var content: some View {
        if study.store == nil {
            ContentUnavailableView("Study Resources Missing", systemImage: "exclamationmark.triangle",
                                   description: Text("This build doesn’t include the study database."))
        } else if study.tab == .context {
            StudyContextView(chapter: study.verse?.chapterKey ?? model.location, verse: study.verse?.verse)
        } else if let verse = study.verse {
            switch study.tab {
            case .crossReferences: CrossReferencesView(verse: verse)
            case .commentary: CommentaryView(verse: verse)
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
