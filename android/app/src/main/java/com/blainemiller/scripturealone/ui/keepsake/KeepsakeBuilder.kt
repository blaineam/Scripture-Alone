package com.blainemiller.scripturealone.ui.keepsake

import com.blainemiller.scripturealone.data.keepsake.Keepsake
import com.blainemiller.scripturealone.data.keepsake.KeepsakeHighlight
import com.blainemiller.scripturealone.data.keepsake.KeepsakeManifest
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.blainemiller.scripturealone.data.userdata.Highlight
import com.blainemiller.scripturealone.data.userdata.HighlightColor
import com.blainemiller.scripturealone.data.userdata.Note
import com.blainemiller.scripturealone.data.userdata.Selection
import com.blainemiller.scripturealone.ui.export.ExportSupport
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Making a keepsake from the reader's own marks, and reading one back in the reader — the pure parts
 * of `KeepsakeCreateView.swift` and `LegacyLibrary.swift`'s `Keepsake.marks(for:)`.
 */
object KeepsakeBuilder {

    /** One row per verse, the newest colour winning as in the reader, in verse order — `latestHighlights`. */
    fun latestHighlights(highlights: List<Highlight>): List<KeepsakeHighlight> {
        val byVerse = mutableMapOf<Int, Highlight>()
        for (h in highlights) {
            val existing = byVerse[h.verseKey]
            if (existing != null && existing.createdAt.isAfter(h.createdAt)) continue
            byVerse[h.verseKey] = h
        }
        return byVerse.values.sortedBy { it.verseKey }.map { it.toKeepsake() }
    }

    fun uniqueHighlightCount(highlights: List<Highlight>): Int = highlights.map { it.verseKey }.toSet().size

    /** "March 2019 to September 2026", or one month — the "From" row. */
    fun dateSpan(highlights: List<Highlight>, notes: List<Note>, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String? {
        val dates = highlights.map { it.createdAt } + notes.map { it.createdAt }
        val first = dates.minOrNull() ?: return null
        val last = dates.max()
        val format = DateTimeFormatter.ofPattern("LLLL yyyy", locale)
        val a = format.format(first.atZone(zone))
        val b = format.format(last.atZone(zone))
        return if (a == b) a else "$a to $b"
    }

    /** The keepsake — `KeepsakeCreateView.create`, before encoding. Blank name and dedication are left out. */
    fun make(
        highlights: List<Highlight>,
        notes: List<Note>,
        bibleID: UUID,
        ownerName: String,
        dedication: String,
        translation: String,
        generator: String,
        now: Instant = Instant.now(),
    ): Keepsake {
        val name = ownerName.trim()
        val words = dedication.trim()
        return Keepsake(
            manifest = KeepsakeManifest(
                bibleID = bibleID, createdAt = now, generator = generator,
                ownerName = name.ifEmpty { null }, dedication = words.ifEmpty { null }, preferredTranslation = translation,
            ),
            highlights = latestHighlights(highlights),
            notes = ExportSupport.canonicallySorted(notes.map { it.toKeepsake() }),
        ).withRefreshedSummary()
    }

    // Reading one

    /** The keepsake's highlights as the reader's own kind; unknown colours show as yellow, as on iOS. */
    fun highlights(keepsake: Keepsake): List<Highlight> = keepsake.highlights.map {
        Highlight(it.verse, (HighlightColor.fromRaw(it.color) ?: HighlightColor.YELLOW).raw, it.createdAt)
    }

    fun notes(keepsake: Keepsake): List<Note> = keepsake.notes.map(Note::fromKeepsake)

    /** Colours and note markers for one chapter — `Keepsake.marks(for:verseCount:)`. */
    fun marks(keepsake: Keepsake, chapter: ChapterRef, verseCount: Int): Pair<Map<Int, String>, Map<Int, List<String>>> =
        Selection.highlightColors(highlights(keepsake), chapter) to Selection.noteMarkers(notes(keepsake), chapter, maxOf(1, verseCount))
}

/** The keepsake screens' wording that depends on the file. */
object KeepsakeText {
    /** "412 highlights · 38 notes · 2019–2026 · read in the ASV" — `KeepsakeSummaryHeader.detail`. */
    fun summaryDetail(manifest: KeepsakeManifest, zone: ZoneId = ZoneId.systemDefault()): String {
        val parts = mutableListOf<String>()
        manifest.counts?.let {
            parts += "${it.highlights} ${if (it.highlights == 1) "highlight" else "highlights"}"
            parts += "${it.notes} ${if (it.notes == 1) "note" else "notes"}"
        }
        manifest.dateRange?.let {
            val start = it.start.atZone(zone).year.toString()
            val end = it.end.atZone(zone).year.toString()
            parts += if (start == end) start else "$start–$end"
        }
        manifest.preferredTranslation?.let { parts += "read in the $it" }
        return parts.joinToString(" · ")
    }

    /** The create sheet's closing line — "Dad’s Bible.scripturelegacy is ready, protected with your passphrase. …" */
    fun ready(name: String, protected: Boolean): String =
        "$name is ready${if (protected) ", protected with your passphrase" else ""}. It’s a snapshot of today; make a new one whenever you like, and it will replace the older copy when your family opens it."
}
