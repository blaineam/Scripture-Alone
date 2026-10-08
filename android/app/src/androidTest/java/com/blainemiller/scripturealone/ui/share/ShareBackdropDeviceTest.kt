package com.blainemiller.scripturealone.ui.share

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.blainemiller.scripturealone.data.share.SharePassageText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The grounds as Android actually draws them: close to the iOS drawing the JVM tests check colours
 * against (`ShareBackground.referenceStats`), legible with each ready-made style, and crisp at the
 * 2× export — `ShareStyleTests.swift` asks the same of the Apple drawing.
 */
@RunWith(AndroidJUnit4::class)
class ShareBackdropDeviceTest {

    private fun close(a: Long, b: Long, tolerance: Int): Boolean =
        listOf(16, 8, 0).all { abs(((a shr it) and 0xFF) - ((b shr it) and 0xFF)) <= tolerance }

    @Test fun groundsMatchTheIosDrawingAndEveryStyleReads() {
        for (background in ShareBackground.entries) {
            val stats = ShareBackdrop.stats(background)
            val reference = background.referenceStats
            assertTrue("$background mean ${stats.mean.toString(16)} vs ${reference.mean.toString(16)}", close(stats.mean, reference.mean, 10))
            val colors = ShareStyle().applying(background).colors(stats)
            assertFalse("$background needed adjusting on Android", colors.adjusted)
            assertTrue("$background text", ShareContrast.check(colors.ink, stats).mean >= ShareContrast.TARGET)
        }
    }

    /** The exported card: 2160 px with fine texture that still differs pixel to pixel. */
    @Test fun texturesAreCrispAtExportSize() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val renderer = ShareCardRenderer(context)
        for (background in listOf(ShareBackground.GRAIN, ShareBackground.LINEN, ShareBackground.CANVAS, ShareBackground.WATERCOLOR)) {
            val style = ShareStyle().applying(background)
            val content = ShareCardContent(SharePassageText("", emptyList(), emptyList()), "John 3:16", "BSB", null, 40f)
            val bitmap = renderer.bitmap(content, style)
            assertEquals(2160, bitmap.width)
            var total = 0.0
            var count = 0
            val y = 200
            for (x in 1 until bitmap.width) {
                total += abs(lum(bitmap.getPixel(x, y)) - lum(bitmap.getPixel(x - 1, y)))
                count++
            }
            bitmap.recycle()
            assertTrue("$background detail ${total / count}", total / count > 0.6)
        }
    }

    private fun lum(c: Int) = 0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)
}
