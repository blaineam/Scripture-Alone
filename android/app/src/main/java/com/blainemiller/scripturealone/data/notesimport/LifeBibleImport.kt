package com.blainemiller.scripturealone.data.notesimport

import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.keepsake.ZipArchive
import com.blainemiller.scripturealone.data.reference.ReferenceParser
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.math.abs

/**
 * Reads a `LifeBibleData.zip` — the export produced by Life Bible (formerly Tecarta Bible) under
 * Settings → Advanced → Export your data — so years of someone's study notes can move here. Ported
 * from `ScriptureAloneCore/Import/LifeBibleImport.swift`.
 *
 * **What the export actually is.** Not JSON, not CSV: a folder of HTML pages meant for a person to
 * read, and the structure has to be recovered from them.
 *
 * - `verse-notes.html` — one `<p>` per note. The first line is the reference, then `<br>`, then the
 *   note's own HTML.
 * - `highlights.html` — `<br>`-separated lines of `reference  #rrggbb`, optionally prefixed
 *   `underline ` and optionally suffixed `word: N` / `words: N-M`.
 * - `saves-in-this-folder.html` — `<br>`-separated references, one per saved verse.
 * - every other `.html` — a journal entry, whose title is its file name and whose body is the page.
 * - Folders in the app become directories in the archive, so a journal file may sit at any depth.
 *
 * **References are display strings, not identifiers.** They read `Genesis 3:1 NKJV`, and three
 * things about that matter. The space between the book and the chapter is a **non-breaking** space
 * (U+00A0), so splitting on `" "` finds nothing. The trailing token is the translation the note was
 * written against, which is information about the note rather than part of the reference. And the
 * book name is whatever that translation calls it, so it has to go through the same fuzzy matcher the
 * reader's own "jump to passage" field uses rather than a fixed table.
 *
 * **Nothing is guessed.** A line whose reference cannot be resolved is not dropped and not
 * approximated: it is collected in `unresolved` so the reader can be told exactly what did not come
 * across. Losing a note silently would be worse than not importing at all.
 */
object LifeBibleImport {

    // MARK: Reading the archive

    /**
     * @param verseCount how many verses a chapter has, in the reader's own translation. It is what
     *   lets a highlight spanning a chapter break (Genesis 1:30–2:2) be walked through real verses —
     *   see [verses]. Without it, or where it answers 0, nothing is invented: only the verses the
     *   reference itself names are highlighted.
     */
    fun read(archive: ByteArray, verseCount: ((book: BookID, chapter: Int) -> Int)? = null): ImportedNotes {
        // The Swift reader indexes lazily and skips an entry that fails to inflate; ZipArchive inflates
        // everything up front and refuses the whole archive instead. Either way the reader is told the
        // file couldn't be opened rather than being handed half of it silently.
        val entries = try {
            ZipArchive.read(archive)
        } catch (_: ZipArchive.ZipException) {
            throw NoteImportException(NoteImportError.NOT_AN_ARCHIVE)
        }
        // ZipReader refuses an archive with no files in it, which the import reports as not a zip.
        if (entries.isEmpty()) throw NoteImportException(NoteImportError.NOT_AN_ARCHIVE)

        val result = ImportedNotes()
        var sawKnownFile = false

        for ((name, bytes) in entries) {
            if (!name.lowercase().endsWith(".html")) continue
            // Directory entries and macOS resource forks are not content.
            val file = name.split('/').filter { it.isNotEmpty() }
            val leaf = file.lastOrNull() ?: continue
            if ("__MACOSX" in file) continue
            val html = decodeUtf8(bytes) ?: continue

            when (leaf.lowercase()) {
                "verse-notes.html" -> { sawKnownFile = true; readVerseNotes(html, result) }
                "highlights.html" -> { sawKnownFile = true; readHighlights(html, result, verseCount) }
                "saves-in-this-folder.html" -> { sawKnownFile = true; readSaves(html, result) }
                else -> {
                    // A journal entry. Its folder path is kept in the title so a reader who organised
                    // their journal into folders can still tell entries apart.
                    val folders = file.drop(1).dropLast(1).filter { it != "LifeBibleData" }
                    readJournal(html, leaf, folders, result)
                }
            }
        }

        if (!sawKnownFile && result.journals.isEmpty()) throw NoteImportException(NoteImportError.NOT_A_LIFE_BIBLE_EXPORT)
        if (result.isEmpty) throw NoteImportException(NoteImportError.NOTHING_TO_IMPORT)
        return result
    }

    /** `String(data:encoding: .utf8)`: null for bytes that aren't valid UTF-8, rather than U+FFFD. */
    private fun decodeUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    // MARK: The four shapes

    internal fun readVerseNotes(html: String, result: ImportedNotes) {
        for (block in paragraphs(body(html))) {
            // Reference, <br>, then the note. Only the first break separates them; the rest belong to
            // the note's own text.
            val pieces = lines(block)
            val head = pieces.firstOrNull() ?: continue
            val rest = pieces.drop(1).joinToString("\n")
            val reference = reference(head)
            if (reference == null) {
                if (text(block).isNotEmpty()) result.unresolved += text(head)
                continue
            }
            val body = text(rest)
            if (body.isEmpty()) continue
            result.verseNotes += ImportedNotes.Note(reference.range, reference.display, body, reference.translation)
        }
    }

    private const val WS = """[\t\n\f\r\p{Z}]"""   // ICU's \s, which NSRegularExpression uses; Java's \s is ASCII
    private val wordOffsets = Regex("""$WS+words?:$WS*\p{Nd}+(-\p{Nd}+)?$""")

    internal fun readHighlights(html: String, result: ImportedNotes, verseCount: ((book: BookID, chapter: Int) -> Int)? = null) {
        for (line in lines(body(html))) {
            val text = text(line)
            if (text.isEmpty()) continue
            // "underline " is a style Life Bible has and this app does not; the highlight still comes
            // across, in the nearest color, rather than being dropped for want of a style.
            var remainder = text
            if (remainder.lowercase().startsWith("underline ")) {
                remainder = remainder.graphemes().drop("underline ".length).joinToString("")
            }
            // Trailing "word: 3" / "words: 3-7" are Life Bible's own word offsets within a verse. They
            // cannot be honoured here — this app highlights whole verses — so they are cut.
            wordOffsets.find(remainder)?.let { remainder = remainder.removeRange(it.range) }
            val hash = remainder.lastIndexOfCharacter("#")
            if (hash < 0) { result.unresolved += text; continue }
            val hex = remainder.substring(hash + 1).trimSwiftWhitespace()
            val head = remainder.substring(0, hash)
            val reference = reference(head)
            val range = reference?.range
            if (reference == null || range == null) {
                result.unresolved += text
                continue
            }
            val translation = reference.translation
            val color = nearestColor(hex)
            // One row per verse: a Life Bible highlight spanning verses exports as one line each, but
            // a range would still be meaningful and is expanded rather than truncated.
            for (verse in verses(range, verseCount)) {
                result.highlights += ImportedNotes.Highlight(verse, color, translation)
            }
        }
    }

    /**
     * The real verses a range covers, for expanding a highlight into one row per verse.
     *
     * **Not every integer key between the ends.** Keys are `book·10⁶ + chapter·10³ + verse`, so
     * counting from Genesis 1:30 to 2:2 by one passes 1:31…1:999 and 2:0 — nearly a thousand verses
     * that do not exist, each of which became a highlight. This walks a chapter to its last verse and
     * carries on at verse 1 of the next, which crosses a book boundary correctly too.
     *
     * Verses the reference names itself — its start verse, and everything up to its end verse in the
     * end chapter — are always kept, even past [verseCount]: versifications differ, and the reader's
     * translation disagreeing about a verse is no reason to drop a highlight they made. Only the verses
     * *filled in* between depend on the counts, and where a count is unknown (no function, or 0 for a
     * chapter the translation lacks) none are filled in for that chapter.
     */
    internal fun verses(range: VerseRange, verseCount: ((book: BookID, chapter: Int) -> Int)?): List<VerseRef> {
        val start = range.start
        val end = range.end
        val out = ArrayList<VerseRef>()
        var book = BookID.of(start.book) ?: return out
        var chapter = start.chapter
        while (true) {
            val isFirst = book.number == start.book && chapter == start.chapter
            val isLast = book.number == end.book && chapter == end.chapter
            val from = if (isFirst) start.verse else 1
            val known = verseCount?.invoke(book, chapter) ?: 0
            val through = when {
                isLast -> end.verse
                known > 0 -> maxOf(known, if (isFirst) from else 0)
                // Unknown length: the start verse was named, so it is real; nothing after it is known.
                isFirst -> from
                else -> 0
            }
            for (verse in from..through) out += VerseRef(book.number, chapter, verse)
            if (isLast) break
            // `ChapterRef.next` in Swift: the next chapter, or the first of the next book.
            if (chapter < book.chapterCount) {
                chapter += 1
            } else {
                book = BookID.of(book.number + 1) ?: break
                chapter = 1
            }
            if (book.number > end.book || (book.number == end.book && chapter > end.chapter)) break
        }
        return out
    }

    internal fun readSaves(html: String, result: ImportedNotes) {
        for (line in lines(body(html))) {
            val text = text(line)
            if (text.isEmpty()) continue
            val range = reference(text)?.range
            if (range == null) {
                result.unresolved += text
                continue
            }
            result.saved += range
        }
    }

    internal fun readJournal(html: String, leaf: String, folders: List<String>, result: ImportedNotes) {
        val body = text(body(html))
        if (body.isEmpty()) return
        // The file name is a slug of the title the reader gave it. The title is usually repeated as the
        // entry's first line, which is a better source: it kept its capitals and punctuation.
        val slug = leaf.replace(".html", "", ignoreCase = true)
        val fromSlug = slug.replace("-", " ")
        val firstLine = body.split('\n').firstOrNull { it.isNotEmpty() } ?: ""
        val title = if (firstLine.isNotEmpty() && firstLine.graphemeCount() <= 120 &&
            slugify(firstLine).startsWith(slugify(fromSlug).graphemes().take(24).joinToString(""))
        ) {
            firstLine.trimCharacters(" .")
        } else {
            capitalized(fromSlug)
        }
        val prefixed = if (folders.isEmpty()) title else folders.joinToString(" › ") + " › " + title
        result.journals += ImportedNotes.Note(null, prefixed, body, null)
    }

    // MARK: References

    internal data class Reference(val range: VerseRange?, val display: String, val translation: String?)

    private val nonVersified = Regex(""",$WS*para\.$WS*\p{Nd}+""")

    /**
     * Pulls `Genesis 3:1 NKJV` apart, tolerating the non-breaking space and the trailing translation,
     * and resolves it with the same parser the reader's own passage field uses.
     */
    internal fun reference(raw: String): Reference? {
        var text = text(raw).replace('\u00A0', ' ').trimSwiftWhitespaceAndNewlines()
        if (text.isEmpty()) return null

        // A non-versified resource exports "Book, para. 4", which names nothing in a Bible.
        if (nonVersified.containsMatchIn(text)) return null

        // The translation is the last token when it looks like an abbreviation — all caps and digits,
        // two or more characters: CSB, NKJV, NASB95, NIV84. A book name never does.
        var translation: String? = null
        val space = text.lastIndexOfCharacter(" ")
        if (space >= 0) {
            val tail = text.substring(space + 1).graphemes()
            if (tail.size in 2..10 && tail.all { isSwiftUppercase(it) || isSwiftNumber(it) } && tail.any(::isSwiftLetter)) {
                translation = tail.joinToString("")
                text = text.substring(0, space).trimSwiftWhitespace()
            }
        }
        val passage = ReferenceParser.parse(text)?.clamped ?: return null
        // No store here to ask for verse counts, and an import must not invent an end verse it cannot
        // check — a whole-chapter reference becomes its first verse, which is where the reader will be
        // taken.
        val (first, last) = passage.range { _, _ -> passage.endVerse ?: passage.startVerse ?: 1 }
        val range = VerseRange.of(VerseRef.fromKey(first), VerseRef.fromKey(last))
        return Reference(range, text, translation)
    }

    // MARK: Colors

    private val ours: List<Pair<String, Long>> = listOf(
        "yellow" to 0xF7D154L, "green" to 0x8CD48AL, "blue" to 0x7FB8F0L,
        "pink" to 0xF29BB8L, "purple" to 0xB9A2ECL,
    )

    /**
     * Life Bible's five palette colors, plus any custom color a reader picked, mapped to the nearest
     * of this app's five.
     *
     * Matched by **hue**, not by distance in RGB. That distinction is the whole of it: Life Bible's
     * palette is pale (`#cae1fe`) where this app's is saturated (`#7fb8f0`), so straight RGB distance
     * is dominated by lightness and files a pale blue under purple — which is simply the wrong colour.
     * Hue is what someone means when they say they highlighted a verse in blue.
     *
     * A colour with almost no hue — Life Bible's grey — is matched instead to the palest colour here,
     * since there is no grey to match it to and any answer is a compromise.
     */
    internal fun nearestColor(hex: String): String {
        val value = parseUInt32(hex.trimCharacters("# "), 16) ?: return "yellow"
        val target = hueAndSaturation(value)
        // Under about 15% saturation there is no hue worth comparing.
        if (target.second < 0.15) {
            return ours.minByOrNull { hueAndSaturation(it.second).second }?.first ?: "yellow"
        }
        return ours.minByOrNull { hueDistance(hueAndSaturation(it.second).first, target.first) }?.first ?: "yellow"
    }

    /**
     * Degrees around the colour wheel, and how far from grey — enough for matching, without pulling a
     * colour framework into a parser.
     */
    internal fun hueAndSaturation(rgb: Long): Pair<Double, Double> {
        val r = ((rgb shr 16) and 0xFF) / 255.0
        val g = ((rgb shr 8) and 0xFF) / 255.0
        val b = (rgb and 0xFF) / 255.0
        val high = maxOf(r, g, b)
        val low = minOf(r, g, b)
        val spread = high - low
        if (spread <= 0 || high <= 0) return 0.0 to 0.0
        // Kotlin's % on doubles is fmod, like Swift's truncatingRemainder: the sign follows the dividend.
        val hue = when (high) {
            r -> 60 * (((g - b) / spread) % 6)
            g -> 60 * ((b - r) / spread + 2)
            else -> 60 * ((r - g) / spread + 4)
        }
        return (if (hue < 0) hue + 360 else hue) to spread / high
    }

    /** Around the wheel, so 350° and 10° are twenty degrees apart rather than three hundred. */
    internal fun hueDistance(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return minOf(d, 360 - d)
    }

    // MARK: HTML, reduced to text

    internal fun body(html: String): String {
        val open = html.indexOf("<body", ignoreCase = true)
        if (open < 0) return html
        val close = html.indexOf('>', open + "<body".length)
        if (close < 0) return html
        val rest = html.substring(close + 1)
        val end = rest.indexOf("</body>", ignoreCase = true)
        return if (end >= 0) rest.substring(0, end) else rest
    }

    private val paragraph = Regex("""<p\b[^>]*>(.*?)</p>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val lineBreak = Regex("""<br$WS*/?>""", RegexOption.IGNORE_CASE)
    private val scriptOrStyle = Regex("""<(script|style)\b.*?</\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val blockClose = Regex("""</(p|div|li|h[1-6]|blockquote)>""", RegexOption.IGNORE_CASE)
    private val anyTag = Regex("<[^>]+>")

    internal fun paragraphs(html: String): List<String> =
        paragraph.findAll(html).map { it.groupValues[1] }.toList()

    internal fun lines(html: String): List<String> = html.replace(lineBreak, "\n").split("\n")

    private val namedEntities = listOf(
        "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">",
        "&quot;" to "\"", "&apos;" to "'",
        "&rsquo;" to "’", "&lsquo;" to "‘", "&ldquo;" to "“",
        "&rdquo;" to "”", "&mdash;" to "—", "&ndash;" to "–",
    )

    /**
     * Tags out, entities decoded, whitespace tidied — without pulling in a whole HTML parser for four
     * files whose markup this app produced a reader for, not a browser.
     */
    internal fun text(html: String): String {
        var out = html.replace(lineBreak, "\n")
        out = out.replace(scriptOrStyle, "")
        out = out.replace(blockClose, "\n")
        out = out.replace(anyTag, "")
        // Numeric references first, and generally: the export writes `&#39;` for an apostrophe and
        // `&#47;` for a slash, so a date typed as 06/11/22 arrives as `06&#47;11&#47;22`. A fixed table
        // of named entities would have let that through into someone's note, which is how it was found.
        out = decodeNumericEntities(out)
        for ((entity, character) in namedEntities) out = out.replace(entity, character, ignoreCase = true)
        out = out.replace('\u00A0', ' ')
        // Collapse runs of blank lines, and trailing space on each line.
        val kept = mutableListOf<String>()
        for (line in out.split("\n").map { it.trimSwiftWhitespace() }) {
            if (line.isEmpty() && (kept.lastOrNull()?.isEmpty() != false)) continue
            kept += line
        }
        return kept.joinToString("\n").trimSwiftWhitespaceAndNewlines()
    }

    private val numericEntity = Regex("&#(x?)([0-9A-Fa-f]{1,6});")

    /**
     * `&#39;` and `&#x27;` — every numeric character reference, rather than the handful someone thought
     * to list. `&amp;` is deliberately left to the named pass that follows, so that a literal `&amp;#39;`
     * in a note stays literal instead of being decoded twice.
     */
    internal fun decodeNumericEntities(text: String): String {
        if (!text.contains("&#")) return text
        return numericEntity.replace(text) { match ->
            val radix = if (match.groupValues[1].isEmpty()) 10 else 16
            val value = parseUInt32(match.groupValues[2], radix)
            // Swift's `Unicode.Scalar(_:)` refuses surrogates and anything past U+10FFFF; such a
            // reference is left as written.
            if (value == null || value > 0x10FFFF || value in 0xD800L..0xDFFFL) match.value
            else String(Character.toChars(value.toInt()))
        }
    }

    internal fun slugify(text: String): String =
        text.lowercase().graphemes().joinToString("") { if (isSwiftLetter(it) || isSwiftNumber(it)) it else "-" }

    /**
     * Foundation's `capitalized`: the first letter of each word uppercased and the rest lowercased,
     * a word being anything between whitespace.
     */
    private fun capitalized(text: String): String {
        val out = StringBuilder(text.length)
        var atWordStart = true
        for (g in text.graphemes()) {
            if (g.all { Character.isWhitespace(it) }) {
                out.append(g)
                atWordStart = true
            } else {
                out.append(if (atWordStart) g.uppercase() else g.lowercase())
                atWordStart = false
            }
        }
        return out.toString()
    }
}
