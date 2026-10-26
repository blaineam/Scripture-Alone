package com.blainemiller.scripturealone.data.listen

import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Android Auto tree — ported from `CarPlayCatalogTests` (iOS): what the car shows, and what a row does. */
class CarCatalogTest {
    private val labels = CarLabels("Listen", "Today’s Reading", "Continue", "Start a plan in Scripture Alone", "Books", "Old Testament", "New Testament", "Favorites")
    private fun tree(limit: Int = 12, favorites: List<VerseRange> = emptyList(), today: String? = null) =
        CarCatalog.tree(labels, today, ChapterRef(43, 3), favorites, limit = limit)

    private fun walk(e: CarEntry, visit: (CarEntry) -> Unit) { visit(e); e.children?.forEach { walk(it, visit) } }

    @Test fun everyListFitsTheLimitWhateverItIs() {
        for (limit in listOf(3, 5, 12, 50, 100)) walk(tree(limit)) { assertTrue("limit $limit", (it.children?.size ?: 0) <= limit) }
    }

    @Test fun everyChapterIsReachableExactlyOnceAndIdsAreUnique() {
        val all = mutableListOf<CarEntry>()
        walk(tree(12)) { all += it }
        assertEquals("ids are unique", all.size, all.map { it.id }.toSet().size)
        val chapters = all.mapNotNull { (CarAction.parse(it.id) as? CarAction.Chapter)?.ref }
        assertEquals(1189, chapters.size)
        assertEquals(1189, chapters.toSet().size)
        assertEquals(all.size, CarCatalog.index(tree(12)).size)
    }

    @Test fun psalmsFoldIntoRunsThatFit() {
        val psalms = CarCatalog.book(BookID.PSALMS, 12)
        assertEquals(listOf("1 – 144", "145 – 150"), psalms.children!!.map { it.title })
        val runs = psalms.children!![0].children!!
        assertEquals(12, runs.size)
        assertEquals("1 – 12", runs.first().title)
        assertEquals("133 – 144", runs.last().title)
        assertEquals(21, CarCatalog.book(BookID.JOHN, 30).children!!.size)
        // A single-chapter book plays at once.
        assertEquals(CarAction.Chapter(ChapterRef(65, 1)), CarAction.parse(CarCatalog.book(BookID.JUDE).id))
    }

    @Test fun listenOffersTodayAndWhereYouLeftOffAndTestamentsSplitAtMatthew() {
        val root = tree(100)
        assertEquals(listOf("Listen", "Books", "Favorites"), root.children!!.map { it.title })
        val listen = root.children!![0].children!!
        assertEquals(CarAction.Today, CarAction.parse(listen[0].id))
        assertEquals("Start a plan in Scripture Alone", listen[0].subtitle)
        assertEquals("John 3", listen[1].subtitle)
        assertEquals("Genesis 1–3", tree(today = "Genesis 1–3").children!![0].children!![0].subtitle)
        val books = root.children!![1].children!!
        assertEquals(39, books[0].children!!.size)
        assertEquals(27, books[1].children!!.size)
        assertEquals(BookID.REVELATION.displayName, books[1].children!!.last().title)
    }

    @Test fun favoritesCarryTheirRangesAndIdsParseBack() {
        val a = VerseRange(VerseRef(43, 3, 16), VerseRef(43, 3, 16))
        val b = VerseRange(VerseRef(19, 23, 1), VerseRef(19, 23, 3))
        val rows = tree(favorites = listOf(a, b)).children!![2].children!!
        assertEquals(listOf(CarAction.Range(a), CarAction.Range(b)), rows.map { CarAction.parse(it.id) })
        assertEquals(listOf("John 3:16", "Psalms 23:1–3"), rows.map { it.title })
        assertTrue(tree().children!![2].children!!.isEmpty())
        // Folders, unknown and out-of-range ids are not actions.
        for (id in listOf("books", "ot", "play:chapter:99:1", "play:chapter:43:99", "play:nope", "play:range:x", "")) assertNull(id, CarAction.parse(id))
        assertEquals(CarAction.Continue, CarAction.parse(CarAction.CONTINUE))
        assertEquals(CarAction.Chapter(ChapterRef(43, 3)), CarAction.parse(CarAction.chapterId(ChapterRef(43, 3))))
    }
}
