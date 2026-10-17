import Foundation
import SwiftData
import SwiftUI
import Testing
import ScriptureAloneCore
@testable import Scripture_Alone

/// The rest of Siri, Shortcuts and Spotlight's surface: finding and opening notes, the favorite
/// checks, the entities the system indexes, and the snippets it shows. Intents run against
/// `DataStore.shared`, which the test scheme keeps in memory (`-inMemoryStore`); every note and
/// favorite a test makes is removed again.
@MainActor
@Suite(.serialized)
struct IntentsMoreTests {
    func note(_ marker: String, passage: String? = "John 3:16") async throws -> Note {
        let intent = CreateNoteIntent()
        intent.noteTitle = "Title \(marker)"
        intent.text = "Body \(marker)\nsecond line"
        intent.passage = passage
        _ = try await intent.perform()
        return try #require(IntentLibrary.notes().first { $0.body.contains(marker) })
    }

    func remove(_ note: Note) {
        IntentLibrary.context.delete(note)
        try? IntentLibrary.context.save()
    }

    /// The commands the center holds, taken (emptying it).
    func takeCommands() -> [AppCommand] { AppCommandCenter.shared.take() }

    // MARK: Notes

    @Test func findNotesAnswersForWordsAndForNothing() async throws {
        let marker = "find-\(UUID().uuidString)"
        let saved = try await note(marker)
        defer { remove(saved) }

        let find = FindNotesIntent()
        find.query = marker
        _ = try await find.perform()
        find.query = nil
        _ = try await find.perform()
        find.query = "zzz-no-such-note-\(UUID().uuidString)"
        _ = try await find.perform()

        let entities = try await NoteQuery().entities(matching: marker)
        #expect(entities.map(\.id) == [saved.uuid])
        let entity = try #require(entities.first)
        #expect(entity.title == "Title \(marker)")
        #expect(entity.passages == "John 3:16")
        #expect(entity.excerpt == "Body \(marker) second line", "newlines flatten in the excerpt")
        #expect(entity.attributeSet.title == entity.title)
        #expect(entity.attributeSet.keywords == ["John 3:16"])
        _ = entity.displayRepresentation
        #expect(try await NoteQuery().entities(for: [saved.uuid, UUID()]).map(\.id) == [saved.uuid])
        #expect(try await NoteQuery().suggestedEntities().contains { $0.id == saved.uuid })
    }

    @Test func aLongNoteHasAShortExcerpt() {
        let entity = NoteEntity(id: UUID(), title: "Long", body: String(repeating: "word ", count: 80), passages: "", modified: .now)
        #expect(entity.excerpt.count == 161)
        #expect(entity.excerpt.hasSuffix("…"))
        #expect(entity.attributeSet.keywords == [""])
    }

    @Test func openingAndStartingNotesPostCommandsForTheReader() async throws {
        _ = takeCommands()
        let marker = "open-\(UUID().uuidString)"
        let saved = try await note(marker)
        defer { remove(saved) }
        _ = takeCommands()

        let entity = NoteEntity(saved)
        _ = try await OpenNoteIntent(target: entity).perform()
        #expect(takeCommands() == [.link(.note(saved.uuid))])

        let fresh = NewNoteIntent()
        fresh.passage = "Romans 8:28"
        _ = try await fresh.perform()
        #expect(takeCommands() == [.newNote([VerseRange(VerseRef(.romans, 8, 28))])])
        fresh.passage = nil
        _ = try await fresh.perform()
        #expect(takeCommands() == [.newNote([])])
        fresh.passage = "Hezekiah 4:1"
        await #expect(throws: ScriptureIntentError.self) { _ = try await fresh.perform() }
        #expect(takeCommands().isEmpty, "a passage that isn't there starts nothing")
    }

    @Test func theNotesSnippetDrawsWithAndWithoutMoreNotes() async throws {
        let entities = [NoteEntity(id: UUID(), title: "One", body: "First body", passages: "John 3:16", modified: .now),
                        NoteEntity(id: UUID(), title: "Two", body: "", passages: "", modified: .now)]
        #expect(await ViewHost.render(NotesSnippet(notes: entities, more: 0), size: CGSize(width: 360, height: 300)).isDrawn)
        #expect(await ViewHost.render(NotesSnippet(notes: entities, more: 7), size: CGSize(width: 360, height: 300), dark: true).isDrawn)
        #expect(await ViewHost.render(NotesSnippet(notes: [], more: 0), size: CGSize(width: 360, height: 300)).isLaidOut)
    }

    // MARK: Favorites

    @Test func isFavoriteAnswersYesAndNoAndOpenFavoriteHandsOverTheRange() async throws {
        _ = takeCommands()
        let passage = "Jude 1:24-25"
        let range = VerseRange(VerseRef(.jude, 1, 24), VerseRef(.jude, 1, 25))
        let check = IsFavoriteIntent()
        check.passage = passage
        _ = try await check.perform()
        #expect(!IntentLibrary.favorites().contains { $0.rangeRaw == range.storageString })

        let add = AddToFavoritesIntent()
        add.passage = passage
        _ = try await add.perform()
        defer {
            for favorite in IntentLibrary.favorites() where favorite.rangeRaw == range.storageString { IntentLibrary.context.delete(favorite) }
            try? IntentLibrary.context.save()
        }
        _ = try await check.perform()
        #expect(IntentLibrary.favorites().contains { $0.rangeRaw == range.storageString })

        let entity = try #require(try await FavoriteVerseQuery().entities(for: [range.storageString]).first)
        #expect(entity.range == range)
        #expect(entity.reference == "Jude 1:24–25")
        #expect(entity.translation == "ASV" || !entity.translation.isEmpty)
        // Every translation says "stumbling"; the wording around it differs.
        #expect(entity.text.contains("stumbling"))
        #expect(entity.attributeSet.keywords?.first == entity.reference)
        _ = entity.displayRepresentation
        #expect(try await FavoriteVerseQuery().entities(matching: "stumbling").contains { $0.id == entity.id })
        #expect(try await FavoriteVerseQuery().entities(matching: "zzzzqq").isEmpty)

        let open = OpenFavoriteIntent()
        open.target = entity
        _ = try await open.perform()
        #expect(takeCommands() == [.link(.open([range]))])

        let remove = RemoveFromFavoritesIntent()
        remove.passage = "Jude 1:1"
        _ = try await remove.perform()      // wasn't a favorite: answers false, removes nothing
        #expect(IntentLibrary.favorites().contains { $0.rangeRaw == range.storageString })
    }

    // MARK: Reading

    @Test func listenAndSnippetsCarryTheirPassages() async throws {
        _ = takeCommands()
        let listen = ListenToChapterIntent()
        _ = try await listen.perform()
        #expect(takeCommands() == [.listen(nil)])
        listen.passage = "Psalm 23"
        _ = try await listen.perform()
        let posted = takeCommands()
        #expect(posted.count == 1)
        if case .listen(let link) = posted.first { #expect(link != nil) } else { Issue.record("expected a listen command") }
        listen.passage = "Hezekiah 4:1"
        await #expect(throws: ScriptureIntentError.self) { _ = try await listen.perform() }

        let open = OpenVerseLinkIntent(ranges: [VerseRange(VerseRef(.john, 3, 16))])
        #expect(await ViewHost.render(VerseSnippet(theme: "Hope", text: "For God so loved the world", reference: "John 3:16",
                                                   translation: "ASV", open: open), size: CGSize(width: 360, height: 220)).isDrawn)
        #expect(await ViewHost.render(VerseSnippet(theme: nil, text: "Plain", reference: "John 3:16", translation: "ASV", open: nil),
                                      size: CGSize(width: 360, height: 220), dark: true).isDrawn)
    }

    @Test func verseOfTheDayAndGetVersesAnswerWithSnippets() async throws {
        _ = try await VerseOfTheDayIntent().perform()
        let get = GetVersesIntent()
        get.passage = "John 3:16-17"
        _ = try await get.perform()
        get.passage = "Psalm 117"
        _ = try await get.perform()
    }

    @Test func everyShapeAndDesignHasADisplayName() {
        for template in VerseImageTemplate.allCases {
            #expect(VerseImageTemplate.caseDisplayRepresentations[template] != nil, "\(template)")
        }
        for shape in VerseImageShape.allCases {
            #expect(VerseImageShape.caseDisplayRepresentations[shape] != nil, "\(shape)")
        }
        #expect(ShareBackground(rawValue: VerseImageTemplate.parchment.rawValue) == .parchment)
    }
}
