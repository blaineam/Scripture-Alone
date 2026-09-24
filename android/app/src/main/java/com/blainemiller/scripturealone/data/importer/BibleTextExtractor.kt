package com.blainemiller.scripturealone.data.importer

import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.reference.ReferenceDetector
import com.blainemiller.scripturealone.data.reference.ReferenceParser
import com.blainemiller.scripturealone.text.AppText

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
 * a chapter-number class, from the file's own `<section title>`, and — when a file declares nothing —
 * from carrying the previous file's place forward, which is what a chapter split across two spine
 * files needs.
 */
class BibleTextExtractor(val options: Options = Options()) {

    data class Options(
        /** Keep words-of-Christ spans the ePub marks with a class. The reader already renders them. */
        val redLetters: Boolean = true,
        /** Keep section headings ("The Beatitudes") as heading blocks. */
        val headings: Boolean = true,
        /** Keep footnote markers, with the note text when the ePub puts it in the same file. */
        val footnotes: Boolean = true,
        /** Keep a study Bible's notes, introductions, essays and pictures, apart from the text. */
        val studyMaterial: Boolean = true,
    )

    /** Reads every XHTML document in the spine, in order. */
    fun extract(pkg: EPUBPackage): ExtractedBible {
        val assembler = Assembler(options)
        val scanner = DocumentScanner(options, DocumentScanner.redClasses(pkg.stylesheets))
        for (item in pkg.spine) {
            val source = try {
                pkg.document(item)
            } catch (error: BibleImportError) {
                assembler.bible.notes.add(ImportNote(ImportNote.Severity.WARNING, AppText.get(R.string.data_import_note_unreadable, item.path, error.message ?: "")))
                continue
            }
            assembler.consume(scanner.scan(source), item.path)
        }
        assembler.finish()
        if (assembler.bible.isEmpty) throw BibleImportError.NoScriptureFound()
        val bible = assembler.bible
        bible.loadStudyImages { path ->
            try {
                pkg.data(path)
            } catch (_: BibleImportError) {
                null
            }
        }
        return bible
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

    /**
     * Anchors that carry their own id and link into another file — the back-link a note body uses to
     * return to its caller. A document full of them is a notes page, not scripture.
     */
    var backLinks = 0

    /**
     * Chapter numbers recognised by class or id. A file of drop-cap chapters is scripture even when it
     * prints no other number.
     */
    var chapterMarkers = 0
}

internal sealed class FlowItem {
    data class BlockStart(val kind: ExtractedBlock.Kind) : FlowItem()
    data object BlockEnd : FlowItem()
    data class Text(val text: String, val red: Boolean) : FlowItem()
    data class MarkerItem(val marker: Marker) : FlowItem()
    data class Heading(val text: String) : FlowItem()
    data class NoteMarker(val id: String?, val label: String) : FlowItem()

    /** The document said which book this is outside its text — `<section title="Romans">`. */
    data class Place(val book: BookID, val chapter: Int?) : FlowItem()

    /** An essay or box set into the text: its title and paragraphs. */
    data class Article(val title: String, val text: String) : FlowItem()

    /** A picture: its source path as written, and its description. */
    data class Image(val source: String, val caption: String) : FlowItem()
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
 *
 * [styledRedClasses] are classes the file's own stylesheets color red — words of Christ under
 * whatever name the publisher chose ("sgc-7", "jesus").
 */
internal class DocumentScanner(
    private val options: BibleTextExtractor.Options,
    private val styledRedClasses: Set<String> = emptySet(),
) {

    private sealed class Capture {
        data class Candidate(val marker: Marker) : Capture()
        data object Heading : Capture()
        data class Note(val id: String) : Capture()
        data class NoteReference(val id: String?) : Capture()
        data object Essay : Capture()
    }

    private class Frame(
        val name: String,
        var capture: Capture? = null,
        var isSkipped: Boolean = false,
        var isRed: Boolean = false,
        var startedBlock: Boolean = false,
        var isExternalLink: Boolean = false,
        /**
         * Where this block's own items begin (buffer depth, item count), so a block that turns out to
         * be only a link to another file ("Book of 2 Chronicles ⇨") can be taken back out.
         */
        var blockMark: Pair<Int, Int>? = null,
    )

    /** Text seen inside one block, split by whether it sat inside a link to another file. */
    private class BlockText {
        var linked = 0
        var plain = 0
    }

    fun scan(xhtml: String): ScannedDocument {
        val document = ScannedDocument()
        val stack = ArrayList<Frame>()
        val buffers = ArrayList<MutableList<FlowItem>>().apply { add(ArrayList()) } // innermost capture buffer is last
        var skipDepth = 0
        var redDepth = 0
        var externalLinkDepth = 0
        var noteDepth = 0
        val blockTexts = ArrayList<BlockText>()
        // Text before the first element (a byte-order mark, stray bytes before `<html>`) is not in the
        // document at all. Kept, it would be carried onto the previous file's last verse.
        var seenElement = false

        val emit: (FlowItem) -> Unit = { item -> if (skipDepth == 0) buffers.last().add(item) }

        for (event in XMLScanner.scan(xhtml)) {
            when (event) {
                is XMLEvent.Start -> {
                    seenElement = true
                    val tag = event.tag
                    val frame = Frame(tag.name)
                    val epubType = tag.epubType
                    val classList = tag.classes
                    val classes = classList.toSet()
                    val typeWords = SwiftText.split(epubType, ' ')

                    // A picture is recorded where it stands, even inside a caption or figure the text
                    // skips: a study Bible's maps sit beside the verses they illustrate.
                    val source = tag.attribute("src")
                    if (tag.name == "img" && options.studyMaterial && !source.isNullOrEmpty()) {
                        val caption = SwiftText.trimWhitespace((tag.attribute("alt") ?: "").replace("-", " ").replace("_", " "))
                        buffers.last().add(FlowItem.Image(source, caption))
                    }
                    // An essay boxed into the text is kept, as study material, not as scripture.
                    if (skipDepth == 0 && options.studyMaterial && classes.any(::isEssayClass)) {
                        frame.capture = Capture.Essay
                        buffers.add(ArrayList())
                        stack.add(frame)
                        continue
                    }
                    if (tag.name in skippedElements || typeWords.any { it in skippedTypes } ||
                        "toc" in classes || "footnotes" in classes || classes.any(::isNeverScriptureClass)
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
                    // A line break separates words: "children.<br/>These" is two sentences, not one word.
                    if (tag.name == "br") {
                        emit(FlowItem.Text(" ", redDepth > 0))
                        stack.add(frame)
                        continue
                    }

                    if (options.redLetters && (
                            classes.any { it in redClasses || it in styledRedClasses } ||
                                epubType.contains("x-woc") || isRed(tag.attribute("style"))
                            )
                    ) {
                        frame.isRed = true
                        redDepth++
                    }

                    // A note body: captured aside from the text, keyed by id so markers can find it.
                    if (typeWords.any { it in noteTypes } ||
                        (tag.name == "aside" && classes.any { it == "footnote" || it == "note" || it == "fn" })
                    ) {
                        frame.capture = Capture.Note(tag.attribute("id") ?: "")
                        noteDepth++
                        buffers.add(ArrayList())
                        stack.add(frame)
                        continue
                    }

                    // A footnote marker: an anchor pointing at a note.
                    if (tag.name == "a" && isNoteReference(tag, classList)) {
                        frame.capture = Capture.NoteReference(fragment(tag.attribute("href")))
                        buffers.add(ArrayList())
                        stack.add(frame)
                        continue
                    }
                    val href = tag.attribute("href")
                    if (tag.name == "a" && href != null && !href.startsWith("#") && href.isNotEmpty()) {
                        if (noteDepth == 0 && tag.attribute("id") != null && href.contains('#')) document.backLinks++
                        frame.isExternalLink = true
                        externalLinkDepth++
                    }

                    // The file's own statement of which book it holds, when it makes one.
                    if (tag.name == "section" || tag.name == "body") {
                        val place = tag.attribute("title")?.let(ScriptureLabels::heading)
                        if (place?.book != null) emit(FlowItem.Place(place.book, place.chapter))
                    }

                    if (tag.name in blockElements) {
                        emit(FlowItem.BlockEnd)
                        emit(FlowItem.BlockStart(blockKind(tag, classList)))
                        frame.startedBlock = true
                        frame.blockMark = (buffers.size - 1) to buffers.last().size
                        blockTexts.add(BlockText())
                    }

                    if (isHeading(tag, classList)) {
                        frame.capture = Capture.Heading
                        buffers.add(ArrayList())
                        stack.add(frame)
                        continue
                    }

                    val candidate = candidate(tag, classList)
                    if (candidate != null) {
                        // A reference id is a fact about the element, not a guess, so it is emitted even
                        // when the element holds the verse text rather than the number.
                        if ((VerseMarkupShape.REFERENCE_IDENTIFIER in candidate.shapes ||
                                VerseMarkupShape.VERSE_ANCHOR in candidate.shapes) && !isNumberLike(tag, classList)
                        ) {
                            val marker = candidate.copy(
                                shapes = candidate.shapes - VerseMarkupShape.NUMBER_CLASS - VerseMarkupShape.SUPERSCRIPT -
                                    VerseMarkupShape.BOLD_NUMBER,
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
                        if (frame.isExternalLink) externalLinkDepth = maxOf(0, externalLinkDepth - 1)
                        val capture = frame.capture
                        if (capture is Capture.Note) noteDepth = maxOf(0, noteDepth - 1)
                        if (capture != null && buffers.size > 1) {
                            val captured = buffers.removeAt(buffers.size - 1)
                            close(capture, captured, document, emit)
                        }
                        val mark = frame.blockMark
                        if (mark != null && blockTexts.isNotEmpty()) {
                            val text = blockTexts.removeAt(blockTexts.size - 1)
                            // A paragraph that is nothing but a link to another file is navigation.
                            val (depth, count) = mark
                            if (text.plain == 0 && text.linked > 0 && depth == buffers.size - 1 && count <= buffers[depth].size) {
                                val items = buffers[depth]
                                if (items.subList(count, items.size).none { it is FlowItem.MarkerItem }) {
                                    items.subList(count, items.size).clear()
                                }
                            }
                        }
                        if (frame.startedBlock) emit(FlowItem.BlockEnd)
                    }
                }

                is XMLEvent.Text -> {
                    if (skipDepth != 0 || !seenElement) continue
                    val collapsed = collapse(event.text)
                    if (collapsed.isEmpty()) continue
                    if (blockTexts.isNotEmpty() && collapsed != " ") {
                        if (externalLinkDepth > 0) blockTexts.last().linked += collapsed.length
                        else blockTexts.last().plain += collapsed.length
                    }
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
                // A superscript footnote letter ("said<sup>b</sup>") whose note is not linked: it is
                // neither a verse number nor text, so it goes, rather than reading "saidb".
                if (VerseMarkupShape.SUPERSCRIPT in capture.marker.shapes && isFootnoteLabel(text)) return
                // Not a number after all: it was an ordinary span that happened to be called "verse".
                for (item in captured) emit(item)
            }
            Capture.Heading -> {
                // A "title" that holds a chapter or verse number is where the chapter starts — a psalm's
                // number and superscription, or its first line — not a heading above it.
                if (captured.any { it is FlowItem.MarkerItem }) {
                    for (item in captured) emit(item)
                    return
                }
                val trimmed = SwiftText.trimWhitespace(text)
                if (trimmed.isNotEmpty()) emit(FlowItem.Heading(trimmed))
            }
            is Capture.Note -> {
                val body = paragraphs(captured).joinToString("\n\n")
                if (capture.id.isNotEmpty() && body.isNotEmpty()) document.notes[capture.id] = body
            }
            Capture.Essay -> {
                var title = ""
                val items = captured.toMutableList()
                val first = items.indexOfFirst { it is FlowItem.Heading }
                if (first >= 0) {
                    title = (items[first] as FlowItem.Heading).text
                    items.removeAt(first)
                }
                val paragraphs = paragraphs(items).toMutableList()
                if (title.isEmpty() && paragraphs.isNotEmpty() && looksLikeTitle(paragraphs.first())) {
                    title = paragraphs.removeAt(0)
                }
                val body = paragraphs.joinToString("\n\n")
                if (body.isNotEmpty()) emit(FlowItem.Article(title, body))
                // Pictures inside the box still stand where the box does.
                for (item in captured) if (item is FlowItem.Image) emit(item)
            }
            is Capture.NoteReference -> {
                if (!options.footnotes) return
                emit(FlowItem.NoteMarker(capture.id, SwiftText.trimWhitespace(text)))
            }
        }
    }

    private fun record(document: ScannedDocument, marker: Marker) {
        if (marker.isChapter) {
            // Only a number the typesetter styled as a chapter number: ids like "genesis1" also label
            // introductions and outlines, which are not scripture.
            if (VerseMarkupShape.NUMBER_CLASS in marker.shapes) document.chapterMarkers++
            return
        }
        for (shape in marker.shapes) document.shapeCounts[shape] = (document.shapeCounts[shape] ?: 0) + 1
    }

    // MARK: Element questions

    private fun isHeading(tag: XMLTag, classes: List<String>): Boolean {
        if (tag.name in headingElements) return true
        return classes.any { name ->
            name.contains("heading") || name.contains("subhead") || name.contains("title") ||
                name == "speaker" || name.startsWith("speaker-") || name == "psalm-book" ||
                name == "acrostic" || name.startsWith("acrostic-")
        }
    }

    private fun blockKind(tag: XMLTag, classes: List<String>): ExtractedBlock.Kind {
        if (classes.any { it.startsWith("q2") || it.contains("line2") || it.contains("indent2") || (it.contains("poet") && it.contains("indent")) }) {
            return ExtractedBlock.Kind.POETRY2
        }
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
    private fun isNumberLike(tag: XMLTag, classes: List<String>): Boolean {
        if (tag.name == "sup" || tag.name == "b" || tag.name == "strong") return true
        return classes.any { it.contains("num") || it == "v" || it == "vn" || it == "b" || it == "bold" }
    }

    /** Every shape that would explain this element as a verse or chapter marker. */
    private fun candidate(tag: XMLTag, classes: List<String>): Marker? {
        val shapes = HashSet<VerseMarkupShape>()
        var book: BookID? = null
        var chapter: Int? = null
        var verse: Int? = null
        var isChapter = false

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
        if (!isChapter && (tag.name == "b" || tag.name == "strong" || classes.any { it == "b" || it == "bold" || it == "strong" })) {
            shapes += VerseMarkupShape.BOLD_NUMBER
        }
        return if (shapes.isEmpty()) null else Marker(shapes, book, chapter, verse, null, isChapter)
    }

    private fun isNoteReference(tag: XMLTag, classes: List<String>): Boolean {
        if (tag.epubType.contains("noteref")) return true
        if (classes.any { it.contains("note") || it == "fn" || it.startsWith("fn-") || it.contains("footnote") }) return true
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
        private val headingElements = setOf("h1", "h2", "h3", "h4", "h5", "h6")

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

        private fun squash(name: String): String = name.replace("-", "").replace("_", "")

        /** A box or sidebar set into the text: an essay. */
        fun isEssayClass(name: String): Boolean {
            val squashed = squash(name)
            return squashed.contains("sidebar") || (squashed.endsWith("box") && squashed.length <= 12)
        }

        /**
         * Classes whose subtree is never the translation's text: cross-reference callers, study notes,
         * essay boxes set into the text, and image captions.
         */
        fun isNeverScriptureClass(name: String): Boolean {
            val squashed = squash(name)
            return squashed.contains("crossref") || squashed == "xref" || squashed.startsWith("xref") ||
                squashed.contains("studynote") || squashed.contains("sidebar") ||
                (squashed.endsWith("box") && squashed.length <= 12) ||
                squashed == "image" || squashed.contains("caption") || squashed.contains("illustration")
        }

        fun isVerseNumberClass(name: String): Boolean {
            if (name in setOf("v", "vn", "vnum", "vnumber", "verse", "verses", "versenum", "verseno", "versenumber", "vers")) return true
            // "verse-num", "v-num", "verse_number", "vNum1", "bibleverse-number"
            val squashed = squash(name)
            if (squashed.startsWith("verse") || squashed.startsWith("vnum") || squashed.startsWith("vno")) return true
            return squashed.contains("versenum") || squashed.contains("verseno")
        }

        fun isChapterNumberClass(name: String): Boolean {
            val squashed = squash(name)
            return squashed in setOf("c", "cnum", "chapnum", "chapternum", "chapternumber", "chapter", "chap") ||
                squashed.startsWith("chapnum") || squashed.startsWith("chapternum")
        }

        // MARK: Red letters

        private val cssComment = Regex("""/\*[\s\S]*?\*/""")

        /**
         * Class names whose CSS rule sets a red text color: `.wj { color: #c00 }`,
         * `span.sgc-7 { color: rgb(200, 30, 30) }`. Rules with descendant or state selectors are
         * ignored — only a rule the class alone decides.
         */
        fun redClasses(sheets: List<String>): Set<String> {
            val found = HashSet<String>()
            for (sheet in sheets) {
                val css = sheet.replace(cssComment, "")
                for (rule in css.split('}')) {
                    val open = rule.indexOf('{')
                    if (open < 0 || !isRed(rule.substring(open + 1))) continue
                    for (selector in rule.substring(0, open).split(',')) {
                        val trimmed = selector.trim()
                        if (trimmed.isEmpty() || trimmed.contains(' ') || trimmed.contains(':') || trimmed.contains('>')) continue
                        val dot = trimmed.lastIndexOf('.')
                        if (dot < 0) continue
                        val name = trimmed.substring(dot + 1)
                        if (name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '-' || it == '_' }) found.add(name.lowercase())
                    }
                }
            }
            return found
        }

        private val redNames = setOf("red", "darkred", "firebrick", "crimson", "maroon", "brown")

        /** Does a declaration block set the text color to something a reader would call red? */
        fun isRed(style: String?): Boolean {
            if (style == null) return false
            for (declaration in style.lowercase().split(';')) {
                val colon = declaration.indexOf(':')
                if (colon < 0 || declaration.substring(0, colon).trim() != "color") continue
                val value = declaration.substring(colon + 1).trim().replace("!important", "").trim()
                if (value in redNames) return true
                val rgb = rgb(value)
                if (rgb != null) return rgb[0] >= 0x80 && rgb[1] <= rgb[0] / 2 && rgb[2] <= rgb[0] / 2
            }
            return false
        }

        private fun rgb(value: String): IntArray? {
            if (value.startsWith("#")) {
                var hex = value.substring(1)
                if (hex.length == 3) hex = hex.map { "$it$it" }.joinToString("")
                if (hex.length != 6) return null
                val n = hex.toIntOrNull(16) ?: return null
                return intArrayOf(n shr 16 and 0xFF, n shr 8 and 0xFF, n and 0xFF)
            }
            val open = value.indexOf('(')
            val close = value.indexOf(')')
            if (!value.startsWith("rgb") || open < 0 || close < open) return null
            val numbers = value.substring(open + 1, close).split(',').mapNotNull { it.trim().toIntOrNull() }
            return if (numbers.size >= 3) intArrayOf(numbers[0], numbers[1], numbers[2]) else null
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
                // A soft hyphen is a hint for where a line may break, not text. Left in, "be\u00ADginning"
                // no longer matches a search for "beginning" (FTS5's tokenizer splits on it).
                if (characters.isChar(i, '\u00AD')) continue
                if (characters.isWhitespace(i) || characters.isChar(i, '\u00A0')) {
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

        /** A short paragraph in capitals, standing where a title would: "COVENANT OF WORKS". */
        fun looksLikeTitle(paragraph: String): Boolean {
            val letters = paragraph.filter { it.isLetter() }
            return SwiftText.characterCount(paragraph) <= 90 && letters.length >= 3 && letters.uppercase() == letters
        }

        /** Text as paragraphs, split where the markup's blocks were. */
        fun paragraphs(items: List<FlowItem>): List<String> {
            val result = ArrayList<String>()
            val current = StringBuilder()
            fun flush() {
                val trimmed = SwiftText.trimWhitespace(collapse(current.toString()))
                if (trimmed.isNotEmpty()) result.add(trimmed)
                current.setLength(0)
            }
            for (item in items) {
                when (item) {
                    is FlowItem.Text -> current.append(item.text)
                    is FlowItem.Heading -> {
                        flush()
                        current.append(item.text)
                        flush()
                    }
                    is FlowItem.BlockStart, FlowItem.BlockEnd -> flush()
                    else -> Unit
                }
            }
            flush()
            return result
        }

        fun plainText(items: List<FlowItem>): String {
            val output = StringBuilder()
            for (item in items) {
                if (item is FlowItem.Text) output.append(item.text)
                if (item is FlowItem.Heading) output.append(item.text)
            }
            return output.toString()
        }

        private val bridgeSeparators = setOf('-', '\u2010', '\u2011', '\u2012', '–', '—')

        /**
         * "12", "[12]", "1-2", "1–2" — a verse number, and the last number of a bridged pair. A dangling
         * separator ("7–", a bridge the typesetter split across two superscripts) is the number alone.
         */
        fun verseNumber(raw: String): Pair<Int, Int?>? {
            val characters = SwiftCharacters(raw)
            val split = (0 until characters.count).firstOrNull { i -> bridgeSeparators.any { characters.isChar(i, it) } }
            if (split != null) {
                val first = number(raw.substring(0, characters.start(split)))
                if (first != null) {
                    val rest = raw.substring(characters.end(split))
                    val last = number(rest)
                    if (last != null && last > first && last - first < 20) return first to last
                    if (SwiftText.trim(rest, ::isNumberTrim).isEmpty()) return first to null
                }
            }
            return number(raw)?.let { it to null }
        }

        /**
         * A footnote or cross-reference caller printed as a superscript: "a", "b", "aa", "[c]", "*",
         * "†". Ordinal endings ("1<sup>st</sup>") are not callers.
         */
        fun isFootnoteLabel(raw: String): Boolean {
            val label = SwiftText.trim(raw) { cp ->
                cp == 0x20 || cp == 0xA0 || cp == '['.code || cp == ']'.code || cp == '('.code || cp == ')'.code ||
                    cp == '\n'.code || cp == '\t'.code
            }
            val characters = SwiftCharacters(label)
            val count = characters.count
            if (label.isEmpty() || count > 3) return false
            // "*", "†", "✞": a caller made only of symbols.
            if ((0 until count).none { characters.isLetter(it) || characters.isNumber(it) }) return true
            if (count > 2 || !label.all { it in 'a'..'z' || it in 'A'..'Z' }) return false
            return label.lowercase() !in setOf("st", "nd", "rd", "th")
        }

        private const val NUMBER_TRIM = " \u00A0[](){}.,:;\u00B7\u2022*\u200B\n\t"

        private fun isNumberTrim(cp: Int): Boolean = cp < 0x10000 && NUMBER_TRIM.indexOf(cp.toChar()) >= 0

        /** "12", " 12 ", "[12]", "12.", "12 " → 12. Anything else → null. */
        fun number(raw: String): Int? {
            val stripped = SwiftText.trim(raw, ::isNumberTrim)
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

    /**
     * A chapter number was just set; the text that follows it, before any verse number, is verse 1 —
     * the drop-cap layout, where "1" is never printed.
     */
    private var awaitingFirstVerse = false

    /** The current verse 1 was begun by [awaitingFirstVerse], so a "1" that follows is the same verse. */
    private var verseIsImplicit = false

    /**
     * Section headings seen since the last verse. They belong above the verse that follows, which may
     * be in the next chapter: "The Fall" comes before the "3" that starts Genesis 3.
     */
    private val pendingHeadings = ArrayList<String>()

    /**
     * A chapter number printed early, ahead of the last verse of the chapter before it (one publisher
     * sets "8" before John 7:53). That verse is filed under its own chapter, and the new chapter
     * resumes at its verse 1.
     */
    private var deferredChapter: Int? = null

    /** The file has stated its chapter numbers, so none are inferred. */
    private var chaptersAreMarked = false

    /** The file names its books by title, so headings don't. */
    private var booksArePlaced = false

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
        awaitingFirstVerse = false

        // A document with no verse markup (an introduction, an outline, study notes) or whose paragraphs
        // link back to another file (a page of footnotes or cross references) holds no scripture. It may
        // still say which book comes next, and nothing else is taken from it.
        var evidence = (document.shapeCounts[shape] ?: 0) + document.chapterMarkers
        // Bold and superscript numbers are everywhere — a concordance is full of them. They count only
        // when they run the way verse numbers do: each one after the last, or back to 1.
        if ((shape == VerseMarkupShape.BOLD_NUMBER || shape == VerseMarkupShape.SUPERSCRIPT) && !runsLikeVerses(document.flow, shape)) {
            evidence = document.chapterMarkers
        }
        if (evidence <= 0 || document.backLinks >= evidence) {
            for (item in document.flow) {
                when (item) {
                    is FlowItem.Place -> if (item.book != book && !bible.hasVerses(item.book)) move(item.book, null)
                    is FlowItem.Heading -> {
                        val place = ScriptureLabels.heading(item.text)
                        if (place?.book != null && place.book != book) move(place.book, place.chapter)
                    }
                    else -> Unit
                }
            }
            // A page read before its book's text begins is that book's introduction or outline. Pages of
            // notes follow the text they annotate, so they never qualify — even ones that open with a
            // table of links to every chapter, as an introduction may too.
            val current = book
            if (options.studyMaterial && current != null && !bible.hasVerses(current)) recordIntroduction(document, current, path)
            return
        }

        // The book the file's own title named. An element id that names another book is a label that
        // happens to parse ("john3" wrapping 3 John), not a move.
        var titledBook: BookID? = null

        for (item in document.flow) {
            when (item) {
                is FlowItem.Place -> {
                    titledBook = item.book
                    booksArePlaced = true
                    // A file's title is a label someone typed; a title naming a book already read is a
                    // copy-paste slip (a later book's pages titled with an earlier book), not a return.
                    if (item.book != book && !bible.hasVerses(item.book)) {
                        move(item.book, item.chapter)
                        pendingHeadings.clear()
                    }
                    documentDeclaredPlace = true
                }
                is FlowItem.BlockStart -> {
                    closeBlock()
                    blockKind = item.kind
                }
                FlowItem.BlockEnd -> closeBlock()
                is FlowItem.Heading -> {
                    // A heading may say where the text is — but not in a file that states its books by
                    // title or its chapters by number, where a heading naming a book is a section heading
                    // ("…Prophetic Revelation").
                    val place = ScriptureLabels.heading(item.text)
                    val placeChapter = place?.chapter
                    if (place != null && (place.book != null || placeChapter != null) &&
                        (place.book == null || !booksArePlaced) &&
                        (placeChapter == null || place.book != null || !chaptersAreMarked) &&
                        (placeChapter == null || placeChapter <= (place.book ?: book ?: BookID.PSALMS).chapterCount)
                    ) {
                        move(place.book, placeChapter)
                        documentDeclaredPlace = true
                    } else if (options.headings && book != null) {
                        pendingHeadings.add(item.text)
                    }
                }
                is FlowItem.MarkerItem -> {
                    var marker = item.marker
                    val named = marker.book
                    if (titledBook != null && named != null && named != titledBook) {
                        if (marker.isChapter) continue
                        marker = marker.copy(book = null, chapter = null)
                    }
                    val accepted = marker.isChapter || shape in marker.shapes
                    if (marker.isChapter) {
                        val number = marker.chapter
                        // A chapter the book doesn't have is a number set large for another reason.
                        val current = marker.book ?: book
                        if (number != null && current != null && number > current.chapterCount) continue
                        // Chapters don't run backwards within a book: a smaller number is a stray.
                        val now = chapter
                        if (chaptersAreMarked && number != null && now != null && (marker.book == null || marker.book == book) &&
                            number < now
                        ) continue
                        chaptersAreMarked = true
                        move(marker.book, marker.chapter)
                        documentDeclaredPlace = true
                        awaitingFirstVerse = book != null && chapter != null && verse == null
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
                is FlowItem.Article -> {
                    val current = book
                    if (!options.studyMaterial || current == null) continue
                    bible.study.articles.add(ExtractedStudy.Article(ExtractedStudy.ArticleKind.ESSAY, current, studyAnchor, item.title, item.text))
                }
                is FlowItem.Image -> {
                    if (!options.studyMaterial) continue
                    bible.study.images.add(
                        ExtractedStudy.Image(
                            studyAnchor, book, item.caption,
                            EPUBPackage.resolve(EPUBPackage.directory(path), item.source), null, mediaType(item.source),
                        ),
                    )
                }
                is FlowItem.Text -> {
                    if (awaitingFirstVerse && verse == null && SwiftText.trimWhitespace(item.text).isNotEmpty()) {
                        start(1, null)
                        verseIsImplicit = true
                    }
                    append(item.text, item.red && options.redLetters)
                }
                is FlowItem.NoteMarker -> {
                    // A study Bible's own note: kept as study material, anchored on this verse.
                    val raw = item.id?.let { document.notes[it] }
                    if (options.studyMaterial && item.id != null && raw != null && isCommentary(strippingLabel(raw, item.label))) {
                        recordStudyNote(path + "#" + item.id, raw)
                        continue
                    }
                    if (!options.footnotes) continue
                    val body = footnoteBody(raw, item.label) ?: continue
                    if (awaitingFirstVerse && verse == null) {
                        start(1, null)
                        verseIsImplicit = true
                    }
                    addFootnote(body)
                }
            }
        }
        closeBlock()
        if (!documentDeclaredPlace && bible.shapesByDocument[path] == VerseMarkupShape.NONE && hint.book != null) {
            // A file we could not read at all, whose name promised a book, is worth saying aloud.
            bible.notes.add(ImportNote(ImportNote.Severity.INFO, AppText.get(R.string.data_import_note_no_verse_markup, path)))
        }
    }

    fun finish() {
        closeBlock()
        bible.recoverVersesAfterOmissions()
        bible.tidy()
        bible.outOfOrderChapters = outOfOrder.toSet()
        bible.bridgedVerses = bridged.toMap()
        if (bridged.isNotEmpty()) {
            bible.notes.add(ImportNote(ImportNote.Severity.INFO, AppText.get(R.string.data_import_note_bridged_verses, bridged.size)))
        }
        for (chapter in outOfOrder.sorted()) {
            bible.notes.add(ImportNote(ImportNote.Severity.WARNING, AppText.get(R.string.data_import_out_of_order, chapter.display)))
        }
        if (skippedText > 0) {
            bible.notes.add(
                ImportNote(ImportNote.Severity.WARNING, AppText.get(R.string.data_import_note_dropped_text, skippedText)),
            )
        }
    }

    // MARK: Study material

    /** Where study material found now belongs: the verse being read, or the start of the chapter. */
    private val studyAnchor: VerseRef?
        get() {
            val book = book ?: return null
            val chapter = chapter ?: return null
            return verseRef(book, chapter, verse ?: 1)
        }

    /** Every caller pointing at one note widens it: a note on 1:1–2 is called from both verses. */
    private fun recordStudyNote(id: String, text: String) {
        val anchor = studyAnchor ?: return
        val note = bible.study.notes[id]
        if (note != null) {
            var widened = note
            if (anchor.key > widened.end.key) widened = widened.copy(end = anchor)
            if (anchor.key < widened.start.key) widened = widened.copy(start = anchor)
            bible.study.notes[id] = widened
        } else {
            bible.study.notes[id] = ExtractedStudy.Note(anchor, anchor, studyNoteText(text))
            bible.study.noteOrder.add(id)
        }
    }

    private fun recordIntroduction(document: ScannedDocument, book: BookID, path: String) {
        var title = ""
        val items = document.flow.toMutableList()
        val first = items.indexOfFirst { it is FlowItem.Heading }
        if (first >= 0) {
            title = (items[first] as FlowItem.Heading).text
            items.removeAt(first)
        }
        // A table of links to every chapter ("Genesis 1 · Genesis 2 · …") is navigation, not prose.
        val paragraphs = DocumentScanner.paragraphs(items).filter { paragraph ->
            val covered = ReferenceDetector.detect(paragraph).sumOf { it.range.last - it.range.first + 1 }
            covered.toDouble() < paragraph.length * 0.6
        }.toMutableList()
        if (title.isEmpty() && paragraphs.isNotEmpty() && DocumentScanner.looksLikeTitle(paragraphs.first())) {
            title = paragraphs.removeAt(0)
        }
        if (SwiftText.characterCount(paragraphs.joinToString("")) <= 120) return
        bible.study.articles.add(
            ExtractedStudy.Article(ExtractedStudy.ArticleKind.INTRODUCTION, book, null, title, paragraphs.joinToString("\n\n")),
        )
        for (item in document.flow) {
            if (item !is FlowItem.Image) continue
            bible.study.images.add(
                ExtractedStudy.Image(
                    null, book, item.caption,
                    EPUBPackage.resolve(EPUBPackage.directory(path), item.source), null, mediaType(item.source),
                ),
            )
        }
    }

    // MARK: Position

    private fun move(newBook: BookID?, newChapter: Int?) {
        closeBlock()
        deferredChapter = null
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
        awaitingFirstVerse = false
        val book = book
        if (number == null || book == null) {
            if (verse == null) skippedText++
            return
        }
        if (verseIsImplicit) {
            verseIsImplicit = false
            // The "1" printed after a chapter number that already began verse 1.
            if (number == 1 && verse == 1) return
        }
        if (chapter == null) chapter = 1
        val resume = deferredChapter
        val current = chapter
        if (resume != null) {
            if (number == 1 || number < lastVerseNumber) {
                closeBlock()
                chapter = resume
                lastVerseNumber = 0
                deferredChapter = null
            }
        } else if (number > 1 && lastVerseNumber == 0 && current != null && current > 1 &&
            bible.highestVerse(ChapterRef(book, current)) == 0 &&
            bible.highestVerse(ChapterRef(book, current - 1)) == number - 1
        ) {
            closeBlock()
            deferredChapter = current
            chapter = current - 1
            lastVerseNumber = number - 1
        }
        // A verse number we already have means the file moved on to the next chapter without saying so
        // — the common shape when chapter numbers are drop-caps we did not recognise. Not in a file that
        // has been marking its chapters: there it is one number read out of place (a list set in a
        // table), and inventing a chapter would misfile everything after it.
        val here = chapterRef
        if (here != null && bible.verses[verseRef(book, here.chapter, number)] != null && !chaptersAreMarked) {
            closeBlock()
            chapter = (chapter ?: 1) + 1
            lastVerseNumber = 0
        } else if (number < lastVerseNumber && here != null) {
            outOfOrder.add(here)
        }
        val now = chapterRef
        if (pendingHeadings.isNotEmpty() && now != null) {
            closeBlock()
            for (heading in pendingHeadings) bible.append(ExtractedBlock(ExtractedBlock.Kind.HEADING, heading), now)
            pendingHeadings.clear()
        }
        verse = number
        lastVerseNumber = number
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
            // which beats a bare superscript, which beats a bold number.
            val order = listOf(
                VerseMarkupShape.REFERENCE_IDENTIFIER, VerseMarkupShape.VERSE_ANCHOR,
                VerseMarkupShape.NUMBER_CLASS, VerseMarkupShape.SUPERSCRIPT, VerseMarkupShape.BOLD_NUMBER,
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

        /** Do this shape's numbers run the way verse numbers do — each one after the last, or back to 1? */
        fun runsLikeVerses(flow: List<FlowItem>, shape: VerseMarkupShape): Boolean {
            var previous = 0
            var steps = 0
            var total = 0
            for (item in flow) {
                val marker = (item as? FlowItem.MarkerItem)?.marker ?: continue
                if (marker.isChapter || shape !in marker.shapes) continue
                val number = marker.verse ?: continue
                total++
                // The first number may start anywhere: a file can open mid-chapter.
                if (total == 1 || number == previous + 1 || number == 1 || number == previous + 2) steps++
                previous = marker.through ?: number
            }
            return total > 0 && steps.toDouble() / total >= 0.6
        }

        fun hint(path: String): ScriptureLabels.Place {
            val file = SwiftText.split(path, '/').lastOrNull() ?: path
            val stem = SwiftText.split(file, '.').firstOrNull() ?: file
            return ScriptureLabels.fileStem(stem) ?: ScriptureLabels.Place(null, null)
        }

        private val leadingReference = Regex("""^\s*(?:[1-3]\s)?\p{Lu}[\p{Lu}\s]*?\s+\d+(?:[:.,;–\-]\s?\d+)*[;,]?\s*""")
        private val leadingVerse = Regex("""^\s*\d+:\d+(?:[–\-,]\s?\d+)*\s*""")
        private val openingChapterVerse = Regex("""^\d{1,3}:\d{1,3}""")

        /**
         * A note opens by naming what it discusses ("ROMANS 1:1 Paul."); the reader shows the passage
         * already, so the reference goes and the lemma ("Paul.") stays.
         */
        fun studyNoteText(raw: String): String {
            var text = raw
            val match = leadingReference.find(text) ?: leadingVerse.find(text)
            if (match != null) text = text.removeRange(match.range)
            return SwiftText.trimWhitespaceAndNewlines(text)
        }

        fun strippingLabel(body: String, label: String): String {
            val caller = SwiftText.trimWhitespace(label)
            if (caller.isEmpty() || !body.startsWith("$caller ")) return body
            return body.substring(caller.length + 1)
        }

        fun mediaType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }

        /**
         * What a footnote marker should carry, or null when it carries nothing worth keeping.
         *
         * A caller whose note cannot be found is dropped rather than kept as its own label ("a", "[✞]").
         * A note that opens by naming the passage it discusses ("ROMANS 1:1 Paul.", "3:16 For God") is a
         * study Bible's commentary, not the translation's own footnote.
         */
        fun footnoteBody(note: String?, label: String): String? {
            val caller = SwiftText.trimWhitespace(label)
            var body = note?.let(SwiftText::trimWhitespace)
            if (body.isNullOrEmpty()) return if (SwiftText.characterCount(caller) > 3) caller else null
            // "1 Or brothers" under a caller "1": the note repeats its own label.
            if (caller.isNotEmpty() && body.startsWith("$caller ")) body = SwiftText.trimWhitespace(body.substring(caller.length + 1))
            if (body.isEmpty() || isCommentary(body)) return null
            return body
        }

        fun isCommentary(body: String): Boolean {
            val head = SwiftText.prefix(body, 48)
            if (openingChapterVerse.containsMatchIn(head)) return true
            val tokens = ScriptureLabels.normalizedTokens(head)
            if (tokens.size < 2) return false
            for (width in 1..minOf(3, tokens.size - 1)) {
                if (!SwiftText.allNumbers(tokens[width])) continue
                val phrase = ReferenceParser.normalizeOrdinals(tokens.subList(0, width).joinToString(" "))
                if (ScriptureLabels.exactBook(BookID.normalize(phrase)) != null) return true
            }
            return false
        }
    }
}
