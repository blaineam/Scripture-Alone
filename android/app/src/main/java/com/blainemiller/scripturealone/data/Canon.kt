package com.blainemiller.scripturealone.data

import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.sabible.ChapterRef

/**
 * Chapter navigation over the canon. The book table itself is [BookID] — generated from
 * `ScriptureAloneCore/Canon.swift`, so ordinals, names and chapter counts can't drift from iOS by a
 * retyping slip. This object adds only what the reader needs on top: display and neighbours.
 *
 * Kept in code rather than read from a database's `books` table because the sealed ASV has no such
 * table, and a chapter's neighbours must not depend on which translation is open.
 */
object Canon {
    val books: List<BookID> get() = BookID.entries

    fun book(id: Int): BookID = BookID.of(id) ?: error("no book $id")

    /** "Genesis 2", or just "Jude" for a single-chapter book — `ChapterRef.display` on iOS. */
    fun display(ref: ChapterRef): String {
        val book = book(ref.book)
        return if (book.isSingleChapter) book.displayName else "${book.displayName} ${ref.chapter}"
    }

    /** The following chapter, crossing into the next book; null after Revelation 22. */
    fun next(ref: ChapterRef): ChapterRef? = when {
        ref.chapter < book(ref.book).chapterCount -> ChapterRef(ref.book, ref.chapter + 1)
        ref.book < books.size -> ChapterRef(ref.book + 1, 1)
        else -> null
    }

    /** The preceding chapter, crossing into the previous book's last; null before Genesis 1. */
    fun previous(ref: ChapterRef): ChapterRef? = when {
        ref.chapter > 1 -> ChapterRef(ref.book, ref.chapter - 1)
        ref.book > 1 -> ChapterRef(ref.book - 1, book(ref.book - 1).chapterCount)
        else -> null
    }
}
