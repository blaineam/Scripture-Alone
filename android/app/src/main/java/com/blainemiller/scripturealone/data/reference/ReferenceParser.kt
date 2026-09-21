package com.blainemiller.scripturealone.data.reference

import com.blainemiller.scripturealone.data.canon.BookID

/**
 * A passage as typed: a book with optional chapter and verse bounds. Verse counts are resolved later
 * against a translation ([range]), because they differ between translations.
 *
 * Ported from `ScriptureAloneCore/ReferenceParser.swift`. Construct through [of], which applies the
 * same defaults as the Swift initialiser: a missing end chapter is the start chapter, and a missing
 * end verse is the start verse *only* when no end chapter was given.
 */
data class Passage(
    val book: BookID,
    val startChapter: Int,
    val startVerse: Int?,
    val endChapter: Int,
    val endVerse: Int?,
) {
    val isWholeChapter: Boolean get() = startVerse == null

    /** Verse keys `(first, last)` once verse counts are known — `book*1_000_000 + chapter*1_000 + verse`. */
    fun range(verseCount: (book: BookID, chapter: Int) -> Int): Pair<Int, Int> {
        val last = endVerse ?: verseCount(book, endChapter)
        return key(startChapter, startVerse ?: 1) to key(endChapter, maxOf(1, last))
    }

    private fun key(chapter: Int, verse: Int) = book.number * 1_000_000 + chapter * 1_000 + verse

    /** Clamps chapters to the book; verses are clamped by the reader against real counts. */
    val clamped: Passage
        get() {
            val c1 = startChapter.coerceIn(1, book.chapterCount)
            val c2 = endChapter.coerceIn(c1, book.chapterCount)
            return of(book, c1, startVerse, c2, endVerse)
        }

    /** "John 3:16", "Romans 8:28–39", "Psalms 23", "Genesis 1:1–2:3", "Psalms 23–24". */
    val display: String
        get() {
            val name = book.displayName
            val v1 = startVerse
            if (v1 == null) {
                if (book.isSingleChapter) return name
                return if (endChapter == startChapter) "$name $startChapter" else "$name $startChapter–$endChapter"
            }
            val head = if (book.isSingleChapter) "$name $v1" else "$name $startChapter:$v1"
            if (endChapter != startChapter) return "$head–$endChapter:${endVerse ?: ""}"
            val v2 = endVerse
            if (v2 == null || v2 == v1) return head
            return "$head–$v2"
        }

    companion object {
        fun of(book: BookID, startChapter: Int, startVerse: Int? = null,
               endChapter: Int? = null, endVerse: Int? = null): Passage =
            Passage(
                book = book,
                startChapter = startChapter,
                startVerse = startVerse,
                endChapter = endChapter ?: startChapter,
                endVerse = endVerse ?: (if (endChapter == null) startVerse else null),
            )
    }
}

/**
 * Turns what a reader types — "jn 3 16", "Rom 8:28-39", "1co13", "Ps 23", "Gen 1:1–2:3", "Jude 3" —
 * into a [Passage]. A line-for-line port of the Swift parser; its tests are ported too, so the two
 * apps accept exactly the same input.
 */
object ReferenceParser {

    /** Books most people mean when an abbreviation is ambiguous ("jo" → John, "ph" → Philippians). */
    private val popularity: List<BookID> = listOf(
        BookID.JOHN, BookID.PSALMS, BookID.ROMANS, BookID.MATTHEW, BookID.GENESIS, BookID.MARK,
        BookID.LUKE, BookID.PROVERBS, BookID.ISAIAH, BookID.ACTS, BookID.PHILIPPIANS, BookID.EPHESIANS,
        BookID.HEBREWS, BookID.JAMES, BookID.FIRST_CORINTHIANS, BookID.GALATIANS, BookID.REVELATION,
        BookID.EXODUS, BookID.JOB, BookID.JEREMIAH, BookID.DANIEL, BookID.COLOSSIANS, BookID.FIRST_JOHN,
        BookID.FIRST_PETER, BookID.JOSHUA, BookID.JUDGES, BookID.RUTH, BookID.JONAH, BookID.DEUTERONOMY,
        BookID.LEVITICUS, BookID.NUMBERS, BookID.ECCLESIASTES, BookID.FIRST_SAMUEL, BookID.FIRST_KINGS,
        BookID.FIRST_THESSALONIANS, BookID.FIRST_TIMOTHY, BookID.SECOND_TIMOTHY, BookID.TITUS, BookID.JUDE,
    )

    private fun rank(book: BookID): Int = popularity.indexOf(book).let { if (it >= 0) it else 100 + book.number }

    private val ordinals = listOf(
        Regex("""^(iii|3rd|third)\s+""") to "3 ",
        Regex("""^(ii|2nd|second)\s+""") to "2 ",
        Regex("""^(i|1st|first)\s+""") to "1 ",
    )

    /** Replaces spelled-out or Roman ordinals before a book name: "First John", "II Cor" → "1 john", "2 cor". */
    internal fun normalizeOrdinals(s: String): String {
        for ((pattern, replacement) in ordinals) {
            val match = pattern.find(s) ?: continue
            return s.replaceRange(match.range, replacement)
        }
        return s
    }

    /**
     * Books matching what has been typed so far, best first: an exact abbreviation wins, then prefixes
     * of names or abbreviations, ordered by how often each book is read.
     */
    fun books(matching: String): List<BookID> {
        val token = BookID.normalize(normalizeOrdinals(matching.lowercase().trim()))
        if (token.isEmpty()) return emptyList()
        val exact = BookID.entries.firstOrNull { token in it.aliases }
            ?: return prefixMatches(token)
        return listOf(exact) + prefixMatches(token).filter { it != exact }
    }

    private fun prefixMatches(token: String): List<BookID> =
        BookID.entries
            .filter { book -> book.aliases.any { it.startsWith(token) } }
            .sortedBy(::rank)

    // book, chapter, (":" verse | " " verse), ("-" chapter-or-verse (":" verse)?)
    private val pattern = Regex(
        """^([1-3]?\s*[a-z][a-z ]*?)\s*(\d+)?(?:\s*[:.]\s*(\d+)|\s+(\d+))?(?:\s*-\s*(\d+)?(?:\s*[:.]\s*(\d+))?)?$""",
    )

    internal fun clean(text: String): String {
        var s = text.lowercase()
            .replace("–", "-")
            .replace("—", "-")
            .trim()
        // Periods after abbreviations ("Rom. 8") are noise; keep "3.16" as a separator.
        s = s.replace(Regex("""(?<=[a-z])\."""), " ")
        s = s.replace(Regex("""\s+"""), " ")
        return normalizeOrdinals(s)
    }

    /** Parses a single reference: "jn 3 16", "Rom 8:28-39", "1co13", "Ps 23", "Gen 1:1–2:3", "Jude 3". */
    fun parse(text: String): Passage? {
        val m = pattern.find(clean(text)) ?: return null
        fun group(i: Int): String? = m.groups[i]?.value
        fun number(i: Int): Int? = group(i)?.toIntOrNull()
        val book = group(1)?.let { books(matching = it).firstOrNull() } ?: return null

        val chapter = number(2) ?: return Passage.of(book, 1)
        val verse = number(3) ?: number(4)
        val dashA = number(5)
        val dashB = number(6)

        if (book.isSingleChapter && verse == null) {
            // "Jude 3" / "Jude 3-5" mean verses.
            if (chapter == 1 && dashA == null) return Passage.of(book, 1)
            return Passage.of(book, 1, startVerse = chapter, endChapter = 1, endVerse = dashA ?: chapter)
        }
        if (verse != null) {
            if (dashA != null && dashB != null) {
                return Passage.of(book, chapter, startVerse = verse, endChapter = dashA, endVerse = dashB)
            }
            return Passage.of(book, chapter, startVerse = verse, endChapter = chapter, endVerse = dashA ?: verse)
        }
        if (dashA != null) {
            // "Gen 1-2:3" → 1:1 through 2:3
            if (dashB != null) return Passage.of(book, chapter, startVerse = 1, endChapter = dashA, endVerse = dashB)
            return Passage.of(book, chapter, endChapter = maxOf(chapter, dashA))
        }
        return Passage.of(book, chapter)
    }

    /**
     * Parses a list — "Eph 2:1-10; Rom 3:23, 6:23" or "Ps 23, 24". Later items inherit the book, and
     * the chapter too for bare verse numbers after a verse reference.
     */
    fun parseList(text: String): List<Passage> {
        val results = mutableListOf<Passage>()
        val leadingOrdinalBook = Regex("""^[1-3]\s*[A-Za-z]""")
        for (piece in text.split(';', ',').map { it.trim() }) {
            if (piece.isEmpty()) continue
            if (piece.first().isLetter() || leadingOrdinalBook.containsMatchIn(piece)) {
                parse(piece)?.let(results::add)
                continue
            }
            val last = results.lastOrNull() ?: continue
            val s = clean(piece)
            if (':' in s || '.' in s) {
                parse("${last.book.aliases[0]} $s")?.let(results::add)
            } else if (last.startVerse != null) {
                // "Rom 3:23, 25" → verse 25 of the same chapter; "…, 25-27" → a verse range.
                val parts = s.split('-').mapNotNull { it.trim().toIntOrNull() }
                val v1 = parts.firstOrNull() ?: continue
                results += Passage.of(last.book, last.endChapter, startVerse = v1,
                                      endChapter = last.endChapter, endVerse = parts.getOrElse(1) { v1 })
            } else {
                parse("${last.book.aliases[0]} $s")?.let(results::add)
            }
        }
        return results
    }
}
