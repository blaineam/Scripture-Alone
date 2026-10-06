package com.blainemiller.scripturealone.data.image

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The size to decode a picture at — the size it is shown at, never its own when that is larger.
 * A 12-megapixel photo decoded whole is 48 MB of pixels for a 64 dp thumbnail; decoded at the
 * thumbnail's size it is a quarter of a megabyte. Plain arithmetic, so it is proven on the JVM;
 * [ScaledBitmaps] hands the result to `ImageDecoder.setTargetSize`.
 *
 * Every function scales down only, keeps the aspect ratio (to the pixel), and never returns a side
 * below 1.
 */
object ImageSizing {

    data class Size(val width: Int, val height: Int) {
        /** The bitmap's pixels in bytes at ARGB_8888. */
        val bytes: Long get() = width.toLong() * height * 4
    }

    /** [width]×[height] shrunk by [scale] (≤ 1), rounded, each side at least 1. */
    private fun scaled(width: Int, height: Int, scale: Double): Size =
        if (scale >= 1.0) Size(width, height)
        else Size(max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()))

    /** Inside a [maxWidth]×[maxHeight] box — `ContentScale.Fit`. */
    fun fitWithin(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Size {
        require(width > 0 && height > 0 && maxWidth > 0 && maxHeight > 0)
        return scaled(width, height, min(maxWidth.toDouble() / width, maxHeight.toDouble() / height))
    }

    /** No side longer than [maxLongEdge] — a slide read for its text, a photo kept with a note. */
    fun fitLongEdge(width: Int, height: Int, maxLongEdge: Int): Size = fitWithin(width, height, maxLongEdge, maxLongEdge)

    /**
     * Filling a [boxWidth]×[boxHeight] box and cropped to it — `ContentScale.Crop`: the smallest size
     * that still covers the box, so the crop loses no sharpness.
     */
    fun cover(width: Int, height: Int, boxWidth: Int, boxHeight: Int): Size {
        require(width > 0 && height > 0 && boxWidth > 0 && boxHeight > 0)
        return scaled(width, height, max(boxWidth.toDouble() / width, boxHeight.toDouble() / height))
    }

    /**
     * As wide as a [maxWidth] column — `ContentScale.FillWidth` — and no side past [maxLongEdge], so a
     * very tall picture in a scrolling viewer stays a sane texture.
     */
    fun fillWidth(width: Int, height: Int, maxWidth: Int, maxLongEdge: Int): Size {
        require(width > 0 && height > 0 && maxWidth > 0 && maxLongEdge > 0)
        return scaled(width, height, min(maxWidth.toDouble() / width, maxLongEdge.toDouble() / max(width, height)))
    }
}
