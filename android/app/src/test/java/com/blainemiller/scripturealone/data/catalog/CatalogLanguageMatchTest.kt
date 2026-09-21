package com.blainemiller.scripturealone.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A port of `CatalogLanguageMatchTests.swift`. */
class CatalogLanguageMatchTest {

    private fun entry(
        id: String, language: String, books: Int = 66, verses: Int = 31102,
        script: String = "Latin", title: String? = null,
    ) = CatalogTranslation(
        id = id, languageCode = language, languageName = language,
        languageNameInEnglish = language, title = title ?: id, shortTitle = id,
        copyright = "Public Domain", isRedistributable = true,
        otBooks = if (books > 27) 39 else 0, ntBooks = minOf(books, 27),
        otVerses = if (books > 27) verses - 7957 else 0,
        ntVerses = if (books > 27) 7957 else verses,
        textDirection = "ltr", script = script,
    )

    @Test fun mapsAppleTagsToISO639_3() {
        assertEquals(listOf("eng"), CatalogLanguageMatch.preferredLanguageCodes(listOf("en-US")))
        assertEquals(listOf("spa", "eng"), CatalogLanguageMatch.preferredLanguageCodes(listOf("es-MX", "en-US")))
        assertEquals(listOf("deu"), CatalogLanguageMatch.preferredLanguageCodes(listOf("de-DE")))
        assertEquals(listOf("jpn"), CatalogLanguageMatch.preferredLanguageCodes(listOf("ja-JP")))
    }

    @Test fun keepsThePreferenceOrderAndDropsDuplicates() {
        val codes = CatalogLanguageMatch.preferredLanguageCodes(listOf("en-GB", "en-US", "fr-FR"))
        assertEquals(listOf("eng", "fra"), codes)
    }

    @Test fun ignoresTagsWithNoLanguage() {
        assertTrue("eng" in CatalogLanguageMatch.preferredLanguageCodes(listOf("", "und", "en-US")))
    }

    @Test fun putsTheReadersLanguageFirst() {
        val all = listOf(entry("spaRV", "spa"), entry("engWEB", "eng"), entry("fraLSG", "fra"))
        val parts = CatalogLanguageMatch.split(all, preferred = listOf("eng"), script = "Latin")
        assertEquals(listOf("engWEB"), parts.mine.map { it.id })
        assertEquals(setOf("spaRV", "fraLSG"), parts.other.map { it.id }.toSet())
    }

    @Test fun ordersABilingualDeviceByItsOwnPreference() {
        val all = listOf(entry("fraLSG", "fra"), entry("engWEB", "eng"), entry("spaRV", "spa"))
        val mine = CatalogLanguageMatch.split(all, preferred = listOf("spa", "eng"), script = null).mine
        assertEquals(listOf("spaRV", "engWEB"), mine.map { it.id })
    }

    @Test fun completeBiblesBeforeNewTestaments() {
        val all = listOf(entry("engNT", "eng", books = 27, verses = 7957), entry("engFull", "eng"))
        val mine = CatalogLanguageMatch.split(all, preferred = listOf("eng"), script = null).mine
        assertEquals(listOf("engFull", "engNT"), mine.map { it.id })
    }

    /** A Simplified Chinese device should not have to scroll past Traditional editions. */
    @Test fun prefersTheReadersScriptWithinOneLanguage() {
        val all = listOf(entry("cmnHant", "cmn", script = "Hant"), entry("cmnHans", "cmn", script = "Hans"))
        val mine = CatalogLanguageMatch.split(all, preferred = listOf("cmn"), script = "Hans").mine
        assertEquals(listOf("cmnHans", "cmnHant"), mine.map { it.id })

        val traditional = CatalogLanguageMatch.split(all, preferred = listOf("cmn"), script = "Hant").mine
        assertEquals(listOf("cmnHant", "cmnHans"), traditional.map { it.id })
    }

    @Test fun readsAScriptFromTheTag() {
        assertEquals("Hans", CatalogLanguageMatch.preferredScript("zh-Hans-CN"))
        assertEquals("Hant", CatalogLanguageMatch.preferredScript("zh-Hant-TW"))
        assertEquals("Latn", CatalogLanguageMatch.preferredScript("en-US"))
    }

    /** An unmatched locale must still show the whole catalogue, not an empty screen. */
    @Test fun everythingSurvivesAnUnknownLocale() {
        val all = listOf(entry("engWEB", "eng"), entry("spaRV", "spa"))
        val parts = CatalogLanguageMatch.split(all, preferred = listOf("xyz"), script = null)
        assertTrue(parts.mine.isEmpty())
        assertEquals(2, parts.other.size)
        assertEquals(2, CatalogLanguageMatch.ordered(all, preferred = listOf("xyz"), script = null).size)
    }

    @Test fun orderedIsPreferredThenTheRest() {
        val all = listOf(entry("spaRV", "spa"), entry("engWEB", "eng"))
        assertEquals(
            listOf("engWEB", "spaRV"),
            CatalogLanguageMatch.ordered(all, preferred = listOf("eng"), script = null).map { it.id },
        )
    }

    /** A caller repeating a code must not crash; the code keeps its first, highest position. */
    @Test fun aRepeatedPreferredCodeKeepsItsFirstPosition() {
        val all = listOf(entry("engWEB", "eng"), entry("spaRV", "spa"))
        val parts = CatalogLanguageMatch.split(all, preferred = listOf("spa", "eng", "spa"), script = null)
        assertEquals(listOf("spaRV", "engWEB"), parts.mine.map { it.id })
        assertTrue(parts.other.isEmpty())
    }
}
