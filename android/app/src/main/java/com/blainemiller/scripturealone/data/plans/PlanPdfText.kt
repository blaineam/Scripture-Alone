package com.blainemiller.scripturealone.data.plans

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

/** A PDF's text through PDFBox's Android port (the same library the Bible importer reads PDFs with). */
object PlanPdfText {
    /** Reads the whole text layer, rows in reading order; null when the PDF can't be opened. */
    fun extractor(context: Context): (ByteArray) -> String? {
        PDFBoxResourceLoader.init(context.applicationContext)
        return { bytes ->
            runCatching {
                PDDocument.load(bytes).use { document -> PDFTextStripper().apply { sortByPosition = true }.getText(document) }
            }.getOrNull()
        }
    }
}
