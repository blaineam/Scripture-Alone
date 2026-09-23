package com.blainemiller.scripturealone.text

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** Which of its languages the app runs in, as the resource system would pick — `preferredLocalizations`. */
class AppLanguageTest {
    private fun resolve(vararg tags: String) = AppLanguage.resolve(tags.map(Locale::forLanguageTag))

    @Test fun theFirstLanguageTheAppSpeaks() {
        assertEquals("fr", resolve("fr-CA"))
        assertEquals("pt-BR", resolve("pt-PT"))
        assertEquals("ja", resolve("ja-JP", "en-US"))
        assertEquals("en", resolve("en-GB", "fr-FR"))
        // Russian isn't one of the app's languages: the next one the reader reads is.
        assertEquals("de", resolve("ru-RU", "de-AT"))
        assertEquals("en", resolve("ru-RU"))
        assertEquals("en", resolve())
    }

    @Test fun simplifiedChineseButNotTraditional() {
        assertEquals("zh-Hans", resolve("zh-Hans-CN"))
        assertEquals("zh-Hans", resolve("zh-CN"))
        assertEquals("zh-Hans", resolve("zh-SG"))
        assertEquals("en", resolve("zh-Hant-TW"))
        assertEquals("en", resolve("zh-TW"))
        assertEquals("en", resolve("zh-HK"))
    }

    @Test fun countedPhrasesFollowTheLanguagesRules() {
        assertEquals(true, AppText.isOne(1, null))
        assertEquals(false, AppText.isOne(0, null))
        assertEquals(false, AppText.isOne(2, null))
    }
}
