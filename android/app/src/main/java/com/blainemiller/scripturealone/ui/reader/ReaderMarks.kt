package com.blainemiller.scripturealone.ui.reader

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.blainemiller.scripturealone.data.userdata.HighlightColor

/**
 * What the reader draws over the chapter's text: highlight colours by verse key, and the selection.
 * Kept out of the rendered text so marking a verse repaints without re-typesetting.
 */
data class VerseMarks(val highlights: Map<Int, String> = emptyMap(), val selection: Set<Int> = emptySet()) {
    val isEmpty: Boolean get() = highlights.isEmpty() && selection.isEmpty()
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
 * height (TextKit's `backgroundColor`); a selected verse gets the thick dotted accent underline iOS
 * gives it (`.thick | .patternDot`).
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
            drawPath(text.getPathForRange(start, end), highlightFill(color, palette.isDark))
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
