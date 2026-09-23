package com.blainemiller.scripturealone.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class LocaleBibleTest {
    private val all = listOf("ASV", "BSB", "KJV") + LocaleBible.LOCALES.keys + listOf("WEB")

    @Test
    fun anEnglishReaderSeesOnlyTheEnglishBiblesAndTheirOwn() {
        assertEquals(listOf("ASV", "BSB", "KJV", "WEB"), LocaleBible.offered(all, listOf("en-US")))
    }

    @Test
    fun aReaderSeesTheBibleOfEachOfTheirLanguages() {
        assertEquals(listOf("ASV", "BSB", "KJV", "LSG", "WEB"), LocaleBible.offered(all, listOf("fr-FR", "en-US")))
        // Every preferred language counts, not only the first.
        assertEquals(listOf("ASV", "BSB", "KJV", "LUT1912", "KRV", "WEB"),
            LocaleBible.offered(all, listOf("en-US", "ko-KR", "de-AT")))
    }

    @Test
    fun traditionalChineseIsNotHandedTheSimplifiedBible() {
        assertEquals(listOf("ASV", "BSB", "KJV", "WEB"), LocaleBible.offered(all, listOf("zh-Hant-TW")))
        assertEquals(listOf("ASV", "BSB", "KJV", "CUVS", "WEB"), LocaleBible.offered(all, listOf("zh-Hans-CN")))
    }

    @Test
    fun aDownloadedBibleStaysAfterTheLanguageChanges() {
        assertEquals(listOf("ASV", "BSB", "KJV", "RVR1909", "WEB"),
            LocaleBible.offered(all, listOf("en-US")) { it == "RVR1909" })
    }
}
