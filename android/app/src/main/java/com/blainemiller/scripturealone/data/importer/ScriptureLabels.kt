package com.blainemiller.scripturealone.data.importer

import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.reference.ReferenceParser

/**
 * Reading book names and references out of the strings publishers actually ship: chapter headings
 * ("The Gospel According to St. John", "PSALM 23") and element ids ("ABC_Gen.1.1", "xyz-Gen-1-1",
 * "v12"). Ported from `Import/ScriptureLabels.swift`.
 *
 * Book matching goes through the canon and abbreviation tables the app already has ([BookID],
 * [ReferenceParser]), so an ePub spelling the reader already understands in the search bar is
 * understood here too.
 */
internal object ScriptureLabels {
    /** What an element id turned out to name. */
    sealed class Identifier {
        data class Verse(val book: BookID, val chapter: Int, val verse: Int) : Identifier()
        data class Chapter(val book: BookID, val chapter: Int) : Identifier()

        /** `id="v3"` — a verse number with no book or chapter of its own. */
        data class VerseNumber(val verse: Int) : Identifier()

        /** `id="ch2"` — a chapter number with no book of its own. */
        data class ChapterNumber(val chapter: Int) : Identifier()
    }

    /** A place a heading or file name names: a book, a chapter number, or both. */
    data class Place(val book: BookID?, val chapter: Int?)

    /** Words that decorate a book title without naming it. */
    private val noise: Set<String> = setOf(
        "the", "a", "book", "books", "gospel", "gospels", "according", "st", "saint", "holy",
        "bible", "epistle", "epistles", "letter", "letters", "general", "paul", "pauls", "apostle",
        "apostles", "jesus", "christ", "unto", "by", "chapter", "chapters",
    )

    private val verseWords: Set<String> = setOf("v", "vs", "ver", "vers", "verse", "versenum", "verseno")
    private val chapterWords: Set<String> = setOf("c", "ch", "chap", "chapter", "chapternum")

    // MARK: - Headings

    /**
     * A chapter heading: the book it names, the chapter number it carries, or both. Returns null when
     * the text is a section heading ("The Creation of the World") rather than a book or chapter title.
     */
    fun heading(raw: String): Place? {
        val tokens = normalizedTokens(raw).toMutableList()
        if (tokens.isEmpty() || tokens.size > 12) return null

        var chapter: Int? = null
        val last = tokens.last()
        val value = if (SwiftText.allNumbers(last)) SwiftText.int(last) else null
        if (value != null && value > 0 && value <= 200) {
            // A leading ordinal ("1 John") is part of the name, not a chapter number.
            if (tokens.size > 1 || SwiftText.allNumbers(SwiftText.trimWhitespaceAndNewlines(raw))) {
                chapter = value
                tokens.removeAt(tokens.size - 1)
            }
        }
        val book = book(tokens) ?: return chapter?.let { Place(null, it) }
        return Place(book, chapter)
    }

    /**
     * The book a run of words names, trying the phrase as written and again with the connecting words
     * removed, so both "Song of Solomon" and "First Epistle of John" resolve.
     *
     * The match must account for every word that is left after the decoration is stripped. That is what
     * keeps a section heading like "Job’s Complaint" from being read as the book of Job.
     */
    fun book(tokens: List<String>): BookID? {
        val cleaned = tokens.filter { it !in noise }
        if (cleaned.isEmpty()) return null
        val candidates = listOf(cleaned, cleaned.filter { it != "of" && it != "to" })
        var best: Pair<BookID, Int>? = null
        for (candidate in candidates) {
            val match = longestMatch(candidate) ?: continue
            if (match.second != candidate.size) continue
            if (best == null || match.second > best.second) best = match
        }
        if (best != null) return best.first
        // Last resort: the app's own prefix matching, which knows every abbreviation the search bar
        // accepts. Kept narrow so prose headings do not become books.
        if (cleaned.size > 2 || SwiftText.characterCount(cleaned.last()) < 3) return null
        val joined = ReferenceParser.normalizeOrdinals(cleaned.joinToString(" "))
        return ReferenceParser.books(joined).firstOrNull()
    }

    /** The longest run of adjacent words that is exactly a book name or abbreviation. */
    private fun longestMatch(tokens: List<String>): Pair<BookID, Int>? {
        var best: Pair<BookID, Int>? = null
        for (start in tokens.indices) {
            var end = tokens.size
            while (end > start) {
                val width = end - start
                if (best != null && width <= best.second) break
                val phrase = ReferenceParser.normalizeOrdinals(tokens.subList(start, end).joinToString(" "))
                val book = exactBook(BookID.normalize(phrase))
                if (book != null) {
                    best = book to width
                    break
                }
                end--
            }
        }
        return best
    }

    /** Exact alias or USFM code — never a prefix, so "the" cannot become Titus. */
    fun exactBook(token: String): BookID? {
        if (SwiftText.characterCount(token) < 2) return null
        BookID.entries.firstOrNull { token in it.aliases }?.let { return it }
        return BookID.entries.firstOrNull { it.code.lowercase() == token }
    }

    /** A book (and chapter) named by a file name: "gen01.xhtml", "01_Genesis.xhtml", "Genesis.xhtml". */
    fun fileStem(stem: String): Place? {
        identifier(stem)?.let { parsed ->
            return when (parsed) {
                is Identifier.Verse -> Place(parsed.book, parsed.chapter)
                is Identifier.Chapter -> Place(parsed.book, parsed.chapter)
                is Identifier.ChapterNumber -> Place(null, parsed.chapter)
                is Identifier.VerseNumber -> null
            }
        }
        val tokens = normalizedTokens(stem).toMutableList()
        // A zero-padded or large leading number is a sort key ("01_Genesis"); a bare 1, 2 or 3 is an
        // ordinal that belongs to the name ("1_John").
        while (tokens.isNotEmpty()) {
            val first = tokens.first()
            if (!SwiftText.allNumbers(first)) break
            if (!(SwiftText.characterCount(first) > 1 || (SwiftText.int(first) ?: 0) > 3)) break
            tokens.removeAt(0)
        }
        if (tokens.isEmpty()) return null
        return heading(tokens.joinToString(" "))
    }

    fun normalizedTokens(raw: String): List<String> {
        val lowered = raw.lowercase()
        val characters = SwiftCharacters(lowered)
        val token = StringBuilder()
        val tokens = ArrayList<String>()
        for (i in 0 until characters.count) {
            if (characters.isLetter(i) || characters.isNumber(i)) {
                characters.appendTo(token, i)
            } else if (token.isNotEmpty()) {
                tokens.add(token.toString())
                token.setLength(0)
            }
        }
        if (token.isNotEmpty()) tokens.add(token.toString())
        return tokens
    }

    // MARK: - Identifiers

    /**
     * Reads an element id. Handles `ABC_Gen.1.1`, `xyz-Gen-1-1`, `MAT.5.3`, `1Cor.13.4`, `Gen.1`,
     * `v12`, `verse-3`, `ch2`.
     */
    fun identifier(raw: String): Identifier? {
        val parts = idComponents(raw)
        if (parts.size < 2) return null

        fun bookToken(index: Int): BookID? {
            if (index < 0 || index >= parts.size || parts[index].isDigits) return null
            val word = parts[index].text
            if (index > 0) {
                val previous = parts[index - 1]
                val ordinal = SwiftText.int(previous.text)
                if (previous.isDigits && SwiftText.characterCount(previous.text) == 1 && ordinal != null && ordinal in 1..3) {
                    exactBook(previous.text + word)?.let { return it }
                }
            }
            return exactBook(word)
        }

        val last = parts[parts.size - 1]
        val lastValue = SwiftText.int(last.text)
        if (!last.isDigits || lastValue == null || lastValue <= 0 || lastValue >= 1000) return null

        if (parts.size >= 3 && parts[parts.size - 2].isDigits) {
            val chapter = SwiftText.int(parts[parts.size - 2].text)
            if (chapter != null && chapter > 0 && chapter < 1000) {
                bookToken(parts.size - 3)?.let { return Identifier.Verse(it, chapter, lastValue) }
            }
        }
        if (parts.size >= 2 && !parts[parts.size - 2].isDigits) {
            val word = parts[parts.size - 2].text
            bookToken(parts.size - 2)?.let { return Identifier.Chapter(it, lastValue) }
            if (word in verseWords) return Identifier.VerseNumber(lastValue)
            if (word in chapterWords) return Identifier.ChapterNumber(lastValue)
        }
        return null
    }

    data class IDComponent(val text: String, val isDigits: Boolean)

    /** Splits on separators and on letter/digit boundaries: "ABC_1Cor.13.4" → ABC, 1, Cor, 13, 4. */
    fun idComponents(raw: String): List<IDComponent> {
        val parts = ArrayList<IDComponent>()
        val current = StringBuilder()
        var currentIsDigits = false
        fun flush() {
            if (current.isNotEmpty()) parts.add(IDComponent(current.toString(), currentIsDigits))
            current.setLength(0)
        }
        val characters = SwiftCharacters(raw.lowercase())
        for (i in 0 until characters.count) {
            if (characters.isNumber(i)) {
                if (current.isNotEmpty() && !currentIsDigits) flush()
                currentIsDigits = true
                characters.appendTo(current, i)
            } else if (characters.isLetter(i)) {
                if (current.isNotEmpty() && currentIsDigits) flush()
                currentIsDigits = false
                characters.appendTo(current, i)
            } else {
                flush()
            }
        }
        flush()
        return parts
    }
}
