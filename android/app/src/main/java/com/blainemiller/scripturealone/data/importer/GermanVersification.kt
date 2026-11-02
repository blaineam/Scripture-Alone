package com.blainemiller.scripturealone.data.importer

import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID

/**
 * German Bibles divide two prophets differently from the KJV-style numbering the reader, search and
 * cross-references use:
 *
 * - **Joel** has four chapters: 1 and 2:1-27 as the KJV, **3:1-5 = KJV 2:28-32**, **4 = KJV 3**.
 * - **Malachi** has three chapters: **3:19-24 = KJV 4:1-6**.
 *
 * An import keeps one numbering, so a German import is renumbered to the KJV's once it has been read.
 * Without that, Joel 4 is "a chapter the book doesn't have" and its verses land in Joel 3, two
 * different verses on every row. `Import/GermanVersification.swift` is the same table.
 *
 * A book is German-numbered only when the file proves it: Joel has a chapter 4, or Malachi's chapter 3
 * runs past verse 18 (the KJV's last). An English-numbered Joel or Malachi is never touched.
 */
internal object GermanVersification {
    /** The most chapters an import may carry for [book] — one more than the KJV's for Joel. */
    fun maxChapter(book: BookID): Int = if (book == BookID.JOEL) 4 else book.chapterCount

    /** Malachi's chapter 3, in the KJV, ends at this verse. */
    private const val MALACHI_KJV_LAST_VERSE_OF_3 = 18

    /** Where a German `chapter:verse` of [book] sits in the KJV numbering. */
    fun english(book: BookID, chapter: Int, verse: Int): Pair<Int, Int> = when {
        book == BookID.JOEL && chapter == 3 && verse in 1..5 -> 2 to (27 + verse)
        book == BookID.JOEL && chapter == 4 -> 3 to verse
        book == BookID.MALACHI && chapter == 3 && verse > MALACHI_KJV_LAST_VERSE_OF_3 -> 4 to (verse - MALACHI_KJV_LAST_VERSE_OF_3)
        else -> chapter to verse
    }

    /** The books of [bible] the file numbered the German way. */
    fun germanNumbered(bible: ExtractedBible): Set<BookID> {
        val found = HashSet<BookID>()
        for (ref in bible.verses.keys) {
            val book = BookID.of(ref.book) ?: continue
            if (book == BookID.JOEL && ref.chapter >= 4) found += book
            if (book == BookID.MALACHI && ref.chapter == 3 && ref.verse > MALACHI_KJV_LAST_VERSE_OF_3) found += book
        }
        return found
    }

    fun english(book: BookID, ref: VerseRef): VerseRef {
        val (chapter, verse) = english(book, ref.chapter, ref.verse)
        return VerseRef(ref.book, chapter, verse)
    }
}
