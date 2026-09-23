import Foundation

/// A translation the reader can draw: a chapter's layout, the verses in a selection, and the terms
/// that govern both.
///
/// `BibleStore` (a SQLite file — bundled, imported or the online cache) and `TranslationPackage` (a
/// signed, encrypted package) both satisfy it, with the same signatures they already had. That is
/// the seam the app needs: the reader asks a source for a layout and gets one, and asks
/// `info.rights` whether the text may leave, without knowing or caring which kind of source it
/// holds. A packaged translation is not a special case with its own rules; it is the same case with
/// a different answer to the same questions.
///
/// Search is part of it, and that is a decision worth stating. A package now carries an encrypted
/// index (`PackageSearchIndex`), so it answers the same `search(_:limit:)` a store answers, with the
/// same semantics and the same return type. Keeping search off the protocol would mean the one place
/// in the app that searches has to ask *what kind* of source it is holding — a downcast to
/// `BibleStore` — which is precisely the branching the protocol exists to remove. Instead the
/// capability is a question the source answers: `isSearchable`. A store always can; a package can
/// when it was built with an index; and a source that cannot says so rather than being a different
/// type. A caller that searches an unsearchable source gets a thrown error, not empty results,
/// because "no matches" and "this translation cannot be searched" are different sentences to show a
/// reader.
public protocol ChapterTextSource: Sendable {
    var info: TranslationInfo { get }
    var isSearchable: Bool { get }
    func contains(_ chapter: ChapterRef) -> Bool
    func verseCount(_ chapter: ChapterRef) -> Int
    func layout(for chapter: ChapterRef) throws -> ChapterLayout
    func verses(in range: VerseRange) throws -> [VerseText]
    func search(_ query: String, limit: Int) throws -> [BibleStore.SearchHit]
    /// How this source's verse numbers line up with the KJV keys marks are stored under.
    /// `verses(in:)` takes KJV keys; `layout(for:)` and `verseCount(_:)` are in the source's own
    /// numbering. See `VerseNumbering`.
    var numbering: VerseNumbering { get }
}

public extension ChapterTextSource {
    func search(_ query: String) throws -> [BibleStore.SearchHit] { try search(query, limit: 300) }
    /// Sources without a `kjv_map` — packages, imports, the online cache — number as the KJV does.
    var numbering: VerseNumbering { .identity }
}

extension BibleStore: ChapterTextSource {
    /// A store is its own index. The online cache is a store too, and searches what has been read —
    /// which is what it has always done.
    public var isSearchable: Bool { true }
}

extension TranslationPackage: ChapterTextSource {}
