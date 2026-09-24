package com.blainemiller.scripturealone.data.importer

import com.blainemiller.scripturealone.data.VerseRef

/**
 * Words of Christ for a translation whose file doesn't mark them, carried over word by word from one
 * that does. Ported from `Import/RedLetterInference.swift`.
 *
 * Each verse is aligned with the same verse in a reference translation (a longest common subsequence
 * of its words). A word lines up with a red word, or sits between two words that do, and is marked
 * red. A verse that barely aligns — another language, a free rendering — gets nothing: guessing wrong
 * would put words in Christ's mouth. This is the rule `Tools/build_bibles.py` uses to give one bundled
 * text another's red letters.
 */
internal object RedLetterInference {
    /** A word, by its Unicode-scalar offsets ([start] inclusive, [end] exclusive), lowercased. */
    class Token(val start: Int, val end: Int, val word: String)

    fun tokens(text: String): List<Token> {
        val tokens = ArrayList<Token>()
        var start = -1
        val word = StringBuilder()
        var offset = 0
        var index = 0
        while (index < text.length) {
            val cp = text.codePointAt(index)
            val wordy = SwiftText.isLetter(cp) || SwiftText.isNumber(cp)
            if (wordy || ((cp == '\''.code || cp == 0x2019) && start >= 0)) {
                if (start < 0) start = offset
                if (wordy) word.appendCodePoint(cp)
            } else if (start >= 0) {
                tokens.add(Token(start, offset, word.toString().lowercase()))
                start = -1
                word.setLength(0)
            }
            offset++
            index += Character.charCount(cp)
        }
        if (start >= 0) tokens.add(Token(start, offset, word.toString().lowercase()))
        return tokens
    }

    /** Red spans for [target], from [reference] and its red spans; empty when the two don't align. */
    fun redSpans(target: String, reference: String, referenceRed: List<ScalarSpan>): List<ScalarSpan> {
        if (referenceRed.isEmpty()) return emptyList()
        val theirs = tokens(reference)
        val ours = tokens(target)
        if (theirs.isEmpty() || ours.isEmpty()) return emptyList()
        val red = theirs.map { token -> referenceRed.any { it.start < token.end && it.start + it.length > token.start } }
        if (true !in red) return emptyList()

        // Longest common subsequence of words.
        val n = ours.size
        val m = theirs.size
        val table = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                table[i][j] = if (ours[i].word == theirs[j].word) table[i + 1][j + 1] + 1 else maxOf(table[i + 1][j], table[i][j + 1])
            }
        }
        val aligned = arrayOfNulls<Int>(n)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            if (ours[i].word == theirs[j].word) {
                aligned[i] = j
                i++
                j++
            } else if (table[i + 1][j] >= table[i][j + 1]) {
                i++
            } else {
                j++
            }
        }
        // Too little in common to say which words are whose.
        val matched = aligned.count { it != null }
        if (matched < n * 0.5) return emptyList()

        val alignedRed: List<Boolean?> = aligned.map { it?.let { index -> red[index] } }
        val ourRed = alignedRed.toMutableList()
        for (index in 0 until n) {
            if (ourRed[index] != null) continue
            // Swift reads the neighbours from the array as it is filled in, so a gap takes its left
            // neighbour's answer once decided.
            val before = ourRed.subList(0, index).lastOrNull { it != null }
            val after = ourRed.subList(index + 1, n).firstOrNull { it != null }
            ourRed[index] = before == true && after == true
        }

        val spans = ArrayList<ScalarSpan>()
        var runStart = -1
        var runEnd = 0
        for ((index, token) in ours.withIndex()) {
            if (ourRed[index] == true) {
                if (runStart < 0) runStart = token.start
                runEnd = token.end
            } else if (runStart >= 0) {
                spans.add(ScalarSpan(runStart, runEnd - runStart))
                runStart = -1
            }
        }
        if (runStart >= 0) spans.add(ScalarSpan(runStart, runEnd - runStart))
        // A closing mark straight after the last red word belongs to what was said ("life.").
        val scalars = target.codePoints().toArray()
        return spans.map { span ->
            var end = span.start + span.length
            while (end < scalars.size && scalars[end] in closingMarks) end++
            ScalarSpan(span.start, end - span.start)
        }
    }

    private val closingMarks = ".,;:!?".map { it.code }.toSet()

    /**
     * Each of [target]'s words, with whether the reference word it lines up with is red — null for a
     * word that lines up with nothing. `matched` is the share of words that line up.
     */
    class Alignment(val tokens: List<Token>, val red: List<Boolean?>, val matched: Double)

    fun alignment(target: String, reference: String, referenceRed: List<ScalarSpan>): Alignment {
        val theirs = tokens(reference)
        val ours = tokens(target)
        if (theirs.isEmpty() || ours.isEmpty()) return Alignment(ours, List(ours.size) { null }, 0.0)
        val red = theirs.map { token -> referenceRed.any { it.start < token.end && it.start + it.length > token.start } }
        val n = ours.size
        val m = theirs.size
        val table = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                table[i][j] = if (ours[i].word == theirs[j].word) table[i + 1][j + 1] + 1 else maxOf(table[i + 1][j], table[i][j + 1])
            }
        }
        val result = arrayOfNulls<Boolean>(n)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            if (ours[i].word == theirs[j].word) {
                result[i] = red[j]
                i++
                j++
            } else if (table[i + 1][j] >= table[i][j + 1]) {
                i++
            } else {
                j++
            }
        }
        return Alignment(ours, result.toList(), result.count { it != null }.toDouble() / n)
    }

    // MARK: Quotations

    /** One stretch of a verse a quotation covers: scalar offsets [start] (inclusive) to [end] (exclusive). */
    data class Part(val verse: VerseRef, val start: Int, val end: Int)

    /**
     * One quotation in the target's own punctuation: the stretch of each verse it covers, marks
     * included, in reading order. A quotation can run for chapters (the Sermon on the Mount).
     */
    class Quotation {
        val parts: MutableList<Part> = ArrayList()

        /** Speech quoted inside it, one level down — Paul telling what the Lord said to him. */
        val inner: MutableList<Quotation> = ArrayList()
    }

    private enum class Mark { DOUBLE, SINGLE }

    /** Foundation `CharacterSet.letters`: the L and M general categories. */
    private fun isLetterOrMark(cp: Int): Boolean = Character.isLetter(cp) || when (Character.getType(cp)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    /**
     * The target's top-level quotations. English nests speech by alternating marks — “He said,
     * ‘They answered, “…”’” — so the marks are followed as a stack: a single mark opens a level inside
     * a quotation, a double mark inside a single one opens a deeper level, and only the double mark
     * that closes the outermost level ends the quotation. A paragraph that goes on speaking reopens the
     * quotation without closing it, so an opening double mark at the start of a verse continues it (and
     * ends any nesting left open); one mid-verse, with a quotation still open, means a closing mark was
     * missed, and starts a new quotation. A ’ between two letters is an apostrophe. A quotation still
     * open at a chapter whose first words don't reopen it is closed there, so one missed mark can't run
     * away with the book.
     */
    fun quotations(verses: List<Pair<VerseRef, String>>): List<Quotation> {
        val result = ArrayList<Quotation>()
        var open: Quotation? = null
        var stack = ArrayList<Mark>()
        var partStart: Int? = null
        var nested: Quotation? = null
        var nestedStart: Int? = null
        var chapter: Pair<Int, Int>? = null
        fun close(ref: VerseRef, end: Int) {
            val quotation = open ?: return
            val start = partStart ?: return
            quotation.parts.add(Part(ref, start, end))
            nested?.let { if (it.parts.isNotEmpty()) quotation.inner.add(it) }
            result.add(quotation)
            open = null
            partStart = null
            nested = null
            nestedStart = null
            stack = ArrayList()
        }
        // A level opened or closed one down from the outermost: the edges of nested speech.
        fun nestedOpened(offset: Int) {
            if (stack.size == 2) {
                nested = Quotation()
                nestedStart = offset
            }
        }
        fun nestedClosed(ref: VerseRef, end: Int) {
            if (stack.size != 1) return
            val inner = nested ?: return
            val start = nestedStart ?: return
            inner.parts.add(Part(ref, start, end))
            open?.inner?.add(inner)
            nested = null
            nestedStart = null
        }
        for ((ref, text) in verses) {
            val scalars = text.codePoints().toArray()
            val chapterKey = ref.book to ref.chapter
            if (chapterKey != chapter) {
                chapter = chapterKey
                val first = scalars.firstOrNull { !SwiftText.isHorizontalWhitespace(it) }
                val quotation = open
                if (quotation != null && first != 0x201C && first != '"'.code) {
                    result.add(quotation)
                    open = null
                    stack = ArrayList()
                }
            }
            partStart = if (open == null) null else 0
            nestedStart = if (nested == null) null else 0
            for (offset in scalars.indices) {
                val scalar = scalars[offset]
                val letterBefore = offset > 0 && isLetterOrMark(scalars[offset - 1])
                val letterAfter = offset + 1 < scalars.size && isLetterOrMark(scalars[offset + 1])
                when (scalar) {
                    0x201C -> {
                        val startsVerse = (0 until offset).all { SwiftText.isHorizontalWhitespace(scalars[it]) }
                        if (open == null) {
                            open = Quotation()
                            partStart = offset
                            stack = arrayListOf(Mark.DOUBLE)
                        } else if (startsVerse) {
                            // A new paragraph going on speaking: whatever nesting was left open ends.
                            nested?.let { if (it.parts.isNotEmpty()) open?.inner?.add(it) }
                            nested = null
                            nestedStart = null
                            stack = arrayListOf(Mark.DOUBLE)
                        } else if (stack.lastOrNull() == Mark.SINGLE) {
                            stack.add(Mark.DOUBLE)
                            nestedOpened(offset)
                        } else {
                            // Mid-verse, with a quotation still open: a closing mark was missed, so the
                            // old quotation ends here and a new one begins.
                            close(ref, offset)
                            open = Quotation()
                            partStart = offset
                            stack = arrayListOf(Mark.DOUBLE)
                        }
                    }
                    0x201D -> {
                        if (open != null) {
                            while (stack.lastOrNull() == Mark.SINGLE) {
                                stack.removeAt(stack.size - 1)
                                nestedClosed(ref, offset)
                            }
                            if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                            nestedClosed(ref, offset + 1)
                            if (stack.isEmpty()) close(ref, offset + 1)
                        }
                    }
                    0x2018 -> {
                        if (open != null) {
                            stack.add(Mark.SINGLE)
                            nestedOpened(offset)
                        }
                    }
                    0x2019 -> {
                        if (open != null && stack.lastOrNull() == Mark.SINGLE && !(letterBefore && letterAfter)) {
                            stack.removeAt(stack.size - 1)
                            nestedClosed(ref, offset + 1)
                        }
                    }
                    '"'.code -> {
                        if (open != null && stack.size <= 1) {
                            close(ref, offset + 1)
                        } else if (open == null) {
                            open = Quotation()
                            partStart = offset
                            stack = arrayListOf(Mark.DOUBLE)
                        }
                    }
                }
            }
            val inner = nested
            val innerStart = nestedStart
            if (inner != null && innerStart != null && innerStart < scalars.size) {
                inner.parts.add(Part(ref, innerStart, scalars.size))
            }
            val quotation = open
            val start = partStart
            if (quotation != null && start != null && start < scalars.size) {
                quotation.parts.add(Part(ref, start, scalars.size))
            }
        }
        open?.let { if (it.parts.isNotEmpty()) result.add(it) }
        return result
    }

    /**
     * Whether a quotation is Christ speaking: a vote of its words against the reference. A word that
     * lines up counts as its reference word does, however loosely the rest of the verse is rendered; a
     * word that lines up with nothing counts as the reference verse leans, when it leans clearly one way.
     */
    fun isSpokenByChrist(quotation: Quotation, evidence: (VerseRef) -> VerseEvidence?): Boolean {
        var red = 0
        var black = 0
        val quoteLeans = ArrayList<Boolean>()
        for (part in quotation.parts) {
            val verse = evidence(part.verse) ?: continue
            verse.quotationLean?.let { quoteLeans.add(it) }
            for ((index, token) in verse.tokens.withIndex()) {
                if (token.start < part.start || token.start >= part.end) continue
                val vote = verse.red[index] ?: verse.wordLean(token.word) ?: verse.lean
                if (vote == true) red++ else if (vote == false) black++
            }
        }
        // Nothing lines up at all ("Rise, and have no fear" / "Get up… Do not be afraid"): the
        // reference's own quotations in the same verses answer.
        if (red == 0 && black == 0) return quoteLeans.isNotEmpty() && false !in quoteLeans
        return red >= 1 && red.toDouble() >= (red + black).toDouble() * 0.6
    }

    /** What the reference says about one verse, for the vote. */
    class VerseEvidence(
        /** The target's words, and whether each lines up with a red reference word (null: with none). */
        val tokens: List<Token>,
        val red: List<Boolean?>,
        /** The share of the target's words that line up with the reference. */
        val matched: Double,
        /** The reference verse is (almost) all red, or not red at all; null when mixed. */
        val lean: Boolean?,
        /** The reference verse's quotations are all red (true), none red (false), or mixed / none (null). */
        val quotationLean: Boolean?,
        val redWords: Set<String>,
        val blackWords: Set<String>,
    ) {
        /**
         * A word that didn't line up, judged by where it appears in the reference verse: only among its
         * red words, or only among its others.
         */
        fun wordLean(word: String): Boolean? {
            val inRed = word in redWords
            val inBlack = word in blackWords
            return when {
                inRed && !inBlack -> true
                !inRed && inBlack -> false
                else -> null
            }
        }
    }

    fun evidence(target: String, reference: String, referenceRed: List<ScalarSpan>): VerseEvidence {
        val aligned = alignment(target, reference, referenceRed)
        val isRed = { token: Token -> referenceRed.any { it.start < token.end && it.start + it.length > token.start } }
        val words = tokens(reference)
        val redWords = HashSet<String>()
        val blackWords = HashSet<String>()
        for (token in words) if (isRed(token)) redWords.add(token.word) else blackWords.add(token.word)
        val share = if (words.isEmpty()) 0.0 else words.count(isRed).toDouble() / words.size
        // The reference verse's own quotations, each judged by whether its words are red.
        val quoted = quotations(listOf(VerseRef(1, 1, 1) to reference)).flatMap { it.parts }.map { part ->
            words.filter { it.start >= part.start && it.start < part.end }
        }.filter { it.isNotEmpty() }
        val verdicts = quoted.map { inside -> inside.count(isRed) * 2 > inside.size }
        val quotationLean: Boolean? = when {
            verdicts.isEmpty() -> null
            verdicts.all { it } -> true
            verdicts.none { it } -> false
            else -> null
        }
        return VerseEvidence(
            aligned.tokens, aligned.red, aligned.matched,
            lean = if (share >= 0.9) true else if (share == 0.0) false else null,
            quotationLean = quotationLean, redWords = redWords, blackWords = blackWords,
        )
    }
}

/**
 * Gives a translation with no red letters of its own the words of Christ from [reference] (text and
 * red spans, in scalars, of the same verse in a translation that marks them). Returns how many verses
 * were marked; a file that marks any red of its own is left exactly as it is.
 *
 * A book that prints its speech in quotation marks is read by its quotations: each is Christ speaking
 * or not as a whole, decided by how its words line up with the reference — so the narration around a
 * saying stays black however differently it is worded, and every word of a long discourse turns red
 * even where the two translations part company. A book without quotation marks is matched word by word
 * ([RedLetterInference.redSpans]).
 */
fun ExtractedBible.inferRedLetters(reference: (VerseRef) -> Pair<String, List<ScalarSpan>>?): Int {
    if (verses.values.any { it.red.isNotEmpty() }) return 0
    val texts = verses.mapValues { it.value.text }
    val spansByVerse = HashMap<VerseRef, MutableList<ScalarSpan>>()
    for (book in books) {
        val ordered = verses.values.filter { it.ref.book == book.number }.sortedBy { it.ref.key }
        // English-style marks only: „…“ and «…» close with what opens here, and a book in another
        // language won't line up with the reference well enough to vote anyway.
        var hasDouble = false
        var hasForeign = false
        for (verse in ordered) {
            verse.text.codePoints().forEach { cp ->
                if (cp == 0x201C || cp == '"'.code) hasDouble = true
                if (cp == 0x201E || cp == 0x00AB || cp == 0x00BB) hasForeign = true
            }
        }
        if (!hasDouble || hasForeign) {
            for (verse in ordered) {
                val source = reference(verse.ref) ?: continue
                if (source.second.isEmpty()) continue
                val spans = RedLetterInference.redSpans(verse.text, source.first, source.second)
                if (spans.isNotEmpty()) spansByVerse[verse.ref] = spans.toMutableList()
            }
            continue
        }
        val evidence = HashMap<VerseRef, RedLetterInference.VerseEvidence>()
        val align: (VerseRef) -> RedLetterInference.VerseEvidence? = align@{ ref ->
            evidence[ref]?.let { return@align it }
            val text = texts[ref] ?: return@align null
            val source = reference(ref) ?: return@align null
            RedLetterInference.evidence(text, source.first, source.second).also { evidence[ref] = it }
        }
        // The same language as the reference, or near enough to vote: on average a third of the words
        // in its red verses line up. Otherwise nothing here is judged at all.
        val judged = ordered.mapNotNull { verse ->
            val source = reference(verse.ref)
            if (source == null || source.second.isEmpty()) null else align(verse.ref)?.matched
        }
        if (judged.isEmpty() || judged.sum() / judged.size < 0.33) continue
        val quotations = RedLetterInference.quotations(ordered.map { it.ref to it.text })
        for (quotation in quotations) {
            // Someone else speaking may still quote Him ("the Lord said to me, ‘…’").
            val spoken = if (RedLetterInference.isSpokenByChrist(quotation, align)) listOf(quotation)
            else quotation.inner.filter { RedLetterInference.isSpokenByChrist(it, align) }
            for (part in spoken.flatMap { it.parts }) {
                spansByVerse.getOrPut(part.verse) { ArrayList() }.add(ScalarSpan(part.start, part.end - part.start))
            }
        }
    }
    var marked = 0
    for ((ref, spans) in spansByVerse) {
        setRed(ExtractedBible.merge(spans), ref)
        marked++
    }
    if (marked == 0) return 0
    // The paragraphs the reader draws carry the same spans, found in each fragment's words.
    for (chapter in chapterOrder.toList()) {
        val cursor = HashMap<Int, Int>()
        updateFragments(chapter) { fragment ->
            val ref = chapter.verse(fragment.verse)
            val spans = spansByVerse[ref] ?: return@updateFragments fragment
            val text = texts[ref] ?: return@updateFragments fragment
            val whole = text.codePoints().toArray()
            val own = fragment.text.codePoints().toArray()
            var lead = 0
            while (lead < own.size && SwiftText.isWhitespaceOrNewline(own[lead])) lead++
            var tail = own.size
            while (tail > lead && SwiftText.isWhitespaceOrNewline(own[tail - 1])) tail--
            val body = own.copyOfRange(lead, tail)
            if (body.isEmpty()) return@updateFragments fragment
            val found = findScalars(body, whole, cursor[fragment.verse] ?: 0) ?: return@updateFragments fragment
            cursor[fragment.verse] = found + body.size
            val local = spans.mapNotNull { span ->
                val start = maxOf(span.start, found)
                val end = minOf(span.start + span.length, found + body.size)
                if (end > start) StyledSpan(start - found + lead, end - start, StyledSpan.Style.WORDS_OF_CHRIST) else null
            }
            fragment.copy(spans = ExtractedBible.mergeStyled(fragment.spans + local))
        }
    }
    redLettersInferred = true
    return marked
}

private fun findScalars(needle: IntArray, haystack: IntArray, start: Int): Int? {
    if (needle.size > haystack.size || start > haystack.size - needle.size) return null
    for (offset in start..(haystack.size - needle.size)) {
        if (haystack[offset] != needle[0]) continue
        var same = true
        for (k in needle.indices) if (haystack[offset + k] != needle[k]) { same = false; break }
        if (same) return offset
    }
    return null
}
