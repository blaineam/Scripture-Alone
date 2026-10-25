package com.blainemiller.scripturealone.data.plans

import com.blainemiller.scripturealone.data.canon.BookID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

/** Ported case for case from `ScriptureAloneCoreTests/ReadingPlanTests.swift`. Add a case to both or neither. */
class ReadingPlanTest {
    private fun d(iso: String) = LocalDate.parse(iso)

    @Test fun builtInPlansCoverEveryChapterExactlyOnce() {
        for (plan in listOf(PlanCatalog.canonical, PlanCatalog.chronological)) {
            val chapters = plan.days.flatMap { it.passages.flatMap { p -> p.chapters } }
            assertEquals(365, plan.days.size)
            assertEquals(1189, chapters.size)
            assertEquals(1189, chapters.toSet().size)
            assertTrue(plan.days.all { it.passages.isNotEmpty() })
        }
    }

    @Test fun canonicalRunsInOrderAndChronologicalPutsJobEarly() {
        assertEquals("Genesis 1–3", PlanCatalog.canonical.days[0].passages.first().display)
        assertEquals(BookID.REVELATION, PlanCatalog.canonical.days.last().passages.last().book)
        val order = PlanCatalog.chronological.days.flatMap { it.passages.map { p -> p.book } }
        assertTrue(order.indexOf(BookID.JOB) < order.indexOf(BookID.EXODUS))
        assertTrue(order.indexOf(BookID.DANIEL) < order.indexOf(BookID.MATTHEW))
    }

    @Test fun bookPlanIsOneChapterADay() {
        val romans = PlanCatalog.plan(PlanCatalog.book(BookID.ROMANS).id)!!
        assertEquals(16, romans.days.size)
        assertEquals("Romans 8", romans.days[7].display)
        assertEquals(1, PlanCatalog.book(BookID.JUDE).days.size)
        assertNull(PlanCatalog.plan("nope"))
    }

    private fun schedule(plan: ReadingPlan = PlanCatalog.book(BookID.ROMANS), start: String = "2026-10-05",
                         done: Set<Int> = emptySet(), follows: Boolean = false) =
        PlanSchedule(plan, PlanProgress(plan.id, start, follows, done))

    @Test fun ordinalDaysFollowTheCalendar() {
        val s = schedule()
        assertEquals(PlanToday.Reading(PlanCatalog.book(BookID.ROMANS).days[0]), s.today(d("2026-10-05")))
        assertEquals(3, s.dayIndex(d("2026-10-08")))
        assertNull(s.dayIndex(d("2026-10-04")))
        assertEquals(PlanToday.NotStarted("2026-10-05"), s.today(d("2026-10-01")))
        assertEquals(PlanToday.Finished, s.today(d("2027-01-01")))
    }

    @Test fun skippedWeekdaysAreRestDays() {
        val plan = ReadingPlan("w", PlanKind.IMPORTED, "W", "",
            (0 until 10).map { PlanDay(it, null, null, listOf(PlanPassage.chapters(BookID.PSALMS, (it + 1)..(it + 1)))) },
            skipWeekdays = setOf(1, 7))
        val s = schedule(plan)
        assertEquals(4, s.dayIndex(d("2026-10-09")))
        assertNull(s.dayIndex(d("2026-10-10")))
        assertEquals(5, s.dayIndex(d("2026-10-12")))
        assertEquals(5, (s.today(d("2026-10-10")) as PlanToday.Rest).next?.index)
        assertEquals(d("2026-10-12"), s.dateForDay(5))
    }

    @Test fun missedDaysCatchUpAndStreak() {
        assertEquals(listOf(1, 3), schedule(done = setOf(0, 2)).missedDays(d("2026-10-09")).map { it.index })
        assertEquals(4, schedule(done = setOf(0, 2)).reading(d("2026-10-09"))?.index)
        assertEquals(3, schedule(done = setOf(0, 1, 2)).streak(d("2026-10-08")))
        assertEquals(4, schedule(done = setOf(0, 1, 2, 3)).streak(d("2026-10-08")))
        assertEquals(1, schedule(done = setOf(0, 1, 3)).streak(d("2026-10-08")))
        assertEquals(0, schedule(done = setOf(0, 1)).streak(d("2026-10-09")))
        assertEquals(0.25, schedule(done = setOf(0, 1, 2, 3)).fraction, 0.0001)
    }

    @Test fun calendarModeUsesSourceDates() {
        val dated = ReadingPlan("c", PlanKind.IMPORTED, "C", "", listOf(
            PlanDay(0, "2026-10-19", null, listOf(PlanPassage.chapters(BookID.FIRST_CORINTHIANS, 1..1))),
            PlanDay(1, "2026-10-20", null, listOf(PlanPassage.chapters(BookID.FIRST_CORINTHIANS, 2..2))),
            PlanDay(2, "2026-10-22", null, listOf(PlanPassage.chapters(BookID.FIRST_CORINTHIANS, 3..3))),
        ))
        val s = schedule(dated, "2026-10-19", follows = true)
        assertEquals(2, s.dayIndex(d("2026-10-22")))
        assertNull(s.dayIndex(d("2026-10-21")))
        assertEquals(listOf(0, 1), s.missedDays(d("2026-10-22")).map { it.index })
        assertEquals(2, (s.today(d("2026-10-21")) as PlanToday.Rest).next?.index)
    }

    // ---- importer logic

    @Test fun parsesTitles() {
        val cases = listOf(
            Triple("Epistle- 1 Corinthians 5", "Epistle", "1 Corinthians 5"),
            Triple("Exile Era- Daniel 12", "Exile Era", "Daniel 12"),
            Triple("Psalms 23-24", null, "Psalms 23–24"),
            Triple("Day 4: John 3:1-21", "Day 4", "John 3:1–21"),
            Triple("Gospel &#8211; Luke 2", "Gospel", "Luke 2"),
            Triple("Wisdom- Psalms 1; Proverbs 1", "Wisdom", "Psalms 1"),
        )
        for ((title, label, first) in cases) {
            val parsed = PlanFeed.parseTitle(title)
            assertNotNull(title, parsed)
            assertEquals(title, label, parsed!!.first)
            assertEquals(title, first, parsed.second.first().display)
        }
        for (title in listOf("About The Plan", "Olive Drive Church", "Download Plan", "")) assertNull(title, PlanFeed.parseTitle(title))
    }

    @Test fun pastedAddresses() {
        assertEquals(listOf(PlanFeed.Endpoint.WordPress("https://discipleschurch.com/bible", null),
            PlanFeed.Endpoint.Rss("https://discipleschurch.com/bible/feed/")), PlanFeed.endpoints("discipleschurch.com/bible"))
        assertEquals(listOf(PlanFeed.Endpoint.WordPress("https://x.org/bible", 2)),
            PlanFeed.endpoints("https://x.org/bible/wp-json/wp/v2/posts?categories=2&per_page=5"))
        assertEquals(listOf(PlanFeed.Endpoint.Rss("https://x.org/bible/feed/")), PlanFeed.endpoints("https://x.org/bible/feed/"))
        assertTrue(PlanFeed.endpoints("  ").isEmpty())
        assertTrue(PlanFeed.endpoints("not a url").isEmpty())
        assertTrue(PlanFeed.endpoints("ftp://x.org/a").isEmpty())
    }

    private val posts = """
        [{"id":1,"date":"2026-10-19T00:00:00","link":"https://s.org/a/","title":{"rendered":"Epistle- 1 Corinthians 1"}},
         {"id":2,"date":"2026-10-20T00:00:00","link":"https://s.org/b/","title":{"rendered":"Epistle- 1 Corinthians 2"}},
         {"id":3,"date":"2026-10-20T00:00:30","link":"https://s.org/b2/","title":{"rendered":"Epistle- 1 Corinthians 2"}},
         {"id":4,"date":"2026-10-21T00:00:00","link":"https://s.org/c/","title":{"rendered":"About The Plan"}},
         {"id":5,"date":"2026-10-22T00:00:00","link":"https://s.org/d/","title":{"rendered":"Epistle- 1 Corinthians 3"}}]
    """.trimIndent()

    @Test fun importsScheduleFromRestJson() {
        val source = PlanSource("https://s.org/bible", null, null, "S")
        val result = PlanFeed.plan(PlanFeed.entriesFromRest(posts), "S Plan", source)
        assertEquals(listOf("1 Corinthians 1", "1 Corinthians 2", "1 Corinthians 3"), result.plan.days.map { it.display })
        assertEquals(listOf("2026-10-19", "2026-10-20", "2026-10-22"), result.plan.days.map { it.date })
        assertEquals("Epistle", result.plan.days.first().label)
        assertEquals(listOf("About The Plan"), result.skipped)
        assertTrue(result.plan.isDated)
        assertEquals(result.plan.id, PlanFeed.plan(PlanFeed.entriesFromRest(posts), "S Plan", source).plan.id)
    }

    @Test fun derivesRestWeekdaysAndRejectsNoReadings() {
        val entries = (0 until 28).map { d("2026-10-05").plusDays(it.toLong()) }
            .filter { it.dayOfWeek.value <= 5 }.mapIndexed { i, date -> FeedEntry("Psalms ${i % 100 + 1}", date.toString(), null) }
        val source = PlanSource("https://s.org", null, null, "S")
        assertEquals(setOf(1, 7), PlanFeed.plan(entries, "P", source).plan.skipWeekdays)
        try {
            PlanFeed.plan(listOf(FeedEntry("Hello", "2026-01-01", null)), "t", source)
            fail("expected NO_READINGS")
        } catch (e: PlanFeedException) {
            assertEquals(PlanFeedException.Kind.NO_READINGS, e.kind)
        }
    }

    /** Weekday posts "Epistle- <book> n", from [start], one chapter each. */
    private fun chapterPosts(book: String, chapters: IntRange, start: String): List<FeedEntry> {
        var date = d(start)
        return chapters.map { chapter ->
            while (date.dayOfWeek.value > 5) date = date.plusDays(1)
            FeedEntry("Epistle- $book $chapter", date.toString(), null).also { date = date.plusDays(1) }
        }
    }

    @Test fun projectsDaysNotPostedYetFromTheLastPass() {
        val source = PlanSource("https://s.org", null, null, "S")
        val entries = chapterPosts("Romans", 1..12, "2022-10-03") + chapterPosts("Romans", 1..4, "2026-10-19")
        val plan = PlanFeed.plan(entries, "S", source, keepingFrom = "2026-10-01", projecting = true).plan
        assertEquals((1..12).map { "Romans $it" }, plan.days.map { it.display })
        assertEquals(List(4) { false } + List(8) { true }, plan.days.map { it.projected })
        // Romans 5 is that Friday, Romans 6 the Monday, and nothing falls on a weekend.
        assertEquals("2026-10-23", plan.days[4].date)
        assertEquals("2026-10-26", plan.days[5].date)
        assertEquals((0 until 12).toList(), plan.days.map { it.index })
        assertTrue(plan.days.all { it.date!! >= "2026-10-01" && d(it.date!!).dayOfWeek.value <= 5 })
        // No earlier pass, no guess; and nothing is projected unless asked for.
        assertEquals(4, PlanFeed.plan(chapterPosts("Romans", 1..4, "2026-10-19"), "S", source, projecting = true).plan.days.size)
        assertTrue(PlanFeed.plan(entries, "S", source).plan.days.none { it.projected })
    }

    @Test fun projectionSkipsAWeeklyStudyAndCarriesOnAsThePlanGrows() {
        val source = PlanSource("https://s.org", null, null, "S")
        val entries = chapterPosts("Romans", 1..12, "2022-10-03") + chapterPosts("Romans", 1..5, "2026-10-19") +
            FeedEntry("Saturday Study – Romans 1-5", "2026-10-24", null)
        val plan = PlanFeed.plan(entries, "S", source, keepingFrom = "2026-10-01", projecting = true).plan
        assertEquals("Romans 1–5", plan.days[5].display)
        assertEquals((6..12).map { "Romans $it" }, plan.days.filter { it.projected }.map { it.display })
        assertEquals("2026-10-26", plan.days.first { it.projected }.date)
        val later = PlanFeed.plan(chapterPosts("Romans", 1..7, "2026-10-19"), "S", source).plan
        val carried = PlanFeed.projection(later.days, plan.days)
        assertEquals((8..12).map { "Romans $it" }, carried.map { it.display })
        assertEquals("2026-10-28", carried.first().date)
    }

    @Test fun projectionStopsWhereTheEarlierPassChangedYear() {
        val source = PlanSource("https://s.org", null, null, "S")
        val entries = chapterPosts("John", 1..8, "2022-12-27") + chapterPosts("John", 1..2, "2026-10-19")
        val plan = PlanFeed.plan(entries, "S", source, keepingFrom = "2026-10-01", projecting = true).plan
        assertEquals(listOf("John 1", "John 2", "John 3", "John 4"), plan.days.map { it.display })
    }

    @Test fun textHelpers() {
        assertEquals("a & b – c ’ &bogus; &", PlanFeed.decodeEntities("a &amp; b &#8211; c &#x2019; &bogus; &"))
        assertEquals("Daniel 8-12\n\nIt’s here.", PlanFeed.paragraphs("<p>Daniel 8-12</p>\n<p>It&#8217;s <em>here</em>.</p><script>x()</script>"))
        assertEquals(listOf(2, 7), PlanFeed.categories("""[{"id":7,"name":"Saturday Study","count":546},{"id":2,"name":"Scripture","count":3700}]""").map { it.first })
    }
}
