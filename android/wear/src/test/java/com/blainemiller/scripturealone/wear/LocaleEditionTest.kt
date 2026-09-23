package com.blainemiller.scripturealone.wear

import com.blainemiller.scripturealone.companion.VerseSnapshot
import com.blainemiller.scripturealone.companion.WatchEditionBuilder
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.canon.BookNames
import com.blainemiller.scripturealone.data.daily.DailyVerseCatalog
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

/**
 * A locale Bible's edition as the phone sends it, read on the watch: the Segond's own verse numbers are
 * drawn, while everything stored or handed over — favorites, highlights, notes, today's verse, routes —
 * stays in KJV keys (docs/localization.md). Built here from the real LSG database with the phone's
 * statements ([WatchEditionBuilder]).
 */
class LocaleEditionTest {

    companion object {
        private val root = File(System.getProperty("scripturealone.watchResources") ?: error("scripturealone.watchResources is not set"))

        private val file: File by lazy {
            val out = File.createTempFile("LSG-", "-Watch.sqlite").apply { delete(); deleteOnExit() }
            DriverManager.getConnection("jdbc:sqlite:${out.path}").use { c ->
                WatchEditionBuilder.write(Builder(c), File(root, "../../ScriptureAlone/Resources/Bibles/LSG.sqlite").path)
            }
            out
        }
        private val rows by lazy { JdbcEditionRows(file) }
        private val lsg by lazy { WatchEdition("LSG", rows) }

        @AfterClass @JvmStatic fun close() {
            rows.connection.close()
            BookNames.use(null)
        }
    }

    private class Builder(val connection: Connection) : WatchEditionBuilder.Database {
        override fun execute(sql: String, vararg args: Any) {
            connection.prepareStatement(sql).use { st ->
                args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                st.execute()
            }
        }

        override fun sourceHasTable(name: String): Boolean =
            connection.prepareStatement("SELECT 1 FROM src.sqlite_master WHERE type = 'table' AND name = ?").use { st ->
                st.setString(1, name)
                st.executeQuery().use { it.next() }
            }

        override fun transaction(block: () -> Unit) {
            connection.autoCommit = false
            try {
                block()
                connection.commit()
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private fun ref(book: BookID, chapter: Int, verse: Int) = VerseRef(book.number, chapter, verse)
    private fun range(a: VerseRef, b: VerseRef = a) = VerseRange(a, b)

    @Test fun theEditionKnowsItsLanguageAndNumbering() {
        assertEquals("Louis Segond 1910", lsg.name)
        assertEquals("fr", lsg.language)
        assertFalse(lsg.numbering.isIdentity)
        BookNames.use(lsg.language)
        assertEquals("Jean", BookID.JOHN.displayName)
        val john3 = lsg.nativeVerses(lsg.chapterRange(BookID.JOHN.number, 3))
        assertEquals(36, john3.size)
        assertTrue(john3[15].text.startsWith("Car Dieu a tant aimé le monde"))
    }

    @Test fun aKjvKeyReadsTheVerseThatHoldsIt() {
        // A favorite on the KJV's Psalm 51:10 is the Segond's 51:12.
        val verses = lsg.verses(range(ref(BookID.PSALMS, 51, 10)))
        assertEquals(1, verses.size)
        assertEquals(ref(BookID.PSALMS, 51, 12), verses[0].ref)
        assertEquals(ref(BookID.PSALMS, 51, 10), verses[0].kjv)
        assertTrue(verses[0].text.contains("cœur pur"))
        assertEquals(range(ref(BookID.PSALMS, 51, 12)), lsg.nativeRange(range(ref(BookID.PSALMS, 51, 10))))
        // The KJV's Exodus 8:1 is the Segond's 7:26.
        assertEquals(ref(BookID.EXODUS, 7, 26), lsg.verses(range(ref(BookID.EXODUS, 8, 1))).single().ref)
    }

    @Test fun nativeAndKjvKeysRoundTrip() {
        for ((book, chapter) in listOf(BookID.PSALMS to 51, BookID.EXODUS to 7, BookID.EXODUS to 8, BookID.JOHN to 3, BookID.MALACHI to 3)) {
            val verses = lsg.nativeVerses(lsg.chapterRange(book.number, chapter))
            assertTrue("$book $chapter", verses.isNotEmpty())
            for (verse in verses) {
                // A psalm's title verses fold onto the KJV's verse 1, which reads back as the verse itself.
                if (book == BookID.PSALMS && verse.ref.verse <= 2) continue
                assertEquals("$book ${verse.ref}", verse.ref.key, lsg.numbering.native(verse.kjv.key))
                // Opening the verse from the chapter (by its KJV key) reads the very verse tapped.
                assertEquals(verse.ref, lsg.verses(lsg.numbering.kjvRange(range(verse.ref))).first().ref)
            }
        }
    }

    @Test fun highlightsStoredInKjvKeysTintTheNativeVerse() {
        val now = Instant.ofEpochSecond(1_790_000_000)
        val snapshot = VerseSnapshot(
            generatedAt = now, translation = "LSG",
            items = listOf(VerseSnapshot.Item(VerseSnapshot.Kind.HIGHLIGHT, "19051010-19051010", 19051010, 19051010, "", "", "yellow", null, now)),
        )
        val psalm = lsg.nativeVerses(lsg.chapterRange(BookID.PSALMS.number, 51))
        assertEquals(mapOf(ref(BookID.PSALMS, 51, 12).key to "yellow"), snapshot.highlightColors(psalm, lsg.numbering))
    }

    @Test fun verseOfTheDayIsInTheReadersLanguage() {
        val catalog = DailyVerseCatalog.parse(File(root, "../../ScriptureAlone/Shared/DailyVerses.json").readText())
        val available = catalog.translations
        assertEquals("LSG", WatchVerseOfDay.translation("ASV", available, listOf("fr-FR")))
        assertEquals("LSG", WatchVerseOfDay.translation("LSG", available, listOf("fr-FR")))
        assertEquals("CUVS", WatchVerseOfDay.translation("KJV", available, listOf("zh-Hans-CN")))
        assertEquals("KRV", WatchVerseOfDay.translation("KRV", available, listOf("en-US")))
        assertEquals("BSB", WatchVerseOfDay.translation("BSB", available, listOf("en-US")))
        // Traditional Chinese has no Bible of its own here: not handed the simplified one.
        assertEquals("ASV", WatchVerseOfDay.translation("ASV", available, listOf("zh-TW")))

        // Its reference in the Segond's own numbers; the list is keyed by the KJV's.
        val renumbered = catalog.verses.first { lsg.numbering.nativeRange(it.range!!) != it.range }
        val native = lsg.numbering.nativeRange(renumbered.range!!)!!
        val entry = WatchVerseOfDay.at(catalog, Instant.EPOCH, "LSG", numbering = lsg.numbering)!!.copy(verse = renumbered)
        assertEquals(native.display, entry.reference)
        assertTrue(entry.reference != renumbered.range!!.display)
        assertEquals(renumbered.text.getValue("LSG"), entry.text)
    }
}

/** [EditionRows] over JDBC. */
class JdbcEditionRows(file: File) : EditionRows {
    val connection: Connection = DriverManager.getConnection("jdbc:sqlite:${file.path}")

    override fun <T> query(sql: String, vararg args: Any, map: (EditionRows.Row) -> T): List<T> =
        connection.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeQuery().use { rs ->
                val row = object : EditionRows.Row {
                    override fun long(column: Int) = rs.getLong(column + 1)
                    override fun text(column: Int): String = rs.getString(column + 1)
                    override fun isNull(column: Int) = rs.getObject(column + 1) == null
                }
                buildList { while (rs.next()) add(map(row)) }
            }
        }
}
