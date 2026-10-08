package com.blainemiller.scripturealone.ui.reader

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.blainemiller.scripturealone.data.userdata.HighlightColor

/**
 * What the reader draws over the chapter's text: highlight colours by verse key, and the selection.
 * Kept out of the rendered text so marking a verse repaints without re-typesetting.
 */
data class VerseMarks(
    val highlights: Map<Int, String> = emptyMap(),
    val selection: Set<Int> = emptySet(),
    /** The verse Listen is reading, if it is in view. */
    val speaking: Int? = null,
) {
    val isEmpty: Boolean get() = highlights.isEmpty() && selection.isEmpty() && speaking == null
}

/**
 * The runs to fill, as `ChapterRenderer.swift` colours them: each highlighted verse, number included,
 * and the space between two verses of the same colour, so a highlighted passage reads as one band.
 */
internal fun highlightRuns(spans: List<VerseSpan>, highlights: Map<Int, String>): List<Triple<Int, Int, HighlightColor>> {
    val runs = mutableListOf<Triple<Int, Int, HighlightColor>>()
    for (span in spans) {
        val color = HighlightColor.fromRaw(highlights[span.key]) ?: continue
        val last = runs.lastOrNull()
        if (last != null && last.third == color && span.start - last.second <= 1) {
            runs[runs.size - 1] = Triple(last.first, span.end, color)
        } else {
            runs += Triple(span.start, span.end, color)
        }
    }
    return runs
}

/**
 * Draws highlights and the selection behind a paragraph's text. Highlights are fills of the full line
 * height (TextKit's `backgroundColor`), as one band with rounded outer corners (`HighlightShape`);
 * a selected verse gets the thick dotted accent underline iOS
 * gives it (`.thick | .patternDot`).
 *
 * The verse being read aloud is marked as `ChapterRenderer.swift` marks it: an accent tint (13%, or 20%
 * on a dark page) unless a highlight already fills it, and a thin solid accent rule beneath unless it
 * is selected — selection is dotted, highlights are fills, listening is a line.
 */
fun Modifier.verseMarks(
    layout: () -> TextLayoutResult?,
    spans: List<VerseSpan>,
    marks: VerseMarks,
    palette: ReaderPalette,
): Modifier {
    if (spans.isEmpty() || marks.isEmpty) return this
    return drawBehind {
        val text = layout() ?: return@drawBehind
        val length = text.layoutInput.text.length
        for ((start, end, color) in highlightRuns(spans, marks.highlights)) {
            if (end > length) continue
            drawPath(roundedBand(text, start, end), highlightFill(color, palette.isDark))
        }
        val speaking = marks.speaking?.let { key -> spans.firstOrNull { it.key == key } }
        if (speaking != null && speaking.end <= length && speaking.end > speaking.start) {
            if (HighlightColor.fromRaw(marks.highlights[speaking.key]) == null) {
                drawPath(roundedBand(text, speaking.start, speaking.end), palette.accent.copy(alpha = if (palette.isDark) 0.2f else 0.13f))
            }
            if (speaking.key !in marks.selection) {
                val rule = 1.dp.toPx()
                forEachLine(text, speaking) { x1, x2, baseline ->
                    drawLine(palette.accent.copy(alpha = 0.7f), Offset(x1, baseline + rule * 2.5f), Offset(x2, baseline + rule * 2.5f), strokeWidth = rule)
                }
            }
        }
        val thickness = 2.dp.toPx()
        val dash = PathEffect.dashPathEffect(floatArrayOf(thickness, thickness * 1.2f))
        for (span in spans) {
            if (span.key !in marks.selection || span.end > length || span.end <= span.start) continue
            val firstLine = text.getLineForOffset(span.start)
            val lastLine = text.getLineForOffset(span.end - 1)
            for (line in firstLine..lastLine) {
                val from = maxOf(span.start, text.getLineStart(line))
                val to = minOf(span.end, text.getLineEnd(line, visibleEnd = true))
                if (to <= from) continue
                val x1 = text.getHorizontalPosition(from, usePrimaryDirection = true)
                val x2 = if (to == text.getLineEnd(line, visibleEnd = true) && to < span.end) {
                    text.getLineRight(line)
                } else {
                    text.getHorizontalPosition(to, usePrimaryDirection = true)
                }
                val y = text.getLineBaseline(line) + thickness * 1.8f
                drawLine(
                    palette.accent, Offset(minOf(x1, x2), y), Offset(maxOf(x1, x2), y),
                    strokeWidth = thickness, pathEffect = dash, cap = StrokeCap.Butt,
                )
            }
        }
    }
}

/**
 * The shape of a highlight, as `HighlightShape` in `ChapterTextView.swift` draws it: one rect per line
 * (rects on one line that touch become one), a line reaching down to the next across a gap of a few
 * points, and only the outer corners rounded — a corner the line above or below continues past stays
 * square, so a highlight wrapping over several lines reads as one shape.
 */
internal object HighlightShape {
    data class Corners(val topLeft: Boolean, val topRight: Boolean, val bottomRight: Boolean, val bottomLeft: Boolean) {
        companion object { val ALL = Corners(true, true, true, true) }
    }

    data class Band(val rect: Rect, val corners: Corners, val radius: Float)

    /** About a fifth of the line's height, between 4 and 6 points ([density] pixels per point). */
    fun radius(lineHeight: Float, density: Float): Float = minOf(6f * density, maxOf(4f * density, lineHeight * 0.2f))

    fun bands(rects: List<Rect>, density: Float = 1f): List<Band> {
        val lines = merged(rects.filter { it.width > 0.5f && it.height > 0.5f })
        return lines.map { rect ->
            val radius = minOf(radius(rect.height, density), rect.width / 2, rect.height / 2)
            val above = lines.filter { kotlin.math.abs(it.bottom - rect.top) < 1f }
            val below = lines.filter { kotlin.math.abs(it.top - rect.bottom) < 1f }
            fun covered(x: Float, neighbours: List<Rect>, leading: Boolean) = neighbours.any {
                if (leading) it.left <= x + 0.5f && it.right >= x + radius else it.left <= x - radius && it.right >= x - 0.5f
            }
            Band(
                rect,
                Corners(
                    topLeft = !covered(rect.left, above, true),
                    topRight = !covered(rect.right, above, false),
                    bottomRight = !covered(rect.right, below, false),
                    bottomLeft = !covered(rect.left, below, true),
                ),
                radius,
            )
        }
    }

    private fun merged(rects: List<Rect>): List<Rect> {
        val result = mutableListOf<Rect>()
        for (rect in rects.sortedWith(compareBy({ it.center.y }, { it.left }))) {
            val index = result.indexOfLast { other ->
                val overlap = minOf(other.bottom, rect.bottom) - maxOf(other.top, rect.top)
                overlap > minOf(other.height, rect.height) / 2 && rect.left <= other.right + 1 && rect.right >= other.left - 1
            }
            if (index >= 0) result[index] = Rect(
                minOf(result[index].left, rect.left), minOf(result[index].top, rect.top),
                maxOf(result[index].right, rect.right), maxOf(result[index].bottom, rect.bottom),
            ) else result += rect
        }
        for (i in 0 until result.size - 1) {
            val line = result[i]
            val next = result[i + 1]
            val gap = next.top - line.bottom
            val overlaps = minOf(line.right, next.right) > maxOf(line.left, next.left)
            if (gap > 0 && gap <= minOf(8f, minOf(line.height, next.height) * 0.4f) && overlaps) {
                result[i] = line.copy(bottom = next.top)
            }
        }
        return result
    }
}

/** The run [start]..[end] of [text] as a rounded band (`HighlightShape`), one rect per line it covers. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.roundedBand(text: TextLayoutResult, start: Int, end: Int): Path {
    val rects = mutableListOf<Rect>()
    forEachLineIndex(text, VerseSpan(0, start, end)) { x1, x2, line ->
        // A run that carries on to the next line fills to the paragraph's edge, as TextKit's does
        // (and as `getPathForRange` did).
        val right = if (line < text.getLineForOffset(end - 1)) maxOf(x2, text.size.width.toFloat()) else x2
        rects += Rect(x1, text.getLineTop(line), right, text.getLineBottom(line))
    }
    val path = Path()
    for (band in HighlightShape.bands(rects, density)) {
        fun r(on: Boolean) = if (on) CornerRadius(band.radius) else CornerRadius.Zero
        val c = band.corners
        path.addRoundRect(RoundRect(band.rect, r(c.topLeft), r(c.topRight), r(c.bottomRight), r(c.bottomLeft)))
    }
    return path
}

/** Each line [span] covers, as its left and right x and its line. */
private inline fun forEachLineIndex(text: TextLayoutResult, span: VerseSpan, draw: (Float, Float, Int) -> Unit) {
    val firstLine = text.getLineForOffset(span.start)
    val lastLine = text.getLineForOffset(span.end - 1)
    for (line in firstLine..lastLine) {
        val from = maxOf(span.start, text.getLineStart(line))
        val to = minOf(span.end, text.getLineEnd(line, visibleEnd = true))
        if (to <= from) continue
        val x1 = text.getHorizontalPosition(from, usePrimaryDirection = true)
        val x2 = if (to == text.getLineEnd(line, visibleEnd = true) && to < span.end) {
            text.getLineRight(line)
        } else {
            text.getHorizontalPosition(to, usePrimaryDirection = true)
        }
        draw(minOf(x1, x2), maxOf(x1, x2), line)
    }
}

/** Each line [span] covers, as its left and right x and its baseline. */
private inline fun forEachLine(text: TextLayoutResult, span: VerseSpan, draw: (Float, Float, Float) -> Unit) =
    forEachLineIndex(text, span) { x1, x2, line -> draw(x1, x2, text.getLineBaseline(line)) }

/**
 * The verse under a tap at [position], in text coordinates — `ChapterGeometry.characterIndex`, which
 * ignores taps in the blank margin beside a short line (8 dp of slack either side).
 */
internal fun verseAt(text: TextLayoutResult, spans: List<VerseSpan>, position: Offset, slack: Float): Int? {
    if (spans.isEmpty()) return null
    val line = text.getLineForVerticalPosition(position.y)
    if (position.y < text.getLineTop(line) - slack || position.y > text.getLineBottom(line) + slack) return null
    if (position.x < text.getLineLeft(line) - slack || position.x > text.getLineRight(line) + slack) return null
    val offset = text.getOffsetForPosition(position)
    return spans.firstOrNull { offset >= it.start && offset < it.end }?.key
        // Between two verses (the joining space) or just past a line's end: the nearest before it.
        ?: spans.lastOrNull { it.start <= offset }?.key
}
