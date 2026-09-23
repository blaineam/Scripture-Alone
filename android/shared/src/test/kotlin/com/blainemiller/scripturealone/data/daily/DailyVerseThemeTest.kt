package com.blainemiller.scripturealone.data.daily

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Verse of the Day theme in the reader's language — `DailyVerse.theme(in:)` on iOS. */
class DailyVerseThemeTest {
    private val verse = DailyVerse(
        ref = "19023001-19023001", theme = "The LORD is my shepherd", text = mapOf("ASV" to "Jehovah is my shepherd"),
        themes = mapOf("fr" to "L’Éternel est mon berger", "pt-BR" to "O Senhor é o meu pastor", "zh-Hans" to "耶和华是我的牧者"),
    )

    @Test fun exactThenLanguageThenEnglish() {
        assertEquals("L’Éternel est mon berger", verse.theme("fr"))
        assertEquals("L’Éternel est mon berger", verse.theme("fr-CA"))
        assertEquals("O Senhor é o meu pastor", verse.theme("pt-BR"))
        assertEquals("O Senhor é o meu pastor", verse.theme("pt-PT"))
        assertEquals("The LORD is my shepherd", verse.theme("en"))
        assertEquals("The LORD is my shepherd", verse.theme("de"))
    }

    @Test fun simplifiedChineseOnlyForSimplifiedReaders() {
        assertEquals("耶和华是我的牧者", verse.theme("zh-Hans"))
        assertEquals("耶和华是我的牧者", verse.theme("zh-CN"))
        assertEquals("耶和华是我的牧者", verse.theme("zh-Hans-CN"))
        assertEquals("The LORD is my shepherd", verse.theme("zh-Hant"))
        assertEquals("The LORD is my shepherd", verse.theme("zh-TW"))
    }

    @Test fun anOlderCatalogWithoutThemesReadsTheEnglish() {
        assertEquals("The LORD is my shepherd", verse.copy(themes = emptyMap()).theme("fr"))
        val json = """{"version":1,"translations":["ASV"],"verses":[
            {"ref":"19023001-19023001","theme":"The LORD is my shepherd","text":{"ASV":"x"}},
            {"ref":"43003016-43003016","theme":"God so loved","text":{"ASV":"y"},"themes":{"ja":"神は世を愛された"}}]}"""
        val catalog = DailyVerseCatalog.parse(json)
        assertEquals("The LORD is my shepherd", catalog.verses[0].theme("ja"))
        assertEquals("神は世を愛された", catalog.verses[1].theme("ja-JP"))
    }
}
