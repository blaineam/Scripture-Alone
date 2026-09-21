package com.blainemiller.scripturealone.ui.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.keepsake.KeepsakeNote
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.ceil

/**
 * Typesets notes into a PDF — `NotesPDFRenderer.swift` — with `android.graphics.pdf.PdfDocument`:
 * [NotesPdfDocument]'s paragraphs wrapped by `StaticLayout` and set line by line down pages of Letter
 * or A4, with the document's title and the page number in each page's footer.
 *
 * The iOS faces are Iowan Old Style and the system sans; here they are the faces the reader already
 * ships for them, Literata and Inter (see `ReaderFontFamily`). Sizes, colours, spacing and margins are
 * the Swift values, in PDF points.
 */
class NotesPdfRenderer(private val context: Context) {

    fun render(
        notes: List<KeepsakeNote>,
        options: NotesPdfDocument.Options,
        verseText: (VerseRange) -> String?,
    ): ByteArray = render(NotesPdfDocument.paragraphs(notes, options, verseText), options.title, options.locale)

    fun render(paragraphs: List<NotesPdfDocument.Paragraph>, title: String, locale: Locale = Locale.getDefault()): ByteArray {
        val (pageWidth, pageHeight) = NotesPdfDocument.pageSize(locale)
        val width = pageWidth - NotesPdfDocument.MARGIN_X * 2
        val bottom = pageHeight - NotesPdfDocument.MARGIN_Y
        val document = PdfDocument()
        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun finishPage() {
            val current = page ?: return
            drawFooter(current.canvas, pageNumber, title, pageWidth, pageHeight)
            document.finishPage(current)
            page = null
        }
        fun newPage(): Canvas {
            finishPage()
            pageNumber++
            val info = PdfDocument.PageInfo.Builder(ceil(pageWidth).toInt(), ceil(pageHeight).toInt(), pageNumber).create()
            page = document.startPage(info)
            y = NotesPdfDocument.MARGIN_Y
            return page!!.canvas
        }

        var canvas = newPage()
        for (paragraph in paragraphs) {
            val paint = paint(paragraph)
            val available = (width - paragraph.indent).toInt().coerceAtLeast(1)
            // Core Text multiplies the face's own line height. Literata and Inter stand taller than Iowan
            // and San Francisco (Literata about 1.38 em against Iowan's 1.2), so the multiple is applied
            // to the iOS faces' height instead, keeping the page's rhythm the Swift one.
            val metrics = paint.fontMetrics
            val natural = metrics.descent - metrics.ascent
            val target = paragraph.size * IOS_LINE_HEIGHT * paragraph.lineHeight
            val layout = StaticLayout.Builder.obtain(paragraph.text, 0, paragraph.text.length, paint, available)
                .setAlignment(if (paragraph.centered) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(target - natural, 1f)
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()
            // Space before a paragraph is dropped at the top of a page, as a typesetter drops it.
            if (y > NotesPdfDocument.MARGIN_Y) y += paragraph.spacingBefore
            for (line in 0 until layout.lineCount) {
                val top = layout.getLineTop(line).toFloat()
                val height = layout.getLineBottom(line) - top
                if (y + height > bottom && y > NotesPdfDocument.MARGIN_Y) canvas = newPage()
                // Each line is drawn as its own text run, so the PDF's text is exactly what's on the page.
                val x = NotesPdfDocument.MARGIN_X + paragraph.indent + layout.getLineLeft(line)
                val baseline = y + (layout.getLineBaseline(line) - top)
                val end = layout.getLineVisibleEnd(line)
                val start = layout.getLineStart(line)
                if (end > start) canvas.drawText(paragraph.text, start, end, x, baseline, paint)
                y += height
            }
            y += paragraph.spacingAfter
        }
        finishPage()
        return ByteArrayOutputStream().use { out ->
            document.writeTo(out)
            document.close()
            out.toByteArray()
        }
    }

    /** The title at the left and the page number at the right, 8 pt, half a margin up from the foot. */
    private fun drawFooter(canvas: Canvas, page: Int, title: String, pageWidth: Float, pageHeight: Float) {
        val paint = paint(NotesPdfDocument.Face.SANS, 8f, NotesPdfDocument.Ink.SECONDARY)
        val baseline = pageHeight - NotesPdfDocument.MARGIN_Y / 2
        val room = pageWidth - NotesPdfDocument.MARGIN_X * 2 - 40f
        val shown = android.text.TextUtils.ellipsize(title, paint, room, android.text.TextUtils.TruncateAt.END).toString()
        canvas.drawText(shown, NotesPdfDocument.MARGIN_X, baseline, paint)
        val number = page.toString()
        canvas.drawText(number, pageWidth - NotesPdfDocument.MARGIN_X - paint.measureText(number), baseline, paint)
    }

    private fun paint(paragraph: NotesPdfDocument.Paragraph): TextPaint =
        paint(paragraph.face, paragraph.size, paragraph.ink).apply {
            // Core Text's kern is points between letters; Android's letter spacing is in ems.
            if (paragraph.kern != 0f) letterSpacing = paragraph.kern / paragraph.size
        }

    private fun paint(face: NotesPdfDocument.Face, size: Float, ink: NotesPdfDocument.Ink): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            color = 0xFF000000.toInt() or ink.rgb
            typeface = typeface(face)
        }

    private val faces = HashMap<NotesPdfDocument.Face, Typeface>()

    /**
     * Static instances of the reader's Literata and Inter (`assets/pdf-fonts`, made by
     * `tools/pdf_fonts.py`): the PDF writer embeds a static face as a subset TrueType font, but draws
     * a variable one's every glyph as a Type 3 outline on every page — megabytes, and poor to search.
     */
    private fun typeface(face: NotesPdfDocument.Face): Typeface = faces.getOrPut(face) {
        val file = when (face) {
            NotesPdfDocument.Face.SERIF -> "literata_regular.ttf"
            NotesPdfDocument.Face.SERIF_BOLD -> "literata_bold.ttf"
            NotesPdfDocument.Face.SERIF_ITALIC -> "literata_italic.ttf"
            NotesPdfDocument.Face.SANS -> "inter_regular.ttf"
            // iOS's emphasized system font is semibold.
            NotesPdfDocument.Face.SANS_BOLD -> "inter_semibold.ttf"
        }
        runCatching { Typeface.createFromAsset(context.assets, "pdf-fonts/$file") }.getOrElse {
            when (face) {
                NotesPdfDocument.Face.SANS, NotesPdfDocument.Face.SANS_BOLD -> Typeface.SANS_SERIF
                else -> Typeface.SERIF
            }
        }
    }

    private companion object {
        /** Iowan Old Style's and San Francisco's line height, in ems — what Core Text's multiple scales. */
        const val IOS_LINE_HEIGHT = 1.2f
    }
}
