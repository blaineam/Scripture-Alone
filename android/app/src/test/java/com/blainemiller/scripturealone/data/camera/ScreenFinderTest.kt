package com.blainemiller.scripturealone.data.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // MARK: The screen that holds the text — `SlideScreen.straightened(_:around:)`

    private fun line(text: String, x: Double, y: Double, width: Double = 0.2, height: Double = 0.04, confidence: Double = 0.9) =
        com.blainemiller.scripturealone.data.slides.SlideLine(text, x, y, width, height, confidence)

    private fun quad(l: Double, t: Double, r: Double, b: Double) =
        ScreenQuad(ScreenPoint(l, t), ScreenPoint(r, t), ScreenPoint(r, b), ScreenPoint(l, b))

    @Test fun theScreenWithTheTextWinsOverABiggerBrightDoorway() {
        val doorway = quad(0.05, 0.05, 0.45, 0.95)
        val screen = quad(0.55, 0.2, 0.9, 0.6)
        val text = listOf(line("Sermon title", 0.6, 0.25), line("John 3:16", 0.6, 0.35), line("Grace", 0.6, 0.45))
        assertEquals(ScreenCrop.Screen(screen), ScreenFinder.aroundText(listOf(doorway, screen), text))
    }

    @Test fun ofTwoShapesHoldingTheTextTheTightestWins() {
        val wall = quad(0.1, 0.1, 0.85, 0.85)
        val screen = quad(0.3, 0.3, 0.7, 0.7)
        val text = listOf(line("Romans 8", 0.35, 0.4), line("We know that", 0.35, 0.5))
        assertEquals(ScreenCrop.Screen(screen), ScreenFinder.aroundText(listOf(wall, screen), text))
    }

    @Test fun aShapeWithLessThanHalfTheTextIsNotTheScreen() {
        val lamp = quad(0.05, 0.05, 0.25, 0.25)
        val text = listOf(line("one", 0.1, 0.1, 0.05), line("two", 0.5, 0.5), line("three", 0.5, 0.6), line("four", 0.5, 0.7))
        // No shape holds half: the text itself, with room to spare.
        val crop = ScreenFinder.aroundText(listOf(lamp), text) as ScreenCrop.Text
        assertEquals(0.02, crop.left, 1e-9)
        assertEquals(0.02, crop.top, 1e-9)
        assertEquals(0.78, crop.right, 1e-9)
        assertEquals(0.82, crop.bottom, 1e-9)
    }

    @Test fun textAcrossMostOfThePhotoIsNotCropped() {
        val text = listOf(line("top left", 0.0, 0.0), line("bottom right", 0.8, 0.95))
        assertNull(ScreenFinder.aroundText(emptyList(), text))
    }

    @Test fun noReadableTextNoCrop() {
        val screen = quad(0.3, 0.3, 0.7, 0.7)
        assertNull(ScreenFinder.aroundText(listOf(screen), emptyList()))
        // Low-confidence specks and single letters don't count as the slide's text.
        assertNull(ScreenFinder.aroundText(listOf(screen), listOf(line("x", 0.4, 0.4), line("blur", 0.4, 0.5, confidence = 0.2))))
    }

    @Test fun aScreenFillingThePhotoGivesNothingToStraighten() {
        val full = quad(0.0, 0.0, 1.0, 0.95)
        val text = listOf(line("Hello there", 0.4, 0.45))
        // The screen is the whole picture: the crop falls back to the text.
        assertEquals(ScreenCrop.Text::class, ScreenFinder.aroundText(listOf(full), text)!!::class)
    }

    @Test fun candidatesFindTwoBrightShapesBiggestFirst() {
        val luma = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            when {
                x in 5 until 55 && y in 5 until 115 -> 225 // a tall doorway
                x in 80 until 150 && y in 30 until 80 -> 215 // the screen
                else -> 35
            }
        }
        val found = ScreenFinder.candidates(LumaGrid(luma, w, h))
        assertEquals(2, found.size)
        assertTrue(found[0].area > found[1].area)
        assertEquals(ScreenFinder.find(LumaGrid(luma, w, h)), found[0])
    }
}
