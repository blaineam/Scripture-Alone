package com.blainemiller.scripturealone.data.importer

import android.content.Context
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.text.AppText
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.io.IOException

/**
 * [PdfTextSource] over PDFBox's Android port. Only the text layer is read: each glyph's text, place,
 * size and font, in the order the page draws them, which is all [PDFBibleReader] needs. Nothing is
 * rendered and nothing is decrypted — a PDF that needs a password, or whose owner forbids copying its
 * text, is refused before a page is read.
 */
class PdfBoxTextSource private constructor(private val document: PDDocument) : PdfTextSource {
    override val pageCount: Int get() = document.numberOfPages

    override val title: String? get() = document.documentInformation?.title

    override fun glyphs(index: Int): List<PdfGlyph> {
        val glyphs = ArrayList<PdfGlyph>()
        val stripper = object : PDFTextStripper() {
            // Every glyph as the page draws it, before the stripper's own line and word guessing.
            override fun processTextPosition(text: TextPosition) {
                glyphs.add(
                    PdfGlyph(
                        text.unicode ?: "", text.xDirAdj, text.yDirAdj, text.widthDirAdj, text.heightDir,
                        text.fontSizeInPt, text.font?.name ?: "",
                    ),
                )
            }
        }
        stripper.sortByPosition = false
        stripper.startPage = index + 1
        stripper.endPage = index + 1
        stripper.getText(document)
        return glyphs
    }

    override fun close() = document.close()

    companion object {
        /** Opens PDFs for the importer; PDFBox's resources (glyph lists, font metrics) load from the app's assets. */
        fun opener(context: Context): PdfTextSource.Opener {
            PDFBoxResourceLoader.init(context.applicationContext)
            return PdfTextSource.Opener { file: File ->
                val document = try {
                    PDDocument.load(file, MemoryUsageSetting.setupMixed(64L * 1024 * 1024))
                } catch (_: InvalidPasswordException) {
                    throw BibleImportError.ProtectedByDRM(DRMEvidence.PDF_PASSWORD)
                } catch (_: IOException) {
                    throw BibleImportError.UnsupportedFormat(AppText.get(R.string.data_import_detail_pdf_unreadable))
                }
                if (!document.currentAccessPermission.canExtractContent()) {
                    document.close()
                    throw BibleImportError.ProtectedByDRM(DRMEvidence.PDF_COPY_PROTECTED)
                }
                PdfBoxTextSource(document)
            }
        }
    }
}
