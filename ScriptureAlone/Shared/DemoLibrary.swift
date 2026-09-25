import Foundation
import SwiftData
import ScriptureAloneCore

/// DEBUG-only demo content for simulator verification and screenshot rigs: launch with
/// `-seedDemoLibrary` and an empty store gets a few favorites, a list of highlights and two sermon notes —
/// no personal data, no taps needed. The App Store rig adds `-inMemoryStore` so every scene
/// starts from exactly this library. Compiled into the phone/Mac app and the watch app.
enum DemoLibrary {
    static func seedIfRequested(_ context: ModelContext) {
        #if DEBUG
        guard ProcessInfo.processInfo.arguments.contains("-seedDemoLibrary") else { return }
        let existing = (try? context.fetchCount(FetchDescriptor<Favorite>())) ?? 0
        guard existing == 0 else { return }
        let now = Date.now
        let favorites: [VerseRange] = [
            VerseRange(VerseRef(.romans, 8, 38), VerseRef(.romans, 8, 39)),
            VerseRange(VerseRef(.psalms, 23, 1)),
            VerseRange(VerseRef(.john, 14, 6)),
            VerseRange(VerseRef(.isaiah, 40, 31)),
        ]
        for (offset, range) in favorites.enumerated() {
            let favorite = Favorite(range: range)
            favorite.createdAt = now.addingTimeInterval(TimeInterval(-60 * offset))
            context.insert(favorite)
        }
        for verse in 16...17 {
            context.insert(Highlight(verseKey: VerseRef(.john, 3, verse).key, color: .yellow))
        }
        context.insert(Highlight(verseKey: VerseRef(.philippians, 4, 13).key, color: .blue))
        context.insert(Highlight(verseKey: VerseRef(.psalms, 23, 1).key, color: .green))
        context.insert(Highlight(verseKey: VerseRef(.john, 14, 6).key, color: .purple))
        // More for the Highlights lists (phone and watch screenshots): none of these chapters is
        // on screen in another scene, so the reader scenes keep just the marks above.
        let more: [(BookID, Int, ClosedRange<Int>, HighlightColor)] = [
            (.proverbs, 3, 5...6, .pink),
            (.isaiah, 40, 31...31, .blue),
            (.lamentations, 3, 22...23, .yellow),
            (.matthew, 11, 28...30, .green),
            (.romans, 8, 28...28, .purple),
        ]
        for (book, chapter, verses, color) in more {
            for verse in verses { context.insert(Highlight(verseKey: VerseRef(book, chapter, verse).key, color: color)) }
        }
        let romans = Note(title: String(localized: "Sunday sermon: No condemnation", comment: "Sample note shown in App Store screenshots (demo mode)"),
                          body: String(localized: "Life in the Spirit. Verse 1 is the hinge — everything after it flows from “no condemnation.”", comment: "Sample note shown in App Store screenshots (demo mode)"),
                          anchors: [VerseRange(VerseRef(.romans, 8, 1), VerseRef(.romans, 8, 17))])
        romans.updatedAt = now.addingTimeInterval(-86_400 * 7)
        context.insert(romans)
        let nicodemus = Note(title: String(localized: "Evening sermon: Born of the Spirit", comment: "Sample note shown in App Store screenshots (demo mode)"),
                             body: String(localized: "• Nicodemus comes by night (v. 2)\n• “You must be born anew” — the Spirit’s work, not ours\n• The serpent in the wilderness points to the cross (Numbers 21:8–9)", comment: "Sample note shown in App Store screenshots (demo mode)"),
                             anchors: [VerseRange(VerseRef(.john, 3, 1), VerseRef(.john, 3, 21)),
                                       VerseRange(VerseRef(.numbers, 21, 4), VerseRef(.numbers, 21, 9))],
                             origin: "camera")
        nicodemus.updatedAt = now.addingTimeInterval(-86_400 * 3)
        context.insert(nicodemus)
        try? context.save()
        #endif
    }
}
