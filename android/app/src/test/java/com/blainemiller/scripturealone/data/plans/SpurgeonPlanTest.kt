package com.blainemiller.scripturealone.data.plans

import com.blainemiller.scripturealone.data.ChapterVerse
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.listen.ListenQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

/** Spurgeon's *Morning and Evening*: the file both apps ship, as `SpurgeonPlanTests` in the core reads it. */
class SpurgeonPlanTest {
    @get:Rule val tmp = TemporaryFolder()

    private val plan: ReadingPlan = PlanJson.decodePlan(File("../../ScriptureAlone/Resources/Plans/SpurgeonMorningEvening.json").readText())
    private fun d(iso: String) = LocalDate.parse(iso)
    private fun schedule(start: String) = PlanSchedule(plan, PlanProgress(plan.id, start))

    @Test fun hasAMorningAndAnEveningForEveryDayOfALeapYear() {
        assertTrue(plan.annual && plan.kind == PlanKind.DEVOTIONAL && !plan.isDated)
        assertEquals(366, plan.days.size)
        assertEquals(366, plan.days.mapNotNull { it.date }.toSet().size)
        assertEquals("02-29", plan.days[59].date)
        for (day in plan.days) {
            assertEquals(listOf("Morning", "Evening"), day.devotions.map { it.heading })
            assertEquals(2, day.passages.size)
            assertTrue(day.devotions.all { it.text.length > 400 && !it.quote.isNullOrEmpty() })
            assertTrue(day.passages.all { it.startChapter <= it.book.chapterCount })
        }
        assertEquals("Joshua 5:12", plan.days[0].passages.first().display)
    }

    @Test fun todaysReadingIsTheOneForTodaysDate() {
        val s = schedule("2026-10-24")
        assertEquals("10-24", (s.today(d("2026-10-24")) as PlanToday.Reading).day.date)
        assertNull(s.dayIndex(d("2026-03-01")))
        assertTrue(s.missedDays(d("2026-12-01")).isEmpty())
        val early = schedule("2026-01-01")
        assertEquals(58, early.dayIndex(d("2026-02-28")))
        assertEquals(60, early.dayIndex(d("2026-03-01")))
        assertNull(early.dateForDay(59))
        assertEquals(59, schedule("2028-01-01").dayIndex(d("2028-02-29")))
    }

    @Test fun startsOverEachJanuary() {
        val progress = PlanProgress(plan.id, "2026-10-24", completed = setOf(297, 298))
        assertNull(progress.rolledOver(plan, d("2026-12-31")))
        val next = progress.rolledOver(plan, d("2027-01-01"))!!
        assertEquals("2027-01-01", next.startDate)
        assertTrue(next.completed.isEmpty())
        assertNull(progress.rolledOver(PlanCatalog.canonical, d("2027-01-01")))
    }

    @Test fun roundTripsThroughJsonAndIsOfferedOnlyInEnglishUntilFollowed() {
        assertEquals(plan, PlanJson.decodePlan(PlanJson.plan(plan).toString()))
        val store = PlanStore(tmp.newFolder(), listOf(plan)) { d("2026-10-24") }
        assertEquals(plan, store.plan(plan.id))
        assertEquals(listOf(plan), store.offeredBundled("en"))
        assertTrue(store.offeredBundled("de").isEmpty())
        store.start(plan)
        assertTrue(store.offeredBundled("en").isEmpty())
        val reading = store.todaysReading()!!
        assertEquals(2, reading.devotions.size)
        assertFalse(reading.isCatchUp)
        // A new year forgets the old one's reading.
        store.setDone(true, reading.day.index, plan.id)
        store.rollOverAnnualPlans(d("2027-01-02"))
        assertTrue(store.progress(plan.id)!!.completed.isEmpty())
        assertEquals("2027-01-01", store.progress(plan.id)!!.startDate)
    }

    @Test fun aDevotionIsReadAsHeadingVerseAndMeditation() {
        fun verses(book: Int, chapter: Int) = (1..40).map { ChapterVerse(VerseRef(book, chapter, it), "v$it", emptyList()) }
        val day = plan.days[0]
        val items = ListenQueue.devotionItems(day.passages, day.devotions) { ref -> verses(ref.book, ref.chapter) }
        assertEquals("Morning.", items.first().text)
        assertEquals(6 * 1_000_000 + 5 * 1_000 + 12, items[1].key)
        assertTrue(items.count { it.text == "Evening." } == 1)
        assertTrue("headings and paragraphs are unmarked prose", items.filter { it.isAnnouncement }.size > 4)
        // Without matching devotions it is just the passages.
        assertEquals(ListenQueue.readingItems(day.passages) { ref -> verses(ref.book, ref.chapter) },
            ListenQueue.devotionItems(day.passages, emptyList()) { ref -> verses(ref.book, ref.chapter) })
    }
}
