package com.blainemiller.scripturealone.data.search

import com.blainemiller.scripturealone.data.VerseRef

/**
 * One verse a search found: where it is as the translation numbers it ([ref] — what the result shows),
 * its plain text for the results list, and the KJV key it is stored under ([kjv] — where tapping the
 * result goes; see [com.blainemiller.scripturealone.data.VerseNumbering]).
 */
data class SearchHit(val ref: VerseRef, val text: String, val kjv: VerseRef = ref)

/**
 * What the phone's plain-store search and a sealed package's index both need, here so the Wear OS app
 * can open a sealed package too: the result type and the word splitter.
 */
object SearchWords {
    /** iOS caps a search at 300 and the results header says "300+ verses" when it's hit. */
    const val DEFAULT_LIMIT = 300

    /**
     * The words of a query: runs of letters, marks, digits and apostrophes, the curly apostrophe
     * straightened. Shared with the sealed index's query parser, which splits the same way.
     */
    fun words(text: String): List<String> {
        val words = mutableListOf<String>()
        val word = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isWordCodePoint(cp)) {
                word.appendCodePoint(if (cp == RIGHT_SINGLE_QUOTE) '\''.code else cp)
            } else if (word.isNotEmpty()) {
                words += word.toString()
                word.clear()
            }
            i += Character.charCount(cp)
        }
        if (word.isNotEmpty()) words += word.toString()
        return words
    }

    private const val RIGHT_SINGLE_QUOTE = 0x2019

    /**
     * Foundation's `CharacterSet.alphanumerics` — the L*, M* and N* general categories — plus the
     * two apostrophes.
     */
    private fun isWordCodePoint(cp: Int): Boolean {
        if (cp == '\''.code || cp == RIGHT_SINGLE_QUOTE) return true
        return when (Character.getType(cp).toByte()) {
            Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
            Character.MODIFIER_LETTER, Character.OTHER_LETTER,
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK,
            Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
            -> true
            else -> false
        }
    }
}
