package com.blainemiller.scripturealone.data.importer

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Reads the text of a typeset Bible out of a PDF — the kind a publisher exports from its page layout,
 * with a text layer. Ported from `Import/PDFBibleReader.swift`.
 *
 * A PDF has no markup, only glyphs in fonts at sizes. That is enough, because a Bible is typeset by a
 * small set of rules that hold across publishers: chapter numbers are drop caps far larger than the
 * text, verse numbers are set in their own (bold or superscript) font, section headings are smaller
 * or capitalised, footnote callers are small letters, and the notes themselves sit in small type at
 * the foot of the page, each opening with its caller and a chapter:verse reference. Every size below
 * is judged against the file's own body size — the size most of its characters are set in — never
 * against a figure from one publisher.
 *
 * Where the Swift reader asks PDFKit for lines and then where each sits, this one has every glyph's
 * position from the text layer ([PdfTextSource]) and builds the lines, the spaces between words and
 * the reading order itself; everything after that — the classification, the vocabulary that decides
 * what a line break was, the flow handed to the assembler — is the same.
 *
 * The result is the same flow the ePub scanner produces ([ScannedDocument]), one per page, so the
 * assembler does the rest exactly as it does for an ePub: a drop cap starts verse 1, a book title
 * changes book, headings land above the verse they introduce, and a page with no verse markup (front
 * matter, maps, a concordance) contributes nothing.
 */
internal class PDFBibleReader(private val options: BibleTextExtractor.Options) {

    class Run(
        var text: String,
        val size: Double,
        /** The run begins a line of its own and ends one. */
        var ownLine: Boolean = false,
        /** The run ends a line of print. Kept when [Lexicon] resolves the break itself. */
        var endsLine: Boolean = false,
    )

    // MARK: - Reading

    fun extract(source: PdfTextSource): ExtractedBible {
        // Glyphs are read a page at a time and dropped: a whole Bible's are far more than its text.
        val raw = (0 until source.pageCount).map { runs(source.glyphs(it)) }
        val lexicon = Lexicon(raw.flatten())
        val pages = raw.map { lexicon.resolveLineBreaks(it) }
        val body = bodySize(pages.flatten())
        if (body <= 0) throw BibleImportError.NoScriptureFound()
        val rare = rareSizes(pages.flatten(), body)

        val assembler = Assembler(options)
        var outside = true // before the first book title, and after anything that isn't one
        val lastVerse = intArrayOf(0)
        for ((index, runs) in pages.withIndex()) {
            val (document, stillOutside) = scan(runs, index, body, rare, outside, lastVerse)
            outside = stillOutside
            assembler.consume(document, "page ${index + 1}")
        }
        assembler.finish()
        if (assembler.bible.isEmpty) throw BibleImportError.NoScriptureFound()
        return assembler.bible
    }

    /**
     * Turns one page's runs into a flow. [outside] is true while the reader is in front or back matter:
     * set by a large title that isn't a book, cleared by one that is. [lastVerse] carries across pages.
     */
    fun scan(runs: List<Run>, page: Int, body: Double, rare: Set<Double>, outside: Boolean, lastVerse: IntArray): Pair<ScannedDocument, Boolean> {
        val document = ScannedDocument()
        val flow = ArrayList<FlowItem>()
        var outsideNow = outside
        var chapterSinceTitle = true
        var inNotes = false
        var noteID: String? = null
        val noteText = StringBuilder()

        fun closeNote() {
            noteID?.let { id ->
                val trimmed = SwiftText.trimWhitespace(DocumentScanner.collapse(noteText.toString()))
                if (trimmed.isNotEmpty()) document.notes[id] = trimmed
            }
            noteID = null
            noteText.setLength(0)
        }

        fun chapterMarker(number: Int): FlowItem {
            document.chapterMarkers++
            return FlowItem.MarkerItem(Marker(setOf(VerseMarkupShape.NUMBER_CLASS), chapter = number, isChapter = true))
        }

        var skipThrough = -1
        for ((index, run) in runs.withIndex()) {
            if (index <= skipThrough) continue
            val text = SwiftText.trimWhitespaceAndNewlines(run.text.replace("\u00AD", ""))
            if (text.isEmpty() || isPrinterSlug(text)) continue
            val ratio = run.size / body
            val next = runs.subList(index + 1, runs.size).firstOrNull { SwiftText.trimWhitespaceAndNewlines(it.text).isNotEmpty() }

            // A short line that opens a little larger than the text, in a face of its own: an acrostic
            // letter and its name (a Hebrew letter and its name), a heading set as one line in two fonts.
            if (!outsideNow && ratio > 1.04 && ratio < 1.45 && !run.ownLine && !run.endsLine) {
                val end = (index + 1 until runs.size).firstOrNull { runs[it].endsLine }
                if (end != null && end - index <= 2) {
                    val line = runs.subList(index, end + 1).joinToString("") { it.text }
                    val words = SwiftText.trimWhitespaceAndNewlines(line).split(' ').filter { it.isNotBlank() }
                    if (words.size <= 3 && line.length <= 32 && line.none { it.isDigit() }) {
                        if (options.headings) appendHeading(headingText(line), flow)
                        skipThrough = end
                        continue
                    }
                }
            }

            // Titles: a book begins, or something that isn't scripture does.
            if (ratio >= 1.45) {
                // A drop-cap initial: the first letter of the text, set large. In a book of one chapter it
                // is where chapter 1 begins, since no number is printed.
                if (SwiftText.characterCount(text) == 1 && text.first().isLetter()) {
                    if (outsideNow) continue
                    if (!chapterSinceTitle) {
                        flow.add(chapterMarker(1))
                        chapterSinceTitle = true
                        lastVerse[0] = 1
                    }
                    flow.add(FlowItem.Text(text, false))
                    continue
                }
                // A two-digit drop cap can come out as two runs ("1", "0"): digits with nothing between
                // them are one number.
                val previous = (flow.lastOrNull() as? FlowItem.MarkerItem)?.marker
                val first = previous?.chapter
                if (text.length <= 2 && text.all { it.isDigit() } && previous != null && previous.isChapter && first != null && first < 100) {
                    val merged = "$first$text".toIntOrNull()
                    if (merged != null) {
                        flow[flow.size - 1] = FlowItem.MarkerItem(previous.copy(chapter = merged))
                        continue
                    }
                }
                val number = number(text)
                val place = if (number == null) ScriptureLabels.heading(text) else null
                when {
                    number != null -> {
                        if (outsideNow) continue
                        flow.add(chapterMarker(number))
                        chapterSinceTitle = true
                        lastVerse[0] = 1
                    }
                    place?.book != null -> {
                        outsideNow = false
                        chapterSinceTitle = false
                        lastVerse[0] = 0
                        flow.add(FlowItem.Place(place.book, place.chapter))
                    }
                    place?.chapter != null && !outsideNow -> flow.add(chapterMarker(place.chapter))
                    text.none { it.isDigit() } -> outsideNow = true
                }
                continue
            }
            if (outsideNow) continue

            // The notes at the foot of the page: a tiny caller, then "15:4", then the note.
            if (ratio < 0.8) {
                // In a book of one chapter a note names only its verse ("17"), in note-sized type.
                val isLabel = isCallerLetters(text) && ratio < 0.58 && next != null &&
                    (isReference(next.text) || (number(next.text) != null && next.size / body < 0.8))
                if (isLabel) {
                    closeNote()
                    inNotes = true
                    noteID = noteID(page, text)
                    continue
                }
                if (inNotes && noteID != null) {
                    if (!(noteText.isEmpty() && isReference(text))) noteText.append(run.text)
                    continue
                }
                // In the text: a small letter is a footnote caller, a small number a superscript verse
                // number. Anything else this small (a running head, a page number) goes.
                val number = number(text)
                if (isCallerLetters(text)) {
                    if (options.footnotes) flow.add(FlowItem.NoteMarker(noteID(page, text), text))
                    // A caller sits in a word break; the break survives it.
                    val after = next?.text?.firstOrNull()
                    if (run.text.firstOrNull()?.isWhitespace() == true || run.text.lastOrNull()?.isWhitespace() == true ||
                        after == '\u00AD' || after?.isWhitespace() == true
                    ) {
                        flow.add(FlowItem.Text(" ", false))
                    }
                } else if (number != null && followsOn(number, lastVerse[0])) {
                    lastVerse[0] = number
                    document.shapeCounts[VerseMarkupShape.SUPERSCRIPT] = (document.shapeCounts[VerseMarkupShape.SUPERSCRIPT] ?: 0) + 1
                    flow.add(FlowItem.MarkerItem(Marker(setOf(VerseMarkupShape.SUPERSCRIPT), verse = number)))
                }
                continue
            }
            // Body-sized type ends a note: the notes of one column sit between that column's text and
            // the next column's.
            if (inNotes) {
                closeNote()
                inNotes = false
            }

            // Smaller than the text but not note-sized: headings, and the running heads and page numbers
            // every page carries, which aren't content.
            if (ratio < 0.97) {
                if (number(text) != null || isRunningHead(text)) continue
                if (options.headings) appendHeading(headingText(text), flow)
                continue
            }

            // Body-sized. A run of nothing but a number is a verse number: a font change (bold, or a
            // different face) is what split it from the words around it — if it is the number a verse
            // would have here. Measures and dates set in another font ("75 feet") go back into the text.
            val number = number(text)
            if (number != null && number < 200 && followsOn(number, lastVerse[0])) {
                lastVerse[0] = number
                document.shapeCounts[VerseMarkupShape.NUMBER_CLASS] = (document.shapeCounts[VerseMarkupShape.NUMBER_CLASS] ?: 0) + 1
                flow.add(FlowItem.MarkerItem(Marker(setOf(VerseMarkupShape.NUMBER_CLASS), verse = number)))
                continue
            }
            // A line of its own in a size the text almost never uses, or a larger one: a heading ("BOOK I
            // (Psalms 1–41)", an acrostic letter).
            if (run.ownLine && (ratio > 1.04 || rounded(run.size) in rare)) {
                if (options.headings) appendHeading(headingText(text), flow)
                continue
            }
            flow.add(FlowItem.Text(bodyText(run.text), false))
        }
        closeNote()
        document.flow = flow
        return document to outsideNow
    }

    /** A heading set over two lines arrives as two; it is one heading. */
    private fun appendHeading(text: String, flow: MutableList<FlowItem>) {
        val previous = flow.lastOrNull()
        if (previous is FlowItem.Heading) flow[flow.size - 1] = FlowItem.Heading(previous.text + " " + text)
        else flow.add(FlowItem.Heading(text))
    }

    companion object {
        // MARK: - Lines

        /** A rectangle in page space, y growing downward. */
        private class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
            val width: Float get() = right - left
            val height: Float get() = bottom - top
            val midY: Float get() = (top + bottom) / 2

            fun union(other: Box) = Box(minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom))
        }

        /** One row of print: glyphs left to right. */
        private class Piece(val glyphs: List<PdfGlyph>) {
            val box: Box = glyphs.map { Box(it.x, it.top, it.right, it.baseline) }.reduce { a, b -> a.union(b) }
            val size: Float get() = glyphs.maxOf { it.size }
            val text: String get() = glyphs.joinToString("") { it.text }
        }

        private fun overlap(a: PdfGlyph, b: PdfGlyph): Float = minOf(a.baseline, b.baseline) - maxOf(a.top, b.top)

        /**
         * Groups a page's glyphs into rows of print. A glyph continues the row when it sits beside the
         * last one — overlapping it vertically, a little to its right, and of a comparable size (a drop
         * cap is a row of its own). Rows the page drew in pieces are then joined where they touch.
         */
        private fun pieces(glyphs: List<PdfGlyph>): List<Piece> {
            val rows = ArrayList<MutableList<PdfGlyph>>()
            var current: MutableList<PdfGlyph>? = null
            for (glyph in glyphs) {
                if (glyph.text.isBlank() || glyph.width <= 0f && glyph.height <= 0f) continue
                val row = current
                val last = row?.lastOrNull()
                val continues = last != null && run {
                    val size = maxOf(last.size, glyph.size)
                    val ratio = maxOf(last.size, glyph.size) / maxOf(0.1f, minOf(last.size, glyph.size))
                    overlap(last, glyph) > minOf(last.height, glyph.height) * 0.3f &&
                        glyph.x >= last.x - size * 0.2f && glyph.x - last.right <= size * 1.5f && ratio < 2f
                }
                if (continues) {
                    row!!.add(glyph)
                } else {
                    current = mutableListOf(glyph).also { rows.add(it) }
                }
            }
            // Join rows drawn in separate passes that meet on one baseline.
            val joined = ArrayList<MutableList<PdfGlyph>>()
            for (row in rows.map { it.sortedBy { g -> g.x }.toMutableList() }) {
                val first = row.first()
                val target = joined.lastOrNull { other ->
                    val end = other.last()
                    abs(end.baseline - first.baseline) < maxOf(end.size, first.size) * 0.2f &&
                        first.x >= end.right - 1f && first.x - end.right <= maxOf(end.size, first.size) * 0.5f &&
                        maxOf(end.size, first.size) / maxOf(0.1f, minOf(end.size, first.size)) < 2f
                }
                if (target != null) target.addAll(row) else joined.add(row)
            }
            return joined.map { Piece(it) }
        }

        // MARK: - Runs

        /**
         * A page's text in reading order, as runs of one font and size.
         *
         * A two-column page is found by its gutter: the strip down the page, near its middle, that the
         * fewest rows cross. A row that straddles an empty gutter is cut into the text on each side of
         * it; a row with text in the gutter spans the page (a book title, a heading across it) and
         * divides the page into bands, each read left column first, top to bottom, then the right.
         * Every row ends in "\n", for [Lexicon] to decide whether the break fell between words or
         * inside one.
         */
        fun runs(glyphs: List<PdfGlyph>, trace: StringBuilder? = null): List<Run> {
            var pieces = mergedStackedDigits(pieces(glyphs).map(::restoringSmallCapitals))
            if (pieces.isEmpty()) return emptyList()
            // The columns are the text's: rows in the page's own body type. A printer's slug in the margin
            // or a line of notes across the foot says nothing about where the gutter is.
            val typical = pieces.groupingBy { rounded(it.size.toDouble()) }.fold(0) { count, piece -> count + piece.glyphs.size }
                .maxByOrNull { it.value }?.key ?: 0.0
            val body = pieces.filter { abs(it.size.toDouble() - typical) <= typical * 0.1 }.ifEmpty { pieces }
            val left = body.minOf { it.box.left }
            val right = body.maxOf { it.box.right }
            // The gutter is where the fewest rows cross, near the middle — not the middle itself: a
            // left-hand page's columns sit off-centre on the sheet. Of the stretches the fewest rows cross,
            // the widest is the gutter, and its centre the line between the columns: verse numbers hang
            // out from the right-hand column into it.
            val narrow = body.filter { it.box.width < (right - left) * 0.6f }
            val middle = run {
                val center = (left + right) / 2
                val xs = generateSequence(left + (right - left) * 0.3f) { it + 1f }.takeWhile { it <= left + (right - left) * 0.7f }.toList()
                val counts = xs.map { x -> narrow.count { it.box.left < x && it.box.right > x } }
                val fewest = counts.minOrNull() ?: return@run center
                var best = center
                var bestWidth = -1
                var start = -1
                for (i in 0..xs.size) {
                    if (i < xs.size && counts[i] == fewest) {
                        if (start < 0) start = i
                        continue
                    }
                    if (start >= 0) {
                        val width = i - start
                        val mid = (xs[start] + xs[i - 1]) / 2
                        if (width > bestWidth || (width == bestWidth && abs(mid - center) < abs(best - center))) {
                            bestWidth = width
                            best = mid
                        }
                        start = -1
                    }
                }
                best
            }
            val all = pieces.flatMap { it.glyphs }
            fun gutterIsEmpty(box: Box): Boolean {
                val top = box.top + box.height * 0.25f
                val bottom = box.top + box.height * 0.75f
                return all.none { it.x < middle + 1.5f && it.right > middle - 1.5f && it.top < bottom && it.baseline > top }
            }
            // Only a row wide enough to hold text from both columns is a candidate for cutting; a drop
            // cap or verse number sitting on the gutter line is one thing, kept whole.
            val crossing = pieces.indices.filter {
                pieces[it].box.left < middle - 2 && pieces[it].box.right > middle + 2 && pieces[it].box.width > 40
            }.toSet()
            val open = crossing.filter { gutterIsEmpty(pieces[it].box) }.toSet()
            val rightOnly = pieces.count { it.box.left >= middle }
            // Judged by the text's own rows: notes set across the foot of the page cross an empty gutter
            // above them, and say nothing about whether the text runs in columns.
            val bodyCrossing = crossing.filter { pieces[it] in body }
            val twoColumns = rightOnly > 2 && bodyCrossing.count { it in open } * 2 >= bodyCrossing.size
            val spanning = HashSet<Int>()
            if (twoColumns) {
                val split = ArrayList<Piece>()
                for ((index, piece) in pieces.withIndex()) {
                    if (index !in open) {
                        if (index in crossing) spanning.add(split.size)
                        split.add(piece)
                        continue
                    }
                    // Cut at the first glyph that sits right of the gutter.
                    val (before, after) = piece.glyphs.partition { it.x < middle }
                    if (before.isNotEmpty()) split.add(Piece(before))
                    if (after.isNotEmpty()) split.add(Piece(after))
                }
                pieces = split
            } else {
                spanning.addAll(pieces.indices)
            }

            // Which column each piece reads in. Usually the side of the gutter it starts on — but a verse
            // number hung out into the gutter beside a line belongs with the line it numbers: a narrow
            // piece goes with its nearest neighbour on the same line.
            val isRight = pieces.map { piece ->
                if (piece.box.width >= 40) return@map piece.box.left >= middle
                val sameLine = pieces.filter { other ->
                    val overlap = minOf(other.box.bottom, piece.box.bottom) - maxOf(other.box.top, piece.box.top)
                    other.box.width >= 40 && overlap > minOf(other.box.height, piece.box.height) * 0.3f
                }
                val before = sameLine.filter { it.box.right <= piece.box.left + 1 }.minOfOrNull { piece.box.left - it.box.right }
                // Only a line it sits right up against: a short line of one column ("for them.") is not
                // the number of a line across the gutter.
                val after = sameLine.filter { it.box.left >= piece.box.right - 1 && it.box.left - piece.box.right < piece.size * 1.5f }
                    .minByOrNull { it.box.left }
                if (after != null && (before == null || after.box.left - piece.box.right < before)) after.box.left >= middle
                else piece.box.left >= middle
            }

            // Reading order: bands split at spanning pieces, each band left column then right. By the top
            // of each piece: a drop cap is centred on the lines it spans, but its top is level with the
            // first of them — or near it: a text layer can set a large glyph's top a little low, so a drop
            // cap reads as if a quarter of its height higher.
            fun readingTop(piece: Piece): Float =
                if (piece.size >= typical * 1.45) piece.box.top - piece.box.height * 0.25f else piece.box.top
            val order = pieces.indices.sortedBy { readingTop(pieces[it]) }
            trace?.let { out ->
                out.append("middle=$middle twoColumns=$twoColumns crossing=${crossing.size} open=${open.size} rightOnly=$rightOnly\n")
                for (index in order) {
                    val box = pieces[index].box
                    out.append(
                        String.format(
                            "  [%6.1f %6.1f %6.1f %6.1f] %s%s %s\n", box.left, box.top, box.right, box.bottom,
                            if (isRight[index]) "R" else "L", if (index in spanning) "S" else " ", pieces[index].text,
                        ),
                    )
                }
            }
            val ordered = ArrayList<Piece>()
            val band = ArrayList<Int>()
            fun flush() {
                val lines: (List<Int>) -> List<Piece> = { column ->
                    column.map { pieces[it] }.sortedWith { a, b ->
                        val top = readingTop(a) - readingTop(b)
                        if (abs(top) > 2) top.compareTo(0f) else a.box.left.compareTo(b.box.left)
                    }
                }
                ordered += lines(band.filter { !isRight[it] })
                ordered += lines(band.filter { isRight[it] })
                band.clear()
            }
            for (index in order) {
                if (twoColumns && index in spanning) {
                    flush()
                    ordered.add(pieces[index])
                } else {
                    band.add(index)
                }
            }
            flush()

            val runs = ArrayList<Run>()
            var previous: Piece? = null
            for (piece in ordered) {
                // Further along the same row: a space where there's a visible gap, else nothing.
                val prior = previous
                if (prior != null && abs(prior.box.midY - piece.box.midY) <= 2 && piece.box.left >= prior.box.right - 1 && runs.isNotEmpty()) {
                    val last = runs.last()
                    if (last.text.endsWith("\n")) last.text = last.text.dropLast(1)
                    // Two pieces of one row come from different lines of text, so they never continue one
                    // word: a space between them, unless punctuation closes up to what came before
                    // ("Elijah?" + "”") or opens onto what follows.
                    val before = last.text.replace("\u00AD", "").lastOrNull()
                    val after = piece.text.replace("\u00AD", "").firstOrNull()
                    val closes = after?.let { it in "”’),.;:!?]" } ?: true
                    val opens = before?.let { it in "“‘([ " || it.isWhitespace() } ?: true
                    if (!closes && !opens) last.text += " "
                }
                runs.addAll(pieceRuns(piece))
                runs.last().let { it.text = it.text.trimEnd('\n', '\r') + "\n" }
                previous = piece
            }
            var lineStart = true
            for (run in runs) {
                val endsLine = run.text.endsWith("\n")
                run.ownLine = lineStart && endsLine
                run.endsLine = endsLine
                lineStart = endsLine
            }
            return runs
        }

        /**
         * A piece's runs: one per stretch of glyphs in one font at one size. A space goes wherever the
         * page leaves a gap wider than letters sit apart — the text layer's own spaces aren't trusted,
         * as a page may draw one inside a word, where a syllable was set, or none between words.
         */
        private fun pieceRuns(piece: Piece): List<Run> {
            val runs = ArrayList<Run>()
            val text = StringBuilder()
            var size = -1.0
            var font = ""
            var previous: PdfGlyph? = null
            for (glyph in piece.glyphs) {
                val glyphSize = rounded(glyph.size.toDouble())
                val prior = previous
                if (prior != null) {
                    val gap = glyph.x - prior.right
                    if (gap > maxOf(prior.size, glyph.size) * 0.06f && !text.endsWith(" ")) text.append(' ')
                }
                if (text.isNotEmpty() && (glyphSize != size || glyph.font != font)) {
                    runs.add(Run(text.toString(), size))
                    text.setLength(0)
                }
                size = glyphSize
                font = glyph.font
                text.append(glyph.text)
                previous = glyph
            }
            if (text.isNotEmpty()) runs.add(Run(text.toString(), size))
            return runs
        }

        /**
         * "Lord" set in small capitals — LORD, the way a Bible prints the divine name. A text layer gives
         * small capitals back as lowercase, but their widths give them away: a lowercase r is narrow
         * beside its o (about three quarters of its width), a small-capital R is as wide as the O. The
         * measure is relative to the word itself, so it holds for any typeface and size.
         */
        private fun restoringSmallCapitals(piece: Piece): Piece {
            val glyphs = piece.glyphs
            var changed: MutableList<PdfGlyph>? = null
            for (i in 0..glyphs.size - 4) {
                if (glyphs[i].text != "L" || glyphs[i + 1].text != "o" || glyphs[i + 2].text != "r" || glyphs[i + 3].text != "d") continue
                val before = glyphs.getOrNull(i - 1)?.takeIf { glyphs[i].x - it.right < glyphs[i].size * 0.06f }
                val after = glyphs.getOrNull(i + 4)?.takeIf { it.x - glyphs[i + 3].right < it.size * 0.06f }
                if (before?.text?.firstOrNull()?.isLetter() == true || after?.text?.firstOrNull()?.isLetter() == true) continue
                val o = glyphs[i + 1].width
                if (o <= 0 || glyphs[i + 2].width / o <= 0.88f) continue
                val list = changed ?: glyphs.toMutableList().also { changed = it }
                for ((offset, letter) in listOf("O", "R", "D").withIndex()) {
                    val g = glyphs[i + 1 + offset]
                    list[i + 1 + offset] = PdfGlyph(letter, g.x, g.baseline, g.width, g.height, g.size, g.font)
                }
            }
            return changed?.let { Piece(it) } ?: piece
        }

        /**
         * A drop cap of two digits is sometimes set as two glyphs, one above the other ("1" over "0").
         * Large digit-only pieces in the same place, one just below the other, are one number.
         */
        private fun mergedStackedDigits(pieces: List<Piece>): List<Piece> {
            if (pieces.isEmpty()) return pieces
            val sizes = pieces.map { it.size }.sorted()
            val typical = sizes[sizes.size / 2]
            val result = pieces.toMutableList()
            var index = 0
            while (index < result.size) {
                val piece = result[index]
                val digits = piece.text.trim()
                val big = piece.size
                if (digits.isEmpty() || digits.length > 2 || !digits.all { it.isDigit() } || big <= typical * 1.8f) {
                    index++
                    continue
                }
                val below = result.indices.firstOrNull { other ->
                    other != index && abs(result[other].size - big) < 0.5f && result[other].text.trim().all { it.isDigit() } &&
                        abs(result[other].box.left - piece.box.left) < big * 0.6f &&
                        result[other].box.top >= piece.box.bottom - big * 0.3f && result[other].box.top - piece.box.bottom < big * 0.6f
                }
                if (below != null) {
                    // One glyph list, the lower digits placed after the upper ones, so they read in order.
                    val lower = result[below]
                    val shift = piece.box.right - lower.box.left
                    val moved = lower.glyphs.map { PdfGlyph(it.text, it.x + shift, piece.glyphs.last().baseline, it.width, it.height, it.size, it.font) }
                    result[index] = Piece(piece.glyphs + moved)
                    result.removeAt(below)
                    if (below < index) index--
                    continue
                }
                index++
            }
            return result
        }

        // MARK: - Sizes

        /** The size most characters are set in. */
        fun bodySize(runs: List<Run>): Double {
            val counts = HashMap<Double, Int>()
            for (run in runs) counts.merge(rounded(run.size), run.text.length, Int::plus)
            return counts.maxByOrNull { it.value }?.key ?: 0.0
        }

        /** Body-band sizes used for almost nothing — a heading style that happens to be near the text size. */
        fun rareSizes(runs: List<Run>, body: Double): Set<Double> {
            val counts = HashMap<Double, Int>()
            for (run in runs) counts.merge(rounded(run.size), run.text.length, Int::plus)
            val total = counts.values.sum()
            return counts.filter { it.key / body in 0.97..1.04 && it.value < total * 0.002 }.keys
        }

        fun rounded(size: Double): Double = (size * 10).roundToLong() / 10.0

        // MARK: - Text

        private val keptHyphen = Regex("""(\p{L})-\n(?=\p{L})""")

        /**
         * Words as the page prints them. Soft hyphens are typesetting, not text, except that a line
         * broken at one joins without a space; a hard hyphen at a line's end that [Lexicon] kept is a
         * real one ("three-year-" / "old"). Small capitals a text layer spells "L\u00ADord" become the
         * capitals every digital Bible writes.
         */
        fun bodyText(raw: String): String {
            var text = raw
            for ((small, capital) in listOf(
                "L\u00ADord" to "LORD", "G\u00ADod" to "GOD", "Y\u00ADah" to "YAH", "L\u00ADORD" to "LORD", "G\u00ADOD" to "GOD",
            )) {
                text = text.replace(small, capital)
            }
            text = text.replace("\u00AD\n", "").replace("\u00AD", "")
            text = text.replace(keptHyphen, "$1-")
            return DocumentScanner.collapse(text)
        }

        fun headingText(raw: String): String = SwiftText.trimWhitespace(DocumentScanner.collapse(bodyText(raw)))

        /**
         * Whether a number is plausibly the next verse: a little ahead of the last (translations omit a
         * verse here and there), or a new count starting where none has begun.
         */
        fun followsOn(number: Int, last: Int): Boolean = (number > last && number <= last + 4) || (last == 0 && number <= 2)

        fun number(text: String): Int? {
            val trimmed = SwiftText.trimWhitespaceAndNewlines(text)
            if (trimmed.isEmpty() || trimmed.length > 3 || !trimmed.all { it.isDigit() }) return null
            return trimmed.toIntOrNull()?.takeIf { it > 0 }
        }

        fun isCallerLetters(text: String): Boolean {
            val trimmed = SwiftText.trimWhitespaceAndNewlines(text)
            return (trimmed.isNotEmpty() && trimmed.length <= 2 && trimmed.all { it.isLetter() && it.isLowerCase() }) ||
                (DocumentScanner.isFootnoteLabel(trimmed) && trimmed.length <= 2)
        }

        private val reference = Regex("""^(\p{L}+\.?\s)?\d{1,3}:\d{1,3}""")

        /** "15:4", "3:16–18", "Ps 3:2" at the head of a note. */
        fun isReference(text: String): Boolean = reference.containsMatchIn(SwiftText.trimWhitespaceAndNewlines(text))

        private val headDigits = Regex("""[\d\-–—:,]""")

        /** "GENESIS 2-3 2", "3 GENESIS 3–4": a book name with a chapter span and a page number. */
        fun isRunningHead(text: String): Boolean {
            if (text.none { it.isDigit() }) return false
            // Case is not a test: small capitals can come out of a PDF as "NUMbERS".
            val words = text.replace(headDigits, " ").trim { it.isWhitespace() || it.isISOControl() }
            if (words.isEmpty() || text.length > 48) return false
            return ScriptureLabels.heading(words)?.book != null
        }

        private val slug = Regex("""\.(indb|indd|qxp|pdf)\b""")

        private val slugTime = Regex("""^\d{1,2}/\d{1,2}/\d{2,4}\s+\d{1,2}:\d{2}\s*[AP]M$""")

        /**
         * The imposition slug a printer leaves on every page ("Bible.indb 11 10/26/17 8:59 PM") — its file
         * name, or its time stamp when that comes apart from the name.
         */
        fun isPrinterSlug(text: String): Boolean = slug.containsMatchIn(text) || slugTime.matches(text)

        fun noteID(page: Int, label: String): String = "p$page-${SwiftText.trimWhitespaceAndNewlines(label)}"

        // MARK: - Identity

        /** The first pages' text, where a title page and copyright page are. */
        fun frontMatter(source: PdfTextSource, pages: Int = 8): String =
            (0 until minOf(pages, source.pageCount)).joinToString("\n") { page -> runs(source.glyphs(page)).joinToString("") { it.text } }
    }
}

/**
 * The document's own vocabulary, for deciding what a line break was. Ported from `Lexicon` in
 * `Import/PDFBibleReader.swift`.
 *
 * Typeset text hyphenates words across lines: one line ends "Phari-", the next begins "sees". It also
 * breaks between words: "were" / "created", and at a compound's own hyphen: "three-" / "year-old".
 * Nothing on the page tells these apart, but the rest of the book does — a Bible repeats its words, and
 * "Pharisees" appears whole on some line where it wasn't broken, while "werecreated" never does. That
 * makes the rule language-independent: no dictionary, only the file itself.
 */
internal class Lexicon(runs: List<PDFBibleReader.Run>) {
    private val words = HashMap<String, Int>()
    private val pairs = HashMap<String, Int>()

    /** Compounds written with a hyphen inside a line ("three-year"), so a break at their hyphen keeps it. */
    private val hyphenated = HashMap<String, Int>()

    /** First halves of those compounds ("beth" of "Beth-shemesh"): a name that opens many. */
    private val compoundHeads = HashMap<String, Int>()

    /** Words seen other than straight after an f-ligature ("off er", "suf ering"). */
    private val standalone = HashMap<String, Int>()

    /** Words seen straight after one ending in "f". */
    private val afterF = HashMap<String, Int>()

    init {
        for (run in runs) {
            for (line in run.text.replace("\u00AD", "").split("\n")) {
                val tokens = tokens(line)
                // The first and last word of a line may be halves; only whole ones count.
                if (tokens.size <= 2) continue
                val inner = tokens.subList(1, tokens.size - 1)
                for (word in inner) words.merge(word, 1, Int::plus)
                for (i in 0 until inner.size - 1) pairs.merge(inner[i] + " " + inner[i + 1], 1, Int::plus)
                // A line's first word may be the tail of a broken one, so only words with a word before
                // them on the same line count.
                for (i in 1 until tokens.size) {
                    if (endsInLigature(tokens[i - 1])) afterF.merge(tokens[i], 1, Int::plus) else standalone.merge(tokens[i], 1, Int::plus)
                }
                // Each joint of a chain: "three-year-old" is "three-year" and "year-old".
                for (match in compound.findAll(line)) {
                    val parts = match.value.lowercase().split('-')
                    for (i in 0 until parts.size - 1) {
                        hyphenated.merge(parts[i] + "-" + parts[i + 1], 1, Int::plus)
                        compoundHeads.merge(parts[i], 1, Int::plus)
                    }
                }
            }
        }
    }

    /**
     * Extraction breaks words at f-ligatures: "offered" comes out "off ered". The piece after the break
     * is joined back when it never stands as a word anywhere else in the book.
     */
    fun repairingLigatures(text: String): String = text.replace(ligatureBreak) { match ->
        val tail = match.groupValues[2]
        if (isFragment(tail)) match.groupValues[1] + tail else match.value
    }

    /** A piece of a word broken after an "f": it follows an "f" far more often than it stands alone. */
    fun isFragment(tail: String): Boolean {
        val key = tail.lowercase()
        return (standalone[key] ?: 0) * 5 < (afterF[key] ?: 0)
    }

    /** Whether [first] at a line's end and [second] at the next line's start are one word. */
    fun joins(first: String, second: String): Boolean {
        val a = first.lowercase()
        val b = second.lowercase()
        val joined = words[a + b] ?: 0
        if (joined <= 0) return false
        // Both halves are words too ("some" / "one"): the more common reading wins.
        return joined >= (pairs["$a $b"] ?: 0)
    }

    /**
     * Whether a hyphen that ends a line between [first] and [second] is the typesetter's (the word is
     * one: "Phari-" / "sees") rather than the word's own ("three-" / "year"). A break the book never
     * shows either way is the typesetter's — unless its first half opens compounds elsewhere and its
     * second is a word of its own, as in "Beth-" / "haran".
     */
    fun hyphenIsTypesetting(first: String, second: String): Boolean {
        val a = first.lowercase()
        val b = second.lowercase()
        val joined = words[a + b] ?: 0
        val kept = hyphenated["$a-$b"] ?: 0
        if (joined > 0 || kept > 0) return joined >= kept
        return (compoundHeads[a] ?: 0) == 0 || (words[b] ?: 0) == 0
    }

    /** Replaces each line break with nothing (inside a word) or a space (between words). */
    fun resolveLineBreaks(runs: List<PDFBibleReader.Run>): List<PDFBibleReader.Run> {
        val result = runs.map { PDFBibleReader.Run(it.text, it.size, it.ownLine, it.endsLine) }
        // A ligature is often a run of its own, so its break can fall between runs too.
        for (index in 0 until result.size - 1) {
            val text = result[index].text.replace("\u00AD", "")
            if (!text.endsWith(" ") || !endsInLigature(text.dropLast(1).lowercase())) continue
            val tail = result[index + 1].text.replace("\u00AD", "").takeWhile { it.isLetter() }
            val first = tail.firstOrNull() ?: continue
            if (!first.isLowerCase() || !isFragment(tail)) continue
            result[index].text = result[index].text.trimEnd()
        }
        for ((index, run) in result.withIndex()) {
            val text = run.text
            if (!text.contains('\n')) {
                run.text = repairingLigatures(text)
                continue
            }
            val following = result.subList(index + 1, result.size).firstOrNull { it.text.isNotEmpty() }?.text ?: ""
            val joined = StringBuilder()
            val parts = text.split("\n")
            for ((position, part) in parts.withIndex()) {
                joined.append(part)
                if (position >= parts.size - 1) break
                val next = if (position + 1 < parts.size - 1 || parts[position + 1].isNotEmpty()) parts[position + 1] else following
                when (val separator = separator(part, next)) {
                    DROP_HYPHEN -> joined.setLength(joined.length - 1)
                    else -> joined.append(separator)
                }
            }
            run.text = repairingLigatures(joined.toString())
        }
        return result
    }

    private fun separator(line: String, next: String): String {
        val clean = line.replace("\u00AD", "")
        val head = next.replace("\u00AD", "")
        if (line.endsWith("\u00AD")) return ""
        // A hyphen at the end of a line between letters: the typesetter's, or the word's own.
        if (clean.endsWith("-") && head.firstOrNull()?.isLetter() == true) {
            val before = clean.dropLast(1)
            if (before.lastOrNull()?.isLetter() != true) return ""
            val tail = before.takeLastWhile { it.isLetter() }
            val lead = head.takeWhile { it.isLetter() }
            return if (line.endsWith("-") && hyphenIsTypesetting(tail, lead)) DROP_HYPHEN else ""
        }
        val last = clean.lastOrNull()
        if (last == null || !last.isLetter() || head.firstOrNull()?.isLetter() != true || next.startsWith("\u00AD")) return " "
        val tail = clean.takeLastWhile { it.isLetter() }
        val lead = head.takeWhile { it.isLetter() }
        return if (joins(tail, lead)) "" else " "
    }

    companion object {
        /** Joins the halves and takes the hyphen out. */
        private const val DROP_HYPHEN = "\u0000drop"

        private val compound = Regex("""\p{L}+(?:-\p{L}+)+""")
        private val ligatureBreak = Regex("""(\p{L}*f) (\p{Ll}+)""")

        /** Extraction opens a gap after an "f" wherever the font joins it to the next letter — a ligature or a kerned pair. */
        fun endsInLigature(word: String): Boolean = word.endsWith("f")

        private val nonLetters = Regex("""[^\p{L}]+""")

        fun tokens(text: String): List<String> = text.lowercase().split(nonLetters).filter { it.isNotEmpty() }
    }
}
