package com.blainemiller.scripturealone.data.listen

import com.blainemiller.scripturealone.data.Canon
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.sabible.ChapterRef

/**
 * What Android Auto (and any other media browser) shows, and what choosing a row does — the same screens as the iOS
 * app's CarPlay (`CarPlayCatalog.swift`): Listen (today's reading, continue), Books (testaments → books → chapters) and
 * Favorites. Plain values, so the whole tree is proven on the JVM without a car.
 *
 * A row is a folder ([children] set) or something to play ([id] names what: see [CarAction.parse]).
 */
data class CarEntry(val id: String, val title: String, val subtitle: String? = null, val children: List<CarEntry>? = null) {
    val isFolder: Boolean get() = children != null
}

/** The words on the screens, in the reader's language (string resources in the app, plain text in tests). */
data class CarLabels(
    val listen: String, val today: String, val continueReading: String, val startAPlan: String,
    val books: String, val oldTestament: String, val newTestament: String, val favorites: String,
    val readingPlans: String = "Reading Plans", val weeklyStudy: String = "Weekly Study", val latestMessage: String = "The latest message",
    val nothingToday: String = "Nothing today",
) {
    /** "Bible in a Year Study" when several plans have one. */
    fun studyOf(plan: String) = "$plan — $weeklyStudy"
}

/** One of the reader's started plans, with today's passage in it; [hasStudy] when its church publishes a weekly study. */
data class CarPlanRow(val id: String, val title: String, val today: String?, val hasStudy: Boolean = false)

/** What a playable row does. */
sealed interface CarAction {
    data object Today : CarAction
    data object Continue : CarAction
    data class Chapter(val ref: ChapterRef) : CarAction
    /** A favorite: stored (KJV) verse keys. */
    data class Range(val range: VerseRange) : CarAction
    /** Today's reading in one of several plans. */
    data class Plan(val planId: String) : CarAction
    /** The weekly study of a church's plan, read aloud. */
    data class Study(val planId: String) : CarAction
    /** A favorite, read on through the others: this range first, then those after it, then those before it. */
    data class Favorites(val from: VerseRange) : CarAction
    /** What the assistant heard: "play John 3:16-21" — a reference, in the app's language, as one or more phrases ([VoiceQuery.decode]); blank means "play something". */
    data class Spoken(val query: String) : CarAction

    companion object {
        const val TODAY = "play:today"
        const val CONTINUE = "play:continue"
        fun chapterId(ref: ChapterRef) = "play:chapter:${ref.book}:${ref.chapter}"
        fun planId(id: String) = "play:plan:$id"
        fun studyId(id: String) = "play:study:$id"
        fun rangeId(range: VerseRange) = "play:range:${range.storageString}"
        fun favoritesId(from: VerseRange) = "play:favorites:${from.storageString}"
        fun spokenId(query: String) = "play:spoken:$query"
        /** A voice request with every phrase the assistant gave for it ([VoiceQuery]). */
        fun spokenId(phrases: List<String>) = spokenId(VoiceQuery.encode(phrases))

        /** The action a media id stands for; null for a folder or anything unknown. */
        fun parse(id: String): CarAction? {
            val parts = id.split(":")
            if (parts.size < 2 || parts[0] != "play") return null
            return when (parts[1]) {
                "today" -> Today
                "continue" -> Continue
                "chapter" -> {
                    val book = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it in 1..BookID.entries.size } ?: return null
                    val chapter = parts.getOrNull(3)?.toIntOrNull()?.takeIf { it in 1..Canon.book(book).chapterCount } ?: return null
                    Chapter(ChapterRef(book, chapter))
                }
                // The query may hold colons ("John 3:16"), so it is everything after the third separator.
                "spoken" -> Spoken(id.split(":", limit = 3).getOrNull(2).orEmpty())
                "favorites" -> parts.getOrNull(2)?.let { VerseRange.parse(it) }?.let(::Favorites)
                // Plan ids can hold colons, so each is everything after the third separator.
                "plan" -> id.split(":", limit = 3).getOrNull(2)?.takeIf { it.isNotEmpty() }?.let(::Plan)
                "study" -> id.split(":", limit = 3).getOrNull(2)?.takeIf { it.isNotEmpty() }?.let(::Study)
                "range" -> parts.getOrNull(2)?.let { VerseRange.parse(it) }?.let(::Range)
                else -> null
            }
        }
    }
}

object CarCatalog {
    const val ROOT = "root"

    /** Rows a list may hold when the host doesn't say; Android Auto pages far more, but a driver scrolls little. */
    const val DEFAULT_LIMIT = 50

    /**
     * The whole tree. [todayDisplay] is today's reading ("Genesis 1–3") or null with no plan started; [location] is where
     * the reader left off; [favorites] are their stored ranges, shown with [display]. Every list holds at most [limit] rows.
     */
    fun tree(
        labels: CarLabels, todayDisplay: String?, location: ChapterRef, favorites: List<VerseRange>,
        display: (VerseRange) -> String = { it.display }, limit: Int = DEFAULT_LIMIT, plans: List<CarPlanRow> = emptyList(),
    ): CarEntry {
        val listenRows = mutableListOf(
            CarEntry(CarAction.TODAY, labels.today, todayDisplay ?: labels.startAPlan),
            CarEntry(CarAction.CONTINUE, labels.continueReading, Canon.display(location)),
        )
        // With two or more plans started there is a choice to offer; with one, "Today's Reading" already is it.
        if (plans.size >= 2) {
            listenRows += CarEntry("plans", labels.readingPlans, null, grouped("plans",
                plans.map { CarEntry(CarAction.planId(it.id), it.title, it.today ?: labels.nothingToday) }, limit))
        }
        for (plan in plans.filter { it.hasStudy }) {
            val several = plans.count { it.hasStudy } > 1
            listenRows += CarEntry(CarAction.studyId(plan.id), if (several) labels.studyOf(plan.title) else labels.weeklyStudy, labels.latestMessage)
        }
        val listen = CarEntry("listen", labels.listen, null, listenRows)
        val old = BookID.entries.filter { !it.isNewTestament }
        val new = BookID.entries.filter { it.isNewTestament }
        val books = CarEntry("books", labels.books, null, listOf(
            CarEntry("ot", labels.oldTestament, null, grouped("ot", old.map { book(it, limit) }, limit)),
            CarEntry("nt", labels.newTestament, null, grouped("nt", new.map { book(it, limit) }, limit)),
        ))
        val saved = CarEntry("favs", labels.favorites, null, grouped("favs",
            favorites.map { CarEntry(CarAction.favoritesId(it), display(it)) }, limit))
        return CarEntry(ROOT, "Scripture Alone", null, listOf(listen, books, saved))
    }

    /** A book: its chapters, or the one chapter of a single-chapter book, which plays at once. */
    fun book(book: BookID, limit: Int = DEFAULT_LIMIT): CarEntry {
        if (book.isSingleChapter) return CarEntry(CarAction.chapterId(ChapterRef(book.number, 1)), book.displayName)
        val chapters = (1..book.chapterCount).map { n ->
            CarEntry(CarAction.chapterId(ChapterRef(book.number, n)), "$n", Canon.display(ChapterRef(book.number, n)))
        }
        return CarEntry("book:${book.number}", book.displayName, null, grouped("book:${book.number}", chapters, limit))
    }

    /**
     * [entries] as they are when they fit in [limit] rows; otherwise folders of at most [limit] each, labelled by their
     * first and last row ("Genesis – Ruth", "1 – 144"), and folders of those folders if even they are too many.
     */
    fun grouped(parent: String, entries: List<CarEntry>, limit: Int): List<CarEntry> {
        val size = maxOf(2, limit)
        if (entries.size <= size) return entries
        val folders = entries.chunked(size).mapIndexed { i, slice ->
            val title = if (slice.size == 1) slice[0].title
            else slice.first().title.substringBefore(" – ") + " – " + slice.last().title.substringAfterLast(" – ")
            CarEntry("$parent/$i", title, null, slice)
        }
        return grouped("$parent/g", folders, size)
    }

    /** Every row of [root] by id, for `onGetItem` and to look a folder's children up. */
    fun index(root: CarEntry): Map<String, CarEntry> {
        val out = LinkedHashMap<String, CarEntry>()
        fun walk(e: CarEntry) {
            out[e.id] = e
            e.children?.forEach(::walk)
        }
        walk(root)
        return out
    }
}
