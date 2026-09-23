import SwiftUI
import SwiftData
import ScriptureAloneCore

/// Appears while verses are selected: highlight, note, copy, share.
struct SelectionBar: View {
    @Environment(ReaderModel.self) private var model
    @Environment(\.modelContext) private var context
    let onNote: () -> Void
    @State private var copied = false
    @State private var interlinear: InterlinearRequest?

    var body: some View {
        let ranges = model.selectedRanges
        let quotation = model.quotation(for: ranges)
        let mayQuote = model.mayQuote(ranges)
        VStack(spacing: 10) {
            HStack {
                Text(ranges.map(\.display).joined(separator: ", "))
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                Spacer()
                Button { ListenController.shared.playSelection(in: model) } label: {
                    Label("Listen", systemImage: "headphones").font(.subheadline.weight(.semibold))
                }
                .buttonStyle(.plain)
                .foregroundStyle(.tint)
                Button { model.selection.removeAll() } label: {
                    Image(systemName: "xmark").font(.subheadline.weight(.semibold))
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
                .accessibilityLabel("Clear Selection")
                .keyboardShortcut(.escape, modifiers: [])
            }
            // Every control gets an equal share of the bar's width, so the row fits any phone
            // (a fixed 12-pt spacing made it wider than the screen once Favorites joined).
            HStack(spacing: 0) {
                ForEach(HighlightColor.allCases) { color in
                    Button { highlight(color) } label: {
                        Circle().fill(color.swatch)
                            .frame(width: 26, height: 26)
                            .overlay(Circle().strokeBorder(.primary.opacity(0.12)))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Highlight \(color.name)")
                    .modifier(BarCell())
                }
                Button { removeHighlights() } label: { Image(systemName: "eraser") }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Remove Highlight")
                    .modifier(BarCell())
                Divider().frame(height: 24).padding(.horizontal, 2)
                FavoriteButton(ranges: ranges).modifier(BarCell())
                Button(action: onNote) { Image(systemName: "square.and.pencil") }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Add Note")
                    .modifier(BarCell())
                // Disabled rather than hidden: a reader who selected more than this translation
                // allows should see that the control exists and why it won't work, not wonder
                // where it went.
                Button { copy(quotation) } label: { Image(systemName: copied ? "checkmark" : "doc.on.doc") }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Copy")
                    .disabled(!mayQuote || !model.rights.allowCopy)
                    .modifier(BarCell())
                if let single = singleVerse, InterlinearLibrary.shared.supports(model.translationInfo) {
                    Button { interlinear = InterlinearRequest(verse: single) } label: { Image(systemName: "character.book.closed") }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Original Language")
                        .modifier(BarCell())
                }
                ShareMenu(ranges: ranges, quotation: quotation)
                    .disabled(!mayQuote)
                    .modifier(BarCell())
            }
            .font(.title3)

            if !mayQuote {
                Text(quotationLimitNotice)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 12)
        .frame(maxWidth: 520)
        .glassEffect(.regular, in: .rect(cornerRadius: 26))
        .sheet(item: $interlinear) { request in
            InterlinearView(verse: request.verse, verseText: text(of: request.verse),
                            glossText: bereanText(of: request.verse))
                #if os(macOS)
                .frame(minWidth: 460, minHeight: 560)
                #endif
        }
    }

    /// Word-by-word makes sense for one verse at a time; a selection spanning several would be a
    /// wall rather than a study aid.
    /// As a KJV key: the word-by-word data and `verses(in:)` are both keyed that way.
    private var singleVerse: VerseRef? {
        guard model.selection.count == 1, let key = model.selection.first else { return nil }
        return VerseRef(key: model.numbering.kjv(forNative: key))
    }

    /// Says whose limit it is and what it is, because "this doesn't work" is not an explanation.
    private var quotationLimitNotice: String {
        let limit = model.rights.maxQuotationVerses
        let name = model.translationInfo?.abbreviation ?? String(localized: "This translation", comment: "Stands in for a translation's abbreviation in “%@ can't be quoted outside the app.”")
        guard limit > 0 else { return String(localized: "\(name) can't be quoted outside the app.", comment: "%@ is a translation abbreviation, e.g. “ESV”, or “This translation”.") }
        return String(localized: "\(name) allows up to \(limit) verses in one quotation. Select fewer to copy or share.", comment: "%1$@ is a translation abbreviation, e.g. “ESV”; %2$lld is a number of verses.")
    }

    /// The verse in the Berean Standard Bible, which the word-by-word data is keyed to. The app
    /// ships the BSB, so this is a local lookup and costs nothing even when reading online.
    private func bereanText(of verse: VerseRef) -> String? {
        guard let bsb = model.source(for: InterlinearStore.translationID) else { return nil }
        return (try? bsb.verses(in: VerseRange(verse, verse)))?.first?.text
    }

    private func text(of verse: VerseRef) -> String {
        (try? model.source?.verses(in: VerseRange(verse, verse)))??.first?.text ?? ""
    }

    /// One flexible slot in the action row: shrinks with the bar, never below a 28-pt target.
    private struct BarCell: ViewModifier {
        func body(content: Content) -> some View {
            content.frame(minWidth: 28, maxWidth: .infinity, minHeight: 36)
        }
    }

    private func existing(for keys: Set<Int>) -> [Highlight] {
        let low = keys.min() ?? 0
        let high = keys.max() ?? 0
        let descriptor = FetchDescriptor<Highlight>(predicate: #Predicate { $0.verseKey >= low && $0.verseKey <= high })
        return ((try? context.fetch(descriptor)) ?? []).filter { keys.contains($0.verseKey) }
    }

    /// Highlights are stored one per KJV verse (`selectedKJVKeys`), so they show in every
    /// translation whatever it calls the verse.
    private func highlight(_ color: HighlightColor) {
        let keys = model.selectedKJVKeys
        for old in existing(for: keys) { context.delete(old) }
        for key in keys { context.insert(Highlight(verseKey: key, color: color)) }
        model.selection.removeAll()
    }

    private func removeHighlights() {
        for old in existing(for: model.selectedKJVKeys) { context.delete(old) }
        model.selection.removeAll()
    }

    private func copy(_ text: String) {
        #if os(iOS)
        UIPasteboard.general.string = text
        #else
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        #endif
        copied = true
        Task {
            try? await Task.sleep(for: .seconds(1.2))
            copied = false
        }
    }
}


/// A verse to show word-by-word. `VerseRef` is a plain value in Core; wrapping it here keeps
/// SwiftUI's `sheet(item:)` happy without making a data type conform to a UI protocol.
private struct InterlinearRequest: Identifiable {
    let verse: VerseRef
    var id: Int { verse.key }
}
