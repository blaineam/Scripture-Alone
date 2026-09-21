package com.blainemiller.scripturealone.data.userdata

import com.blainemiller.scripturealone.data.Canon
import com.blainemiller.scripturealone.data.ChapterVerse
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.sabible.ChapterRef

/**
 * The pure rules behind selecting verses and marking them — each a port of the Swift code named in
 * its doc, kept free of Android so the JVM tests run exactly what the app does.
 */
object Selection {

    /**
     * Selected verse keys as contiguous ranges — `VerseRange.ranges(from:verseCount:)`. Two verses join
     * when the second follows the first in its chapter, or opens the next chapter after the first
     * chapter's last verse. [verseCount] answers 0 for a chapter it doesn't know, which keeps the
     * ranges apart rather than guessing.
     */
    fun ranges(keys: Collection<Int>, verseCount: (ChapterRef) -> Int): List<VerseRange> {
        val refs = keys.mapNotNull { VerseRange.ref(it) }.sortedBy { it.key }
        val result = mutableListOf<VerseRange>()
        for (ref in refs) {
            val last = result.lastOrNull()
            if (last != null) {
                val end = last.end
                val endChapter = ChapterRef(end.book, end.chapter)
                val sameChapter = end.book == ref.book && end.chapter == ref.chapter && ref.verse == end.verse + 1
                val nextChapter = ref.verse == 1 && Canon.next(endChapter) == ChapterRef(ref.book, ref.chapter) &&
                    end.verse >= verseCount(endChapter) && verseCount(endChapter) > 0
                if (sameChapter || nextChapter) {
                    result[result.size - 1] = VerseRange(last.start, ref)
                    continue
                }
            }
            result += VerseRange.of(ref)
        }
        return result
    }

    /**
     * Every verse key a range covers, given each chapter's verse count — `ShareSupport.verseKeys`, for
     * selecting a passage a link opened. Capped at 2,000 verses, as Swift caps it.
     */
    fun keys(range: VerseRange, verseCount: (ChapterRef) -> Int): List<Int> {
        val keys = mutableListOf<Int>()
        var chapter = ChapterRef(range.start.book, range.start.chapter)
        var verse = range.start.verse.coerceAtLeast(1)
        while (keys.size < 2_000) {
            val count = verseCount(chapter)
            if (verse > count) {
                val next = Canon.next(chapter)
                if (count <= 0 || next == null) break
                chapter = next
                verse = 1
                continue
            }
            val key = VerseRef(chapter.book, chapter.chapter, verse).key
            if (key > range.end.key) break
            keys += key
            verse += 1
        }
        return keys
    }

    /**
     * Long-press extends the selection: every verse from the nearest selected verse to [key], in the
     * same chapter. With nothing selected nearby it selects just [key].
     */
    fun extend(selection: Set<Int>, key: Int): Set<Int> {
        val chapterBase = key / 1_000 * 1_000
        val inChapter = selection.filter { it / 1_000 * 1_000 == chapterBase }
        val anchor = inChapter.minByOrNull { kotlin.math.abs(it - key) } ?: return selection + key
        val range = if (anchor <= key) anchor..key else key..anchor
        return selection + range.filter { it % 1_000 > 0 }
    }

    /**
     * Newest highlight wins when a verse has more than one — `ChapterPane.renderInput`. Only verses in
     * [chapter].
     */
    fun highlightColors(highlights: Collection<Highlight>, chapter: ChapterRef): Map<Int, String> {
        val keys = VerseRef.chapterRange(chapter.book, chapter.chapter)
        val newest = mutableMapOf<Int, Highlight>()
        for (h in highlights) {
            if (h.verseKey !in keys) continue
            val existing = newest[h.verseKey]
            if (existing == null || !existing.createdAt.isAfter(h.createdAt)) newest[h.verseKey] = h
        }
        return newest.mapValues { it.value.color }
    }

    /**
     * Where each note's marker goes in [chapter]: the last verse of every anchor that falls in it (or
     * the chapter's last verse, when the anchor runs on past it) — `ChapterPane.renderInput`.
     * Ids are UUID strings, newest-edited note first as Swift's query sorts them.
     */
    fun noteMarkers(notes: Collection<Note>, chapter: ChapterRef, verseCount: Int): Map<Int, List<String>> {
        val markers = linkedMapOf<Int, MutableList<String>>()
        for (note in notes.sortedByDescending { it.updatedAt }) {
            for (anchor in note.anchors) {
                if (!anchor.overlaps(chapter)) continue
                val end = if (anchor.end.book == chapter.book && anchor.end.chapter == chapter.chapter) {
                    anchor.end.key
                } else {
                    VerseRef(chapter.book, chapter.chapter, verseCount).key
                }
                val ids = markers.getOrPut(end) { mutableListOf() }
                val id = note.id.toString()
                if (id !in ids) ids += id
            }
        }
        return markers
    }

    /**
     * "For God so loved…\n— John 3:16 (ASV)" — `ReaderModel.quotation(for:)`. One block per range,
     * verses numbered when a range has more than one, blocks separated by a blank line. [verses]
     * holds every verse the ranges cover (and may hold more); callers gate on the rights first.
     */
    fun quotation(ranges: List<VerseRange>, verses: List<ChapterVerse>, abbreviation: String): String =
        ranges.mapNotNull { range ->
            val inRange = verses.filter { range.contains(it.ref.key) }.sortedBy { it.ref.key }
            if (inRange.isEmpty()) return@mapNotNull null
            val text = if (inRange.size == 1) {
                inRange[0].text
            } else {
                inRange.joinToString(" ") { "${it.ref.verse} ${it.text}" }
            }
            "$text\n— ${range.display} ($abbreviation)"
        }.joinToString("\n\n")

    /** The heart is filled when every selected range is already a favorite — `FavoriteButton`. */
    fun isFavorite(ranges: List<VerseRange>, favorites: Collection<Favorite>): Boolean {
        val stored = favorites.map { it.range.storageString }.toSet()
        return ranges.isNotEmpty() && ranges.all { it.storageString in stored }
    }
}
