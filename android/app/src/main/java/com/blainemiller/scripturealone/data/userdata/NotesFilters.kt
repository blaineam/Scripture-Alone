package com.blainemiller.scripturealone.data.userdata

import androidx.annotation.StringRes
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.sabible.ChapterRef

/**
 * Where in the Bible the Notes panel looks — `NotesPanel.Place`: everywhere, the book on screen, or
 * its chapter. Applies to notes, highlights and favorites alike.
 */
enum class NotesPlace(@StringRes val titleRes: Int) {
    ALL(R.string.notes_place_all), BOOK(R.string.notes_place_book), CHAPTER(R.string.notes_scope_chapter);

    /**
     * Whether [range] falls in this place, for the chapter on screen. [range] is in the translation's
     * own numbering (the panel converts stored KJV keys first), as [location] is.
     */
    fun contains(range: VerseRange, location: ChapterRef): Boolean = when (this) {
        ALL -> true
        BOOK -> range.start.book == location.book || range.end.book == location.book
        CHAPTER -> range.overlaps(location)
    }
}

/**
 * Neighbouring verses highlighted in one colour, read as one passage ("Romans 8:38–39") —
 * `HighlightsSection.runs`. [marks] are every stored row in the run, for removing it whole.
 */
data class HighlightRun(val range: VerseRange, val color: HighlightColor, val marks: List<Highlight>) {
    /** The verse keys the run covers: one chapter, consecutive. */
    val keys: List<Int> get() = marks.map { it.verseKey }.distinct()

    companion object {
        /**
         * Every highlighted verse in Bible order, grouped into runs. A verse marked twice (two devices,
         * before a merge) counts once, the newest colour winning, as in the reader. Runs never cross a
         * chapter.
         */
        fun of(highlights: List<Highlight>): List<HighlightRun> {
            val newest = HashMap<Int, Highlight>()
            for (mark in highlights) {
                val current = newest[mark.verseKey]
                if (current == null || current.createdAt < mark.createdAt) newest[mark.verseKey] = mark
            }
            val byKey = highlights.groupBy { it.verseKey }
            val result = mutableListOf<HighlightRun>()
            for (key in newest.keys.sorted()) {
                val mark = newest.getValue(key)
                val verse = VerseRange.ref(key) ?: continue
                val color = HighlightColor.fromRaw(mark.color) ?: HighlightColor.YELLOW
                val duplicates = byKey[key].orEmpty()
                val last = result.lastOrNull()
                if (last != null && last.color == color && last.range.end.key + 1 == key &&
                    last.range.end.book == verse.book && last.range.end.chapter == verse.chapter
                ) {
                    result[result.lastIndex] = HighlightRun(VerseRange(last.range.start, verse), color, last.marks + duplicates)
                } else {
                    result += HighlightRun(VerseRange(verse, verse), color, duplicates)
                }
            }
            return result
        }
    }
}
