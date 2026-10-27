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
    /// What a spoken request asks to read.
    enum Request: Equatable {
        case today
        case resume
        case passages([Passage])
    }

    /// Siri hands over words, not punctuation: "John 3 16 through 21", "Psalm 23", "today's reading", or nothing at
    /// all ("play Scripture Alone", which picks up where the reader left off). Nil when the words are none of these.
    static func request(in intent: INPlayMediaIntent) -> Request? {
        request(heard: intent.mediaSearch?.mediaName ?? intent.mediaItems?.first?.title ?? "")
    }

    static func request(heard: String) -> Request? {
        let text = heard.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return .resume }
        let lower = text.lowercased()
        if lower.contains("today") || lower.contains("reading plan") || lower.contains("daily reading") { return .today }
        let passages = ReferenceParser.parseList(SpokenReference.normalize(text), language: BookNames.current)
        return passages.isEmpty ? nil : .passages(passages)
    }

    func resolveMediaItems(for intent: INPlayMediaIntent,
                           with completion: @escaping ([INPlayMediaMediaItemResolutionResult]) -> Void) {
        guard let request = Self.request(in: intent) else {
            completion([.unsupported(forReason: .unsupportedMediaType)])
            return
        }
        let title: String
        switch request {
        case .today: title = String(localized: "Today’s Reading", comment: "Siri's name for the item it will play")
        case .resume: title = "Scripture Alone"
        case .passages(let passages): title = passages.map { PlanPassage($0).display }.joined(separator: "; ")
        }
        completion([.success(with: INMediaItem(identifier: intent.mediaSearch?.mediaName ?? title, title: title,
                                               type: .audioBook, artwork: nil))])
    }

    func handle(intent: INPlayMediaIntent, completion: @escaping (INPlayMediaIntentResponse) -> Void) {
        let request = Self.request(in: intent)
        nonisolated(unsafe) let done = completion
        Task { @MainActor in
            let player = CarPlayPlayer.shared
            let outcome: CarPlayPlayer.Outcome
            switch request {
            case .today?: outcome = player.perform(.playToday)
            case .resume?: outcome = player.perform(.playContinue)
            case .passages(let passages)?: outcome = player.play(passages.map(PlanPassage.init))
            case nil: outcome = .unavailable("")
            }
            done(INPlayMediaIntentResponse(code: outcome == .started ? .success : .failure, userActivity: nil))
        }
    }
}

/// Speech recognition writes "John 3:16" as "John 3 16", "John 3 colon 16" or "John chapter 3 verse 16 through 21".
/// This puts the punctuation back so the reference parser can read it. English words; other languages' speech
/// usually gives digits and the parser's own separators.
nonisolated enum SpokenReference {
    static func normalize(_ heard: String) -> String {
        let isNumber: (String) -> Bool = { !$0.isEmpty && $0.allSatisfy(\.isNumber) }
        let words = heard.lowercased()
            .replacingOccurrences(of: ".", with: " . ").replacingOccurrences(of: ":", with: " : ")
            .replacingOccurrences(of: "-", with: " - ")
            .split(separator: " ").map(String.init).filter { $0 != "chapter" && $0 != "chapters" }
        var tokens: [String] = []
        for (i, word) in words.enumerated() {
            let betweenNumbers = i > 0 && i + 1 < words.count && isNumber(words[i - 1]) && isNumber(words[i + 1])
            switch word {
            case "colon", "verse", "verses": tokens.append(":")
            case ".": if betweenNumbers { tokens.append(":") }       // "3.16" — but "Rom." is just "Rom"
            case "through", "thru", "to", "until", "til": tokens.append(betweenNumbers ? "-" : word)
            default: tokens.append(word)
            }
        }
        // The numbers that end the phrase, with nothing between them but spaces: "john 3 16", "john 3 16 - 21".
        var start = tokens.count
        while start > 0, isNumber(tokens[start - 1]) || tokens[start - 1] == "-" { start -= 1 }
        guard start > 0 else { return tokens.joined(separator: " ") }
        let tail = tokens[start...].split(separator: "-", omittingEmptySubsequences: false)
        guard tail.count <= 2, let left = tail.first, left.count == 2 || left.count == 3 else {
            return tokens.joined(separator: " ").replacingOccurrences(of: " : ", with: ":").replacingOccurrences(of: " - ", with: "-")
        }
        // chapter verse [- end]; a third bare number on the left is a verse range's end: "3 16 21".
        let verses = left.count == 3 ? "\(left[left.startIndex + 1])-\(left[left.startIndex + 2])" : "\(left[left.startIndex + 1])"
        let end = tail.count == 2 ? "-\(tail[1].joined())" : ""
        return (tokens[..<start] + ["\(left[left.startIndex]):\(verses)\(end)"]).joined(separator: " ")
    }
}
#endif
