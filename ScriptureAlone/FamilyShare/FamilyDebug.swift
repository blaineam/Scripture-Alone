#if DEBUG
import Foundation
import ScriptureAloneCore

/// DEBUG-only stand-ins for CloudKit, so family sharing's screens can be seen on a simulator
/// with no iCloud account (real sharing needs two signed-in devices; see
/// docs/family-sharing-test-plan.md). Release builds compile none of this.
///
/// - `-fakeSharedBible live|ended` — a shared Bible from "Dad", built by running invented demo
///   content through the real mirror mapping (`FamilyMirror.records` → `FamilyBibleSnapshot`).
/// - `-fakeFamilyOwner` — the owner's screen with sharing on and two family members.
/// - `-familyScene reader|library|owner|owner-bottom` — opens that screen at launch: the shared
///   Bible in the reader (`-familyVerse N` picks the John 3 verse), Legacy & Export, or Share
///   with Family (scrolled to its end).
///
/// Any of these keeps CloudKit untouched.
nonisolated enum FamilyDebug {
    private static var arguments: [String] { ProcessInfo.processInfo.arguments }

    private static func value(after flag: String) -> String? {
        guard let index = arguments.firstIndex(of: flag), arguments.indices.contains(index + 1) else { return nil }
        let value = arguments[index + 1]
        return value.hasPrefix("-") ? nil : value
    }

    static var fakeSharedBibleMode: String? {
        guard arguments.contains("-fakeSharedBible") else { return nil }
        return value(after: "-fakeSharedBible") ?? "live"
    }

    static var fakeOwner: Bool { arguments.contains("-fakeFamilyOwner") }
    static var isFaking: Bool { fakeSharedBibleMode != nil || fakeOwner }
    static var scene: String? { value(after: "-familyScene") }
    /// `-familyVerse N`: the John 3 verse the reader scene scrolls to.
    static var verse: Int? { value(after: "-familyVerse").flatMap(Int.init) }

    static let demoOwnerID = "_demo_dad"

    static var participants: [FamilyParticipant] {
        [
            FamilyParticipant(id: "owner", name: "Blaine Miller", status: .owner),
            FamilyParticipant(id: "anna", name: "Anna Miller", detail: "anna@example.com", status: .accepted),
            FamilyParticipant(id: "sam", name: "Sam Miller", detail: "+1 (555) 010-0199", status: .invited),
        ]
    }

    @MainActor
    static func sharedBible() -> SharedBibleLibrary.Entry? {
        guard let mode = fakeSharedBibleMode else { return nil }
        let now = Date.now
        func key(_ book: BookID, _ chapter: Int, _ verse: Int) -> Int { VerseRef(book, chapter, verse).key }
        let highlights = [
            KeepsakeHighlight(verse: key(.john, 3, 16), color: "yellow", createdAt: now.addingTimeInterval(-86_400 * 400)),
            KeepsakeHighlight(verse: key(.john, 3, 17), color: "yellow", createdAt: now.addingTimeInterval(-86_400 * 400)),
            KeepsakeHighlight(verse: key(.john, 3, 30), color: "green", createdAt: now.addingTimeInterval(-86_400 * 20)),
            KeepsakeHighlight(verse: key(.psalms, 23, 1), color: "green", createdAt: now.addingTimeInterval(-86_400 * 900)),
            KeepsakeHighlight(verse: key(.romans, 8, 28), color: "blue", createdAt: now.addingTimeInterval(-86_400 * 60)),
        ]
        let notes = [
            KeepsakeNote(id: UUID(uuidString: "D0D0D0D0-0000-4000-8000-000000000001")!,
                         title: "Born again",
                         body: "Nicodemus came at night with questions, and Jesus didn’t send him away. Neither should we. — Written for Anna the week she was baptized.",
                         anchors: [VerseRange(VerseRef(.john, 3, 1), VerseRef(.john, 3, 8))],
                         createdAt: now.addingTimeInterval(-86_400 * 30), updatedAt: now.addingTimeInterval(-300)),
            KeepsakeNote(id: UUID(uuidString: "D0D0D0D0-0000-4000-8000-000000000002")!,
                         title: "He must increase",
                         body: "John the Baptist’s whole life in one line. Mine too, I hope.",
                         anchors: [VerseRange(VerseRef(.john, 3, 30))],
                         createdAt: now.addingTimeInterval(-86_400 * 20), updatedAt: now.addingTimeInterval(-86_400 * 20)),
        ]
        let favorites = [
            FamilyFavorite(id: UUID(uuidString: "D0D0D0D0-0000-4000-8000-0000000000F1")!,
                           start: key(.romans, 8, 38), end: key(.romans, 8, 39), createdAt: now.addingTimeInterval(-86_400)),
            FamilyFavorite(id: UUID(uuidString: "D0D0D0D0-0000-4000-8000-0000000000F2")!,
                           start: key(.psalms, 23, 1), end: key(.psalms, 23, 1), createdAt: now.addingTimeInterval(-86_400 * 90)),
        ]
        let input = FamilyMirrorInput(
            profile: FamilyOwnerProfile(bibleID: UUID(uuidString: "D0D0D0D0-0000-4000-8000-00000000B1B1")!,
                                        ownerName: "Dad",
                                        dedication: "For Anna and Sam — read it slowly, and read it together.",
                                        preferredTranslation: "ASV"),
            highlights: highlights, notes: notes, favorites: favorites)
        // The same path a real fetch takes: records in, snapshot out.
        var snapshot = FamilyBibleSnapshot()
        snapshot.apply(changed: FamilyMirror.records(for: input), deleted: [])
        var entry = SharedBibleLibrary.Entry(id: demoOwnerID, snapshot: snapshot, fallbackID: UUID())
        entry.lastFetched = now.addingTimeInterval(-5 * 60)
        if mode == "ended" { entry.ended = now.addingTimeInterval(-3_600) }
        return entry
    }
}
#endif
