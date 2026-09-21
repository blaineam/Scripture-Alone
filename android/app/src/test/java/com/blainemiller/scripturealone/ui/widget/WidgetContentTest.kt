package com.blainemiller.scripturealone.ui.widget

import com.blainemiller.scripturealone.companion.VerseSnapshot
import com.blainemiller.scripturealone.companion.VerseSnapshot.Kind
import com.blainemiller.scripturealone.data.ChapterVerse
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.daily.DailyVerseCatalog
import com.blainemiller.scripturealone.data.userdata.HighlightColor
import com.blainemiller.scripturealone.data.userdata.JdbcUserDatabase
import com.blainemiller.scripturealone.data.userdata.Note
import com.blainemiller.scripturealone.data.userdata.UserDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The widgets' data path, end to end on the JVM: the reader's real store → the snapshot's inputs →
 * the snapshot → what a Favorites widget shows; and the Verse of the Day entry the widget draws.
 */
class WidgetContentTest {
    private lateinit var dir: File
    private lateinit var db: JdbcUserDatabase

    @Before fun setUp() {
        dir = Files.createTempDirectory("widgets").toFile()
        db = JdbcUserDatabase(File(dir, UserDataWidgetSource.FILE_NAME))
    }

    @After fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    private fun ref(book: BookID, chapter: Int, verse: Int) = VerseRef(book.number, chapter, verse)
    private val now: Instant = Instant.ofEpochSecond(1_790_000_000)

    private fun snapshotOf(library: WidgetLibrary) = VerseSnapshot.build(
        library.favorites, library.highlights, library.notes, "ASV", now,
        verseCount = { _, _ -> 30 }, text = { "Text of ${it.display}" },
    )

    @Test fun theReadersStoreBecomesTheWidgetsSnapshot() {
        val store = UserDataStore(db)
        store.toggleFavorite(listOf(VerseRange(ref(BookID.ROMANS, 8, 38), ref(BookID.ROMANS, 8, 39))), at = now.minusSeconds(60))
        store.toggleFavorite(listOf(VerseRange(ref(BookID.PSALMS, 23, 1), ref(BookID.PSALMS, 23, 1))), at = now)
        store.highlight(listOf(ref(BookID.JOHN, 3, 16).key, ref(BookID.JOHN, 3, 17).key), HighlightColor.YELLOW, at = now)
        store.save(
            Note(
                title = "Sermon", body = "Born of the Spirit.",
                anchors = listOf(VerseRange(ref(BookID.JOHN, 3, 1), ref(BookID.JOHN, 3, 21))),
                createdAt = now, updatedAt = now,
            ),
        )

        val library = UserDataWidgetSource.libraryOf(store)
        assertEquals(2, library.favorites.size)
        assertEquals(2, library.highlights.size) // one row per verse, as stored
        val snapshot = snapshotOf(library)
        assertEquals(listOf("Psalms 23:1", "Romans 8:38–39"), snapshot.items(setOf(Kind.FAVORITE)).map { it.reference })
        // The two highlighted verses are one range on the widget.
        assertEquals(listOf("John 3:16–17"), snapshot.items(setOf(Kind.HIGHLIGHT)).map { it.reference })
        val note = snapshot.items(setOf(Kind.NOTE)).single()
        assertEquals("Sermon", note.noteTitle)
        assertEquals("Born of the Spirit.", note.noteBody)
    }

    @Test fun anEmptyStoreIsAnEmptyWidget() {
        val library = UserDataWidgetSource.libraryOf(UserDataStore(db))
        assertEquals(WidgetLibrary.EMPTY, library)
        val entry = FavoritesEntry.at(snapshotOf(library), VerseSource.EVERYTHING, now, nudge = 0)
        assertNull(entry.item)
        assertEquals(0, entry.total)
    }

    @Test fun theDemoLibraryIsDemoLibrarySwift() = runBlocking {
        val library = DemoWidgetContentSource(now).library.first()
        val snapshot = snapshotOf(library)
        assertEquals(
            listOf("Romans 8:38–39", "Psalms 23:1", "John 14:6", "Isaiah 40:31"),
            snapshot.items(setOf(Kind.FAVORITE)).map { it.reference },
        )
        assertEquals(4, snapshot.items(setOf(Kind.HIGHLIGHT)).size) // John 3:16–17 merge into one
        assertEquals(
            listOf("Evening sermon: Born of the Spirit", "Sunday sermon: No condemnation"),
            snapshot.items(setOf(Kind.NOTE)).map { it.noteTitle },
        )
    }

    @Test fun rotationAndNextFollowTheIosWidget() {
        val snapshot = snapshotOf(DemoWidgetContentSource(now).library.let { runBlocking { it.first() } })
        val zone = ZoneId.systemDefault()
        val morning = LocalDateTime.of(2026, 9, 21, 7, 0).atZone(zone).toInstant()
        val later = LocalDateTime.of(2026, 9, 21, 10, 0).atZone(zone).toInstant()
        val a = FavoritesEntry.at(snapshot, VerseSource.FAVORITES, morning, nudge = 0)
        val b = FavoritesEntry.at(snapshot, VerseSource.FAVORITES, later, nudge = 0)
        val nudged = FavoritesEntry.at(snapshot, VerseSource.FAVORITES, morning, nudge = 1)
        assertEquals(4, a.total)
        assertEquals((a.position + 1) % 4, b.position)
        assertEquals(b.item, nudged.item)
        assertTrue(a.item!!.kind == Kind.FAVORITE)
        // Only notes: the widget never shows a favorite.
        val notes = FavoritesEntry.at(snapshot, VerseSource.NOTES, morning, nudge = 0)
        assertEquals(Kind.NOTE, notes.item!!.kind)
        assertEquals(2, notes.total)
    }

    @Test fun verseOfTheDayHonoursTheReadersTranslationWhereTheListHasIt() {
        val root = System.getProperty("scripturealone.resources") ?: error("scripturealone.resources is not set")
        val catalog = DailyVerseCatalog.parse(File(root, "../Shared/DailyVerses.json").readText())
        val day = LocalDateTime.of(2026, 9, 21, 9, 0).atZone(ZoneId.of("America/Chicago")).toInstant()
        val zone = ZoneId.of("America/Chicago")
        val kjv = VerseOfDayEntry.at(catalog, day, "KJV", zone)
        assertEquals("KJV", kjv.translation)
        assertEquals(catalog.verse(day, zone)!!.text["KJV"], kjv.text)
        // An online or imported translation isn't in the list: the ASV, and labelled as the ASV.
        val esv = VerseOfDayEntry.at(catalog, day, "ESV", zone)
        assertEquals("ASV", esv.translation)
        assertEquals(catalog.verse(day, zone)!!.text["ASV"], esv.text)
        assertEquals(kjv.reference, esv.reference)
        // The same passage all day, the next one after local midnight.
        val lateEvening = LocalDateTime.of(2026, 9, 21, 23, 59).atZone(zone).toInstant()
        val nextDay = LocalDateTime.of(2026, 9, 22, 0, 0).atZone(zone).toInstant()
        assertEquals(kjv.verse, VerseOfDayEntry.at(catalog, lateEvening, "KJV", zone).verse)
        assertTrue(kjv.verse != VerseOfDayEntry.at(catalog, nextDay, "KJV", zone).verse)
        assertEquals(nextDay, DailyVerseCatalog.nextMidnight(day, zone))
    }

    @Test fun snapshotTextIsTheOpeningOfTheFirstChapter() {
        fun verses(book: Int, chapter: Int) = (0..40).map { v ->
            ChapterVerse(VerseRef(book, chapter, v), if (v == 0) "Heading" else if (v == 1) "¶ v$v" else "v$v", emptyList())
        }
        val long = VerseRange(ref(BookID.ROMANS, 8, 1), ref(BookID.ROMANS, 9, 5))
        assertEquals((1..13).joinToString(" ") { "v$it" }, WidgetSnapshots.textOf(long, ::verses))
        val short = VerseRange(ref(BookID.JOHN, 3, 16), ref(BookID.JOHN, 3, 17))
        assertEquals("v16 v17", WidgetSnapshots.textOf(short, ::verses))
    }

    @Test fun widgetSizesPickTheIosFamilies() {
        assertEquals(WidgetFamily.SMALL, WidgetFamily.of(WidgetFamily.SMALL_SIZE))
        assertEquals(WidgetFamily.MEDIUM, WidgetFamily.of(WidgetFamily.MEDIUM_SIZE))
        assertEquals(WidgetFamily.LARGE, WidgetFamily.of(WidgetFamily.LARGE_SIZE))
    }
}
