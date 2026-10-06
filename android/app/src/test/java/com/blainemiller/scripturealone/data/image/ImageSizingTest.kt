package com.blainemiller.scripturealone.data.image

import com.blainemiller.scripturealone.data.image.ImageSizing.Size
import com.blainemiller.scripturealone.ui.share.ShareAspect
import com.blainemiller.scripturealone.ui.share.ShareCardRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pictures are decoded at the size they are shown — never their own when it is larger. */
class ImageSizingTest {

    // A 12 MP phone photo, landscape and portrait, and a 50 MP one.
    private val photo = 4032 to 3024
    private val portrait = 3024 to 4032
    private val huge = 8160 to 6120

    @Test
    fun fitWithinShrinksToTheBoxAndKeepsTheAspect() {
        assertEquals(Size(1080, 810), ImageSizing.fitWithin(photo.first, photo.second, 1080, 1080))
        assertEquals(Size(450, 600), ImageSizing.fitWithin(portrait.first, portrait.second, 1080, 600))
        // The slide photo row on a 1080 px phone, 200 dp tall at 2.625×: height-bound.
        assertEquals(Size(700, 525), ImageSizing.fitWithin(photo.first, photo.second, 1080, 525))
    }

    @Test
    fun neverScalesUp() {
        assertEquals(Size(640, 480), ImageSizing.fitWithin(640, 480, 1080, 1080))
        assertEquals(Size(640, 480), ImageSizing.cover(640, 480, 1080, 1080))
        assertEquals(Size(640, 480), ImageSizing.fillWidth(640, 480, 1080, 4096))
        assertEquals(Size(640, 480), ImageSizing.fitLongEdge(640, 480, 3000))
    }

    @Test
    fun fitLongEdgeIsTheSlideReadSize() {
        assertEquals(Size(3000, 2250), ImageSizing.fitLongEdge(photo.first, photo.second, 3000))
        assertEquals(Size(2250, 3000), ImageSizing.fitLongEdge(portrait.first, portrait.second, 3000))
        assertEquals(Size(3000, 2250), ImageSizing.fitLongEdge(huge.first, huge.second, 3000))
    }

    @Test
    fun coverFillsTheThumbnailWithNothingToSpare() {
        // The 64 dp thumbnail at 2.625× is 168 px: the short side fills it, the long side is cropped.
        assertEquals(Size(224, 168), ImageSizing.cover(photo.first, photo.second, 168, 168))
        assertEquals(Size(168, 224), ImageSizing.cover(portrait.first, portrait.second, 168, 168))
        // Was decoded with inSampleSize at ≥160 px: for the 50 MP photo, 255×191 → now 224×168.
        val s = ImageSizing.cover(huge.first, huge.second, 168, 168)
        assertTrue(s.width >= 168 && s.height >= 168)
        assertEquals(168, s.height)
    }

    @Test
    fun fillWidthIsTheColumnAndCapsTallPictures() {
        assertEquals(Size(1080, 810), ImageSizing.fillWidth(photo.first, photo.second, 1080, 4096))
        // A very tall chart, 2000×20000, shown full-width: long edge capped at 4096.
        assertEquals(Size(410, 4096), ImageSizing.fillWidth(2000, 20_000, 1080, 4096))
    }

    @Test
    fun aSideIsNeverZero() {
        assertEquals(Size(100, 1), ImageSizing.fitWithin(10_000, 1, 100, 100))
        assertEquals(1, ImageSizing.fitLongEdge(20_000, 2, 100).height)
        assertEquals(1, ImageSizing.fillWidth(5_000, 1, 1080, 4096).height)
    }

    @Test
    fun theBytesSavedForTheWorstCases() {
        // The imported-picture viewer used to decode a 50 MP picture at a power-of-two ≥ 2048 —
        // 4080×3060, 49.9 MB. Full-width on a 1080 px phone it is 1080×810, 3.5 MB.
        assertEquals(49_939_200L, Size(4080, 3060).bytes)
        assertEquals(3_499_200L, ImageSizing.fillWidth(huge.first, huge.second, 1080, 4096).bytes)
        // A 1320×2868 guide screenshot shown 240 dp wide (630 px): 15.1 MB → 3.3 MB.
        assertEquals(Size(630, 1369), ImageSizing.fitWithin(1320, 2868, 630, Int.MAX_VALUE))
    }

    @Test
    fun theShareCardIsDrawnAtItsOutputSize() {
        assertEquals(2160 to 2160, ShareCardRenderer.outputSize(ShareAspect.SQUARE))
        assertEquals(1216 to 2160, ShareCardRenderer.outputSize(ShareAspect.STORY))
        assertEquals(2160 to 1216, ShareCardRenderer.outputSize(ShareAspect.WIDE))
    }
}
