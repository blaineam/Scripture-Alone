import SwiftUI
import ScriptureAloneCore

/// Type "jn 3 16", "rom 8:28-39" or a word to search; or browse books and chapters, or topics.
///
/// Topics live here rather than in Study because this is where a reader comes looking for a
/// passage they don't yet have: Study follows the verse already on screen, and a topic starts from
/// what the reader is carrying instead. So the directory sits beside the books, and a search for
/// "anxious" offers the Anxiety topic above the verses that happen to use the word.
struct PassagePicker: View {
    @Environment(ReaderModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var results: [BibleStore.SearchHit] = []
    @State private var path = NavigationPath()
    @FocusState private var focused: Bool

    /// - Parameter initialQuery: words or a reference to start with — a search asked for by Siri,
    ///   Shortcuts or a `scripturealone://search` link.
    init(initialQuery: String = "") {
        _query = State(initialValue: initialQuery)
    }

    private var passage: Passage? { ReferenceParser.parse(query) }

    /// Life themes the words speak to — "anxious" is Anxiety. Only for words, not a reference.
    private var matchingThemes: [LifeTheme] {
        guard passage == nil else { return [] }
        return LifeThemeCatalog.shared.search(query, limit: 3)
    }

    /// A Nave's topic named exactly what was typed ("prayer", "Abraham"), for English readers.
    private var matchingIndexTopic: IndexTopic? {
        guard passage == nil, query.count >= 3 else { return nil }
        return TopicsLibrary.visibleIndex?.topic(named: query)
    }

    /// Three characters before a search runs — two in Chinese, Japanese and Korean, where a
    /// two-character word (恩典, 信心) is a whole word.
    static func minimumSearchLength(_ text: String) -> Int {
        text.unicodeScalars.contains { (0x3040...0x30FF).contains($0.value) || (0x3400...0x9FFF).contains($0.value)
            || (0xAC00...0xD7AF).contains($0.value) } ? 2 : 3
    }
    private var suggestedBooks: [BookID] {
        guard !query.isEmpty, query.rangeOfCharacter(from: .decimalDigits) == nil else { return [] }
        return Array(ReferenceParser.books(matching: query).prefix(6))
    }

    var body: some View {
        NavigationStack(path: $path) {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    if query.isEmpty {
                        recentSection
                        recentSearchesSection
                        topicsSection
                        booksSection(title: "Old Testament", books: BookID.allCases.filter { !$0.isNewTestament })
                        booksSection(title: "New Testament", books: BookID.allCases.filter(\.isNewTestament))
                    } else {
                        typedSection
                    }
                }
                .padding()
            }
            .safeAreaInset(edge: .top) { searchField }
            .navigationTitle("Go To")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
            .navigationDestination(for: BookID.self) { book in
                ChapterGrid(book: book) { chapter in
                    model.show(chapter)
                    dismiss()
                }
                .environment(model)
            }
            .navigationDestination(for: TopicsRoute.self) { route in
                topicsDestination(route)
            }
        }
        .task(id: query) { await search() }
        // A search asked for by Siri, Shortcuts or a `scripturealone://search` link.
        .onChange(of: AppCommandCenter.shared.searchQuery, initial: true) { _, words in
            guard let words else { return }
            query = words
            AppCommandCenter.shared.searchQuery = nil
        }
        .onAppear {
            #if DEBUG
            // The jump screenshot shows the book grid, not a keyboard.
            if ScreenshotScene.current == .jump { return }
            #endif
            focused = true
        }
    }

    private var searchField: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
            TextField("John 3:16, Rom 8, or search words", text: $query)
                .textFieldStyle(.plain)
                .focused($focused)
                .autocorrectionDisabled()
                #if os(iOS)
                .textInputAutocapitalization(.never)
                .submitLabel(.go)
                #endif
                .onSubmit(submit)
            if !query.isEmpty {
                Button { query = "" } label: { Image(systemName: "xmark.circle.fill") }
                    .buttonStyle(.plain)
                    .foregroundStyle(.secondary)
                    .accessibilityLabel("Clear")
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .glassEffect(.regular, in: .capsule)
        .padding(.horizontal)
        .padding(.bottom, 6)
    }

    // MARK: Sections

    @ViewBuilder
    private var recentSection: some View {
        if !model.recent.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                Text("Recent").font(.headline)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach(model.recent, id: \.self) { chapter in
                            Button(chapter.display) {
                                model.show(chapter)
                                dismiss()
                            }
                            .buttonStyle(.bordered)
                            .buttonBorderShape(.capsule)
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var recentSearchesSection: some View {
        if !model.recentSearches.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text("Recent Searches").font(.headline)
                    Spacer()
                    Button("Clear") { model.clearRecentSearches() }
                        .font(.subheadline)
                        .buttonStyle(.plain)
                        .foregroundStyle(.tint)
                }
                .padding(.bottom, 6)
                ForEach(model.recentSearches, id: \.self) { term in
                    Button { query = term } label: {
                        HStack(spacing: 10) {
                            Image(systemName: "clock.arrow.circlepath")
                                .foregroundStyle(.secondary)
                            Text(term).lineLimit(1)
                            Spacer()
                        }
                        .padding(.vertical, 9)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Search again for \(term)")
                    .contextMenu {
                        Button(role: .destructive) { model.forgetSearch(term) } label: {
                            Label("Remove", systemImage: "trash")
                        }
                    }
                    Divider()
                }
            }
        }
    }

    /// A few of the life themes to start from, and the way into the whole directory.
    private var topicsSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Topics", comment: "The directory of Bible passages by topic: its title, and its heading in Go To.").font(.headline)
                Spacer()
                NavigationLink(value: TopicsRoute.directory) {
                    Text("See All", comment: "Opens the whole Topics directory.")
                }
                .font(.subheadline)
                .buttonStyle(.plain)
                .foregroundStyle(.tint)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack {
                    ForEach(TopicsLibrary.featured.compactMap(LifeThemeCatalog.shared.theme(id:))) { theme in
                        NavigationLink(value: TopicsRoute.theme(theme.id)) {
                            Text(theme.localizedName)
                        }
                        .buttonStyle(.bordered)
                        .buttonBorderShape(.capsule)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func topicsDestination(_ route: TopicsRoute) -> some View {
        Group {
            switch route {
            case .directory:
                TopicsDirectoryView()
            case .theme(let id):
                if let theme = LifeThemeCatalog.shared.theme(id: id) {
                    LifeThemeView(theme: theme, onOpen: open)
                }
            case .indexTopic(let id):
                if let index = TopicsLibrary.index, let topic = index.topic(id: id) {
                    IndexTopicView(index: index, topic: topic, onOpen: open)
                }
            }
        }
        .environment(model)
    }

    /// A passage chosen in a topic: a KJV-keyed range, so the reader lands on its own verse.
    private func open(_ range: VerseRange) {
        model.go(to: range.start)
        dismiss()
    }

    /// "Topic: Anxiety & Worry" — offered above the verses when the words name a topic.
    @ViewBuilder
    private var topicMatches: some View {
        let themes = matchingThemes
        if !themes.isEmpty || matchingIndexTopic != nil {
            VStack(spacing: 8) {
                ForEach(themes) { theme in
                    NavigationLink(value: TopicsRoute.theme(theme.id)) {
                        topicCard(title: theme.localizedName, detail: theme.localizedDescription)
                    }
                    .buttonStyle(.plain)
                }
                if let topic = matchingIndexTopic {
                    NavigationLink(value: TopicsRoute.indexTopic(topic.id)) {
                        topicCard(title: topic.name, detail: TopicsLibrary.index?.name ?? "")
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private func topicCard(title: String, detail: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "text.book.closed")
                .font(.title3)
                .foregroundStyle(.tint)
            VStack(alignment: .leading, spacing: 2) {
                Text("Topic", comment: "Small label over a topic offered in Go To's search results.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(title).font(.headline)
                if !detail.isEmpty {
                    Text(detail)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                }
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right").foregroundStyle(.tertiary)
        }
        .padding()
        .background(.tint.opacity(0.12), in: .rect(cornerRadius: 16))
        .contentShape(.rect(cornerRadius: 16))
    }

    private func booksSection(title: LocalizedStringKey, books: [BookID]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.headline)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 86), spacing: 8)], spacing: 8) {
                ForEach(books) { book in
                    NavigationLink(value: book) {
                        BookTile(book: book)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    @ViewBuilder
    private var typedSection: some View {
        if let passage {
            Button(action: submit) {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Go to").font(.caption).foregroundStyle(.secondary)
                        Text(passage.clamped.display).font(.title3.weight(.semibold))
                    }
                    Spacer()
                    Image(systemName: "return").foregroundStyle(.secondary)
                }
                .padding()
                .background(.tint.opacity(0.12), in: .rect(cornerRadius: 16))
            }
            .buttonStyle(.plain)
        }
        topicMatches
        if !suggestedBooks.isEmpty {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 86), spacing: 8)], spacing: 8) {
                ForEach(suggestedBooks) { book in
                    NavigationLink(value: book) { BookTile(book: book) }.buttonStyle(.plain)
                }
            }
        }
        if !results.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                Text(results.count >= 300 ? "300+ verses" : "\(results.count) verses")
                    .font(.headline)
                    .padding(.bottom, 8)
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(results) { hit in
                        Button {
                            model.rememberSearch(query)
                            model.go(to: hit.kjv)   // the KJV key; the reader lands on its own verse
                            dismiss()
                        } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(hit.ref.display).font(.subheadline.weight(.semibold)).foregroundStyle(.tint)
                                Text(emphasized(hit.text)).font(.callout).multilineTextAlignment(.leading)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.vertical, 10)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        Divider()
                    }
                }
            }
        } else if passage == nil, suggestedBooks.isEmpty, matchingThemes.isEmpty, matchingIndexTopic == nil,
                  query.count >= Self.minimumSearchLength(query) {
            ContentUnavailableView.search(text: query)
        }
    }

    // MARK: Actions

    private func submit() {
        if let passage {
            model.go(to: passage)
            dismiss()
        } else if let first = results.first {
            model.rememberSearch(query)
            model.go(to: first.ref)
            dismiss()
        }
    }

    private func search() async {
        let text = query
        // A reference ("john", "ps 23") navigates; anything else searches the text.
        guard text.count >= Self.minimumSearchLength(text), ReferenceParser.parse(text) == nil else {
            results = []
            return
        }
        try? await Task.sleep(for: .milliseconds(180))
        guard !Task.isCancelled else { return }
        let hits = await model.search(text)
        guard !Task.isCancelled else { return }
        results = hits
    }

    private func emphasized(_ text: String) -> AttributedString {
        var attributed = AttributedString(text)
        let words = query.lowercased().split(whereSeparator: { !$0.isLetter && $0 != "'" }).map(String.init)
        for word in words where word.count >= 2 {
            var searchRange = attributed.startIndex..<attributed.endIndex
            while let found = attributed[searchRange].range(of: word, options: [.caseInsensitive, .diacriticInsensitive]) {
                attributed[found].font = .callout.weight(.bold)
                searchRange = found.upperBound..<attributed.endIndex
            }
        }
        return attributed
    }
}

private struct BookTile: View {
    let book: BookID

    var body: some View {
        VStack(spacing: 2) {
            Text(book.abbreviation).font(.headline)
            Text(book.name).font(.caption2).foregroundStyle(.secondary).lineLimit(1).minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity, minHeight: 54)
        .background(tint.opacity(0.13), in: .rect(cornerRadius: 12))
        .overlay(alignment: .leading) {
            RoundedRectangle(cornerRadius: 2).fill(tint).frame(width: 3).padding(.vertical, 10)
        }
        .contentShape(.rect(cornerRadius: 12))
        .accessibilityLabel(book.name)
    }

    private var tint: Color {
        switch book.info.group {
        case .law: .brown
        case .history: .orange
        case .wisdom: .purple
        case .majorProphets: .red
        case .minorProphets: .pink
        case .gospels: .blue
        case .paul: .teal
        case .general: .green
        case .prophecy: .indigo
        }
    }
}

private struct ChapterGrid: View {
    @Environment(ReaderModel.self) private var model
    let book: BookID
    let onPick: (ChapterRef) -> Void

    var body: some View {
        ScrollView {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 52), spacing: 8)], spacing: 8) {
                ForEach(1...book.chapterCount, id: \.self) { chapter in
                    let ref = ChapterRef(book, chapter)
                    Button { onPick(ref) } label: {
                        Text("\(chapter)")
                            .font(.body.monospacedDigit().weight(.medium))
                            .frame(maxWidth: .infinity, minHeight: 48)
                            .background(ref == model.location ? AnyShapeStyle(.tint.opacity(0.25)) : AnyShapeStyle(.fill.tertiary),
                                        in: .rect(cornerRadius: 10))
                            .contentShape(.rect(cornerRadius: 10))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(book.name) chapter \(chapter)")
                }
            }
            .padding()
        }
        .navigationTitle(book.name)
    }
}
