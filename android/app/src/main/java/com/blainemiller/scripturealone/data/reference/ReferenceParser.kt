package com.blainemiller.scripturealone.data.reference

import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.canon.BookNames

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

    private val leadingOrdinalBook = Regex("""^[1-3]\.?\s*\p{L}""")

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
     *
     * The reader's own [language] first, then English, then every other language's spellings that mean
     * only one book — "Jean 3:16" works for anyone, "Es 1" only means Isaiah in French.
     */
    fun books(matching: String, language: String? = BookNames.current): List<BookID> {
        val token = BookID.normalize(normalizeOrdinals(matching.lowercase().trim()))
        if (token.isEmpty()) return emptyList()
        val tiers = spellingTiers(language)
        for (tier in tiers) {
            val exact = BookID.entries.firstOrNull { tier[it]?.contains(token) == true } ?: continue
            return listOf(exact) + prefixMatches(token, tiers).filter { it != exact }
        }
        return prefixMatches(token, tiers)
    }

    private val englishTier: Map<BookID, Set<String>> by lazy { BookID.entries.associateWith { it.aliases.toSet() } }

    private val tierCache = java.util.concurrent.ConcurrentHashMap<String, List<Map<BookID, Set<String>>>>()

    /** Per language: its own spellings, English's, then the other languages' unambiguous ones. */
    private fun spellingTiers(current: String?): List<Map<BookID, Set<String>>> =
        tierCache.getOrPut(current.orEmpty()) { buildTiers(current) }

    private fun buildTiers(current: String?): List<Map<BookID, Set<String>>> {
        val tiers = mutableListOf<Map<BookID, Set<String>>>()
        current?.let { BookNames.aliases[it] }?.let(tiers::add)
        tiers += englishTier
        val others = HashMap<BookID, MutableSet<String>>()
        for ((language, books) in BookNames.aliases) {
            if (language == current) continue
            for ((book, spellings) in books) others.getOrPut(book) { mutableSetOf() } += spellings - BookNames.ambiguous
        }
        tiers += others
        return tiers
    }

    private fun prefixMatches(token: String, tiers: List<Map<BookID, Set<String>>>): List<BookID> {
        for (tier in tiers) {
            val hits = BookID.entries.filter { book -> tier[book]?.any { it.startsWith(token) } == true }
            if (hits.isNotEmpty()) return hits.sortedBy(::rank)
        }
        return emptyList()
    }

    // book, chapter, (":" verse | " " verse), ("-" chapter-or-verse (":" verse)?)
    // Letters in any script: "Genèse", "创世记", "요한복음", "ヨハネ傳福音書".
    private val pattern = Regex(
        """^([1-3]?\s*\p{L}[\p{L}\p{M} ]*?)\s*(\d+)?(?:\s*[:.]\s*(\d+)|\s+(\d+))?(?:\s*-\s*(\d+)?(?:\s*[:.]\s*(\d+))?)?$""",
    )

    private val chapterAndVerse = Regex("""(\d+)\s*[章장]\s*(\d+)\s*[節节절]?""")
    private val counter = Regex("""(\d+)\s*[章장節节절]""")
    private val digitCommaDigit = Regex("""(\d),(\d)""")
    private val germanOrdinal = Regex("""^([1-3])\.\s*(?=\p{L})""")
    private val abbreviationPeriod = Regex("""(?<=\p{L})\.""")
    private val whitespace = Regex("""\s+""")

    internal fun clean(text: String): String {
        var s = text.lowercase()
            .replace("–", "-")
            .replace("—", "-")
            .replace("〜", "-")
            .replace("～", "-")
            .trim()
        // Full-width digits and punctuation, as a Chinese, Japanese or Korean keyboard types them.
        s = buildString(s.length) {
            for (c in s) {
                append(
                    when (c.code) {
                        in 0xFF10..0xFF19 -> '0' + (c.code - 0xFF10)
                        0xFF1A -> ':'
                        0xFF0E, 0x3002 -> '.'
                        0xFF0D -> '-'
                        else -> c
                    },
                )
            }
        }
        // "3章16節", "3章16节", "3장 16절": chapter and verse counters.
        s = s.replace(chapterAndVerse, "$1:$2")
        s = s.replace(counter, "$1")
        // "Joh 3,16": German writes chapter and verse with a comma.
        s = s.replace(digitCommaDigit, "$1:$2")
        // "1. Mose", "2. Korinther": a German ordinal's period is not a separator.
        s = s.replace(germanOrdinal, "$1 ")
        // Periods after abbreviations ("Rom. 8") are noise; keep "3.16" as a separator.
        s = s.replace(abbreviationPeriod, " ")
        s = s.replace(whitespace, " ")
        return normalizeOrdinals(s)
    }

    /**
     * Parses a reference in whichever of the nine languages its book name belongs to — for text that
     * isn't in the reader's own language (a slide, a pasted note). The book must be a whole name or
     * abbreviation of some language; a prefix doesn't count. `ReferenceParser.parseAnyLanguage` in Swift.
     */
    fun parseAnyLanguage(text: String): Passage? {
        for (language in listOf(BookNames.current) + BookNames.languages + listOf(null)) {
            val passage = parse(text, language) ?: continue
            val bookText = pattern.find(clean(text))?.groups?.get(1)?.value ?: continue
            val token = BookID.normalize(normalizeOrdinals(bookText))
            val exact = spellingTiers(language).any { it[passage.book]?.contains(token) == true }
            if (exact && passage.startChapter <= passage.book.chapterCount) return passage
        }
        return null
    }

    /** Parses a single reference: "jn 3 16", "Rom 8:28-39", "1co13", "Ps 23", "Gen 1:1–2:3", "Jude 3". */
    fun parse(text: String, language: String? = BookNames.current): Passage? {
        val m = pattern.find(clean(text)) ?: return null
        fun group(i: Int): String? = m.groups[i]?.value
        fun number(i: Int): Int? = group(i)?.toIntOrNull()
        val book = group(1)?.let { books(matching = it, language = language).firstOrNull() } ?: return null

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
    fun parseList(text: String, language: String? = BookNames.current): List<Passage> {
        val results = mutableListOf<Passage>()
        // Full-width separators too: "；" "，" "、" in Chinese and Japanese lists. A comma between digits
        // is German's chapter-verse separator ("Joh 3,16"), not a list break.
        val joined = text.replace(digitCommaDigit, "$1:$2")
        for (piece in joined.split(';', ',', '；', '，', '、').map { it.trim() }) {
            if (piece.isEmpty()) continue
            if (piece.first().isLetter() || leadingOrdinalBook.containsMatchIn(piece)) {
                parse(piece, language)?.let(results::add)
                continue
            }
            val last = results.lastOrNull() ?: continue
            val s = clean(piece)
            if (':' in s || '.' in s) {
                parse("${last.book.aliases[0]} $s", language)?.let(results::add)
            } else if (last.startVerse != null) {
                // "Rom 3:23, 25" → verse 25 of the same chapter; "…, 25-27" → a verse range.
                val parts = s.split('-').mapNotNull { it.trim().toIntOrNull() }
                val v1 = parts.firstOrNull() ?: continue
                results += Passage.of(last.book, last.endChapter, startVerse = v1,
                                      endChapter = last.endChapter, endVerse = parts.getOrElse(1) { v1 })
            } else {
                parse("${last.book.aliases[0]} $s", language)?.let(results::add)
            }
        }
        return results
    }
}
