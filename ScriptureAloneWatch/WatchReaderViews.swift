import SwiftUI
import SwiftData
import ScriptureAloneCore

/// Book list, grouped the way the phone's picker groups them.
struct WatchBooksView: View {
    var body: some View {
        List {
            ForEach(BookGroup.allCases, id: \.self) { group in
                let books = BookID.allCases.filter { $0.info.group == group }
                if !books.isEmpty {
                    Section(group.title) {
                        ForEach(books) { book in
                            NavigationLink(value: book.isSingleChapter
                                           ? WatchRoute.chapter(ChapterRef(book, 1), focus: nil)
                                           : WatchRoute.book(book)) {
                                Text(book.name)
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Books")
    }
}

struct WatchChaptersView: View {
    let book: BookID
    private let columns = [GridItem(.adaptive(minimum: 40), spacing: 6)]

    var body: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 6) {
                ForEach(1...book.chapterCount, id: \.self) { chapter in
                    NavigationLink(value: WatchRoute.chapter(ChapterRef(book, chapter), focus: nil)) {
                        Text(chapter, format: .number)
                            .font(.body.weight(.semibold))
                            .monospacedDigit()
                            .frame(maxWidth: .infinity, minHeight: 36)
                    }
                    .buttonStyle(.bordered)
                    .accessibilityLabel("Chapter \(chapter)")
                }
            }
        }
        .navigationTitle(book.abbreviation)
    }
}

/// A chapter as a column of verses. The Digital Crown scrolls; highlights synced from the
/// phone tint their verses; tapping a verse opens it with its notes.
struct WatchChapterView: View {
    @Environment(WatchBible.self) private var bible
    @Query private var highlights: [Highlight]
    let chapter: ChapterRef
    let focus: Int?

    init(chapter: ChapterRef, focus: Int?) {
        self.chapter = chapter
        self.focus = focus
        // A chapter's marks are stored under the KJV keys its verses hold, which for a Bible that
        // numbers its own way can reach into the neighbouring chapters; they are mapped below.
        let low = chapter.keyRange.lowerBound - 1_000
        let high = chapter.keyRange.upperBound + 1_000
        _highlights = Query(filter: #Predicate<Highlight> { $0.verseKey >= low && $0.verseKey <= high })
    }

    var body: some View {
        let verses = bible.chapterVerses(chapter)
        let colors = highlightColors
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 8) {
                    ForEach(verses, id: \.ref) { verse in
                        NavigationLink(value: WatchRoute.verse(VerseRange(kjvRef(verse.ref)))) {
                            WatchVerseText(verse: verse, highlight: colors[verse.ref.key].map(VerseStyling.highlight))
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .buttonStyle(.plain)
                        .id(verse.ref.verse)
                    }
                    if let next = chapter.next {
                        NavigationLink(value: WatchRoute.chapter(next, focus: nil)) {
                            Label(next.display, systemImage: "chevron.right")
                        }
                        .padding(.top, 8)
                    }
                }
            }
            .onAppear {
                if let focus, focus > 1 { proxy.scrollTo(focus, anchor: .top) }
            }
        }
        .navigationTitle(chapter.book.isSingleChapter ? chapter.book.abbreviation
                         : "\(chapter.book.abbreviation) \(chapter.chapter)")
    }

    /// The verse's KJV key, which the verse screen reads and marks by.
    private func kjvRef(_ ref: VerseRef) -> VerseRef {
        VerseRef(key: bible.numbering.kjv(forNative: ref.key)) ?? ref
    }

    /// Newest highlight wins when two devices colored the same verse — as on the phone. Keyed by
    /// the native verse the highlight's KJV key falls in.
    private var highlightColors: [Int: String] {
        let numbering = bible.numbering
        var newest: [Int: Highlight] = [:]
        for highlight in highlights {
            guard let key = numbering.native(forKJV: highlight.verseKey),
                  key / 1_000 == chapter.keyRange.lowerBound / 1_000,
                  (newest[key]?.createdAt ?? .distantPast) <= highlight.createdAt else { continue }
            newest[key] = highlight
        }
        return newest.mapValues(\.colorName)
    }
}
