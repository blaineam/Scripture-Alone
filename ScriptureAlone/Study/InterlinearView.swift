import SwiftUI
import ScriptureAloneCore

/// One verse, word by word, with the Hebrew or Greek behind each English word.
///
/// This is the app's answer to "how close is this to the original manuscripts" — not a claim about
/// a translation, but the words themselves, with the parsing and the lexicon entry a reader can
/// check. The data aligns to the Berean Standard Bible, so the affordance only appears there.
struct InterlinearView: View {
    let verse: VerseRef
    let verseText: String

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
                Text(verseText).font(family.swiftUIFont(size: 15)).textCase(nil)
                    .foregroundStyle(.primary)
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

    private func load() {
        guard let store else {
            failure = "The original-language data isn't available in this build."
            return
        }
        do {
            words = try store.words(for: verse, in: verseText)
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
final class InterlinearLibrary {
    static let shared = InterlinearLibrary()

    let store: InterlinearStore?

    private init() {
        guard let url = Bundle.main.url(forResource: "Interlinear", withExtension: "sqlite") else {
            store = nil
            return
        }
        store = try? InterlinearStore(url: url)
    }

    /// Whether to offer the affordance at all: the data aligns to the BSB and nothing else.
    func supports(_ info: TranslationInfo?) -> Bool {
        guard let info, store != nil else { return false }
        return InterlinearStore.alignsTo(info)
    }
}
