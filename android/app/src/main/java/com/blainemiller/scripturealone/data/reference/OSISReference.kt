package com.blainemiller.scripturealone.data.reference

import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID

/**
 * OSIS references — the standard machine form for Bible references: `John.3.16`, `Gen.1.1-Gen.1.3`,
 * `Ps.23`, `1Cor.13.4-1Cor.13.7`. What other apps, study tools and URNs (`urn:osis:John.3.16`) use
 * to name a passage without depending on anyone's language. A line-for-line port of
 * `ScriptureAloneCore/OSISReference.swift`, with its tests, so both apps accept the same links.
 *
 * OSIS names verses by KJV versification, which is also what this app stores, so a parsed reference
 * is a **KJV key** passage: go to it with `go(VerseRef)`, never `go(Passage)`.
 *
 * Accepted beyond strict OSIS, because references in the wild bend the rules:
 * - a `urn:osis:` or `osis:` prefix, and an osisRef work prefix (`Bible.KJV:John.3.16`);
 * - USFM book codes (`JHN.3.16`), case-insensitively;
 * - a shortened end (`John.3.16-18`, `John.3-4`, `John.3.16-4.2`);
 * - several references separated by spaces, commas or semicolons;
 * - grain suffixes (`John.3.16!a`, `John.3.16@s[love]`), which are dropped.
 */
object OSISReference {
    /** The OSIS book abbreviations, in canonical order (index = `BookID.number - 1`). */
    val bookCodes: List<String> = listOf(
        "Gen", "Exod", "Lev", "Num", "Deut", "Josh", "Judg", "Ruth", "1Sam", "2Sam", "1Kgs", "2Kgs",
        "1Chr", "2Chr", "Ezra", "Neh", "Esth", "Job", "Ps", "Prov", "Eccl", "Song", "Isa", "Jer", "Lam",
        "Ezek", "Dan", "Hos", "Joel", "Amos", "Obad", "Jonah", "Mic", "Nah", "Hab", "Zeph", "Hag", "Zech",
        "Mal", "Matt", "Mark", "Luke", "John", "Acts", "Rom", "1Cor", "2Cor", "Gal", "Eph", "Phil", "Col",
        "1Thess", "2Thess", "1Tim", "2Tim", "Titus", "Phlm", "Heb", "Jas", "1Pet", "2Pet", "1John",
        "2John", "3John", "Jude", "Rev",
    )

    /** Lowercased OSIS and USFM codes → book. The two sets never disagree about a spelling. */
    private val books: Map<String, BookID> by lazy {
        val map = HashMap<String, BookID>()
        for (book in BookID.entries) {
            map[book.code.lowercase()] = book
            map[bookCodes[book.ordinal].lowercase()] = book
        }
        map
    }

    /** The OSIS code for a book: `John`, `1Cor`. */
    fun code(book: BookID): String = bookCodes[book.ordinal]

    /** A book from its OSIS or USFM code, ignoring case. */
    fun book(code: String): BookID? = books[code.lowercase()]

    /** `John.3.16`, `John.3.16-John.3.17`, `Gen.1.1-Gen.2.3` — a stored (KJV) range in OSIS form. */
    fun string(range: VerseRange): String {
        fun one(ref: VerseRef): String {
            val book = BookID.of(ref.book)?.let(::code) ?: "${ref.book}"
            return "$book.${ref.chapter}.${ref.verse}"
        }
        return if (range.start == range.end) one(range.start) else "${one(range.start)}-${one(range.end)}"
    }

    /** Strips a `urn:osis:` or `osis:` prefix, which a link may put in front of any reference. */
    fun stripUrnPrefix(text: String): String {
        val s = text.trim()
        for (prefix in listOf("urn:osis:", "osis:")) {
            if (s.lowercase().startsWith(prefix)) return s.substring(prefix.length).trim()
        }
        return s
    }

    /**
     * [stripUrnPrefix], then an osisRef work prefix (`Bible.KJV:John.3.16`). An OSIS reference has no
     * colon of its own, so everything before one is the work — unless it has a space in it, which
     * makes it a plain reference like "John 3:16".
     */
    internal fun stripPrefix(text: String): String {
        val s = stripUrnPrefix(text)
        val colon = s.lastIndexOf(':')
        if (colon < 0) return s
        val work = s.substring(0, colon)
        if (work.none { it.isLetter() } || work.any { it.isWhitespace() }) return s
        return s.substring(colon + 1)
    }

    /**
     * Parses one or more OSIS references. Returns null unless *every* piece is OSIS, so a plain
     * reference ("John 3:16") is left for [ReferenceParser].
     *
     * A whole-chapter reference (`Ps.23`) keeps `startVerse == null`; a range whose end names only a
     * chapter (`John.3.16-John.4`) has `endVerse == null` — resolve both against a translation's verse
     * counts with [Passage.range]. A range that crosses into another book is cut at the end of the
     * first book: a [Passage] belongs to one book.
     */
    fun parse(text: String): List<Passage>? {
        val pieces = stripPrefix(text).split(separators).filter { it.isNotEmpty() }
        if (pieces.isEmpty()) return null
        return pieces.map { parseOne(it) ?: return null }
    }

    private val separators = Regex("""[\s,;]+""")

    private class Point(val book: BookID?, val numbers: List<Int>)

    /** "John.3.16" → (John, [3, 16]); "16" → (null, [16]). Null when it isn't dotted OSIS. */
    private fun point(raw: String): Point? {
        // Grain and sub-identifiers ("!a", "@s[love]") refine a verse; the verse is what we open.
        val trimmed = raw.split('!', '@').firstOrNull { it.isNotEmpty() } ?: ""
        val parts = trimmed.split('.')
        val head = parts.first()
        if (head.isEmpty()) return null
        val number = head.toIntOrNull()
        if (number != null) {
            // A bare end ("-18", "-4.2"): numbers only.
            val numbers = parts.mapNotNull { it.toIntOrNull() }
            if (numbers.size != parts.size || numbers.size > 2 || number <= 0 || numbers.any { it <= 0 }) return null
            return Point(null, numbers)
        }
        val book = book(head) ?: return null
        val rest = parts.drop(1)
        val numbers = rest.mapNotNull { it.toIntOrNull() }
        if (numbers.size != rest.size || numbers.size > 2 || numbers.any { it <= 0 }) return null
        return Point(book, numbers)
    }

    private fun parseOne(piece: String): Passage? {
        val ends = piece.split("-", limit = 2)
        val start = point(ends[0]) ?: return null
        val book = start.book ?: return null

        // Single-chapter books: "Jude.3" is how people write verse 3; OSIS proper says "Jude.1.3".
        var startNumbers = start.numbers
        if (book.isSingleChapter && startNumbers.size == 1 && startNumbers[0] > 1) startNumbers = listOf(1, startNumbers[0])

        val startChapter = startNumbers.firstOrNull() ?: run {
            // A bare book ("Gen") is its first chapter.
            if (ends.size != 1) return null
            return Passage.of(book, 1)
        }
        if (startChapter > book.chapterCount) return null
        val startVerse = if (startNumbers.size > 1) startNumbers[1] else null

        if (ends.size != 2) return Passage.of(book, startChapter, startVerse)
        val end = point(ends[1]) ?: return null
        val endBook = end.book
        if (endBook != null && endBook != book) {
            // Crossing into another book: stop at the end of this one.
            if (endBook < book) return null
            return Passage.of(book, startChapter, startVerse ?: 1, book.chapterCount, null)
        }
        var endNumbers = end.numbers
        if (endBook != null && book.isSingleChapter && endNumbers.size == 1 && endNumbers[0] > 1) endNumbers = listOf(1, endNumbers[0])

        val endChapter: Int
        val endVerse: Int?
        when {
            endNumbers.isEmpty() -> return null
            // "John.3.16-18": a verse in the same chapter.
            startVerse != null && endNumbers.size == 1 && endBook == null -> {
                endChapter = startChapter
                endVerse = endNumbers[0]
            }
            // "John.3-4", "John.3-John.4", "John.3.16-John.4": a chapter.
            endNumbers.size == 1 -> {
                endChapter = endNumbers[0]
                endVerse = null
            }
            else -> {
                endChapter = endNumbers[0]
                endVerse = endNumbers[1]
            }
        }
        if (endChapter < startChapter || endChapter > book.chapterCount) return null
        if (endChapter == startChapter && startVerse != null && endVerse != null && endVerse < startVerse) return null
        if (startVerse == null) {
            // "John.3-John.4.2" starts at verse 1; "John.3-4" stays whole chapters.
            if (endVerse != null) return Passage.of(book, startChapter, 1, endChapter, endVerse)
            return Passage.of(book, startChapter, null, endChapter, null)
        }
        return Passage.of(book, startChapter, startVerse, endChapter, endVerse)
    }
}
