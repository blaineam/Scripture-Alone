package com.blainemiller.scripturealone.data.search

import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.importer.ImportFixtures
import com.blainemiller.scripturealone.data.study.JdbcSqlSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An imported translation searches like a bundled one: the importer writes the same `verses_fts`
 * index (unicode61, diacritics removed) and the reader's [VerseSearch] queries it, as
 * `FileChapterSource.search` does in the app.
 */
class ImportedSearchTest {

    private val john = """
        \id JHN
        \c 10
        \p
        \v 11 I am the good shepherd: the good shepherd layeth down his life for the sheep.
        \v 14 I am the good shepherd; and I know mine own, and mine own know me.
        \c 11
        \p
        \v 35 Jesus wept.
        \v 36 The Jews therefore said, Behold how he loved him!
    """.trimIndent()

    private val psalm = """
        \id PSA
        \c 23
        \q1
        \v 1 \nd Jehovah\nd* is my shepherd; I shall not want.
    """.trimIndent()

    @Test
    fun anImportedStoreIsSearchedByItsOwnIndex() {
        val file = ImportFixtures.write(ImportFixtures.usfmZip(listOf("43-JHN.usfm" to john, "19-PSA.usfm" to psalm)), "import.zip")
        val result = ImportFixtures.importer().importBible(file, directory = ImportFixtures.scratchDirectory())
        JdbcSqlSource(result.storeFile).use { store ->
            val search = VerseSearch(store)
            // Canonical order: Psalms before John.
            assertEquals(listOf(VerseRef(19, 23, 1), VerseRef(43, 10, 11), VerseRef(43, 10, 14)), search.search("shepherd").map { it.ref })
            // The last word is a prefix while typing, and every word must appear.
            assertEquals(listOf(VerseRef(43, 10, 11), VerseRef(43, 10, 14)), search.search("good shep").map { it.ref })
            assertEquals(listOf(VerseRef(43, 11, 35)), search.search("\"Jesus wept\"").map { it.ref })
            assertTrue(search.search("\"wept Jesus\"").isEmpty())
            assertEquals("Jesus wept.", search.search("wept").single().text)
        }
    }
}
