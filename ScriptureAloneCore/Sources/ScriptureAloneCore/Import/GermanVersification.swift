#if !os(watchOS)
import Foundation

/// German Bibles divide two prophets differently from the KJV-style numbering the reader, search
/// and cross-references use:
///
/// - **Joel** has four chapters: 1 and 2:1-27 as the KJV, **3:1-5 = KJV 2:28-32**, **4 = KJV 3**.
/// - **Malachi** has three chapters: **3:19-24 = KJV 4:1-6**.
///
/// An import keeps one numbering, so a German import is renumbered to the KJV's once it has been
/// read. Without that, Joel 4 is "a chapter the book doesn't have" and its verses land in Joel 3,
/// two different verses on every row. `GermanVersification.kt` is the same table.
///
/// A book is German-numbered only when the file proves it: Joel has a chapter 4, or Malachi's
/// chapter 3 runs past verse 18 (the KJV's last). An English-numbered Joel or Malachi is never touched.
enum GermanVersification {
    /// The most chapters an import may carry for `book` — one more than the KJV's for Joel.
    static func maxChapter(of book: BookID) -> Int {
        book == .joel ? 4 : book.chapterCount
    }

    /// Malachi's chapter 3, in the KJV, ends at this verse.
    private static let malachiKJVLastVerseOf3 = 18

    /// Where a German `chapter:verse` of `book` sits in the KJV numbering.
    static func english(_ book: BookID, chapter: Int, verse: Int) -> (chapter: Int, verse: Int) {
        switch book {
        case .joel where chapter == 3 && (1...5).contains(verse): return (2, 27 + verse)
        case .joel where chapter == 4: return (3, verse)
        case .malachi where chapter == 3 && verse > malachiKJVLastVerseOf3: return (4, verse - malachiKJVLastVerseOf3)
        default: return (chapter, verse)
        }
    }

    static func english(_ ref: VerseRef) -> VerseRef {
        let mapped = english(ref.book, chapter: ref.chapter, verse: ref.verse)
        return VerseRef(ref.book, mapped.chapter, mapped.verse)
    }

    /// The books of `bible` the file numbered the German way.
    static func germanNumbered(_ bible: ExtractedBible) -> Set<BookID> {
        var found: Set<BookID> = []
        for ref in bible.verses.keys {
            if ref.book == .joel, ref.chapter >= 4 { found.insert(.joel) }
            if ref.book == .malachi, ref.chapter == 3, ref.verse > malachiKJVLastVerseOf3 { found.insert(.malachi) }
        }
        return found
    }
}
#endif
