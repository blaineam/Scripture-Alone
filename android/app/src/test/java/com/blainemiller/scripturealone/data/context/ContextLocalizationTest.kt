package com.blainemiller.scripturealone.data.context

import com.blainemiller.scripturealone.data.study.JdbcSqlSource
import com.blainemiller.scripturealone.data.study.resource
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.sql.DriverManager

/**
 * The Study context in the reader's language (docs/localization.md) — a port of
 * `ContextLocalizationTests.swift`: a copy of the bundled `Context.sqlite` given a few French rows,
 * read back through [ContextStore].
 */
class ContextLocalizationTest {

    companion object {
        private lateinit var copy: File
        private lateinit var source: JdbcSqlSource

        @BeforeClass @JvmStatic fun seed() {
            copy = File.createTempFile("ctx-", ".sqlite").also { it.deleteOnExit() }
            resource("Study/Context.sqlite").copyTo(copy, overwrite = true)
            DriverManager.getConnection("jdbc:sqlite:${copy.path}").use { db ->
                db.createStatement().use { st ->
                    st.executeUpdate("DROP TABLE IF EXISTS translations")
                    st.executeUpdate(
                        "CREATE TABLE translations (lang TEXT NOT NULL, kind TEXT NOT NULL, source TEXT NOT NULL, " +
                            "text TEXT NOT NULL, PRIMARY KEY (lang, kind, source)) WITHOUT ROWID",
                    )
                    st.executeUpdate("INSERT INTO translations VALUES ('fr', 'place', 'a15257a', 'Jérusalem')")
                    st.executeUpdate("INSERT INTO translations VALUES ('fr', 'string', 'Exodus & Wilderness', 'L’Exode et le désert')")
                    st.executeUpdate("INSERT INTO translations VALUES ('fr', 'string', 'March–April', 'mars–avril')")
                    st.executeUpdate("INSERT INTO translations VALUES ('fr', 'string', 'Mediterranean Sea', 'Mer Méditerranée')")
                    st.executeUpdate("INSERT INTO translations VALUES ('fr', 'person', 'Levi', 'Lévi')")
                }
            }
            source = JdbcSqlSource(copy)
        }

        @AfterClass @JvmStatic fun close() {
            source.close()
            copy.delete()
        }
    }

    private fun feasts(store: ContextStore): FeastsChart = requireNotNull(store.charts().firstOrNull { it.kind == ChartKind.FEASTS }).feasts()

    @Test fun frenchNamesAndProse() {
        val store = ContextStore(source, "fr-CA")
        assertEquals("fr", store.language)
        val jerusalem = store.searchPlaces("Jérusalem").first()
        assertEquals("Jérusalem", jerusalem.name)
        assertEquals("L’Exode et le désert", store.eras().first { it.id == "exodus" }.name)
        assertTrue(store.labels().any { it.text == "Mer Méditerranée" })
        // Untranslated text falls back to the English rather than disappearing.
        assertEquals("The Patriarchs", store.eras().first { it.id == "patriarchs" }.name)
        // A tribe is a person: its name is spelled as the reader's Bible spells it.
        val tribes = requireNotNull(store.charts().firstOrNull { it.kind == ChartKind.TRIBES }).tribes()
        assertTrue(tribes.tribes.any { it.name == "Lévi" })
    }

    @Test fun feastSeasonsSurviveTranslation() {
        val french = feasts(ContextStore(source, "fr"))
        assertTrue(french.feasts.any { it.season == "mars–avril" && it.seasonGroup == "spring" })
        assertEquals(3, french.feasts.count { it.seasonGroup == "autumn" })
        // The English quotation gives way to the reader's own Bible.
        assertTrue(french.feasts.all { it.ntText == null })
    }

    @Test fun englishIsUntouched() {
        val english = ContextStore(source, null)
        assertNull(english.language)
        assertEquals("Exodus & Wilderness", english.eras().first { it.id == "exodus" }.name)
        val chart = feasts(english)
        assertTrue(chart.feasts.any { it.ntText != null })
        // The season groups are there in English too: the chart groups by them.
        assertEquals(4, chart.feasts.count { it.seasonGroup == "spring" })
    }

    @Test fun traditionalChineseGetsNoSimplifiedRows() {
        assertNull(ContextStore(source, "zh-Hant").language)
    }

    @Test fun aDatabaseWithoutTheTableIsEnglish() {
        JdbcSqlSource(resource("Study/Context.sqlite")).use { original ->
            val store = ContextStore(original, "fr")
            // The shipped database may or may not have the table yet; either way it opens and reads.
            assertTrue(store.eras().isNotEmpty())
            assertTrue(store.searchPlaces("Jerusalem").isNotEmpty())
        }
    }
}
