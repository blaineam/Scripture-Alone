import AppIntents
import CoreSpotlight
import Foundation
import UniformTypeIdentifiers
import ScriptureAloneCore

// App Intents types are `nonisolated`: the system resolves them off the main actor, and a
// main-actor conformance (this target's default isolation) would not be found there. Work that
// touches the app's state hops to the main actor inside `perform` and the queries.

// MARK: Translation

/// A translation on this device, for an intent's optional "in translation" parameter.
nonisolated struct TranslationEntity: AppEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation {
        TypeDisplayRepresentation(name: LocalizedStringResource("Translation", comment: "App Intents type name: a Bible translation"))
    }
    static var defaultQuery: TranslationQuery { TranslationQuery() }

    /// "ASV", "KJV", "LSG".
    let id: String
    let name: String

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(name)", subtitle: "\(id)")
    }
}

nonisolated struct TranslationQuery: EntityStringQuery {
    @MainActor
    func entities(for identifiers: [String]) async throws -> [TranslationEntity] {
        let available = IntentLibrary.availableTranslations()
        return identifiers.compactMap { id in
            available.first { $0.id == id }.map { TranslationEntity(id: $0.id, name: $0.name) }
        }
    }

    @MainActor
    func entities(matching string: String) async throws -> [TranslationEntity] {
        IntentLibrary.availableTranslations()
            .filter { $0.id.localizedCaseInsensitiveContains(string) || $0.name.localizedCaseInsensitiveContains(string) }
            .map { TranslationEntity(id: $0.id, name: $0.name) }
    }

    @MainActor
    func suggestedEntities() async throws -> [TranslationEntity] {
        IntentLibrary.availableTranslations().map { TranslationEntity(id: $0.id, name: $0.name) }
    }
}

// MARK: Note

/// A note, for Shortcuts, Siri and — when the reader turns it on — Spotlight.
struct NoteEntity: IndexedEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation {
        TypeDisplayRepresentation(name: LocalizedStringResource("Note", comment: "App Intents type name: a note on a Bible passage"))
    }
    static var defaultQuery: NoteQuery { NoteQuery() }

    let id: UUID

    @Property(title: LocalizedStringResource("Title", comment: "App Intents property of a note"))
    var title: String

    @Property(title: LocalizedStringResource("Body", comment: "App Intents property of a note: its text"))
    var body: String

    /// "John 3:16 · Numbers 21:8–9", as the reader's translation numbers them.
    @Property(title: LocalizedStringResource("Passages", comment: "App Intents property of a note: the Bible passages it is attached to"))
    var passages: String

    @Property(title: LocalizedStringResource("Modified", comment: "App Intents property of a note: when it was last changed"))
    var modified: Date

    init(id: UUID, title: String, body: String, passages: String, modified: Date) {
        self.id = id
        self.title = title
        self.body = body
        self.passages = passages
        self.modified = modified
    }

    /// A few lines of the body, for results and Spotlight.
    var excerpt: String {
        let flat = body.split(whereSeparator: \.isNewline).joined(separator: " ")
        return flat.count > 160 ? String(flat.prefix(160)) + "…" : flat
    }

    var displayRepresentation: DisplayRepresentation {
        let subtitle = [passages, excerpt].filter { !$0.isEmpty }.joined(separator: " — ")
        return DisplayRepresentation(title: "\(title)", subtitle: "\(subtitle)",
                                     image: DisplayRepresentation.Image(systemName: "note.text"))
    }

    var attributeSet: CSSearchableItemAttributeSet {
        let set = CSSearchableItemAttributeSet(contentType: .text)
        set.title = title
        set.displayName = title
        set.contentDescription = [passages, excerpt].filter { !$0.isEmpty }.joined(separator: "\n")
        set.textContent = body
        set.keywords = passages.components(separatedBy: " · ")
        set.contentModificationDate = modified
        return set
    }
}

extension NoteEntity {
    @MainActor
    init(_ note: Note, numbering: VerseNumbering? = nil) {
        let numbering = numbering ?? IntentLibrary.currentSource?.numbering ?? .identity
        let passages = note.anchors.map { (numbering.nativeRange($0) ?? $0).display }.joined(separator: " · ")
        self.init(id: note.uuid, title: note.displayTitle, body: note.body, passages: passages, modified: note.updatedAt)
    }
}

nonisolated struct NoteQuery: EntityStringQuery {
    @MainActor
    func entities(for identifiers: [UUID]) async throws -> [NoteEntity] {
        identifiers.compactMap { IntentLibrary.note($0).map { NoteEntity($0) } }
    }

    @MainActor
    func entities(matching string: String) async throws -> [NoteEntity] {
        NotesSearch.matching(string).map { NoteEntity($0) }
    }

    @MainActor
    func suggestedEntities() async throws -> [NoteEntity] {
        IntentLibrary.notes().prefix(30).map { NoteEntity($0) }
    }
}

/// Finds notes the way the Notes panel's search does: a passage finds the notes on it, anything
/// else matches title, text and references.
@MainActor
enum NotesSearch {
    static func matching(_ query: String) -> [Note] {
        let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let notes = IntentLibrary.notes()
        guard !term.isEmpty else { return notes }
        if term.rangeOfCharacter(from: .decimalDigits) != nil, let source = IntentLibrary.currentSource,
           let ranges = try? IntentLibrary.resolve(term, in: source).map(\.kjv), !ranges.isEmpty {
            return notes.filter { note in
                note.anchors.contains { anchor in ranges.contains { anchor.start <= $0.end && $0.start <= anchor.end } }
            }
        }
        return notes.filter { note in
            [note.title, note.body, note.anchorSummary].contains { $0.localizedStandardContains(term) }
        }
    }
}

// MARK: Favorite

/// A favorited passage. Its id is the stored (KJV) range, so the same favorite made on two devices
/// is one entity, and one Spotlight result.
struct FavoriteVerseEntity: IndexedEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation {
        TypeDisplayRepresentation(name: LocalizedStringResource("Favorite Verse", comment: "App Intents type name: a favorited Bible passage"))
    }
    static var defaultQuery: FavoriteVerseQuery { FavoriteVerseQuery() }

    /// "43003016-43003017"
    let id: String

    /// "John 3:16–17", as the reader's translation numbers it.
    @Property(title: LocalizedStringResource("Reference", comment: "App Intents property of a favorite verse"))
    var reference: String

    /// The passage in the reader's translation.
    @Property(title: LocalizedStringResource("Text", comment: "App Intents property of a favorite verse: the verse text"))
    var text: String

    @Property(title: LocalizedStringResource("Translation", comment: "App Intents property of a favorite verse: the translation its text is from"))
    var translation: String

    init(id: String, reference: String, text: String, translation: String) {
        self.id = id
        self.reference = reference
        self.text = text
        self.translation = translation
    }

    var range: VerseRange? { VerseRange(storageString: id) }

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(reference)", subtitle: "\(text)",
                              image: DisplayRepresentation.Image(systemName: "heart.fill"))
    }

    var attributeSet: CSSearchableItemAttributeSet {
        let set = CSSearchableItemAttributeSet(contentType: .text)
        set.title = reference
        set.displayName = reference
        set.contentDescription = text
        set.textContent = "\(reference) \(text)"
        set.keywords = [reference, translation]
        return set
    }
}

extension FavoriteVerseEntity {
    /// Text is capped at a dozen verses: a favorited chapter is still one result, not a chapter's
    /// worth of index.
    @MainActor
    init?(range: VerseRange, source: (any ChapterTextSource)?) {
        let source = source ?? IntentLibrary.currentSource
        let native = source?.numbering.nativeRange(range) ?? range
        let capped = VerseRange(range.start, min(range.end, VerseRef(range.start.book, range.start.chapter, range.start.verse + 12)))
        let verses = (try? source?.verses(in: capped)) ?? []
        self.init(id: range.storageString, reference: native.display,
                  text: verses.isEmpty ? "" : IntentLibrary.text(of: verses),
                  translation: source?.info.abbreviation ?? "")
    }
}

nonisolated struct FavoriteVerseQuery: EntityStringQuery {
    @MainActor
    func entities(for identifiers: [String]) async throws -> [FavoriteVerseEntity] {
        let source = IntentLibrary.currentSource
        return identifiers.compactMap { VerseRange(storageString: $0) }
            .compactMap { FavoriteVerseEntity(range: $0, source: source) }
    }

    @MainActor
    func entities(matching string: String) async throws -> [FavoriteVerseEntity] {
        try await suggestedEntities().filter {
            $0.reference.localizedStandardContains(string) || $0.text.localizedStandardContains(string)
        }
    }

    @MainActor
    func suggestedEntities() async throws -> [FavoriteVerseEntity] {
        let source = IntentLibrary.currentSource
        var seen = Set<String>()
        return IntentLibrary.favorites().compactMap { favorite in
            guard let range = favorite.range, seen.insert(range.storageString).inserted else { return nil }
            return FavoriteVerseEntity(range: range, source: source)
        }
    }
}

// MARK: Verse-image options

nonisolated enum VerseImageTemplate: String, AppEnum {
    case parchment, ink, dawn, night, linen, stone, olive, minimal

    static var typeDisplayRepresentation: TypeDisplayRepresentation {
        TypeDisplayRepresentation(name: LocalizedStringResource("Design", comment: "App Intents type name: a verse-image design"))
    }

    static var caseDisplayRepresentations: [VerseImageTemplate: DisplayRepresentation] {
        [.parchment: DisplayRepresentation(title: LocalizedStringResource("Parchment", comment: "Name of a share-card design")),
         .ink: DisplayRepresentation(title: LocalizedStringResource("Ink", comment: "Name of a share-card design")),
         .dawn: DisplayRepresentation(title: LocalizedStringResource("Dawn", comment: "Name of a share-card design")),
         .night: DisplayRepresentation(title: LocalizedStringResource("Night", comment: "Name of a share-card design")),
         .linen: DisplayRepresentation(title: LocalizedStringResource("Linen", comment: "Name of a share-card design")),
         .stone: DisplayRepresentation(title: LocalizedStringResource("Stone", comment: "Name of a share-card design")),
         .olive: DisplayRepresentation(title: LocalizedStringResource("Olive", comment: "Name of a share-card design")),
         .minimal: DisplayRepresentation(title: LocalizedStringResource("Minimal", comment: "Name of a share-card design"))]
    }
}

nonisolated enum VerseImageShape: String, AppEnum {
    case square, story, wide

    static var typeDisplayRepresentation: TypeDisplayRepresentation {
        TypeDisplayRepresentation(name: LocalizedStringResource("Shape", comment: "App Intents type name: a verse-image shape"))
    }

    static var caseDisplayRepresentations: [VerseImageShape: DisplayRepresentation] {
        [.square: DisplayRepresentation(title: LocalizedStringResource("Square", comment: "Share-card shape")),
         .story: DisplayRepresentation(title: LocalizedStringResource("Story", comment: "Share-card shape")),
         .wide: DisplayRepresentation(title: LocalizedStringResource("Wide", comment: "Share-card shape"))]
    }
}
