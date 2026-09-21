package com.blainemiller.scripturealone.data.layout

/**
 * Converts a Unicode-scalar offset — what the build tool records for layout spans, footnote
 * positions and `verses.red` — into a UTF-16 offset, which is what a Kotlin [String] and an
 * `AnnotatedString` index by.
 *
 * The two agree for every character in the Basic Multilingual Plane, which is why English text
 * hides the difference; a single character outside it (𝔸, an emoji, a Gothic or cuneiform letter in
 * an imported translation) is one scalar but two UTF-16 units, and every offset after it would land
 * one unit early — colouring the wrong words red, or splitting a surrogate pair in half.
 *
 * Mirrors `String.utf16Offset(ofScalar:)` in `ChapterLayout.swift`, including its edge behaviour: an
 * offset at or past the end returns the string's full UTF-16 length rather than throwing, so a span
 * that runs to the end of a fragment is clamped the same way on both platforms. A negative offset
 * returns 0.
 */
fun String.utf16Offset(scalarOffset: Int): Int {
    var utf16 = 0
    var scalars = 0
    while (utf16 < length && scalars < scalarOffset) {
        // A well-formed surrogate pair is one scalar; a lone surrogate counts as one, as Swift's
        // repair of it to U+FFFD would (it is one scalar there, and one unit here).
        utf16 += if (Character.isHighSurrogate(this[utf16]) && utf16 + 1 < length &&
            Character.isLowSurrogate(this[utf16 + 1])
        ) 2 else 1
        scalars++
    }
    return utf16
}

/** A scalar `[start, start + length)` range as a UTF-16 `[start, end)` pair on this string. */
fun String.utf16Range(scalarStart: Int, scalarLength: Int): IntRange {
    val start = utf16Offset(scalarStart)
    val end = utf16Offset(scalarStart + scalarLength)
    return start until maxOf(start, end)
}
