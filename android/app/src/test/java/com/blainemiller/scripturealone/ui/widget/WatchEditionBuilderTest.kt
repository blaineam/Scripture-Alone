package com.blainemiller.scripturealone.ui.widget

import com.blainemiller.scripturealone.companion.LocaleBible
import com.blainemiller.scripturealone.companion.WatchEditionBuilder
import com.blainemiller.scripturealone.data.assets.AssetPack
import com.blainemiller.scripturealone.data.study.resource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * The watch edition the phone sends for a locale Bible — `WatchEditionTests.swift`: the statements the
 * phone runs on Android's SQLite ([WatchEditions]), run here through JDBC against the real databases.
 */
class WatchEditionBuilderTest {

    private class Jdbc(val connection: Connection) : WatchEditionBuilder.Database {
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
            } catch (e: Exception) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private fun build(id: String): File {
        val out = File.createTempFile("$id-", "-Watch.sqlite").apply { delete(); deleteOnExit() }
        DriverManager.getConnection("jdbc:sqlite:${out.path}").use { WatchEditionBuilder.write(Jdbc(it), resource("Bibles/$id.sqlite").path) }
        return out
    }

    private fun <T> query(file: File, sql: String, read: (java.sql.ResultSet) -> T): List<T> =
        DriverManager.getConnection("jdbc:sqlite:${file.path}").use { c ->
            c.createStatement().use { st -> st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(read(rs)) } } }
        }

    private fun count(file: File, sql: String): Int = query(file, sql) { it.getInt(1) }.single()

    @Test fun aLocaleEditionCarriesItsVerseMapAndLanguage() {
        val source = resource("Bibles/LSG.sqlite")
        val edition = build("LSG")
        // Segond renumbers ~1,400 verses (docs/localization.md): every row travels.
        val sourceMap = count(source, "SELECT count(*) FROM kjv_map")
        assertTrue("LSG has a kjv_map", sourceMap > 1_000)
        assertEquals(sourceMap, count(edition, "SELECT count(*) FROM kjv_map"))
        // Psalm 51:12 in French is the KJV's 51:10.
        assertEquals(listOf(19051010), query(edition, "SELECT kjv FROM kjv_map WHERE id = 19051012") { it.getInt(1) })
        val meta = query(edition, "SELECT key, value FROM meta") { it.getString(1) to it.getString(2) }.toMap()
        assertEquals("LSG", meta["id"])
        assertEquals("fr", meta["language"])
        assertEquals("watch", meta["edition"])
        assertEquals(count(source, "SELECT count(*) FROM verses"), count(edition, "SELECT count(*) FROM verses"))
        assertEquals(1_189, count(edition, "SELECT count(*) FROM chapters"))
        assertEquals("Jean", query(edition, "SELECT name FROM books WHERE book = 43") { it.getString(1) }.single())
        // No layout, no search index: what makes it a third of the size.
        val tables = query(edition, "SELECT name FROM sqlite_master WHERE type = 'table'") { it.getString(1) }
        assertFalse(tables.any { it.startsWith("verses_fts") })
        assertFalse(query(edition, "PRAGMA table_info(chapters)") { it.getString("name") }.contains("layout"))
        assertTrue("${edition.length()} bytes", edition.length() < source.length() / 2)
    }

    @Test fun anEnglishBibleHasAnEmptyMap() {
        val edition = build("BSB")
        assertEquals(0, count(edition, "SELECT count(*) FROM kjv_map"))
        assertEquals(31, count(edition, "SELECT verses FROM chapters WHERE book = 1 AND chapter = 1"))
    }

    @Test fun thePacksAndTheSharedTableAgree() {
        val packs = AssetPack.entries.filter { it.locale != null }.associate { it.name to it.locale }
        assertEquals(LocaleBible.LOCALES, packs)
        assertEquals(AssetPack.LSG, AssetPack.bible(listOf("fr-CA")))
    }
}
