package com.blainemiller.scripturealone.data.reference

import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.canon.BookNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Passages typed in the big-8 languages (docs/localization.md) — `LocalizedReferenceTests.swift`, case
 * for case. The language is passed explicitly rather than set with [BookNames.use], so these can run
 * alongside the English parser tests.
 */
class LocalizedReferenceTest {

    private fun parsed(text: String, language: String?): Triple<BookID, Int, Int?>? =
        ReferenceParser.parse(text, language)?.let { Triple(it.book, it.startChapter, it.startVerse) }

    @Test
    fun nativeNamesInEachLanguage() {
        val cases = listOf(
            Triple("Jean 3:16", "fr", Triple(BookID.JOHN, 3, 16)),
            Triple("Psaumes 51:12", "fr", Triple(BookID.PSALMS, 51, 12)),
            Triple("Génesis 1:1", "es", Triple(BookID.GENESIS, 1, 1)),
            Triple("San Mateo 5:3", "es", Triple(BookID.MATTHEW, 5, 3)),
            Triple("Mateo 5", "es", Triple<BookID, Int, Int?>(BookID.MATTHEW, 5, null)),
            Triple("1. Mose 1,1", "de", Triple(BookID.GENESIS, 1, 1)),
            Triple("Johannes 3:16", "de", Triple(BookID.JOHN, 3, 16)),
            Triple("2. Korinther 5:17", "de", Triple(BookID.SECOND_CORINTHIANS, 5, 17)),
            Triple("João 3:16", "pt", Triple(BookID.JOHN, 3, 16)),
            Triple("Giovanni 3:16", "it", Triple(BookID.JOHN, 3, 16)),
            Triple("约翰福音3:16", "zh-Hans", Triple(BookID.JOHN, 3, 16)),
            Triple("约翰福音 3章16节", "zh-Hans", Triple(BookID.JOHN, 3, 16)),
            Triple("요한복음 3:16", "ko", Triple(BookID.JOHN, 3, 16)),
            Triple("요한복음 3장 16절", "ko", Triple(BookID.JOHN, 3, 16)),
            Triple("ヨハネ傳福音書3章16節", "ja", Triple(BookID.JOHN, 3, 16)),
            Triple("ヨハネによる福音書 3：16", "ja", Triple(BookID.JOHN, 3, 16)),   // the modern name, full-width colon
            Triple("民数記 6:24", "ja", Triple(BookID.NUMBERS, 6, 24)),
        )
        val failures = cases.mapNotNull { (text, language, expected) ->
            val result = parsed(text, language)
            if (result == expected) null else "$text [$language] → $result, expected $expected"
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun standardAbbreviations() {
        assertEquals(BookID.JOHN, parsed("Jn 3:16", "fr")?.first)
        assertEquals(BookID.PSALMS, parsed("Ps 23", "fr")?.first)
        assertEquals(BookID.ACTS, parsed("Hch 2:38", "es")?.first)
        assertEquals(BookID.REVELATION, parsed("Offb 21:4", "de")?.first)
        assertEquals(BookID.GENESIS, parsed("创 1:1", "zh-Hans")?.first)
        assertEquals(BookID.FIRST_CORINTHIANS, parsed("林前 13:4", "zh-Hans")?.first)
        assertEquals(BookID.GENESIS, parsed("창 1:1", "ko")?.first)
        assertEquals(BookID.FIRST_CORINTHIANS, parsed("고전 13:4", "ko")?.first)
        assertEquals(BookID.FIRST_CORINTHIANS, parsed("1コリ 13:4", "ja")?.first)
    }

    @Test
    fun anAmbiguousAbbreviationMeansTheReadersOwn() {
        // "Es" is Isaiah (Ésaïe) in French and Exodus (Esodo) in Italian.
        assertEquals(BookID.ISAIAH, parsed("Es 53:5", "fr")?.first)
        assertEquals(BookID.EXODUS, parsed("Es 20:3", "it")?.first)
    }

    @Test
    fun englishStillWorksEverywhere() {
        assertEquals(BookID.JOHN, parsed("jn 3 16", "fr")?.first)
        assertEquals(BookID.ROMANS, parsed("Rom 8:28", "ko")?.first)
        assertEquals(BookID.JOHN, parsed("jn 3 16", null)?.first)
    }

    @Test
    fun otherLanguagesUnambiguousNamesWorkToo() {
        // A Spanish reader typing a French reference still lands on it.
        assertEquals(BookID.JOHN, parsed("Jean 3:16", "es")?.first)
    }

    @Test
    fun germanCommaListsStillSplit() {
        val list = ReferenceParser.parseList("Joh 3,16; Röm 8,28", "de")
        assertEquals(listOf(BookID.JOHN, BookID.ROMANS), list.map { it.book })
        assertEquals(listOf(16, 28), list.map { it.startVerse })
    }

    @Test
    fun listsWithFullWidthSeparators() {
        val list = ReferenceParser.parseList("罗马书 8:28；约翰福音 3:16", "zh-Hans")
        assertEquals(listOf(BookID.ROMANS, BookID.JOHN), list.map { it.book })
    }

    @Test
    fun namesComeFromEachLanguagesBible() {
        assertEquals("Jean", BookNames.name(BookID.JOHN, "fr"))
        assertEquals("约翰福音", BookNames.name(BookID.JOHN, "zh-Hans"))
        assertEquals("1. Chronik", BookNames.name(BookID.FIRST_CHRONICLES, "de"))   // the source misspells it
        assertNull(BookNames.name(BookID.JOHN, null))
        assertNull(BookNames.tableKey("zh-Hant-TW"))
        assertNull(BookNames.tableKey("zh-TW"))
        assertEquals("zh-Hans", BookNames.tableKey("zh-CN"))
        assertEquals("pt", BookNames.tableKey("pt-BR"))
        assertEquals("fr", BookNames.tableKey("fr-CA"))
        assertNull(BookNames.tableKey("en-US"))
    }

    /** The one process-wide setting: `displayName` follows it, and English is back when it's cleared. */
    @Test
    fun displayNamesFollowTheBibleBeingRead() {
        try {
            BookNames.use("fr-FR")
            assertEquals("Jean", BookID.JOHN.displayName)
            assertEquals("Jean 3:16", ReferenceParser.parse("Jean 3:16")?.display)
            assertEquals("John", BookID.JOHN.englishName)
        } finally {
            BookNames.use(null)
        }
        assertEquals("John", BookID.JOHN.displayName)
    }
}
