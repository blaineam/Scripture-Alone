package com.blainemiller.scripturealone.data.numbering

import com.blainemiller.scripturealone.data.StoreChapters
import com.blainemiller.scripturealone.data.VerseNumbering
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.layout.ChapterLayout
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.blainemiller.scripturealone.data.search.VerseSearch
import com.blainemiller.scripturealone.data.study.JdbcSqlSource
import com.blainemiller.scripturealone.data.study.resource
import com.blainemiller.scripturealone.data.userdata.Highlight
import com.blainemiller.scripturealone.data.userdata.Selection
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The big-8 locales' Bibles keep their own verse numbers; marks are stored under KJV keys —
 * `VerseNumberingTests.swift`, case for case, run against the databases Tools/build_bibles.py builds
 * (docs/localization.md), plus the Android conversion points that sit on top.
 */
class VerseNumberingTest {

    private fun key(book: BookID, chapter: Int, verse: Int) = VerseRef(book.number, chapter, verse).key

    private fun numbering(id: String): VerseNumbering = StoreChapters.numbering(store(id))

    @Test
    fun englishBiblesAreIdentity() {
        for (id in listOf("ASV", "BSB", "KJV")) assertTrue("$id should number as the KJV does", numbering(id).isIdentity)
    }

    @Test
    fun segondPsalmsAndChapterBreaks() {
        val lsg = numbering("LSG")
        // "O Dieu! crée en moi un cœur pur" — French 51:12 is English 51:10.
        assertEquals(key(BookID.PSALMS, 51, 10), lsg.kjv(key(BookID.PSALMS, 51, 12)))
        assertEquals(key(BookID.PSALMS, 51, 12), lsg.native(key(BookID.PSALMS, 51, 10)))
        // The title verses fold onto verse 1, and verse 1 comes back to the verse, not the title.
        assertEquals(key(BookID.PSALMS, 51, 1), lsg.kjv(key(BookID.PSALMS, 51, 1)))
        assertEquals(key(BookID.PSALMS, 51, 3), lsg.native(key(BookID.PSALMS, 51, 1)))
        // The Hebrew chapter break: French Exodus 7:26 is English 8:1, and back.
        assertEquals(key(BookID.EXODUS, 8, 1), lsg.kjv(key(BookID.EXODUS, 7, 26)))
        assertEquals(key(BookID.EXODUS, 7, 26), lsg.native(key(BookID.EXODUS, 8, 1)))
        assertEquals(key(BookID.EXODUS, 8, 1), lsg.native(key(BookID.EXODUS, 8, 5)))
        // A verse neither side moved is itself.
        assertEquals(key(BookID.JOHN, 3, 16), lsg.kjv(key(BookID.JOHN, 3, 16)))
    }

    @Test
    fun aMarkedChapterSpansItsKJVKeys() {
        val lsg = numbering("LSG")
        // French Exodus 8 holds English 8:5 onward; its marks must be found by that key range.
        val exodus8 = lsg.kjvKeyRange(BookID.EXODUS.number, 8, verseCount("LSG", ChapterRef(BookID.EXODUS.number, 8)))
        assertEquals(key(BookID.EXODUS, 8, 5), exodus8.first)
        val exodus7 = lsg.kjvKeyRange(BookID.EXODUS.number, 7, verseCount("LSG", ChapterRef(BookID.EXODUS.number, 7)))
        assertTrue(key(BookID.EXODUS, 8, 4) in exodus7)
    }

    @Test
    fun versesAreReadByKJVKeyAndKeepTheirOwnNumbers() {
        val native = numbering("LSG").nativeRange(VerseRange.of(VerseRef(BookID.PSALMS.number, 51, 10)))!!
        val verses = StoreChapters.verses(store("LSG"), native.start.key, native.end.key)
        assertEquals(listOf(VerseRef(BookID.PSALMS.number, 51, 12)), verses.map { it.ref })
        assertTrue(verses.first().text.contains("cœur pur"))
    }

    @Test
    fun aVerseThatHoldsNineKJVVerses() {
        val rvr = numbering("RVR1909")
        val native = key(BookID.JOB, 39, 30)
        assertEquals(key(BookID.JOB, 39, 27)..key(BookID.JOB, 40, 5), rvr.kjvKeys(native))
        assertEquals(native, rvr.native(key(BookID.JOB, 40, 3)))
        assertEquals(native, rvr.native(key(BookID.JOB, 39, 28)))
        // One highlight per KJV verse: the rest of chapter 39 the map names, then 40:1–5.
        val keys = rvr.kjvKeyList(native)
        assertEquals(key(BookID.JOB, 39, 27), keys.first())
        assertTrue((1..5).all { key(BookID.JOB, 40, it) in keys })
    }

    @Test
    fun printedRangesDecodeAndMap() {
        val cuv = store("CUVS")
        val json = StoreChapters.layoutJson(cuv, ChapterRef(BookID.DEUTERONOMY.number, 13))!!
        val range = ChapterLayout.parse(json).blocks.flatMap { it.fragments }.first { it.verse == 12 && it.numbered }
        assertEquals(13, range.lastVerse)
        assertEquals("12–13", range.label)
        assertEquals(key(BookID.DEUTERONOMY, 13, 12), StoreChapters.numbering(cuv).native(key(BookID.DEUTERONOMY, 13, 13)))
    }

    @Test
    fun nativeBookNamesAndLanguage() {
        val lsg = StoreChapters.info(store("LSG"), "LSG")
        assertEquals("fr", lsg.language)
        assertEquals("Genèse", StoreChapters.bookNames(store("LSG"))[BookID.GENESIS.number])
        assertTrue(StoreChapters.bookNames(store("BSB")).isEmpty())
    }

    // Search — the trigram stores answer by substring.

    private fun search(id: String) = VerseSearch(store(id), StoreChapters.searchesBySubstring(store(id)), numbering(id))

    @Test
    fun chineseSearchFindsTwoCharacterWords() {
        val cuv = search("CUVS")
        assertTrue(StoreChapters.searchesBySubstring(store("CUVS")))
        val grace = cuv.search("恩典", limit = 500)           // two characters: LIKE
        assertTrue("恩典 found ${grace.size}", grace.size > 40)
        assertEquals(10, cuv.search("耶和华", limit = 10).size)  // three: the trigram index
        val both = cuv.search("神 爱世人", limit = 10)           // mixed
        assertTrue(both.any { it.ref == VerseRef(BookID.JOHN.number, 3, 16) })
    }

    @Test
    fun koreanSearchIgnoresParticles() {
        // 하나님 appears as 하나님이, 하나님을, 하나님의… — a word index would miss them all.
        val hits = search("KRV").search("하나님", limit = 5000)
        assertTrue("하나님 found ${hits.size}", hits.size > 3000)
    }

    @Test
    fun japaneseSearchesBySubstring() {
        assertTrue(search("BUNGO").search("神", limit = 50).isNotEmpty())
    }

    @Test
    fun cjkQueriesSearchFromTwoCharacters() {
        assertTrue(VerseSearch.isLongEnough("恩典"))
        assertTrue(VerseSearch.isLongEnough("은혜"))
        assertTrue(!VerseSearch.isLongEnough("神"))
        assertTrue(!VerseSearch.isLongEnough("lo"))
        assertTrue(VerseSearch.isLongEnough("love"))
    }

    @Test
    fun searchHitsCarryTheirKJVKey() {
        assertTrue(!StoreChapters.searchesBySubstring(store("LSG")))
        val hit = search("LSG").search("cœur pur", limit = 50).first { it.ref == VerseRef(BookID.PSALMS.number, 51, 12) }
        assertEquals(VerseRef(BookID.PSALMS.number, 51, 10), hit.kjv)
    }

    // The reader's conversion points (ReaderViewModel, Selection) on the Segond.

    @Test
    fun aHighlightMadeInSegondShowsOnTheEnglishVerse() {
        val lsg = numbering("LSG")
        val psalm51 = ChapterRef(BookID.PSALMS.number, 51)
        // The reader selects French Psalm 51:12 — the verse drawn as "12" — and highlights it.
        val selected = key(BookID.PSALMS, 51, 12)
        val stored = lsg.kjvKeyList(selected)
        assertEquals(listOf(key(BookID.PSALMS, 51, 10)), stored)
        val highlights = stored.map { Highlight(it, "yellow", Instant.EPOCH) }
        // In the BSB it colors 51:10…
        assertEquals(mapOf(key(BookID.PSALMS, 51, 10) to "yellow"), Selection.highlightColors(highlights, psalm51))
        // …and back in the Segond, 51:12 again.
        val count = verseCount("LSG", psalm51)
        assertEquals(mapOf(key(BookID.PSALMS, 51, 12) to "yellow"), Selection.highlightColors(highlights, psalm51, lsg, count))
    }

    @Test
    fun aMarkInTheNextEnglishChapterIsFoundInTheFrenchOne() {
        // English Exodus 8:2 is French 7:27: highlighted in English, it colors the French chapter 7.
        val lsg = numbering("LSG")
        val exodus7 = ChapterRef(BookID.EXODUS.number, 7)
        val highlights = listOf(Highlight(key(BookID.EXODUS, 8, 2), "blue", Instant.EPOCH))
        val colors = Selection.highlightColors(highlights, exodus7, lsg, verseCount("LSG", exodus7))
        assertEquals(mapOf(key(BookID.EXODUS, 7, 27) to "blue"), colors)
    }

    @Test
    fun aSelectionIsStoredAsKJVRangesAndShownInItsOwnNumbers() {
        val lsg = numbering("LSG")
        val native = VerseRange.of(VerseRef(BookID.PSALMS.number, 51, 12), VerseRef(BookID.PSALMS.number, 51, 14))
        val stored = lsg.kjvRange(native)
        assertEquals(VerseRange.of(VerseRef(BookID.PSALMS.number, 51, 10), VerseRef(BookID.PSALMS.number, 51, 12)), stored)
        assertEquals(native, lsg.nativeRange(stored))
    }

    @Test
    fun identityChangesNothing() {
        val range = VerseRange.of(VerseRef(43, 3, 16), VerseRef(43, 3, 18))
        assertEquals(range, VerseNumbering.IDENTITY.kjvRange(range))
        assertEquals(range, VerseNumbering.IDENTITY.nativeRange(range))
        assertEquals(VerseRef.chapterRange(43, 3), VerseNumbering.IDENTITY.kjvKeyRange(43, 3, 36))
    }

    private fun verseCount(id: String, ref: ChapterRef): Int {
        val range = VerseRef.chapterRange(ref.book, ref.chapter)
        return StoreChapters.verses(store(id), range.first, range.last).maxOf { it.ref.verse }
    }

    companion object {
        private val stores = mutableMapOf<String, JdbcSqlSource>()

        fun store(id: String): JdbcSqlSource = stores.getOrPut(id) { JdbcSqlSource(resource("Bibles/$id.sqlite")) }

        @AfterClass
        @JvmStatic
        fun close() {
            stores.values.forEach { it.close() }
            stores.clear()
        }
    }
}
