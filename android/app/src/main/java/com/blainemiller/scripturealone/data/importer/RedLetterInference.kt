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
}

/**
 * Gives a translation with no red letters of its own the words of Christ from [reference] (text and
 * red spans, in scalars, of the same verse in a translation that marks them). Returns how many verses
 * were marked; a file that marks any red of its own is left exactly as it is.
 */
fun ExtractedBible.inferRedLetters(reference: (VerseRef) -> Pair<String, List<ScalarSpan>>?): Int {
    if (verses.values.any { it.red.isNotEmpty() }) return 0
    var marked = 0
    for ((ref, verse) in verses.entries.toList()) {
        val source = reference(ref) ?: continue
        if (source.second.isEmpty()) continue
        val spans = RedLetterInference.redSpans(verse.text, source.first, source.second)
        if (spans.isEmpty()) continue
        setRed(ExtractedBible.merge(spans), ref)
        marked++
    }
    if (marked == 0) return 0
    for (chapter in chapterOrder.toList()) {
        updateFragments(chapter) { fragment ->
            val source = reference(chapter.verse(fragment.verse))
            if (source == null || source.second.isEmpty()) return@updateFragments fragment
            val spans = RedLetterInference.redSpans(fragment.text, source.first, source.second)
            if (spans.isEmpty()) return@updateFragments fragment
            fragment.copy(
                spans = ExtractedBible.mergeStyled(fragment.spans + spans.map { StyledSpan(it.start, it.length, StyledSpan.Style.WORDS_OF_CHRIST) }),
            )
        }
    }
    redLettersInferred = true
    return marked
}
