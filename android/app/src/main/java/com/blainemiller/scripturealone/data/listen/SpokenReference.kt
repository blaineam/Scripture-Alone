package com.blainemiller.scripturealone.data.listen

import com.blainemiller.scripturealone.data.reference.Passage
import com.blainemiller.scripturealone.data.reference.ReferenceParser

/**
 * Speech recognition writes "John 3:16" as "John 3 16", "John 3 colon 16" or "John chapter 3 verse 16 through 21".
 * This puts the punctuation back so the reference parser can read it (the counterpart of iOS `SpokenReference`).
 * English words; other languages' speech usually gives digits and the parser's own separators.
 *
 * A car's assistant hands over more than the reference: "read John chapter 1 in Scripture Alone" arrives with its verb and
 * the app's name still on it (or only some of it, depending on how the assistant split the phrase), the numbers may be
 * words ("Psalm twenty three"), and the book may be asked for as "the book of Romans". [clean] takes all of that off; [plan]
 * is what the player acts on.
 */
object SpokenReference {
    /** What a voice request asks the player to do. */
    sealed interface Request {
        data object Today : Request
        /** "Continue", "resume", or a request with nothing to read in it ("read something"): the reader's place. */
        data object Resume : Request
        data class Read(val passages: List<Passage>) : Request
        data object Unknown : Request
    }

    /** True when the words ask for the day's reading in the reader's plan rather than a passage. */
    fun asksForToday(heard: String): Boolean {
        val lower = heard.lowercase()
        return "today" in lower || "reading plan" in lower || "daily reading" in lower
    }

    /** True when the words ask to carry on where the reader left off, or ask for nothing in particular. */
    fun asksToResume(heard: String): Boolean {
        val said = clean(heard)
        return said.isEmpty() || said in nothingInParticular || resumeWords.containsMatchIn(heard.lowercase())
    }

    /**
     * Decides what a voice request is, from every phrase the assistant gave for it (the search query, then the structured
     * title / album / playlist it may also fill in): today's reading, a passage, the reader's place, or none of those.
     */
    fun plan(candidates: List<String>, parse: (String) -> List<Passage> = { ReferenceParser.parseList(it) }): Request {
        val phrases = candidates.map(String::trim).filter(String::isNotEmpty)
        if (phrases.any(::asksForToday)) return Request.Today
        for (phrase in phrases) {
            val said = clean(phrase)
            if (said.isEmpty()) continue
            val passages = parse(normalize(phrase))
            if (passages.isEmpty()) continue
            // "Read Romans" is the whole book, not its first chapter (the parser's reading of a bare book name).
            val wholeBook = passages.size == 1 && said.none(Char::isDigit)
            return Request.Read(if (wholeBook) passages.map(::wholeBook) else passages)
        }
        return if (phrases.isEmpty() || phrases.any(::asksToResume)) Request.Resume else Request.Unknown
    }

    private fun wholeBook(p: Passage): Passage = Passage.of(p.book, 1, endChapter = p.book.chapterCount)

    /** "John 3:16" from what was heard: the verb, the app's name and filler gone, numbers as digits, punctuation put back. */
    fun normalize(heard: String): String {
        fun isNumber(s: String) = s.isNotEmpty() && s.all(Char::isDigit)
        val words = clean(heard).replace(".", " . ").replace(":", " : ").replace("-", " - ")
            .split(" ").filter { it.isNotEmpty() && it != "chapter" && it != "chapters" }
        val tokens = mutableListOf<String>()
        words.forEachIndexed { i, word ->
            val between = i > 0 && i + 1 < words.size && isNumber(words[i - 1]) && isNumber(words[i + 1])
            when (word) {
                "colon", "verse", "verses" -> tokens += ":"
                "." -> if (between) tokens += ":"
                "through", "thru", "to", "until", "til" -> tokens += if (between) "-" else word
                else -> tokens += word
            }
        }
        fun joined(list: List<String>) = list.joinToString(" ").replace(" : ", ":").replace(" - ", "-")
        var start = tokens.size
        while (start > 0 && (isNumber(tokens[start - 1]) || tokens[start - 1] == "-")) start--
        if (start == 0) return joined(tokens)
        val tail = tokens.subList(start, tokens.size)
        val dash = tail.indexOf("-")
        val left = if (dash < 0) tail else tail.subList(0, dash)
        val right = if (dash < 0) emptyList() else tail.subList(dash + 1, tail.size)
        if ((left.size != 2 && left.size != 3) || right.size > 1) return joined(tokens)
        val verses = if (left.size == 3) "${left[1]}-${left[2]}" else left[1]
        val end = if (right.size == 1) "-${right[0]}" else ""
        return joined(tokens.subList(0, start) + "${left[0]}:$verses$end")
    }

    /**
     * What was heard with the request's own words taken off: lowercased, the wake phrase, "read / play / listen to …" and
     * "… in Scripture Alone" gone, "the book of / the gospel of / the letter to the" gone, "chapter 3 of John" turned round,
     * and number words ("twenty three", "one hundred fifty") written as digits.
     */
    fun clean(heard: String): String {
        var s = heard.lowercase().replace("’", "'").replace(Regex("""[?!"]"""), " ").replace("'s ", " ")
        s = appName.replace(s, " ")
        s = hyphenatedNumber.replace(s, "$1 $2")
        s = s.replace(Regex("""\s+"""), " ").trim()
        // Verbs and filler off the front, again until nothing more comes off ("hey google, please read …").
        while (true) {
            val before = s
            for (r in leading) s = r.replace(s, "").trim()
            if (s == before) break
        }
        s = trailing.replace(s, "").trim()
        s = ordinalBook.replace(s) { "${it.groupValues[1]} " }
        s = bookOf.replace(s, "").trim()
        s = resumeWords.replace(s, " ").trim()
        s = numbersAsDigits(s)
        s = chapterOfBook.replace(s) { "${it.groupValues[2]} ${it.groupValues[1]}" }
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    /** Words that ask for no passage in particular — what is left of "read something in Scripture Alone". */
    private val nothingInParticular = setOf(
        "something", "anything", "some scripture", "some scriptures", "something to read", "the bible", "bible", "scripture",
        "scriptures", "chapter", "passage", "verse", "some bible", "word", "something from the bible",
    )
    private val resumeWords = Regex("""\b(?:continue|resume|keep reading|carry on|pick up|where i (?:left off|was|stopped)|left off)(?:\s+reading)?\b""")

    private val appName = Regex("""\b(?:(?:in|on|with|using|from|via|through|within)\s+)?(?:the\s+)?scriptures?\s+alone(?:\s+app)?\b""")
    private val hyphenatedNumber = Regex("""\b(twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)-(one|two|three|four|five|six|seven|eight|nine)\b""")
    private val leading = listOf(
        Regex("""^(?:hey|ok|okay)\s+google\b[,\s]*"""),
        Regex("""^(?:please|kindly)\b[,\s]*"""),
        Regex("""^(?:can|could|would|will)\s+you\b[,\s]*"""),
        Regex("""^i(?:'d| would)?\s+(?:like|want)\s+(?:you\s+)?to\b[,\s]*"""),
        Regex("""^(?:let's|lets|let us)\s+"""),
        Regex("""^go\s+ahead\s+and\s+"""),
        Regex("""^(?:read|play|listen\s+to|hear|open|start|begin|show|queue|find|navigate\s+to|go\s+to|put\s+on|say|recite|speak)\b(?:\s+(?:me|to\s+me|for\s+me|out\s+loud|aloud))*\s*"""),
        Regex("""^(?:the|a|an|my)\s+(?=\S)"""),
    )
    private val trailing = Regex("""\s*(?:out\s+loud|aloud|please|for\s+me|to\s+me|now)\s*$""")
    /** "the first book of Samuel" → "first samuel"; "second letter to the Corinthians" → "second corinthians". */
    private val ordinalBook = Regex("""\b(first|second|third|1st|2nd|3rd|1|2|3)\s+(?:book|letter|epistle)\s+(?:of|to)\s+(?:the\s+)?(?:st\.?\s+|saint\s+)?""")
    /** "the book of Romans", "the gospel according to John", "the letter to the Hebrews" → the name alone. */
    private val bookOf = Regex("""\b(?:the\s+)?(?:books?|gospel|epistle|letter)\s+(?:of|according\s+to|to)\s+(?:the\s+)?(?:st\.?\s+|saint\s+)?""")
    /** "chapter 3 of John" → "john 3" (the digits are in by then). */
    private val chapterOfBook = Regex("""^chapter\s+(\d+)\s+(?:of|in)\s+(.+)$""")

    private val units = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    ).withIndex().associate { (n, w) -> w to n }
    private val tens = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)

    /**
     * Number words as digits: "twenty three" → 23, "one hundred fifty" / "a hundred and fifty" → 150, "john one" → "john 1". Two
     * numbers in a row stay two ("john three sixteen" → 3 16, which [normalize] makes 3:16), so "psalm one fifty" is read
     * as 1 and 50 — say "a hundred and fifty".
     */
    internal fun numbersAsDigits(text: String): String {
        val words = text.split(" ").filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        var i = 0
        fun word(at: Int) = words.getOrNull(at)
        while (i < words.size) {
            val w = words[i]
            // "a hundred", "one hundred", "two hundred and five".
            val hundreds = when {
                w == "a" && word(i + 1) == "hundred" -> 100 to 2
                units[w]?.let { it in 1..9 } == true && word(i + 1) == "hundred" -> units.getValue(w) * 100 to 2
                else -> null
            }
            if (hundreds != null) {
                var value = hundreds.first
                var used = hundreds.second
                if (word(i + used) == "and") used++
                val restValue = word(i + used)?.let { tens[it] ?: units[it]?.takeIf { n -> n in 1..19 } }
                if (restValue != null) {
                    value += restValue; used++
                    val unit = word(i + used)?.let { units[it] }?.takeIf { it in 1..9 }
                    if (restValue in 20..90 && unit != null) { value += unit; used++ }
                } else if (word(i + used - 1) == "and") used--
                out += value.toString(); i += used
                continue
            }
            val ten = tens[w]
            if (ten != null) {
                val unit = word(i + 1)?.let { units[it] }?.takeIf { it in 1..9 }
                if (unit != null) { out += (ten + unit).toString(); i += 2 } else { out += ten.toString(); i++ }
                continue
            }
            val unit = units[w]
            if (unit != null) { out += unit.toString(); i++; continue }
            out += w; i++
        }
        return out.joinToString(" ")
    }
}
