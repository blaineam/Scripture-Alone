package com.blainemiller.scripturealone.data.listen

import com.blainemiller.scripturealone.data.Canon
import com.blainemiller.scripturealone.data.ChapterVerse
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.listen.ListenQueue.Item
import com.blainemiller.scripturealone.data.listen.ListenQueue.PassEnd
import com.blainemiller.scripturealone.data.listen.ListenQueue.Scope
import com.blainemiller.scripturealone.data.listen.ListenQueue.Skip
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenQueueTest {

    private fun verses(book: Int, chapter: Int, count: Int, withHeading: Boolean = false): List<ChapterVerse> =
        ((if (withHeading) 0 else 1)..count).map { v -> ChapterVerse(VerseRef(book, chapter, v), "v$v", emptyList()) }

    @Test
    fun announcementNamesTheBookAndChapterOrJustASingleChapterBook() {
        assertEquals("John, chapter 3.", ListenQueue.announcement(ChapterRef(43, 3)))
        assertEquals("Jude", ListenQueue.announcement(ChapterRef(65, 1)))
        assertEquals("Psalms, chapter 23.", ListenQueue.announcement(ChapterRef(19, 23)))
    }

    @Test
    fun aChapterFromItsFirstVerseIsAnnouncedAndNeverReadsVerseZero() {
        val items = ListenQueue.chapterItems(ChapterRef(19, 23), verses(19, 23, 6, withHeading = true), from = 1)
        assertEquals(Item(0, "Psalms, chapter 23."), items.first())
        assertEquals(listOf(19023001, 19023002, 19023003, 19023004, 19023005, 19023006), items.drop(1).map { it.key })
        assertTrue(items.none { it.text == "v0" })
    }

    @Test
    fun aChapterFromALaterVerseStartsThereWithoutAnAnnouncement() {
        val items = ListenQueue.chapterItems(ChapterRef(43, 3), verses(43, 3, 36), from = 16)
        assertEquals(43003016, items.first().key)
        assertEquals(21, items.size)
        assertFalse(items.any { it.isAnnouncement })
    }

    @Test
    fun theStartingVerseIsClampedToTheChapter() {
        assertEquals(43003036, ListenQueue.chapterItems(ChapterRef(43, 3), verses(43, 3, 36), from = 99).single().key)
        assertTrue(ListenQueue.chapterItems(ChapterRef(43, 3), verses(43, 3, 36), from = -4).first().isAnnouncement)
        assertTrue(ListenQueue.chapterItems(ChapterRef(43, 3), emptyList(), from = 1).isEmpty())
    }

    @Test
    fun aSelectionIsReadInOrderOnceWithNoAnnouncement() {
        val picked = listOf(43003017, 43003016, 43003016, 43003000).map { ChapterVerse(VerseRef.fromKey(it), "t", emptyList()) }
        assertEquals(listOf(43003016, 43003017), ListenQueue.selectionItems(picked).map { it.key })
    }

    @Test
    fun theAnnouncementMarksTheFirstVerse() {
        val items = listOf(Item(0, "John, chapter 3."), Item(43003001, "a"), Item(43003002, "b"))
        assertEquals(43003001, ListenQueue.markedVerse(items, 0))
        assertEquals(43003002, ListenQueue.markedVerse(items, 2))
        assertNull(ListenQueue.markedVerse(items, 3))
    }

    @Test
    fun skippingStepsOverTheAnnouncementAndFinishesPastTheEnd() {
        val items = listOf(Item(0, "John, chapter 3."), Item(43003001, "a"), Item(43003002, "b"))
        assertEquals(Skip.To(1), ListenQueue.skip(items, 0, 1))
        assertEquals(Skip.To(2), ListenQueue.skip(items, 1, 1))
        assertEquals(Skip.Finished, ListenQueue.skip(items, 2, 1))
        // Back from the first verse: the announcement is stepped over and there is nothing before it.
        assertEquals(Skip.To(1), ListenQueue.skip(items, 1, -1))
        assertEquals(Skip.To(1), ListenQueue.skip(items, 2, -1))
        assertNull(ListenQueue.skip(emptyList(), 0, 1))
    }

    @Test
    fun aPassRestartsAtItsFirstVerse() {
        assertEquals(1, ListenQueue.restartIndex(listOf(Item(0, "x"), Item(43003001, "a"))))
        assertEquals(0, ListenQueue.restartIndex(listOf(Item(43003016, "a"))))
    }

    @Test
    fun theTitleIsTheVerseBeingRead() {
        assertEquals("John 3:16", ListenQueue.title(43003016))
        assertEquals("Jude 1:3", ListenQueue.title(65001003))
        assertNull(ListenQueue.title(0))
        assertNull(ListenQueue.title(null))
    }

    @Test
    fun aChapterPassContinuesIntoTheNextChapterAcrossBooks() {
        assertEquals(PassEnd.Continue(ChapterRef(43, 4)), ListenQueue.passEnd(Scope.Chapter(ChapterRef(43, 3)), true, SleepTimer.OFF, Canon::next))
        assertEquals(PassEnd.Continue(ChapterRef(44, 1)), ListenQueue.passEnd(Scope.Chapter(ChapterRef(43, 21)), true, SleepTimer.MINUTES_30, Canon::next))
    }

    @Test
    fun aPassStopsForASelectionTheToggleTheLastChapterAndEndOfChapter() {
        assertEquals(PassEnd.Stop(false), ListenQueue.passEnd(Scope.Selection, true, SleepTimer.OFF, Canon::next))
        assertEquals(PassEnd.Stop(false), ListenQueue.passEnd(Scope.Chapter(ChapterRef(43, 3)), false, SleepTimer.OFF, Canon::next))
        assertEquals(PassEnd.Stop(false), ListenQueue.passEnd(Scope.Chapter(ChapterRef(66, 22)), true, SleepTimer.OFF, Canon::next))
        // "End of Chapter" stops the pass and turns itself off.
        assertEquals(PassEnd.Stop(true), ListenQueue.passEnd(Scope.Chapter(ChapterRef(43, 3)), true, SleepTimer.END_OF_CHAPTER, Canon::next))
    }

    @Test
    fun sleepTimerOptionsAreIosOnesAndTimedOnesExpireOnTheSleepingClock() {
        assertEquals(listOf("Off", "15 Minutes", "30 Minutes", "1 Hour", "End of Chapter"), SleepTimer.entries.map { it.title })
        assertNull(SleepTimer.OFF.deadline(1_000))
        assertNull(SleepTimer.END_OF_CHAPTER.deadline(1_000))
        val deadline = SleepTimer.MINUTES_15.deadline(1_000)
        assertEquals(1_000 + 15 * 60_000L, deadline)
        assertFalse(SleepTimer.hasExpired(deadline, 1_000 + 15 * 60_000L - 1))
        assertTrue(SleepTimer.hasExpired(deadline, 1_000 + 15 * 60_000L))
        assertFalse(SleepTimer.hasExpired(null, Long.MAX_VALUE))
        assertEquals(60 * 60_000L, SleepTimer.MINUTES_60.durationMillis)
    }

    @Test
    fun speedsAreIosStepsWithIosLabels() {
        assertEquals(listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0), ListenSpeed.steps)
        assertEquals(listOf("0.5×", "0.75×", "1×", "1.25×", "1.5×", "1.75×", "2×"), ListenSpeed.steps.map(ListenSpeed::label))
        assertEquals(1.0, ListenSpeed.sanitize(null), 0.0)
        assertEquals(1.0, ListenSpeed.sanitize(0.0), 0.0)
        assertEquals(2.0, ListenSpeed.sanitize(7.0), 0.0)
        assertEquals(0.5, ListenSpeed.sanitize(0.1), 0.0)
        assertEquals(1.25, ListenSpeed.sanitize(1.25), 0.0)
    }
}
