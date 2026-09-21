package com.blainemiller.scripturealone.data.online

import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.layout.ChapterLayout
import com.blainemiller.scripturealone.data.sabible.ChapterRef

/*
 * Reading a chapter from a publisher's API with its structure intact — ported from
 * `ScriptureAloneCore/ESV/OnlinePassageHTML.swift`.
 *
 * The app used to ask both APIs for plain text. Plain text is a *rendering* — what those services
 * produce for a terminal — and everything that makes scripture look like scripture is thrown away in
 * making it: the words of Christ, the poetry, the psalm titles, the paragraphs. Both services state
 * the structure outright if asked for HTML, so now they are.
 *
 * The blocks this produces are the reader's own [ChapterLayout.Block]s rather than a parallel type:
 * Swift's `ExtractedBlock` / `ExtractedFragment` / `StyledSpan` are field for field the layout's
 * `Block` / `Fragment` / `Span` (a heading's text is `Block.text`, span offsets are Unicode scalars in
 * both), so what the parser builds is exactly what [LayoutJson] encodes and [ChapterLayout.parse]
 * reads back.
 */

/**
 * A UTF-16 range into a verse's text — Swift's `NSRange`, and exactly Kotlin's string indices, so
 * `text.substring(location, location + length)` is the quoted words.
 */
data class Utf16Range(val location: Int, val length: Int) {
    val end: Int get() = location + length

    /**
     * True when this range lies inside [text] and neither end splits a surrogate pair — the Kotlin
     * form of Swift's `Range(nsRange, in: text) != nil`.
     */
    fun isValid(text: String): Boolean {
        if (location < 0 || length < 0 || end > text.length) return false
        fun splitsPair(i: Int) = i in 1 until text.length &&
            Character.isHighSurrogate(text[i - 1]) && Character.isLowSurrogate(text[i])
        return !splitsPair(location) && !splitsPair(end)
    }

    /** The covered text. Call [isValid] first when the range came from outside. */
    fun substring(text: String): String = text.substring(location, end)
}

/**
 * One verse's flat text — Swift's `VerseText`. [red] is the words of Christ as **UTF-16** ranges:
 * what search, quotation, listening and sharing all work from. The cache converts them to scalar
 * offsets on the way into SQLite (see [OnlineChapterCache.scalarSpans]).
 */
data class VerseText(val ref: VerseRef, val text: String, val red: List<Utf16Range> = emptyList())

/** A chapter fetched from a publisher's API, with its structure intact. Swift's `ParsedPassage`. */
data class ParsedPassage(
    /** Flat text per verse, with words of Christ as UTF-16 ranges. */
    val verses: List<VerseText> = emptyList(),
    /** The chapter's shape: paragraphs, poetry lines, headings, psalm titles. */
    val blocks: List<ChapterLayout.Block> = emptyList(),
) {
    val isEmpty: Boolean get() = verses.isEmpty()
}

// ---- A very small HTML scanner -------------------------------------------------------------------

/**
 * Just enough HTML to read two known documents. Not a general parser, and not trying to be: these
 * are two services emitting markup they generate themselves, and the alternative is a dependency to
 * walk a handful of tags.
 */
internal object PassageHTML {

    sealed class Token {
        data class Open(val tag: String, val classes: List<String>, val attributes: Map<String, String>) : Token()
        data class Close(val tag: String) : Token()
        data class Text(val text: String) : Token()
    }

    fun scan(html: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val text = StringBuilder()

        fun flushText() {
            if (text.isNotEmpty()) {
                tokens += Token.Text(decode(text.toString()))
                text.setLength(0)
            }
        }

        var index = 0
        while (index < html.length) {
            if (html[index] != '<') {
                text.append(html[index])
                index++
                continue
            }
            val end = html.indexOf('>', index)
            if (end < 0) {
                text.append(html, index, html.length)
                break
            }
            flushText()
            val inner = html.substring(index + 1, end)
            index = end + 1

            if (inner.startsWith("/")) {
                tokens += Token.Close(inner.substring(1).lowercase())
                continue
            }
            val selfClosing = inner.endsWith("/")
            val body = if (selfClosing) inner.dropLast(1) else inner
            val name = SwiftText.splitOnWhitespace(body).firstOrNull() ?: continue
            val attributes = attributes(body)
            val classes = SwiftText.splitOnWhitespace(attributes["class"] ?: "")
            tokens += Token.Open(name.lowercase(), classes, attributes)
            // `<br />` never has a closing tag; treating it as an open-only token keeps the
            // handlers from having to know which tags are void.
            if (selfClosing) tokens += Token.Close(name.lowercase())
        }
        flushText()
        return tokens
    }

    /**
     * `([\w-]+)\s*=\s*"([^"]*)"` as ICU (NSRegularExpression) reads it: `\w` and `\s` are Unicode
     * classes there, but ASCII-only in `java.util.regex` unless a flag Android does not honour is
     * set — so the classes are written out.
     */
    private val attribute = Regex(
        """([\p{L}\p{M}\p{Nd}\p{Nl}\p{Pc}\u200C\u200D-]+)[\t\n\u000B\f\r\u0085\p{Z}]*=[\t\n\u000B\f\r\u0085\p{Z}]*"([^"]*)"""",
    )

    fun attributes(tag: String): Map<String, String> {
        val found = mutableMapOf<String, String>()
        for (match in attribute.findAll(tag)) {
            found[match.groupValues[1].lowercase()] = match.groupValues[2]
        }
        return found
    }

    private val numericEntity = Regex("&#(x?)([0-9A-Fa-f]{1,6});")

    /** Applied one after another, in this order — which is why `&amp;lt;` comes out as `<`, as in Swift. */
    private val namedEntities = listOf(
        "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">",
        "&quot;" to "\"", "&apos;" to "'", "&rsquo;" to "’",
        "&lsquo;" to "‘", "&ldquo;" to "“", "&rdquo;" to "”",
        "&mdash;" to "—", "&ndash;" to "–", "&hellip;" to "…",
    )

    /**
     * Entities, including numeric ones. `&nbsp;` becomes an ordinary space: both services use runs
     * of it for indentation, which the reader lays out itself.
     */
    fun decode(text: String): String {
        var out = text
        if (out.contains("&#")) {
            val result = StringBuilder()
            var last = 0
            for (match in numericEntity.findAll(out)) {
                val hex = match.groupValues[1].isNotEmpty()
                val value = match.groupValues[2].toIntOrNull(if (hex) 16 else 10) ?: continue
                // `Unicode.Scalar(_:)` refuses surrogates and anything past U+10FFFF; such an
                // entity is left as written.
                if (value > 0x10FFFF || value in 0xD800..0xDFFF) continue
                result.append(out, last, match.range.first).appendCodePoint(value)
                last = match.range.last + 1
            }
            result.append(out, last, out.length)
            out = result.toString()
        }
        for ((entity, character) in namedEntities) out = out.replace(entity, character)
        return out
    }
}

// ---- Building blocks and verses ------------------------------------------------------------------

/**
 * Accumulates fragments as tags come and go, then hands back a passage.
 *
 * Shared by both dialects because the bookkeeping is the same either way: which verse we are in,
 * whether its number has been printed yet, which styles are open, and where one block ends.
 */
internal class PassageBuilder(private val chapter: ChapterRef) {
    private class OpenStyle(val style: ChapterLayout.Span.Style, val start: Int)

    private val blocks = mutableListOf<ChapterLayout.Block>()
    private var kind = ChapterLayout.Kind.PARAGRAPH
    private val fragments = mutableListOf<ChapterLayout.Fragment>()
    private var text = ""
    private val spans = mutableListOf<ChapterLayout.Span>()
    private val openStyles = mutableListOf<OpenStyle>()
    private var verse = 0
    private var numbered = false

    /** Scalar count, because span offsets are Unicode scalars (not Kotlin's UTF-16 length). */
    private val cursor: Int get() = text.codePointCount(0, text.length)

    fun beginBlock(kind: ChapterLayout.Kind) {
        endFragment()
        endBlock()
        this.kind = kind
    }

    fun startVerse(number: Int, numbered: Boolean = true) {
        endFragment()
        verse = number
        this.numbered = numbered
    }

    /**
     * Continues a verse whose number was printed in an earlier block — API.Bible marks these with
     * `data-vid`, and without it a poetry line would be attributed to no verse at all.
     */
    fun continueVerse(number: Int) {
        if (verse != number || text.isNotEmpty()) endFragment()
        verse = number
        numbered = false
    }

    fun append(piece: String) {
        if (piece.isEmpty()) return
        // Collapse the runs of spaces both services use for indentation; the reader indents.
        var cleaned = piece.replace('\u00A0', ' ')
        cleaned = WHITESPACE_RUN.replace(cleaned, " ")
        if (text.isEmpty()) cleaned = cleaned.trimStart(' ')
        if (cleaned.isEmpty()) return
        if (text.endsWith(" ") && cleaned.startsWith(" ")) cleaned = cleaned.substring(1)
        text += cleaned
    }

    fun openStyle(style: ChapterLayout.Span.Style) {
        openStyles += OpenStyle(style, cursor)
    }

    fun closeStyle(style: ChapterLayout.Span.Style) {
        val index = openStyles.indexOfLast { it.style == style }
        if (index < 0) return
        val opened = openStyles.removeAt(index)
        val length = cursor - opened.start
        if (length > 0) spans += ChapterLayout.Span(opened.start, length, style)
    }

    fun endFragment() {
        // Words of Christ are a *character* style, and USX allows one to run across a verse or a
        // paragraph boundary. Neither service does that in the chapters captured so far — both
        // close and reopen around a verse number — but a style that legally spans a boundary must
        // not be silently lost when it does. So anything still open is closed here, and carried
        // into the next fragment at its start.
        val carried = openStyles.map { it.style }
        for (open in openStyles.toList().asReversed()) closeStyle(open.style)
        openStyles.clear()

        // A heading or a psalm's superscription belongs to no verse. The bundled stores encode
        // those as verse 0 — the ASV's Psalm 23 carries "A Psalm of David" exactly that way — so the
        // same convention is used here, and `finish()` keeps such blocks out of the verse text while
        // the layout still draws them.
        val belongsToNoVerse = kind.isHeading || kind == ChapterLayout.Kind.TITLE
        val blank = text.all(SwiftText::isHorizontalWhitespace)
        if (!blank && (verse > 0 || belongsToNoVerse)) {
            val number = if (belongsToNoVerse) 0 else verse
            // Trimming the head would shift every span; only a trailing trim is safe here, and the
            // leading side is already handled in `append`.
            val kept = text.trimEnd(' ')
            fragments += ChapterLayout.Fragment(number, numbered && !belongsToNoVerse, kept, spans.toList())
            numbered = false
        }

        text = ""
        spans.clear()
        // Still open, now at the head of what follows.
        carried.mapTo(openStyles) { OpenStyle(it, 0) }
    }

    fun endBlock() {
        endFragment()
        if (fragments.isEmpty() && kind != ChapterLayout.Kind.STANZA_BREAK) return
        blocks += ChapterLayout.Block(kind, fragments = fragments.toList())
        fragments.clear()
    }

    /** A block with no text of its own — a stanza break. */
    fun emptyBlock(kind: ChapterLayout.Kind) {
        endFragment()
        endBlock()
        blocks += ChapterLayout.Block(kind)
        this.kind = ChapterLayout.Kind.PARAGRAPH
    }

    fun finish(): ParsedPassage {
        endBlock()

        // One `VerseText` per verse: the fragments joined, with the styles converted from scalar
        // offsets within a fragment to UTF-16 ranges within the whole verse — which is what
        // `VerseText.red` is, and what the reader's renderer expects.
        val pieces = linkedMapOf<Int, MutableList<ChapterLayout.Fragment>>()
        for (block in blocks) {
            if (block.kind.isHeading || block.kind == ChapterLayout.Kind.TITLE) continue
            for (fragment in block.fragments) {
                if (fragment.verse > 0) pieces.getOrPut(fragment.verse) { mutableListOf() } += fragment
            }
        }
        val verses = pieces.map { (number, parts) ->
            val whole = StringBuilder()
            val red = mutableListOf<Utf16Range>()
            for (part in parts) {
                if (whole.isNotEmpty()) whole.append(' ')
                val offset = whole.length
                val scalars = part.text.codePointCount(0, part.text.length)
                for (span in part.spans) {
                    if (span.style != ChapterLayout.Span.Style.WORDS_OF_CHRIST) continue
                    // Scalar offsets to UTF-16, by measuring rather than assuming they agree — they
                    // do not, for anything outside the basic plane.
                    if (span.start > scalars || span.start + span.length > scalars) continue
                    val before = part.text.offsetByCodePoints(0, span.start)
                    val after = part.text.offsetByCodePoints(0, span.start + span.length)
                    red += Utf16Range(offset + before, after - before)
                }
                whole.append(part.text)
            }
            VerseText(VerseRef(chapter.book, chapter.chapter, number), whole.toString(), red)
        }.sortedBy { it.ref.key }
        return ParsedPassage(verses, blocks.toList())
    }

    private companion object {
        val WHITESPACE_RUN = Regex("[ \t\n]+")
    }
}

// ---- Crossway's ESV ------------------------------------------------------------------------------

/**
 * Reads `api.esv.org/v3/passage/html`.
 *
 * Its shape, from a real response: paragraphs are `<p>`; poetry is a `<p class="block-indent">`
 * holding `<span class="line">` and `<span class="indent line">` separated by `<br />`; a psalm's
 * superscription is `<h4 class="psalm-title">`; verse numbers are `<b class="verse-num">` (and
 * `<b class="chapter-num">` for the first verse of the chapter); and the words of Christ are
 * `<span class="woc">`, which also wraps the verse number when a red-letter passage runs through it.
 */
object ESVPassageHTML {
    fun parse(html: String, chapter: ChapterRef): ParsedPassage {
        val builder = PassageBuilder(chapter)
        var suppressText = false

        for (token in PassageHTML.scan(html)) {
            when (token) {
                is PassageHTML.Token.Open -> {
                    val classes = token.classes
                    when {
                        token.tag == "p" -> builder.beginBlock(
                            if ("block-indent" in classes) ChapterLayout.Kind.POETRY1 else ChapterLayout.Kind.PARAGRAPH,
                        )
                        token.tag == "h4" || token.tag == "h3" -> builder.beginBlock(
                            if ("psalm-title" in classes) ChapterLayout.Kind.TITLE else ChapterLayout.Kind.HEADING,
                        )
                        token.tag == "span" && "line" in classes -> builder.beginBlock(
                            if ("indent" in classes) ChapterLayout.Kind.POETRY2 else ChapterLayout.Kind.POETRY1,
                        )
                        token.tag == "span" && "woc" in classes -> builder.openStyle(ChapterLayout.Span.Style.WORDS_OF_CHRIST)
                        token.tag == "b" && ("verse-num" in classes || "chapter-num" in classes) -> suppressText = true
                        // `<br>`: line breaks inside poetry are handled by the line spans themselves.
                        else -> Unit
                    }
                }
                is PassageHTML.Token.Close -> when (token.tag) {
                    "p", "h4", "h3" -> builder.endBlock()
                    // A woc span and a line span both close here; closing a style that is not open
                    // is a no-op, so this stays simple.
                    "span" -> builder.closeStyle(ChapterLayout.Span.Style.WORDS_OF_CHRIST)
                    "b" -> suppressText = false
                }
                is PassageHTML.Token.Text -> if (suppressText) {
                    // "3 " or "23:1 " — the trailing number is the verse.
                    trailingNumber(token.text)?.let { builder.startVerse(it) }
                } else {
                    builder.append(token.text)
                }
            }
        }
        return builder.finish()
    }

    /**
     * `text.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }.last`: the last run of
     * numeric characters that is also a plain ASCII integer. A run holding any other numeral fails
     * `Int(_:)` in Swift and is skipped, so it is skipped here.
     */
    private fun trailingNumber(text: String): Int? {
        var last: Int? = null
        var start = -1
        var i = 0
        while (i <= text.length) {
            val cp = if (i < text.length) text.codePointAt(i) else -1
            val numeric = cp >= 0 && SwiftText.isNumber(cp)
            if (numeric && start < 0) start = i
            if (!numeric && start >= 0) {
                SwiftText.int(text.substring(start, i))?.let { last = it }
                start = -1
            }
            i += if (cp >= 0) Character.charCount(cp) else 1
        }
        return last
    }
}

// ---- API.Bible -----------------------------------------------------------------------------------

/**
 * Reads `api.scripture.api.bible` with `content-type=html`.
 *
 * Its markup is USFM with the markers as class names, which is the same vocabulary this app's own
 * layout uses — `p`, `m`, `q1`, `q2`, `s1`, `d`, `b` map across directly. Verses are
 * `<span class="v" data-number="3">`, a paragraph continuing an earlier verse carries
 * `data-vid="PSA 23:1"`, and the words of Jesus are `<span class="wj">`.
 */
object APIBiblePassageHTML {
    fun parse(html: String, chapter: ChapterRef): ParsedPassage {
        val builder = PassageBuilder(chapter)
        var suppressText = false

        for (token in PassageHTML.scan(html)) {
            when (token) {
                is PassageHTML.Token.Open -> {
                    val classes = token.classes
                    when {
                        token.tag == "p" -> {
                            val marker = classes.firstOrNull() ?: "p"
                            if (marker == "b") {
                                builder.emptyBlock(ChapterLayout.Kind.STANZA_BREAK)
                            } else {
                                // Swift's `Kind(rawValue:) ?? .paragraph`; the Kotlin enum's UNKNOWN
                                // is its "no such marker".
                                val kind = ChapterLayout.Kind.of(marker)
                                builder.beginBlock(if (kind == ChapterLayout.Kind.UNKNOWN) ChapterLayout.Kind.PARAGRAPH else kind)
                                // An unnumbered continuation line names the verse it belongs to.
                                token.attributes["data-vid"]?.let(::verseNumber)?.let(builder::continueVerse)
                            }
                        }
                        token.tag == "span" && "v" in classes -> {
                            val number = token.attributes["data-number"]?.let(SwiftText::int)
                                ?: token.attributes["data-sid"]?.let(::verseNumber)
                            if (number != null) builder.startVerse(number)
                            suppressText = true // the span's text is the number itself
                        }
                        token.tag == "span" && "wj" in classes -> builder.openStyle(ChapterLayout.Span.Style.WORDS_OF_CHRIST)
                        token.tag == "span" && "nd" in classes -> builder.openStyle(ChapterLayout.Span.Style.SMALL_CAPS)
                        token.tag == "span" && ("add" in classes || "it" in classes) ->
                            builder.openStyle(ChapterLayout.Span.Style.SUPPLIED)
                        else -> Unit
                    }
                }
                is PassageHTML.Token.Close -> when (token.tag) {
                    "p" -> builder.endBlock()
                    "span" -> {
                        builder.closeStyle(ChapterLayout.Span.Style.WORDS_OF_CHRIST)
                        builder.closeStyle(ChapterLayout.Span.Style.SMALL_CAPS)
                        builder.closeStyle(ChapterLayout.Span.Style.SUPPLIED)
                        suppressText = false
                    }
                }
                is PassageHTML.Token.Text -> if (!suppressText) builder.append(token.text)
            }
        }
        return builder.finish()
    }

    /** "PSA 23:1" → 1. */
    internal fun verseNumber(sid: String): Int? =
        SwiftText.split(sid, ':').lastOrNull()?.let { SwiftText.int(it.trim(SwiftText::isHorizontalWhitespace)) }
}
