package com.blainemiller.scripturealone.data.userdata

import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.notesimport.ImportedNotes
import com.blainemiller.scripturealone.data.notesimport.PastedNotesImport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/** Writing an import into the reader's store — `LifeBibleImportView.bring` — and doing it twice. */
class NotesImportWriteTest {
    private lateinit var dir: File
    private lateinit var db: JdbcUserDatabase
    private lateinit var store: UserDataStore
    private val at = Instant.ofEpochMilli(1_790_000_000_000)

    @Before fun setUp() {
        dir = Files.createTempDirectory("import").toFile()
        db = JdbcUserDatabase(File(dir, "userdata.sqlite"))
        store = UserDataStore(db)
    }

    @After fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    private fun found() = ImportedNotes().apply {
        highlights += ImportedNotes.Highlight(VerseRef(43, 3, 16), "green")
        highlights += ImportedNotes.Highlight(VerseRef(43, 3, 17), "chartreuse")
        verseNotes += ImportedNotes.Note(VerseRange.of(VerseRef(45, 8, 28)), "Romans 8:28", "Not that all things are good")
        journals += ImportedNotes.Note(null, "Sermon", "Grace upon grace")
        saved += VerseRange.of(VerseRef(19, 23, 1), VerseRef(19, 23, 6))
    }

    @Test fun everythingLandsOnceAndASecondImportAddsNothing() {
        val first = store.importNotes(found(), at)
        assertEquals(NotesImportTally(highlights = 2, notes = 1, journals = 1, favorites = 1), first)
        assertEquals(listOf(43003016 to "green", 43003017 to "yellow"), store.highlights().map { it.verseKey to it.color })
        val notes = store.notes().sortedBy { it.title }
        assertEquals(listOf("Romans 8:28", "Sermon"), notes.map { it.title })
        assertEquals(listOf(VerseRange.of(VerseRef(45, 8, 28))), notes[0].anchors)
        assertEquals(emptyList<VerseRange>(), notes[1].anchors)
        assertEquals(setOf(NotesImportTally.ORIGIN), notes.map { it.origin }.toSet())
        assertEquals(at, notes[0].createdAt)
        assertEquals(listOf("19023001-19023006"), store.favorites().map { it.range.storageString })

        val second = store.importNotes(found(), at.plusSeconds(60))
        assertEquals(NotesImportTally(alreadyThere = 5), second)
        assertEquals(0, second.total)
        assertEquals(2, store.highlights().size)
        assertEquals(2, store.notes().size)
        assertEquals(1, store.favorites().size)
    }

    @Test fun existingMarksAreLeftAlone() {
        store.highlight(listOf(43003016), HighlightColor.PURPLE, at)
        store.save(Note(title = "  romans 8:28 ", body = "Not that all\nthings are good", createdAt = at))
        val tally = store.importNotes(found(), at)
        assertEquals(2, tally.alreadyThere)
        assertEquals("purple", store.highlights().first { it.verseKey == 43003016 }.color)
        assertEquals(2, store.notes().size)
    }

    /** As iOS: only what was here before counts, so identical entries within one file both come across. */
    @Test fun duplicatesWithinOneImportAreKept() {
        val amen = ImportedNotes.Note(VerseRange.of(VerseRef(19, 150, 6)), "", "Amen")
        val twice = found().apply {
            verseNotes += amen
            verseNotes += amen.copy(range = VerseRange.of(VerseRef(66, 22, 21)))
        }
        val tally = store.importNotes(twice, at)
        assertEquals(3, tally.notes)
        assertEquals(0, tally.alreadyThere)
        // A second import finds every one of them here already.
        assertEquals(NotesImportTally(alreadyThere = 7), store.importNotes(twice, at))
    }

    @Test fun pastedNotesImportEndToEnd() {
        val parsed = PastedNotesImport.parse("John 3:16 — the whole gospel\n\nRomans 8:28 — not that all things are good")
        val tally = store.importNotes(parsed, at)
        assertEquals(2, tally.notes)
        assertEquals(setOf(43003016, 45008028), store.notes().map { it.firstVerseKey }.toSet())
    }

    @Test fun fingerprintIgnoresCaseAndSpacing() {
        assertEquals(
            NotesImportTally.fingerprint("Romans  8", "a b\n c"),
            NotesImportTally.fingerprint("romans 8", "A B C"),
        )
    }
}
