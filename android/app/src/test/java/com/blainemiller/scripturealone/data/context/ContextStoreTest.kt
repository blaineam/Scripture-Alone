package com.blainemiller.scripturealone.data.context

import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.blainemiller.scripturealone.data.study.JdbcSqlSource
import com.blainemiller.scripturealone.data.study.resource
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import kotlin.math.abs

/**
 * A port of `ContextStoreTests.swift`, run against the very `Study/Context.sqlite` the iOS app ships.
 * (`basemapDecodes` is in [BasemapTest]; its last line, about the Dead Sea label, is here.)
 */
class ContextStoreTest {

    companion object {
        private lateinit var source: JdbcSqlSource
        private lateinit var store: ContextStore

        @BeforeClass @JvmStatic fun open() {
            source = JdbcSqlSource(resource("Study/Context.sqlite"))
            store = ContextStore(source)
        }

        @AfterClass @JvmStatic fun close() = source.close()
    }

    private fun key(book: BookID, chapter: Int, verse: Int) = VerseRef(book.number, chapter, verse).key
    private fun chapter(book: BookID, chapter: Int) = ChapterRef(book.number, chapter)

    @Test fun acts13And14NameThePlacesOfPaulsFirstJourney() {
        val names = listOf(13, 14).flatMap { c -> store.places(chapter(BookID.ACTS, c)).map { it.place.name } }
        for (expected in listOf("Antioch", "Cyprus", "Iconium", "Lystra", "Derbe", "Seleucia", "Salamis", "Paphos")) {
            assertTrue("Acts 13–14 should mention $expected", expected in names)
        }
        val acts13 = store.places(chapter(BookID.ACTS, 13))
        assertEquals("places come in order of first mention", "Antioch", acts13.first().place.name)
        val iconium = requireNotNull(acts13.firstOrNull { it.place.name == "Iconium" })
        assertEquals(listOf(key(BookID.ACTS, 13, 51)), iconium.verses)
        assertEquals("Konya", iconium.place.modernName)
    }

    @Test fun jerusalemIsWhereItShouldBe() {
        val jerusalem = requireNotNull(store.searchPlaces("Jerusalem").firstOrNull())
        assertTrue(abs(jerusalem.latitude - 31.78) < 0.05 && abs(jerusalem.longitude - 35.23) < 0.05)
        assertEquals(Place.Confidence.IDENTIFIED, jerusalem.confidenceLevel)
        val verses = store.versesMentioning(jerusalem.id)
        assertEquals(jerusalem.mentions, verses.size)
        assertTrue(key(BookID.ACTS, 1, 8) in verses)
        assertEquals(verses.sorted(), verses)
        assertTrue(store.prominentPlaces(limit = 5).any { it.id == jerusalem.id })
        assertEquals(jerusalem, store.place(jerusalem.id))
    }

    @Test fun chaptersSitInTheirEras() {
        assertEquals("patriarchs", store.time(chapter(BookID.GENESIS, 12))?.era?.id)
        assertEquals("primeval", store.time(chapter(BookID.GENESIS, 1))?.era?.id)
        assertNull(store.time(chapter(BookID.GENESIS, 1))?.year)
        val kings = requireNotNull(store.time(chapter(BookID.FIRST_KINGS, 12)))
        assertEquals("divided", kings.era.id)
        assertEquals(-931, kings.year)
        assertEquals("c. 931 BC", kings.yearLabel)
        assertEquals(ChapterTime.Basis.WRITTEN, store.time(chapter(BookID.ROMANS, 8))?.basis)
        for (book in BookID.entries) {
            for (c in 1..book.chapterCount) {
                assertNotNull("${book.displayName} $c has no era", store.time(chapter(book, c)))
            }
        }
    }

    @Test fun erasRunInOrderWithoutGaps() {
        val eras = store.eras()
        assertEquals("primeval", eras.first().id)
        assertTrue(eras.any { it.id == "exile" })
        val dated = eras.filter { it.start != null }
        for ((a, b) in dated.zipWithNext()) {
            assertEquals("${a.name} should end where ${b.name} begins", b.start, a.end)
        }
    }

    @Test fun eventsFindTheirChapters() {
        val events = store.events(chapter(BookID.FIRST_KINGS, 12))
        assertTrue(events.any { it.name == "The kingdom divides" })
        val all = store.events()
        assertTrue(all.size > 60)
        assertEquals("primeval", all.first().eraId)
        assertEquals("church", all.last().eraId)
    }

    @Test fun chartsDecodeAndAreSuggestedByBook() {
        val forKings = store.charts(BookID.FIRST_KINGS.number)
        val kings = requireNotNull(forKings.firstOrNull { it.kind == ChartKind.KINGS }).kings()
        assertEquals("Jeroboam I", kings.israel.first().name)
        assertEquals("Zedekiah", kings.judah.last().name)
        assertEquals(KingsChart.Verdict.GOOD, kings.judah.first { it.name == "Josiah" }.verdict)

        val journeys = requireNotNull(store.charts(BookID.ACTS.number).firstOrNull { it.kind == ChartKind.JOURNEYS }).journeys()
        assertEquals(4, journeys.journeys.size)
        assertEquals("Antioch", journeys.journeys[0].stops.first().name)
        assertEquals("Rome", journeys.journeys[3].stops.last().name)
        assertEquals(VerseRef(BookID.ACTS.number, 13, 1), journeys.journeys[0].refs.start)

        val all = store.charts()
        assertEquals(13, all.first { it.kind == ChartKind.TRIBES }.tribes().tribes.size)
        assertEquals("Passover", all.first { it.kind == ChartKind.FEASTS }.feasts().feasts.first().name)
        assertTrue(store.charts(BookID.RUTH.number).isEmpty())
    }

    @Test fun labelsIncludeTheDeadSea() {
        assertTrue(store.labels().any { it.text == "Dead Sea" })
    }

    // Beyond the Swift suite: what the Study panel's Context tab leans on.

    @Test fun chartScopeDecodingIsForgiving() {
        assertEquals(listOf(9, 10), ContextStore.decodeScope("[9, 10]"))
        assertEquals(emptyList<Int>(), ContextStore.decodeScope("not json"))
        assertEquals(emptyList<Int>(), ContextStore.decodeScope("[0, 67]"))
    }

    @Test fun aBrokenChartBodyFailsLoudlyRatherThanHalfDecoding() {
        try {
            ChartDecoding.kings("""{"united":[{"name":"Saul"}],"israel":[],"judah":[]}""")
            throw AssertionError("expected a decoding failure")
        } catch (_: ChartDecodingException) {
        }
        try {
            ChartDecoding.feasts("""{"feasts":[{"name":"x","hebrew":"","date":"","season":"","refs":[99001001,1001001],"meaning":""}]}""")
            throw AssertionError("a reference to book 99 must not decode")
        } catch (_: ChartDecodingException) {
        }
    }
}
