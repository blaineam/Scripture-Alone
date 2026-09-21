package com.blainemiller.scripturealone.data.search

import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.study.JdbcSqlSource
import com.blainemiller.scripturealone.data.study.resource
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The query translation, stated case by case, and the search run against the real BSB and KJV files
 * the app ships — the same FTS5 indexes the iOS app reads.
 *
 * `ScriptureAloneCore` has no direct test of `ftsQuery`; its behaviour is pinned there through
 * `BibleStoreTests.searchesInCanonicalOrder` and `PackageSearchTests` (which run "good shep",
 * "\"Jesus wept\"", "   ", "!!!" and the typing-narrows sequence). Those are ported below against
 * BSB/KJV — the ASV they use is sealed on Android and not searchable yet.
 */
class VerseSearchTest {

    // ftsQuery

    @Test
    fun everyWordMustMatchAndTheLastIsAPrefix() {
        assertEquals("\"good\" \"shep\" *", VerseSearch.ftsQuery("good shep"))
        assertEquals("\"love\" *", VerseSearch.ftsQuery("  love \n"))
    }

    @Test
    fun aQuotedQueryIsOneExactPhrase() {
        assertEquals("\"Jesus wept\"", VerseSearch.ftsQuery("\"Jesus wept\""))
        // Inner quotes are dropped rather than passed to FTS5 as syntax.
        assertEquals("\"in the beginning\"", VerseSearch.ftsQuery("\"in the \"beginning\""))
        // Only a wrapped query is a phrase; an unclosed quote is punctuation.
        assertEquals("\"Jesus\" \"wept\" *", VerseSearch.ftsQuery("\"Jesus wept"))
    }

    @Test
    fun emptyAndUselessQueriesHaveNoQuery() {
        assertNull(VerseSearch.ftsQuery(""))
        assertNull(VerseSearch.ftsQuery("   "))
        assertNull(VerseSearch.ftsQuery("!!!"))
        // Two quotes is not more than two characters, so it's words — and there are none.
        assertNull(VerseSearch.ftsQuery("\"\""))
        // Three quotes is a phrase of nothing.
        assertNull(VerseSearch.ftsQuery("\"\"\""))
    }

    @Test
    fun punctuationSeparatesAndApostrophesJoin() {
        assertEquals("\"faith\" \"hope\" \"love\" *", VerseSearch.ftsQuery("faith, hope & love."))
        // The curly apostrophe is straightened; either keeps the word whole.
        assertEquals("\"the\" \"LORD's\" \"anointed\" *", VerseSearch.ftsQuery("the LORD’s anointed"))
        assertEquals("\"LORD's\" *", VerseSearch.ftsQuery("LORD's"))
    }

    @Test
    fun lettersDigitsAndMarksAreWordCharacters() {
        assertEquals("\"1\" \"Cor\" \"13\" *", VerseSearch.ftsQuery("1 Cor 13"))
        assertEquals("\"Bethsaïda\" *", VerseSearch.ftsQuery("Bethsaïda"))
        // A decomposed diaeresis is a mark (M*), part of the word as in Foundation's alphanumerics.
        assertEquals("\"Bethsaïda\" *", VerseSearch.ftsQuery("Bethsaïda"))
    }

    @Test
    fun operatorWordsAreQuotedSoFts5ReadsThemAsWords() {
        assertEquals("\"love\" \"not\" \"and\" \"or\" \"near\" *", VerseSearch.ftsQuery("love not and or near"))
    }

    // Search against the shipped stores

    @Test
    fun searchesInCanonicalOrder() {
        val hits = bsb.search("good shep")
        assertEquals(VerseRef(43, 10, 11), hits.first().ref)
        assertEquals(hits.map { it.ref.key }.sorted(), hits.map { it.ref.key })
        assertTrue(hits.first().text.startsWith("I am the good shepherd."))
        assertEquals(listOf(VerseRef(43, 11, 35)), kjv.search("\"Jesus wept\"").map { it.ref })
        assertTrue(kjv.search("  ").isEmpty())
        assertTrue(kjv.search("!!!").isEmpty())
        assertTrue(kjv.search("zzzzq").isEmpty())
    }

    @Test
    fun aPhraseIsAdjacentWordsNotJustBothWords() {
        assertTrue(kjv.search("\"wept Jesus\"").isEmpty())
        assertTrue(kjv.search("jesus wept").isNotEmpty())
    }

    @Test
    fun aPrefixNarrowsWhileTyping() {
        val counts = listOf("good she", "good shep", "good shephe", "good shepherd").map { bsb.search(it).size }
        assertEquals(counts.sortedDescending(), counts)
        assertTrue(counts.last() > 0)
    }

    @Test
    fun resultsStopAtTheLimit() {
        val common = kjv.search("the")
        assertEquals(VerseSearch.DEFAULT_LIMIT, common.size)
        assertEquals(VerseRef(1, 1, 1), common.first().ref)
        assertEquals(1, kjv.search("good shepherd", limit = 1).size)
        assertTrue(kjv.search("good", limit = 0).isEmpty())
    }

    @Test
    fun operatorWordsAndApostrophesDoNotBreakTheQuery() {
        assertTrue(kjv.search("love not").isNotEmpty())
        assertTrue(bsb.search("the LORD’s").isNotEmpty())
    }

    // Emphasis

    @Test
    fun typedWordsAreEmphasisedWhereverTheyFall() {
        val text = "I am the good shepherd. The good shepherd lays down His life"
        val marked = SearchEmphasis.ranges(text, "good shep").map { text.substring(it) }
        assertEquals(listOf("good", "shep", "good", "shep"), marked)
    }

    @Test
    fun emphasisIgnoresCaseDiacriticsAndOneLetterWords() {
        val text = "A naïve man; the LORD’s house"
        assertEquals(listOf("naïve"), SearchEmphasis.ranges(text, "a NAIVE").map { text.substring(it) })
        assertEquals(listOf("LORD"), SearchEmphasis.ranges(text, "lord").map { text.substring(it) })
        assertTrue(SearchEmphasis.ranges(text, "a").isEmpty())
        assertTrue(SearchEmphasis.ranges(text, "  ").isEmpty())
    }

    @Test
    fun overlappingMatchesMergeIntoOneRange() {
        val text = "shepherds"
        assertEquals(listOf(0..8), SearchEmphasis.ranges(text, "shep shepherds"))
    }

    companion object {
        private val bsbSource = JdbcSqlSource(resource("Bibles/BSB.sqlite"))
        private val kjvSource = JdbcSqlSource(resource("Bibles/KJV.sqlite"))
        private val bsb = VerseSearch(bsbSource)
        private val kjv = VerseSearch(kjvSource)

        @JvmStatic
        @AfterClass
        fun close() {
            bsbSource.close()
            kjvSource.close()
        }
    }
}
