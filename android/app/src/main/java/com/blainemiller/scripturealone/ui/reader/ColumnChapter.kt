package com.blainemiller.scripturealone.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.offset
import com.blainemiller.scripturealone.data.userdata.Note
import com.blainemiller.scripturealone.ui.system.SystemBars
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * How many columns of text fit across a window, and where they stand in it — `ReaderColumns` in
 * `ColumnChapterView.swift`, in dp for points.
 */
object ReaderColumns {
    /** Space at each side of the spread, and between its columns. */
    const val MARGIN = 40f
    const val GUTTER = 40f

    /**
     * Below this height the window is compact-height — a phone held sideways — as iOS's vertical size
     * class is compact there (Material's compact window height class is under 480 dp too).
     */
    const val COMPACT_HEIGHT = 480f

    /**
     * Columns across a window [width] × [height] dp at [fontSize] (the reader's size, in dp): about
     * eighteen ems a column, the measure of a printed Bible's column, and two at most — three read as a
     * newspaper, not a Bible. One means "read the chapter as a single scrolling column". A window under
     * 360 dp tall has no room for a page.
     */
    fun count(width: Float, height: Float, fontSize: Float): Int {
        if (height < 360f) return 1
        val column = fontSize * 18f
        val fitted = floor((width - MARGIN * 2 + GUTTER) / (column + GUTTER)).toInt()
        return fitted.coerceIn(1, 2)
    }

    fun isCompactHeight(height: Float): Boolean = height < COMPACT_HEIGHT

    /**
     * The width [count] weighs, for a window [width] wide with a side navigation bar or display cutout
     * [insetLeft] / [insetRight] deep at its edges. The spread's margins are measured from the window's
     * edge and only grow where an inset is deeper than they are — a phone's punch-hole camera, held
     * sideways, takes a strip a little wider than the margin, not a margin of its own.
     */
    fun usableWidth(width: Float, insetLeft: Float, insetRight: Float): Float =
        width - maxOf(0f, insetLeft - MARGIN) - maxOf(0f, insetRight - MARGIN)

    /**
     * Where a column's text starts below the top of the window, and how much of the window's foot it
     * leaves, given the persistent chrome over the page: [safeTop] down to the foot of the top bar and
     * [safeBottom] up to the top of the bottom bar.
     *
     * The columns fill the whole page between those bars. The selection bar, the highlighter, Listen's
     * controls and Now Playing are dismissible overlays: they float over the last lines while they are
     * up, and no room is ever kept for them. A short page (a phone held sideways, [compactHeight]) starts
     * its text 8 dp under the top bar, a taller one 20 dp; both end 6 dp above the bottom bar.
     */
    fun verticalInsets(safeTop: Float, safeBottom: Float, compactHeight: Boolean): Pair<Float, Float> =
        (safeTop + if (compactHeight) 8f else 20f) to (safeBottom + 6f)

    /** The height of each column in a window [height] tall. */
    fun columnHeight(height: Float, safeTop: Float, safeBottom: Float, compactHeight: Boolean): Float {
        val (top, bottom) = verticalInsets(safeTop, safeBottom, compactHeight)
        return maxOf(120f, height - top - bottom)
    }

    /** Each column's width in a spread [width] wide. */
    fun columnWidth(width: Float, columns: Int): Float = (width - MARGIN * 2 - GUTTER * (columns - 1)) / columns
}

/**
 * A paragraph as pagination sees it: its space before and after, and each of its lines' top and bottom
 * within its text (pixels). [keepWithNext]: a heading, which never stands alone at a column's foot.
 */
data class ParagraphLines(
    val spaceBefore: Float,
    val spaceAfter: Float,
    val lineTops: List<Float>,
    val lineBottoms: List<Float>,
    val keepWithNext: Boolean = false,
) {
    val lineCount: Int get() = lineTops.size
}

/**
 * Lines [fromLine] until [toLine] of paragraph [paragraph], standing [y] pixels below the column's
 * top. [top] and [bottom] are where those lines lie in the paragraph as [Paragraph] lays it out (its
 * space before included), so the slice is drawn by shifting the paragraph up by [top] and clipping it
 * to [height].
 */
data class ColumnSlice(val paragraph: Int, val fromLine: Int, val toLine: Int, val y: Float, val top: Float, val bottom: Float) {
    val height: Float get() = bottom - top
}

/**
 * Pours a chapter's paragraphs into columns of one height, line by line — what TextKit does on iOS
 * when one layout manager flows into a column-sized text container per column. A line goes whole into
 * the column it fits or on to the next, so no line is ever cut at a column's foot. A paragraph's space
 * before is dropped at a column's head (nothing above to keep apart from), and a heading that would
 * end a column with nothing of its section under it moves on to the next column with it. Pure.
 */
object ColumnPagination {
    /** Half a pixel: what rounding may add to a column without a line passing its foot. */
    private const val SLACK = 0.5f

    fun paginate(paragraphs: List<ParagraphLines>, height: Float): List<List<ColumnSlice>> {
        val columns = mutableListOf(mutableListOf<ColumnSlice>())
        var y = 0f
        fun newColumn() {
            columns += mutableListOf<ColumnSlice>()
            y = 0f
        }
        for ((index, p) in paragraphs.withIndex()) {
            var line = 0
            while (line < p.lineCount) {
                val atHead = columns.last().isEmpty()
                val before = if (line == 0 && !atHead) p.spaceBefore else 0f
                val base = p.lineTops[line]
                var end = line
                while (end < p.lineCount && y + before + (p.lineBottoms[end] - base) <= height + SLACK) end++
                // A heading that fits, but with no line of what follows it after it: on to the next column.
                if (end == p.lineCount && line == 0 && p.keepWithNext && !atHead) {
                    val next = paragraphs.getOrNull(index + 1)
                    if (next != null && next.lineCount > 0) {
                        val after = y + before + (p.lineBottoms.last() - base) + p.spaceAfter + next.spaceBefore +
                            (next.lineBottoms[0] - next.lineTops[0])
                        if (after > height + SLACK) end = line
                    }
                }
                if (end == line) {
                    if (atHead) {
                        // A line taller than a whole column (an enormous text size): it has a column of its own.
                        end = line + 1
                    } else {
                        newColumn()
                        continue
                    }
                }
                val top = p.spaceBefore + p.lineTops[line]
                val bottom = p.spaceBefore + p.lineBottoms[end - 1]
                columns.last() += ColumnSlice(index, line, end, y + before, top, bottom)
                y += before + (bottom - top)
                line = end
                if (line < p.lineCount) newColumn() else y += p.spaceAfter
            }
        }
        if (columns.size > 1 && columns.last().isEmpty()) columns.removeAt(columns.size - 1)
        return columns
    }

    /** The column that shows line [line] of paragraph [paragraph], or null. */
    fun column(paragraph: Int, line: Int, columns: List<List<ColumnSlice>>): Int? =
        columns.indexOfFirst { slices -> slices.any { it.paragraph == paragraph && line >= it.fromLine && line < it.toLine } }
            .takeIf { it >= 0 }
}

/**
 * The spread the column reader shows, for the bottom bar's arrows: they turn spreads while there is
 * one to turn to, and change the chapter past either end.
 */
@Stable
class Spreads {
    var page by mutableIntStateOf(0)
        internal set
    var count by mutableIntStateOf(1)
        internal set
    /** Whether a column reader is showing at all. */
    var active by mutableStateOf(false)
        internal set
    internal var turner: ((Boolean) -> Unit)? = null

    val hasPrevious: Boolean get() = active && page > 0
    val hasNext: Boolean get() = active && page < count - 1

    /** The next or previous spread — or, past either end, the chapter. */
    fun turn(forward: Boolean) {
        turner?.invoke(forward)
    }
}

/** The text a paragraph's note markers take room for — what `BasicText` makes of its inline content. */
private const val INLINE_CONTENT_TAG = "androidx.compose.foundation.text.inlineContent"

/** A paragraph's text laid out at [width] pixels, exactly as [Paragraph] lays it out. */
internal fun measureParagraph(
    measurer: TextMeasurer,
    p: RenderedParagraph,
    palette: ReaderPalette,
    markerSize: Float,
    width: Int,
    density: Density,
    layoutDirection: LayoutDirection,
): TextLayoutResult {
    val endIndent = with(density) { p.endIndent.sp.toDp().roundToPx() }
    val placeholders = p.text.getStringAnnotations(INLINE_CONTENT_TAG, 0, p.text.length)
        .filter { it.item == ChapterRenderer.NOTE_MARKER }
        .map { AnnotatedString.Range<Placeholder>(noteMarkerPlaceholder(markerSize), it.start, it.end) }
    return measurer.measure(
        p.text,
        style = paragraphTextStyle(p, palette),
        placeholders = placeholders,
        constraints = Constraints(maxWidth = (width - endIndent).coerceAtLeast(1)),
        layoutDirection = layoutDirection,
        density = density,
        skipCache = true,
    )
}

/** [layout]'s lines, and [p]'s spacing as [Paragraph] pads it, for [ColumnPagination]. */
internal fun paragraphLines(p: RenderedParagraph, layout: TextLayoutResult, density: Density): ParagraphLines = with(density) {
    ParagraphLines(
        spaceBefore = p.spaceBefore.sp.toDp().roundToPx().toFloat(),
        spaceAfter = p.spaceAfter.sp.toDp().roundToPx().toFloat(),
        lineTops = List(layout.lineCount) { layout.getLineTop(it) },
        lineBottoms = List(layout.lineCount) { layout.getLineBottom(it) },
        keepWithNext = p.role == ParagraphRole.HEADING,
    )
}

/**
 * The first verse beginning at or after the head of [column] — the verse at the top of a spread, for
 * the reading position (`report()` in `ColumnChapterView.swift`). Past the last verse, the last one.
 */
internal fun verseAtHead(rendered: RenderedChapter, column: List<ColumnSlice>?, layouts: List<TextLayoutResult>): Int? {
    val head = column?.firstOrNull() ?: return null
    val paragraphs = rendered.paragraphs
    val start = layouts.getOrNull(head.paragraph)?.getLineStart(head.fromLine) ?: 0
    paragraphs[head.paragraph].verses.firstOrNull { it.offset >= start }?.let { return it.key }
    for (i in head.paragraph + 1 until paragraphs.size) paragraphs[i].verses.firstOrNull()?.let { return it.key }
    return paragraphs.lastOrNull { it.verses.isNotEmpty() }?.verses?.last()?.key
}

/** The top bar's foot below the status bar, and the bottom bar's pills' top above the navigation bar. */
internal val COLUMN_TOP_CHROME = 64.dp
internal val COLUMN_BOTTOM_CHROME = 62.dp

/**
 * The chapter laid out in columns side by side, turned a spread at a time — a printed Bible's page on a
 * screen wide enough for one (a tablet, an unfolded foldable, a wide Chromebook window, a phone held
 * sideways). `ColumnChapterView` on iOS.
 *
 * It draws exactly what the scrolling reader draws — the same [Paragraph]s, so highlights, the
 * selection, Listen's tint, note markers, footnotes and TalkBack's verse nodes all come along — poured
 * into columns by [ColumnPagination]: a paragraph that runs over a column's foot is shown in both
 * columns, each clipped to its own whole lines. It answers what [ChapterColumn] answers: jumping to a
 * verse, following the verse read aloud, reporting the verse at the top. A swipe, or the bottom bar's
 * arrows ([spreads]), turns the spread; past the last spread it moves to the next chapter, and before
 * the first to the previous one.
 */
@Composable
internal fun ColumnChapter(
    rendered: RenderedChapter,
    columns: Int,
    compactHeight: Boolean,
    spreads: Spreads,
    marks: VerseMarks,
    markerSize: Float,
    onVerseTap: (Int) -> Unit,
    onVerseLongPress: (Int) -> Unit,
    notesFor: (List<String>) -> List<Note>,
    onOpenNote: (UUID) -> Unit,
    palette: ReaderPalette,
    scrollTarget: Int?,
    onScrolledToTarget: () -> Unit,
    onTopVerse: (Int) -> Unit,
    onSwipe: (forward: Boolean) -> Unit,
    onAction: (ReaderAction) -> Unit,
    revealVerse: Int? = null,
    /** Room for the keepsake banner beneath the top bar. */
    extraTop: Dp = 0.dp,
    selectable: Boolean = true,
) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val safeTop = SystemBars.topSafe.getTop(density)
    val safeBottom = SystemBars.bottomSafe.getBottom(density)
    val safeLeft = SystemBars.sideSafe.getLeft(density, direction)
    val safeRight = SystemBars.sideSafe.getRight(density, direction)
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val scope = rememberCoroutineScope()

    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().testTag("reader.columns")) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val px = density.density
        val margin = ReaderColumns.MARGIN * px
        val gutter = ReaderColumns.GUTTER * px
        // Margins from the window's edge, deeper only where a side bar or cutout is (`usableWidth`).
        val leftEdge = maxOf(margin, safeLeft.toFloat())
        val rightEdge = maxOf(margin, safeRight.toFloat())
        val columnWidth = ((width - leftEdge - rightEdge - gutter * (columns - 1)) / columns).toInt().coerceAtLeast(1)
        val (topInset, bottomInset) = ReaderColumns.verticalInsets(
            safeTop = safeTop / px + COLUMN_TOP_CHROME.value + extraTop.value,
            safeBottom = safeBottom / px + COLUMN_BOTTOM_CHROME.value,
            compactHeight = compactHeight,
        )
        val columnHeight = maxOf(120f * px, height - (topInset + bottomInset) * px)
        val top = topInset * px

        val layouts = remember(rendered, columnWidth, density, palette, markerSize) {
            rendered.paragraphs.map { measureParagraph(measurer, it, palette, markerSize, columnWidth, density, direction) }
        }
        val poured = remember(layouts, columnHeight) {
            ColumnPagination.paginate(rendered.paragraphs.mapIndexed { i, p -> paragraphLines(p, layouts[i], density) }, columnHeight)
        }
        val pageCount = maxOf(1, (poured.size + columns - 1) / columns)

        // The verse at the top of the spread, kept across a re-pour (a new text size, a turned phone) and
        // a recreated activity, so the reader stays on the same words.
        var anchor by rememberSaveable { mutableStateOf<Int?>(null) }
        var page by rememberSaveable { mutableIntStateOf(0) }
        /** A page more than one away that a turn is animating to (a jump to a verse read aloud). */
        var jump by remember { mutableStateOf<Int?>(null) }
        val offset = remember { Animatable(0f) }
        val target by rememberUpdatedState(scrollTarget)
        val report by rememberUpdatedState(onTopVerse)
        val swipe by rememberUpdatedState(onSwipe)

        // What the turning code reads, always this composition's: the functions below are handed out
        // (to the bottom bar, to the drag handler) and may be called long after they were made.
        val layoutsNow by rememberUpdatedState(layouts)
        val pouredNow by rememberUpdatedState(poured)
        val pageCountNow by rememberUpdatedState(pageCount)
        val columnsNow by rememberUpdatedState(columns)
        val widthNow by rememberUpdatedState(width)

        fun pageOf(key: Int): Int? {
            val (index, at) = rendered.locate(key) ?: return null
            val line = layoutsNow.getOrNull(index)?.getLineForOffset(at) ?: return null
            return ColumnPagination.column(index, line, pouredNow)?.let { it / columnsNow }
        }

        fun settle(newPage: Int) {
            page = newPage.coerceIn(0, pageCountNow - 1)
            if (target == null) {
                verseAtHead(rendered, pouredNow.getOrNull(page * columnsNow), layoutsNow)?.let {
                    anchor = it
                    report(it)
                }
            }
        }

        fun turnTo(newPage: Int) {
            val to = newPage.coerceIn(0, pageCountNow - 1)
            if (to == page) return
            scope.launch {
                if (abs(to - page) > 1) jump = to
                offset.animateTo(if (to > page) -widthNow.toFloat() else widthNow.toFloat(), tween(260))
                jump = null
                settle(to)
                offset.snapTo(0f)
            }
        }

        fun turn(forward: Boolean) {
            if (offset.isRunning) return
            when {
                forward && page < pageCountNow - 1 -> turnTo(page + 1)
                !forward && page > 0 -> turnTo(page - 1)
                else -> swipe(forward)
            }
        }

        // The bottom bar's arrows turn these spreads.
        val turnNow by rememberUpdatedState<(Boolean) -> Unit>(::turn)
        SideEffect {
            spreads.page = page
            spreads.count = pageCount
            spreads.turner = turnNow
        }
        DisposableEffect(spreads) {
            spreads.active = true
            onDispose {
                spreads.active = false
                spreads.turner = null
            }
        }

        // Laid out again: back to the spread with the same verse at its head.
        LaunchedEffect(poured) {
            if (target != null) return@LaunchedEffect
            settle(anchor?.let(::pageOf) ?: page)
        }

        // A verse to bring into view (a Go To, a restored position, a translation switch).
        LaunchedEffect(poured, scrollTarget) {
            val key = scrollTarget ?: return@LaunchedEffect
            page = (pageOf(key) ?: 0).coerceIn(0, pageCount - 1)
            onScrolledToTarget()
            anchor = key
            verseAtHead(rendered, poured.getOrNull(page * columns), layouts)?.let(report)
        }

        // Listen turns the page to follow the verse being read, as `revealIfNeeded` does.
        LaunchedEffect(poured, revealVerse) {
            val key = revealVerse ?: return@LaunchedEffect
            if (target != null) return@LaunchedEffect
            val to = pageOf(key) ?: return@LaunchedEffect
            if (to != page && !offset.isRunning) turnTo(to)
        }

        Box(
            Modifier.fillMaxSize().pointerInput(pageCount, width) {
                // Pages follow the finger; let go past a third of a thumb's travel and the spread turns.
                // Before the first spread or past the last the page gives only a little, and the chapter turns.
                val threshold = 64.dp.toPx()
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val travel = offset.value
                        when {
                            travel < -threshold -> turnNow(true)
                            travel > threshold -> turnNow(false)
                        }
                        scope.launch { if (!offset.isRunning) offset.animateTo(0f, tween(200)) }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f, tween(200)) } },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        val edge = (offset.value > 0 && page == 0) || (offset.value < 0 && page == pageCountNow - 1)
                        scope.launch { offset.snapTo(offset.value + if (edge) amount * 0.35f else amount) }
                    },
                )
            },
        ) {
            for (slot in -1..1) {
                val shown = when (slot) {
                    -1 -> jump?.takeIf { it < page } ?: (page - 1)
                    1 -> jump?.takeIf { it > page } ?: (page + 1)
                    else -> page
                }
                if (shown !in 0 until pageCount) continue
                // Only the spread in view, and its neighbour while a turn is under way.
                if (slot != 0 && offset.value == 0f) continue
                val pageX = slot * width
                for (c in 0 until columns) {
                    val column = poured.getOrNull(shown * columns + c) ?: continue
                    val x = leftEdge + c * (columnWidth + gutter)
                    for (slice in column) {
                        val p = rendered.paragraphs[slice.paragraph]
                        key(shown, c, slice.paragraph, slice.fromLine) {
                            SliceBox(
                                slice, columnWidth,
                                Modifier.offset { IntOffset((pageX + x + offset.value).roundToInt(), (top + slice.y).roundToInt()) },
                            ) {
                                Paragraph(
                                    p, palette, onAction, onLayout = {},
                                    marks = marks, markerSize = markerSize, onVerseTap = onVerseTap, onVerseLongPress = onVerseLongPress,
                                    notesFor = notesFor, onOpenNote = onOpenNote, selectable = selectable,
                                    visibleLines = slice.fromLine until slice.toLine,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One slice of a paragraph: the paragraph laid out at full height, shifted up to the slice's first
 * line and clipped to its last, so only whole lines show — and taps land only on those.
 */
@Composable
private fun SliceBox(slice: ColumnSlice, width: Int, modifier: Modifier, content: @Composable () -> Unit) {
    val top = floor(slice.top).toInt()
    val height = ceil(slice.bottom).toInt() - top
    Layout(content, modifier.clipToBounds()) { measurables, _ ->
        val placeables = measurables.map { it.measure(Constraints(minWidth = width, maxWidth = width)) }
        layout(width, height) { placeables.forEach { it.place(0, -top) } }
    }
}

/**
 * Which reader shows the chapter: [columns] side by side, paged ([paged]), or the one scrolling column.
 * [compactHeight]: a phone held sideways, whose columns run from bar to bar under a one-line header.
 */
data class ReaderLayout(val columns: Int, val compactHeight: Boolean, val paged: Boolean) {
    companion object {
        /**
         * For a reader [width] × [height] dp (inside any side navigation bar or cutout) at [fontSize] dp:
         * columns when the Appearance switch is on ([enabled]), two fit, the page isn't scrolling itself
         * ([autoScrolling]) and the phone's Study sheet isn't over the lower part of it ([covered]) —
         * `ChapterPane` on iOS.
         */
        fun decide(width: Float, height: Float, fontSize: Float, enabled: Boolean, autoScrolling: Boolean, covered: Boolean): ReaderLayout {
            val columns = ReaderColumns.count(width, height, fontSize)
            return ReaderLayout(
                columns = columns,
                compactHeight = ReaderColumns.isCompactHeight(height),
                paged = enabled && columns >= 2 && !autoScrolling && !covered,
            )
        }
    }
}
