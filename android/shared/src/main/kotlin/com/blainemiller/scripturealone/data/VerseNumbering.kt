package com.blainemiller.scripturealone.data

/**
 * How one translation's verse numbers line up with the KJV's — `VerseNumbering.swift` in
 * ScriptureAloneCore, ported line for line.
 *
 * **Why there are two numberings.** Highlights, notes, favorites, cross-references, commentary and
 * the Hebrew and Greek are all keyed to one verse-key space — the KJV's — so they follow a reader
 * from one translation to another. But Bibles do not all number alike: Louis Segond counts a
 * psalm's title as verse 1, so the French Psalm 51:12 is the English 51:10; the Hebrew chapter
 * breaks put the French Exodus 7:26 at the English 8:1; a source can print nine English verses as
 * one. A French reader must see the French numbers — their printed Bible and their pastor's slides
 * use them — so each translation keeps its own, and this converts at the boundary.
 *
 * **The one rule.** Everything stored, shared or looked up uses KJV keys; only what the reader
 * draws uses native ones. A chapter is loaded and drawn with native numbers; a mark is stored,
 * found and shared by its KJV key.
 *
 * Built from a translation's `kjv_map` table (Tools/build_bibles.py), which holds only the verses
 * that differ. No rows — every English Bible, an import, a package, the online cache — is [IDENTITY].
 */
class VerseNumbering(rows: List<Row>) {

    /** A native verse and the KJV verses it holds (usually one; a range or a merged verse, more). */
    class Row(val native: Int, val kjv: Int, kjvLast: Int) {
        val kjvLast: Int = maxOf(kjv, kjvLast)

        override fun equals(other: Any?): Boolean =
            other is Row && other.native == native && other.kjv == kjv && other.kjvLast == kjvLast

        override fun hashCode(): Int = (native * 31 + kjv) * 31 + kjvLast
    }

    private val forward: Map<Int, Row>

    /**
     * Reverse intervals sorted by `kjv`, one per distinct KJV span; where several native verses
     * share a span (a psalm's title verses and its first verse all hold KJV verse 1) the last — the
     * verse itself, not its title — answers.
     */
    private val reverse: List<Row>

    init {
        val forward = HashMap<Int, Row>()
        val bySpan = HashMap<Long, Row>()
        for (row in rows) {
            forward[row.native] = row
            val key = row.kjv.toLong() * 1_000_000_000L + row.kjvLast
            val existing = bySpan[key]
            if (existing != null && existing.native > row.native) continue
            bySpan[key] = row
        }
        this.forward = forward
        this.reverse = bySpan.values.sortedWith(compareBy({ it.kjv }, { it.kjvLast }))
    }

    val isIdentity: Boolean get() = forward.isEmpty()

    override fun equals(other: Any?): Boolean = other is VerseNumbering && other.forward == forward
    override fun hashCode(): Int = forward.hashCode()

    // Native → KJV

    /** The KJV verses a native verse holds, as keys (first..last). Usually one. */
    fun kjvKeys(forNative: Int): IntRange {
        val row = forward[forNative] ?: return forNative..forNative
        return row.kjv..row.kjvLast
    }

    /**
     * Every KJV key a native verse holds, for storing one highlight per KJV verse — so the color
     * shows on each of them in a translation that numbers them separately. A span that runs into
     * the next chapter (the Reina-Valera's Job 39:30 holds 39:27–40:5) is listed as its first key
     * plus the second chapter's from verse 1; the KJV's own chapter lengths are not known here.
     */
    fun kjvKeyList(forNative: Int): List<Int> {
        val range = kjvKeys(forNative)
        val firstChapter = range.first / 1_000
        val lastChapter = range.last / 1_000
        if (firstChapter == lastChapter) return range.toList()
        return listOf(range.first) + ((lastChapter * 1_000 + 1)..range.last).toList()
    }

    /** The KJV key a native verse is stored under: the first it holds. */
    fun kjv(forNative: Int): Int = forward[forNative]?.kjv ?: forNative

    /** A native range as KJV keys, first verse's first KJV key to last verse's last. */
    fun kjvRange(range: VerseRange): VerseRange {
        if (isIdentity) return range
        val start = VerseRange.ref(kjvKeys(range.start.key).first) ?: return range
        val end = VerseRange.ref(kjvKeys(range.end.key).last) ?: return range
        return VerseRange.of(start, end)
    }

    // KJV → native

    /**
     * The native verse that holds a KJV verse, or null when this translation has no verse there (it
     * merged that KJV verse into a neighbour the map does not name, or omits it).
     */
    fun native(forKjv: Int): Int? {
        if (isIdentity) return forKjv
        containing(forKjv)?.let { return it.native }
        // A KJV verse no row mentions keeps its number — unless that native number was itself moved
        // elsewhere, in which case this translation simply has nothing there.
        return if (forward[forKjv] == null) forKjv else null
    }

    /** A KJV range in native keys, widened to whole native verses. Null if none of it is here. */
    fun nativeRange(range: VerseRange): VerseRange? {
        if (isIdentity) return range
        val first = native(range.start.key) ?: nearestAfter(range.start.key, range.end.key)
        val last = native(range.end.key) ?: nearestBefore(range.end.key, range.start.key)
        if (first == null || last == null || first > last) return null
        val start = VerseRange.ref(first) ?: return null
        val end = VerseRange.ref(last) ?: return null
        return VerseRange(start, end)
    }

    /**
     * The KJV keys a native chapter's verses hold, for finding a chapter's stored marks. May run into
     * a neighbouring KJV chapter (the French Exodus 8 holds the English 8:5 onward, its 7:26–29 the
     * English 8:1–4) — which is why marks are matched by key range, never by chapter number.
     */
    fun kjvKeyRange(book: Int, chapter: Int, verseCount: Int): IntRange {
        if (isIdentity || verseCount <= 0) return VerseRef.chapterRange(book, chapter)
        val first = VerseRef(book, chapter, 1).key
        val last = VerseRef(book, chapter, verseCount).key
        var low = kjvKeys(first).first
        var high = kjvKeys(last).last
        for (row in forward.values) {
            if (row.native < first || row.native > last) continue
            low = minOf(low, row.kjv)
            high = maxOf(high, row.kjvLast)
        }
        return low..high
    }

    // Private

    private fun containing(key: Int): Row? {
        // Spans are short and few (a translation has at most ~1,500 rows): a binary search to the last
        // span starting at or before the key, then a short walk back over overlapping spans.
        var low = 0
        var high = reverse.size
        while (low < high) {
            val mid = (low + high) / 2
            if (reverse[mid].kjv <= key) low = mid + 1 else high = mid
        }
        var index = low - 1
        var best: Row? = null
        while (index >= 0 && reverse[index].kjv >= key - 1_000_000) {
            val row = reverse[index]
            if (row.kjv <= key && key <= row.kjvLast && (best == null || row.native > best.native)) best = row
            index -= 1
        }
        return best
    }

    private fun nearestAfter(key: Int, limit: Int): Int? {
        var candidate = key + 1
        while (candidate <= limit) {
            native(candidate)?.let { return it }
            candidate += 1
        }
        return null
    }

    private fun nearestBefore(key: Int, limit: Int): Int? {
        var candidate = key - 1
        while (candidate >= limit) {
            native(candidate)?.let { return it }
            candidate -= 1
        }
        return null
    }

    companion object {
        val IDENTITY = VerseNumbering(emptyList())
    }
}
