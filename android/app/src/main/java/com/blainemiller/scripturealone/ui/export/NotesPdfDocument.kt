package com.blainemiller.scripturealone.ui.export

import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.keepsake.KeepsakeNote
import com.blainemiller.scripturealone.data.keepsake.NotesTextExport
import com.blainemiller.scripturealone.text.AppText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * What a notes PDF says, paragraph by paragraph, with each paragraph's type and spacing — the
 * `document(_:options:verseText:)` half of `NotesPDFRenderer.swift`, kept apart from the drawing so the
 * JVM tests can read it. [NotesPdfRenderer] sets these on pages with `android.graphics.pdf.PdfDocument`.
 *
 * Sizes and spacing are in PDF points and are the Swift values: a title block, then each note with its
 * passages quoted in the chosen translation, its text, and when it was written; the publisher's notice
 * last, when one is supplied.
 */
object NotesPdfDocument {

    data class Options(
        val title: String,
        val subtitle: String? = null,
        /** Translation abbreviation for quoted passages, or null to leave verse text out. */
        val translation: String? = null,
        /**
         * The publisher's copyright line for the quoted translation, set at the end of the document —
         * quotations that leave the device must carry their attribution. Null or blank for public domain.
         */
        val notice: String? = null,
        val locale: Locale = Locale.getDefault(),
        val zone: ZoneId = ZoneId.systemDefault(),
        val now: Instant = Instant.now(),
    )

    /** The faces: Iowan Old Style on iOS (Literata here) and the system sans (Inter here). */
    enum class Face { SERIF, SERIF_BOLD, SERIF_ITALIC, SANS, SANS_BOLD }

    /** The five inks of `NotesPDFRenderer.Palette`, as 0xRRGGBB. */
    enum class Ink(val rgb: Int) {
        INK(rgb(0.11, 0.10, 0.09)),
        QUOTE(rgb(0.27, 0.24, 0.21)),
        SECONDARY(rgb(0.45, 0.43, 0.40)),
        ACCENT(rgb(0.60, 0.42, 0.18)),
        RULE(rgb(0.70, 0.66, 0.60)),
    }

    data class Paragraph(
        val text: String,
        val face: Face,
        val size: Float,
        val ink: Ink,
        val centered: Boolean = false,
        /** Extra space between letters, in points — Core Text's `kern`. */
        val kern: Float = 0f,
        val indent: Float = 0f,
        /** Core Text's `lineHeightMultiple`. */
        val lineHeight: Float = 1.15f,
        val spacingBefore: Float = 0f,
        val spacingAfter: Float = 0f,
    )

    fun paragraphs(notes: List<KeepsakeNote>, options: Options, verseText: (VerseRange) -> String?): List<Paragraph> {
        val out = mutableListOf<Paragraph>()
        out += Paragraph(options.title, Face.SERIF_BOLD, 26f, Ink.INK, spacingAfter = 4f)
        options.subtitle?.let { out += Paragraph(it, Face.SERIF_ITALIC, 11f, Ink.SECONDARY, spacingAfter = 6f) }
        val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(options.locale).format(options.now.atZone(options.zone))
        var summary = AppText.plural(R.string.export_pdf_summary_one, R.string.export_pdf_summary_other, notes.size, notes.size, date)
        options.translation?.let { summary = AppText.get(R.string.export_pdf_summary_translation, summary, it) }
        out += Paragraph(summary, Face.SANS, 9.5f, Ink.SECONDARY, spacingAfter = 26f)

        val dateOptions = NotesTextExport.Options(locale = options.locale, zone = options.zone)
        notes.forEachIndexed { index, note ->
            if (index > 0) {
                out += Paragraph("·   ·   ·", Face.SERIF, 12f, Ink.RULE, centered = true, spacingBefore = 6f, spacingAfter = 20f)
            }
            out += Paragraph(note.displayTitle, Face.SERIF_BOLD, 17f, Ink.INK, spacingAfter = 3f)
            if (note.anchors.isNotEmpty()) {
                out += Paragraph(note.anchorSummary.uppercase(options.locale), Face.SANS_BOLD, 8.5f, Ink.ACCENT, kern = 0.9f, spacingAfter = 10f)
            }
            options.translation?.let { translation ->
                for (range in note.anchors) {
                    val verses = verseText(range)
                    if (verses.isNullOrEmpty()) continue
                    out += Paragraph(verses, Face.SERIF_ITALIC, 10.5f, Ink.QUOTE, indent = 16f, lineHeight = 1.3f, spacingAfter = 2f)
                    out += Paragraph("— ${range.display} ($translation)", Face.SANS, 8.5f, Ink.SECONDARY, indent = 16f, spacingAfter = 10f)
                }
            }
            val body = note.body.trim()
            if (body.isNotEmpty()) {
                for (paragraph in body.split("\n")) {
                    out += Paragraph(paragraph.ifEmpty { " " }, Face.SERIF, 11.5f, Ink.INK, lineHeight = 1.38f, spacingAfter = 5f)
                }
            }
            out += Paragraph(NotesTextExport.dateLine(note, dateOptions), Face.SANS, 8.5f, Ink.SECONDARY, spacingBefore = 6f, spacingAfter = 18f)
        }
        val notice = options.notice?.trim()
        if (!notice.isNullOrEmpty()) {
            out += Paragraph(notice, Face.SANS, 8.5f, Ink.SECONDARY, lineHeight = 1.3f, spacingBefore = 12f)
        }
        return out
    }

    /**
     * Letter where the locale measures in US units, A4 elsewhere — iOS's
     * `Locale.current.measurementSystem == .us`, which Foundation answers for the US, Liberia and Myanmar.
     */
    fun pageSize(locale: Locale = Locale.getDefault()): Pair<Float, Float> =
        if (locale.country in US_MEASURE) 612f to 792f else 595.28f to 841.89f

    /** The page margins: 66 pt at the sides, 72 at top and bottom. */
    const val MARGIN_X = 66f
    const val MARGIN_Y = 72f

    private val US_MEASURE = setOf("US", "LR", "MM")

    private fun rgb(r: Double, g: Double, b: Double): Int =
        (Math.round(r * 255).toInt() shl 16) or (Math.round(g * 255).toInt() shl 8) or Math.round(b * 255).toInt()
}
