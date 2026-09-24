package com.blainemiller.scripturealone.data.translations

import com.blainemiller.scripturealone.data.TranslationInfo
import com.blainemiller.scripturealone.data.online.OnlineEntry
import com.blainemiller.scripturealone.data.online.OnlineProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An import stands in for the online copy of the same translation — `ReaderModel.sameTranslation`. */
class SameTranslationTest {
    private val online = OnlineEntry("ABC", "Alpha Beta Chronicle", OnlineProvider.API_BIBLE, "remote-1")

    @Test fun theSameAbbreviationOrNameIsTheSameTranslation() {
        assertTrue(TranslationLibrary.sameTranslation(TranslationInfo("IMPORT-1", "A Study Bible", "abc", "© Example"), online))
        assertTrue(TranslationLibrary.sameTranslation(TranslationInfo("IMPORT-2", "alpha beta chronicle", "XYZ", "© Example"), online))
    }

    @Test fun anotherTranslationIsNot() {
        assertFalse(TranslationLibrary.sameTranslation(TranslationInfo("IMPORT-3", "Other Bible", "OB", "© Example"), online))
        // An empty abbreviation matches nothing by abbreviation.
        assertFalse(TranslationLibrary.sameTranslation(TranslationInfo("IMPORT-4", "Other Bible", "", "© Example"), online.copy(id = "")))
    }
}
