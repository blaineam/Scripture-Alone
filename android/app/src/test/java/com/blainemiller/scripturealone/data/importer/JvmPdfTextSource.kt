package com.blainemiller.scripturealone.data.importer

import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.File
import java.io.IOException

/**
 * [PdfTextSource] over PDFBox itself — the release the app's Android port is taken from — so the JVM
 * tests run the reader on the same glyphs the app reads. The twin of the app's `PdfBoxTextSource`.
 */
class JvmPdfTextSource private constructor(private val document: PDDocument) : PdfTextSource {
    override val pageCount: Int get() = document.numberOfPages

    override val title: String? get() = document.documentInformation?.title

    override fun glyphs(index: Int): List<PdfGlyph> {
        val glyphs = ArrayList<PdfGlyph>()
        val stripper = object : PDFTextStripper() {
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
        val opener = PdfTextSource.Opener { file: File ->
            val document = try {
                PDDocument.load(file, MemoryUsageSetting.setupMixed(64L * 1024 * 1024))
            } catch (_: InvalidPasswordException) {
                throw BibleImportError.ProtectedByDRM(DRMEvidence.PDF_PASSWORD)
            } catch (error: IOException) {
                throw BibleImportError.UnsupportedFormat(error.message ?: "the PDF could not be opened")
            }
            if (!document.currentAccessPermission.canExtractContent()) {
                document.close()
                throw BibleImportError.ProtectedByDRM(DRMEvidence.PDF_COPY_PROTECTED)
            }
            JvmPdfTextSource(document)
        }
    }
}
