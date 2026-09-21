package com.blainemiller.scripturealone.data.catalog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A port of `CatalogCurationTests.swift`. The allowlist is a promise, so it is pinned by tests rather than left to drift. */
class CatalogCurationTest {

    private fun entry(id: String, language: String = "eng", ot: Int = 39, nt: Int = 27) = CatalogTranslation(
        id = id, languageCode = language, languageName = language,
        languageNameInEnglish = language, title = id, shortTitle = id,
        copyright = "public domain", isRedistributable = true,
        otBooks = ot, ntBooks = nt, otVerses = 23145, ntVerses = 7957,
        textDirection = "ltr", script = "Latin",
    )

    @Test fun theAllowlistIsOffered() {
        for (id in listOf("eng-asv", "engbsb", "engylt", "englsv", "engnet", "eng-kjv2006", "engwebp")) {
            assertTrue("$id should be offered", CatalogCuration.isCurated(entry(id)))
        }
    }

    /**
     * Each of these was considered and excluded by name; a regression that admits one is a promise
     * broken, not a cosmetic change.
     */
    @Test fun theExcludedAreNotOffered() {
        for (id in CatalogCuration.englishExcluded) {
            assertFalse("$id must not be offered", CatalogCuration.isCurated(entry(id)))
        }
    }

    @Test fun apocryphaEditionsAreExcludedByName() {
        for (id in listOf("engDRA", "eng-kjv", "eng-rv", "eng-webbe")) {
            assertTrue(id in CatalogCuration.englishExcluded)
            assertFalse(CatalogCuration.isCurated(entry(id)))
        }
    }

    @Test fun aNewTestamentIsNeverCurated() {
        assertFalse(CatalogCuration.isCurated(entry("engbsb", ot = 0, nt = 27)))
        assertFalse(CatalogCuration.isUncurated(entry("spaRV", language = "spa", ot = 0, nt = 27)))
    }

    /** Other languages are offered, but as eBible's list rather than as the app's recommendation. */
    @Test fun otherLanguagesAreUncuratedNotHidden() {
        val spanish = entry("spaRV", language = "spa")
        assertFalse(CatalogCuration.isCurated(spanish))
        assertTrue(CatalogCuration.isUncurated(spanish))
    }

    @Test fun englishNotOnTheListIsOfferedNeitherWay() {
        val unknown = entry("engSomethingNew")
        assertFalse(CatalogCuration.isCurated(unknown))
        assertFalse("English is decided by the allowlist only", CatalogCuration.isUncurated(unknown))
    }

    @Test fun theTwoListsNeverOverlap() {
        assertTrue(CatalogCuration.englishAllowlist.intersect(CatalogCuration.englishExcluded).isEmpty())
    }
}
