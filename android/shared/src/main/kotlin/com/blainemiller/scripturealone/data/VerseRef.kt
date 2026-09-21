package com.blainemiller.scripturealone.data

/**
 * A verse, and the integer key every bundled database is indexed by.
 *
 * The encoding is shared with the iOS app (`ScriptureAloneCore/VerseRef.swift`) and baked into the
 * databases both apps read, so it must never change here independently:
 *
 *     key = book * 1_000_000 + chapter * 1_000 + verse
 *
 * John 3:16 is 43003016. Verse 0 is a chapter's heading or superscription — Psalm 23's "A Psalm of
 * David." — which is why a verse range starts at 0 rather than 1.
 */
data class VerseRef(val book: Int, val chapter: Int, val verse: Int) {
    val key: Int get() = book * 1_000_000 + chapter * 1_000 + verse

    companion object {
        fun fromKey(key: Int): VerseRef =
            VerseRef(book = key / 1_000_000, chapter = (key / 1_000) % 1_000, verse = key % 1_000)

        /** The key range covering a whole chapter, verse 0 included. */
        fun chapterRange(book: Int, chapter: Int): IntRange {
            val base = book * 1_000_000 + chapter * 1_000
            return base..(base + 999)
        }
    }
}
