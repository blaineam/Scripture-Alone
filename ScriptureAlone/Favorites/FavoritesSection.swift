import SwiftUI
import SwiftData
import ScriptureAloneCore

/// The Favorites scope of the Notes panel: favorited passages, newest first, with their text in
/// the current translation. Tapping one opens it in the reader.
struct FavoritesSection: View {
    @Environment(ReaderModel.self) private var model
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var sizeClass
    #endif
    @Query(sort: \Favorite.createdAt, order: .reverse) private var favorites: [Favorite]
    let search: String
    var place: NotesPanel.Place = .all

    private struct Row: Identifiable {
        let favorite: Favorite
        let range: VerseRange
        let text: String
        var id: UUID { favorite.uuid }
    }

    private var rows: [Row] {
        let term = search.trimmingCharacters(in: .whitespaces)
        let passage = term.rangeOfCharacter(from: .decimalDigits) != nil ? ReferenceParser.parse(term) : nil
        let wanted = passage.flatMap { p in model.source.map { source in p.range { source.verseCount($0) } } }
        return favorites.compactMap { favorite in
            guard let range = favorite.range, place.contains(range, location: model.location) else { return nil }
            let text = (try? model.source?.verses(in: range))?.map(\.text).joined(separator: " ") ?? ""
            if !term.isEmpty {
                if let wanted {
                    guard range.start <= wanted.end && wanted.start <= range.end else { return nil }
                } else if !(range.display.localizedStandardContains(term) || text.localizedStandardContains(term)) {
                    return nil
                }
            }
            return Row(favorite: favorite, range: range, text: text)
        }
    }

    var body: some View {
        let rows = rows
        if rows.isEmpty {
            ContentUnavailableView {
                Label(search.isEmpty ? "No Favorites Yet" : "No Matches", systemImage: "heart")
            } description: {
                Text(search.isEmpty
                     ? "Tap verses in the text, then the heart, to keep a passage close — on your widgets and your watch too."
                     : "Try a word or a passage like Rom 8.")
            }
            .listRowSeparator(.hidden)
            .listRowBackground(Color.clear)
        } else {
            ForEach(rows) { row in
                Button { open(row.range) } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(alignment: .firstTextBaseline) {
                            Text(row.range.display).font(.headline).lineLimit(1)
                            Spacer()
                            Image(systemName: "heart.fill").font(.caption).foregroundStyle(.red)
                                .accessibilityHidden(true)
                        }
                        if !row.text.isEmpty {
                            Text(row.text).font(.callout).foregroundStyle(.secondary).lineLimit(3)
                        }
                    }
                    .padding(.vertical, 2)
                    .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .accessibilityElement(children: .combine)
                .accessibilityHint("Opens the passage")
            }
            .onDelete { offsets in
                for index in offsets { context.delete(rows[index].favorite) }
            }
        }
    }

    private func open(_ range: VerseRange) {
        model.go(to: range.start)
        #if os(iOS)
        // On iPhone the panel is a sheet over the text; get out of the way.
        if sizeClass == .compact { dismiss() }
        #endif
    }
}

/// Every highlighted verse, in Bible order. Verses in a row marked in one colour read as one
/// passage ("Romans 8:38–39"), with that colour's dot; tapping one opens it.
struct HighlightsSection: View {
    @Environment(ReaderModel.self) private var model
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var sizeClass
    #endif
    @Query(sort: \Highlight.verseKey) private var highlights: [Highlight]
    let search: String
    var place: NotesPanel.Place = .all

    private struct Row: Identifiable {
        let range: VerseRange
        let color: HighlightColor
        let marks: [Highlight]
        let text: String
        var id: Int { range.start.key }
    }

    /// Runs of neighbouring verses in one colour. A verse marked twice (two devices, before a merge)
    /// counts once, newest colour winning, as in the reader.
    private var runs: [(range: VerseRange, color: HighlightColor, marks: [Highlight])] {
        var newest: [Int: Highlight] = [:]
        for mark in highlights where newest[mark.verseKey].map({ $0.createdAt < mark.createdAt }) ?? true {
            newest[mark.verseKey] = mark
        }
        var result: [(range: VerseRange, color: HighlightColor, marks: [Highlight])] = []
        for key in newest.keys.sorted() {
            guard let mark = newest[key], let verse = VerseRef(key: key) else { continue }
            let color = HighlightColor(rawValue: mark.colorName) ?? .yellow
            let duplicates = highlights.filter { $0.verseKey == key }
            if let last = result.last, last.color == color, last.range.end.key + 1 == key,
               last.range.end.chapterKey == verse.chapterKey {
                result[result.count - 1] = (VerseRange(last.range.start, verse), color, last.marks + duplicates)
            } else {
                result.append((VerseRange(verse, verse), color, duplicates))
            }
        }
        return result
    }

    private var rows: [Row] {
        let term = search.trimmingCharacters(in: .whitespaces)
        let passage = term.rangeOfCharacter(from: .decimalDigits) != nil ? ReferenceParser.parse(term) : nil
        let wanted = passage.flatMap { p in model.source.map { source in p.range { source.verseCount($0) } } }
        return runs.compactMap { run in
            guard place.contains(run.range, location: model.location) else { return nil }
            let text = (try? model.source?.verses(in: run.range))?.map(\.text).joined(separator: " ") ?? ""
            if !term.isEmpty {
                if let wanted {
                    guard run.range.start <= wanted.end && wanted.start <= run.range.end else { return nil }
                } else if !(run.range.display.localizedStandardContains(term) || text.localizedStandardContains(term)
                            || run.color.name.localizedStandardContains(term)) {
                    return nil
                }
            }
            return Row(range: run.range, color: run.color, marks: run.marks, text: text)
        }
    }

    var body: some View {
        let rows = rows
        if rows.isEmpty {
            ContentUnavailableView {
                Label(search.isEmpty ? String(localized: "No Highlights Yet", comment: "Notes panel, Highlights, when there are none") : String(localized: "No Matches"),
                      systemImage: "highlighter")
            } description: {
                Text(search.isEmpty
                     ? String(localized: "Tap verses in the text, then a colour, to highlight them.", comment: "Notes panel, Highlights, when there are none")
                     : String(localized: "Try a word or a passage like Rom 8."))
            }
            .listRowSeparator(.hidden)
            .listRowBackground(Color.clear)
        } else {
            ForEach(rows) { row in
                Button { open(row.range) } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(alignment: .firstTextBaseline) {
                            Circle().fill(row.color.swatch).frame(width: 10, height: 10)
                                .accessibilityLabel(row.color.name)
                            Text(row.range.display).font(.headline).lineLimit(1)
                        }
                        if !row.text.isEmpty {
                            Text(row.text).font(.callout).foregroundStyle(.secondary).lineLimit(3)
                        }
                    }
                    .padding(.vertical, 2)
                    .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .accessibilityElement(children: .combine)
                .accessibilityHint("Opens the passage")
            }
            .onDelete { offsets in
                for index in offsets { rows[index].marks.forEach(context.delete) }
            }
        }
    }

    private func open(_ range: VerseRange) {
        model.go(to: range.start)
        #if os(iOS)
        if sizeClass == .compact { dismiss() }
        #endif
    }
}
