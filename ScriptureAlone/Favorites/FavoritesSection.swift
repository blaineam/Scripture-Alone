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

    private struct Row: Identifiable {
        let favorite: Favorite
        let range: VerseRange
        let text: String
        var id: UUID { favorite.uuid }
    }

    private var rows: [Row] {
        let term = search.trimmingCharacters(in: .whitespaces)
        let passage = term.rangeOfCharacter(from: .decimalDigits) != nil ? ReferenceParser.parse(term) : nil
        let wanted = passage.flatMap { p in model.store.map { store in p.range { store.verseCount($0) } } }
        return favorites.compactMap { favorite in
            guard let range = favorite.range else { return nil }
            let text = (try? model.store?.verses(in: range))?.map(\.text).joined(separator: " ") ?? ""
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
