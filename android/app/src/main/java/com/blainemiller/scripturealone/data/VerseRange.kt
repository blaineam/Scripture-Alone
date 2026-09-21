package com.blainemiller.scripturealone.data

import com.blainemiller.scripturealone.data.canon.BookID

/**
 * An inclusive span of verses, ported from `VerseRange` in `ScriptureAloneCore/VerseRef.swift`.
 * [start] is never after [end]. Its [storageString] ("43003016-43003018") is what share links and
 * `scripturealone://open?ref=` carry, so it must stay byte-identical to the iOS app's.
 */
data class VerseRange(val start: VerseRef, val end: VerseRef) {
    init {
        require(start.key <= end.key) { "a range starts before it ends; use VerseRange.of to order two verses" }
    }

    val storageString: String get() = "${start.key}-${end.key}"

    fun contains(key: Int): Boolean = key in start.key..end.key

    /** "John 3:16", "Romans 8:1–17", "Genesis 1:1–2:3", "Romans 8:1–Romans 9:2" */
    val display: String
        get() = when {
            start == end -> display(start)
            start.book != end.book -> "${display(start)}–${display(end)}"
            start.chapter != end.chapter -> "${display(start)}–${end.chapter}:${end.verse}"
            else -> "${display(start)}–${end.verse}"
        }

    companion object {
        fun of(a: VerseRef, b: VerseRef = a): VerseRange = if (a.key <= b.key) VerseRange(a, b) else VerseRange(b, a)

        /** A key naming one of the 66 books, or null — as Swift's `VerseRef(key:)`. */
        fun ref(key: Int): VerseRef? = if (BookID.of(key / 1_000_000) == null) null else VerseRef.fromKey(key)

        /** "43003016-43003018"; null unless both halves are verse keys of real books. */
        fun parse(storageString: String): VerseRange? {
            val parts = storageString.split('-').filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() }
            if (parts.size != 2) return null
            return of(ref(parts[0]) ?: return null, ref(parts[1]) ?: return null)
        }

        private fun display(ref: VerseRef): String =
            "${BookID.of(ref.book)?.displayName ?: ref.book} ${ref.chapter}:${ref.verse}"
    }
}
