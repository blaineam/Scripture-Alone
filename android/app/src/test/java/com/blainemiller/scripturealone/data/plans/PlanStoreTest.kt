package com.blainemiller.scripturealone.data.plans

import com.blainemiller.scripturealone.data.canon.BookID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.LocalDate

/** The Kotlin side of `ReadingPlansTests.swift` (the app tests): store, importer, study cache. */
class PlanStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val day = LocalDate.parse("2026-10-23")
    private fun store(dir: java.io.File = tmp.newFolder(), today: LocalDate = day) = PlanStore(dir) { today }

    @Test fun startingAPlanGivesTodaysReading() {
        val s = store()
        assertNull(s.todaysReading())
        s.start(PlanCatalog.book(BookID.ROMANS))
        val r = s.todaysReading()!!
        assertEquals("Romans 1", r.display)
        assertTrue(r.dayNumber == 1 && r.totalDays == 16 && !r.isDone && !r.isCatchUp)
        s.setDone(true, 0, r.planId)
        assertTrue(s.todaysReading()!!.isDone)
    }

    @Test fun progressSurvivesARelaunch() {
        val dir = tmp.newFolder()
        val first = store(dir)
        first.start(PlanCatalog.canonical)
        first.setDone(true, 0, PlanCatalog.canonical.id)
        first.setReminder(PlanCatalog.canonical.id, 7, 30)
        val second = store(dir)
        assertEquals(PlanCatalog.canonical.id, second.primaryId)
        val p = second.progress(PlanCatalog.canonical.id)!!
        assertEquals(setOf(0), p.completed)
        assertEquals(7 to 30, p.reminderHour to p.reminderMinute)
    }

    @Test fun importedPlansRoundTripThroughJson() {
        val dir = tmp.newFolder()
        val source = PlanSource("https://s.org/bible", "https://s.org/bible/wp-json/wp/v2", 7, "S", 2)
        val plan = PlanFeed.plan(PlanFeed.entriesFromRest(POSTS), "S", source).plan
        val first = store(dir)
        first.add(plan)
        first.start(plan, followsCalendar = true)
        assertEquals(plan, store(dir).imported.single())
        assertTrue(store(dir).progress(plan.id)!!.followsCalendar)
    }

    @Test fun aPlanStartedDaysAgoOffersCatchUpAndStoppingForgetsIt() {
        val s = store()
        s.start(PlanCatalog.book(BookID.PSALMS), day.minusDays(3))
        assertEquals(3, s.schedule(PlanCatalog.book(BookID.PSALMS).id)!!.missedDays(day).size)
        assertEquals(4, s.todaysReading()!!.dayNumber)
        s.start(PlanCatalog.book(BookID.JUDE))
        assertEquals(PlanCatalog.book(BookID.JUDE).id, s.primaryId)
        s.stop(PlanCatalog.book(BookID.JUDE).id)
        assertEquals(PlanCatalog.book(BookID.PSALMS).id, s.primaryId)
    }

    // ---- importer

    private class Table(private val responses: Map<String, PlanFetcher.Response>, private val offline: Boolean = false) : PlanFetcher {
        val requested = mutableListOf<String>()
        override fun fetch(url: String): PlanFetcher.Response {
            requested += url
            if (offline) throw IOException("offline")
            return responses.entries.firstOrNull { url.startsWith(it.key) }?.value ?: PlanFetcher.Response(404, "")
        }
    }

    private val today = LocalDate.parse("2026-10-23")

    @Test fun importsTheSchedulePagedAndFollowsTheCalendar() {
        val table = Table(mapOf("https://s.org/bible/wp-json/wp/v2/posts" to PlanFetcher.Response(200, POSTS, mapOf("x-wp-totalpages" to "1"))))
        val result = PlanImporter(table).import("https://s.org/bible", null, PlanImporter.Mode.FollowCalendar, today)
        assertEquals(3, result.plan.days.size)
        assertTrue(table.requested.single().contains("after=2026-09-23T00%3A00%3A00"))
        val s = store()
        s.add(result.plan)
        s.start(result.plan, LocalDate.parse("2026-10-19"), true)
        assertEquals(PlanToday.Rest::class, s.schedule(result.plan.id)!!.today(LocalDate.parse("2026-10-21"))::class)
    }

    @Test fun offlineAndServerFailuresAreDistinct() {
        try {
            PlanImporter(Table(emptyMap(), offline = true)).import("s.org", null, PlanImporter.Mode.FollowCalendar, today)
            fail()
        } catch (e: PlanFeedException) { assertEquals(PlanFeedException.Kind.OFFLINE, e.kind) }
        try {
            PlanImporter(Table(emptyMap())).import("s.org", null, PlanImporter.Mode.FollowCalendar, today)
            fail()
        } catch (e: PlanFeedException) { assertEquals(PlanFeedException.Kind.NOT_A_FEED, e.kind) }
        try {
            PlanImporter(Table(emptyMap())).import("  ", null, PlanImporter.Mode.FollowCalendar, today)
            fail()
        } catch (e: PlanFeedException) { assertEquals(PlanFeedException.Kind.BAD_URL, e.kind) }
    }

    @Test fun aPastedSiteFallsBackToRss() {
        val rss = """<?xml version="1.0"?><rss version="2.0"><channel><title>X</title>
            <item><title>Epistle- 1 Corinthians 5</title><link>https://s.org/a/</link><pubDate>Fri, 23 Oct 2026 07:00:00 +0000</pubDate></item>
            <item><title><![CDATA[Epistle- 1 Corinthians 4]]></title><link>https://s.org/b/</link><pubDate>Thu, 22 Oct 2026 07:00:00 +0000</pubDate></item>
            </channel></rss>""".trimIndent()
        val (endpoint, entries) = PlanImporter(Table(mapOf("https://s.org/feed/" to PlanFetcher.Response(200, rss)))).entries("https://s.org")
        assertEquals(PlanFeed.Endpoint.Rss("https://s.org/feed/"), endpoint)
        assertEquals(listOf("2026-10-22", "2026-10-23"), entries.map { it.date })
        assertEquals(listOf("Epistle- 1 Corinthians 4", "Epistle- 1 Corinthians 5"), entries.map { it.title })
    }

    @Test fun refreshAddsNewDaysAndIgnoresAFailure() {
        val source = PlanSource("https://s.org/bible", "https://s.org/bible/wp-json/wp/v2", null, "S", 2)
        val plan = PlanFeed.plan(PlanFeed.entriesFromRest(POSTS), "S", source).plan
        val s = store(today = LocalDate.parse("2026-10-23"))
        s.add(plan)
        s.start(plan, LocalDate.parse("2026-10-19"), true)
        PlanImporter(Table(emptyMap(), offline = true)).refresh(s, LocalDate.parse("2026-10-23"))
        assertEquals(3, s.plan(plan.id)!!.days.size)
        val more = POSTS.dropLast(1) + """,{"id":6,"date":"2026-10-23T00:00:00","link":"https://s.org/e/","title":{"rendered":"Epistle- 1 Corinthians 4"}}]"""
        PlanImporter(Table(mapOf("https://s.org/bible/wp-json" to PlanFetcher.Response(200, more, mapOf("x-wp-totalpages" to "1"))))).refresh(s, LocalDate.parse("2026-10-23"))
        assertEquals(4, s.plan(plan.id)!!.days.size)
        assertEquals(1, s.progress.size)
    }

    @Test fun fetchesTheLatestStudyAsPlainTextAndCachesIt() {
        val body = """[{"id":9,"date":"2026-10-17T00:01:00","link":"https://s.org/bible/study/","title":{"rendered":"Saturday Study &#8211; Daniel 8-12"},"content":{"rendered":"<p>Daniel 8-12</p>\n<p>It&#8217;s <em>here</em>.</p>"}}]"""
        val source = PlanSource("https://s.org/bible", "https://s.org/bible/wp-json/wp/v2", 7, "S")
        val study = PlanImporter(Table(mapOf("https://s.org/bible/wp-json/wp/v2/posts" to PlanFetcher.Response(200, body)))).latestStudy(source, today)!!
        assertEquals("Saturday Study – Daniel 8-12", study.title)
        assertEquals("Daniel 8-12\n\nIt’s here.", study.text)
        assertEquals("https://s.org/bible/study/", study.link)
        assertNull(PlanImporter(Table(emptyMap())).latestStudy(source.copy(studyCategory = null), today))
        val cache = StudyCache(tmp.newFolder())
        assertNull(cache.load(source))
        cache.save(study, source)
        assertEquals(study, cache.load(source))
        cache.discard(source)
        assertNull(cache.load(source))
        assertFalse(cache.load(source) != null)
        assertNotNull(PlanImporter.DISCIPLES_CHURCH)
    }

    @Test fun remindersCoverTheComingDaysAndSkipReadOnes() {
        val s = store()
        val plan = PlanCatalog.book(BookID.PSALMS)
        s.start(plan, day)
        s.setReminder(plan.id, 8, 0)
        val morning = java.time.LocalDateTime.of(day, java.time.LocalTime.of(6, 0))
        val all = PlanReminderRules.upcoming(s, morning)
        assertEquals(14, all.size)
        assertEquals("Psalms 1", all.first().display)
        s.setDone(true, 0, plan.id)
        assertEquals(13, PlanReminderRules.upcoming(s, morning).size)
        // Past today's time, today's is gone from "upcoming" but still owed by a late alarm.
        val noon = java.time.LocalDateTime.of(day, java.time.LocalTime.of(12, 0))
        s.setDone(false, 0, plan.id)
        assertEquals(13, PlanReminderRules.upcoming(s, noon).size)
        assertEquals(listOf("Psalms 1"), PlanReminderRules.due(s, noon).map { it.display })
        assertTrue(PlanReminderRules.due(s, java.time.LocalDateTime.of(day, java.time.LocalTime.of(20, 0))).isEmpty())
        s.setDone(true, 0, plan.id)
        assertTrue(PlanReminderRules.due(s, noon).isEmpty())
    }

    private companion object {
        val POSTS = """[{"id":1,"date":"2026-10-19T00:00:00","link":"https://s.org/a/","title":{"rendered":"Epistle- 1 Corinthians 1"}},
         {"id":2,"date":"2026-10-20T00:00:00","link":"https://s.org/b/","title":{"rendered":"Epistle- 1 Corinthians 2"}},
         {"id":4,"date":"2026-10-21T00:00:00","link":"https://s.org/c/","title":{"rendered":"About The Plan"}},
         {"id":5,"date":"2026-10-22T00:00:00","link":"https://s.org/d/","title":{"rendered":"Epistle- 1 Corinthians 3"}}]"""
    }
}
