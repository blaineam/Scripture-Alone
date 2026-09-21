package com.blainemiller.scripturealone.data.keepsake

import com.blainemiller.scripturealone.data.VerseRange
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Markdown and plain-text renderings of notes, for export. Ported from `Keepsake/NotesTextExport.swift`;
 * pure, so it's testable on the JVM. The app supplies verse text through `verseText` and does the PDF.
 */
object NotesTextExport {

    data class Options(
        /** Heading for a multi-note document ("Notes", "Dad’s Notes"). */
        val title: String = "Notes",
        /** Translation abbreviation shown after quoted passages ("ASV"), or null to omit verse text. */
        val translation: String? = null,
        /**
         * The publisher's copyright line, when the quoted translation requires one. It is written at
         * the end of the document: a file of quotations that leaves the device without its attribution
         * is the thing publishers' permissions actually forbid.
         */
        val notice: String? = null,
        /** Where "Written September 18, 2026" is formatted — the device's locale and zone by default. */
        val locale: Locale = Locale.getDefault(),
        val zone: ZoneId = ZoneId.systemDefault(),
    )

    private val RULE = "—".repeat(24)

    /** The publisher's line, at the foot of an export that quotes their text. */
    internal fun noticeBlock(options: Options, markdown: Boolean): String {
        val notice = options.notice?.trim()
        if (notice.isNullOrEmpty()) return ""
        return if (markdown) "\n---\n\n$notice\n" else "\n$RULE\n$notice\n"
    }

    private fun withNotice(markdown: String, options: Options): String {
        val block = noticeBlock(options, markdown = true)
        return if (block.isEmpty()) markdown else markdown + "\n" + block
    }

    // Markdown

    /**
     * One Markdown document holding every note. Every export that quotes a licensed text ends with its
     * notice — a single note and each file of a folder too, which the Swift original misses.
     */
    fun markdown(notes: List<KeepsakeNote>, options: Options, verseText: (VerseRange) -> String?): String {
        if (notes.size == 1) return withNotice(markdown(notes[0], 1, options, verseText), options)
        val parts = mutableListOf("# ${options.title}", exportedLine(notes.size, options))
        for (note in notes) {
            parts += "---"
            parts += markdown(note, 2, options, verseText)
        }
        return parts.joinToString("\n\n") + "\n" + noticeBlock(options, markdown = true)
    }

    /** One Markdown document per note, with unique, file-system-safe names. */
    fun markdownFiles(notes: List<KeepsakeNote>, options: Options, verseText: (VerseRange) -> String?): List<Pair<String, String>> {
        val used = mutableSetOf<String>()
        return notes.map { note ->
            val base = fileName(note, options.zone)
            var name = "$base.md"
            var n = 2
            while (name.lowercase() in used) {
                name = "$base $n.md"
                n++
            }
            used += name.lowercase()
            name to markdown(note, 1, options, verseText) + "\n" + noticeBlock(options, markdown = true)
        }
    }

    fun markdown(note: KeepsakeNote, headingLevel: Int, options: Options, verseText: (VerseRange) -> String?): String {
        val lines = mutableListOf("#".repeat(headingLevel) + " " + note.displayTitle)
        if (note.anchors.isNotEmpty()) {
            lines += ""
            lines += "**${note.anchorSummary}**"
        }
        if (options.translation != null) {
            for (range in note.anchors) {
                val text = verseText(range)
                if (text.isNullOrEmpty()) continue
                lines += ""
                for (line in text.split('\n')) lines += "> $line"
                lines += "> — ${range.display} (${options.translation})"
            }
        }
        val body = note.body.trim()
        if (body.isNotEmpty()) {
            lines += ""
            lines += body
        }
        lines += ""
        lines += "*${dateLine(note, options)}*"
        return lines.joinToString("\n")
    }

    // Plain text

    fun plainText(notes: List<KeepsakeNote>, options: Options, verseText: (VerseRange) -> String?): String {
        val blocks = mutableListOf<String>()
        if (notes.size > 1) blocks += options.title.uppercase(options.locale) + "\n" + exportedLine(notes.size, options)
        for (note in notes) {
            val lines = mutableListOf(note.displayTitle)
            if (note.anchors.isNotEmpty()) lines += note.anchorSummary
            if (options.translation != null) {
                for (range in note.anchors) {
                    val text = verseText(range)
                    if (text.isNullOrEmpty()) continue
                    lines += ""
                    lines += "    “" + text.replace("\n", "\n    ") + "”"
                    lines += "    — ${range.display} (${options.translation})"
                }
            }
            val body = note.body.trim()
            if (body.isNotEmpty()) {
                lines += ""
                lines += body
            }
            lines += ""
            lines += dateLine(note, options)
            blocks += lines.joinToString("\n")
        }
        return blocks.joinToString("\n\n$RULE\n\n") + "\n" + noticeBlock(options, markdown = false)
    }

    // Helpers

    private fun longDate(instant: Instant, options: Options): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(options.locale).format(instant.atZone(options.zone))

    fun dateLine(note: KeepsakeNote, options: Options = Options()): String {
        val created = longDate(note.createdAt, options)
        val edited = longDate(note.updatedAt, options)
        return if (created == edited) "Written $created" else "Written $created · Edited $edited"
    }

    internal fun exportedLine(count: Int, options: Options, now: Instant = Instant.now()): String {
        val noun = if (count == 1) "note" else "notes"
        var line = "$count $noun, exported ${longDate(now, options)}"
        if (options.translation != null) line += " · Scripture quoted from the ${options.translation}"
        return line
    }

    /** "2026-09-18 Romans 8 sermon" — dated so a folder sorts by when notes were written. */
    fun fileName(note: KeepsakeNote, zone: ZoneId = ZoneId.systemDefault()): String {
        val day = note.createdAt.atZone(zone).toLocalDate().toString()
        val forbidden = "/\\:*?\"<>|\n\r\t"
        val safe = StringBuilder()
        note.displayTitle.codePoints().forEach { cp ->
            safe.append(if (cp < 0x20 || cp in 0x7F..0x9F || (cp < 0x80 && cp.toChar() in forbidden)) "-" else String(Character.toChars(cp)))
        }
        fun isEdge(c: Char) = c.isWhitespace() || c == '.' || c == '-'
        var title = safe.toString().trim(::isEdge)
        if (title.codePointCount(0, title.length) > 80) {
            title = title.substring(0, title.offsetByCodePoints(0, 80)).trim { it == ' ' || it == '\t' }
        }
        return if (title.isEmpty()) day else "$day $title"
    }
}
