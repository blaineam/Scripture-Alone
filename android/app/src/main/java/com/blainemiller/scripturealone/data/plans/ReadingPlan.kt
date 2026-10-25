package com.blainemiller.scripturealone.data.plans

import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.reference.Passage

/**
 * Reading plans, ported from `ScriptureAloneCore/Plans/` (docs/reading-plans.md). A plan is a
 * schedule of passage *references*; the text is read from the reader's own translation on the day.
 * Everything here is pure Kotlin so the JVM tests prove it against the same cases as the Swift ones.
 */
data class PlanPassage(
    val book: BookID,
    val startChapter: Int,
    val startVerse: Int?,
    val endChapter: Int,
    val endVerse: Int?,
) {
    val passage: Passage get() = Passage.of(book, startChapter, startVerse, endChapter, endVerse)
    val display: String get() = passage.display

    /** Every chapter the passage touches, in order, as (book, chapter). */
    val chapters: List<Pair<BookID, Int>>
        get() {
            val c = passage.clamped
            return (c.startChapter..maxOf(c.startChapter, c.endChapter)).map { book to it }
        }

    companion object {
        fun of(passage: Passage) = PlanPassage(passage.book, passage.startChapter, passage.startVerse, passage.endChapter, passage.endVerse)
        fun chapters(book: BookID, range: IntRange) = PlanPassage(book, range.first, null, range.last, null)
    }
}

/** A piece of a devotional plan's own writing for a day — `PlanDevotion` in the core. Paragraphs are separated by a blank line. */
data class PlanDevotion(val heading: String, val quote: String?, val text: String)

data class PlanDay(
    val index: Int,
    /** "yyyy-MM-dd", present for an imported plan whose source dated its days. */
    val date: String?,
    val label: String?,
    val passages: List<PlanPassage>,
    /** The plan's own writing for the day, when it has any (a devotional plan). */
    val devotions: List<PlanDevotion> = emptyList(),
    /** A guess for a day its source hasn't posted yet, from the last pass through the same readings ([PlanFeed.projection]). */
    val projected: Boolean = false,
) {
    val display: String get() = passages.joinToString("; ") { it.display }
}

enum class PlanKind { CANONICAL, CHRONOLOGICAL, BOOK, IMPORTED, DEVOTIONAL }

data class PlanSource(
    val siteUrl: String,
    val apiRoot: String?,
    val studyCategory: Int?,
    val name: String,
    val scheduleCategory: Int? = null,
)

data class ReadingPlan(
    val id: String,
    val kind: PlanKind,
    val title: String,
    val summary: String,
    val days: List<PlanDay>,
    /** Weekdays a plan with no dates of its own skips, 1 = Sunday … 7 = Saturday, as on iOS. */
    val skipWeekdays: Set<Int> = emptySet(),
    val source: PlanSource? = null,
    /** Days are a month and a day (`date` is "MM-DD"), and the plan starts over each January. 366 days. */
    val annual: Boolean = false,
) {
    val isDated: Boolean get() = !annual && days.isNotEmpty() && days.all { it.date != null }
}

/** The plans that ship with the app, generated from the canon: nothing to bundle or license. */
object PlanCatalog {
    const val YEAR = 365
    private const val BOOK_PREFIX = "book-"

    val canonical: ReadingPlan by lazy {
        ReadingPlan(
            "bible-year-canonical", PlanKind.CANONICAL, "Bible in a Year",
            "Genesis to Revelation in order, about three chapters a day.",
            spread(BookID.entries.map { PlanPassage.chapters(it, 1..it.chapterCount) }, YEAR),
        )
    }

    val chronological: ReadingPlan by lazy {
        ReadingPlan(
            "bible-year-chronological", PlanKind.CHRONOLOGICAL, "Bible in a Year, Chronological",
            "The whole Bible in the order the events happened, about three chapters a day.",
            spread(CHRONOLOGICAL_ORDER.map { (b, from, to) -> PlanPassage.chapters(b, from..to) }, YEAR),
        )
    }

    val builtIn: List<ReadingPlan> get() = listOf(chronological, canonical)

    fun book(book: BookID): ReadingPlan = ReadingPlan(
        "$BOOK_PREFIX${book.number}", PlanKind.BOOK, book.englishName,
        "One chapter a day through ${book.englishName}.",
        (1..book.chapterCount).mapIndexed { i, c -> PlanDay(i, null, null, listOf(PlanPassage.chapters(book, c..c))) },
    )

    fun plan(id: String): ReadingPlan? = when {
        id == chronological.id -> chronological
        id == canonical.id -> canonical
        id.startsWith(BOOK_PREFIX) -> id.removePrefix(BOOK_PREFIX).toIntOrNull()?.let { BookID.of(it) }?.let(::book)
        else -> null
    }

    /** Cuts a run of passages into [count] days of nearly equal chapters, in order. */
    internal fun spread(passages: List<PlanPassage>, count: Int): List<PlanDay> {
        val chapters = passages.flatMap { it.chapters }
        val total = chapters.size
        return (0 until count).map { day ->
            val from = day * total / count
            val to = (day + 1) * total / count
            val merged = mutableListOf<PlanPassage>()
            var run = mutableListOf<Pair<BookID, Int>>()
            fun flush() {
                if (run.isNotEmpty()) merged += PlanPassage.chapters(run.first().first, run.first().second..run.last().second)
                run = mutableListOf()
            }
            for (chapter in chapters.subList(from, to)) {
                if (run.isNotEmpty() && run.last().first != chapter.first) flush()
                run += chapter
            }
            flush()
            PlanDay(day, null, null, merged)
        }
    }

    /** An editorial chronological order, chapter-granular; covers every chapter exactly once (tested). */
    internal val CHRONOLOGICAL_ORDER: List<Triple<BookID, Int, Int>> = listOf(
        Triple(BookID.GENESIS, 1, 11), Triple(BookID.JOB, 1, 42), Triple(BookID.GENESIS, 12, 50),
        Triple(BookID.EXODUS, 1, 40), Triple(BookID.LEVITICUS, 1, 27), Triple(BookID.NUMBERS, 1, 36),
        Triple(BookID.DEUTERONOMY, 1, 34), Triple(BookID.PSALMS, 90, 90), Triple(BookID.JOSHUA, 1, 24),
        Triple(BookID.JUDGES, 1, 21), Triple(BookID.RUTH, 1, 4), Triple(BookID.FIRST_SAMUEL, 1, 31),
        Triple(BookID.SECOND_SAMUEL, 1, 24), Triple(BookID.FIRST_CHRONICLES, 1, 29), Triple(BookID.PSALMS, 1, 72),
        Triple(BookID.FIRST_KINGS, 1, 11), Triple(BookID.SECOND_CHRONICLES, 1, 9), Triple(BookID.PROVERBS, 1, 31),
        Triple(BookID.ECCLESIASTES, 1, 12), Triple(BookID.SONG_OF_SOLOMON, 1, 8), Triple(BookID.PSALMS, 73, 89),
        Triple(BookID.FIRST_KINGS, 12, 22), Triple(BookID.SECOND_CHRONICLES, 10, 20), Triple(BookID.OBADIAH, 1, 1),
        Triple(BookID.SECOND_KINGS, 1, 14), Triple(BookID.SECOND_CHRONICLES, 21, 25), Triple(BookID.JOEL, 1, 3),
        Triple(BookID.JONAH, 1, 4), Triple(BookID.AMOS, 1, 9), Triple(BookID.HOSEA, 1, 14),
        Triple(BookID.SECOND_KINGS, 15, 16), Triple(BookID.SECOND_CHRONICLES, 26, 28), Triple(BookID.ISAIAH, 1, 39),
        Triple(BookID.MICAH, 1, 7), Triple(BookID.SECOND_KINGS, 17, 19), Triple(BookID.SECOND_CHRONICLES, 29, 32),
        Triple(BookID.PSALMS, 91, 119), Triple(BookID.NAHUM, 1, 3), Triple(BookID.SECOND_KINGS, 20, 21),
        Triple(BookID.SECOND_CHRONICLES, 33, 33), Triple(BookID.ZEPHANIAH, 1, 3), Triple(BookID.ISAIAH, 40, 66),
        Triple(BookID.JEREMIAH, 1, 52), Triple(BookID.LAMENTATIONS, 1, 5), Triple(BookID.HABAKKUK, 1, 3),
        Triple(BookID.SECOND_KINGS, 22, 25), Triple(BookID.SECOND_CHRONICLES, 34, 36), Triple(BookID.EZEKIEL, 1, 48),
        Triple(BookID.DANIEL, 1, 12), Triple(BookID.EZRA, 1, 10), Triple(BookID.HAGGAI, 1, 2),
        Triple(BookID.ZECHARIAH, 1, 14), Triple(BookID.ESTHER, 1, 10), Triple(BookID.NEHEMIAH, 1, 13),
        Triple(BookID.MALACHI, 1, 4), Triple(BookID.PSALMS, 120, 150), Triple(BookID.MATTHEW, 1, 28),
        Triple(BookID.MARK, 1, 16), Triple(BookID.LUKE, 1, 24), Triple(BookID.JOHN, 1, 21), Triple(BookID.ACTS, 1, 28),
        Triple(BookID.JAMES, 1, 5), Triple(BookID.GALATIANS, 1, 6), Triple(BookID.FIRST_THESSALONIANS, 1, 5),
        Triple(BookID.SECOND_THESSALONIANS, 1, 3), Triple(BookID.FIRST_CORINTHIANS, 1, 16),
        Triple(BookID.SECOND_CORINTHIANS, 1, 13), Triple(BookID.ROMANS, 1, 16), Triple(BookID.EPHESIANS, 1, 6),
        Triple(BookID.PHILIPPIANS, 1, 4), Triple(BookID.COLOSSIANS, 1, 4), Triple(BookID.PHILEMON, 1, 1),
        Triple(BookID.FIRST_TIMOTHY, 1, 6), Triple(BookID.TITUS, 1, 3), Triple(BookID.FIRST_PETER, 1, 5),
        Triple(BookID.JUDE, 1, 1), Triple(BookID.HEBREWS, 1, 13), Triple(BookID.SECOND_TIMOTHY, 1, 4),
        Triple(BookID.SECOND_PETER, 1, 3), Triple(BookID.FIRST_JOHN, 1, 5), Triple(BookID.SECOND_JOHN, 1, 1),
        Triple(BookID.THIRD_JOHN, 1, 1), Triple(BookID.REVELATION, 1, 22),
    )
}
