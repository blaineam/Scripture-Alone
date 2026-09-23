import SwiftUI
import ScriptureAloneCore

/// Type "jn 3 16", "rom 8:28-39" or a word to search; or browse books and chapters.
struct PassagePicker: View {
    @Environment(ReaderModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var results: [BibleStore.SearchHit] = []
    @State private var path: [BookID] = []
    @FocusState private var focused: Bool

    private var passage: Passage? { ReferenceParser.parse(query) }
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
        }
        .task(id: query) { await search() }
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

    private func booksSection(title: String, books: [BookID]) -> some View {
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
        if !suggestedBooks.isEmpty {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 86), spacing: 8)], spacing: 8) {
                ForEach(suggestedBooks) { book in
                    NavigationLink(value: book) { BookTile(book: book) }.buttonStyle(.plain)
                }
            }
        }
        if !results.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                Text(results.count >= 300 ? "300+ verses" : "\(results.count) verse\(results.count == 1 ? "" : "s")")
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
        } else if passage == nil, suggestedBooks.isEmpty, query.count >= 3 {
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
        guard text.count >= 3, ReferenceParser.parse(text) == nil else {
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
