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
/// Search is deliberately not here. A store has an FTS5 index; a package cannot be searched without
/// decrypting all of it, which is exactly what the format exists to prevent. A packaged translation
/// is searchable only over what the reader has already opened, or not at all — see the notes in
/// `docs/encrypted-translations.md`.
public protocol ChapterTextSource: Sendable {
    var info: TranslationInfo { get }
    func contains(_ chapter: ChapterRef) -> Bool
    func verseCount(_ chapter: ChapterRef) -> Int
    func layout(for chapter: ChapterRef) throws -> ChapterLayout
    func verses(in range: VerseRange) throws -> [VerseText]
}

extension BibleStore: ChapterTextSource {}

extension TranslationPackage: ChapterTextSource {}
