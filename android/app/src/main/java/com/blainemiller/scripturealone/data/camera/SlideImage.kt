package com.blainemiller.scripturealone.data.camera

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decoding and shrinking slide photos — `SlideImage` in `SlideRecognizer.swift`. [ImageDecoder]
 * applies the photo's EXIF orientation, so the recognizer and the thumbnail see the slide upright.
 */
object SlideImage {

    /** The long edge a slide is read at — plenty for slide text, and quick to read. */
    const val READ_SIZE = 3000

    fun decode(data: ByteArray, maxPixelSize: Int = READ_SIZE): Bitmap? =
        runCatching { decode(ImageDecoder.createSource(ByteBuffer.wrap(data)), maxPixelSize) }.getOrNull()

    fun decode(resolver: ContentResolver, uri: Uri, maxPixelSize: Int = READ_SIZE): Bitmap? =
        runCatching { decode(ImageDecoder.createSource(resolver, uri), maxPixelSize) }.getOrNull()

    private fun decode(source: ImageDecoder.Source, maxPixelSize: Int): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // Software pixels: ML Kit and Compress both read them.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val size = info.size
            val longEdge = max(size.width, size.height)
            if (longEdge > maxPixelSize) {
                val scale = maxPixelSize.toDouble() / longEdge
                decoder.setTargetSize(max(1, (size.width * scale).roundToInt()), max(1, (size.height * scale).roundToInt()))
            }
        }

    /**
     * A camera frame turned upright by its [rotationDegrees] and capped at [maxPixelSize] —
     * `UIImage.uprightCGImage`.
     */
    fun upright(frame: Bitmap, rotationDegrees: Int, maxPixelSize: Int = READ_SIZE): Bitmap {
        val small = scaled(frame, maxPixelSize)
        if (rotationDegrees % 360 == 0) return small
        return Bitmap.createBitmap(small, 0, 0, small.width, small.height, Matrix().apply { postRotate(rotationDegrees.toFloat()) }, true)
    }

    /**
     * The screen the slide is on, straightened out to fill the picture — `SlideScreen.straightened` on
     * iOS. Null when no screen is found, or when it already fills the photo and there's nothing to cut.
     */
    fun screen(photo: Bitmap): Bitmap? {
        val quad = ScreenFinder.find(lumaGrid(photo)) ?: return null
        if (quad.area > 0.9) return null
        return straighten(photo, quad)
    }

    /** [photo]'s brightness on a grid [ScreenFinder.GRID] cells on its long edge. */
    fun lumaGrid(photo: Bitmap): LumaGrid {
        // Halving down to the grid averages away the slide's text strokes, where one bilinear step would skip them.
        var small = photo
        while (max(small.width, small.height) > ScreenFinder.GRID * 2) {
            small = Bitmap.createScaledBitmap(small, max(1, small.width / 2), max(1, small.height / 2), true)
        }
        small = scaled(small, ScreenFinder.GRID)
        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        val luma = IntArray(pixels.size) { i ->
            val p = pixels[i]
            (299 * (p shr 16 and 0xFF) + 587 * (p shr 8 and 0xFF) + 114 * (p and 0xFF)) / 1000
        }
        return LumaGrid(luma, small.width, small.height)
    }

    /** [photo] with the four-sided [quad] mapped onto a rectangle its own size — `CIPerspectiveCorrection`. */
    fun straighten(photo: Bitmap, quad: ScreenQuad): Bitmap {
        val points = quad.corners.map { floatArrayOf((it.x * photo.width).toFloat(), (it.y * photo.height).toFloat()) }
        fun length(a: Int, b: Int) = hypot(points[a][0] - points[b][0], points[a][1] - points[b][1])
        val width = max(1, ((length(0, 1) + length(3, 2)) / 2).roundToInt())
        val height = max(1, ((length(0, 3) + length(1, 2)) / 2).roundToInt())
        val matrix = Matrix().apply {
            setPolyToPoly(
                points.flatMap { it.toList() }.toFloatArray(), 0,
                floatArrayOf(0f, 0f, width.toFloat(), 0f, width.toFloat(), height.toFloat(), 0f, height.toFloat()), 0, 4,
            )
        }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(photo, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** A modest JPEG for keeping the photo with a note (only when the user asks to) — 1600 px, quality 0.7. */
    fun jpeg(image: Bitmap, maxPixelSize: Int = 1600, quality: Int = 70): ByteArray? {
        val small = scaled(image, maxPixelSize)
        val out = ByteArrayOutputStream()
        return if (small.compress(Bitmap.CompressFormat.JPEG, quality, out)) out.toByteArray() else null
    }

    fun scaled(image: Bitmap, maxPixelSize: Int): Bitmap {
        val longEdge = max(image.width, image.height)
        if (longEdge <= maxPixelSize) return image
        val scale = maxPixelSize.toDouble() / longEdge
        return Bitmap.createScaledBitmap(
            image, max(1, (image.width * scale).roundToInt()), max(1, (image.height * scale).roundToInt()), true,
        )
    }
}
