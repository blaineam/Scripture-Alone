package com.blainemiller.scripturealone.data.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blainemiller.scripturealone.data.slides.SlideLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [SlideImage.screen] on real bitmaps: a dark room with a big lit doorway and a smaller lit screen
 * holding the text — the screen is straightened, not the doorway — and the plain crop to the text
 * when nothing bright surrounds it.
 */
@RunWith(AndroidJUnit4::class)
class SlideScreenDeviceTest {

    private fun room(vararg bright: android.graphics.RectF): Bitmap {
        val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(30, 28, 26))
            val paint = Paint().apply { color = Color.rgb(235, 235, 230) }
            for (rect in bright) drawRect(rect, paint)
        }
        return bitmap
    }

    private fun line(text: String, x: Double, y: Double) = SlideLine(text, x, y, 0.2, 0.03, 0.9)

    @Test fun theScreenHoldingTheTextIsStraightenedNotTheDoorway() {
        // Doorway: 300×800 px on the left. Screen: 480×270 px (16:9) on the right.
        val photo = room(android.graphics.RectF(60f, 50f, 360f, 850f), android.graphics.RectF(600f, 200f, 1080f, 470f))
        val text = listOf(line("The Good Shepherd", 0.53, 0.28), line("John 10:11", 0.53, 0.35), line("I am the door", 0.53, 0.42))
        val screen = requireNotNull(SlideImage.screen(photo, text)) { "no screen found" }
        // The screen's own proportions, about 480×270 — not the doorway's tall 300×800.
        assertEquals(480.0, screen.width.toDouble(), 30.0)
        assertEquals(270.0, screen.height.toDouble(), 30.0)
    }

    @Test fun textWithNoScreenAroundItIsCroppedToTheText() {
        val photo = room()
        val text = listOf(line("Romans 8:28", 0.4, 0.4), line("All things", 0.4, 0.45))
        val crop = SlideImage.screen(photo, text)!!
        // 0.2 wide plus two line heights (0.06) each side → 0.32 of 1200; 0.08 tall plus 0.12 → 0.2 of 900.
        assertEquals(384.0, crop.width.toDouble(), 3.0)
        assertEquals(180.0, crop.height.toDouble(), 3.0)
    }

    @Test fun noTextNoCrop() {
        assertNull(SlideImage.screen(room(android.graphics.RectF(600f, 200f, 1080f, 470f)), emptyList()))
    }
}
