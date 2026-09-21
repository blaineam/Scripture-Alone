package com.blainemiller.scripturealone.data.userdata

import com.blainemiller.scripturealone.data.ChapterVerse
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SelectionTest {
    private fun range(a: Int, b: Int = a) = VerseRange.of(VerseRef.fromKey(a), VerseRef.fromKey(b))
    private val john = mapOf(ChapterRef(43, 3) to 36, ChapterRef(43, 4) to 54)
    private val counts: (ChapterRef) -> Int = { john[it] ?: 0 }

    @Test fun adjacentVersesJoinIntoOneRange() {
        assertEquals(
            listOf(range(43003016, 43003018), range(43003020)),
            Selection.ranges(setOf(43003018, 43003016, 43003017, 43003020), counts),
        )
    }

    @Test fun aRangeRunsOnIntoTheNextChapterOnlyFromItsLastVerse() {
        assertEquals(listOf(range(43003035, 43004002)), Selection.ranges(setOf(43003035, 43003036, 43004001, 43004002), counts))
        assertEquals(listOf(range(43003035), range(43004001)), Selection.ranges(setOf(43003035, 43004001), counts))
        // Unknown chapter length: kept apart rather than guessed.
        assertEquals(
            listOf(range(44001026), range(44002001)),
            Selection.ranges(setOf(44001026, 44002001)) { 0 },
        )
    }

    @Test fun rangesSkipKeysThatAreNotVerses() {
        assertEquals(listOf(range(43003016)), Selection.ranges(setOf(43003016, 99001001), counts))
    }

    @Test fun keysOfARangeAcrossAChapter() {
        assertEquals(listOf(43003035, 43003036, 43004001, 43004002), Selection.keys(range(43003035, 43004002), counts))
        assertEquals(listOf(43003016), Selection.keys(range(43003016), counts))
        // A range past the chapter's end stops rather than inventing verses.
        assertEquals(listOf(43004054), Selection.keys(range(43004054, 43004060), counts))
    }

    @Test fun longPressExtendsFromTheNearestSelectedVerse() {
        assertEquals(setOf(43003016, 43003017, 43003018, 43003019), Selection.extend(setOf(43003016), 43003019))
        assertEquals(setOf(43003010, 43003011, 43003012, 43003020), Selection.extend(setOf(43003012, 43003020), 43003010))
        // Nothing selected in this chapter: just the verse.
        assertEquals(setOf(43002001, 43003005), Selection.extend(setOf(43002001), 43003005))
    }

    @Test fun newestHighlightWins() {
        val h = listOf(
            Highlight(43003016, "yellow", Instant.ofEpochMilli(5)),
            Highlight(43003016, "blue", Instant.ofEpochMilli(9)),
            Highlight(43003017, "green", Instant.ofEpochMilli(1)),
            Highlight(43004001, "pink", Instant.ofEpochMilli(1)),
        )
        assertEquals(mapOf(43003016 to "blue", 43003017 to "green"), Selection.highlightColors(h, ChapterRef(43, 3)))
    }

    @Test fun noteMarkersSitOnTheLastVerseInTheChapter() {
        val a = Note(title = "a", anchors = listOf(range(43003016, 43003018)), updatedAt = Instant.ofEpochMilli(2))
        val b = Note(title = "b", anchors = listOf(range(43003030, 43004002), range(43003016, 43003018)), updatedAt = Instant.ofEpochMilli(3))
        val c = Note(title = "c", anchors = listOf(range(43002001, 43003002)), updatedAt = Instant.ofEpochMilli(1))
        val markers = Selection.noteMarkers(listOf(a, b, c), ChapterRef(43, 3), verseCount = 36)
        assertEquals(listOf(b.id.toString(), a.id.toString()), markers[43003018])
        assertEquals(listOf(b.id.toString()), markers[43003036])
        assertEquals(listOf(c.id.toString()), markers[43003002])
        assertEquals(3, markers.size)
    }

    @Test fun quotationMatchesTheSwiftFormat() {
        val verses = listOf(
            ChapterVerse(VerseRef(43, 3, 16), "For God so loved the world…", emptyList()),
            ChapterVerse(VerseRef(43, 3, 17), "For God sent not the Son…", emptyList()),
            ChapterVerse(VerseRef(43, 3, 20), "For every one that doeth evil…", emptyList()),
        )
        assertEquals(
            "16 For God so loved the world… 17 For God sent not the Son…\n— John 3:16–17 (ASV)\n\n" +
                "For every one that doeth evil…\n— John 3:20 (ASV)",
            Selection.quotation(listOf(range(43003016, 43003017), range(43003020)), verses, "ASV"),
        )
    }

    @Test fun heartIsFilledOnlyWhenEveryRangeIsAFavorite() {
        val favorites = listOf(Favorite(range = range(43003016)))
        assertTrue(Selection.isFavorite(listOf(range(43003016)), favorites))
        assertFalse(Selection.isFavorite(listOf(range(43003016), range(43003018)), favorites))
        assertFalse(Selection.isFavorite(emptyList(), favorites))
    }

    @Test fun searchByPassageOrWords() {
        val note = Note(title = "Shepherd", body = "He leads me", anchors = listOf(range(19023001, 19023006)))
        assertTrue(NoteSearch.matches(note, ""))
        assertTrue(NoteSearch.matches(note, "ps 23"))
        assertTrue(NoteSearch.matches(note, "Psalm 23:4"))
        assertFalse(NoteSearch.matches(note, "Ps 24"))
        assertTrue(NoteSearch.matches(note, "LEADS"))
        assertTrue(NoteSearch.matches(note, "23:1"))
        assertFalse(NoteSearch.matches(note, "gospel"))
        assertNull(NoteSearch.passage("shepherd"))
        val favorite = Favorite(range = range(45008038, 45008039))
        assertTrue(NoteSearch.matches(favorite, "neither death nor life", "Rom 8"))
        assertTrue(NoteSearch.matches(favorite, "neither death nor life", "death"))
        assertFalse(NoteSearch.matches(favorite, "neither death nor life", "Rom 9"))
    }

    @Test fun highlightNamesAndPaletteMatchIOS() {
        assertEquals(listOf("yellow", "green", "blue", "pink", "purple"), HighlightColor.entries.map { it.raw })
        assertEquals(
            listOf(0xF7D154L, 0x8CD48AL, 0x7FB8F0L, 0xF29BB8L, 0xB9A2ECL),
            HighlightColor.entries.map { it.rgb },
        )
        assertEquals(0.42f, HighlightColor.YELLOW.alpha(isDark = false))
        assertEquals(0.34f, HighlightColor.YELLOW.alpha(isDark = true))
        assertEquals(HighlightColor.PINK, HighlightColor.fromRaw("pink"))
        assertNull(HighlightColor.fromRaw("orange"))
    }
}
