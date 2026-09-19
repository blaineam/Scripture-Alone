import SwiftUI
import SwiftData
import ScriptureAloneCore

/// The heart in the selection bar. Filled when every selected range is already a favorite;
/// tapping then removes them, otherwise it favorites whatever isn't yet.
struct FavoriteButton: View {
    @Environment(\.modelContext) private var context
    @Query private var favorites: [Favorite]
    let ranges: [VerseRange]

    private var isFavorite: Bool {
        let stored = Set(favorites.map(\.rangeRaw))
        return !ranges.isEmpty && ranges.allSatisfy { stored.contains($0.storageString) }
    }

    var body: some View {
        Button(action: toggle) {
            Image(systemName: isFavorite ? "heart.fill" : "heart")
                .foregroundStyle(isFavorite ? AnyShapeStyle(.red) : AnyShapeStyle(.primary))
                .contentTransition(.symbolEffect(.replace))
                .frame(width: 28, height: 28)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(isFavorite ? "Remove from Favorites" : "Add to Favorites")
        .sensoryFeedback(.selection, trigger: isFavorite)
        .keyboardShortcut("d", modifiers: .command)
    }

    private func toggle() {
        if isFavorite {
            let raws = Set(ranges.map(\.storageString))
            for favorite in favorites where raws.contains(favorite.rangeRaw) { context.delete(favorite) }
        } else {
            let stored = Set(favorites.map(\.rangeRaw))
            for range in ranges where !stored.contains(range.storageString) { context.insert(Favorite(range: range)) }
        }
    }
}
