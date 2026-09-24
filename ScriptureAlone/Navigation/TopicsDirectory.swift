import SwiftUI
import SwiftData
import ScriptureAloneCore

/// Where the Go To sheet can go besides a book's chapters: the Topics directory, a life theme,
/// a topic in Nave's Topical Bible.
enum TopicsRoute: Hashable {
    case directory
    case theme(String)
    case indexTopic(Int)
}

/// The two halves of the Topics directory.
///
/// The life themes (`LifeThemeCatalog`) are for everyone, in every language: their passages are
/// references, drawn in whatever translation is being read. Nave's Topical Bible is English prose
/// about English words, so like the commentaries it is shown only to readers using the app in
/// English (`StudyLanguage`).
enum TopicsLibrary {
    /// Opened on first use: 1.2 MB, 5,300 topic names read up front for listing and search.
    static let index: TopicalIndex? =
        Bundle.main.url(forResource: "Topics", withExtension: "sqlite").flatMap { try? TopicalIndex(url: $0) }

    /// Nave's, when the reader should see it.
    static var visibleIndex: TopicalIndex? { StudyLanguage.isEnglish ? index : nil }

    /// Nave's topics under their first letter, for the A–Z list.
    static let lettered: [(letter: String, topics: [IndexTopic])] = {
        guard let index else { return [] }
        let grouped = Dictionary(grouping: index.topics) { $0.initial }
        return grouped.keys.sorted().map { ($0, grouped[$0] ?? []) }
    }()

    /// The themes offered first in Go To, before anyone has typed: the things people most often
    /// come to Scripture carrying.
    static let featured = ["anxiety", "fear", "grief", "loneliness", "depression", "guilt", "peace", "hope", "strength", "guidance"]

    static func symbol(for group: String) -> String {
        switch group {
        case "feelings": "cloud.rain"
        case "suffering": "bandage"
        case "sin": "arrow.uturn.backward.circle"
        case "relationships": "person.2"
        case "life": "briefcase"
        case "growing": "leaf"
        default: "sun.max"
        }
    }
}

/// Life themes, grouped, then (in English) Nave's Topical Bible from A to Z, with a search over
/// both: theme names, the words people use for them ("worried", "burned out"), and Nave's topics.
struct TopicsDirectoryView: View {
    @State private var query = ""
    private let catalog = LifeThemeCatalog.shared

    var body: some View {
        List {
            if TopicSearch.normalize(query).isEmpty {
                themeSections
                indexSections
            } else {
                searchResults
            }
        }
        #if os(iOS)
        .listStyle(.insetGrouped)
        .listSectionIndexVisibility(.visible)
        #else
        .listStyle(.inset)
        #endif
        .searchable(text: $query, prompt: Text("Search topics", comment: "Placeholder in the Topics directory's search field."))
        .autocorrectionDisabled()
        .navigationTitle(Text("Topics", comment: "The directory of Bible passages by topic: its title, and its heading in Go To."))
    }

    @ViewBuilder
    private var themeSections: some View {
        ForEach(catalog.groups) { group in
            Section {
                ForEach(catalog.themes(in: group)) { theme in
                    NavigationLink(value: TopicsRoute.theme(theme.id)) { LifeThemeRow(theme: theme) }
                }
            } header: {
                Label(group.localizedName, systemImage: TopicsLibrary.symbol(for: group.id))
            }
        }
    }

    @ViewBuilder
    private var indexSections: some View {
        if let index = TopicsLibrary.visibleIndex {
            Section {
                Text("Every topic in Nave’s Topical Bible, from Aaron to Zuzims, with the passages it lists.",
                     comment: "Introduces the A–Z topic index. “Aaron” and “Zuzims” are its first and last topics.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            } header: {
                Text(index.name)
            }
            ForEach(TopicsLibrary.lettered, id: \.letter) { group in
                Section(group.letter) {
                    ForEach(group.topics) { topic in
                        NavigationLink(topic.name, value: TopicsRoute.indexTopic(topic.id))
                    }
                }
                .sectionIndexLabel(group.letter)
            }
        }
    }

    @ViewBuilder
    private var searchResults: some View {
        let crisis = CrisisSupport.isCrisis(query)
        // The crisis card stands alone: "want to die" would otherwise also list Death & Dying.
        let themes = crisis ? [] : catalog.search(query, limit: catalog.themes.count)
        let topics = crisis ? [] : TopicsLibrary.visibleIndex?.search(query, limit: 60) ?? []
        if crisis {
            Section { CrisisCard().listRowInsets(EdgeInsets()) }
        }
        if themes.isEmpty, topics.isEmpty, !crisis {
            ContentUnavailableView.search(text: query)
        }
        if !themes.isEmpty {
            Section {
                ForEach(themes) { theme in
                    NavigationLink(value: TopicsRoute.theme(theme.id)) { LifeThemeRow(theme: theme) }
                }
            } header: {
                Text("Life Topics", comment: "Heading over curated topics (anxiety, grief…) in the Topics directory's search results.")
            }
        }
        if let index = TopicsLibrary.visibleIndex, !topics.isEmpty {
            Section(index.name) {
                ForEach(topics) { topic in
                    NavigationLink(topic.name, value: TopicsRoute.indexTopic(topic.id))
                }
            }
        }
    }
}

/// A life theme's name over its one line of description.
struct LifeThemeRow: View {
    let theme: LifeTheme

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(theme.localizedName).font(.body.weight(.medium))
            Text(theme.localizedDescription)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(2)
        }
        .padding(.vertical, 2)
    }
}

/// A life theme: each passage in the translation being read, one tap from the reader.
struct LifeThemeView: View {
    @Environment(ReaderModel.self) private var model
    @Environment(\.modelContext) private var context
    @Query private var favorites: [Favorite]
    let theme: LifeTheme
    let onOpen: (VerseRange) -> Void

    @State private var texts: [VerseRange: AttributedString] = [:]
    /// The translation `texts` were read from, once they all have been.
    @State private var loadedFor: String?

    var body: some View {
        List {
            Section {
                Text(theme.localizedDescription)
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            Section {
                ForEach(theme.passages, id: \.self) { range in
                    passageRow(range)
                }
            } footer: {
                Text("Passages from the \(model.translationAbbreviation).",
                     comment: "Under a topic's passages. %@ is a translation abbreviation, e.g. “KJV”.")
            }
            if let index = TopicsLibrary.visibleIndex {
                let related = theme.naveTopics.compactMap(index.topic(named:))
                if !related.isEmpty {
                    Section {
                        ForEach(related) { topic in
                            NavigationLink(topic.name, value: TopicsRoute.indexTopic(topic.id))
                        }
                    } header: {
                        Text("More in \(index.name)", comment: "Heading over related topics. %@ is “Nave’s Topical Bible”.")
                    }
                }
            }
        }
        #if os(iOS)
        .listStyle(.insetGrouped)
        #else
        .listStyle(.inset)
        #endif
        .navigationTitle(theme.localizedName)
        .task(id: model.translationID) { await load() }
    }

    private func passageRow(_ range: VerseRange) -> some View {
        let reference = model.displayRange(range).display
        let text = texts[range]
        return Button { onOpen(range) } label: {
            VStack(alignment: .leading, spacing: 5) {
                Text(reference)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.tint)
                if let text {
                    Text(text)
                        .font(.system(.body, design: .serif))
                        .multilineTextAlignment(.leading)
                } else if loadedFor == model.translationID {
                    Text("Couldn’t load this passage.", comment: "In a topic, when a passage's text couldn't be read or fetched.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                } else {
                    ProgressView().controlSize(.small)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 3)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityHint(Text("Opens \(reference) in the reader", comment: "Accessibility hint. %@ is a Bible reference."))
        .contextMenu { passageMenu(range, reference: reference) }
    }

    /// What a selection offers, for one passage: the same favorites, and copying and sharing under
    /// the same publisher's terms (`ReaderModel.quotation(for:)` refuses what they don't allow).
    @ViewBuilder
    private func passageMenu(_ range: VerseRange, reference: String) -> some View {
        Button { onOpen(range) } label: {
            Label { Text("Go to \(reference)", comment: "Menu item. %@ is a Bible reference.") } icon: { Image(systemName: "arrow.right") }
        }
        let favorite = favorites.contains { $0.rangeRaw == range.storageString }
        Button { toggleFavorite(range) } label: {
            favorite ? Label("Remove from Favorites", systemImage: "heart.slash") : Label("Add to Favorites", systemImage: "heart")
        }
        let quotation = model.quotation(for: [range])
        if !quotation.isEmpty {
            if model.rights.permits(\.allowCopy) {
                Button { copy(quotation) } label: { Label("Copy", systemImage: "doc.on.doc") }
            }
            if model.rights.permits(\.allowShare) {
                ShareLink(item: quotation) { Label("Share Text", systemImage: "text.quote") }
            }
        }
    }

    private func toggleFavorite(_ range: VerseRange) {
        let matching = favorites.filter { $0.rangeRaw == range.storageString }
        if matching.isEmpty {
            context.insert(Favorite(range: range))
        } else {
            matching.forEach(context.delete)
        }
    }

    private func copy(_ text: String) {
        #if os(iOS)
        UIPasteboard.general.string = text
        #else
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        #endif
    }

    /// The verses run together, each after its number in small raised type — as the reader sets
    /// them — unless there is only one.
    static func numbered(_ verses: [VerseText]) -> AttributedString {
        guard verses.count > 1 else { return AttributedString(verses.first?.text ?? "") }
        var result = AttributedString()
        for (i, verse) in verses.enumerated() {
            var number = AttributedString((i > 0 ? " " : "") + "\(verse.ref.verse)\u{2009}")
            number.font = .system(.caption2, design: .serif)
            number.baselineOffset = 5
            number.foregroundColor = .secondary
            result += number
            result += AttributedString(verse.text)
        }
        return result
    }

    private func load() async {
        let translation = model.translationID
        if loadedFor != translation { texts = [:] }
        for range in theme.passages where texts[range] == nil {
            guard !Task.isCancelled else { return }
            if let verses = await model.passageVerses(range) { texts[range] = Self.numbered(verses) }
        }
        guard !Task.isCancelled else { return }
        loadedFor = translation
    }
}

/// A topic in Nave's Topical Bible: its lines, each with its passages as links, and the topics
/// it sends the reader on to.
struct IndexTopicView: View {
    @Environment(ReaderModel.self) private var model
    let index: TopicalIndex
    let topic: IndexTopic
    let onOpen: (VerseRange) -> Void

    @State private var entries: [IndexEntry]?

    /// The scheme of the links inside a line of passages; never leaves this view.
    private static let linkScheme = "sa-topic-passage"

    var body: some View {
        List {
            if let entries {
                ForEach(entries) { entry in
                    if !entry.label.isEmpty || !entry.passages.isEmpty {
                        line(entry)
                    }
                    ForEach(entry.seeAlso) { other in
                        NavigationLink(value: TopicsRoute.indexTopic(other.id)) {
                            Label {
                                Text("See \(other.name)", comment: "A link to another topic in the topic index. %@ is its name.")
                            } icon: {
                                Image(systemName: "arrow.turn.down.right")
                            }
                            .foregroundStyle(.tint)
                        }
                        .padding(.leading, entry.level == 1 ? 18 : 0)
                    }
                }
                Section {
                    Text(index.attribution)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
        }
        #if os(iOS)
        .listStyle(.insetGrouped)
        #else
        .listStyle(.inset)
        #endif
        .navigationTitle(topic.name)
        .environment(\.openURL, OpenURLAction { url in
            guard url.scheme == Self.linkScheme, let range = VerseRange(storageString: url.host() ?? "") else {
                return .systemAction
            }
            onOpen(range)
            return .handled
        })
        .task(id: topic.id) {
            entries = (try? index.entries(for: topic)) ?? []
        }
    }

    private func line(_ entry: IndexEntry) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            if !entry.label.isEmpty {
                Text(entry.label)
                    .font(entry.level == 0 ? .body.weight(.medium) : .callout)
            }
            if !entry.passages.isEmpty {
                Text(links(entry.passages))
                    .font(.callout)
                    .tint(.accentColor)
            }
        }
        .padding(.leading, entry.level == 1 ? 18 : 0)
        .padding(.vertical, 2)
    }

    /// "Ex 6:16–20; Jos 21:4; 1 Ch 6:2", each a link that opens the reader there.
    private func links(_ passages: [VerseRange]) -> AttributedString {
        var result = AttributedString()
        for (i, range) in passages.enumerated() {
            if i > 0 { result += AttributedString("; ") }
            var link = AttributedString(Self.short(model.displayRange(range)))
            link.link = URL(string: "\(Self.linkScheme)://\(range.storageString)")
            result += link
        }
        return result
    }

    /// A reference with the book abbreviated, as an index prints it.
    static func short(_ range: VerseRange) -> String {
        let start = "\(range.start.book.abbreviation) \(range.start.chapter):\(range.start.verse)"
        if range.start == range.end { return start }
        if range.start.chapterKey == range.end.chapterKey { return "\(start)–\(range.end.verse)" }
        if range.start.book == range.end.book { return "\(start)–\(range.end.chapter):\(range.end.verse)" }
        return "\(start)–\(range.end.book.abbreviation) \(range.end.chapter):\(range.end.verse)"
    }
}

/// Shown above everything else when a search reads as someone thinking of ending their life
/// (`CrisisSupport`): a crisis line for their country to call — or text, where it takes messages —
/// the directory of every other country's lines, and passages for a dark day.
struct CrisisCard: View {
    private let helpline = CrisisSupport.helpline(forRegion: Locale.current.region?.identifier)

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label {
                Text("You’re Not Alone", comment: "Title of the card shown when a search suggests someone may be thinking of suicide.")
                    .font(.headline)
            } icon: {
                Image(systemName: "heart.fill").foregroundStyle(.pink)
            }
            Text("If you’re thinking about ending your life, please reach out to someone now. You matter, and talking can help.",
                 comment: "Body of the crisis card shown when a search suggests someone may be thinking of suicide.")
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
            if let helpline {
                Text(verbatim: helpline.name).font(.subheadline.weight(.semibold))
                HStack(spacing: 8) {
                    if let call = helpline.callURL {
                        Link(destination: call) {
                            Label(String(localized: "Call \(helpline.display)", comment: "Crisis card button. %@ is a crisis line's phone number, e.g. “988”."),
                                  systemImage: "phone.fill")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    if let text = helpline.textURL {
                        Link(destination: text) {
                            Label(String(localized: "Text \(helpline.display)", comment: "Crisis card button: send a text message to a crisis line. %@ is its number, e.g. “988”."),
                                  systemImage: "message.fill")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                    }
                }
            }
            Link(destination: CrisisSupport.directoryURL) {
                Label(helpline == nil
                      ? String(localized: "Find a Helpline", comment: "Crisis card button opening an international directory of crisis lines, where the app knows none for the reader's country.")
                      : String(localized: "Helplines in Other Countries", comment: "Crisis card link to an international directory of crisis lines."),
                      systemImage: "globe")
            }
            .font(.subheadline)
            NavigationLink(value: TopicsRoute.theme("hope")) {
                Label(String(localized: "Passages of Hope", comment: "Crisis card link to the Hope topic's passages."), systemImage: "sunrise")
            }
            .font(.subheadline)
            Text("In danger right now? Call your local emergency number.",
                 comment: "Crisis card footnote for someone in immediate danger.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.pink.opacity(0.1), in: .rect(cornerRadius: 16))
        .accessibilityElement(children: .contain)
    }
}
