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

    /// The plans the reader started, the primary first, each with today's passage.
    var planRows: [CarPlayCatalog.PlanRow] {
        plans.started.map {
            CarPlayCatalog.PlanRow(id: $0.id, title: $0.displayTitle, today: plans.todaysReading(in: $0.id)?.display,
                                   hasStudy: $0.source?.studyCategory != nil)
        }
    }
    var location: ChapterRef { model.location }

    func perform(_ action: CarPlayEntry.Action) -> Outcome {
        switch action {
        case .playToday:
            guard plans.todaysReading() != nil else {
                return .unavailable(String(localized: "You haven’t started a reading plan. Open Scripture Alone and choose one under Reading Plans.",
                                           comment: "Shown on the CarPlay screen when there is no reading plan to read from"))
            }
            return listen.playTodaysReading(in: model, store: plans) ? .started : unavailable
        case .playPlan(let id):
            guard plans.todaysReading(in: id) != nil else {
                return .unavailable(String(localized: "There is no reading for today in that plan.", comment: "Shown on the CarPlay screen when a plan has nothing to read today"))
            }
            return listen.playTodaysReading(inPlan: id, in: model, store: plans) ? .started : unavailable
        case .playStudy:
            // Needs the network, so it is `playStudy(_:)`, which the car's list awaits.
            return .unavailable(String(localized: "The study can’t be read right now.", comment: "Shown on the CarPlay screen when the weekly study can't be read"))
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

    /// The church's latest weekly study, read aloud: fetched when the phone is online, else the last one it kept.
    func playStudy(_ planID: String, fetcher: any PlanFetching = URLSession.shared) async -> Outcome {
        guard let source = plans.plan(id: planID)?.source, source.studyCategory != nil else {
            return .unavailable(String(localized: "That plan has no weekly study.", comment: "Shown on the CarPlay screen when a plan has no weekly study"))
        }
        var post: StudyPost?
        if let latest = try? await PlanFeedImporter(fetcher: fetcher).latestStudy(source: source, onOrBefore: PlanSchedule.isoDay(Date())) {
            StudyCache.save(latest, for: source)
            post = latest
        } else {
            post = StudyCache.load(for: source)
        }
        guard let post else {
            return .unavailable(String(localized: "There’s no study to read yet. Connect the phone to the internet and try again.", comment: "Shown on the CarPlay screen when no weekly study could be had"))
        }
        return listen.playProse(post.text, title: post.title, in: model) ? .started : unavailable
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
        // The shortest writing of each speed, no unit: CarPlay draws the button's image small, and a short label
        // is a large one. The button is the speed, and a tap steps it.
        let s = listen.speed
        let whole = Int(s.rounded(.down))
        let fraction = ["0.25": "¼", "0.5": "½", "0.75": "¾"][String(format: "%g", s - Double(whole))] ?? ""
        if !fraction.isEmpty { return (whole == 0 ? "" : String(whole)) + fraction }
        return s == s.rounded() ? String(Int(s)) : String(format: "%g", s)
    }
}
#endif
