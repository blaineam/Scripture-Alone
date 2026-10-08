package com.blainemiller.scripturealone.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The column reader's decisions, on the JVM: when a window reads in columns (`ReaderColumns`, iOS's
 * numbers in dp), and how a chapter's lines are poured into them (`ColumnPagination`) — whole lines
 * only, every line once and in order, none past a column's foot.
 */
class ColumnChapterTest {

    // ── When ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aPhoneReadsOneColumnUprightAndTwoSideways() {
        // sa_guide_phone (1080 × 2400 at 420 dpi): 411 × 914 dp, at the default 19 pt.
        assertEquals(1, ReaderColumns.count(width = 411f, height = 914f, fontSize = 19f))
        assertEquals(2, ReaderColumns.count(width = 914f, height = 411f, fontSize = 19f))
        assertTrue(ReaderColumns.isCompactHeight(411f))
        assertFalse(ReaderColumns.isCompactHeight(914f))
    }

    @Test
    fun aCutoutOrSideBarNarrowsThePageOnlyPastTheMargin() {
        // A punch-hole camera held sideways: a 52 dp strip at one edge, 12 dp deeper than the margin.
        assertEquals(914f - 12f, ReaderColumns.usableWidth(914f, 52f, 0f), 0.01f)
        assertEquals(2, ReaderColumns.count(ReaderColumns.usableWidth(823f, 52f, 0f), 411f, 19f))
        // Insets within the margins cost nothing; a 3-button bar at the side (48 dp) costs 8.
        assertEquals(914f, ReaderColumns.usableWidth(914f, 24f, 40f), 0.01f)
        assertEquals(906f, ReaderColumns.usableWidth(914f, 0f, 48f), 0.01f)
    }

    @Test
    fun tabletsFoldablesAndWideWindowsReadInColumns() {
        assertEquals("a tablet held sideways", 2, ReaderColumns.count(1280f, 800f, 19f))
        assertEquals("an unfolded foldable", 2, ReaderColumns.count(841f, 701f, 19f))
        assertEquals("a wide Chromebook window", 2, ReaderColumns.count(1200f, 760f, 19f))
        // An 11-inch iPad upright (820 pt) holds two; a narrower tablet upright, as on iOS, holds one.
        assertEquals(2, ReaderColumns.count(820f, 1180f, 19f))
        assertEquals(1, ReaderColumns.count(800f, 1280f, 19f))
        assertFalse(ReaderColumns.isCompactHeight(800f))
    }

    @Test
    fun theThresholdIsEighteenEmsAColumnAndNeverMoreThanTwo() {
        // Two columns of 18 × 19 = 342 dp, a 40 dp gutter and 40 dp margins: 804 dp.
        assertEquals(2, ReaderColumns.count(804f, 400f, 19f))
        assertEquals(1, ReaderColumns.count(803f, 400f, 19f))
        // A larger text size needs a wider window.
        assertEquals(1, ReaderColumns.count(914f, 411f, 28f))
        // However wide, two at most.
        assertEquals(2, ReaderColumns.count(3000f, 1200f, 14f))
        // Under 360 dp tall there is no page to turn.
        assertEquals(1, ReaderColumns.count(914f, 359f, 19f))
        assertEquals(2, ReaderColumns.count(914f, 360f, 19f))
    }

    @Test
    fun theSettingAutoScrollAndTheStudySheetEachTurnColumnsOff() {
        val wide = { enabled: Boolean, scrolling: Boolean, covered: Boolean ->
            ReaderLayout.decide(914f, 411f, 19f, enabled, scrolling, covered).paged
        }
        assertTrue(wide(true, false, false))
        assertFalse("Columns on Wide Screens off", wide(false, false, false))
        assertFalse("auto-scroll scrolls one column", wide(true, true, false))
        assertFalse("the phone's Study sheet covers the page", wide(true, false, true))
        assertFalse("portrait is unchanged", ReaderLayout.decide(411f, 914f, 19f, true, false, false).paged)
    }

    @Test
    fun columnsFillThePageFromBarToBar() {
        // 411 dp tall: status bar 24 + top bar 64 above, gesture bar 24 + bottom bar 62 below.
        val safeTop = 24f + 64f
        val safeBottom = 24f + 62f
        val sideways = ReaderColumns.columnHeight(411f, safeTop, safeBottom, compactHeight = true)
        assertEquals(411f - safeTop - 8f - safeBottom - 6f, sideways, 0.01f)
        assertTrue("a sideways column is $sideways dp", sideways >= 220f)
        // A tablet too: the overlays (selection, highlighter, Listen, Now Playing) float over the text,
        // so no room is kept for them above the bottom bar.
        assertEquals(20f to 26f, ReaderColumns.verticalInsets(0f, 20f, compactHeight = false))
        assertEquals(800f - safeTop - 20f - safeBottom - 6f, ReaderColumns.columnHeight(800f, safeTop, safeBottom, compactHeight = false), 0.01f)
    }

    // ── Pagination ────────────────────────────────────────────────────────────────────────────────

    /** A paragraph of [lines] lines [height] px each, with the spacing given. */
    private fun para(lines: Int, height: Float = 30f, before: Float = 0f, after: Float = 0f, heading: Boolean = false) =
        ParagraphLines(before, after, List(lines) { it * height }, List(lines) { (it + 1) * height }, keepWithNext = heading)

    /** Every line of every paragraph appears exactly once, in reading order, and none passes [height]. */
    private fun assertWhole(paragraphs: List<ParagraphLines>, columns: List<List<ColumnSlice>>, height: Float) {
        val seen = columns.flatten().flatMap { s -> (s.fromLine until s.toLine).map { s.paragraph to it } }
        val expected = paragraphs.flatMapIndexed { i, p -> (0 until p.lineCount).map { i to it } }
        assertEquals(expected, seen)
        for ((c, column) in columns.withIndex()) {
            for (slice in column) {
                assertTrue("column $c: a line runs past the foot (${slice.y + slice.height} > $height)", slice.y + slice.height <= height + 0.5f)
                // A slice is whole lines: exactly its lines' height.
                val p = paragraphs[slice.paragraph]
                assertEquals(p.lineBottoms[slice.toLine - 1] - p.lineTops[slice.fromLine], slice.height, 0.001f)
            }
            column.zipWithNext { a, b -> assertTrue("slices overlap in column $c", b.y >= a.y + a.height - 0.001f) }
        }
    }

    @Test
    fun aColumnHoldsAsManyWholeLinesAsFit() {
        // 100 lines of 30 px in columns 280 px tall: 9 lines each (270 px), never a tenth cut in half.
        val paragraphs = listOf(para(100))
        val columns = ColumnPagination.paginate(paragraphs, 280f)
        assertWhole(paragraphs, columns, 280f)
        assertEquals(List(11) { 9 } + 1, columns.map { c -> c.sumOf { it.toLine - it.fromLine } })
        // The paragraph continues into the next column at the very next line, drawn from where it left off.
        assertEquals(9, columns[1][0].fromLine)
        assertEquals(270f, columns[1][0].top, 0.001f)
        assertEquals(0f, columns[1][0].y, 0.001f)
    }

    @Test
    fun spacingIsKeptBetweenParagraphsAndDroppedAtAColumnsHead() {
        val paragraphs = listOf(para(3, before = 0f, after = 10f), para(3, before = 20f, after = 10f), para(4, before = 20f))
        val columns = ColumnPagination.paginate(paragraphs, 200f)
        assertWhole(paragraphs, columns, 200f)
        // First column: 3 lines (90), 10 after + 20 before, 3 lines (90) — 210 is too much, so 2 of them.
        val first = columns[0]
        assertEquals(listOf(0, 1), first.map { it.paragraph })
        assertEquals(120f, first[1].y, 0.001f)
        assertEquals(2, first[1].toLine)
        // The second column opens on the rest with no space above it.
        assertEquals(0f, columns[1][0].y, 0.001f)
        assertEquals(2, columns[1][0].fromLine)
        // A paragraph that begins mid-column keeps its space before; its slice starts below it.
        val third = columns[1][1]
        assertEquals(2, third.paragraph)
        assertEquals(30f + 10f + 20f, third.y, 0.001f)
        assertEquals(20f, third.top, 0.001f)
    }

    @Test
    fun aHeadingNeverEndsAColumnAlone() {
        // 8 lines (240) then a heading (30) in a 290-px column: the heading would fit, its section wouldn't.
        val paragraphs = listOf(para(8), para(1, heading = true), para(5))
        val columns = ColumnPagination.paginate(paragraphs, 290f)
        assertWhole(paragraphs, columns, 290f)
        assertEquals(listOf(0), columns[0].map { it.paragraph })
        assertEquals(listOf(1, 2), columns[1].map { it.paragraph })
    }

    @Test
    fun aLineTallerThanAColumnStillGetsOne() {
        val paragraphs = listOf(para(2, height = 400f), para(1))
        val columns = ColumnPagination.paginate(paragraphs, 300f)
        assertEquals(3, columns.size)
        assertEquals(listOf(1, 1, 1), columns.map { c -> c.sumOf { it.toLine - it.fromLine } })
    }

    @Test
    fun findsTheColumnThatShowsALine() {
        val paragraphs = listOf(para(5), para(20))
        val columns = ColumnPagination.paginate(paragraphs, 300f)
        assertEquals(0, ColumnPagination.column(0, 4, columns))
        assertEquals(0, ColumnPagination.column(1, 4, columns))
        assertEquals(1, ColumnPagination.column(1, 5, columns))
        assertEquals(2, ColumnPagination.column(1, 15, columns))
        assertNull(ColumnPagination.column(1, 20, columns))
    }

    @Test
    fun anEmptyChapterIsOneEmptyColumn() {
        assertEquals(1, ColumnPagination.paginate(emptyList(), 300f).size)
    }
}
