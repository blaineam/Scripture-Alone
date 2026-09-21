package com.blainemiller.scripturealone.data.userdata

import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.keepsake.KeepsakeHighlight
import com.blainemiller.scripturealone.data.keepsake.KeepsakeNote
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import java.time.Instant
import java.util.UUID

// The reader's own marks — `ScriptureAlone/Persistence/Models.swift`. Verse keys are
// translation-independent, so highlights, notes and favorites follow the reader across translations.

/**
 * The five highlight colours, by the names iOS stores (`HighlightColor` in `ReaderStyle.swift`) and a
 * keepsake carries. [rgb] is the swatch; a highlight is drawn at 42% on a light page, 34% on a dark
 * one — the Swift `platformColor(isDark:)`.
 */
enum class HighlightColor(val rgb: Long) {
    YELLOW(0xF7D154), GREEN(0x8CD48A), BLUE(0x7FB8F0), PINK(0xF29BB8), PURPLE(0xB9A2EC);

    /** "yellow", "green", … — the stored and keepsake name. */
    val raw: String get() = name.lowercase()

    /** "Yellow" — for the accessibility label "Highlight Yellow". */
    val title: String get() = name.lowercase().replaceFirstChar { it.uppercase() }

    fun alpha(isDark: Boolean): Float = if (isDark) 0.34f else 0.42f

    companion object {
        fun fromRaw(raw: String?): HighlightColor? = entries.firstOrNull { it.raw == raw }
    }
}

/** One highlighted verse. A selection of several verses stores one row per verse, as on iOS. */
data class Highlight(val verseKey: Int, val color: String, val createdAt: Instant) {
    fun toKeepsake() = KeepsakeHighlight(verseKey, color, createdAt)
}

/**
 * A note attached to one or more verse ranges. Shaped to map one-to-one onto [KeepsakeNote] — id,
 * title, body, passages, createdAt, updatedAt, origin — so export and keepsake creation read this
 * store directly.
 */
data class Note(
    val id: UUID = UUID.randomUUID(),
    val title: String = "",
    val body: String = "",
    /** Kept sorted, as Swift's `anchors` setter sorts. */
    val anchors: List<VerseRange> = emptyList(),
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = createdAt,
    /** "manual" or "camera". */
    val origin: String = "manual",
) {
    val displayTitle: String
        get() = title.trim().ifEmpty { anchors.firstOrNull()?.display ?: "Untitled Note" }

    val anchorSummary: String get() = anchors.joinToString(" · ") { it.display }

    /** Earliest anchored verse key, for canonical sorting; 0 with no anchors. */
    val firstVerseKey: Int get() = anchors.firstOrNull()?.start?.key ?: 0

    fun touches(chapter: ChapterRef): Boolean = anchors.any { it.overlaps(chapter) }

    fun toKeepsake(): KeepsakeNote = KeepsakeNote.of(title, body, anchors, createdAt, updatedAt, origin, id)

    companion object {
        fun fromKeepsake(note: KeepsakeNote) = Note(
            id = note.id, title = note.title, body = note.body, anchors = note.anchors,
            createdAt = note.createdAt, updatedAt = note.updatedAt, origin = note.origin,
        )

        /** "43003016-43003017,45008001-45008017" → ranges, sorted; malformed parts are skipped. */
        fun parseAnchors(raw: String): List<VerseRange> =
            raw.split(',').mapNotNull { VerseRange.parse(it.trim()) }.sortedWith(RANGE_ORDER)

        fun encodeAnchors(anchors: List<VerseRange>): String =
            anchors.sortedWith(RANGE_ORDER).joinToString(",") { it.storageString }
    }
}

/** A favorited verse or passage — one row per contiguous range, so "Romans 8:38–39" is one favorite. */
data class Favorite(val id: UUID = UUID.randomUUID(), val range: VerseRange, val createdAt: Instant = Instant.now())

/** Canonical order: by start, then end — Swift's `VerseRange <`. */
val RANGE_ORDER: Comparator<VerseRange> = compareBy({ it.start.key }, { it.end.key })

fun VerseRange.overlaps(chapter: ChapterRef): Boolean {
    val keys = VerseRef.chapterRange(chapter.book, chapter.chapter)
    return start.key <= keys.last && end.key >= keys.first
}

fun VerseRange.overlaps(other: VerseRange): Boolean = start.key <= other.end.key && other.start.key <= end.key
