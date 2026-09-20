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

    @State private var downloading = false
    private var store: InterlinearStore? { InterlinearLibrary.shared.store }

    var body: some View {
        NavigationStack {
            Group {
                if downloading {
                    VStack(spacing: 12) {
                        ProgressView(value: downloadFraction)
                            .frame(maxWidth: 220)
                        Text("Downloading \(OnDemandPack.interlinear.title)…").font(.callout)
                        Text(OnDemandPack.interlinear.explanation)
                            .font(.caption).foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding()
                } else if InterlinearLibrary.shared.store == nil, words.isEmpty, failure == nil {
                    ContentUnavailableView {
                        Label(OnDemandPack.interlinear.title, systemImage: "arrow.down.circle")
                    } description: {
                        Text(OnDemandPack.interlinear.explanation)
                    } actions: {
                        Button("Download") { Task { await prepareAndLoad() } }
                    }
                } else if let failure {
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
            .task {
                // Already downloaded: open it without asking. Not downloaded: the reader is shown
                // the size and taps to fetch, because 11 MB on a cellular connection is their
                // decision, not ours.
                if InterlinearLibrary.shared.store != nil || OnDemandLibrary.shared.isReady(.interlinear) {
                    await prepareAndLoad()
                }
            }
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

    private var downloadFraction: Double {
        if case .downloading(let value) = OnDemandLibrary.shared.state(of: .interlinear) { return value }
        return 0
    }

    private func prepareAndLoad() async {
        if InterlinearLibrary.shared.store == nil {
            downloading = true
            _ = await InterlinearLibrary.shared.prepare()
            downloading = false
        }
        load()
    }

    private func load() {
        guard let store else {
            if case .failed(let message) = OnDemandLibrary.shared.state(of: .interlinear) {
                failure = message
            } else {
                failure = "The original-language data isn't available yet."
            }
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
@Observable
final class InterlinearLibrary {
    static let shared = InterlinearLibrary()

    /// Nil until the on-demand pack is on the device. Opened lazily rather than at launch,
    /// because the file may not exist yet and may be purged later.
    private(set) var store: InterlinearStore?

    private init() { open() }

    private func open() {
        guard store == nil, let url = OnDemandLibrary.shared.url(of: .interlinear) else { return }
        store = try? InterlinearStore(url: url)
    }

    /// Downloads the pack if needed, then opens it.
    @discardableResult
    func prepare() async -> Bool {
        if store != nil { return true }
        guard await OnDemandLibrary.shared.ensure(.interlinear) else { return false }
        open()
        return store != nil
    }

    /// Whether to offer the affordance at all: the data aligns to the BSB and nothing else.
    ///
    /// Deliberately does not require the pack to be downloaded — the offer is what prompts the
    /// download, so hiding it until the file exists would mean nobody ever gets it.
    func supports(_ info: TranslationInfo?) -> Bool {
        guard let info else { return false }
        return InterlinearStore.alignsTo(info)
    }
}
