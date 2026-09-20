import SwiftUI
import ScriptureAloneCore

/// One verse, word by word, with the Hebrew or Greek behind each English word.
///
/// This is the app's answer to "how close is this to the original manuscripts" — not a claim about
/// a translation, but the words themselves, with the parsing and the lexicon entry a reader can
/// check. The data aligns to the Berean Standard Bible, so the affordance only appears there.
struct InterlinearView: View {
    let verse: VerseRef
    /// The verse as the reader is reading it, shown at the top for context.
    let verseText: String
    /// The same verse in the Berean Standard Bible, which is what the word-by-word data is keyed
    /// to. Equal to `verseText` when the reader is already in the BSB; nil only if the BSB store
    /// cannot be opened, which would be odd since the app ships it.
    var glossText: String?

    @Environment(\.dismiss) private var dismiss
    @AppStorage(SettingsKey.fontFamily) private var family = FontFamily.newYork

    @State private var words: [InterlinearWord] = []
    @State private var expanded: String?
    @State private var entry: LexiconEntry?
    @State private var failure: String?

    private var store: InterlinearStore? { InterlinearLibrary.shared.store }

    var body: some View {
        NavigationStack {
            Group {
                if let failure {
                    ContentUnavailableView("No Original-Language Data", systemImage: "character.book.closed",
                                           description: Text(failure))
                } else {
                    list
                }
            }
            .navigationTitle(verse.display)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .task { load() }
        }
    }

    private var list: some View {
        List {
            Section {
                ForEach(words, id: \.position) { word in
                    row(word)
                }
            } header: {
                VStack(alignment: .leading, spacing: 6) {
                    Text(verseText).font(family.swiftUIFont(size: 15)).textCase(nil)
                        .foregroundStyle(.primary)
                    // Said plainly rather than left to be noticed: the English beside each Hebrew
                    // or Greek word is the Berean Standard Bible's, which is what the word-by-word
                    // data is keyed to. The Hebrew and Greek themselves are the verse's own.
                    if glossesFromAnotherTranslation {
                        Text("English shown word-by-word is the Berean Standard Bible's, which this data is keyed to.")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .textCase(nil)
                    }
                }
                .padding(.vertical, 6)
            }

            if let attribution = store?.attribution {
                Section("Sources") {
                    ForEach(Array(attribution.requiredLines.enumerated()), id: \.offset) { _, line in
                        Text(line).font(.caption2).foregroundStyle(.secondary)
                    }
                }
            }
        }
    }

    private func row(_ word: InterlinearWord) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(alignment: .firstTextBaseline) {
                Text(word.original)
                    .font(.title3)
                    .environment(\.layoutDirection, word.language.isRightToLeft ? .rightToLeft : .leftToRight)
                Spacer()
                if let strongs = word.strongs {
                    Text(strongs).font(.caption.monospaced()).foregroundStyle(.tint)
                }
            }
            Text(word.transliteration).font(.callout.italic()).foregroundStyle(.secondary)
            HStack(spacing: 6) {
                if !word.english.isEmpty {
                    Text(word.english).font(.callout.weight(.medium))
                }
                if word.isSuperscription {
                    Text("superscription").font(.caption2).foregroundStyle(.secondary)
                }
            }
            if !word.parsingDescription.isEmpty {
                Text(word.parsingDescription).font(.caption).foregroundStyle(.secondary)
            }
            if expanded == word.strongs, let entry {
                VStack(alignment: .leading, spacing: 4) {
                    if !entry.gloss.isEmpty {
                        Text(entry.gloss).font(.callout.weight(.semibold))
                    }
                    ForEach(Array(entry.senses.enumerated()), id: \.offset) { _, sense in
                        VStack(alignment: .leading, spacing: 2) {
                            if !sense.lemma.isEmpty {
                                Text("\(sense.lemma) · \(sense.gloss)").font(.caption.weight(.medium))
                            }
                            if !sense.definition.isEmpty {
                                Text(sense.definition).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                .padding(.top, 2)
            }
        }
        .padding(.vertical, 3)
        .contentShape(Rectangle())
        .onTapGesture { toggle(word) }
        .accessibilityElement(children: .combine)
    }

    private func toggle(_ word: InterlinearWord) {
        guard let strongs = word.strongs else { return }
        if expanded == strongs {
            expanded = nil
            entry = nil
            return
        }
        expanded = strongs
        entry = try? store?.entry(for: strongs)
    }

    /// True when the reader is in something other than the translation the alignment is keyed to.
    private var glossesFromAnotherTranslation: Bool {
        guard let glossText, !glossText.isEmpty else { return false }
        return glossText != verseText
    }

    private func load() {
        guard let store else {
            failure = "The original-language data isn't available."
            return
        }
        do {
            // Keyed to the BSB's wording, so the words are resolved against the BSB's text even
            // when the reader is in another translation. The Hebrew and Greek behind a verse do
            // not change with the English in front of them — only the word-by-word alignment
            // does, and that is what `glossText` supplies.
            words = try store.words(for: verse, in: glossText ?? verseText)
            if words.isEmpty { failure = "This verse has no original-language data." }
        } catch {
            failure = error.localizedDescription
        }
    }
}

/// Opens the bundled interlinear database once.
///
/// It is 11 MB and read-only, so one instance serves the whole app; a verse's worth of taps costs
/// one inflate rather than one per word.
@MainActor
@Observable
final class InterlinearLibrary {
    static let shared = InterlinearLibrary()

    /// Nil only if the database will not open, which is a corrupt install. Opened lazily rather
    /// than at launch: most readers never ask for the Hebrew or the Greek.
    private(set) var store: InterlinearStore?

    private init() { open() }

    private func open() {
        guard store == nil, let url = StudyPack.interlinear.url else { return }
        store = try? InterlinearStore(url: url)
    }

    /// Whether to offer the affordance at all.
    ///
    /// Offered for every translation. The word-by-word data is keyed to the BSB's wording, but
    /// what it *holds* — the Hebrew, Aramaic and Greek, the parsing, the Strong's numbers and the
    /// lexicon — is a property of the verse, not of the English in front of it. Restricting the
    /// whole feature to one translation withheld all of that from anyone reading anything else,
    /// to avoid a mismatch in one column.
    ///
    /// Deliberately does not require the pack to be downloaded — the offer is what prompts the
    /// download, so hiding it until the file exists would mean nobody ever gets it.
    func supports(_ info: TranslationInfo?) -> Bool {
        info != nil
    }

    /// Kept for the one thing that really is BSB-only: whether a word's `range` can point into the
    /// text the reader is looking at.
    func alignsToDisplayedText(_ info: TranslationInfo?) -> Bool {
        guard let info else { return false }
        return InterlinearStore.alignsTo(info)
    }
}
