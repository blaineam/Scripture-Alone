import SwiftUI
import SwiftData
import ScriptureAloneCore

/// Appears while verses are selected: highlight, note, copy, share.
struct SelectionBar: View {
    @Environment(ReaderModel.self) private var model
    @Environment(\.modelContext) private var context
    let onNote: () -> Void
    @State private var copied = false

    var body: some View {
        let ranges = model.selectedRanges
        let quotation = model.quotation(for: ranges)
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
            HStack(spacing: 12) {
                ForEach(HighlightColor.allCases) { color in
                    Button { highlight(color) } label: {
                        Circle().fill(color.swatch)
                            .frame(width: 28, height: 28)
                            .overlay(Circle().strokeBorder(.primary.opacity(0.12)))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Highlight \(color.rawValue)")
                }
                Button { removeHighlights() } label: {
                    Image(systemName: "eraser").frame(width: 28, height: 28)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Remove Highlight")
                Divider().frame(height: 24)
                Button(action: onNote) { Image(systemName: "square.and.pencil").frame(width: 28, height: 28) }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Add Note")
                Button { copy(quotation) } label: {
                    Image(systemName: copied ? "checkmark" : "doc.on.doc").frame(width: 28, height: 28)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Copy")
                ShareLink(item: quotation) { Image(systemName: "square.and.arrow.up").frame(width: 28, height: 28) }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Share")
            }
            .font(.title3)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 12)
        .frame(maxWidth: 520)
        .glassEffect(.regular, in: .rect(cornerRadius: 26))
    }

    private func existing(for keys: Set<Int>) -> [Highlight] {
        let low = keys.min() ?? 0
        let high = keys.max() ?? 0
        let descriptor = FetchDescriptor<Highlight>(predicate: #Predicate { $0.verseKey >= low && $0.verseKey <= high })
        return ((try? context.fetch(descriptor)) ?? []).filter { keys.contains($0.verseKey) }
    }

    private func highlight(_ color: HighlightColor) {
        let keys = model.selection
        for old in existing(for: keys) { context.delete(old) }
        for key in keys { context.insert(Highlight(verseKey: key, color: color)) }
        model.selection.removeAll()
    }

    private func removeHighlights() {
        for old in existing(for: model.selection) { context.delete(old) }
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
