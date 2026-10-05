import Foundation
import SwiftData
import Testing
import ScriptureAloneCore
@testable import Scripture_Alone

/// The SwiftData models are the reader's own notes, highlights and favourites — the one thing in the
/// app that cannot be downloaded again. Their stored form syncs through CloudKit, so it must stay
/// readable by every older and newer copy of the app on the reader's other devices.
@MainActor
struct ModelsTests {
    let romans8 = VerseRange(VerseRef(.romans, 8, 1), VerseRef(.romans, 8, 17))
    let john316 = VerseRange(VerseRef(.john, 3, 16))
    let numbers21 = VerseRange(VerseRef(.numbers, 21, 8), VerseRef(.numbers, 21, 9))

    static func container() throws -> ModelContainer {
        try ModelContainer(for: DataStore.schema, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
    }

    // MARK: Note anchors

    @Test func anchorsAreStoredSortedAndFirstVerseKeyFollowsTheEarliest() {
        let note = Note(title: "Lifted up", anchors: [john316, numbers21])
        #expect(note.anchors == [numbers21, john316], "canonical order, whatever order they were given in")
        #expect(note.anchorsRaw == "\(numbers21.storageString),\(john316.storageString)")
        #expect(note.firstVerseKey == numbers21.start.key)

        note.anchors = [romans8]
        #expect(note.anchors == [romans8])
        #expect(note.firstVerseKey == VerseRef(.romans, 8, 1).key)
    }

    @Test func clearingTheAnchorsResetsTheSortKey() {
        let note = Note(anchors: [john316])
        note.anchors = []
        #expect(note.anchorsRaw.isEmpty)
        #expect(note.firstVerseKey == 0)
        #expect(note.anchors.isEmpty)
    }

    /// A row synced from a damaged or future copy must not take the rest of the note down with it.
    @Test func malformedStoredAnchorsAreSkippedNotFatal() {
        let note = Note()
        note.anchorsRaw = "garbage,\(john316.storageString),,43003016,99-1"
        #expect(note.anchors == [john316])
    }

    @Test func displayTitleFallsBackToThePassageThenToUntitled() {
        #expect(Note(title: "  Sunday sermon \n").displayTitle == "Sunday sermon")
        #expect(Note(title: "   ", anchors: [romans8]).displayTitle == romans8.display)
        #expect(Note().displayTitle == String(localized: "Untitled Note"))
    }

    @Test func anchorSummaryListsEveryPassage() {
        let note = Note(anchors: [john316, numbers21])
        #expect(note.anchorSummary == "\(numbers21.display) · \(john316.display)")
    }

    @Test func touchesOnlyTheChaptersItsPassagesCover() {
        let note = Note(anchors: [VerseRange(VerseRef(.john, 3, 36), VerseRef(.john, 4, 2))])
        #expect(note.touches(ChapterRef(.john, 3)))
        #expect(note.touches(ChapterRef(.john, 4)), "a range crossing a chapter boundary shows in both")
        #expect(!note.touches(ChapterRef(.john, 5)))
        #expect(!note.touches(ChapterRef(.romans, 3)))
        #expect(!Note().touches(ChapterRef(.john, 3)))
    }

    @Test func exportValueCarriesEverythingAKeepsakeNeeds() {
        let note = Note(title: "T", body: "B", anchors: [john316], origin: "camera")
        let value = note.exportValue
        #expect(value.id == note.uuid)
        #expect(value.title == "T" && value.body == "B")
        #expect(value.anchors == [john316])
        #expect(value.origin == "camera")
        #expect(value.createdAt == note.createdAt && value.updatedAt == note.updatedAt)
    }

    // MARK: Favorite

    @Test func favoriteRangeRoundTripsWithItsSortKeys() {
        let favorite = Favorite(range: romans8)
        #expect(favorite.range == romans8)
        #expect(favorite.startKey == romans8.start.key)
        #expect(favorite.endKey == romans8.end.key)
        favorite.rangeRaw = "not a range"
        #expect(favorite.range == nil)
    }

    @Test func highlightKeepsItsColorName() {
        let highlight = Highlight(verseKey: john316.start.key, color: .blue)
        #expect(highlight.colorName == HighlightColor.blue.rawValue)
        #expect(highlight.verseKey == 43_003_016)
    }

    // MARK: The store

    @Test func notesHighlightsAndFavoritesPersistAndDelete() throws {
        let container = try Self.container()
        let context = ModelContext(container)
        let note = Note(title: "No condemnation", body: "Pastor Jim", anchors: [romans8])
        context.insert(note)
        context.insert(Highlight(verseKey: john316.start.key, color: .yellow))
        context.insert(Favorite(range: numbers21))
        try context.save()

        // A fresh context reads back what was saved, not the objects still in memory.
        let reader = ModelContext(container)
        let notes = try reader.fetch(FetchDescriptor<Note>())
        #expect(notes.count == 1)
        #expect(notes.first?.anchors == [romans8])
        #expect(notes.first?.uuid == note.uuid)
        #expect(try reader.fetch(FetchDescriptor<Highlight>()).first?.verseKey == john316.start.key)
        #expect(try reader.fetch(FetchDescriptor<Favorite>()).first?.range == numbers21)

        // The canonical-order query the Notes panel uses.
        reader.insert(Note(title: "Earlier", anchors: [numbers21]))
        try reader.save()
        let sorted = try reader.fetch(FetchDescriptor<Note>(sortBy: [SortDescriptor(\.firstVerseKey)]))
        #expect(sorted.map(\.title) == ["Earlier", "No condemnation"])

        for note in sorted { reader.delete(note) }
        try reader.save()
        #expect(try ModelContext(container).fetchCount(FetchDescriptor<Note>()) == 0)
        #expect(try ModelContext(container).fetchCount(FetchDescriptor<Favorite>()) == 1, "deleting notes leaves favourites alone")
    }

    /// CloudKit-backed SwiftData refuses a schema with a unique constraint or a non-optional
    /// relationship, and every stored property needs a default so a record from an older copy of the
    /// app decodes. The models say so in a comment; this holds them to it.
    @Test func theSchemaFollowsCloudKitRules() {
        for entity in DataStore.schema.entities {
            #expect(entity.uniquenessConstraints.isEmpty, "\(entity.name) has a unique constraint")
            for relationship in entity.relationships {
                #expect(relationship.isOptional, "\(entity.name).\(relationship.name) must be optional")
            }
            for attribute in entity.attributes {
                #expect(attribute.isOptional || attribute.defaultValue != nil,
                        "\(entity.name).\(attribute.name) has no default — an older device's record would not decode")
            }
        }
        #expect(Set(DataStore.schema.entities.map(\.name)) == ["Highlight", "Note", "Favorite"])
    }

    /// Changing a stored property's name or type is a migration on every one of the reader's
    /// devices; this pins the stored shape so that is never done by accident.
    @Test func storedPropertyNamesArePinned() {
        func names(_ entity: String) -> Set<String> {
            Set(DataStore.schema.entities.first { $0.name == entity }?.attributes.map(\.name) ?? [])
        }
        #expect(names("Highlight") == ["verseKey", "colorName", "createdAt"])
        #expect(names("Note") == ["uuid", "title", "body", "anchorsRaw", "firstVerseKey", "createdAt", "updatedAt", "origin", "slidePhoto"])
        #expect(names("Favorite") == ["uuid", "rangeRaw", "startKey", "endKey", "createdAt"])
    }
}
