import SwiftUI
import ScriptureAloneCore

/// What the old commentators wrote on the passage around this verse. Scripture references
/// inside the text are links that open in the reader.
struct CommentaryView: View {
    @Environment(ReaderModel.self) private var model
    @Environment(StudyModel.self) private var study
    let verse: VerseRef

    @AppStorage("study.commentarySource") private var sourceID = "calvin"
    @State private var loaded: Loaded?

    struct Prepared: Identifiable {
        let entry: CommentaryEntry
        let paragraphs: [AttributedString]
        var id: String { entry.id }
    }

    struct Loaded {
        let key: String
        let entries: [Prepared]
        let introduction: Prepared?
        /// Other sources that do comment on this verse, for the empty state.
        let alternatives: [StudySource]
    }

    private var sources: [StudySource] { study.store?.commentarySources ?? [] }
    private var source: StudySource? { sources.first { $0.id == sourceID } ?? sources.first }
    private var loadKey: String { "\(source?.id ?? "")-\(verse.key)" }

    var body: some View {
        VStack(spacing: 0) {
            if sources.count > 1 {
                Picker("Commentary", selection: Binding(get: { source?.id ?? sourceID }, set: { sourceID = $0 })) {
                    ForEach(sources) { Text($0.shortName).tag($0.id) }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .padding(.horizontal)
                .padding(.vertical, 8)
            }
            Group {
                if let loaded, loaded.key == loadKey, let source {
                    if loaded.entries.isEmpty && loaded.introduction == nil {
                        empty(source: source, alternatives: loaded.alternatives)
                    } else {
                        reading(loaded, source: source)
                    }
                } else {
                    ProgressView()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .task(id: loadKey) { load() }
    }

    private func reading(_ loaded: Loaded, source: StudySource) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(source.name).font(.headline)
                    Text(source.author).font(.caption).foregroundStyle(.secondary)
                }
                if let intro = loaded.introduction {
                    DisclosureGroup {
                        paragraphs(intro)
                            .padding(.top, 8)
                    } label: {
                        Text("Introduction to \(intro.entry.chapter.display)")
                            .font(.subheadline.weight(.semibold))
                    }
                }
                ForEach(loaded.entries) { prepared in
                    VStack(alignment: .leading, spacing: 10) {
                        if let range = prepared.entry.range {
                            passageHeader(range)
                        }
                        paragraphs(prepared)
                    }
                }
                Divider()
                Text(source.attribution)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            .padding()
            .textSelection(.enabled)
        }
    }

    private func passageHeader(_ range: VerseRange) -> some View {
        Text(range.display)
            .font(.caption.weight(.semibold))
            .textCase(.uppercase)
            .kerning(0.8)
            .foregroundStyle(.secondary)
            .accessibilityAddTraits(.isHeader)
    }

    private func paragraphs(_ prepared: Prepared) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            ForEach(prepared.paragraphs.indices, id: \.self) { index in
                Text(prepared.paragraphs[index])
                    .font(.system(.body, design: .serif))
                    .lineSpacing(4)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func empty(source: StudySource, alternatives: [StudySource]) -> some View {
        ContentUnavailableView {
            Label("Nothing from \(source.shortName) Here", systemImage: "text.book.closed")
        } description: {
            Text("\(source.name) doesn’t comment on \(verse.display).")
        } actions: {
            ForEach(alternatives) { other in
                Button("Read \(other.shortName)") { sourceID = other.id }
            }
        }
    }

    private func load() {
        guard let store = study.store, let source else { return }
        let entries = (try? store.commentary(source.id, on: verse)) ?? []
        let intro = try? store.introduction(source.id, to: verse.chapterKey)
        var alternatives: [StudySource] = []
        if entries.isEmpty {
            let commenting = (try? store.sourcesCommenting(on: verse)) ?? []
            alternatives = sources.filter { $0.id != source.id && commenting.contains($0.id) }
        }
        loaded = Loaded(key: loadKey,
                        entries: entries.map(prepare),
                        introduction: intro.map(prepare),
                        alternatives: alternatives)
    }

    private func prepare(_ entry: CommentaryEntry) -> Prepared {
        Prepared(entry: entry, paragraphs: entry.paragraphs.map { linked($0) })
    }

    /// Turns references like "Rom 5:8" or "Joh 3:22" into links that open in the reader.
    private func linked(_ paragraph: String) -> AttributedString {
        var result = AttributedString(paragraph)
        for match in ReferenceDetector.detect(in: paragraph) where match.passage.startVerse != nil {
            let range = match.passage.range { model.source?.verseCount($0) ?? 176 }
            guard let url = StudyLink.url(for: range),
                  let stringRange = Range(match.range, in: paragraph),
                  let lower = AttributedString.Index(stringRange.lowerBound, within: result),
                  let upper = AttributedString.Index(stringRange.upperBound, within: result) else { continue }
            result[lower..<upper].link = url
        }
        return result
    }
}
