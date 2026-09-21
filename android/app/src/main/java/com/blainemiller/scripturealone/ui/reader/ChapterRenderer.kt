package com.blainemiller.scripturealone.ui.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.blainemiller.scripturealone.data.Canon
import com.blainemiller.scripturealone.data.layout.ChapterLayout
import com.blainemiller.scripturealone.data.layout.ChapterLayout.Kind
import com.blainemiller.scripturealone.data.layout.ChapterLayout.Fragment
import com.blainemiller.scripturealone.data.layout.ChapterLayout.Span
import com.blainemiller.scripturealone.data.layout.utf16Offset
import com.blainemiller.scripturealone.data.layout.utf16Range
import com.blainemiller.scripturealone.data.sabible.ChapterRef

/**
 * The typefaces a render uses. [body] is the reading face (Source Serif 4, standing in for New
 * York); [display] is the same face cut for large sizes, for the chapter number; [chrome] is the
 * platform sans, standing in for SF in headings, verse numbers and captions.
 */
data class ReaderFonts(
    val body: FontFamily,
    val display: FontFamily = body,
    val chrome: FontFamily = FontFamily.SansSerif,
    /**
     * Whether [body]'s italic has real small capitals. Source Serif 4's roman does (`smcp`), its
     * italic does not, and an OpenType feature a face lacks is silently ignored — which would print
     * "Lord" in plain lowercase. Where it is false, the divine name is drawn as reduced capitals.
     */
    val italicHasSmallCaps: Boolean = false,
)

/** Tappable chrome inside the chapter. */
enum class ReaderAction { NEXT_CHAPTER }

/**
 * One paragraph of the rendered chapter, with the paragraph-level metrics NSParagraphStyle carries on
 * iOS. All lengths are in sp, derived from the reader's size, so indents and spacing scale with the
 * text as they do under Dynamic Type.
 *
 * Paragraphs rather than one long AnnotatedString, because Compose's ParagraphStyle has no
 * paragraph spacing before or after — iOS's rhythm (a heading's 1.1 em above, poetry's 0.12 em
 * between lines) is spacing, and a column of paragraphs expresses it exactly. It also lets a long
 * chapter (Psalm 119) be laid out lazily.
 */
data class RenderedParagraph(
    val text: AnnotatedString,
    val align: TextAlign = TextAlign.Start,
    val firstLineIndent: Float = 0f,
    val restLineIndent: Float = 0f,
    /** iOS `tailIndent` (negated): space kept clear at the end of every line. */
    val endIndent: Float = 0f,
    val spaceBefore: Float = 0f,
    val spaceAfter: Float = 0f,
    /** Absolute line height, sp. */
    val lineHeight: Float,
    val action: ReaderAction? = null,
    /** Where each verse begins in [text], in reading order — for scrolling to a verse and saving the position. */
    val verses: List<VerseStart> = emptyList(),
)

/** A verse's [key] (`VerseRef.key`) and the UTF-16 [offset] in its paragraph where the verse begins. */
data class VerseStart(val offset: Int, val key: Int)

data class RenderedChapter(val ref: ChapterRef, val paragraphs: List<RenderedParagraph>) {
    /**
     * The paragraph and offset where verse [key] begins. A verse the chapter doesn't print (past the
     * end of this translation's numbering) falls back to the last verse before it, so a position saved
     * in one translation still lands close in another.
     */
    fun locate(key: Int): Pair<Int, Int>? {
        var best: Triple<Int, Int, Int>? = null
        paragraphs.forEachIndexed { index, p ->
            for (v in p.verses) {
                if (v.key == key) return index to v.offset
                if (v.key < key && (best == null || v.key > best!!.third)) best = Triple(index, v.offset, v.key)
            }
        }
        return best?.let { it.first to it.second }
    }
}

/**
 * Turns a [ChapterLayout] into styled paragraphs — a port of `ScriptureAlone/Reader/ChapterRenderer.swift`.
 * Every size, indent and spacing below is the Swift value, written in terms of the reader's size the
 * way Swift writes it, so a change on one side is easy to find on the other.
 *
 * Not ported yet, because the features they belong to aren't: highlights, selection, note markers,
 * the spoken-verse mark, and verse keys on characters (tap targets).
 *
 * Pure: no Android or composition types beyond Compose's text value classes, so it runs in JVM tests.
 */
class ChapterRenderer(
    private val style: ReaderStyle,
    private val fonts: ReaderFonts,
) {
    private val size = style.size
    private val palette = style.palette
    private val indent = size * 1.25f
    private val numberSize = maxOf(9f, size * 0.58f)

    private val body = SpanStyle(fontFamily = fonts.body, fontSize = size.sp, color = palette.ink)

    /** Letters handed out in reading order across the chapter, as the Swift builder's counter does. */
    private var footnoteCounter = 0
    private var chapter = ChapterRef(0, 0)

    fun render(ref: ChapterRef, layout: ChapterLayout, copyright: String): RenderedChapter {
        footnoteCounter = 0
        chapter = ref
        val out = mutableListOf<RenderedParagraph>()
        header(ref, out)
        if (style.layout == ReadingLayout.VERSES) verseByVerse(layout, out) else paragraphs(layout, out)
        footer(ref, copyright, out)
        return RenderedChapter(ref, out)
    }

    // Header / footer

    private fun header(ref: ChapterRef, out: MutableList<RenderedParagraph>) {
        val book = Canon.book(ref.book)
        val captionSize = maxOf(11f, size * 0.62f)
        out += RenderedParagraph(
            text = AnnotatedString(
                book.displayName.uppercase(),
                SpanStyle(
                    fontFamily = fonts.chrome, fontWeight = FontWeight.SemiBold, fontSize = captionSize.sp,
                    color = palette.secondary, letterSpacing = 2.2.sp,
                ),
            ),
            align = TextAlign.Center,
            spaceAfter = 2f,
            lineHeight = captionSize * NATURAL_LINE_HEIGHT,
        )
        val bigSize = size * 2.6f
        out += RenderedParagraph(
            text = AnnotatedString(
                if (book.isSingleChapter) "" else ref.chapter.toString(),
                SpanStyle(fontFamily = fonts.display, fontSize = bigSize.sp, color = palette.accent),
            ),
            align = TextAlign.Center,
            spaceAfter = size * 1.1f,
            lineHeight = bigSize * NATURAL_LINE_HEIGHT,
        )
    }

    /**
     * Swift appends `"\n<next>  →\n"` and `"\n<copyright>\n"`: the leading newline in each makes an
     * empty paragraph carrying the same spacing-before, so the gap above each line is that spacing
     * twice plus one empty line. Folded into [RenderedParagraph.spaceBefore] here.
     */
    private fun footer(ref: ChapterRef, copyright: String, out: MutableList<RenderedParagraph>) {
        Canon.next(ref)?.let { next ->
            val nextSize = size * 0.8f
            out += RenderedParagraph(
                text = AnnotatedString(
                    "${Canon.display(next)}  →",
                    SpanStyle(
                        fontFamily = fonts.chrome, fontWeight = FontWeight.SemiBold, fontSize = nextSize.sp,
                        color = palette.accent,
                    ),
                ),
                align = TextAlign.Center,
                spaceBefore = size * 2 * 2 + nextSize * NATURAL_LINE_HEIGHT,
                lineHeight = nextSize * NATURAL_LINE_HEIGHT,
                action = ReaderAction.NEXT_CHAPTER,
            )
        }
        val fineSize = maxOf(10f, size * 0.55f)
        out += RenderedParagraph(
            text = AnnotatedString(copyright, SpanStyle(fontFamily = fonts.chrome, fontSize = fineSize.sp, color = palette.secondary)),
            align = TextAlign.Center,
            spaceBefore = size * 2 + fineSize * NATURAL_LINE_HEIGHT,
            lineHeight = fineSize * NATURAL_LINE_HEIGHT,
        )
    }

    // Layout modes

    private fun paragraphs(layout: ChapterLayout, out: MutableList<RenderedParagraph>) {
        var pendingBreak = false
        for (block in layout.blocks) {
            when {
                block.kind == Kind.STANZA_BREAK -> {
                    pendingBreak = true
                    continue
                }
                block.kind.isHeading -> if (style.headings) heading(block)?.let { out += it }
                block.kind == Kind.UNKNOWN -> continue
                else -> {
                    val text = AnnotatedString.Builder()
                    val starts = mutableListOf<VerseStart>()
                    for (fragment in block.fragments) {
                        if (text.length > 0) text.withStyle(body) { append(" ") }
                        if (fragment.numbered && fragment.verse > 0) starts += VerseStart(text.length, verseKey(fragment.verse))
                        text.append(fragment(fragment, block.kind))
                    }
                    // An empty paragraph leaves a pending stanza break for the next one, as in Swift.
                    if (text.length == 0) continue
                    out += paragraph(text.toAnnotatedString(), block.kind, extraSpaceBefore = pendingBreak).copy(verses = starts)
                }
            }
            pendingBreak = false
        }
    }

    private fun verseByVerse(layout: ChapterLayout, out: MutableList<RenderedParagraph>) {
        var current: AnnotatedString.Builder? = null
        var starts = mutableListOf<VerseStart>()
        fun flush() {
            val line = current ?: return
            current = null
            val lineStarts = starts
            starts = mutableListOf()
            if (line.length == 0) return
            out += RenderedParagraph(
                text = line.toAnnotatedString(),
                restLineIndent = if (style.verseNumbers) size * 1.5f else 0f,
                spaceAfter = size * 0.45f,
                lineHeight = size * NATURAL_LINE_HEIGHT * style.lineSpacing,
                verses = lineStarts,
            )
        }
        for (block in layout.blocks) {
            if (block.kind.isHeading) {
                flush()
                if (style.headings) heading(block)?.let { out += it }
                continue
            }
            if (block.kind == Kind.TITLE) {
                flush()
                val title = AnnotatedString.Builder()
                block.fragments.forEach { title.append(fragment(it, Kind.TITLE)) }
                out += paragraph(title.toAnnotatedString(), Kind.TITLE, extraSpaceBefore = false)
                continue
            }
            for (fragment in block.fragments) {
                val line = current
                if (fragment.numbered) {
                    flush()
                    current = AnnotatedString.Builder()
                    if (fragment.verse > 0) starts += VerseStart(0, verseKey(fragment.verse))
                } else if (line != null && line.length > 0) {
                    line.withStyle(body) { append(" ") }
                }
                val target = current ?: AnnotatedString.Builder().also { current = it }
                // Every line reads as plain prose here: Swift renders these as `.continuation`, so a
                // Selah is not italic in this mode.
                target.append(fragment(fragment, Kind.CONTINUATION))
            }
        }
        flush()
    }

    // Pieces

    private fun heading(block: ChapterLayout.Block): RenderedParagraph? {
        val text = block.text?.takeIf { it.isNotEmpty() } ?: return null
        var align = TextAlign.Start
        var before = 0f
        val after: Float
        var color = palette.ink
        var letterSpacing = 0f
        val fontSize: Float
        var family = fonts.chrome
        var weight: FontWeight? = null
        var italic = false
        when (block.kind) {
            Kind.HEADING -> {
                fontSize = size * 0.86f; weight = FontWeight.SemiBold
                before = size * 1.1f; after = size * 0.35f
            }
            Kind.SUBHEADING -> {
                fontSize = size * 0.9f; family = fonts.body; italic = true
                before = size * 0.6f; after = size * 0.25f
            }
            Kind.PARALLEL -> {
                fontSize = size * 0.68f; color = palette.secondary
                after = size * 0.5f
            }
            Kind.MAJOR_SECTION -> {
                fontSize = size * 0.72f; weight = FontWeight.SemiBold; color = palette.secondary
                letterSpacing = 1.8f; align = TextAlign.Center
                before = size; after = size * 0.6f
            }
            else -> { // acrostic letters
                fontSize = size * 0.78f; weight = FontWeight.SemiBold; color = palette.accent
                align = TextAlign.Center
                before = size * 0.8f; after = size * 0.2f
            }
        }
        val base = SpanStyle(
            fontFamily = family, fontWeight = weight, fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            fontSize = fontSize.sp, color = color, letterSpacing = letterSpacing.sp,
        )
        val shown = if (block.kind == Kind.MAJOR_SECTION) text.uppercase() else text
        val styled = if (block.kind == Kind.MAJOR_SECTION) {
            AnnotatedString(shown, base)
        } else {
            // The chrome sans (Roboto / the device's sans) has real small caps; the body italic doesn't.
            val synthetic = italic && family == fonts.body && !fonts.italicHasSmallCaps
            val runs = Runs(shown, base, palette.red)
            divineNameTails(shown).forEach { runs.smallCaps(it, synthetic) }
            runs.build(emptyList())
        }
        return RenderedParagraph(
            text = styled, align = align, spaceBefore = before, spaceAfter = after,
            lineHeight = fontSize * NATURAL_LINE_HEIGHT * 1.1f,
        )
    }

    private fun paragraph(text: AnnotatedString, kind: Kind, extraSpaceBefore: Boolean): RenderedParagraph {
        var p = RenderedParagraph(
            text = text,
            spaceBefore = if (extraSpaceBefore) size * 0.7f else 0f,
            spaceAfter = size * 0.45f,
            lineHeight = size * NATURAL_LINE_HEIGHT * style.lineSpacing,
        )
        p = when (kind) {
            Kind.PARAGRAPH -> p.copy(firstLineIndent = indent)
            Kind.EMBEDDED -> p.copy(firstLineIndent = indent, restLineIndent = indent, endIndent = indent)
            Kind.CENTERED -> p.copy(align = TextAlign.Center)
            Kind.LIST1 -> p.copy(firstLineIndent = indent, restLineIndent = indent * 2)
            Kind.LIST2 -> p.copy(firstLineIndent = indent * 2, restLineIndent = indent * 3)
            Kind.POETRY1 -> p.copy(firstLineIndent = indent, restLineIndent = indent * 2.5f, spaceAfter = size * 0.12f)
            Kind.POETRY2 -> p.copy(firstLineIndent = indent * 2.5f, restLineIndent = indent * 3.5f, spaceAfter = size * 0.12f)
            Kind.SELAH -> p.copy(align = TextAlign.End, spaceAfter = size * 0.3f)
            Kind.TITLE -> p.copy(align = TextAlign.Center, spaceAfter = size * 0.7f, lineHeight = size * NATURAL_LINE_HEIGHT * 1.1f)
            else -> p
        }
        return p
    }

    /** One verse fragment: its number, its text with red / italic / small-cap runs, and footnote letters. */
    internal fun fragment(fragment: Fragment, kind: Kind): AnnotatedString {
        val isTitle = kind == Kind.TITLE
        val italic = isTitle || kind == Kind.SELAH
        val base = body.copy(
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            color = if (isTitle) palette.secondary else palette.ink,
        )
        val text = fragment.text
        val runs = Runs(text, base, palette.red)
        for (span in fragment.spans) {
            // Scalar offsets → UTF-16, here and nowhere else.
            val range = text.utf16Range(span.start, span.length)
            if (range.last + 1 > text.length) continue
            when (span.style) {
                Span.Style.WORDS_OF_CHRIST -> if (style.redLetters) runs.red(range)
                Span.Style.SUPPLIED -> runs.italic(range)
                // "c" marks the divine name, which Swift finds by pattern instead (below); a span
                // style it doesn't draw isn't drawn here either.
                else -> Unit
            }
        }
        divineNameTails(text).forEach { tail ->
            runs.smallCaps(tail, synthetic = !fonts.italicHasSmallCaps && runs.isItalic(tail.first))
        }

        val markers = if (style.footnotes) {
            fragment.footnotes.map { note ->
                // Clamped to the text's end, as Swift's `min(text.length, …)`.
                minOf(text.length, text.utf16Offset(note.position)) to footnoteMarker(note.text)
            }.sortedBy { it.first }
        } else {
            emptyList()
        }

        val result = AnnotatedString.Builder()
        if (fragment.numbered && style.verseNumbers && fragment.verse > 0) {
            // Narrow no-break space keeps the number on the same line as its first word.
            val number = SpanStyle(
                fontFamily = fonts.chrome, fontWeight = FontWeight.SemiBold, fontSize = numberSize.sp,
                color = palette.accent, baselineShift = shift(size * 0.32f, numberSize),
            )
            result.withStyle(number) { append(fragment.verse.toString()) }
            // The platform sans draws U+202F about twice as wide as SF does, which opened a visible
            // gap between number and word; tightened to match the iOS reference (Genesis 2).
            result.withStyle(number.copy(letterSpacing = NUMBER_GAP_TIGHTENING.em)) { append("\u202F") }
        }
        result.append(runs.build(markers))
        return result.toAnnotatedString()
    }

    /**
     * The letter, carrying the note's text as a [FOOTNOTE_TAG] annotation — as Swift carries it in the
     * `.footnote` attribute — so a tap on the letter can find what to show without a second lookup.
     */
    private fun footnoteMarker(note: String): AnnotatedString {
        footnoteCounter += 1
        val label = LETTERS[(footnoteCounter - 1) % LETTERS.length].toString()
        val markerSize = maxOf(9f, size * 0.55f)
        val marker = AnnotatedString.Builder()
        marker.pushStringAnnotation(FOOTNOTE_TAG, note)
        marker.withStyle(
            SpanStyle(
                fontFamily = fonts.chrome, fontWeight = FontWeight.Medium, fontStyle = FontStyle.Normal,
                fontSize = markerSize.sp, color = palette.secondary, baselineShift = shift(size * 0.38f, markerSize),
            ),
        ) { append(label) }
        marker.pop()
        return marker.toAnnotatedString()
    }

    private fun verseKey(verse: Int) = chapter.book * 1_000_000 + chapter.chapter * 1_000 + verse

    /**
     * Per-character styling of one run of text, flattened into spans at the end. Styles overlap
     * freely in the layout (a supplied word inside words of Christ; the divine name inside either), so
     * each UTF-16 unit carries its own flags and runs are cut wherever they change — which also keeps
     * inserted footnote letters out of the surrounding red or italic.
     */
    private class Runs(val text: String, val base: SpanStyle, val redColor: Color) {
        private val red = BooleanArray(text.length)
        private val italic = BooleanArray(text.length)
        /** 0 none, 1 real small caps (`smcp`), 2 synthetic (reduced capitals). */
        private val caps = ByteArray(text.length)
        private val chars = text.toCharArray()

        fun red(range: IntRange) = range.forEach { red[it] = true }
        fun italic(range: IntRange) = range.forEach { italic[it] = true }
        fun isItalic(at: Int): Boolean = base.fontStyle == FontStyle.Italic || italic[at]

        /** "LORD" → L + small-cap "ord"; synthetic keeps the capitals and draws them smaller. */
        fun smallCaps(range: IntRange, synthetic: Boolean) = range.forEach {
            if (synthetic) {
                caps[it] = 2
            } else {
                caps[it] = 1
                chars[it] = chars[it].lowercaseChar()
            }
        }

        fun build(markers: List<Pair<Int, AnnotatedString>>): AnnotatedString {
            val out = AnnotatedString.Builder()
            var m = 0
            var i = 0
            while (i <= text.length) {
                while (m < markers.size && markers[m].first == i) out.append(markers[m++].second)
                if (i == text.length) break
                var j = i + 1
                while (j < text.length && same(i, j) && markers.getOrNull(m)?.first != j) j++
                out.withStyle(styleAt(i)) { append(String(chars, i, j - i)) }
                i = j
            }
            return out.toAnnotatedString()
        }

        private fun same(a: Int, b: Int) = red[a] == red[b] && italic[a] == italic[b] && caps[a] == caps[b]

        private fun styleAt(i: Int): SpanStyle {
            var s = base
            if (red[i]) s = s.copy(color = redColor)
            if (italic[i]) s = s.copy(fontStyle = FontStyle.Italic)
            when (caps[i].toInt()) {
                1 -> s = s.copy(fontFeatureSettings = "smcp")
                2 -> s = s.copy(fontSize = (base.fontSize.value * SYNTHETIC_SMALL_CAP_SCALE).sp)
            }
            return s
        }
    }

    companion object {
        /** The string annotation on a footnote letter; its item is the note's text. */
        const val FOOTNOTE_TAG = "footnote"

        /**
         * The system fonts' natural line height as a multiple of point size. iOS's
         * `lineHeightMultiple` multiplies the font's own line height, not its size; measured on the
         * iOS reference (Genesis 2, 19 pt, 1.35 → 30.6 pt lines) New York's is 1.19 × size, as SF's
         * is. Compose takes an absolute line height, so the same product is written out here rather
         * than inheriting Source Serif's or Roboto's slightly different metrics.
         */
        const val NATURAL_LINE_HEIGHT = 1.19f

        /** Reduced capitals standing in for small caps where a face has none: about its x-height. */
        const val SYNTHETIC_SMALL_CAP_SCALE = 0.78f

        private const val LETTERS = "abcdefghijklmnopqrstuvwxyz"

        /** Letter spacing on the space after a verse number, in em of the number's size. */
        private const val NUMBER_GAP_TIGHTENING = -0.15f

        /**
         * Android's baseline shift is a multiple of the span font's ascent (≈ 0.93 em in the platform
         * sans), not points; this converts Swift's `baselineOffset` in points into that multiple.
         */
        private const val SANS_ASCENT = 0.93f
        private fun shift(points: Float, fontSize: Float) = BaselineShift(points / (fontSize * SANS_ASCENT))

        /** `\b(LORD|GOD)(?=\b|’|')` — capitals standing for the divine name, as Swift matches them. */
        private val DIVINE_NAME = Regex("""\b(LORD|GOD)(?=\b|’|')""")

        /** The ranges after each match's first letter: the part drawn as small capitals. */
        internal fun divineNameTails(text: String): List<IntRange> =
            DIVINE_NAME.findAll(text).map { (it.range.first + 1)..it.range.last }.toList()
    }
}
