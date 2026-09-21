package com.blainemiller.scripturealone.data.userdata

import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.keepsake.KeepsakeNote
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

class UserDataStoreTest {
    private lateinit var dir: File
    private lateinit var file: File
    private val open = mutableListOf<JdbcUserDatabase>()

    @Before fun setUp() {
        dir = Files.createTempDirectory("userdata").toFile()
        file = File(dir, "userdata.sqlite")
    }

    @After fun tearDown() {
        open.forEach { it.close() }
        dir.deleteRecursively()
    }

    /** A fresh connection to the same file — what a relaunch does. */
    private fun store(): UserDataStore = UserDataStore(JdbcUserDatabase(file).also { open += it })

    private fun range(a: Int, b: Int = a) = VerseRange.of(VerseRef.fromKey(a), VerseRef.fromKey(b))

    @Test fun highlightsSurviveARelaunch() {
        store().highlight(listOf(43003016, 43003017), HighlightColor.YELLOW, Instant.ofEpochMilli(1_000))
        val reopened = store()
        assertEquals(
            listOf(43003016 to "yellow", 43003017 to "yellow"),
            reopened.highlights().map { it.verseKey to it.color },
        )
        assertEquals(Instant.ofEpochMilli(1_000), reopened.highlights().first().createdAt)
    }

    @Test fun rehighlightingReplacesTheColour() {
        val s = store()
        s.highlight(listOf(43003016), HighlightColor.YELLOW)
        s.highlight(listOf(43003016), HighlightColor.PURPLE)
        assertEquals(listOf("purple"), s.highlights().map { it.color })
    }

    @Test fun eraserRemovesOnlyTheSelectedVerses() {
        val s = store()
        s.highlight(listOf(43003016, 43003017, 43003018), HighlightColor.GREEN)
        s.removeHighlights(listOf(43003017))
        assertEquals(listOf(43003016, 43003018), s.highlights().map { it.verseKey })
        assertEquals(listOf(43003018), s.highlights(43003018..43003999).map { it.verseKey })
    }

    @Test fun notesRoundTripEveryKeepsakeField() {
        val id = UUID.randomUUID()
        val note = Note(
            id = id, title = "The Good Shepherd", body = "He knows his own by name.\nLine two — “quoted”.",
            anchors = listOf(range(19023001, 19023006), range(43010011, 43010018)),
            createdAt = Instant.ofEpochMilli(1_700_000_000_000), updatedAt = Instant.ofEpochMilli(1_700_000_500_000),
            origin = "camera",
        )
        store().save(note)
        val loaded = store().note(id)!!
        assertEquals(note, loaded)
        // And straight onto the keepsake model, field for field.
        val keepsake = loaded.toKeepsake()
        assertEquals(id, keepsake.id)
        assertEquals(listOf(KeepsakeNote.Passage(19023001, 19023006), KeepsakeNote.Passage(43010011, 43010018)), keepsake.passages)
        assertEquals(note.createdAt, keepsake.createdAt)
        assertEquals(note.updatedAt, keepsake.updatedAt)
        assertEquals("camera", keepsake.origin)
        assertEquals(note, Note.fromKeepsake(keepsake))
    }

    @Test fun anchorsAreStoredSortedAsSwiftStoresThem() {
        assertEquals(
            "19023001-19023006,43010011-43010018",
            Note.encodeAnchors(listOf(range(43010011, 43010018), range(19023001, 19023006))),
        )
        assertEquals(listOf(range(19023001, 19023006)), Note.parseAnchors("garbage,19023001-19023006,1-2"))
    }

    @Test fun notesListMostRecentlyEditedFirstAndDelete() {
        val s = store()
        val old = Note(title = "Old", createdAt = Instant.ofEpochMilli(1), updatedAt = Instant.ofEpochMilli(1))
        val new = Note(title = "New", createdAt = Instant.ofEpochMilli(2), updatedAt = Instant.ofEpochMilli(5))
        s.save(old)
        s.save(new)
        assertEquals(listOf("New", "Old"), s.notes().map { it.title })
        s.save(old.copy(body = "edited", updatedAt = Instant.ofEpochMilli(9)))
        assertEquals(listOf("Old", "New"), s.notes().map { it.title })
        assertEquals(2, s.notes().size)
        s.deleteNote(new.id)
        assertNull(store().note(new.id))
        assertEquals(1, store().notes().size)
    }

    @Test fun favoriteToggleMirrorsTheHeart() {
        val s = store()
        val ranges = listOf(range(45008038, 45008039), range(43003016))
        assertTrue(s.toggleFavorite(ranges))
        assertEquals(setOf("45008038-45008039", "43003016-43003016"), s.favorites().map { it.range.storageString }.toSet())
        // One already a favorite, one not: favorites the other rather than removing.
        s.deleteFavorite(s.favorites().first { it.range == range(43003016) }.id)
        assertTrue(s.toggleFavorite(ranges))
        assertEquals(2, s.favorites().size)
        // Both favorites: the heart is filled, and tapping removes them.
        assertTrue(Selection.isFavorite(ranges, s.favorites()))
        assertFalse(s.toggleFavorite(ranges))
        assertTrue(store().favorites().isEmpty())
    }

    @Test fun favoritesNewestFirst() {
        val s = store()
        s.add(Favorite(range = range(1001001), createdAt = Instant.ofEpochMilli(1)))
        s.add(Favorite(range = range(2001001), createdAt = Instant.ofEpochMilli(3)))
        s.add(Favorite(range = range(3001001), createdAt = Instant.ofEpochMilli(2)))
        assertEquals(listOf(2001001, 3001001, 1001001), store().favorites().map { it.range.start.key })
    }

    @Test fun reopeningDoesNotRerunTheMigration() {
        store().highlight(listOf(1001001), HighlightColor.BLUE)
        store()
        assertEquals(1, store().highlights().size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun observableStateFollowsTheStore() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val data = UserData(scope, dispatcher) { store() }
        scope.advanceUntilIdle()
        assertTrue(data.loaded.value)
        data.highlight(listOf(43003016), HighlightColor.PINK)
        data.toggleFavorite(listOf(range(43003016)))
        val note = data.newNote(listOf(range(43003016)))
        // A new note is visible at once, before the write lands — the editor opens on it.
        assertEquals(listOf(note.id), data.notes.value.map { it.id })
        scope.advanceUntilIdle()
        assertEquals(listOf("pink"), data.highlights.value.map { it.color })
        assertEquals(1, data.favorites.value.size)
        assertEquals(note, store().note(note.id))
        data.deleteNote(note.id)
        scope.advanceUntilIdle()
        assertTrue(store().notes().isEmpty())
    }
}
