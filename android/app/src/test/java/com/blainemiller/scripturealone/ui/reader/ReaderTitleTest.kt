package com.blainemiller.scripturealone.ui.reader

import com.blainemiller.scripturealone.data.Canon
import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.canon.BookNames
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The reader's passage title on a narrow phone: the full name when it fits, else the abbreviation. */
class ReaderTitleTest {

    @After
    fun englishNames() = BookNames.use(null)

    @Test
    fun shortDisplayUsesTheBooksAbbreviation() {
        assertEquals("1 Thess 5", Canon.shortDisplay(ChapterRef(BookID.FIRST_THESSALONIANS.number, 5)))
        assertEquals("Song 2", Canon.shortDisplay(ChapterRef(BookID.SONG_OF_SOLOMON.number, 2)))
        assertEquals("Ps 136", Canon.shortDisplay(ChapterRef(BookID.PSALMS.number, 136)))
        // A single-chapter book is just its abbreviation, as its full title is just its name.
        assertEquals("Jude", Canon.shortDisplay(ChapterRef(BookID.JUDE.number, 1)))
    }

    @Test
    fun shortDisplayFollowsTheLanguageBooksAreNamedIn() {
        val language = BookNames.languages.first { BookNames.abbreviation(BookID.FIRST_THESSALONIANS, it) != "1 Thess" }
        BookNames.use(language)
        val abbreviation = BookNames.abbreviation(BookID.FIRST_THESSALONIANS, language)
        assertEquals("$abbreviation 5", Canon.shortDisplay(ChapterRef(BookID.FIRST_THESSALONIANS.number, 5)))
        assertNotEquals("1 Thess 5", Canon.shortDisplay(ChapterRef(BookID.FIRST_THESSALONIANS.number, 5)))
    }

    @Test
    fun theFullNameIsKeptWhileItFits() {
        assertEquals("Romans 8", passageTitle("Romans 8", "Rom 8", fullWidth = 200, room = 200))
        assertEquals("Rom 8", passageTitle("Romans 8", "Rom 8", fullWidth = 201, room = 200))
        // No room at all still leaves a title to tap for Go To.
        assertEquals("Song 2", passageTitle("Song of Solomon 2", "Song 2", fullWidth = 300, room = 0))
    }
}
