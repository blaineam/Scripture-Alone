package com.blainemiller.scripturealone.data.importer

import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID
import java.io.File
import java.io.IOException

/**
 * What a USFM zip says about itself: eBible.org ships `copr.htm` (the copyright page) and often a DBL
 * `metadata.xml`. For an eBible text the licence line is frequently the only thing standing between
 * the app and a licence violation, so it is read first and carried all the way into the store's
 * `meta` row.
 */
data class USFMMetadata(
    val title: String? = null,
    val abbreviation: String? = null,
    val copyright: String? = null,
    val license: String? = null,
    val language: String? = null,
    val identifier: String? = null,
)

/**
 * A zip of USFM books, opened for reading. Ported from `USFMPackage` in `Import/USFMImporter.swift`.
 * Same refusal-first rule as [EPUBPackage]: a protected archive is refused on its entry names alone,
 * before anything is decompressed.
 */
class USFMPackage internal constructor(private val zip: ZipReader) {
    /** The `.usfm`/`.sfm` entries, sorted by name (eBible numbers them canonically). */
    val files: List<String>
    val metadata: USFMMetadata

    constructor(data: ByteArray) : this(ZipReader(data))

    init {
        EPUBPackage.protectionEvidence(zip.names)?.let { throw BibleImportError.ProtectedByDRM(it) }
        if (zip.entries.any { it.isEncrypted }) throw BibleImportError.ProtectedByDRM(DRMEvidence.ZIP_ENTRY_ENCRYPTION)
        files = zip.names.filter { name ->
            val lowered = name.lowercase()
            lowered.endsWith(".usfm") || lowered.endsWith(".sfm")
        }.sorted()
        if (files.isEmpty()) throw BibleImportError.UnsupportedFormat("the archive holds no USFM files")
        metadata = readMetadata(zip)
    }

    fun source(file: String): String = EPUBPackage.text(zip.data(file))

    companion object {
        fun open(file: File): USFMPackage {
            val data = try {
                file.readBytes()
            } catch (error: IOException) {
                throw BibleImportError.UnreadableFile(error.message ?: error.toString())
            }
            return USFMPackage(data)
        }

        // MARK: - Metadata

        internal fun readMetadata(zip: ZipReader): USFMMetadata {
            var metadata = USFMMetadata()
            val metadataName = zip.names.firstOrNull { it.lowercase().endsWith("metadata.xml") }
            if (metadataName != null) {
                val xml = try { EPUBPackage.text(zip.data(metadataName)) } catch (_: BibleImportError) { null }
                if (xml != null) metadata = readDBLMetadata(xml, metadata)
            }
            for (candidate in zip.names) {
                if (!isCopyrightPage(candidate)) continue
                val html = try { EPUBPackage.text(zip.data(candidate)) } catch (_: BibleImportError) { continue }
                val text = plainText(html)
                if (text.isEmpty()) continue
                if (metadata.copyright == null) metadata = metadata.copy(copyright = SwiftText.prefix(text, 1500))
                if (metadata.license == null) metadata = metadata.copy(license = licenseLine(text))
                break
            }
            val copyright = metadata.copyright
            if (metadata.license == null && copyright != null) metadata = metadata.copy(license = licenseLine(copyright))
            return metadata
        }

        fun isCopyrightPage(name: String): Boolean {
            val leaf = (SwiftText.split(name, '/').lastOrNull() ?: name).lowercase()
            return leaf == "copr.htm" || leaf == "copr.html" || leaf == "copyright.htm" || leaf == "copyright.html"
        }

        /** Digital Bible Library metadata: name, abbreviation and the copyright statement. */
        fun readDBLMetadata(xml: String, initial: USFMMetadata): USFMMetadata {
            var metadata = initial
            val path = ArrayList<String>()
            var capturing: String? = null
            val buffer = StringBuilder()

            fun commit() {
                val field = capturing ?: return
                val value = SwiftText.trimWhitespaceAndNewlines(plainWhitespace(buffer.toString()))
                capturing = null
                buffer.setLength(0)
                if (value.isEmpty()) return
                metadata = when (field) {
                    "name" -> metadata.copy(title = metadata.title ?: value)
                    "abbreviation" -> metadata.copy(abbreviation = metadata.abbreviation ?: value)
                    "statement" -> metadata.copy(copyright = metadata.copyright ?: value)
                    "language" -> metadata.copy(language = metadata.language ?: value)
                    "identifier" -> metadata.copy(identifier = metadata.identifier ?: value)
                    else -> metadata
                }
            }

            for (event in XMLScanner.scan(xml)) {
                when (event) {
                    is XMLEvent.Start -> {
                        path.add(event.tag.name)
                        val inIdentification = "identification" in path
                        val name = event.tag.name
                        if (inIdentification && (name == "name" || name == "namelocal")) {
                            commit()
                            capturing = "name"
                        } else if (inIdentification && (name == "abbreviation" || name == "abbreviationlocal")) {
                            commit()
                            capturing = "abbreviation"
                        } else if (name == "statementcontent" || name == "fullstatement") {
                            if (metadata.copyright == null) {
                                commit()
                                capturing = "statement"
                            }
                        } else if (name == "iso" || (name == "code" && "language" in path)) {
                            commit()
                            capturing = "language"
                        }
                    }
                    is XMLEvent.Text -> if (capturing != null) buffer.append(event.text)
                    is XMLEvent.End -> {
                        if (capturing != null) commit()
                        val index = path.lastIndexOf(event.name)
                        if (index >= 0) while (path.size > index) path.removeAt(path.size - 1)
                    }
                }
            }
            commit()
            return metadata
        }

        /** The recognisable licence, when the copyright page states one. */
        fun licenseLine(text: String): String? {
            val lowered = text.lowercase()
            if (lowered.contains("public domain")) return "Public domain"
            // Searched in `text` itself: an index found in the lowercased copy does not line up with
            // `text` when lowercasing changes a length ("İ" becomes two characters).
            val range = text.indexOf("creative commons", ignoreCase = true)
            if (range >= 0) {
                val tail = SwiftText.prefix(text.substring(range), 90)
                return plainWhitespace(tail)
            }
            for (marker in listOf("cc by-sa", "cc by-nc-nd", "cc by-nc-sa", "cc by-nc", "cc by-nd", "cc by")) {
                if (lowered.contains(marker)) return marker.uppercase()
            }
            if (lowered.contains("all rights reserved")) return "All rights reserved"
            return null
        }

        private val lineBreakingTags = setOf("p", "br", "div", "li", "tr", "h1", "h2", "h3")

        fun plainText(html: String): String {
            val output = StringBuilder()
            for (event in XMLScanner.scan(html)) {
                when (event) {
                    is XMLEvent.Text -> output.append(event.text)
                    is XMLEvent.Start -> if (event.tag.name in lineBreakingTags) output.append('\n')
                    else -> Unit
                }
            }
            return SwiftText.trimWhitespaceAndNewlines(plainWhitespace(output.toString()))
        }

        fun plainWhitespace(text: String): String {
            val characters = SwiftCharacters(text)
            val output = StringBuilder()
            var pending = false
            for (i in 0 until characters.count) {
                if (characters.isWhitespace(i)) {
                    pending = output.isNotEmpty()
                    continue
                }
                if (pending) {
                    output.append(' ')
                    pending = false
                }
                characters.appendTo(output, i)
            }
            return output.toString()
        }
    }
}

/**
 * Reads a zip of USFM books — the shape eBible.org publishes — into the same rows the ePub path
 * produces, so both front-ends feed one [ImportedBibleBuilder]. Ported from `Import/USFMImporter.swift`,
 * itself a port of `Tools/build_bibles.py`, which compiles the bundled ASV, BSB and KJV — so an
 * imported translation renders identically to a bundled one.
 *
 * Two USFM shapes need a decision, and here they are:
 * - **`\v 1-2`** (a bridged verse): the text is stored under the *first* number, which is what the
 *   printed text does, and the other numbers are recorded as combined with it — the coverage report
 *   says "combined", not "missing".
 * - **`\v 1a`** (a partial verse): the letter is dropped and the number used. A number that has
 *   already appeared in the chapter continues that verse with an unnumbered fragment, so
 *   `\v 1a … \v 1b …` prints one verse number, not two.
 */
class USFMImporter(val options: BibleTextExtractor.Options = BibleTextExtractor.Options()) {

    fun extract(pkg: USFMPackage): ExtractedBible {
        val files = ArrayList<Pair<String, String>>()
        val unreadable = ArrayList<ImportNote>()
        for (file in pkg.files) {
            try {
                files.add(file to pkg.source(file))
            } catch (error: BibleImportError) {
                unreadable.add(ImportNote(ImportNote.Severity.WARNING, "$file could not be read: ${error.message}"))
            }
        }
        val bible = extract(files)
        bible.notes.addAll(0, unreadable)
        return bible
    }

    /** Parses USFM sources already in hand, as `(name, usfm)` pairs. */
    fun extract(files: List<Pair<String, String>>): ExtractedBible {
        val bible = ExtractedBible()
        val outOfOrder = HashSet<ChapterRef>()
        val bridged = LinkedHashMap<VerseRef, VerseRef>()
        val notes = ArrayList<ImportNote>()

        // Canonical order, whatever order the archive stored them in.
        data class Parsed(val book: BookID, val name: String, val source: String)
        val parsed = ArrayList<Parsed>()
        for ((name, usfm) in files) {
            val code = USFMBookParser.bookCode(usfm)
            if (code == null) {
                notes.add(ImportNote(ImportNote.Severity.WARNING, "$name: no \\id marker, so it was skipped."))
                continue
            }
            val book = USFMBookParser.book(code)
            if (book == null) {
                notes.add(ImportNote(ImportNote.Severity.WARNING, "$name: unknown book code “$code”, so it was skipped."))
                continue
            }
            parsed.add(Parsed(book, name, usfm))
        }
        for (entry in parsed.sortedBy { it.book.number }) {
            val parser = USFMBookParser(options, entry.book)
            parser.parse(entry.source, bible)
            outOfOrder.addAll(parser.outOfOrder)
            for ((key, value) in parser.bridged) bridged.putIfAbsent(key, value)
            bible.shapesByDocument[entry.name] = VerseMarkupShape.USFM_MARKERS
            notes.addAll(parser.notes)
        }

        bible.tidy()
        bible.outOfOrderChapters = outOfOrder.toSet()
        bible.bridgedVerses = bridged.toMap()
        bible.notes.addAll(notes)
        for (chapter in outOfOrder.sorted()) {
            bible.notes.add(ImportNote(ImportNote.Severity.WARNING, "${chapter.display}: verse numbers ran out of order."))
        }
        if (bridged.isNotEmpty()) {
            bible.notes.add(ImportNote(ImportNote.Severity.INFO, "${bridged.size} verse(s) are printed combined with the verse before them."))
        }
        if (bible.isEmpty) throw BibleImportError.NoScriptureFound()
        return bible
    }
}

// MARK: - The parser

/** One USFM book. A direct port of the `Parser` class in `Tools/build_bibles.py`, by way of Swift. */
internal class USFMBookParser(private val options: BibleTextExtractor.Options, private val book: BookID) {
    val outOfOrder = HashSet<ChapterRef>()
    val bridged = LinkedHashMap<VerseRef, VerseRef>()
    val notes = ArrayList<ImportNote>()

    private var chapter = 0
    private var verse = 0
    private var block: ExtractedBlock? = null
    private var fragmentIndex: Int? = null
    private val styles = ArrayList<StyledSpan.Style>()
    private var inWord = false
    private var inReference = false
    private var note: MutableList<String>? = null
    private var noteField = ""
    private var noteIsCrossReference = false
    private var skipText = false
    private var pendingNumber = false
    private val seenVerses = HashSet<Int>()
    private var lastVerse = 0
    private var pendingVerseIsNumbered = true
    private var pendingVerseStart = false
    /** A verse whose text has so far only appeared in a title (`\d \v 1 …`), and that text. */
    private var titleVerse = 0
    private var titleVerseText = ""

    // MARK: Driving

    fun parse(source: String, bible: ExtractedBible) {
        val characters = SwiftCharacters(source.replace("﻿", ""))
        val count = characters.count
        var index = 0
        val text = StringBuilder()

        while (index < count) {
            if (!characters.isChar(index, '\\')) {
                characters.appendTo(text, index)
                index++
                continue
            }
            addText(text.toString(), bible)
            text.setLength(0)

            var cursor = index + 1
            if (cursor < count && characters.isChar(cursor, '+')) cursor++
            val name = StringBuilder()
            while (cursor < count && characters.isLetter(cursor)) {
                characters.appendTo(name, cursor)
                cursor++
            }
            // A marker name starts with a letter ("q1", "toc2"); "\123" is text, not a marker named
            // "123" that would swallow the digits.
            while (name.isNotEmpty() && cursor < count && characters.isNumber(cursor)) {
                characters.appendTo(name, cursor)
                cursor++
            }
            if (name.isEmpty()) {
                text.append('\\')
                index++
                continue
            }
            var closing = false
            if (cursor < count && characters.isChar(cursor, '*')) {
                closing = true
                cursor++
            }
            index = marker(name.toString().lowercase(), closing, characters, cursor, bible)
        }
        addText(text.toString(), bible)
        closeBlock(bible)
        flushTitleVerse(bible)
    }

    /** Handles one marker whose name ends at [start]; returns the index to continue from. */
    private fun marker(name: String, closing: Boolean, characters: SwiftCharacters, start: Int, bible: ExtractedBible): Int {
        var index = start
        // Footnotes and cross references.
        if (name == "f" || name == "x" || name == "fe") {
            if (closing) {
                finishNote()
                // USFM only eats the space after an *opening* marker; the space after \f* belongs to the
                // sentence.
                return index
            }
            note = ArrayList()
            noteField = "caller"
            noteIsCrossReference = name == "x"
            return skipOneSpace(characters, index)
        }
        if (note != null) {
            if (name.startsWith("f") || name.startsWith("x")) {
                noteField = if (closing) "ft" else name
            } else if (name == "ref") {
                inReference = !closing
            }
            if (!closing) index = skipOneSpace(characters, index)
            return index
        }

        if (closing) {
            val style = characterStyles[name]
            val position = if (style != null) styles.lastIndexOf(style) else -1
            if (style != null && position >= 0) {
                styles.removeAt(position)
            } else if (name == "w") {
                inWord = false
            } else if (name == "ref") {
                inReference = false
            }
            return index
        }

        skipText = false
        if (name in skipped) {
            skipText = true
            closeBlock(bible)
            return index
        }
        if (name == "c") {
            closeBlock(bible)
            flushTitleVerse(bible)
            val (parsed, next) = number(characters, index)
            index = next
            chapter = parsed ?: (chapter + 1)
            verse = 0
            lastVerse = 0
            seenVerses.clear()
            // Red letters can run across a chapter boundary; the other styles cannot.
            styles.retainAll { it == StyledSpan.Style.WORDS_OF_CHRIST }
            return skipOneSpace(characters, index)
        }
        if (name == "v") {
            val (parsed, next) = verseNumber(characters, index)
            index = skipOneSpace(characters, next)
            if (parsed != null) start(parsed.first, parsed.second)
            return index
        }
        headings[name]?.let { kind ->
            startBlock(kind, bible)
            return skipOneSpace(characters, index)
        }
        if (name == "b") {
            startBlock(ExtractedBlock.Kind.STANZA_BREAK, bible)
            return skipOneSpace(characters, index)
        }
        textBlocks[name]?.let { kind ->
            startBlock(kind, bible)
            return skipOneSpace(characters, index)
        }
        characterStyles[name]?.let { style ->
            if (style != StyledSpan.Style.WORDS_OF_CHRIST || options.redLetters) styles.add(style)
            return skipOneSpace(characters, index)
        }
        if (name == "w") {
            inWord = true
            return skipOneSpace(characters, index)
        }
        if (name == "ref") {
            inReference = true
            return skipOneSpace(characters, index)
        }
        // Unknown character markers (tl, it, qs, bk, …) simply pass their text through.
        return skipOneSpace(characters, index)
    }

    // MARK: Cursor helpers

    private fun skipOneSpace(characters: SwiftCharacters, index: Int): Int =
        if (index < characters.count && characters.isWhitespace(index)) index + 1 else index

    /** Leading spaces, then digits. Returns the number (null if none, or not ASCII) and the new index. */
    private fun number(characters: SwiftCharacters, start: Int): Pair<Int?, Int> {
        var index = start
        while (index < characters.count && characters.isChar(index, ' ')) index++
        val digits = StringBuilder()
        while (index < characters.count && characters.isNumber(index)) {
            characters.appendTo(digits, index)
            index++
        }
        return SwiftText.int(digits.toString()) to index
    }

    /** `\v 1`, `\v 1-2`, `\v 1–2`, `\v 1a`. Returns the number and, for a bridge, its last number. */
    private fun verseNumber(characters: SwiftCharacters, start: Int): Pair<Pair<Int, Int?>?, Int> {
        val (first, afterNumber) = number(characters, start)
        var index = afterNumber
        if (first == null) return null to index
        val suffixStart = index
        while (index < characters.count && !characters.isWhitespace(index)) index++
        if (index == suffixStart) return (first to null) to index
        if (!verseSeparators.any { characters.isChar(suffixStart, it) }) return (first to null) to index
        var tailEnd = suffixStart + 1
        while (tailEnd < index && characters.isNumber(tailEnd)) tailEnd++
        val last = SwiftText.int(characters.substring(suffixStart + 1, tailEnd))
        if (last == null || last <= first || last - first >= 20) return (first to null) to index
        return (first to last) to index
    }

    // MARK: Structure

    private fun startBlock(kind: ExtractedBlock.Kind, bible: ExtractedBible) {
        closeBlock(bible)
        if (kind.isHeading && !options.headings) {
            // Drop the heading's own text too, rather than letting it fall into a verse.
            skipText = true
            return
        }
        block = ExtractedBlock(kind, if (kind.isHeading) "" else null)
        fragmentIndex = null
    }

    private fun closeBlock(bible: ExtractedBible) {
        val finished = block
        block = null
        fragmentIndex = null
        if (finished == null || chapter <= 0) return
        if (finished.isEmpty) return
        bible.append(finished, ChapterRef(book, chapter))
    }

    private fun ensureTextBlock(bible: ExtractedBible) {
        val current = block
        if (current == null || current.kind.isHeading || current.kind == ExtractedBlock.Kind.STANZA_BREAK) {
            startBlock(ExtractedBlock.Kind.CONTINUATION, bible)
        }
    }

    private fun start(number: Int, through: Int?) {
        if (through != null) {
            for (extra in (number + 1)..through) bridged[verseRef(book, chapter, extra)] = verseRef(book, chapter, number)
        }
        if (number < lastVerse && chapter > 0) outOfOrder.add(ChapterRef(book, chapter))
        val repeated = number in seenVerses
        verse = number
        lastVerse = number
        seenVerses.add(number)
        pendingVerseIsNumbered = !repeated
        pendingVerseStart = true
    }

    private fun startFragment(numbered: Boolean, bible: ExtractedBible) {
        val open = block
        if (numbered && open != null && open.kind == ExtractedBlock.Kind.TITLE && open.fragments.any { it.text.isNotEmpty() }) {
            // "\d A Psalm of David. \v 1 O LORD…" with no paragraph marker between: the title already
            // has its text, so the verse starts a text block of its own rather than being swallowed
            // into the title (where verse text is never stored).
            startBlock(ExtractedBlock.Kind.CONTINUATION, bible)
        }
        ensureTextBlock(bible)
        var wantsNumber = numbered
        if (numbered && block?.kind == ExtractedBlock.Kind.TITLE) {
            // A superscription ("A Psalm of David.") prints unnumbered; the number moves to the first
            // line of the psalm.
            pendingNumber = true
            wantsNumber = false
        } else if (pendingNumber && block?.kind != ExtractedBlock.Kind.TITLE) {
            wantsNumber = true
            pendingNumber = false
            // The number moved to this line. When the line is the same verse, the title was only its
            // superscription; when it is a later verse, the title was all that verse had.
            if (titleVerse == verse) {
                titleVerse = 0
                titleVerseText = ""
            } else {
                flushTitleVerse(bible)
            }
        }
        val current = block
        if (current != null) {
            block = current.copy(fragments = current.fragments + ExtractedFragment(verse, wantsNumber, ""))
        }
        fragmentIndex = (block?.fragments?.size ?: 1) - 1
    }

    // MARK: Text

    private fun addText(raw: String, bible: ExtractedBible) {
        if (skipText || raw.isEmpty()) return
        var source = raw
        if (inWord || inReference) {
            val bar = source.indexOf('|')
            if (bar >= 0) source = source.substring(0, bar)
        }
        val text = DocumentScanner.collapse(source)
        if (text.isEmpty()) return

        note?.let { parts ->
            if (noteField != "fr" && noteField != "caller") parts.add(text)
            return
        }
        block?.let { current ->
            if (!current.kind.isHeading) return@let
            var addition = text
            val heading = current.heading ?: ""
            if (heading.isEmpty() || heading.endsWith(' ')) addition = SwiftText.dropLeadingSpaces(addition).first
            block = current.copy(heading = heading + addition)
            return
        }
        if (SwiftText.trimWhitespace(text).isEmpty()) {
            val currentText = fragmentIndex?.let { index -> block?.fragments?.get(index)?.text }
            if (currentText == null || currentText.isEmpty() || currentText.endsWith(' ')) return
        }
        if (verse == 0 && block?.kind != ExtractedBlock.Kind.TITLE) return

        if (pendingVerseStart) {
            pendingVerseStart = false
            startFragment(pendingVerseIsNumbered, bible)
        }
        if (fragmentIndex == null) startFragment(false, bible)
        val index = fragmentIndex ?: return
        val current = block ?: return
        if (index >= current.fragments.size) return

        var fragment = current.fragments[index]
        var addition = text
        if (fragment.text.isEmpty() || fragment.text.endsWith(' ')) addition = SwiftText.dropLeadingSpaces(addition).first
        if (addition.isEmpty()) return
        val startsFragment = fragment.text.isEmpty()
        val start = SwiftText.scalarCount(fragment.text)
        val length = SwiftText.scalarCount(addition)
        val added = styles.distinct().map { StyledSpan(start, length, it) }
        fragment = fragment.copy(text = fragment.text + addition, spans = ExtractedBible.mergeStyled(fragment.spans + added))
        block = current.copy(fragments = current.fragments.toMutableList().also { it[index] = fragment })

        if (verse > 0 && chapter > 0 && block?.kind != ExtractedBlock.Kind.TITLE) {
            val red = if (StyledSpan.Style.WORDS_OF_CHRIST in styles) listOf(ScalarSpan(0, length)) else emptyList()
            bible.appendVerseText(addition, red, verseRef(book, chapter, verse), separate = startsFragment)
        } else if (verse > 0 && chapter > 0 && pendingNumber && fragment.verse == verse) {
            // "\d \v 1 A Psalm of David." — held until it is known whether a line of the psalm carries
            // verse 1 on (the usual shape) or the title was the whole of the verse.
            titleVerse = verse
            titleVerseText = fragment.text
        }
    }

    /**
     * A verse whose only text sat in a title (`\d \v 1 …` followed by the next verse, chapter or
     * book) is stored from that text, rather than going missing.
     */
    private fun flushTitleVerse(bible: ExtractedBible) {
        val pending = titleVerse
        val text = titleVerseText
        titleVerse = 0
        titleVerseText = ""
        if (pending <= 0 || chapter <= 0) return
        val target = verseRef(book, chapter, pending)
        if (bible.verses[target] != null) return
        bible.appendVerseText(text, emptyList(), target, separate = true)
    }

    private fun finishNote() {
        val parts = note
        note = null
        noteField = ""
        if (!options.footnotes || noteIsCrossReference || parts == null) return
        val body = SwiftText.trimWhitespaceAndNewlines(USFMPackage.plainWhitespace(parts.joinToString("")))
        val index = fragmentIndex
        val current = block
        if (body.isEmpty() || index == null || current == null || index >= current.fragments.size) return
        val fragment = current.fragments[index]
        val position = SwiftText.scalarCount(fragment.text)
        val updated = fragment.copy(footnotes = fragment.footnotes + ExtractedFootnote(position, body))
        block = current.copy(fragments = current.fragments.toMutableList().also { it[index] = updated })
    }

    companion object {
        /** Markers whose text is file metadata, never shown. */
        val skipped: Set<String> = setOf(
            "id", "usfm", "ide", "h", "toc1", "toc2", "toc3",
            "mt", "mt1", "mt2", "mt3", "rem", "sts", "cl", "cp", "va", "vp",
        )

        /** Markers that carry only a heading string. */
        val headings: Map<String, ExtractedBlock.Kind> = mapOf(
            "s" to ExtractedBlock.Kind.HEADING, "s1" to ExtractedBlock.Kind.HEADING,
            "s2" to ExtractedBlock.Kind.SUBHEADING, "s3" to ExtractedBlock.Kind.SUBHEADING,
            "ms" to ExtractedBlock.Kind.MAJOR_SECTION, "ms1" to ExtractedBlock.Kind.MAJOR_SECTION,
            "mr" to ExtractedBlock.Kind.PARALLEL, "r" to ExtractedBlock.Kind.PARALLEL,
            "qa" to ExtractedBlock.Kind.ACROSTIC, "sp" to ExtractedBlock.Kind.SUBHEADING,
        )

        /** Markers that carry verse text. */
        val textBlocks: Map<String, ExtractedBlock.Kind> = mapOf(
            "p" to ExtractedBlock.Kind.PARAGRAPH, "m" to ExtractedBlock.Kind.CONTINUATION,
            "nb" to ExtractedBlock.Kind.CONTINUATION, "pmo" to ExtractedBlock.Kind.EMBEDDED,
            "pm" to ExtractedBlock.Kind.EMBEDDED, "pc" to ExtractedBlock.Kind.CENTERED,
            "pi" to ExtractedBlock.Kind.EMBEDDED, "pi1" to ExtractedBlock.Kind.EMBEDDED,
            "mi" to ExtractedBlock.Kind.EMBEDDED,
            "li" to ExtractedBlock.Kind.LIST1, "li1" to ExtractedBlock.Kind.LIST1, "li2" to ExtractedBlock.Kind.LIST2,
            "q" to ExtractedBlock.Kind.POETRY1, "q1" to ExtractedBlock.Kind.POETRY1,
            "q2" to ExtractedBlock.Kind.POETRY2, "q3" to ExtractedBlock.Kind.POETRY2,
            "qr" to ExtractedBlock.Kind.SELAH, "qc" to ExtractedBlock.Kind.CENTERED,
            "d" to ExtractedBlock.Kind.TITLE,
        )

        val characterStyles: Map<String, StyledSpan.Style> = mapOf(
            "wj" to StyledSpan.Style.WORDS_OF_CHRIST, "add" to StyledSpan.Style.SUPPLIED, "nd" to StyledSpan.Style.SMALL_CAPS,
        )

        private val verseSeparators = listOf('-', '‐', '‑', '‒', '–', '—', ',')

        /** The `\id` code at the top of a USFM file. */
        fun bookCode(source: String): String? {
            val found = source.indexOf("\\id")
            if (found < 0) return null
            val tail = SwiftText.prefix(source.substring(found + 3), 40)
            val token = SwiftText.split(tail) { chars, i -> chars.isWhitespace(i) }.firstOrNull()
            if (token.isNullOrEmpty()) return null
            return SwiftText.prefix(token, 3).uppercase()
        }

        /** Maps a USFM code onto the canon the app already carries ([BookID.code]). */
        fun book(code: String): BookID? {
            val wanted = code.uppercase()
            BookID.entries.firstOrNull { it.code == wanted }?.let { return it }
            // A handful of files use older three-letter codes the canon lists as an alias.
            return ScriptureLabels.exactBook(wanted.lowercase())
        }
    }
}
