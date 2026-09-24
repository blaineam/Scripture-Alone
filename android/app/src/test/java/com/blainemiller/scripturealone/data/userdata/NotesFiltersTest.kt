package com.blainemiller.scripturealone.data.userdata

import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The Notes panel's Highlights list and its All Books / This Book / This Chapter filter. */
class NotesFiltersTest {
    private fun range(a: Int, b: Int = a) = VerseRange.of(VerseRef.fromKey(a), VerseRef.fromKey(b))
    private fun mark(key: Int, color: String, at: Long = 0) = Highlight(key, color, Instant.ofEpochSecond(at))

    @Test fun neighbouringVersesInOneColourAreOnePassage() {
        val runs = HighlightRun.of(listOf(mark(45008039, "yellow"), mark(45008038, "yellow"), mark(45008028, "yellow")))
        assertEquals(listOf(range(45008028), range(45008038, 45008039)), runs.map { it.range })
        assertEquals(listOf(HighlightColor.YELLOW, HighlightColor.YELLOW), runs.map { it.color })
    }

    @Test fun aChangeOfColourStartsANewPassage() {
        val runs = HighlightRun.of(listOf(mark(43003016, "yellow"), mark(43003017, "green"), mark(43003018, "green")))
        assertEquals(listOf(range(43003016), range(43003017, 43003018)), runs.map { it.range })
        assertEquals(listOf(HighlightColor.YELLOW, HighlightColor.GREEN), runs.map { it.color })
    }

    @Test fun passagesAreInBibleOrderAndNeverCrossAChapter() {
        val runs = HighlightRun.of(listOf(mark(43004001, "blue"), mark(1001001, "pink"), mark(43003036, "blue")))
        assertEquals(listOf(range(1001001), range(43003036), range(43004001)), runs.map { it.range })
    }

    @Test fun aVerseMarkedTwiceCountsOnceWithTheNewestColour() {
        val old = mark(19023001, "yellow", at = 10)
        val new = mark(19023001, "purple", at = 20)
        val runs = HighlightRun.of(listOf(new, old, mark(19023002, "purple", at = 5)))
        assertEquals(1, runs.size)
        assertEquals(range(19023001, 19023002), runs[0].range)
        assertEquals(HighlightColor.PURPLE, runs[0].color)
        // Removing the passage removes every row stored for it.
        assertEquals(3, runs[0].marks.size)
        assertEquals(listOf(19023001, 19023002), runs[0].keys.sorted())
    }

    @Test fun anUnknownColourReadsAsYellow() {
        assertEquals(HighlightColor.YELLOW, HighlightRun.of(listOf(mark(43003016, "teal"))).single().color)
    }

    @Test fun placeNarrowsToTheBookOrChapterOnScreen() {
        val here = ChapterRef(45, 8)
        val sameChapter = range(45008028)
        val sameBook = range(45012001, 45012002)
        val elsewhere = range(43003016)
        assertTrue(listOf(sameChapter, sameBook, elsewhere).all { NotesPlace.ALL.contains(it, here) })
        assertEquals(listOf(sameChapter, sameBook), listOf(sameChapter, sameBook, elsewhere).filter { NotesPlace.BOOK.contains(it, here) })
        assertEquals(listOf(sameChapter), listOf(sameChapter, sameBook, elsewhere).filter { NotesPlace.CHAPTER.contains(it, here) })
    }

    @Test fun aPassageEndingInTheBookOrChapterCounts() {
        val here = ChapterRef(45, 8)
        val intoRomans = range(44028031, 45001002)
        assertTrue(NotesPlace.BOOK.contains(intoRomans, ChapterRef(45, 1)))
        assertFalse(NotesPlace.CHAPTER.contains(intoRomans, here))
        assertTrue(NotesPlace.CHAPTER.contains(range(45007025, 45008001), here))
    }

    @Test fun highlightSearchMatchesPassageReferenceTextOrColour() {
        val run = HighlightRun.of(listOf(mark(45008038, "green"), mark(45008039, "green"))).single()
        val text = "For I am persuaded, that neither death, nor life"
        assertTrue(NoteSearch.matches(run, text, "green", ""))
        assertTrue(NoteSearch.matches(run, text, "green", "Rom 8"))
        assertFalse(NoteSearch.matches(run, text, "green", "Rom 9"))
        assertTrue(NoteSearch.matches(run, text, "green", "persuaded"))
        assertTrue(NoteSearch.matches(run, text, "green", "GREEN"))
        assertFalse(NoteSearch.matches(run, text, "green", "yellow"))
    }
}
