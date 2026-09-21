package com.blainemiller.scripturealone.data.importer

import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID

/**
 * Turns an ePub's spine into `(book, chapter, verse, text)` rows. Ported from
 * `Import/BibleTextExtractor.swift`.
 *
 * There is no single way publishers mark verses, so nothing is assumed globally. Each spine document
 * is scanned once into a flow of blocks, text runs and *candidate* markers; each candidate records
 * every shape that would explain it; and the shape that explains the most candidates in **that
 * file** wins. A product whose front matter, Gospels and Psalms are marked three different ways
 * therefore reads correctly, and a file whose `<sup>`s hold footnote letters is not mistaken for a
 * file whose `<sup>`s hold verse numbers.
 *
 * Chapters come from `<h1>`/`<h2>` headings that name a book and/or number, from reference ids, from
 * a chapter-number class, and — when a file declares nothing — from carrying the previous file's
 * place forward, which is what a chapter split across two spine files needs.
 */
class BibleTextExtractor(val options: Options = Options()) {

    data class Options(
        /** Keep words-of-Christ spans the ePub marks with a class. The reader already renders them. */
        val redLetters: Boolean = true,
        /** Keep section headings ("The Beatitudes") as heading blocks. */
        val headings: Boolean = true,
        /** Keep footnote markers, with the note text when the ePub puts it in the same file. */
        val footnotes: Boolean = true,
    )

    /** Reads every XHTML document in the spine, in order. */
    fun extract(pkg: EPUBPackage): ExtractedBible {
        val assembler = Assembler(options)
        for (item in pkg.spine) {
            val source = try {
                pkg.document(item)
            } catch (error: BibleImportError) {
                assembler.bible.notes.add(ImportNote(ImportNote.Severity.WARNING, "${item.path} could not be read: ${error.message}"))
                continue
            }
            assembler.consume(DocumentScanner(options).scan(source), item.path)
        }
        assembler.finish()
        if (assembler.bible.isEmpty) throw BibleImportError.NoScriptureFound()
        return assembler.bible
    }

    /** Documents already in hand, as `(path, xhtml)` pairs — for tests and callers that hold the XHTML. */
    fun extract(documents: List<Pair<String, String>>): ExtractedBible {
        val assembler = Assembler(options)
        for ((path, xhtml) in documents) assembler.consume(DocumentScanner(options).scan(xhtml), path)
        assembler.finish()
        if (assembler.bible.isEmpty) throw BibleImportError.NoScriptureFound()
        return assembler.bible
    }
}

// MARK: - Flow

/** What one scanned document turned into: a linear flow, plus the footnote bodies it carried. */
internal class ScannedDocument {
    var flow: List<FlowItem> = emptyList()
    val notes = HashMap<String, String>()
    val shapeCounts = HashMap<VerseMarkupShape, Int>()
}

internal sealed class FlowItem {
    data class BlockStart(val kind: ExtractedBlock.Kind) : FlowItem()
    data object BlockEnd : FlowItem()
    data class Text(val text: String, val red: Boolean) : FlowItem()
    data class MarkerItem(val marker: Marker) : FlowItem()
    data class Heading(val text: String) : FlowItem()
    data class NoteMarker(val id: String?, val label: String) : FlowItem()
}

/** A candidate verse or chapter number, with every shape that would explain it. */
internal data class Marker(
    val shapes: Set<VerseMarkupShape> = emptySet(),
    val book: BookID? = null,
    val chapter: Int? = null,
    val verse: Int? = null,
    /** The last number of a bridged marker ("1-2"), whose text belongs to [verse]. */
    val through: Int? = null,
    val isChapter: Boolean = false,
)

// MARK: - Scanning one document

/**
 * Walks one XHTML document into a flow. This pass makes no decision about which markup shape the file
 * uses; it only records what it saw.
 */
internal class DocumentScanner(private val options: BibleTextExtractor.Options) {

    private sealed class Capture {
        data class Candidate(val marker: Marker) : Capture()
        data object Heading : Capture()
        data class Note(val id: String) : Capture()
        data class NoteReference(val id: String?) : Capture()
    }

    private class Frame(
        val name: String,
        var capture: Capture? = null,
        var isSkipped: Boolean = false,
        var isRed: Boolean = false,
        var startedBlock: Boolean = false,
    )

    fun scan(xhtml: String): ScannedDocument {
        val document = ScannedDocument()
        val stack = ArrayList<Frame>()
        val buffers = ArrayList<MutableList<FlowItem>>().apply { add(ArrayList()) } // innermost capture buffer is last
        var skipDepth = 0
        var redDepth = 0

        val emit: (FlowItem) -> Unit = { item -> if (skipDepth == 0) buffers.last().add(item) }

        for (event in XMLScanner.scan(xhtml)) {
            when (event) {
                is XMLEvent.Start -> {
                    val tag = event.tag
                    val frame = Frame(tag.name)
                    val epubType = tag.epubType
                    val classes = tag.classes.toSet()
                    val typeWords = SwiftText.split(epubType, ' ')

                    if (tag.name in skippedElements || typeWords.any { it in skippedTypes } ||
                        "toc" in classes || "footnotes" in classes
                    ) {
                        frame.isSkipped = true
                        skipDepth++
                        stack.add(frame)
                        continue
                    }
                    if (skipDepth > 0) {
                        stack.add(frame)
                        continue
                    }

                    if (options.redLetters && (classes.any { it in redClasses } || epubType.contains("x-woc"))) {
                        frame.isRed = true
                        redDepth++
                    }

                    // A note body: captured aside from the text, keyed by id so markers can find it.
                    if (typeWords.any { it in noteTypes } ||
                        (tag.name == "aside" && classes.any { it == "footnote" || it == "note" || it == "fn" })
                    ) {
                        frame.capture = Capture.Note(tag.attribute("id") ?: "")
                        buffers.add(ArrayList())
                        stack.add(frame)
                        continue
                    }

                    // A footnote marker: an anchor pointing at a note.
                    if (tag.name == "a" && isNoteReference(tag)) {
                        frame.capture = Capture.NoteReference(fragment(tag.attribute("href")))
                        buffers.add(ArrayList())
                        stack.add(frame)
                        continue
                    }

                    if (tag.name in blockElements) {
                        emit(FlowItem.BlockEnd)
                        emit(FlowItem.BlockStart(blockKind(tag)))
                        frame.startedBlock = true
                    }

                    if (isHeading(tag)) {
                        frame.capture = Capture.Heading
                        buffers.add(ArrayList())
                        stack.add(frame)
                        continue
                    }

                    val candidate = candidate(tag)
                    if (candidate != null) {
                        // A reference id is a fact about the element, not a guess, so it is emitted even
                        // when the element holds the verse text rather than the number.
                        if ((VerseMarkupShape.REFERENCE_IDENTIFIER in candidate.shapes ||
                                VerseMarkupShape.VERSE_ANCHOR in candidate.shapes) && !isNumberLike(tag)
                        ) {
                            val marker = candidate.copy(
                                shapes = candidate.shapes - VerseMarkupShape.NUMBER_CLASS - VerseMarkupShape.SUPERSCRIPT,
                            )
                            record(document, marker)
                            emit(FlowItem.MarkerItem(marker))
                            stack.add(frame)
                            continue
                        }
                        frame.capture = Capture.Candidate(candidate)
                        buffers.add(ArrayList())
                        stack.add(frame)
                        continue
                    }
                    stack.add(frame)
                }

                is XMLEvent.End -> {
                    val index = stack.indexOfLast { it.name == event.name }
                    if (index < 0) continue
                    // Close everything the document forgot to close.
                    while (stack.size > index) {
                        val frame = stack.removeAt(stack.size - 1)
                        if (frame.isSkipped) {
                            skipDepth = maxOf(0, skipDepth - 1)
                            continue
                        }
                        if (frame.isRed) redDepth = maxOf(0, redDepth - 1)
                        val capture = frame.capture
                        if (capture != null && buffers.size > 1) {
                            val captured = buffers.removeAt(buffers.size - 1)
                            close(capture, captured, document, emit)
                        }
                        if (frame.startedBlock) emit(FlowItem.BlockEnd)
                    }
                }

                is XMLEvent.Text -> {
                    if (skipDepth != 0) continue
                    val collapsed = collapse(event.text)
                    if (collapsed.isEmpty()) continue
                    emit(FlowItem.Text(collapsed, redDepth > 0))
                }
            }
        }
        while (buffers.size > 1) {
            val captured = buffers.removeAt(buffers.size - 1)
            buffers.last().addAll(captured)
        }
        document.flow = buffers[0]
        return document
    }

    /** Decides what a captured element actually was, now that its contents are known. */
    private fun close(capture: Capture, captured: List<FlowItem>, document: ScannedDocument, emit: (FlowItem) -> Unit) {
        val text = plainText(captured)
        when (capture) {
            is Capture.Candidate -> {
                val parsed = verseNumber(text)
                if (parsed != null) {
                    var marker = capture.marker
                    marker = if (marker.isChapter) {
                        marker.copy(chapter = marker.chapter ?: parsed.first)
                    } else {
                        marker.copy(verse = marker.verse ?: parsed.first, through = marker.through ?: parsed.second)
                    }
                    record(document, marker)
                    emit(FlowItem.MarkerItem(marker))
                    return
                }
                // Not a number after all: it was an ordinary span that happened to be called "verse".
                for (item in captured) emit(item)
            }
            Capture.Heading -> {
                val trimmed = SwiftText.trimWhitespace(text)
                if (trimmed.isNotEmpty()) emit(FlowItem.Heading(trimmed))
            }
            is Capture.Note -> {
                val trimmed = SwiftText.trimWhitespace(text)
                if (capture.id.isNotEmpty() && trimmed.isNotEmpty()) document.notes[capture.id] = trimmed
            }
            is Capture.NoteReference -> {
                if (!options.footnotes) return
                emit(FlowItem.NoteMarker(capture.id, SwiftText.trimWhitespace(text)))
            }
        }
    }

    private fun record(document: ScannedDocument, marker: Marker) {
        if (marker.isChapter) return
        for (shape in marker.shapes) document.shapeCounts[shape] = (document.shapeCounts[shape] ?: 0) + 1
    }

    // MARK: Element questions

    private fun isHeading(tag: XMLTag): Boolean {
        if (tag.name in listOf("h1", "h2", "h3", "h4", "h5", "h6")) return true
        return tag.classes.any { name ->
            name.contains("heading") || name.contains("subhead") || name.contains("section-title") ||
                name == "sectiontitle" || name == "psalm-title" || name == "booktitle"
        }
    }

    private fun blockKind(tag: XMLTag): ExtractedBlock.Kind {
        val classes = tag.classes
        if (classes.any { it.startsWith("q2") || it.contains("line2") || it.contains("indent2") }) return ExtractedBlock.Kind.POETRY2
        if (classes.any { it.startsWith("q1") || it.contains("poet") || it.contains("line1") || it == "line" || it == "stanza" }) {
            return ExtractedBlock.Kind.POETRY1
        }
        return when (tag.name) {
            "li" -> ExtractedBlock.Kind.LIST1
            "blockquote" -> ExtractedBlock.Kind.EMBEDDED
            else -> ExtractedBlock.Kind.PARAGRAPH
        }
    }

    /** Does this element's class or tag suggest it holds only a number? */
    private fun isNumberLike(tag: XMLTag): Boolean {
        if (tag.name == "sup") return true
        return tag.classes.any { it.contains("num") || it == "v" || it == "vn" }
    }

    /** Every shape that would explain this element as a verse or chapter marker. */
    private fun candidate(tag: XMLTag): Marker? {
        val shapes = HashSet<VerseMarkupShape>()
        var book: BookID? = null
        var chapter: Int? = null
        var verse: Int? = null
        var isChapter = false
        val classes = tag.classes

        val id = tag.attribute("id") ?: tag.attribute("data-id")
        when (val parsed = id?.let(ScriptureLabels::identifier)) {
            is ScriptureLabels.Identifier.Verse -> {
                shapes += VerseMarkupShape.REFERENCE_IDENTIFIER
                book = parsed.book; chapter = parsed.chapter; verse = parsed.verse
            }
            is ScriptureLabels.Identifier.Chapter -> {
                shapes += VerseMarkupShape.REFERENCE_IDENTIFIER
                book = parsed.book; chapter = parsed.chapter; isChapter = true
            }
            is ScriptureLabels.Identifier.VerseNumber -> {
                shapes += VerseMarkupShape.VERSE_ANCHOR
                verse = parsed.verse
            }
            is ScriptureLabels.Identifier.ChapterNumber -> {
                shapes += VerseMarkupShape.VERSE_ANCHOR
                chapter = parsed.chapter; isChapter = true
            }
            null -> Unit
        }
        if (classes.any(::isChapterNumberClass)) {
            shapes += VerseMarkupShape.NUMBER_CLASS
            isChapter = true
        } else if (classes.any(::isVerseNumberClass)) {
            shapes += VerseMarkupShape.NUMBER_CLASS
        }
        if (tag.name == "sup" && !isChapter) shapes += VerseMarkupShape.SUPERSCRIPT
        return if (shapes.isEmpty()) null else Marker(shapes, book, chapter, verse, null, isChapter)
    }

    private fun isNoteReference(tag: XMLTag): Boolean {
        if (tag.epubType.contains("noteref")) return true
        if (tag.classes.any { it.contains("note") || it == "fn" || it.startsWith("fn-") || it.contains("footnote") }) return true
        val href = tag.attribute("href") ?: return false
        val target = fragment(href)?.lowercase() ?: ""
        return target.startsWith("note") || target.startsWith("fn") || target.startsWith("footnote")
    }

    private fun fragment(href: String?): String? {
        if (href == null) return null
        val hash = href.indexOf('#')
        if (hash < 0) return null
        val value = href.substring(hash + 1)
        return value.ifEmpty { null }
    }

    companion object {
        /** Elements whose subtree is never scripture. */
        val skippedElements: Set<String> = setOf("head", "script", "style", "nav", "svg", "figure", "figcaption")

        /** `epub:type` values whose subtree is never scripture. */
        val skippedTypes: Set<String> = setOf("toc", "landmarks", "loi", "lot", "pagebreak", "page-list", "titlepage", "cover", "colophon")

        /** `epub:type` values that hold a note body. */
        val noteTypes: Set<String> = setOf("footnote", "rearnote", "endnote", "note", "annotation")

        val blockElements: Set<String> = setOf(
            "p", "div", "li", "blockquote", "h1", "h2", "h3", "h4", "h5", "h6",
            "section", "tr", "td", "dd", "dt", "pre", "ul", "ol", "table", "body",
        )

        val redClasses: Set<String> = setOf(
            "wj", "woc", "red", "redletter", "red-letter", "redletters", "red-letters",
            "wordsofchrist", "words-of-christ", "christ", "jesus-words", "sc-wj",
        )

        fun isVerseNumberClass(name: String): Boolean {
            if (name in setOf("v", "vn", "vnum", "vnumber", "verse", "verses", "versenum", "verseno", "versenumber", "vers")) return true
            // "verse-num", "v-num", "verse_number", "vNum1", "bibleverse-number"
            val squashed = name.replace("-", "").replace("_", "")
            if (squashed.startsWith("verse") || squashed.startsWith("vnum") || squashed.startsWith("vno")) return true
            return squashed.contains("versenum") || squashed.contains("verseno")
        }

        fun isChapterNumberClass(name: String): Boolean {
            val squashed = name.replace("-", "").replace("_", "")
            return squashed in setOf("c", "cnum", "chapnum", "chapternum", "chapternumber", "chapter", "chap") ||
                squashed.startsWith("chapnum") || squashed.startsWith("chapternum")
        }

        // MARK: Text helpers

        /**
         * Collapses every whitespace run to one space, keeping the leading/trailing one that separates
         * inline elements.
         */
        fun collapse(raw: String): String {
            val characters = SwiftCharacters(raw)
            val output = StringBuilder(raw.length)
            var pendingSpace = false
            for (i in 0 until characters.count) {
                if (characters.isWhitespace(i) || characters.isChar(i, ' ')) {
                    pendingSpace = true
                    continue
                }
                if (pendingSpace) {
                    output.append(' ')
                    pendingSpace = false
                }
                characters.appendTo(output, i)
            }
            if (pendingSpace) output.append(' ')
            return output.toString()
        }

        fun plainText(items: List<FlowItem>): String {
            val output = StringBuilder()
            for (item in items) {
                if (item is FlowItem.Text) output.append(item.text)
                if (item is FlowItem.Heading) output.append(item.text)
            }
            return output.toString()
        }

        private val bridgeSeparators = setOf('-', '‐', '‑', '‒', '–', '—')

        /** "12", "[12]", "1-2", "1–2" — a verse number, and the last number of a bridged pair. */
        fun verseNumber(raw: String): Pair<Int, Int?>? {
            val characters = SwiftCharacters(raw)
            val split = (0 until characters.count).firstOrNull { i -> bridgeSeparators.any { characters.isChar(i, it) } }
            if (split != null) {
                val first = number(raw.substring(0, characters.start(split)))
                val last = number(raw.substring(characters.end(split)))
                if (first != null && last != null && last > first && last - first < 20) return first to last
            }
            return number(raw)?.let { it to null }
        }

        private const val NUMBER_TRIM = "  [](){}.,:;·•*​\n\t"

        /** "12", " 12 ", "[12]", "12.", "12 " → 12. Anything else → null. */
        fun number(raw: String): Int? {
            val stripped = SwiftText.trim(raw) { cp -> cp < 0x10000 && NUMBER_TRIM.indexOf(cp.toChar()) >= 0 }
            if (stripped.isEmpty() || SwiftText.characterCount(stripped) > 3 || !SwiftText.allNumbers(stripped)) return null
            val value = SwiftText.int(stripped) ?: return null
            return if (value in 1..999) value else null
        }
    }
}

// MARK: - Assembling documents into a Bible

/**
 * Walks each scanned document's flow with the winning markup shape and lays the text into chapters,
 * blocks, fragments and verses.
 */
internal class Assembler(private val options: BibleTextExtractor.Options) {
    val bible = ExtractedBible()

    private var book: BookID? = null
    private var chapter: Int? = null
    private var verse: Int? = null
    private var block: ExtractedBlock? = null
    private var blockKind: ExtractedBlock.Kind = ExtractedBlock.Kind.PARAGRAPH
    private var fragmentIndex: Int? = null
    private val outOfOrder = HashSet<ChapterRef>()
    private val bridged = LinkedHashMap<VerseRef, VerseRef>()
    private var lastVerseNumber = 0
    private var skippedText = 0

    val chapterRef: ChapterRef?
        get() {
            val book = book ?: return null
            val chapter = chapter ?: return null
            return if (chapter > 0) ChapterRef(book, chapter) else null
        }

    fun consume(document: ScannedDocument, path: String) {
        val shape = winningShape(document.shapeCounts)
        bible.shapesByDocument[path] = shape
        closeBlock()

        // A file that declares nothing continues the previous one — that is what a chapter split across
        // two spine files looks like. A file name that names a book is a weak hint, used only when the
        // file itself says nothing.
        val hint = hint(path)
        var documentDeclaredPlace = false

        for (item in document.flow) {
            when (item) {
                is FlowItem.BlockStart -> {
                    closeBlock()
                    blockKind = item.kind
                }
                FlowItem.BlockEnd -> closeBlock()
                is FlowItem.Heading -> {
                    val place = ScriptureLabels.heading(item.text)
                    if (place != null && (place.book != null || place.chapter != null)) {
                        move(place.book, place.chapter)
                        documentDeclaredPlace = true
                    } else if (options.headings && chapterRef != null) {
                        closeBlock()
                        bible.append(ExtractedBlock(ExtractedBlock.Kind.HEADING, item.text), chapterRef!!)
                    }
                }
                is FlowItem.MarkerItem -> {
                    val marker = item.marker
                    val accepted = marker.isChapter || shape in marker.shapes
                    if (marker.isChapter) {
                        move(marker.book, marker.chapter)
                        documentDeclaredPlace = true
                        continue
                    }
                    if (!accepted) {
                        // A rejected marker can still tell us where we are, when nothing else has.
                        if (book == null && marker.book != null) {
                            move(marker.book, marker.chapter)
                            documentDeclaredPlace = true
                        }
                        continue
                    }
                    if (marker.book != null || (marker.chapter != null && marker.chapter != chapter)) {
                        move(marker.book, marker.chapter)
                        documentDeclaredPlace = true
                    }
                    if (book == null && hint.book != null) move(hint.book, hint.chapter)
                    start(marker.verse, marker.through)
                }
                is FlowItem.Text -> append(item.text, item.red && options.redLetters)
                is FlowItem.NoteMarker -> {
                    if (!options.footnotes) continue
                    val body = item.id?.let { document.notes[it] } ?: item.label
                    addFootnote(body)
                }
            }
        }
        closeBlock()
        if (!documentDeclaredPlace && bible.shapesByDocument[path] == VerseMarkupShape.NONE && hint.book != null) {
            // A file we could not read at all, whose name promised a book, is worth saying aloud.
            bible.notes.add(ImportNote(ImportNote.Severity.INFO, "$path named a book but carried no verse markup."))
        }
    }

    fun finish() {
        closeBlock()
        bible.tidy()
        bible.outOfOrderChapters = outOfOrder.toSet()
        bible.bridgedVerses = bridged.toMap()
        if (bridged.isNotEmpty()) {
            bible.notes.add(ImportNote(ImportNote.Severity.INFO, "${bridged.size} verse(s) are printed combined with the verse before them."))
        }
        for (chapter in outOfOrder.sorted()) {
            bible.notes.add(ImportNote(ImportNote.Severity.WARNING, "${chapter.display}: verse numbers ran out of order."))
        }
        if (skippedText > 0) {
            bible.notes.add(
                ImportNote(ImportNote.Severity.WARNING, "$skippedText run(s) of text were dropped because no book or verse was in scope."),
            )
        }
    }

    // MARK: Position

    private fun move(newBook: BookID?, newChapter: Int?) {
        closeBlock()
        if (newBook != null && newBook != book) {
            book = newBook
            chapter = newChapter ?: 1
            verse = null
            lastVerseNumber = 0
            return
        }
        if (newChapter != null && newChapter != chapter) {
            chapter = newChapter
            verse = null
            lastVerseNumber = 0
        } else if (newBook != null && chapter == null) {
            chapter = newChapter ?: 1
        }
    }

    private fun start(number: Int?, through: Int?) {
        val book = book
        if (number == null || book == null) {
            if (verse == null) skippedText++
            return
        }
        if (chapter == null) chapter = 1
        // A verse number we already have means the file moved on to the next chapter without saying
        // so — the common shape when chapter numbers are drop-caps we did not recognise.
        val current = chapterRef
        if (current != null && bible.verses[verseRef(book, current.chapter, number)] != null) {
            chapter = (chapter ?: 1) + 1
            lastVerseNumber = 0
            closeBlock()
        } else if (number < lastVerseNumber && current != null) {
            outOfOrder.add(current)
        }
        verse = number
        lastVerseNumber = number
        val now = chapterRef
        if (through != null && now != null) {
            for (extra in (number + 1)..through) bridged[now.verse(extra)] = now.verse(number)
        }
        openBlockIfNeeded()
        val opened = block!!
        block = opened.copy(fragments = opened.fragments + ExtractedFragment(number, true, ""))
        fragmentIndex = block!!.fragments.size - 1
    }

    // MARK: Text

    private fun openBlockIfNeeded() {
        if (block == null) block = ExtractedBlock(blockKind)
    }

    private fun closeBlock() {
        val finished = block
        val chapterRef = chapterRef
        block = null
        fragmentIndex = null
        if (finished == null || chapterRef == null) return
        val trimmed = finished.copy(fragments = finished.fragments.map { it.copy(text = SwiftText.dropTrailingWhitespace(it.text)) })
        if (trimmed.isEmpty) return
        bible.append(trimmed, chapterRef)
    }

    private fun append(text: String, red: Boolean) {
        val book = book
        val verse = verse
        val chapter = chapter
        if (book == null || verse == null || chapter == null) {
            if (SwiftText.trimWhitespace(text).isNotEmpty()) skippedText++
            return
        }
        openBlockIfNeeded()
        var current = block!!
        if (fragmentIndex == null || current.fragments.isEmpty()) {
            current = current.copy(fragments = current.fragments + ExtractedFragment(verse, false, ""))
            fragmentIndex = current.fragments.size - 1
        }
        val index = fragmentIndex!!
        var fragment = current.fragments[index]
        var chunk = text
        if (fragment.text.isEmpty() || fragment.text.endsWith(' ')) chunk = SwiftText.dropLeadingSpaces(chunk).first
        if (chunk.isEmpty()) {
            block = current
            return
        }
        val startsFragment = fragment.text.isEmpty()
        val start = SwiftText.scalarCount(fragment.text)
        val length = SwiftText.scalarCount(chunk)
        fragment = fragment.copy(text = fragment.text + chunk)
        var spans = emptyList<ScalarSpan>()
        if (red) {
            fragment = fragment.copy(
                spans = ExtractedBible.mergeStyled(fragment.spans + StyledSpan(start, length, StyledSpan.Style.WORDS_OF_CHRIST)),
            )
            spans = listOf(ScalarSpan(0, length))
        }
        block = current.copy(fragments = current.fragments.toMutableList().also { it[index] = fragment })
        bible.appendVerseText(chunk, spans, verseRef(book, chapter, verse), separate = startsFragment)
    }

    private fun addFootnote(body: String) {
        val index = fragmentIndex
        val current = block
        if (body.isEmpty() || index == null || current == null || index >= current.fragments.size) return
        val fragment = current.fragments[index]
        val position = SwiftText.scalarCount(fragment.text)
        val updated = fragment.copy(footnotes = fragment.footnotes + ExtractedFootnote(position, body))
        block = current.copy(fragments = current.fragments.toMutableList().also { it[index] = updated })
    }

    companion object {
        fun winningShape(counts: Map<VerseMarkupShape, Int>): VerseMarkupShape {
            // Ties go to the most explicit shape: an id that names book, chapter and verse beats a class,
            // which beats a bare superscript.
            val order = listOf(
                VerseMarkupShape.REFERENCE_IDENTIFIER, VerseMarkupShape.VERSE_ANCHOR,
                VerseMarkupShape.NUMBER_CLASS, VerseMarkupShape.SUPERSCRIPT,
            )
            var best = VerseMarkupShape.NONE
            var bestCount = 0
            for (shape in order) {
                val count = counts[shape] ?: 0
                if (count > bestCount) {
                    best = shape
                    bestCount = count
                }
            }
            return best
        }

        fun hint(path: String): ScriptureLabels.Place {
            val file = SwiftText.split(path, '/').lastOrNull() ?: path
            val stem = SwiftText.split(file, '.').firstOrNull() ?: file
            return ScriptureLabels.fileStem(stem) ?: ScriptureLabels.Place(null, null)
        }
    }
}
