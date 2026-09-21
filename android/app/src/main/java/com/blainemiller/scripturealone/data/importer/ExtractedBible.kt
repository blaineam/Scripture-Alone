package com.blainemiller.scripturealone.data.importer

import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID

/**
 * A chapter of a canonical book — Swift's `ChapterRef` (`VerseRef.swift`), which carries a [BookID]
 * rather than a bare number. Ordered by book, then chapter.
 */
data class ChapterRef(val book: BookID, val chapter: Int) : Comparable<ChapterRef> {
    override fun compareTo(other: ChapterRef): Int =
        compareValuesBy(this, other, { it.book.number }, { it.chapter })

    /** "Genesis 2", or just "Jude" for a single-chapter book. */
    val display: String get() = if (book.isSingleChapter) book.displayName else "${book.displayName} $chapter"

    fun verse(verse: Int): VerseRef = VerseRef(book.number, chapter, verse)
}

internal fun verseRef(book: BookID, chapter: Int, verse: Int): VerseRef = VerseRef(book.number, chapter, verse)

/** A run of styled text, in Unicode scalars — the same unit `ChapterLayout.Span` uses. */
data class ScalarSpan(val start: Int, val length: Int)

/**
 * A styled run inside a fragment. The three styles are the ones the reader already draws: words of
 * Christ, supplied words (the KJV's italics) and small caps (LORD).
 */
data class StyledSpan(val start: Int, val length: Int, val style: Style) {
    enum class Style(val rawValue: String) { WORDS_OF_CHRIST("r"), SUPPLIED("i"), SMALL_CAPS("c") }

    val scalarSpan: ScalarSpan get() = ScalarSpan(start, length)
}

/** A footnote marker: where it sits in the text (Unicode scalars) and what it said. */
data class ExtractedFootnote(val position: Int, val text: String)

/**
 * One verse's worth of text inside one paragraph. A verse that runs across two paragraphs has two
 * fragments; a paragraph holding five verses has five. Immutable, as the Swift struct is a value.
 */
data class ExtractedFragment(
    val verse: Int,
    /** The verse number is printed at the start of this fragment. */
    val numbered: Boolean,
    val text: String,
    /** Words of Christ, supplied words and small caps, when the file marks them. */
    val spans: List<StyledSpan> = emptyList(),
    val footnotes: List<ExtractedFootnote> = emptyList(),
) {
    val red: List<ScalarSpan> get() = spans.filter { it.style == StyledSpan.Style.WORDS_OF_CHRIST }.map { it.scalarSpan }
}

/** A block in reading order: a section heading, or a paragraph of verse fragments. */
data class ExtractedBlock(
    val kind: Kind,
    /** Set for heading kinds. */
    val heading: String? = null,
    val fragments: List<ExtractedFragment> = emptyList(),
) {
    /** Raw values are the layout kinds `ChapterLayout` already decodes. */
    enum class Kind(val rawValue: String) {
        HEADING("s1"), SUBHEADING("s2"), MAJOR_SECTION("ms"), PARALLEL("r"), ACROSTIC("qa"),
        PARAGRAPH("p"), CONTINUATION("m"), EMBEDDED("pmo"), CENTERED("pc"),
        LIST1("li1"), LIST2("li2"), POETRY1("q1"), POETRY2("q2"), SELAH("qr"), TITLE("d"),

        /** A blank line between stanzas. Carries no text and is never dropped as empty. */
        STANZA_BREAK("b");

        val isHeading: Boolean get() = this == HEADING || this == SUBHEADING || this == MAJOR_SECTION || this == PARALLEL || this == ACROSTIC
    }

    val isEmpty: Boolean
        get() {
            if (kind == Kind.STANZA_BREAK) return false
            return if (kind.isHeading) (heading ?: "").isEmpty()
            else fragments.all { it.text.isEmpty() && !it.numbered && it.footnotes.isEmpty() }
        }
}

/**
 * One verse's whole text, joined across however many paragraphs carried it. This is what search,
 * speech and sharing read, and what the `verses` table stores.
 */
data class ExtractedVerse(val ref: VerseRef, val text: String, val red: List<ScalarSpan> = emptyList())

/** Something the engine noticed while reading; these ride into the coverage report. */
data class ImportNote(val severity: Severity, val message: String) {
    /** Declared in rank order, so `compareTo` is Swift's `Comparable` conformance. */
    enum class Severity(val rawValue: String) { INFO("info"), WARNING("warning"), PROBLEM("problem") }
}

/**
 * Everything one file yielded: verses, the layout blocks that print them, and what went wrong.
 * Ported from `Import/ExtractedBible.swift`.
 */
class ExtractedBible {
    private val chapterList = ArrayList<ChapterRef>()
    private val blockMap = HashMap<ChapterRef, List<ExtractedBlock>>()
    private val verseMap = HashMap<VerseRef, ExtractedVerse>()

    /** Chapters in the order they were met, so the reader's blocks stay in reading order. */
    val chapterOrder: List<ChapterRef> get() = chapterList
    val blocks: Map<ChapterRef, List<ExtractedBlock>> get() = blockMap
    val verses: Map<VerseRef, ExtractedVerse> get() = verseMap
    var notes: MutableList<ImportNote> = ArrayList()
        internal set

    /** Chapters whose verse numbers went backwards — the report names them. */
    var outOfOrderChapters: Set<ChapterRef> = emptySet()
        internal set

    /**
     * Verses a source combined into one (USFM `\v 1-2`): the absent number maps to the verse that
     * holds the text, so the report calls it combined rather than missing.
     */
    var bridgedVerses: Map<VerseRef, VerseRef> = emptyMap()
        internal set

    /** How each spine file's verse markup was recognised. */
    val shapesByDocument: MutableMap<String, VerseMarkupShape> = LinkedHashMap()

    val books: List<BookID> get() = chapterList.map { it.book }.distinct().sortedBy { it.number }

    val verseCount: Int get() = verseMap.size
    val isEmpty: Boolean get() = verseMap.isEmpty()

    fun blocks(chapter: ChapterRef): List<ExtractedBlock> = blockMap[chapter] ?: emptyList()

    /** Highest verse number recorded in a chapter (what the `chapters.verses` column stores). */
    fun highestVerse(chapter: ChapterRef): Int = verseNumbers(chapter).maxOrNull() ?: 0

    fun verseNumbers(chapter: ChapterRef): List<Int> =
        verseMap.keys.filter { it.book == chapter.book.number && it.chapter == chapter.chapter }.map { it.verse }.sorted()

    internal fun append(block: ExtractedBlock, chapter: ChapterRef) {
        val existing = blockMap[chapter]
        if (existing == null) chapterList.add(chapter)
        blockMap[chapter] = (existing ?: emptyList()) + block
    }

    /**
     * Adds text to a verse. [separate] is true when this chunk starts a new fragment, which is the only
     * time a joining space is inserted — mid-fragment runs (`\add word\add*s`) must not gain one. This
     * is the rule `Tools/build_bibles.py` uses. [red] spans are relative to [text], in scalars.
     */
    internal fun appendVerseText(text: String, red: List<ScalarSpan>, ref: VerseRef, separate: Boolean = true) {
        if (text.isEmpty()) return
        val existing = verseMap[ref] ?: ExtractedVerse(ref, "")
        var existingText = existing.text
        var addition = text
        var dropped = 0
        if (existingText.isEmpty()) {
            val (rest, count) = SwiftText.dropLeadingSpaces(addition)
            addition = rest
            dropped = count
        } else if (separate && !existingText.endsWith(' ') && !SwiftText.hasPrefixSpace(addition)) {
            existingText += " "
        }
        if (addition.isEmpty()) {
            verseMap[ref] = existing.copy(text = existingText)
            return
        }
        val offset = SwiftText.scalarCount(existingText)
        val shifted = red.mapNotNull { span ->
            val start = span.start - dropped
            if (start >= 0) return@mapNotNull ScalarSpan(offset + start, span.length)
            val length = span.length + start
            if (length > 0) ScalarSpan(offset, length) else null
        }
        verseMap[ref] = existing.copy(text = existingText + addition, red = existing.red + shifted)
    }

    /**
     * Trims trailing space and merges touching red spans, so stored text matches what the bundled
     * translations look like.
     */
    internal fun tidy() {
        for ((ref, verse) in verseMap.entries.toList()) {
            var text = verse.text
            var red = verse.red
            val trimmed = SwiftText.trimWhitespaceAndNewlines(text)
            if (trimmed != text) {
                val lead = SwiftText.scalarCount(text) - SwiftText.scalarCount(SwiftText.dropLeadingWhitespace(text))
                text = trimmed
                val limit = SwiftText.scalarCount(trimmed)
                red = red.mapNotNull { span ->
                    val start = span.start - lead
                    if (start >= limit) return@mapNotNull null
                    ScalarSpan(maxOf(0, start), minOf(span.length, limit - maxOf(0, start)))
                }
            }
            verseMap[ref] = verse.copy(text = text, red = merge(red))
        }
        for (chapter in chapterList) {
            val kept = ArrayList<ExtractedBlock>()
            for (block in blockMap[chapter] ?: emptyList()) {
                var tidied = block
                if (tidied.kind.isHeading) {
                    tidied = tidied.copy(heading = tidied.heading?.let(SwiftText::trimWhitespaceAndNewlines))
                }
                if (!tidied.kind.isHeading && tidied.kind != ExtractedBlock.Kind.STANZA_BREAK) {
                    tidied = tidied.copy(
                        fragments = tidied.fragments.map(::trimTrailing)
                            .filter { it.text.isNotEmpty() || it.numbered || it.footnotes.isNotEmpty() },
                    )
                }
                if (tidied.isEmpty) continue
                // A stanza break that leads a chapter, or follows another, prints nothing.
                if (tidied.kind == ExtractedBlock.Kind.STANZA_BREAK &&
                    (kept.isEmpty() || kept.last().kind == ExtractedBlock.Kind.STANZA_BREAK)
                ) continue
                kept.add(tidied)
            }
            while (kept.lastOrNull()?.kind == ExtractedBlock.Kind.STANZA_BREAK) kept.removeAt(kept.size - 1)
            blockMap[chapter] = kept
        }
        chapterList.retainAll { (blockMap[it] ?: emptyList()).isNotEmpty() }
    }

    companion object {
        /** Drops trailing whitespace and pulls the spans and footnote positions back inside the text. */
        fun trimTrailing(fragment: ExtractedFragment): ExtractedFragment {
            val text = SwiftText.dropTrailingWhitespace(fragment.text)
            val limit = SwiftText.scalarCount(text)
            return fragment.copy(
                text = text,
                spans = fragment.spans.mapNotNull { span ->
                    if (span.start >= limit) null else StyledSpan(span.start, minOf(span.length, limit - span.start), span.style)
                },
                footnotes = fragment.footnotes.map { ExtractedFootnote(minOf(it.position, limit), it.text) },
            )
        }

        /** Merges touching runs of the same style. */
        fun mergeStyled(spans: List<StyledSpan>): List<StyledSpan> {
            val merged = ArrayList<StyledSpan>()
            for (style in StyledSpan.Style.entries) {
                val runs = merge(spans.filter { it.style == style }.map { it.scalarSpan })
                merged += runs.map { StyledSpan(it.start, it.length, style) }
            }
            return merged.sortedWith(compareBy({ it.start }, { it.length }))
        }

        fun merge(spans: List<ScalarSpan>): List<ScalarSpan> {
            val sorted = spans.filter { it.length > 0 }.sortedBy { it.start }
            val merged = ArrayList<ScalarSpan>()
            for (span in sorted) {
                val last = merged.lastOrNull()
                if (last != null && span.start <= last.start + last.length) {
                    merged[merged.size - 1] = last.copy(length = maxOf(last.length, span.start + span.length - last.start))
                } else {
                    merged.add(span)
                }
            }
            return merged
        }
    }
}

/**
 * How one spine document numbered its verses. Detected per file, never assumed for the book:
 * publishers mix shapes between front matter, the Gospels and the Psalms in one product.
 */
enum class VerseMarkupShape(val rawValue: String, val label: String) {
    /** `id="ESV_Gen.1.1"`, `id="csb-Gen-1-1"`, `id="MAT.5.3"` — a full reference on the element. */
    REFERENCE_IDENTIFIER("referenceIdentifier", "reference ids"),

    /** `id="v1"`, `id="verse-3"` — a chapter-relative verse anchor. */
    VERSE_ANCHOR("verseAnchor", "verse anchors"),

    /** `class="verse-num"`, `class="vnum"`, `class="v-num"` — a class naming the number. */
    NUMBER_CLASS("numberClass", "numbered classes"),

    /** `<sup>3</sup>` — a superscript holding nothing but digits. */
    SUPERSCRIPT("superscript", "superscript numbers"),

    /** USFM `\c` / `\v` markers — unambiguous, so no detection is needed. */
    USFM_MARKERS("usfmMarkers", "USFM markers"),

    /** Nothing recognisable. */
    NONE("none", "no verse markup"),
}
