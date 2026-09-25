package com.blainemiller.scripturealone.data.slides

import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.reference.Passage
import com.blainemiller.scripturealone.data.reference.ReferenceDetector
import java.text.Normalizer

/** One line of text recognized on a photographed sermon slide. */
data class SlideLine(
    val text: String,
    /** Normalized to the image (0..1), origin at the TOP-left, y growing downward. */
    val box: Box,
    /** Recognizer confidence, 0..1. */
    val confidence: Double = 1.0,
) {
    constructor(text: String, x: Double, y: Double, width: Double, height: Double, confidence: Double = 1.0) :
        this(text, Box(x, y, width, height), confidence)

    data class Box(val x: Double, val y: Double, val width: Double, val height: Double) {
        val midY: Double get() = y + height / 2
        val maxY: Double get() = y + height
        val maxX: Double get() = x + width
    }
}

/** What a slide says: the sermon title, the passages it cites, and the rest of its lines. */
data class SlideReading(val title: String, val passages: List<Passage>, val bodyLines: List<String>)

/**
 * Turns recognized slide text into a note: the most prominent line becomes the title, every scripture
 * reference becomes an anchor, and the remaining meaningful lines become the body. A port of
 * `ScriptureAloneCore/SlideParser.swift`, tests included, so a slide reads the same on both apps.
 * Pure logic — the recognizer that produces [SlideLine]s (ML Kit here, Vision on iOS) lives in the app.
 */
object SlideParser {

    const val BULLET = "• "

    fun read(rawLines: List<SlideLine>): SlideReading {
        val lines = readingOrder(
            rawLines.filter { it.confidence >= 0.3 }
                .map { it.copy(text = normalize(it.text)) }
                .filter { it.text.isNotEmpty() },
        )

        // References are found in the whole slide, lines joined, so one broken across two lines
        // ("1 Corinthians" / "13:4–7") is still found.
        val joined = StringBuilder()
        val spans = mutableListOf<IntRange>()
        for (line in lines) {
            if (joined.isNotEmpty()) joined.append('\n')
            val start = joined.length
            joined.append(line.text)
            spans += start until joined.length
        }
        val matches = ReferenceDetector.detect(joined.toString())
        val passages = matches.map { it.passage }.distinct()

        // What is left of each line once its references are removed.
        val residuals = spans.mapIndexed { index, span ->
            val cuts = matches.map { it.range }
                .map { maxOf(it.first, span.first)..minOf(it.last, span.last) }
                .filter { !it.isEmpty() }
            if (cuts.isEmpty()) return@mapIndexed lines[index].text
            val text = StringBuilder(lines[index].text)
            for (cut in cuts.sortedByDescending { it.first }) {
                text.replace(cut.first - span.first, cut.last + 1 - span.first, " ")
            }
            stripReferenceLabels(text.toString())
        }

        data class Candidate(val index: Int, val text: String, val line: SlideLine, val isContent: Boolean)

        val candidates = lines.indices.map { Candidate(it, residuals[it], lines[it], isContent(residuals[it])) }

        // Title: the tallest content line, favoring lines nearer the top.
        val titleable = candidates.filter { it.isContent && isTitleLike(it.text) }
        val titleIndices = mutableListOf<Int>()
        val best = titleable.maxByOrNull { titleScore(it.line) }
        if (best != null) {
            titleIndices += best.index
            // Multi-line titles: neighbors of similar height, stacked tightly, overlapping horizontally.
            fun joins(a: SlideLine, b: SlideLine): Boolean {
                val ratio = minOf(a.box.height, b.box.height) / maxOf(a.box.height, b.box.height)
                val gap = maxOf(b.box.y - a.box.maxY, a.box.y - b.box.maxY)
                val overlap = minOf(a.box.maxX, b.box.maxX) - maxOf(a.box.x, b.box.x)
                return ratio > 0.8 && gap < maxOf(a.box.height, b.box.height) * 0.9 && overlap > 0
            }
            var upper = best.index
            while (upper > 0 && titleIndices.size < 3) {
                val previous = titleable.firstOrNull { it.index == upper - 1 } ?: break
                if (!joins(previous.line, lines[upper])) break
                upper -= 1
                titleIndices.add(0, upper)
            }
            var lower = best.index
            while (titleIndices.size < 3) {
                val next = titleable.firstOrNull { it.index == lower + 1 } ?: break
                if (!joins(lines[lower], next.line)) break
                lower += 1
                titleIndices += lower
            }
        }
        val title = cleanTitle(joinLines(titleIndices.map { residuals[it] }))

        val seen = mutableSetOf<String>()
        if (title.isNotEmpty()) seen += foldKey(title)
        val body = mutableListOf<String>()
        for (candidate in candidates) {
            if (!candidate.isContent || candidate.index in titleIndices) continue
            val key = foldKey(candidate.text)
            if (key.isEmpty() || key in seen) continue
            seen += key
            body += candidate.text
        }
        return SlideReading(title, passages, body)
    }

    /** Convenience for plain text (one line per row, no geometry): the first content line is the title. */
    fun read(text: String): SlideReading {
        val rows = text.split('\n', '\r', '', '', '', ' ', ' ').filter { it.isNotEmpty() }
        val count = maxOf(rows.size, 1).toDouble()
        return read(rows.mapIndexed { index, row -> SlideLine(row, 0.1, index / count, 0.8, 0.5 / count) })
    }

    // Ranges

    /**
     * Resolves passages against a translation's verse counts: chapters and verses are clamped to what
     * exists (OCR reads "Psalm 23:1-66" sometimes), and duplicates drop out.
     */
    fun ranges(passages: List<Passage>, verseCount: (book: BookID, chapter: Int) -> Int): List<VerseRange> {
        val result = mutableListOf<VerseRange>()
        for (passage in passages) {
            val p = passage.clamped
            val startCount = verseCount(p.book, p.startChapter)
            val endCount = verseCount(p.book, p.endChapter)
            if (startCount <= 0 || endCount <= 0) continue
            val startVerse = minOf(maxOf(1, p.startVerse ?: 1), startCount)
            val endVerse = minOf(maxOf(1, p.endVerse ?: endCount), endCount)
            val range = VerseRange.of(
                VerseRef(p.book.number, p.startChapter, startVerse),
                VerseRef(p.book.number, p.endChapter, endVerse),
            )
            if (range !in result) result += range
        }
        return result
    }

    /**
     * Adds a slide's ranges to a note's, dropping any range another one already covers ("John 10:11"
     * once "John 10:11–15" is there).
     */
    fun merging(existing: List<VerseRange>, added: List<VerseRange>): List<VerseRange> {
        val all = (existing + added).distinct()
        return all.filter { range ->
            all.none { other -> other != range && other.start.key <= range.start.key && range.end.key <= other.end.key }
        }
    }

    /**
     * Appends slide lines to an existing note body as bullets, skipping lines the body already has (a
     * later slide often repeats the sermon title or the point before it).
     */
    fun append(heading: String?, lines: List<String>, body: String, title: String = ""): String {
        val existing = body.lines().filter { it.isNotEmpty() }.map(::foldKey).toMutableSet()
        if (title.isNotEmpty()) existing += foldKey(title)
        val additions = mutableListOf<String>()
        val trimmedHeading = heading?.let { trimSpaces(it) }
        if (!trimmedHeading.isNullOrEmpty() && foldKey(trimmedHeading) !in existing) additions += trimmedHeading
        for (line in lines) {
            val trimmed = trimSpaces(line)
            if (trimmed.isEmpty() || foldKey(trimmed) in existing || additions.any { foldKey(it) == foldKey(trimmed) }) continue
            additions += BULLET + trimmed
        }
        if (additions.isEmpty()) return body
        val trimmedBody = body.trim()
        val block = additions.joinToString("\n")
        return if (trimmedBody.isEmpty()) block else "$trimmedBody\n\n$block"
    }

    /** Whether a slide line is already in the note (its title or a line of its body) — a running header. */
    fun noteAlreadyHas(line: String, title: String, body: String): Boolean {
        val key = foldKey(line)
        if (key.isEmpty()) return true
        if (key == foldKey(title)) return true
        return body.lines().filter { it.isNotEmpty() }.any { foldKey(it) == key }
    }

    /** The body a new note starts with. */
    fun body(lines: List<String>): String = lines.joinToString("\n") { BULLET + it }

    // Normalizing OCR text

    private val versesWord = Regex("""(\d{1,3})\s*,?\s*\b(?:vv?|verses?|vs)\.?\s*(\d{1,3})""", RegexOption.IGNORE_CASE)
    private val parenJohn = Regex("""\((?:n|In|ln)\.?\s*(?=\d{1,3}\s*:\s*\d)""")
    private val leadingJohn = Regex("""^(?:In|ln)\.?\s+(?=\d{1,3}\s*:\s*\d)""")
    private val semicolonColon = Regex("""(?<=[A-Za-z.]\s?\d{1,3});(?=\d)""")
    private val spaces = Regex("""\s+""")

    internal fun normalize(raw: String): String {
        var s = raw
            .replace('‘', '\'').replace('’', '\'')
            .replace('“', '"').replace('”', '"')
            .replace('‐', '-').replace('‑', '-')
            .replace('‒', '–').replace('−', '-')
            .replace('：', ':').replace(' ', ' ')
        // "Romans 8, vv. 1-17" / "Romans 8 verses 1–17" → "Romans 8:1-17"
        s = versesWord.replace(s, "$1:$2")
        // "(Jn 10:3)" often loses its J to the parenthesis ("(n 10:3)"), and a J at the start of a
        // line reads as "In". Only with chapter:verse after it — "in 3 ways" stays as it is.
        s = parenJohn.replace(s, "(Jn ")
        s = leadingJohn.replace(s, "Jn ")
        // OCR reads the colon of "3:16" as a semicolon when there is no space around it.
        s = semicolonColon.replace(s, ":")
        s = spaces.replace(s, " ")
        return trimSpaces(s)
    }

    private val referenceLabels = Regex(
        """(?i)\b(?:scriptures?|texts?|readings?|passages?|key verses?|focal passage|see also|also|and|cf)\b\s*:?""",
    )

    /** "Text: " / "Scripture —" / "·" / "&" left behind once references are cut out. */
    internal fun stripReferenceLabels(text: String): String {
        val s = trimSeparators(referenceLabels.replace(text, " "))
        // Only separators or a stray letter left: the line was all references.
        return if (s.count { it.isLetter() } < 2) "" else s
    }

    private val danglingSeparator = Regex("""\s*[·•|;,&/]\s*(?=[·•|;,&/]|$)""")
    private val emptyBrackets = Regex("""\(\s*\)|\[\s*\]""")
    private const val EDGE_CHARACTERS = "·•|;,:&/-–—("

    internal fun trimSeparators(text: String): String {
        var s = danglingSeparator.replace(text, "")
        s = emptyBrackets.replace(s, "")
        s = spaces.replace(s, " ")
        fun isEdge(c: Char) = isSpace(c) || c in EDGE_CHARACTERS
        s = s.trim(::isEdge)
        if (s.endsWith(")") && !s.contains("(")) s = s.dropLast(1)
        return s.trim(::isEdge)
    }

    // Classifying lines

    private val noiseLink = Regex("""https?://|www\.|\.(com|org|net|church|tv|io)\b|@[a-z0-9_]{2,}|#[a-z]{3,}""")
    private val noiseLicense = Regex("""\bccli\b|copyright|©|all rights reserved|license\s*#|streaming license""")
    private val noiseGreeting = Regex(
        """^(welcome|welcome home|welcome to\b.*|good morning|glad you'?re here|please silence.*|let'?s worship|let us pray|announcements?|offering|giving|prayer requests?|connect card.*|wi-?fi.*|text .* to \d+)[!. ]*$""",
    )
    private val slideNumber = Regex("""^(slide|page)?\s*\d+\s*(/|of)\s*\d+$""")

    private fun isContent(text: String): Boolean {
        val letters = text.count { it.isLetter() }
        // A Chinese, Japanese or Korean word is whole in two or three characters (恩典, 好牧人,
        // 선한 목자), so the length floors below that suit Latin text would throw a title away.
        val cjk = text.codePoints().anyMatch { it in 0x3040..0x30FF || it in 0x3400..0x9FFF || it in 0xAC00..0xD7AF }
        if (letters < (if (cjk) 2 else 3)) return false
        val lower = text.lowercase()
        if (noiseLink.containsMatchIn(lower)) return false
        if (noiseLicense.containsMatchIn(lower)) return false
        if (noiseGreeting.containsMatchIn(lower)) return false
        if (isDateOrTime(lower)) return false
        if (slideNumber.containsMatchIn(lower)) return false
        if (isChurchName(text)) return false
        if (!cjk && letters < 4 && characterCount(text) <= 4) return false
        return true
    }

    private const val MONTHS = "jan(uary)?|feb(ruary)?|mar(ch)?|apr(il)?|may|june?|july?|aug(ust)?|sep(t|tember)?|oct(ober)?|nov(ember)?|dec(ember)?"
    private const val WEEKDAYS = "sun(day)?|mon(day)?|tue(s|sday)?|wed(nesday)?|thu(rs|rsday)?|fri(day)?|sat(urday)?"

    private val dateTokens = listOf(
        """\b($WEEKDAYS)\b,?""",
        """\b($MONTHS)\.?\s+\d{1,2}(st|nd|rd|th)?,?(\s+\d{4})?""",
        """\b\d{1,2}(st|nd|rd|th)?\s+($MONTHS)\.?(\s+\d{4})?""",
        """\b\d{1,2}[/.-]\d{1,2}([/.-]\d{2,4})?\b""",
        """\b\d{1,2}(:\d{2})?\s*(am|pm|a\.m\.|p\.m\.)""",
        """\b(19|20)\d{2}\b""",
        """\b(morning|evening)\s+(worship|service)\b""",
        """\b(at|on|the|of)\b""",
    ).map(::Regex)
    private val anyDate = Regex(dateTokens.take(5).joinToString("|") { it.pattern })
    private val nonLetters = Regex("""[^\p{L}]+""")

    private fun isDateOrTime(lower: String): Boolean {
        // Strip every date/time token; if (almost) nothing is left, the line was a date.
        if (!anyDate.containsMatchIn(lower)) return false
        var s = lower
        for (token in dateTokens) s = token.replace(s, " ")
        // A single leftover word is usually a clipped weekday ("unday, September 14") or a label
        // ("Easter"); anything longer is a real sentence that mentions a date.
        val leftover = s.split(nonLetters).filter { characterCount(it) > 1 }
        return s.count { it.isLetter() } < 3 || (leftover.size == 1 && characterCount(leftover[0]) <= 9)
    }

    private const val PLACES = "(church|chapel|fellowship|cathedral|parish|ministries|tabernacle|congregation|assembly|basilica|kirk)"
    private val placeAtEnd = Regex("""\b$PLACES$""")
    private val placeOf = Regex("""\b$PLACES (of|at|in|on)\b""")
    private val startsWithChurch = Regex("""^(the )?(church|chapel)""")
    private val saintDenomination =
        Regex("""^(st\.?|saint) [a-z']+('s)?\b.*\b(lutheran|anglican|episcopal|catholic|presbyterian|methodist|baptist|orthodox)""")

    private fun isChurchName(text: String): Boolean {
        if (text.split(' ').filter { it.isNotEmpty() }.size > 7) return false
        val lower = text.lowercase()
        if (placeAtEnd.containsMatchIn(lower)) return true
        if (placeOf.containsMatchIn(lower) && !startsWithChurch.containsMatchIn(lower)) return true
        return saintDenomination.containsMatchIn(lower)
    }

    private val notTitles = listOf(
        """^(pastor|pr\.|rev\.?|reverend|dr\.?|father|fr\.|elder|bishop|speaker|preacher|guest speaker)\b""",
        """^(week|part|session|message)\s+\d+\b""",
        """^(series|sermon series)\s*:""",
        """^\d+[.)]\s""",
    ).map(::Regex)

    /** Speaker bylines, series labels and the like can still be body lines, but not the title. */
    private fun isTitleLike(text: String): Boolean {
        val lower = text.lowercase()
        if (notTitles.any { it.containsMatchIn(lower) }) return false
        return text.split(' ').filter { it.isNotEmpty() }.size <= 14
    }

    /** Height dominates; the top half of the slide gets a nudge. */
    private fun titleScore(line: SlideLine): Double = line.box.height * (1.15 - 0.3 * line.box.midY.coerceIn(0.0, 1.0))

    // Titles

    private fun joinLines(parts: List<String>): String {
        val result = StringBuilder()
        for (part in parts) {
            if (part.isEmpty()) continue
            if (result.endsWith("-") && part.first().isLowerCase()) {
                result.setLength(result.length - 1)
                result.append(part)
            } else {
                if (result.isNotEmpty()) result.append(' ')
                result.append(part)
            }
        }
        return result.toString()
    }

    private val titleLabel = Regex("""(?i)^(sermon|message|title|today'?s message|this week)\s*:\s*""")

    internal fun cleanTitle(raw: String): String {
        var s = trimSpaces(titleLabel.replace(raw, ""))
        if (characterCount(s) > 2 && s.first() in "\"'" && s.last() in "\"'") {
            s = trimSpaces(s.substring(1, s.length - 1))
        }
        val letters = s.filter { it.isLetter() }
        if (letters.length >= 4 && letters.all { it.isUpperCase() }) s = titleCase(s)
        return s
    }

    private val minorWords = setOf(
        "a", "an", "and", "as", "at", "but", "by", "for", "in", "nor", "of", "on", "or", "the", "to", "up", "with",
    )
    private val romanNumeral = Regex("""^(i|ii|iii|iv|v|vi|vii|viii|ix|x)[:.]?$""")

    internal fun titleCase(text: String): String {
        val words = text.lowercase().split(' ')
        return words.mapIndexed { index, word ->
            if (index > 0 && index < words.size - 1 && word in minorWords) return@mapIndexed word
            // Roman numerals in series titles stay as they are.
            if (index > 0 && romanNumeral.containsMatchIn(word)) return@mapIndexed word.uppercase()
            val first = word.indexOfFirst { it.isLetter() }
            if (first < 0) word else word.substring(0, first) + word[first].uppercase() + word.substring(first + 1)
        }.joinToString(" ")
    }

    // Helpers

    private val combiningMarks = Regex("""\p{Mn}+""")
    private val leadingBullet = Regex("""^\s*(•|-|\*)\s*""")

    /** Case- and diacritic-insensitive, letters and digits only — Swift's `folding` then a filter. */
    private fun foldKey(text: String): String {
        val folded = combiningMarks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase()
        return leadingBullet.replace(folded, "").filter { it.isLetterOrDigit() }
    }

    /** Top-to-bottom rows, left-to-right within a row. */
    internal fun readingOrder(lines: List<SlideLine>): List<SlideLine> {
        val rows = mutableListOf<MutableList<SlideLine>>()
        for (line in lines.sortedBy { it.box.midY }) {
            val last = rows.lastOrNull()?.last()
            if (last != null && Math.abs(last.box.midY - line.box.midY) < minOf(last.box.height, line.box.height) * 0.5) {
                rows.last() += line
            } else {
                rows += mutableListOf(line)
            }
        }
        return rows.flatMap { row -> row.sortedBy { it.box.x } }
    }

    /** Swift's `CharacterSet.whitespaces`: spaces and tabs, not newlines. */
    private fun isSpace(c: Char) = c == '\t' || Character.getType(c) == Character.SPACE_SEPARATOR.toInt()

    private fun trimSpaces(text: String) = text.trim(::isSpace)

    /** Grapheme clusters, as Swift's `String.count`. */
    private fun characterCount(text: String): Int {
        val breaks = java.text.BreakIterator.getCharacterInstance()
        breaks.setText(text)
        var count = 0
        while (breaks.next() != java.text.BreakIterator.DONE) count++
        return count
    }
}
