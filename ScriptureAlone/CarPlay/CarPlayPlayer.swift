#if os(iOS)
import Foundation
import ScriptureAloneCore

/// Starts reading from a CarPlay row. CarPlay can connect with no phone window open, so it keeps a reader of its
/// own — the same one every window starts from: the translation and the place the reader left off. The Bibles the
/// reader imported are offered to it as they are to every window (`RootView`), so an imported translation is read in
/// the car too. (A translation fetched online with a key is not: it needs the network and a loader a car has no use for.)
@MainActor
final class CarPlayPlayer {
    static let shared = CarPlayPlayer()

    enum Outcome: Equatable {
        case started
        /// Nothing could be read: what to tell the driver.
        case unavailable(String)
    }

    /// Slower to faster, for the Now Playing speed button.
    static let speeds: [Double] = [0.75, 1, 1.25, 1.5, 2]

    private(set) lazy var model: ReaderModel = {
        let model = ReaderModel()
        // The reader's imports, then: registering them also restores the reader's choice when it is one of them.
        model.refreshTranslations(imported: library.entries.map { ($0.info, $0.url) })
        return model
    }()
    private let listen = ListenController.shared
    private let plans: PlanStore
    private let library: ImportedLibrary

    init(plans: PlanStore = .shared, library: ImportedLibrary = ImportedLibrary()) {
        self.plans = plans
        self.library = library
    }

    var todaysReading: TodaysReading? { plans.todaysReading() }
    var location: ChapterRef { model.location }

    func perform(_ action: CarPlayEntry.Action) -> Outcome {
        switch action {
        case .playToday:
            guard plans.todaysReading() != nil else {
                return .unavailable(String(localized: "You haven’t started a reading plan. Open Scripture Alone and choose one under Reading Plans.",
                                           comment: "Shown on the CarPlay screen when there is no reading plan to read from"))
            }
            return listen.playTodaysReading(in: model, store: plans) ? .started : unavailable
        case .playContinue:
            listen.playChapter(model.location, in: model)
            return listen.isListening(in: model) ? .started : unavailable
        case .playChapter(let chapter):
            listen.playChapter(chapter, in: model)
            return listen.isListening(in: model) ? .started : unavailable
        case .playRange(let range):
            // Stored by KJV keys; the reader's translation may number its verses differently.
            let native = model.numbering.nativeRange(range) ?? range
            let passage = Passage(book: native.start.book, startChapter: native.start.chapter, startVerse: native.start.verse,
                                  endChapter: native.end.chapter, endVerse: native.end.verse)
            return listen.playReading([PlanPassage(passage)], in: model) ? .started : unavailable
        case .playFavorites(let from, let all):
            // A playlist: the one chosen, then those after it, then those before it — each announced by its
            // reference, since a verse alone doesn't say where it is from.
            let start = all.firstIndex(of: from) ?? 0
            let ordered = Array(all[start...]) + Array(all[..<start])
            let passages = ordered.map { range -> PlanPassage in
                let native = model.numbering.nativeRange(range) ?? range
                return PlanPassage(Passage(book: native.start.book, startChapter: native.start.chapter, startVerse: native.start.verse,
                                           endChapter: native.end.chapter, endVerse: native.end.verse))
            }
            return listen.playReading(passages, announcing: true, in: model) ? .started : unavailable
        case .open:
            return .started
        }
    }

    /// Reads passages the driver asked Siri for ("play John 3:16-21").
    func play(_ passages: [PlanPassage]) -> Outcome {
        listen.playReading(passages, in: model) ? .started : unavailable
    }

    private var unavailable: Outcome {
        .unavailable(String(localized: "This translation can’t be read aloud right now. Open Scripture Alone to download it.",
                            comment: "Shown on the CarPlay screen when the translation's text isn't on the phone"))
    }

    /// The next speed up, wrapping to the slowest — one tap on Now Playing's speed button.
    func cycleSpeed() {
        let next = Self.speeds.first { $0 > listen.speed + 0.001 } ?? Self.speeds[0]
        listen.speed = next
    }

    var speedLabel: String {
        // Fractions are the shortest way to write these, and a short label is a large one: CarPlay scales the
        // button's image to fit, so every extra character shrinks the letters.
        let s = listen.speed
        let whole = Int(s.rounded(.down))
        let fraction = ["0.25": "¼", "0.5": "½", "0.75": "¾"][String(format: "%g", s - Double(whole))] ?? ""
        if !fraction.isEmpty { return (whole == 0 ? "" : String(whole)) + fraction + "×" }
        return (s == s.rounded() ? String(Int(s)) : String(format: "%g", s)) + "×"
    }
}
#endif
