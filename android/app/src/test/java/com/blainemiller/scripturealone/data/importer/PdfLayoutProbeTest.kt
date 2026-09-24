package com.blainemiller.scripturealone.data.importer

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * How [PDFBibleReader] lays out pages of a real, user-owned PDF named by `SA_PDF_PROBE` — for working
 * out why a passage came out wrong. Skipped otherwise: the file is copyrighted and never enters the
 * repo.
 *
 * - `SA_PDF_PROBE_FIND`: text to look for, `|`-separated; every page holding one is reported.
 * - `SA_PDF_PROBE_PAGES`: page numbers (from 1), comma-separated, reported whatever they hold.
 * - `SA_PDF_PROBE_TRACE`: also each row's box, column and whether it spans the page.
 * - `SA_PDF_PROBE_GLYPHS`: also every glyph.
 * - `SA_PDF_PROBE_OUT`: where the report goes (the temporary directory by default).
 */
class PdfLayoutProbeTest {
    @Test fun layout() {
        val path = System.getenv("SA_PDF_PROBE")
        assumeTrue("SA_PDF_PROBE is not set", !path.isNullOrEmpty())
        val find = System.getenv("SA_PDF_PROBE_FIND")?.split('|')?.filter { it.isNotEmpty() }.orEmpty()
        val pages = System.getenv("SA_PDF_PROBE_PAGES")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
        val out = StringBuilder()
        JvmPdfTextSource.opener.open(File(path!!)).use { source ->
            for (index in 0 until source.pageCount) {
                val glyphs = source.glyphs(index)
                val trace = StringBuilder()
                val runs = PDFBibleReader.runs(glyphs, trace)
                val text = runs.joinToString("") { it.text }
                if (index + 1 !in pages && find.none { text.contains(it) }) continue
                out.append("=== page ${index + 1}\n")
                if (System.getenv("SA_PDF_PROBE_TRACE") != null) out.append(trace)
                for (run in runs) out.append(String.format("%5.1f %s |%s|\n", run.size, if (run.ownLine) "L" else " ", run.text.replace("\n", "⏎")))
                if (System.getenv("SA_PDF_PROBE_GLYPHS") != null) {
                    for (g in glyphs) {
                        out.append(String.format("  %s x=%.1f y=%.1f w=%.2f h=%.2f s=%.1f %s\n", g.text, g.x, g.baseline, g.width, g.height, g.size, g.font))
                    }
                }
            }
        }
        val directory = File(System.getenv("SA_PDF_PROBE_OUT") ?: System.getProperty("java.io.tmpdir")).also { it.mkdirs() }
        File(directory, "pdf-layout.txt").writeText(out.toString())
    }
}
