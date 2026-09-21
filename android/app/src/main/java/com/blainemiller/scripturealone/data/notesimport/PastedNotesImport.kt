package com.blainemiller.scripturealone.data.notesimport

import com.blainemiller.scripturealone.data.VerseRange

/**
 * Reads notes out of text a reader pasted or a spreadsheet they exported, from any app at all.
 * Ported from `ScriptureAloneCore/Import/PastedNotesImport.swift`.
 *
 * **Why this is not a list of supported apps.** Olive Tree exports a CSV; Logos exports documents;
 * YouVersion exports nothing and tells people to copy their notes by hand. Writing a parser per app
 * would mean guessing at each vendor's columns from documentation rather than files — and a reference
 * parsed wrong attaches somebody's note to the wrong verse, silently. So this reads by *structure*: a
 * reference, and some text that belongs to it. That covers the exports this app has never seen, and
 * the next one too.
 *
 * Two shapes are understood, told apart by looking rather than by asking:
 *
 * - **A table** — CSV or tab-separated, with or without a header. The column holding references is
 *   found by trying to parse them, not by its name; the note is whichever remaining column has the
 *   most text; a column of colour names or hex is used for highlights when one is there.
 * - **Blocks of text** — a reference on its own line or at the start of one, then the note, with
 *   blank lines between entries. This is what copying notes out of an app by hand produces.
 *
 * A line that names no verse is never guessed at. It is returned in `unresolved` so a reader can be
 * shown exactly what did not come across.
 *
 * Character-level work here walks grapheme clusters, as the Swift walks `Character`s, so a quote or a
 * separator that a combining mark has attached itself to is not mistaken for the bare one.
 */
object PastedNotesImport {

    private val separators = listOf(",", "\t", ";")

    /** @throws NoteImportException [NoteImportError.NOTHING_RECOGNISED] when no reference is found. */
    fun parse(raw: String): ImportedNotes {
        val text = raw.replace("\r\n", "\n").replace("\r", "\n").replace('\u00A0', ' ')
        if (text.trimSwiftWhitespaceAndNewlines().isEmpty()) throw NoteImportException(NoteImportError.NOTHING_RECOGNISED)

        var result = if (looksLikeATable(text)) readTable(text) else readBlocks(text)
        // Falling back rather than failing: a table whose references are in none of its columns is
        // better read as prose than reported as empty.
        if (result.isEmpty && looksLikeATable(text)) result = readBlocks(text)
        if (result.isEmpty) throw NoteImportException(NoteImportError.NOTHING_RECOGNISED)
        return result
    }

    // MARK: Telling a table from prose

    /**
     * A table has a separator appearing the same number of times on most rows. Prose does not — and a
     * note with commas in it is prose, which is why this counts consistency rather than presence.
     *
     * Counted **outside quotes**, which is the whole subtlety: a CSV whose notes contain commas has a
     * different raw comma count on every line, and counting naively decides it is prose and reads the
     * entire export as one run-on block.
     */
    internal fun looksLikeATable(text: String): Boolean {
        for (separator in separators) {
            val counts = rows(text).take(30).map { separatorCount(it, separator) }
            val first = counts.firstOrNull() ?: continue
            if (counts.size < 2 || first <= 0) continue
            val agreeing = counts.count { it == first }
            if (agreeing.toDouble() / counts.size >= 0.8) return true
        }
        return false
    }

    /** Separators at the top level only — those inside a quoted field belong to the text. */
    internal fun separatorCount(line: String, separator: String): Int {
        var count = 0
        var quoted = false
        for (character in line.graphemes()) {
            if (character == "\"") quoted = !quoted else if (character == separator && !quoted) count++
        }
        return count
    }

    /**
     * Splits text into rows, keeping a quoted field together even when it contains newlines. A note
     * someone wrote across three lines is one cell, not three rows.
     *
     * The Swift signature also takes the separator, which it never reads: rows split on newlines only.
     */
    internal fun rows(text: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        for (character in text.graphemes()) {
            if (character == "\"") quoted = !quoted
            if (character == "\n" && !quoted) {
                if (current.toString().trimSwiftWhitespace().isNotEmpty()) out += current.toString()
                current.setLength(0)
            } else {
                current.append(character)
            }
        }
        if (current.toString().trimSwiftWhitespace().isNotEmpty()) out += current.toString()
        return out
    }

    // MARK: Tables

    internal fun readTable(text: String): ImportedNotes {
        val result = ImportedNotes()
        val separator = bestSeparator(text)
        val rows = rows(text)
            .map { fields(it, separator) }
            .filter { row -> row.any { it.trimSwiftWhitespace().isNotEmpty() } }
        if (rows.isEmpty()) return result

        // Which column holds references, decided by parsing every column on every row and taking
        // whichever succeeds most often. A header row simply fails to parse and costs one row.
        val width = rows.maxOf { it.size }
        val successes = IntArray(width)
        for (row in rows) {
            for ((index, field) in row.withIndex()) if (reference(field) != null) successes[index]++
        }
        val referenceColumn = successes.indices.maxByOrNull { successes[it] } ?: return result
        if (successes[referenceColumn] <= 0) return result

        val colourColumn = (0 until width).firstOrNull { column ->
            column != referenceColumn &&
                rows.count { column < it.size && colour(it[column]) != null } > rows.size / 2
        }
        // The note is the wordiest remaining column, measured over the whole table so one long cell
        // cannot decide it.
        val textColumn = (0 until width)
            .filter { it != referenceColumn && it != colourColumn }
            .maxByOrNull { totalLength(rows, it) }

        for ((index, row) in rows.withIndex()) {
            val found = if (referenceColumn < row.size) reference(row[referenceColumn]) else null
            val range = found?.range
            if (found == null || range == null) {
                val line = row.joinToString(" ").trimSwiftWhitespace()
                // A header row is not a failure worth reporting to anybody.
                if (line.isNotEmpty() && index > 0) result.unresolved += line
                continue
            }
            val body = textColumn?.let { if (it < row.size) row[it] else null }
                ?.trimSwiftWhitespaceAndNewlines() ?: ""
            val swatch = colourColumn?.let { if (it < row.size) colour(row[it]) else null }

            if (body.isEmpty()) {
                // A row that names a verse and says nothing about it is a highlight when it has a
                // colour, and a saved verse when it does not.
                if (swatch != null) appendHighlights(range, swatch, found.translation, result)
                else result.saved += range
            } else {
                result.verseNotes += ImportedNotes.Note(range, found.display, body, found.translation)
                if (swatch != null) appendHighlights(range, swatch, found.translation, result)
            }
        }
        return result
    }

    internal fun totalLength(rows: List<List<String>>, column: Int): Int =
        rows.sumOf { if (column < it.size) it[column].graphemeCount() else 0 }

    internal fun bestSeparator(text: String): String {
        var best = "," to 0
        for (separator in separators) {
            val counts = rows(text).take(30).map { separatorCount(it, separator) }
            val first = counts.firstOrNull() ?: continue
            if (first <= 0) continue
            val agreeing = counts.count { it == first }
            if (agreeing > best.second) best = separator to agreeing
        }
        return best.first
    }

    /**
     * Splits one row, honouring quotes and doubled quotes — a note containing a comma is the ordinary
     * case, not an edge one.
     */
    internal fun fields(line: String, separator: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        val characters = line.graphemes()
        var index = 0
        while (index < characters.size) {
            val character = characters[index]
            if (character == "\"") {
                if (quoted && index + 1 < characters.size && characters[index + 1] == "\"") {
                    current.append('"')
                    index++
                } else {
                    quoted = !quoted
                }
            } else if (character == separator && !quoted) {
                out += current.toString()
                current.setLength(0)
            } else {
                current.append(character)
            }
            index++
        }
        out += current.toString()
        return out.map { it.trimSwiftWhitespace() }
    }

    // MARK: Blocks of prose

    internal fun readBlocks(text: String): ImportedNotes {
        val result = ImportedNotes()
        // Entries are separated by blank lines. Where there are none, every line that starts with a
        // reference begins a new entry — which is what a hand-copied list looks like.
        var blocks = text.split("\n\n").filter { it.trimSwiftWhitespaceAndNewlines().isNotEmpty() }
        if (blocks.size <= 1) blocks = splitOnReferenceLines(text)

        for (block in blocks) {
            val lines = block.split("\n")
            val head = lines.firstOrNull { it.trimSwiftWhitespace().isNotEmpty() } ?: continue

            // The reference may be the whole first line, or may lead it: "John 3:16 — God so loved".
            val (found, remainderOfHead) = splitLeadingReference(head)
            val range = found?.range
            if (found == null || range == null) {
                result.unresolved += block.trimSwiftWhitespaceAndNewlines().split("\n")
                    .firstOrNull { it.isNotEmpty() } ?: block
                continue
            }
            val rest = lines.dropWhile { it != head }.drop(1).joinToString("\n")
            val body = listOf(remainderOfHead, rest).joinToString("\n").trimSwiftWhitespaceAndNewlines()

            if (body.isEmpty()) {
                result.saved += range
            } else {
                result.verseNotes += ImportedNotes.Note(range, found.display, body, found.translation)
            }
        }
        return result
    }

    internal fun splitOnReferenceLines(text: String): List<String> {
        val blocks = mutableListOf<String>()
        val current = mutableListOf<String>()
        for (line in text.split("\n")) {
            if (splitLeadingReference(line).first != null && current.isNotEmpty()) {
                blocks += current.joinToString("\n")
                current.clear()
            }
            current += line
        }
        if (current.isNotEmpty()) blocks += current.joinToString("\n")
        return blocks
    }

    /** Punctuation a person writes between a reference and their note. */
    private const val JOINERS = " —-–:·|"

    /**
     * The longest leading run of words that still parses as a reference, and whatever follows it.
     * Longest-first so "John 3:16-17" is not read as "John 3:16".
     */
    internal fun splitLeadingReference(line: String): Pair<LifeBibleImport.Reference?, String> {
        val trimmed = line.trimSwiftWhitespace()
        if (trimmed.isEmpty()) return null to ""
        val words = trimmed.splitOnCharacter(" ", omittingEmpty = true)
        for (count in minOf(words.size, 6) downTo 1) {
            val head = words.take(count).joinToString(" ")
            val cleaned = head.trimCharacters(JOINERS)
            val found = reference(cleaned) ?: continue
            val rest = words.drop(count).joinToString(" ").trimCharacters(JOINERS)
            return found to rest
        }
        return null to trimmed
    }

    // MARK: Shared pieces

    /**
     * The same reference reader the Life Bible import uses, so both sources treat "1 Chronicles 29:14
     * NKJV" identically and there is one place to fix when one of them is wrong.
     */
    internal fun reference(raw: String): LifeBibleImport.Reference? {
        val text = raw.trimSwiftWhitespaceAndNewlines()
        // A bare number is not a reference, however willing a parser might be to read it as one.
        val characters = text.graphemes()
        if (characters.size < 3 || characters.none(::isSwiftLetter)) return null
        return LifeBibleImport.reference(text)
    }

    /** Names people and apps actually use, mapped onto the five this app has. */
    private val colourNames: Map<String, String> = mapOf(
        "yellow" to "yellow", "gold" to "yellow", "orange" to "yellow", "amber" to "yellow",
        "green" to "green", "olive" to "green", "lime" to "green", "teal" to "green",
        "blue" to "blue", "cyan" to "blue", "aqua" to "blue", "navy" to "blue",
        "pink" to "pink", "red" to "pink", "rose" to "pink", "magenta" to "pink",
        "purple" to "purple", "violet" to "purple", "lavender" to "purple", "grey" to "purple",
        "gray" to "purple",
    )

    /** A colour cell, as a name or a hex value. Anything else is not a colour. */
    internal fun colour(raw: String): String? {
        val text = raw.trimSwiftWhitespace().lowercase()
        if (text.isEmpty()) return null
        if (text.startsWith("#") || (text.graphemeCount() == 6 && parseUInt32(text, 16) != null)) {
            return LifeBibleImport.nearestColor(text)
        }
        return colourNames[text]
    }

    internal fun appendHighlights(range: VerseRange, colour: String, translation: String?, result: ImportedNotes) {
        for (key in range.start.key..range.end.key) {
            val verse = VerseRange.ref(key) ?: continue
            result.highlights += ImportedNotes.Highlight(verse, colour, translation)
        }
    }
}
