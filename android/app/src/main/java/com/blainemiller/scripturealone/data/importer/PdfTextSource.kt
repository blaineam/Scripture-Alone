package com.blainemiller.scripturealone.data.importer

import java.io.File

/**
 * One glyph a PDF page draws, as its text layer reports it: in page space with y growing downward,
 * in points. [baseline] is where the glyph sits; [height] rises above it; [width] is its advance.
 */
class PdfGlyph(
    val text: String,
    val x: Float,
    val baseline: Float,
    val width: Float,
    val height: Float,
    /** The type size, in points. */
    val size: Float,
    /** The font's name: a change of face (bold, a sans for verse numbers) splits a run as a change of size does. */
    val font: String,
) {
    val right: Float get() = x + width
    val top: Float get() = baseline - height
}

/**
 * A PDF opened for its text layer — the glyphs of each page in the order the page draws them. The
 * reading itself ([PDFBibleReader]) is pure Kotlin over these; the app reads them with PDFBox's Android
 * port, the JVM tests with PDFBox itself, so both run the same reader.
 *
 * Opening refuses a PDF that is locked with a password or whose owner forbids copying its text
 * ([DRMEvidence.PDF_PASSWORD], [DRMEvidence.PDF_COPY_PROTECTED]) — the PDF's own form of protection,
 * honoured as an ePub's is. Nothing here decrypts anything.
 */
interface PdfTextSource : AutoCloseable {
    val pageCount: Int

    /** The document's own title, when its metadata carries one. */
    val title: String?

    /** The glyphs page [index] (from 0) draws, blank ones included. */
    fun glyphs(index: Int): List<PdfGlyph>

    fun interface Opener {
        /** Opens [file], throwing [BibleImportError.ProtectedByDRM] for a protected PDF. */
        fun open(file: File): PdfTextSource
    }
}
