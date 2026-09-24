package com.blainemiller.scripturealone.data.userdata

import com.blainemiller.scripturealone.data.VerseNumbering
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.reference.ReferenceParser

/**
 * The Notes panel's search — `NotesPanel.matches` and `FavoritesSection.rows`. A term with a digit
 * that parses as a passage ("Rom 8") finds everything anchored anywhere in it; anything else is
 * matched as words, ignoring case, against the title, body and passages (or a favorite's text).
 */
object NoteSearch {

    /**
     * The passage [term] names, if it names one. [verseCount] resolves a whole chapter's end; a count
     * that isn't known yet (0) is read as the widest a chapter can be, which is right for overlap.
     */
    fun passage(
        term: String,
        verseCount: (BookID, Int) -> Int = { _, _ -> 0 },
        numbering: VerseNumbering = VerseNumbering.IDENTITY,
    ): VerseRange? {
        val trimmed = term.trim()
        if (trimmed.none { it.isDigit() }) return null
        val parsed = ReferenceParser.parse(trimmed) ?: return null
        val (first, last) = parsed.clamped.range { book, chapter -> verseCount(book, chapter).takeIf { it > 0 } ?: 999 }
        // Typed in the reader's own numbering; anchors and favorites are stored as KJV keys.
        return numbering.kjvRange(VerseRange.of(VerseRef.fromKey(first), VerseRef.fromKey(last)))
    }

    fun matches(
        note: Note,
        term: String,
        verseCount: (BookID, Int) -> Int = { _, _ -> 0 },
        numbering: VerseNumbering = VerseNumbering.IDENTITY,
    ): Boolean {
        val trimmed = term.trim()
        if (trimmed.isEmpty()) return true
        passage(trimmed, verseCount, numbering)?.let { wanted -> return note.anchors.any { it.overlaps(wanted) } }
        return listOf(note.title, note.body, note.anchorSummary).any { it.contains(trimmed, ignoreCase = true) }
    }

    fun matches(
        favorite: Favorite,
        text: String,
        term: String,
        verseCount: (BookID, Int) -> Int = { _, _ -> 0 },
        numbering: VerseNumbering = VerseNumbering.IDENTITY,
    ): Boolean {
        val trimmed = term.trim()
        if (trimmed.isEmpty()) return true
        passage(trimmed, verseCount, numbering)?.let { return favorite.range.overlaps(it) }
        return favorite.range.display.contains(trimmed, ignoreCase = true) || text.contains(trimmed, ignoreCase = true)
    }

    /**
     * A run of highlighted verses — `HighlightsSection.rows`: by passage, else by its reference, its
     * text or its colour's name ([colorName], in the app's language).
     */
    fun matches(
        run: HighlightRun,
        text: String,
        colorName: String,
        term: String,
        verseCount: (BookID, Int) -> Int = { _, _ -> 0 },
        numbering: VerseNumbering = VerseNumbering.IDENTITY,
    ): Boolean {
        val trimmed = term.trim()
        if (trimmed.isEmpty()) return true
        passage(trimmed, verseCount, numbering)?.let { return run.range.overlaps(it) }
        return listOf(run.range.display, text, colorName).any { it.contains(trimmed, ignoreCase = true) }
    }
}
