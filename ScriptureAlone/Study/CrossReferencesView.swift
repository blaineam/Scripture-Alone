import SwiftUI
import ScriptureAloneCore

/// Where else Scripture speaks to this verse: the strongest links first, then the rest in
/// canonical order, each with its text in the translation being read.
struct CrossReferencesView: View {
    @Environment(ReaderModel.self) private var model
    @Environment(StudyModel.self) private var study
    let verse: VerseRef

    @State private var loaded: Loaded?
    @State private var showAll = false

    /// How many of the strongest links lead the list.
    private static let strongest = 6
    /// Beyond this many, the rest wait behind "Show All".
    private static let initialLimit = 40

    struct Row: Identifiable, Hashable {
        let reference: CrossReference
        let text: String
        var id: String { reference.id }
    }

    struct Loaded: Equatable {
        let key: String
        let rows: [Row]
    }

    private var loadKey: String { "\(verse.key)-\(model.translationID)" }

    var body: some View {
        Group {
            if let loaded, loaded.key == loadKey {
                if loaded.rows.isEmpty {
                    ContentUnavailableView("No Cross References", systemImage: "arrow.triangle.branch",
                                           description: Text("Nothing is linked to \(verse.display) yet."))
                } else {
                    list(loaded.rows)
                }
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .task(id: loadKey) { load() }
    }

    private func list(_ rows: [Row]) -> some View {
        let top = Array(rows.prefix(Self.strongest))
        let rest = rows.dropFirst(Self.strongest)
        let shown = showAll ? Array(rest) : Array(rest.prefix(Self.initialLimit - Self.strongest))
        let ordered = shown.sorted { $0.reference.target < $1.reference.target }
        let oldTestament = ordered.filter { !$0.reference.target.start.book.isNewTestament }
        let newTestament = ordered.filter { $0.reference.target.start.book.isNewTestament }
        let maxVotes = max(1, rows.first?.reference.votes ?? 1)
        return List {
            Section {
                ForEach(top) { row in CrossReferenceRow(row: row, maxVotes: maxVotes) }
            } header: {
                Text("Strongest")
            } footer: {
                if rows.count > Self.strongest {
                    Text("\(rows.count) references, ranked by how many readers found each one helpful.")
                }
            }
            if !oldTestament.isEmpty {
                Section("Old Testament") {
                    ForEach(oldTestament) { row in CrossReferenceRow(row: row, maxVotes: maxVotes) }
                }
            }
            if !newTestament.isEmpty {
                Section("New Testament") {
                    ForEach(newTestament) { row in CrossReferenceRow(row: row, maxVotes: maxVotes) }
                }
            }
            if !showAll, rows.count > Self.initialLimit {
                Section {
                    Button("Show All \(rows.count) References") { showAll = true }
                }
            }
            if let source = study.store?.crossReferenceSource {
                Section {
                    Text(source.attribution)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
        }
        #if os(iOS)
        .listStyle(.insetGrouped)
        #else
        .listStyle(.inset)
        #endif
    }

    private func load() {
        showAll = false
        guard let store = study.store, let bible = model.source else { return }
        let references = (try? store.crossReferences(for: verse)) ?? []
        let rows = references.map { reference in
            let verses = (try? bible.verses(in: reference.target)) ?? []
            let text = verses.count > 1
                ? verses.map { "\($0.ref.verse) \($0.text)" }.joined(separator: " ")
                : (verses.first?.text ?? "")
            return Row(reference: reference, text: text)
        }
        loaded = Loaded(key: loadKey, rows: rows)
    }
}

private struct CrossReferenceRow: View {
    @Environment(ReaderModel.self) private var model
    @Environment(StudyModel.self) private var study
    let row: CrossReferencesView.Row
    let maxVotes: Int

    var body: some View {
        Button { study.jump(to: row.reference.target, reader: model) } label: {
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .firstTextBaseline) {
                    Text(row.reference.target.display)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.tint)
                    Spacer(minLength: 8)
                    StrengthMeter(fraction: Double(row.reference.votes) / Double(maxVotes))
                }
                if !row.text.isEmpty {
                    Text(row.text)
                        .font(.system(.callout, design: .serif))
                        .foregroundStyle(.secondary)
                        .lineLimit(3)
                        .multilineTextAlignment(.leading)
                }
            }
            .padding(.vertical, 2)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityHint("Opens \(row.reference.target.display) in the reader")
        .contextMenu {
            Button { study.jump(to: row.reference.target, reader: model) } label: {
                Label("Go to \(row.reference.target.display)", systemImage: "arrow.right")
            }
            Button { copy() } label: { Label("Copy", systemImage: "doc.on.doc") }
        } preview: {
            VStack(alignment: .leading, spacing: 8) {
                Text(row.reference.target.display).font(.headline)
                Text(row.text).font(.system(.body, design: .serif))
            }
            .padding()
            .frame(width: 340, alignment: .leading)
        }
    }

    private func copy() {
        let text = "\(row.text)\n— \(row.reference.target.display) (\(model.translationID))"
        #if os(iOS)
        UIPasteboard.general.string = text
        #else
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        #endif
    }
}

/// Three small bars, filled in proportion to a reference's votes against the strongest one.
private struct StrengthMeter: View {
    let fraction: Double

    var body: some View {
        let filled = fraction > 0.66 ? 3 : fraction > 0.25 ? 2 : 1
        HStack(spacing: 2) {
            ForEach(0..<3, id: \.self) { index in
                Capsule()
                    .fill(index < filled ? AnyShapeStyle(.tint) : AnyShapeStyle(.quaternary))
                    .frame(width: 3, height: 6 + CGFloat(index) * 3)
            }
        }
        .accessibilityHidden(true)
    }
}
