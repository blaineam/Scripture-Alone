#if os(iOS)
import Foundation
import Intents
import ScriptureAloneCore

/// Siri's media request — "Play John 3:16–21 in Scripture Alone" — which is how CarPlay (and AirPods, and
/// HomePod) ask an audio app to start. The app handles it itself (`application(_:handlerForIntent:)`), with no
/// extension: it already holds the Bibles and the player, and playing needs to happen in its process.
///
/// What Siri heard goes through the same reference parser as the search box, in the language the app is shown in,
/// so "first Corinthians thirteen", "Psalm 23" and "Romans 8:28 through 30" all land on a passage.
nonisolated final class PlayPassageHandler: NSObject, INPlayMediaIntentHandling {
    /// The passages in what Siri heard: the media name, else the first item it already resolved.
    static func passages(in intent: INPlayMediaIntent) -> [Passage] {
        let heard = intent.mediaSearch?.mediaName ?? intent.mediaItems?.first?.title ?? ""
        let text = heard.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return [] }
        return ReferenceParser.parseList(text, language: BookNames.current)
    }

    func resolveMediaItems(for intent: INPlayMediaIntent,
                           with completion: @escaping ([INPlayMediaMediaItemResolutionResult]) -> Void) {
        let passages = Self.passages(in: intent)
        guard !passages.isEmpty else {
            completion([.unsupported(forReason: .unsupportedMediaType)])
            return
        }
        // One item for the whole request: a list of passages reads in order.
        let title = passages.map { PlanPassage($0).display }.joined(separator: "; ")
        let heard = intent.mediaSearch?.mediaName ?? title
        completion([.success(with: INMediaItem(identifier: heard, title: title, type: .audioBook, artwork: nil))])
    }

    func handle(intent: INPlayMediaIntent, completion: @escaping (INPlayMediaIntentResponse) -> Void) {
        let passages = Self.passages(in: intent).map(PlanPassage.init)
        nonisolated(unsafe) let done = completion
        Task { @MainActor in
            let started = !passages.isEmpty && CarPlayPlayer.shared.play(passages) == .started
            done(INPlayMediaIntentResponse(code: started ? .success : .failure, userActivity: nil))
        }
    }
}
#endif
