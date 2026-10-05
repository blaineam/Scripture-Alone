import Foundation
import SwiftData
import Testing
import ScriptureAloneCore
@testable import Scripture_Alone

/// Siri, Shortcuts and Spotlight: how a spoken or typed passage becomes stored keys, the quotation
/// they read back, and the intents that write the reader's notes and favourites. The intents run
/// against `DataStore.shared`, which the test scheme keeps in memory (`-inMemoryStore`).
@MainActor
@Suite(.serialized)
struct IntentsTests {
    var asv: any ChapterTextSource {
        get throws { try #require(IntentLibrary.source(for: "ASV"), "the ASV is in every Debug build") }
    }

    // MARK: Passages

    @Test func aTypedReferenceResolvesToStoredKeys() throws {
        let resolved = try IntentLibrary.resolve("John 3:16-18", in: try asv)
        #expect(resolved.map(\.kjv) == [VerseRange(VerseRef(.john, 3, 16), VerseRef(.john, 3, 18))])
        #expect(resolved.map(\.native) == resolved.map(\.kjv), "the ASV numbers John as the KJV does")
    }

    @Test(arguments: ["John.3.16", "urn:osis:John.3.16", "jn 3:16", "John 3 16"])
    func otherSpellingsOfTheSameVerse(text: String) throws {
        let resolved = try IntentLibrary.resolve(text, in: try asv)
        #expect(resolved.first?.kjv.start == VerseRef(.john, 3, 16), "\(text)")
    }

    @Test func aWholeChapterIsClampedToItsVerses() throws {
        let resolved = try IntentLibrary.resolve("Psalm 117", in: try asv)
        #expect(resolved.first?.kjv == VerseRange(VerseRef(.psalms, 117, 1), VerseRef(.psalms, 117, 2)))
    }

    /// A chapter past the end of the book is the last chapter, as the reader's Go To clamps it.
    @Test func aChapterPastTheEndIsTheLastChapter() throws {
        let resolved = try IntentLibrary.resolve("John 99", in: try asv)
        #expect(resolved.first?.kjv.start == VerseRef(.john, 21, 1))
    }

    @Test(arguments: ["the sermon on the mount", "Jude 1:99", ""])
    func passagesThatAreNotThereAreRefused(text: String) throws {
        let source = try asv
        #expect(throws: ScriptureIntentError.self) { try IntentLibrary.resolve(text, in: source) }
    }

    @Test func quotationsReadLikeTheReadersCopy() throws {
        let source = try asv
        let one = try IntentLibrary.resolve("John 3:16", in: source)
        let quote = IntentLibrary.quotation(one, in: source)
        #expect(quote.hasPrefix("For God so loved the world"))
        #expect(quote.hasSuffix("— John 3:16 (ASV)"))
        let two = try IntentLibrary.resolve("Psalm 117", in: source)
        #expect(IntentLibrary.quotation(two, in: source).hasPrefix("1 "))
        #expect(IntentLibrary.verseCount(two, in: source) == 2)

    }

    @Test func theASVIsAlwaysAvailableToIntents() {
        #expect(IntentLibrary.availableTranslations().contains { $0.id == "ASV" })
        #expect(IntentLibrary.source(for: "NOPE") == nil)
    }

    // MARK: Notes

    @Test func createNoteSavesTheNoteWithItsPassage() async throws {
        let marker = "intent-test-\(UUID().uuidString)"
        let intent = CreateNoteIntent()
        intent.noteTitle = "  From Siri  "
        intent.text = marker
        intent.passage = "Romans 8:1-4"
        _ = try await intent.perform()
        defer { delete(notesWithBody: marker) }

        let saved = IntentLibrary.notes().filter { $0.body == marker }
        #expect(saved.count == 1)
        let note = try #require(saved.first)
        #expect(note.title == "From Siri")
        #expect(note.anchors == [VerseRange(VerseRef(.romans, 8, 1), VerseRef(.romans, 8, 4))])
        #expect(note.firstVerseKey == VerseRef(.romans, 8, 1).key)
        #expect(IntentLibrary.note(note.uuid) === note)

        // Find Notes finds it by words and by an overlapping passage, and not by another passage.
        #expect(NotesSearch.matching(marker).map(\.uuid) == [note.uuid])
        #expect(NotesSearch.matching("Romans 8:3").contains { $0.uuid == note.uuid })
        #expect(!NotesSearch.matching("Romans 9:1").contains { $0.uuid == note.uuid })
    }

    @Test func aNoteWithoutAPassageHasNoAnchors() async throws {
        let marker = "intent-test-\(UUID().uuidString)"
        let intent = CreateNoteIntent()
        intent.text = marker
        intent.passage = "   "
        _ = try await intent.perform()
        defer { delete(notesWithBody: marker) }
        let note = try #require(IntentLibrary.notes().first { $0.body == marker })
        #expect(note.anchors.isEmpty)
        #expect(note.title.isEmpty)
    }

    @Test func aNoteOnAPassageThatIsNotThereIsNotSaved() async throws {
        let marker = "intent-test-\(UUID().uuidString)"
        let intent = CreateNoteIntent()
        intent.text = marker
        intent.passage = "Hezekiah 4:1"
        await #expect(throws: ScriptureIntentError.self) { _ = try await intent.perform() }
        #expect(!IntentLibrary.notes().contains { $0.body == marker })
    }

    // MARK: Favorites

    @Test func favoritesAddOnceCheckAndRemove() async throws {
        let passage = "Obadiah 1:3-4"
        let range = VerseRange(VerseRef(.obadiah, 1, 3), VerseRef(.obadiah, 1, 4))
        defer { deleteFavorites(range) }

        let add = AddToFavoritesIntent()
        add.passage = passage
        _ = try await add.perform()
        _ = try await add.perform()          // a second time is not a second favourite
        #expect(IntentLibrary.favorites().filter { $0.rangeRaw == range.storageString }.count == 1)
        let suggested = try await FavoriteVerseQuery().suggestedEntities()
        #expect(suggested.filter { $0.id == range.storageString }.count == 1)
        let byID = try await FavoriteVerseQuery().entities(for: [range.storageString, "garbage"])
        #expect(byID.map(\.id) == [range.storageString])

        let remove = RemoveFromFavoritesIntent()
        remove.passage = passage
        _ = try await remove.perform()
        #expect(!IntentLibrary.favorites().contains { $0.rangeRaw == range.storageString })
    }

    // MARK: Helpers

    func delete(notesWithBody body: String) {
        let context = IntentLibrary.context
        for note in IntentLibrary.notes() where note.body == body { context.delete(note) }
        try? context.save()
    }

    func deleteFavorites(_ range: VerseRange) {
        let context = IntentLibrary.context
        for favorite in IntentLibrary.favorites() where favorite.rangeRaw == range.storageString { context.delete(favorite) }
        try? context.save()
    }
}
