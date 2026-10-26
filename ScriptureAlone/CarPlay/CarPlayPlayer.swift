#if os(iOS)
import Foundation
import ScriptureAloneCore

/// Starts reading from a CarPlay row. CarPlay can connect with no phone window open, so it keeps a reader of its
/// own — the same one every window starts from: the translation and the place the reader left off.
/// (Translations imported or fetched online arrive after launch and aren't in it, so CarPlay reads the
/// bundled translation the reader chose, or the app's default.)
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

    private(set) lazy var model = ReaderModel()
    private let listen = ListenController.shared
    private let plans: PlanStore

    init(plans: PlanStore = .shared) { self.plans = plans }

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
        case .open:
            return .started
        }
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
        let s = listen.speed
        return (s == s.rounded() ? String(Int(s)) : String(format: "%g", s)) + "×"
    }
}
#endif
