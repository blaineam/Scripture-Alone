import Foundation
import SwiftData
import ScriptureAloneCore

/// DEBUG-only demo content for simulator verification and screenshot rigs: launch with
/// `-seedDemoLibrary` and an empty store gets a few favorites, highlights and a note — no
/// personal data, no taps needed. Compiled into the phone/Mac app and the watch app.
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
        context.insert(Note(title: "Sunday sermon: No condemnation",
                            body: "Life in the Spirit. Verse 1 is the hinge — everything after it flows from “no condemnation.”",
                            anchors: [VerseRange(VerseRef(.romans, 8, 1), VerseRef(.romans, 8, 17))]))
        try? context.save()
        #endif
    }
}
