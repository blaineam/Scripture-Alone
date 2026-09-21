package com.blainemiller.scripturealone.ui.export

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.keepsake.KeepsakeNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.Locale

/**
 * The PDF on a device: it opens, it paginates, and a licensed text's notice is set after the last
 * note. The files are left in the app's files directory (`pdf-check/`) to be pulled and looked at.
 */
@RunWith(AndroidJUnit4::class)
class NotesPdfRendererTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val at = Instant.parse("2026-09-18T15:00:00Z")

    private fun note(i: Int) = KeepsakeNote.of(
        "Week $i", "What the sermon said, week $i.\n\nAnd a second paragraph that runs on long enough to wrap onto another line of the page.",
        listOf(VerseRange.of(VerseRef(45, 8, i.coerceIn(1, 39)))), at, at,
    )

    private fun render(notes: List<KeepsakeNote>, notice: String?, name: String): Int {
        val bytes = NotesPdfRenderer(context).render(
            notes, NotesPdfDocument.Options("Dad’s Notes", translation = "ESV", notice = notice, locale = Locale.US),
        ) { "1 There is therefore now no condemnation for those who are in Christ Jesus." }
        val file = File(File(context.filesDir, "pdf-check").apply { mkdirs() }, name)
        file.writeBytes(bytes)
        assertTrue(String(bytes, 0, 5) == "%PDF-")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }
    }

    @Test fun aShortExportIsOneLetterPage() {
        val notice = "Scripture quotations are from the ESV® Bible (The Holy Bible, English Standard Version®), © 2001 by Crossway, a publishing ministry of Good News Publishers. Used by permission. All rights reserved."
        assertEquals(1, render(listOf(note(1), note(2)), notice, "notice.pdf"))
        val page = ParcelFileDescriptor.open(File(context.filesDir, "pdf-check/notice.pdf"), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r -> r.openPage(0).use { it.width to it.height } }
        }
        assertEquals(612 to 792, page)
    }

    @Test fun aLongExportRunsOntoMorePages() {
        assertTrue(render((1..30).map(::note), "© Publisher", "long.pdf") >= 4)
    }
}
