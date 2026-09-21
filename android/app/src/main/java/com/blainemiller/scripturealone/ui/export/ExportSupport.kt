package com.blainemiller.scripturealone.ui.export

import com.blainemiller.scripturealone.data.ChapterVerse
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.keepsake.KeepsakeNote
import com.blainemiller.scripturealone.data.rights.TranslationRights

// The pieces of `ScriptureAlone/Export/ExportSupport.swift` that aren't Android: the order notes are
// exported in, the verse text an export quotes, and safe file names. Pure, so the JVM tests run them.

/** The four formats of `NotesExportSheet.Format`, with iOS's titles and descriptions. */
enum class ExportFormat(val title: String, val detail: String) {
    PDF("PDF", "Typeset for reading and printing."),
    MARKDOWN("Markdown", "One file, for note apps and editors."),
    MARKDOWN_FOLDER("Markdown Folder", "A file for each note."),
    PLAIN_TEXT("Plain Text", "Simple text that opens anywhere.");

    companion object {
        /** A folder of one file is just a file: a single note offers the other three, as on iOS. */
        fun available(noteCount: Int): List<ExportFormat> = if (noteCount == 1) listOf(PDF, MARKDOWN, PLAIN_TEXT) else entries
    }
}

object ExportSupport {

    /** Bible order by first passage; notes without passages last, oldest first — `canonicallySorted`. */
    fun canonicallySorted(notes: List<KeepsakeNote>): List<KeepsakeNote> = notes.sortedWith { a, b ->
        val x = a.anchors.firstOrNull()
        val y = b.anchors.firstOrNull()
        when {
            x != null && y != null && x != y -> compareValuesBy(x, y, { it.start.key }, { it.end.key })
            x == null && y != null -> 1
            x != null && y == null -> -1
            else -> a.createdAt.compareTo(b.createdAt)
        }
    }

    /** Past this many verses a passage is shortened to its opening [SHORTENED_TO] — iOS's `limit`. */
    const val LONG_PASSAGE = 20
    const val SHORTENED_TO = 5

    /**
     * Verse text for export — `ReaderModel.exportVerseText(translation:)`. Long ranges (a note on a
     * whole chapter) are shortened to their opening verses so an export stays about the notes.
     *
     * Gated as every quotation is: nothing at all unless the translation's terms allow an export
     * ([TranslationRights.Permission.NOTES_EXPORT]), and each passage only when its quotation is within
     * the terms' verse limit ([TranslationRights.mayQuote]). [verses] reads a range's verses — verse 0,
     * a heading, never counts or appears.
     */
    fun verseText(rights: TranslationRights, verses: (VerseRange) -> List<ChapterVerse>): (VerseRange) -> String? {
        if (!rights.permits(TranslationRights.Permission.NOTES_EXPORT)) return { null }
        return lookup@{ range ->
            val all = verses(range).filter { it.ref.verse > 0 && range.contains(it.ref.key) }.sortedBy { it.ref.key }
            if (all.isEmpty()) return@lookup null
            val shown = if (all.size > LONG_PASSAGE) all.take(SHORTENED_TO) else all
            if (!rights.mayQuote(shown.size)) return@lookup null
            if (all.size == 1) return@lookup all[0].text
            var text = shown.joinToString(" ") { "${it.ref.verse} ${it.text}" }
            if (shown.size < all.size) text += " …"
            text
        }
    }

    /** Replaces characters file systems refuse — `ExportStaging.safeName`. */
    fun safeName(name: String): String {
        val forbidden = "/\\:*?\"<>|"
        val cleaned = buildString {
            name.codePoints().forEach { cp ->
                val bad = Character.isISOControl(cp) || cp == '\n'.code || cp == '\r'.code || cp == 0x2028 || cp == 0x2029 ||
                    (cp < 0x80 && cp.toChar() in forbidden)
                if (bad) append('-') else appendCodePoint(cp)
            }
        }.trim()
        return cleaned.ifEmpty { "Export" }
    }

    /** "Dad’s Notes", "James’ Notes", or "Notes" — `LegacyNotesPanel.notesTitle`. */
    fun notesTitle(ownerName: String?): String {
        val name = ownerName?.trim()
        if (name.isNullOrEmpty()) return "Notes"
        return if (name.endsWith("s")) "$name’ Notes" else "$name’s Notes"
    }
}
