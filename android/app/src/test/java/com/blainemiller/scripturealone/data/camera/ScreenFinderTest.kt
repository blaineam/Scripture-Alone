package com.blainemiller.scripturealone.data.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** [ScreenFinder] against rooms drawn cell by cell: a lit screen with dark slide text, at an angle, and things that aren't screens. */
class ScreenFinderTest {

    private val w = 160
    private val h = 120

    /** A dim room with a bright four-sided screen at [corners] (grid cells, clockwise from top left). */
    private fun room(vararg corners: Pair<Double, Double>, room: Int = 40, screen: Int = 215, text: Boolean = true): LumaGrid {
        val luma = IntArray(w * h) { i ->
            val x = i % w + 0.5
            val y = i / w + 0.5
            if (inside(x, y, corners.toList())) screen else room + (i * 7919 % 13) // a little noise
        }
        if (text) {
            // Dark lines of slide text across the middle of the screen.
            val xs = corners.map { it.first }
            val ys = corners.map { it.second }
            val left = xs.min() + (xs.max() - xs.min()) * 0.2
            val right = xs.max() - (xs.max() - xs.min()) * 0.2
            val top = ys.min() + (ys.max() - ys.min()) * 0.3
            for (line in 0 until 3) {
                val y = (top + line * 6).toInt()
                for (x in left.toInt() until right.toInt()) {
                    luma[y * w + x] = 30
                    luma[(y + 1) * w + x] = 30
                }
            }
        }
        return LumaGrid(luma, w, h)
    }

    private fun inside(x: Double, y: Double, poly: List<Pair<Double, Double>>): Boolean {
        var sign = 0
        for (i in poly.indices) {
            val (ax, ay) = poly[i]
            val (bx, by) = poly[(i + 1) % poly.size]
            val cross = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
            val s = if (cross >= 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    private fun assertCorner(expected: Pair<Double, Double>, actual: ScreenPoint, cells: Double = 2.5) {
        assertEquals(expected.first / w, actual.x, cells / w)
        assertEquals(expected.second / h, actual.y, cells / h)
    }

    @Test fun findsAStraightOnScreenWithTextOnIt() {
        val corners = arrayOf(30.0 to 20.0, 130.0 to 20.0, 130.0 to 80.0, 30.0 to 80.0)
        val quad = found(ScreenFinder.find(room(*corners)))
        assertCorner(corners[0], quad.topLeft)
        assertCorner(corners[1], quad.topRight)
        assertCorner(corners[2], quad.bottomRight)
        assertCorner(corners[3], quad.bottomLeft)
        assertEquals(100.0 * 60 / (w * h), quad.area, 0.03)
    }

    @Test fun findsAScreenSeenFromTheSide() {
        // Nearer on the left: a taller left edge than right.
        val corners = arrayOf(20.0 to 15.0, 120.0 to 30.0, 120.0 to 75.0, 20.0 to 95.0)
        val quad = found(ScreenFinder.find(room(*corners)))
        assertCorner(corners[0], quad.topLeft)
        assertCorner(corners[1], quad.topRight)
        assertCorner(corners[2], quad.bottomRight)
        assertCorner(corners[3], quad.bottomLeft)
    }

    @Test fun findsAScreenRunningOffTheFrame() {
        val quad = found(ScreenFinder.find(room(-10.0 to 10.0, 150.0 to 10.0, 150.0 to 110.0, -10.0 to 110.0, text = false)))
        assertEquals(0.0, quad.topLeft.x, 1e-9)
        assertEquals(0.0, quad.bottomLeft.x, 1e-9)
    }

    @Test fun aTinyScreenIsNotTheScreen() {
        assertNull(ScreenFinder.find(room(70.0 to 50.0, 90.0 to 50.0, 90.0 to 62.0, 70.0 to 62.0, text = false)))
    }

    @Test fun anEvenlyLitPictureHasNoScreen() {
        assertNull(ScreenFinder.find(LumaGrid(IntArray(w * h) { 120 + it % 9 }, w, h)))
    }

    @Test fun aScreenBarelyBrighterThanTheRoomIsNotFound() {
        assertNull(ScreenFinder.find(room(30.0 to 20.0, 130.0 to 20.0, 130.0 to 80.0, 30.0 to 80.0, room = 150, screen = 175)))
    }

    @Test fun anLShapedLightIsNotAScreen() {
        val luma = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if ((x in 20 until 60 && y in 10 until 110) || (x in 20 until 140 && y in 70 until 110)) 220 else 35
        }
        assertNull(ScreenFinder.find(LumaGrid(luma, w, h)))
    }

    @Test fun aThinLightJoinedToTheScreenIsCutAway() {
        val corners = arrayOf(40.0 to 30.0, 140.0 to 30.0, 140.0 to 90.0, 40.0 to 90.0)
        val grid = room(*corners)
        // A one-cell-wide strip of light running from the screen to the corner of the room.
        for (x in 0 until 40) grid.luma[30 * w + x] = 215
        val quad = found(ScreenFinder.find(grid))
        assertCorner(corners[0], quad.topLeft)
        assertCorner(corners[3], quad.bottomLeft)
    }

    private fun found(quad: ScreenQuad?): ScreenQuad {
        assertNotNull("no screen found", quad)
        return quad!!
    }
}
