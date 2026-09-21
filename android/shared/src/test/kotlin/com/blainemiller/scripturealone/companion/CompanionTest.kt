package com.blainemiller.scripturealone.companion

import com.blainemiller.scripturealone.companion.TranslationChoice.Pick
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionTest {

    // ScriptureLink — `deepLinksRoundTrip` in CompanionDataTests.swift.

    @Test fun deepLinksRoundTrip() {
        val range = VerseRange(VerseRef(BookID.ROMANS.number, 8, 28), VerseRef(BookID.ROMANS.number, 8, 30))
        val url = ScriptureLink.url(range)
        assertEquals("scripturealone://open?ref=45008028-45008030", url)
        assertEquals(range, ScriptureLink.range(url))
        val single = VerseRef(BookID.JOHN.number, 3, 16)
        assertEquals(VerseRange(single, single), ScriptureLink.range("scripturealone://open?ref=43003016"))
        assertEquals(VerseRange(single, single), ScriptureLink.range("SCRIPTUREALONE://OPEN?x=1&ref=43003016"))
        assertNull(ScriptureLink.range("https://example.com/open?ref=43003016"))
        assertNull(ScriptureLink.range("scripturealone://open?ref=99001001"))
        assertNull(ScriptureLink.range("scripturealone://open"))
        assertNull(ScriptureLink.range("not a url at all"))
    }

    // VerseStyling.openingWords / VerseAccessoryView.split

    @Test fun openingWordsForTightSpaces() {
        assertEquals("Jehovah is my shepherd; I shall…", VerseText.openingWords("Jehovah is my shepherd; I shall not want."))
        assertEquals("Jesus wept.", VerseText.openingWords("Jesus wept."))
        assertEquals("The LORD is my…", VerseText.openingWords("The LORD is my shepherd", maxWords = 4))
        // Punctuation before the cut is dropped, as `trimmingCharacters(in: .punctuationCharacters)`.
        assertEquals("For God so…", VerseText.openingWords("For God so, loved the world", maxWords = 3))
    }

    @Test fun referencesSplitForTheCircularComplication() {
        assertEquals("1 Cor" to "13:4–7", VerseText.split("1 Cor 13:4–7"))
        assertEquals("Ps" to "23:1", VerseText.split("Ps 23:1"))
        assertEquals("Jude" to "", VerseText.split("Jude"))
    }

    // WatchBible.resolve

    @Test fun theNewestChoiceTheWatchCanShowWins() {
        val all = listOf("ASV", "BSB", "KJV")
        assertEquals("ASV", TranslationChoice.resolve(all, null, null))
        assertEquals("KJV", TranslationChoice.resolve(all, Pick("BSB", 10.0), Pick("KJV", 20.0)))
        assertEquals("BSB", TranslationChoice.resolve(all, Pick("BSB", 30.0), Pick("KJV", 20.0)))
        // The phone's online translation has no edition here: remembered, not applied.
        assertEquals("BSB", TranslationChoice.resolve(all, Pick("BSB", 1.0), Pick("ESV", 99.0)))
        assertEquals("ASV", TranslationChoice.resolve(all, null, Pick("ESV", 99.0)))
        // A tie keeps the watch's own pick.
        assertEquals("BSB", TranslationChoice.resolve(all, Pick("BSB", 5.0), Pick("KJV", 5.0)))
        // No ASV at all: the first edition.
        assertEquals("KJV", TranslationChoice.resolve(listOf("KJV", "BSB"), null, null))
        assertEquals("ASV", TranslationChoice.resolve(emptyList(), null, null))
    }

    @Test fun translationIdsMustBeSafeFileNames() {
        assertTrue(WearLink.isSafeId("ASV"))
        assertTrue(WearLink.isSafeId("eng-web_2"))
        assertFalse(WearLink.isSafeId(""))
        assertFalse(WearLink.isSafeId("../ASV"))
        assertFalse(WearLink.isSafeId("a.b"))
        assertFalse(WearLink.isSafeId("é"))
        assertFalse(WearLink.isSafeId("x".repeat(65)))
    }

    @Test fun abbreviatedReferencesMatchSwift() {
        fun r(b: BookID, c: Int, v: Int) = VerseRef(b.number, c, v)
        assertEquals("Ps 23:1", VerseRange(r(BookID.PSALMS, 23, 1), r(BookID.PSALMS, 23, 1)).abbreviatedDisplay)
        assertEquals("1 Cor 13:4–7", VerseRange(r(BookID.FIRST_CORINTHIANS, 13, 4), r(BookID.FIRST_CORINTHIANS, 13, 7)).abbreviatedDisplay)
        assertEquals("Gen 1:1–2:3", VerseRange(r(BookID.GENESIS, 1, 1), r(BookID.GENESIS, 2, 3)).abbreviatedDisplay)
        assertEquals("John 3:16", VerseRange(r(BookID.JOHN, 3, 16), r(BookID.JOHN, 3, 16)).abbreviatedDisplay)
    }
}
