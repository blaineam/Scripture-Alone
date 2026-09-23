import SwiftUI
import ScriptureAloneCore

/// The chapter in two translations, verse beside verse.
///
/// Comparing is how a reader sees *where* translations differ, which is the honest answer to "is
/// this one close to the original" — closer than any claim the app could make for them. Every
/// translation the app can read is eligible: bundled, imported, or fetched over the network.
struct CompareView: View {
    @Environment(ReaderModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @AppStorage(SettingsKey.compareTranslation) private var otherID = ""
    @AppStorage(SettingsKey.fontFamily) private var family = FontFamily.newYork
    @AppStorage(SettingsKey.fontSize) private var fontSize = 19.0

    @State private var rows: [Row] = []
    @State private var failure: String?
    @State private var loading = false

    private struct Row: Identifiable {
        /// The verse number shown: the left-hand translation's own (the right's when it alone has it).
        let verse: Int
        let left: String?
        let right: String?
        /// Rows pair up by KJV key, not by number: French Psalm 51:12 sits beside English 51:10.
        let key: Int
        var id: Int { key }
        /// Nothing to compare when one side simply doesn't have the verse — translations differ on
        /// which verses they print at all.
        var isOneSided: Bool { left == nil || right == nil }
    }

    /// Everything except what's already on the left.
    private var candidates: [TranslationEntry] {
        model.translations.filter { $0.id != model.translationID }
    }

    var body: some View {
        NavigationStack {
            Group {
                if candidates.isEmpty {
                    ContentUnavailableView("Nothing to Compare With",
                                           systemImage: "rectangle.split.2x1",
                                           description: Text("Add another translation first."))
                } else if let failure {
                    ContentUnavailableView("Couldn't Load That Translation",
                                           systemImage: "exclamationmark.triangle",
                                           description: Text(failure))
                } else {
                    table
                }
            }
            .navigationTitle(model.location.display)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .principal) { picker }
            }
            .task(id: "\(otherID)-\(model.location)-\(model.translationID)") { await load() }
        }
    }

    private var picker: some View {
        Menu {
            Picker("Compare With", selection: $otherID) {
                ForEach(candidates) { Text("\($0.id) — \($0.name)").tag($0.id) }
            }
        } label: {
            HStack(spacing: 4) {
                Text(model.translationID).font(.subheadline.weight(.semibold))
                Image(systemName: "arrow.left.arrow.right").font(.caption)
                Text(otherID.isEmpty ? String(localized: "Choose", comment: "Button: choose a second translation to compare") : otherID).font(.subheadline.weight(.semibold))
            }
        }
    }

    private var table: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if loading { ProgressView().padding() }
                ForEach(rows) { row in
                    HStack(alignment: .top, spacing: 14) {
                        column(row.left, verse: row.verse)
                        column(row.right, verse: nil)
                    }
                    .padding(.vertical, 9)
                    .padding(.horizontal)
                    // A verse only one side prints is the most interesting row on the screen, so
                    // it is marked rather than left looking like a rendering fault.
                    .background(row.isOneSided ? AnyShapeStyle(.tint.opacity(0.07)) : AnyShapeStyle(.clear))
                    Divider()
                }
            }
        }
    }

    @ViewBuilder
    private func column(_ text: String?, verse: Int?) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            if let verse {
                Text("\(verse)")
                    .font(.caption.monospacedDigit().weight(.semibold))
                    .foregroundStyle(.tint)
            }
            if let text {
                Text(text).font(family.swiftUIFont(size: fontSize * 0.82))
            } else {
                Text("—").foregroundStyle(.secondary)
                    .accessibilityLabel("Not in this translation")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func load() async {
        failure = nil
        if otherID.isEmpty || !candidates.contains(where: { $0.id == otherID }) {
            otherID = candidates.first?.id ?? ""
        }
        guard !otherID.isEmpty, let left = model.source else { rows = []; return }
        loading = true
        defer { loading = false }

        let chapter = model.location
        // The right-hand side may be an online translation, which fetches (and caches) on demand,
        // or a sealed package — so it is a source, like the left-hand side.
        let right: (any ChapterTextSource)?
        if let entry = model.translations.first(where: { $0.id == otherID }), entry.isOnline {
            do { right = try await model.onlineStore(for: entry, chapter: chapter) }
            catch { failure = error.localizedDescription; rows = []; return }
        } else {
            right = model.source(for: otherID)
        }
        guard let right else { failure = String(localized: "That translation isn't available."); rows = []; return }

        // The chapter on screen by its own numbers; the other side by the KJV keys those verses hold,
        // which for a translation that numbers differently can reach into a neighbouring chapter.
        let leftVerses = (try? left.nativeVerses(in: chapter.wholeChapter)) ?? []
        let span = left.numbering.kjvKeyRange(of: chapter, verseCount: left.verseCount(chapter))
        let rightVerses = VerseRef(key: span.lowerBound).flatMap { start in
            VerseRef(key: span.upperBound).flatMap { end in try? right.verses(in: VerseRange(start, end)) }
        } ?? []
        var leftByKey: [Int: (Int, String)] = [:], rightByKey: [Int: (Int, String)] = [:]
        for verse in leftVerses { leftByKey[left.numbering.kjv(forNative: verse.ref.key)] = (verse.ref.verse, verse.text) }
        for verse in rightVerses { rightByKey[right.numbering.kjv(forNative: verse.ref.key)] = (verse.ref.verse, verse.text) }
        rows = Set(leftByKey.keys).union(rightByKey.keys).sorted().map { key in
            Row(verse: leftByKey[key]?.0 ?? rightByKey[key]?.0 ?? key % 1_000,
                left: leftByKey[key]?.1, right: rightByKey[key]?.1, key: key)
        }
    }
}

extension ChapterRef {
    /// The whole chapter as a range, for fetching every verse at once.
    var wholeChapter: VerseRange {
        VerseRange(VerseRef(book, chapter, 1), VerseRef(book, chapter, 200))
    }
}
