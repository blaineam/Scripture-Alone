package com.blainemiller.scripturealone.data.listen

import com.blainemiller.scripturealone.data.ChapterVerse
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.sabible.ChapterRef

/**
 * What Listen reads and in what order — the queue half of `ScriptureAlone/Listen/ListenController.swift`,
 * kept free of Android so every rule is proven on the JVM.
 *
 * What is read is each verse's plain text: the `verses.text` column, which carries no headings,
 * footnote letters or verse numbers. Headings and psalm superscriptions (verse 0) are never read,
 * and the words of Christ are read in the same voice as the rest — exactly as iOS does.
 */
object ListenQueue {

    /** One thing to say: a verse, or the chapter announcement ([key] `== 0`). */
    data class Item(val key: Int, val text: String) {
        val isAnnouncement: Boolean get() = key == 0
    }

    /** Where a pass reads: from a verse to the end of a chapter (and maybe on), or just the selection. */
    sealed interface Scope {
        data class Chapter(val ref: ChapterRef) : Scope
        data object Selection : Scope
    }

    /** "John, chapter 3." — or just "Jude" for a single-chapter book, as iOS announces it. */
    fun announcement(ref: ChapterRef): String {
        val book = BookID.of(ref.book) ?: return ""
        return if (book.isSingleChapter) book.displayName else "${book.displayName}, chapter ${ref.chapter}."
    }

    /**
     * The chapter from verse [from] (clamped to the verses it has) to its end — `chapterItems`. Starting
     * at the first verse, the chapter is announced first. Verse 0 is never read.
     */
    fun chapterItems(ref: ChapterRef, verses: List<ChapterVerse>, from: Int): List<Item> {
        val numbered = verses.filter { it.ref.verse >= 1 && it.ref.book == ref.book && it.ref.chapter == ref.chapter }
            .sortedBy { it.ref.verse }
        val count = numbered.lastOrNull()?.ref?.verse ?: return emptyList()
        val first = from.coerceIn(1, count)
        val result = mutableListOf<Item>()
        if (first == 1) result += Item(0, announcement(ref))
        numbered.filter { it.ref.verse >= first }.mapTo(result) { Item(it.ref.key, it.text) }
        return result
    }

    /** The selected verses, in order, with no announcement — `playSelection`. */
    fun selectionItems(verses: List<ChapterVerse>): List<Item> =
        verses.filter { it.ref.verse >= 1 }.sortedBy { it.ref.key }.distinctBy { it.ref.key }.map { Item(it.ref.key, it.text) }

    /**
     * The verse to mark when a pass begins at [index]: that verse, or while the announcement is being
     * read, the first verse after it.
     */
    fun markedVerse(items: List<Item>, index: Int): Int? {
        val item = items.getOrNull(index) ?: return null
        return if (!item.isAnnouncement) item.key else items.firstOrNull { !it.isAnnouncement }?.key
    }

    /** What a skip lands on. */
    sealed interface Skip {
        data class To(val index: Int) : Skip
        /** Past the last verse: the pass is over (and may continue into the next chapter). */
        data object Finished : Skip
    }

    /**
     * Next or previous verse from [current] — `skip(by:)`. The announcement is stepped over; before the
     * first verse is the first verse.
     */
    fun skip(items: List<Item>, current: Int, delta: Int): Skip? {
        if (items.isEmpty()) return null
        var target = current + delta
        val step = if (delta > 0) 1 else -1
        while (target >= 0 && target < items.size && items[target].isAnnouncement) target += step
        if (target < 0) target = items.indexOfFirst { !it.isAnnouncement }.coerceAtLeast(0)
        if (target >= items.size) return Skip.Finished
        return Skip.To(target)
    }

    /** Where play starts again after a pass ends: its first verse, past any announcement — `endPass`. */
    fun restartIndex(items: List<Item>): Int = if (items.firstOrNull()?.isAnnouncement == true) 1 else 0

    /** "John 3:16" for the verse being read — `nowPlayingTitle`. */
    fun title(key: Int?): String? {
        if (key == null || key <= 0) return null
        val ref = VerseRef.fromKey(key)
        val book = BookID.of(ref.book) ?: return null
        return "${book.displayName} ${ref.chapter}:${ref.verse}"
    }

    /** How a pass ends — the decision at the top of `passFinished`. */
    sealed interface PassEnd {
        /** Read on into [next], opening it in the reader. */
        data class Continue(val next: ChapterRef) : PassEnd
        /** Stop, paused at the start of what was read, the bar still up. */
        data class Stop(val clearEndOfChapterTimer: Boolean) : PassEnd
    }

    /**
     * A chapter pass goes on into the next chapter when Continue to Next Chapter is on and the sleep
     * timer isn't "End of Chapter"; a selection pass, Revelation 22 and an end-of-chapter timer stop.
     */
    fun passEnd(scope: Scope, continueChapters: Boolean, sleepTimer: SleepTimer, next: (ChapterRef) -> ChapterRef?): PassEnd {
        if (scope is Scope.Chapter && continueChapters && sleepTimer != SleepTimer.END_OF_CHAPTER) {
            next(scope.ref)?.let { return PassEnd.Continue(it) }
        }
        return PassEnd.Stop(clearEndOfChapterTimer = sleepTimer == SleepTimer.END_OF_CHAPTER)
    }
}

/** Stops listening after a while — for falling asleep to the Psalms. The iOS options and titles. */
enum class SleepTimer(val title: String, val durationMillis: Long?) {
    OFF("Off", null),
    MINUTES_15("15 Minutes", 15 * 60_000L),
    MINUTES_30("30 Minutes", 30 * 60_000L),
    MINUTES_60("1 Hour", 60 * 60_000L),
    END_OF_CHAPTER("End of Chapter", null),
    ;

    /**
     * The moment a timed option runs out, on a clock that keeps counting while the phone sleeps
     * (`elapsedRealtime`), or null for Off and End of Chapter.
     */
    fun deadline(now: Long): Long? = durationMillis?.let { now + it }

    companion object {
        /** Whether a timer set to end at [deadline] has run out at [now]. */
        fun hasExpired(deadline: Long?, now: Long): Boolean = deadline != null && now >= deadline
    }
}

/** Reading speed: iOS's seven steps and their labels ("1×", "1.25×"). */
object ListenSpeed {
    val steps: List<Double> = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)
    const val DEFAULT = 1.0

    /** A stored speed, kept within 0.5–2×; anything unreadable is 1×. */
    fun sanitize(stored: Double?): Double =
        if (stored == null || stored.isNaN() || stored <= 0) DEFAULT else stored.coerceIn(steps.first(), steps.last())

    /** "1×", "0.5×", "1.25×" — at most two decimals, none when whole. */
    fun label(speed: Double): String {
        val rounded = Math.round(speed * 100) / 100.0
        val text = if (rounded == Math.floor(rounded)) rounded.toLong().toString()
        else rounded.toBigDecimal().stripTrailingZeros().toPlainString()
        return "$text×"
    }
}
