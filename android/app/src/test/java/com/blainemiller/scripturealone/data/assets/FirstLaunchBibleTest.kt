package com.blainemiller.scripturealone.data.assets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which Bible a first launch fetches for the device's languages — `AssetPack.bible(forPreferredLanguages:)`. */
class FirstLaunchBibleTest {

    @Test
    fun eachBig8LanguageGetsItsBible() {
        assertEquals(AssetPack.LSG, AssetPack.bible(listOf("fr-FR")))
        assertEquals(AssetPack.LSG, AssetPack.bible(listOf("fr-CA")))
        assertEquals(AssetPack.LUT1912, AssetPack.bible(listOf("de-DE")))
        assertEquals(AssetPack.RVR1909, AssetPack.bible(listOf("es-MX")))
        assertEquals(AssetPack.BLIVRE, AssetPack.bible(listOf("pt-BR")))
        assertEquals(AssetPack.RIV1927, AssetPack.bible(listOf("it-IT")))
        assertEquals(AssetPack.KRV, AssetPack.bible(listOf("ko-KR")))
        assertEquals(AssetPack.BUNGO, AssetPack.bible(listOf("ja-JP")))
    }

    @Test
    fun simplifiedChineseOnly() {
        assertEquals(AssetPack.CUVS, AssetPack.bible(listOf("zh-Hans-CN")))
        assertEquals(AssetPack.CUVS, AssetPack.bible(listOf("zh-CN")))
        assertNull(AssetPack.bible(listOf("zh-Hant-TW")))
        assertNull(AssetPack.bible(listOf("zh-TW")))
        // A Traditional reader who also reads Japanese gets the Japanese Bible.
        assertEquals(AssetPack.BUNGO, AssetPack.bible(listOf("zh-HK", "ja-JP")))
    }

    @Test
    fun englishAndOthersGetNone() {
        assertNull(AssetPack.bible(listOf("en-US", "fr-FR")))
        assertNull(AssetPack.bible(listOf("nl-NL")))
        assertEquals(AssetPack.LSG, AssetPack.bible(listOf("nl-NL", "fr-BE")))
        assertNull(AssetPack.bible(emptyList()))
    }

    @Test
    fun theBig8AreOnDemandTranslations() {
        for (pack in listOf(AssetPack.CUVS, AssetPack.BUNGO, AssetPack.LUT1912, AssetPack.LSG, AssetPack.RVR1909,
            AssetPack.KRV, AssetPack.BLIVRE, AssetPack.RIV1927)) {
            assertEquals(AssetPack.Delivery.ON_DEMAND, pack.delivery)
            assertEquals(pack, AssetPack.forTranslation(pack.name))
            assertEquals("${pack.name}.sqlite", pack.file)
        }
    }
}
