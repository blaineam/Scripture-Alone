#if !os(watchOS)
import Foundation

/// What any note import produces, whatever it was read from.
///
/// One shape for every source, so the screen that shows a reader what was found, the code that
/// writes it into the library, and the de-duplication that makes a second import harmless are
/// written once. A new source is a new parser and nothing else.
public struct ImportedNotes: Sendable, Equatable {

    /// A highlight, reduced to what this app can hold: one verse, one of five colors.
    public struct Highlight: Sendable, Equatable {
        public let verse: VerseRef
        /// Matches `HighlightColor`'s raw values: yellow, green, blue, pink, purple.
        public let color: String
        /// The translation it was made in, where the source says. Kept for the summary only.
        public let translation: String?

        public init(verse: VerseRef, color: String, translation: String? = nil) {
            self.verse = verse
            self.color = color
            self.translation = translation
        }
    }

    /// A note. `range` is nil for an entry attached to no verse — a journal page, a sermon.
    public struct Note: Sendable, Equatable {
        public let range: VerseRange?
        public let title: String
        public let body: String
        public let translation: String?

        public init(range: VerseRange?, title: String, body: String, translation: String? = nil) {
            self.range = range
            self.title = title
            self.body = body
            self.translation = translation
        }
    }

    /// The color names an import may produce — `HighlightColor`'s raw values in the app.
    public static let highlightColorNames: Set<String> = ["yellow", "green", "blue", "pink", "purple"]

    public var highlights: [Highlight] = []
    public var verseNotes: [Note] = []
    public var journals: [Note] = []
    public var saved: [VerseRange] = []
    /// Lines whose reference could not be resolved, verbatim, so they can be shown rather than lost.
    public var unresolved: [String] = []

    public init() {}

    public var isEmpty: Bool {
        highlights.isEmpty && verseNotes.isEmpty && journals.isEmpty && saved.isEmpty
    }

    public var total: Int {
        highlights.count + verseNotes.count + journals.count + saved.count
    }
}

/// Why an import could not be read. Shared, because a reader's mistake is the same mistake
/// whichever file they picked.
public enum NoteImportError: LocalizedError, Equatable {
    case notAnArchive
    case notALifeBibleExport
    case nothingToImport
    case nothingRecognised

    public var errorDescription: String? {
        switch self {
        case .notAnArchive:
            "That file isn't a zip archive."
        case .notALifeBibleExport:
            "That doesn't look like a Life Bible export. Look for LifeBibleData.zip, from "
                + "Settings → Advanced → Export your data."
        case .nothingToImport:
            "That export has no notes, highlights or saved verses in it."
        case .nothingRecognised:
            "Nothing in that looked like a Bible reference. Each note needs to start with one — "
                + "“John 3:16”, say — so it can be attached to the right verse."
        }
    }
}
#endif
