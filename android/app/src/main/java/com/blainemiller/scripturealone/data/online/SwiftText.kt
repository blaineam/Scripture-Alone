package com.blainemiller.scripturealone.data.online

/**
 * The handful of Swift `String`/`Character` predicates the online parsers lean on, spelled out so
 * the Kotlin port answers the same way. Kotlin's own versions differ at the edges that matter here:
 * [Char.isWhitespace] rejects the no-break space both services pad with, and [String.toIntOrNull]
 * accepts Arabic-Indic and other non-ASCII digits that Swift's `Int(_:)` refuses.
 */
internal object SwiftText {

    /**
     * `Character.isWhitespace`: the Unicode `White_Space` property. Includes U+00A0 and the other
     * no-break spaces, which Java's `isWhitespace` deliberately excludes.
     */
    fun isWhitespace(c: Char): Boolean = when (c) {
        in '\u0009'..'\u000D', ' ', '\u0085', '\u00A0', '\u1680', in '\u2000'..'\u200A',
        '\u2028', '\u2029', '\u202F', '\u205F', '\u3000' -> true
        else -> false
    }

    /** `CharacterSet.whitespaces`: general category Zs, plus the tab. No newlines. */
    fun isHorizontalWhitespace(c: Char): Boolean =
        c == '\t' || Character.getType(c) == Character.SPACE_SEPARATOR.toInt()

    /**
     * `Character.isNumber`, approximated by the three numeric general categories. Swift also counts
     * the handful of CJK ideographs that carry a numeric value; none can appear in a verse number.
     */
    fun isNumber(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt() -> true
        else -> false
    }

    /**
     * `Int(_: String)`: an optional sign, then one or more **ASCII** digits, nothing else. Swift's
     * `Int` is 64-bit; a value that does not fit a Kotlin [Int] is refused, which no verse number
     * comes near.
     */
    fun int(text: String): Int? {
        val digits = if (text.startsWith("+") || text.startsWith("-")) text.substring(1) else text
        if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return null
        return text.toLongOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    }

    /** `split(separator:)`: Swift drops the empty pieces by default; Kotlin's `split` keeps them. */
    fun split(text: String, separator: Char): List<String> = text.split(separator).filter { it.isNotEmpty() }

    /** `split(whereSeparator: \.isWhitespace)`, empty pieces dropped. */
    fun splitOnWhitespace(text: String): List<String> {
        val pieces = mutableListOf<String>()
        var start = -1
        for (i in text.indices) {
            if (isWhitespace(text[i])) {
                if (start >= 0) pieces += text.substring(start, i)
                start = -1
            } else if (start < 0) {
                start = i
            }
        }
        if (start >= 0) pieces += text.substring(start)
        return pieces
    }
}
