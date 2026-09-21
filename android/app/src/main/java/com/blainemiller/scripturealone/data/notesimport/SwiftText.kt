package com.blainemiller.scripturealone.data.notesimport

import java.text.BreakIterator

// The import parsers were written against Swift's `String`, whose unit is the Character — a grapheme
// cluster — and against Foundation's character sets. Kotlin's unit is the UTF-16 char and its `trim()`
// has its own idea of whitespace. These helpers restore the Swift meaning wherever the difference can
// change an answer: counting ("at least three characters"), splitting on a space that a combining mark
// has attached itself to, and trimming a non-breaking space that `trim()` would also eat but a newline
// that Swift's `.whitespaces` would not.

/** Foundation's `CharacterSet.whitespaces`: General Category Zs, plus tab. No newlines. */
internal fun isSwiftWhitespace(c: Char): Boolean = c == '\t' || Character.getType(c) == Character.SPACE_SEPARATOR.toInt()

/** Foundation's `CharacterSet.whitespacesAndNewlines`: Z*, U+000A–U+000D, U+0085, and tab. */
internal fun isSwiftWhitespaceOrNewline(c: Char): Boolean {
    if (c == '\t' || c in '\u000A'..'\u000D' || c == '\u0085') return true
    return when (Character.getType(c).toByte()) {
        Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
        else -> false
    }
}

/** `trimmingCharacters(in: .whitespaces)`. */
internal fun String.trimSwiftWhitespace(): String = trim(::isSwiftWhitespace)

/** `trimmingCharacters(in: .whitespacesAndNewlines)`. */
internal fun String.trimSwiftWhitespaceAndNewlines(): String = trim(::isSwiftWhitespaceOrNewline)

/** `trimmingCharacters(in: CharacterSet(charactersIn: set))`. Every member here is a single BMP char. */
internal fun String.trimCharacters(set: String): String = trim { it in set }

/** The string's Swift Characters — extended grapheme clusters — in order. */
internal fun String.graphemes(): List<String> {
    if (isEmpty()) return emptyList()
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(this)
    val out = ArrayList<String>(length)
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        out += substring(start, end)
        start = end
        end = iterator.next()
    }
    return out
}

/** Swift's `String.count`. */
internal fun String.graphemeCount(): Int = graphemes().size

/** Swift's `split(separator: Character, omittingEmptySubsequences:)`, matching whole Characters. */
internal fun String.splitOnCharacter(separator: String, omittingEmpty: Boolean = true): List<String> {
    val out = mutableListOf<String>()
    val current = StringBuilder()
    for (g in graphemes()) {
        if (g == separator) {
            if (!omittingEmpty || current.isNotEmpty()) out += current.toString()
            current.setLength(0)
        } else {
            current.append(g)
        }
    }
    if (!omittingEmpty || current.isNotEmpty()) out += current.toString()
    return out
}

/** Swift's `lastIndex(of: Character)`, as a UTF-16 offset, or -1. */
internal fun String.lastIndexOfCharacter(character: String): Int {
    var offset = 0
    var found = -1
    for (g in graphemes()) {
        if (g == character) found = offset
        offset += g.length
    }
    return found
}

/** Swift's `Character.isLetter`: the first scalar is Alphabetic. */
internal fun isSwiftLetter(character: String): Boolean =
    character.isNotEmpty() && Character.isAlphabetic(character.codePointAt(0))

/** Swift's `Character.isNumber`: the first scalar has a numeric type. */
internal fun isSwiftNumber(character: String): Boolean {
    if (character.isEmpty()) return false
    return when (Character.getType(character.codePointAt(0)).toByte()) {
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }
}

/**
 * Swift's `Character.isUppercase`: for one scalar, its Uppercase property; for a cluster, that it is
 * unchanged by uppercasing and changed by lowercasing.
 */
internal fun isSwiftUppercase(character: String): Boolean {
    if (character.isEmpty()) return false
    val first = character.codePointAt(0)
    if (Character.charCount(first) == character.length) return Character.isUpperCase(first)
    return character.uppercase() == character && character.lowercase() != character
}

/**
 * Swift's `UInt32(text, radix:)`: an optional sign, then one or more digits of the radix, with the
 * value fitting 32 unsigned bits. Anything else is null — including an empty string.
 */
internal fun parseUInt32(text: String, radix: Int): Long? {
    var digits = text
    var negative = false
    if (digits.startsWith("+")) digits = digits.substring(1)
    else if (digits.startsWith("-")) { digits = digits.substring(1); negative = true }
    if (digits.isEmpty()) return null
    var value = 0L
    for (c in digits) {
        val d = Character.digit(c, radix)
        // Character.digit accepts full-width and other non-ASCII digits; Swift's parser does not.
        if (d < 0 || c.code > 0x7F) return null
        value = value * radix + d
        if (value > 0xFFFF_FFFFL) return null
    }
    if (negative && value != 0L) return null
    return value
}
