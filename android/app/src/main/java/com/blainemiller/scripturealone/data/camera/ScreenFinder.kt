package com.blainemiller.scripturealone.data.camera

import com.blainemiller.scripturealone.data.slides.SlideLine
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A picture's brightness (0–255) on a coarse grid of square cells, row by row — what [ScreenFinder] looks at. */
class LumaGrid(val luma: IntArray, val width: Int, val height: Int) {
    init {
        require(luma.size == width * height)
    }
}

/** A corner of the screen, normalized to the picture (0…1, origin top-left). */
data class ScreenPoint(val x: Double, val y: Double)

/** The screen's four corners, clockwise from the top left. */
data class ScreenQuad(val topLeft: ScreenPoint, val topRight: ScreenPoint, val bottomRight: ScreenPoint, val bottomLeft: ScreenPoint) {
    val corners: List<ScreenPoint> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    /** The share of the picture the screen covers. */
    val area: Double get() = quadArea(corners.map { it.x }, corners.map { it.y })

    /** Whether the (convex) screen holds [x], [y] — `SlideScreen.contains`. */
    fun contains(x: Double, y: Double): Boolean {
        var sign = 0.0
        val points = corners
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            val cross = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)
            if (cross == 0.0) continue
            if (sign == 0.0) sign = cross else if ((sign > 0) != (cross > 0)) return false
        }
        return true
    }
}

/** What to read a slide photo at: the screen straightened, or a plain crop around its text (0…1, top-left origin). */
sealed class ScreenCrop {
    data class Screen(val quad: ScreenQuad) : ScreenCrop()
    data class Text(val left: Double, val top: Double, val right: Double, val bottom: Double) : ScreenCrop()
}

/**
 * Finds the screen a slide is shown on — a projector screen, a TV, a monitor — in a photo or a preview
 * frame, as the one large, bright, four-sided shape that stands out from the room around it. iOS asks
 * Vision's rectangle detector (`SlideScreen` in `SlideRecognizer.swift`); ML Kit has none, and a
 * lit screen in a darker room is what a sermon slide nearly always is.
 *
 * Null when nothing in the picture is shaped like a screen: the reader then gets the whole photo.
 */
object ScreenFinder {
    /** The long edge of the grid a picture is looked at on: fine enough for the corners, quick enough for every frame. */
    const val GRID = 160

    /** How much brighter than the room the screen must be, in levels of 255. */
    private const val MIN_CONTRAST = 40.0

    /** The smallest share of the picture a screen can cover. */
    private const val MIN_SHARE = 0.06

    /** Past this share, the "screen" is the whole frame — nothing stood out from it. */
    private const val MAX_SHARE = 0.97

    /** How much of its outline a screen must fill — the rest is room caught in a lopsided shape. */
    private const val MIN_FILL = 0.85

    /** The most screen-like shape: the biggest bright one, as the live preview outlines it. */
    fun find(grid: LumaGrid): ScreenQuad? = shapes(grid).firstOrNull()?.let { quad(it, grid.width, grid.height) }

    /**
     * Every bright, four-sided shape that could be the screen, biggest first, up to [limit] — for
     * [aroundText] to choose the one holding the slide's words, as iOS asks Vision for up to 24
     * rectangles. A lit doorway or window may be bigger than the screen; the text tells them apart.
     */
    fun candidates(grid: LumaGrid, limit: Int = 6): List<ScreenQuad> =
        shapes(grid).asSequence().mapNotNull { quad(it, grid.width, grid.height) }.take(limit).toList()

    /** The bright shapes, holes filled, biggest first. */
    private fun shapes(grid: LumaGrid): Sequence<BooleanArray> {
        val w = grid.width
        val h = grid.height
        if (w < 8 || h < 8) return emptySequence()
        val threshold = threshold(grid.luma) ?: return emptySequence()
        val bright = open(BooleanArray(w * h) { grid.luma[it] > threshold }, w, h)
        return components(bright, w, h).map { fillHoles(it, w, h) }
    }

    /**
     * The screen the slide's [text] is on — `SlideScreen.straightened(_:around:)`. The lines are those
     * read from the whole photo; the screen is the [candidates] shape holding the most of them (at least
     * half), the tightest of those. With none around the text, the text itself with a margin of a few
     * lines' height. Null when there's no text, or the crop would be most of the photo anyway.
     */
    fun aroundText(candidates: List<ScreenQuad>, text: List<SlideLine>): ScreenCrop? {
        val lines = text.filter { it.confidence >= 0.5 && it.text.length >= 2 }
        if (lines.isEmpty()) return null
        val centers = lines.map { (it.box.x + it.box.width / 2) to it.box.midY }
        val enough = max(1, (centers.size + 1) / 2)
        val screen = candidates
            .map { quad -> quad to centers.count { (x, y) -> quad.contains(x, y) } }
            .filter { it.second >= enough }
            .sortedWith(compareByDescending<Pair<ScreenQuad, Int>> { it.second }.thenBy { it.first.area })
            .firstOrNull()?.first
        if (screen != null && screen.area < 0.9) return ScreenCrop.Screen(screen)

        val lineHeight = lines.maxOf { it.box.height }
        val margin = max(lineHeight * 2, 0.03)
        val left = max(0.0, lines.minOf { it.box.x } - margin)
        val top = max(0.0, lines.minOf { it.box.y } - margin)
        val right = min(1.0, lines.maxOf { it.box.maxX } + margin)
        val bottom = min(1.0, lines.maxOf { it.box.maxY } + margin)
        if ((right - left) * (bottom - top) >= 0.8) return null
        return ScreenCrop.Text(left, top, right, bottom)
    }

    /** [shape]'s four corners, if it is screen-sized and screen-shaped. */
    private fun quad(shape: BooleanArray, w: Int, h: Int): ScreenQuad? {
        val cells = shape.count { it }
        if (cells < MIN_SHARE * w * h || cells > MAX_SHARE * w * h) return null

        // The corners are the shape's extremes along the diagonals — the cell's own outer corner, so a
        // screen that fills the frame reaches its edges.
        var tl = 0; var tr = 0; var br = 0; var bl = 0
        var tlScore = Int.MAX_VALUE; var trScore = Int.MIN_VALUE; var brScore = Int.MIN_VALUE; var blScore = Int.MAX_VALUE
        for (i in shape.indices) {
            if (!shape[i]) continue
            val x = i % w
            val y = i / w
            if (x + y < tlScore) { tlScore = x + y; tl = i }
            if (x - y > trScore) { trScore = x - y; tr = i }
            if (x + y > brScore) { brScore = x + y; br = i }
            if (x - y < blScore) { blScore = x - y; bl = i }
        }
        val xs = doubleArrayOf((tl % w).toDouble(), (tr % w) + 1.0, (br % w) + 1.0, (bl % w).toDouble())
        val ys = doubleArrayOf((tl / w).toDouble(), (tr / w).toDouble(), (br / w) + 1.0, (bl / w) + 1.0)
        if (!isScreenShaped(xs, ys, cells)) return null
        fun point(i: Int) = ScreenPoint(xs[i] / w, ys[i] / h)
        return ScreenQuad(point(0), point(1), point(2), point(3))
    }

    /** Four corners that make a convex, roughly rectangular screen the shape fills. */
    private fun isScreenShaped(xs: DoubleArray, ys: DoubleArray, cells: Int): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val a = i
            val b = (i + 1) % 4
            val c = (i + 2) % 4
            val cross = (xs[b] - xs[a]) * (ys[c] - ys[b]) - (ys[b] - ys[a]) * (xs[c] - xs[b])
            val s = if (cross > 0) 1 else if (cross < 0) -1 else return false
            if (sign == 0) sign = s else if (s != sign) return false
        }
        fun side(i: Int) = hypot(xs[(i + 1) % 4] - xs[i], ys[(i + 1) % 4] - ys[i])
        val top = side(0); val right = side(1); val bottom = side(2); val left = side(3)
        // Opposite sides alike, even at an angle; not a sliver.
        if (min(top, bottom) < 0.4 * max(top, bottom) || min(left, right) < 0.4 * max(left, right)) return false
        val across = (top + bottom) / 2
        val down = (left + right) / 2
        if (max(across, down) > 4 * min(across, down)) return false
        return cells >= MIN_FILL * quadArea(xs.toList(), ys.toList())
    }

    /** Otsu's threshold between the room and the screen — null when the two aren't far enough apart. */
    private fun threshold(luma: IntArray): Int? {
        val histogram = IntArray(256)
        for (v in luma) histogram[v.coerceIn(0, 255)]++
        val total = luma.size
        var sumAll = 0.0
        for (i in 0..255) sumAll += i.toDouble() * histogram[i]
        var sumBelow = 0.0
        var below = 0
        var best = -1.0
        var threshold = -1
        var contrast = 0.0
        for (t in 0..254) {
            below += histogram[t]
            if (below == 0) continue
            val above = total - below
            if (above == 0) break
            sumBelow += t.toDouble() * histogram[t]
            val meanBelow = sumBelow / below
            val meanAbove = (sumAll - sumBelow) / above
            val between = below.toDouble() * above * (meanAbove - meanBelow) * (meanAbove - meanBelow)
            if (between > best) {
                best = between
                threshold = t
                contrast = meanAbove - meanBelow
            }
        }
        return threshold.takeIf { it >= 0 && contrast >= MIN_CONTRAST }
    }

    /** Erodes then dilates, so a thin bright bridge — a lamp, a lit doorframe — doesn't join the screen to the room. */
    private fun open(mask: BooleanArray, w: Int, h: Int): BooleanArray {
        fun pass(source: BooleanArray, erode: Boolean): BooleanArray = BooleanArray(w * h) { i ->
            val x = i % w
            val y = i / w
            // Off the edge counts as the cell itself, so a screen that runs out of the frame isn't eaten from there.
            val left = if (x > 0) source[i - 1] else source[i]
            val right = if (x < w - 1) source[i + 1] else source[i]
            val up = if (y > 0) source[i - w] else source[i]
            val down = if (y < h - 1) source[i + w] else source[i]
            if (erode) source[i] && left && right && up && down else source[i] || left || right || up || down
        }
        return pass(pass(mask, erode = true), erode = false)
    }

    /** The four-connected runs of bright cells of at least [minCells], biggest first (lazily materialized). */
    private fun components(mask: BooleanArray, w: Int, h: Int, minCells: Int = 1): Sequence<BooleanArray> {
        val label = IntArray(w * h)
        val queue = IntArray(w * h)
        val sizes = mutableListOf<Pair<Int, Int>>()
        var next = 0
        for (start in mask.indices) {
            if (!mask[start] || label[start] != 0) continue
            next++
            var head = 0
            var tail = 0
            queue[tail++] = start
            label[start] = next
            while (head < tail) {
                val i = queue[head++]
                val x = i % w
                val y = i / w
                fun visit(j: Int) {
                    if (mask[j] && label[j] == 0) {
                        label[j] = next
                        queue[tail++] = j
                    }
                }
                if (x > 0) visit(i - 1)
                if (x < w - 1) visit(i + 1)
                if (y > 0) visit(i - w)
                if (y < h - 1) visit(i + w)
            }
            if (tail >= max(1, minCells)) sizes += next to tail
        }
        return sizes.sortedByDescending { it.second }.asSequence().map { (id, _) -> BooleanArray(w * h) { label[it] == id } }
    }

    /** The shape with the slide's own dark text and pictures filled in: everything the room can't reach. */
    private fun fillHoles(shape: BooleanArray, w: Int, h: Int): BooleanArray {
        val outside = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var tail = 0
        fun seed(i: Int) {
            if (!shape[i] && !outside[i]) {
                outside[i] = true
                queue[tail++] = i
            }
        }
        for (x in 0 until w) { seed(x); seed((h - 1) * w + x) }
        for (y in 0 until h) { seed(y * w); seed(y * w + w - 1) }
        var head = 0
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            if (x > 0) seed(i - 1)
            if (x < w - 1) seed(i + 1)
            if (y > 0) seed(i - w)
            if (y < h - 1) seed(i + w)
        }
        return BooleanArray(w * h) { !outside[it] }
    }
}

/** A quadrilateral's area, by the shoelace formula. */
private fun quadArea(xs: List<Double>, ys: List<Double>): Double {
    var sum = 0.0
    for (i in xs.indices) {
        val j = (i + 1) % xs.size
        sum += xs[i] * ys[j] - xs[j] * ys[i]
    }
    return abs(sum) / 2
}
