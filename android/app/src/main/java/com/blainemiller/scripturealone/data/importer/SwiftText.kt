package com.blainemiller.scripturealone.data.importer

/**
 * The handful of Swift `String`/`Character` semantics the importers depend on, reproduced so the
 * Kotlin port reads a file the way the iOS engine does.
 *
 * Three things differ between the platforms and each one changes output:
 * - **What a "character" is.** Swift iterates grapheme clusters, so `"\r\n"` is one `Character`
 *   and `"é"` is one; Kotlin iterates UTF-16 units. [SwiftCharacters] segments a string into
 *   clusters (see [joinsPrevious] for the rules implemented), and the Swift `Character` properties
 *   are read from a cluster's first scalar, as Swift does.
 * - **What whitespace is.** Swift's `Character.isWhitespace` is the Unicode `White_Space` property
 *   (which includes U+00A0 and U+0085); Kotlin's `isWhitespace` is Java's, which excludes those and
 *   includes U+001C–U+001F. Foundation's `CharacterSet.whitespacesAndNewlines` is a third set again.
 * - **What an offset counts.** Everything stored — red letters, spans, footnote positions — is in
 *   Unicode scalars ([scalarCount]), not UTF-16 units.
 */
internal object SwiftText {

    // MARK: - Character properties (of a scalar)

    /** Swift `Character.isWhitespace`: the Unicode `White_Space` property. */
    fun isWhitespace(cp: Int): Boolean =
        cp in 0x09..0x0D || cp == 0x20 || cp == 0x85 || cp == 0xA0 || cp == 0x1680 ||
            cp in 0x2000..0x200A || cp == 0x2028 || cp == 0x2029 || cp == 0x202F || cp == 0x205F || cp == 0x3000

    /** Swift `Character.isLetter`: the Unicode `Alphabetic` property. */
    fun isLetter(cp: Int): Boolean = Character.isAlphabetic(cp)

    /**
     * Swift `Character.isNumber`: a `Numeric_Type` other than none. Approximated by the Nd, Nl and No
     * general categories; the Unihan ideographs that also carry a numeric type (五, 萬) are not counted.
     */
    fun isNumber(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt() -> true
        else -> false
    }

    /** Foundation `CharacterSet.whitespacesAndNewlines`: Z*, tab, U+000A–U+000D and U+0085. */
    fun isWhitespaceOrNewline(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.SPACE_SEPARATOR.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt() -> true
        else -> cp == 0x09 || cp in 0x0A..0x0D || cp == 0x85
    }

    /** Foundation `CharacterSet.whitespaces`: Zs and tab. */
    fun isHorizontalWhitespace(cp: Int): Boolean =
        cp == 0x09 || Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()

    // MARK: - Scalars

    /** `string.unicodeScalars.count`. */
    fun scalarCount(s: String): Int = s.codePointCount(0, s.length)

    /** Foundation `trimmingCharacters(in:)`, which works scalar by scalar. */
    fun trim(s: String, set: (Int) -> Boolean): String {
        var start = 0
        var end = s.length
        while (start < end) {
            val cp = s.codePointAt(start)
            if (!set(cp)) break
            start += Character.charCount(cp)
        }
        while (end > start) {
            val cp = s.codePointBefore(end)
            if (!set(cp)) break
            end -= Character.charCount(cp)
        }
        return s.substring(start, end)
    }

    fun trimWhitespaceAndNewlines(s: String): String = trim(s, ::isWhitespaceOrNewline)
    fun trimWhitespace(s: String): String = trim(s, ::isHorizontalWhitespace)

    // MARK: - Character-level string operations

    /** `s.hasPrefix(" ")` — true only when the first *character* is exactly the space. */
    fun hasPrefixSpace(s: String): Boolean =
        s.startsWith(' ') && (s.length == 1 || !joinsPrevious(0x20, s.codePointAt(1)))

    /** `while s.hasPrefix(" ") { s.removeFirst() }`, and how many were removed. */
    fun dropLeadingSpaces(s: String): Pair<String, Int> {
        var index = 0
        while (index < s.length && s[index] == ' ' &&
            (index + 1 == s.length || !joinsPrevious(0x20, s.codePointAt(index + 1)))
        ) index++
        return s.substring(index) to index
    }

    /** `while let last = s.last, last.isWhitespace { s.removeLast() }`. */
    fun dropTrailingWhitespace(s: String): String {
        if (s.isEmpty()) return s
        val characters = SwiftCharacters(s)
        var count = characters.count
        while (count > 0 && characters.isWhitespace(count - 1)) count--
        return if (count == characters.count) s else s.substring(0, characters.start(count))
    }

    /** `String(s.drop(while: \.isWhitespace))`. */
    fun dropLeadingWhitespace(s: String): String {
        val characters = SwiftCharacters(s)
        var index = 0
        while (index < characters.count && characters.isWhitespace(index)) index++
        return s.substring(characters.start(index))
    }

    /** `String(s.prefix(n))`, in characters. */
    fun prefix(s: String, n: Int): String {
        val characters = SwiftCharacters(s)
        return if (n >= characters.count) s else s.substring(0, characters.start(n))
    }

    /** `s.count`. */
    fun characterCount(s: String): Int = SwiftCharacters(s).count

    /** `s.split(whereSeparator:)` with Swift's default of omitting empty pieces. */
    fun split(s: String, isSeparator: (SwiftCharacters, Int) -> Boolean): List<String> {
        val characters = SwiftCharacters(s)
        val pieces = ArrayList<String>()
        var pieceStart = 0
        for (i in 0 until characters.count) {
            if (isSeparator(characters, i)) {
                if (characters.start(i) > pieceStart) pieces.add(s.substring(pieceStart, characters.start(i)))
                pieceStart = characters.end(i)
            }
        }
        if (s.length > pieceStart) pieces.add(s.substring(pieceStart))
        return pieces
    }

    /** `s.split(separator: c)`, omitting empty pieces. */
    fun split(s: String, separator: Char): List<String> = split(s) { chars, i -> chars.isChar(i, separator) }

    /** `s.allSatisfy(\.isNumber)` (true for an empty string, as in Swift). */
    fun allNumbers(s: String): Boolean {
        val characters = SwiftCharacters(s)
        return (0 until characters.count).all(characters::isNumber)
    }

    /**
     * Swift's `Int(String)`: an optional sign and ASCII digits, nothing else — "٣" and "1a" are nil,
     * where Kotlin's `toIntOrNull` would accept the first. Values outside `Int` are nil here; Swift's
     * `Int` is 64-bit, so an absurd number a Swift build keeps is dropped by this one.
     */
    fun int(s: String): Int? {
        if (s.isEmpty()) return null
        var index = 0
        var negative = false
        if (s[0] == '+' || s[0] == '-') {
            negative = s[0] == '-'
            index = 1
        }
        if (index >= s.length) return null
        var value = 0L
        while (index < s.length) {
            val c = s[index]
            if (c !in '0'..'9') return null
            value = value * 10 + (c - '0')
            if (value > Int.MAX_VALUE.toLong() + 1) return null
            index++
        }
        val signed = if (negative) -value else value
        return if (signed in Int.MIN_VALUE..Int.MAX_VALUE) signed.toInt() else null
    }

    /** Swift's `UInt32(String, radix:)`. */
    fun uint32(s: String, radix: Int): Long? {
        if (s.isEmpty()) return null
        var index = 0
        var negative = false
        if (s[0] == '+' || s[0] == '-') {
            negative = s[0] == '-'
            index = 1
        }
        if (index >= s.length) return null
        var value = 0L
        while (index < s.length) {
            val digit = Character.digit(s[index], radix)
            if (digit < 0 || s[index].code > 0x7F) return null
            value = value * radix + digit
            if (value > 0xFFFF_FFFFL) return null
            index++
        }
        if (negative && value != 0L) return null
        return value
    }

    // MARK: - Grapheme clusters

    /**
     * Does [cp] continue the cluster that [previous] is in? An approximation of UAX #29 covering what
     * appears in scripture text: CR LF (GB3), controls break (GB4/GB5), combining marks, ZWJ,
     * variation selectors, emoji modifiers and tags extend (GB9/GB9a), and ZWJ joins a following
     * symbol (GB11, approximated by the So category). Hangul jamo sequences and Prepend characters are
     * not joined, and regional-indicator pairing is handled by [SwiftCharacters].
     */
    fun joinsPrevious(previous: Int, cp: Int): Boolean {
        if (previous == 0x0D && cp == 0x0A) return true
        if (isControl(previous) || isControl(cp)) return false
        if (isExtend(cp)) return true
        if (previous == 0x200D && Character.getType(cp) == Character.OTHER_SYMBOL.toInt()) return true
        return false
    }

    private fun isControl(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.CONTROL.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt() -> true
        Character.FORMAT.toInt() -> cp != 0x200C && cp != 0x200D && !(cp in 0xE0020..0xE007F)
        else -> false
    }

    private fun isExtend(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt() -> true
        else -> cp == 0x200C || cp == 0x200D || cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF ||
            cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F
    }

    fun isRegionalIndicator(cp: Int): Boolean = cp in 0x1F1E6..0x1F1FF
}

/**
 * A string viewed as Swift's `Array(string)`: one entry per grapheme cluster, indexable, with the
 * `Character` properties Swift reads from each cluster's first scalar.
 */
internal class SwiftCharacters(val source: String) {
    /** UTF-16 start of each character, then `source.length`. */
    private val bounds: IntArray
    val count: Int

    init {
        var starts = IntArray(minOf(source.length, 16) + 1)
        var n = 0
        var index = 0
        var previous = -1
        var regionalRun = 0
        while (index < source.length) {
            val cp = source.codePointAt(index)
            val joins = previous >= 0 && if (SwiftText.isRegionalIndicator(cp) && SwiftText.isRegionalIndicator(previous)) {
                regionalRun % 2 == 1
            } else {
                SwiftText.joinsPrevious(previous, cp)
            }
            if (!joins) {
                if (n + 1 >= starts.size) starts = starts.copyOf(starts.size * 2)
                starts[n++] = index
            }
            regionalRun = if (SwiftText.isRegionalIndicator(cp)) regionalRun + 1 else 0
            previous = cp
            index += Character.charCount(cp)
        }
        if (n + 1 > starts.size) starts = starts.copyOf(n + 1)
        starts[n] = source.length
        bounds = starts
        count = n
    }

    fun start(i: Int): Int = bounds[i]
    fun end(i: Int): Int = bounds[i + 1]

    /** The character's first scalar, which is what Swift's `Character` properties read. */
    fun first(i: Int): Int = source.codePointAt(bounds[i])

    /** `characters[i] == c` for a single-scalar [c]. */
    fun isChar(i: Int, c: Char): Boolean = bounds[i + 1] - bounds[i] == 1 && source[bounds[i]] == c

    fun isWhitespace(i: Int): Boolean = SwiftText.isWhitespace(first(i))
    fun isLetter(i: Int): Boolean = SwiftText.isLetter(first(i))
    fun isNumber(i: Int): Boolean = SwiftText.isNumber(first(i))

    fun string(i: Int): String = source.substring(bounds[i], bounds[i + 1])
    fun substring(from: Int, to: Int): String = source.substring(bounds[from], bounds[to])

    fun appendTo(builder: StringBuilder, i: Int) {
        builder.append(source, bounds[i], bounds[i + 1])
    }
}
