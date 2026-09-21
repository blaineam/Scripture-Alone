package com.blainemiller.scripturealone.ui.export

import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.keepsake.KeepsakeNote
import com.blainemiller.scripturealone.ui.export.NotesPdfDocument.Face
import com.blainemiller.scripturealone.ui.export.NotesPdfDocument.Ink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/** The PDF's content and type, paragraph by paragraph, against `NotesPDFRenderer.document`. */
class NotesPdfDocumentTest {

    private val at = Instant.parse("2026-09-18T15:00:00Z")
    private val notes = listOf(
        KeepsakeNote.of("No condemnation", "Pastor Jim, Sunday.\n\nLine two.", listOf(VerseRange.of(VerseRef(45, 8, 1), VerseRef(45, 8, 2))), at, at),
        KeepsakeNote.of("", "A thought.", emptyList(), at, at.plusSeconds(86_400 * 3)),
    )

    private fun options(translation: String? = "ESV", notice: String? = null) = NotesPdfDocument.Options(
        title = "Dad’s Notes", translation = translation, notice = notice, locale = Locale.US, zone = ZoneOffset.UTC, now = at,
    )

    private val verses: (VerseRange) -> String? = { "1 For there is… 2 For the law…" }

    @Test fun titleBlockThenEachNote() {
        val p = NotesPdfDocument.paragraphs(notes, options(), verses)
        assertEquals(NotesPdfDocument.Paragraph("Dad’s Notes", Face.SERIF_BOLD, 26f, Ink.INK, spacingAfter = 4f), p[0])
        assertEquals("2 notes · September 18, 2026 · Scripture from the ESV", p[1].text)
        assertEquals(26f, p[1].spacingAfter)
        assertEquals(
            listOf(
                "No condemnation", "ROMANS 8:1–2", "1 For there is… 2 For the law…", "— Romans 8:1–2 (ESV)",
                "Pastor Jim, Sunday.", " ", "Line two.", "Written September 18, 2026",
                "·   ·   ·", "Untitled Note", "A thought.", "Written September 18, 2026 · Edited September 21, 2026",
            ),
            p.drop(2).map { it.text },
        )
        val anchors = p[3]
        assertEquals(Face.SANS_BOLD, anchors.face)
        assertEquals(Ink.ACCENT, anchors.ink)
        assertEquals(0.9f, anchors.kern)
        val quote = p[4]
        assertEquals(Face.SERIF_ITALIC, quote.face)
        assertEquals(16f, quote.indent)
        assertEquals(1.3f, quote.lineHeight)
        assertTrue(p[10].centered)
    }

    @Test fun withoutATranslationNoVersesAreQuoted() {
        val p = NotesPdfDocument.paragraphs(notes, options(translation = null), verses)
        assertEquals("2 notes · September 18, 2026", p[1].text)
        assertFalse(p.any { it.text.startsWith("—") })
    }

    @Test fun theNoticeEndsTheDocument() {
        val notice = "Scripture quotations are from the ESV® Bible, © 2001 by Crossway."
        val p = NotesPdfDocument.paragraphs(notes, options(notice = "  $notice\n"), verses)
        assertEquals(NotesPdfDocument.Paragraph(notice, Face.SANS, 8.5f, Ink.SECONDARY, lineHeight = 1.3f, spacingBefore = 12f), p.last())
        assertEquals("Written September 18, 2026 · Edited September 21, 2026", NotesPdfDocument.paragraphs(notes, options(notice = " "), verses).last().text)
    }

    @Test fun inksAreTheSwiftPalette() {
        assertEquals(0x1C1A17, Ink.INK.rgb)
        assertEquals(0x996B2E, Ink.ACCENT.rgb)
    }

    @Test fun letterForUsMeasureA4Elsewhere() {
        assertEquals(612f to 792f, NotesPdfDocument.pageSize(Locale.US))
        assertEquals(595.28f to 841.89f, NotesPdfDocument.pageSize(Locale.UK))
        assertEquals(595.28f to 841.89f, NotesPdfDocument.pageSize(Locale.CANADA))
    }
}
