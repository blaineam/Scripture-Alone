package com.blainemiller.scripturealone.data.importer

import com.blainemiller.scripturealone.data.canon.BookID

/**
 * What the import actually got, book by book. Ported from `Import/ImportCoverageReport.swift`.
 *
 * Publisher files vary enormously, and a run that yields 40% of Genesis must say so rather than report
 * success. Everything here is computed from the rows themselves — chapter counts come from the app's
 * canon, and missing verses are the gaps in each chapter's own numbering, because no two translations
 * agree on how many verses a chapter has.
 */
class ImportCoverageReport(bible: ExtractedBible) {

    data class ChapterCoverage(
        val chapter: Int,
        val highestVerse: Int,
        val versesFound: Int,
        /**
         * Numbers missing from 1..highestVerse. A translation that genuinely omits a verse (the ASV
         * omits sixteen) shows up here too, which is the honest answer.
         */
        val missingVerses: List<Int>,
        val outOfOrder: Boolean,
    )

    data class BookCoverage(
        val book: BookID,
        val chaptersFound: Int,
        val chaptersExpected: Int,
        val versesFound: Int,
        val missingChapters: List<Int>,
        val unexpectedChapters: List<Int>,
        val chaptersWithGaps: List<ChapterCoverage>,
    ) {
        val id: Int get() = book.number
        val completeness: Double get() = if (chaptersExpected == 0) 0.0 else chaptersFound.toDouble() / chaptersExpected
        val isComplete: Boolean get() = missingChapters.isEmpty() && chaptersWithGaps.isEmpty() && unexpectedChapters.isEmpty()
    }

    val books: List<BookCoverage>
    val booksMissing: List<BookID>
    val totalVerses: Int
    val totalChapters: Int

    /** How each spine document's (or USFM file's) verse markup was recognised. */
    val markupShapes: Map<String, VerseMarkupShape>
    val notes: List<ImportNote>

    val booksFound: List<BookID> get() = books.map { it.book }
    val isWholeBible: Boolean get() = booksMissing.isEmpty() && books.all { it.isComplete }

    /** Share of the canon's 1,189 chapters that arrived. */
    val completeness: Double
        get() {
            val expected = BookID.entries.sumOf { it.chapterCount }
            return if (expected == 0) 0.0 else totalChapters.toDouble() / expected
        }

    val highestSeverity: ImportNote.Severity get() = notes.maxOfOrNull { it.severity } ?: ImportNote.Severity.INFO

    /** One line the UI can show without composing its own. */
    val summary: String
        get() {
            val books = booksFound.size
            // Swift's `rounded()` rounds half away from zero; `Math.round` agrees for these non-negative values.
            val percent = Math.round(completeness * 100).toInt()
            if (isWholeBible) return "All 66 books, $totalChapters chapters, $totalVerses verses."
            return "$books book${if (books == 1) "" else "s"}, $totalChapters chapters, $totalVerses verses — $percent% of the canon."
        }

    /** Short lines describing everything incomplete, worst first. */
    val problems: List<String>
        get() {
            val lines = ArrayList<String>()
            if (booksMissing.isNotEmpty()) {
                val shown = booksMissing.map { it.displayName }.take(6).joinToString(", ")
                lines.add(
                    if (booksMissing.size > 6) "${booksMissing.size} books are missing, including $shown."
                    else "Missing: $shown.",
                )
            }
            for (book in books) {
                if (book.isComplete) continue
                val name = book.book.displayName
                if (book.missingChapters.isNotEmpty()) {
                    lines.add("$name: ${book.chaptersFound} of ${book.chaptersExpected} chapters (missing ${condense(book.missingChapters)}).")
                }
                if (book.unexpectedChapters.isNotEmpty()) {
                    lines.add("$name: unexpected chapter${if (book.unexpectedChapters.size == 1) "" else "s"} " + condense(book.unexpectedChapters) + ".")
                }
                for (chapter in book.chaptersWithGaps) {
                    if (chapter.missingVerses.isNotEmpty()) {
                        lines.add(
                            "$name ${chapter.chapter}: missing verse${if (chapter.missingVerses.size == 1) "" else "s"} " +
                                condense(chapter.missingVerses) + ".",
                        )
                    }
                    if (chapter.outOfOrder) lines.add("$name ${chapter.chapter}: verse numbers ran out of order.")
                }
            }
            // A note that repeats a line already given ("Genesis 3: verse numbers ran out of order.")
            // is said once.
            for (note in notes) {
                if (note.severity != ImportNote.Severity.INFO && note.message !in lines) lines.add(note.message)
            }
            return lines
        }

    init {
        val coverage = ArrayList<BookCoverage>()
        var verseTotal = 0
        var chapterTotal = 0
        val outOfOrderChapters = bible.outOfOrderChapters
        // A verse the source printed as part of the verse before it is combined, not missing.
        val bridged = bible.bridgedVerses.keys

        for (book in bible.books) {
            val chapters = bible.chapterOrder.filter { it.book == book }.map { it.chapter }.sorted()
            val gaps = ArrayList<ChapterCoverage>()
            var versesInBook = 0
            for (chapter in chapters) {
                val ref = ChapterRef(book, chapter)
                val numbers = bible.verseNumbers(ref)
                versesInBook += numbers.size
                val highest = numbers.lastOrNull() ?: 0
                val present = numbers.toSet()
                val missing = if (highest > 0) {
                    (1..highest).filter { it !in present && verseRef(book, chapter, it) !in bridged }
                } else {
                    emptyList()
                }
                val disordered = ref in outOfOrderChapters
                if (missing.isNotEmpty() || disordered || numbers.isEmpty()) {
                    gaps.add(ChapterCoverage(chapter, highest, numbers.size, missing, disordered))
                }
            }
            val expected = book.chapterCount
            val found = chapters.toSet()
            val missingChapters = (1..expected).filter { it !in found }
            val unexpected = chapters.filter { it > expected || it < 1 }
            verseTotal += versesInBook
            chapterTotal += chapters.size
            coverage.add(BookCoverage(book, chapters.size, expected, versesInBook, missingChapters, unexpected, gaps))
        }

        val present = bible.books.toSet()
        books = coverage
        booksMissing = BookID.entries.filter { it !in present }
        totalVerses = verseTotal
        totalChapters = chapterTotal
        markupShapes = bible.shapesByDocument.toMap()
        notes = bible.notes.toList()
    }

    companion object {
        /** "1–3, 7, 19–21" */
        fun condense(numbers: List<Int>): String {
            val runs = ArrayList<String>()
            val sorted = numbers.sorted()
            var index = 0
            while (index < sorted.size) {
                var end = index
                while (end + 1 < sorted.size && sorted[end + 1] == sorted[end] + 1) end++
                runs.add(if (end == index) "${sorted[index]}" else "${sorted[index]}–${sorted[end]}")
                index = end + 1
            }
            return runs.joinToString(", ")
        }
    }
}
