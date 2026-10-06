package com.blainemiller.scripturealone.data.camera

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import com.blainemiller.scripturealone.data.image.ImageSizing
import com.blainemiller.scripturealone.data.image.ScaledBitmaps
import java.io.ByteArrayOutputStream
import java.io.File
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

    /** Straight from the file, so the photo's compressed bytes aren't held in memory too. */
    fun decode(file: File, maxPixelSize: Int = READ_SIZE): Bitmap? =
        runCatching { decode(ImageDecoder.createSource(file), maxPixelSize) }.getOrNull()

    private fun decode(source: ImageDecoder.Source, maxPixelSize: Int): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // Software pixels: ML Kit and Compress both read them.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            // Subsampled while decoding: the camera's full 12–50 MP never exists as a bitmap.
            val size = info.size
            if (max(size.width, size.height) > maxPixelSize) {
                val target = ImageSizing.fitLongEdge(size.width, size.height, maxPixelSize)
                decoder.setTargetSize(target.width, target.height)
            }
        }

    /**
     * A camera frame turned upright by its [rotationDegrees] and capped at [maxPixelSize] —
     * `UIImage.uprightCGImage`. The scaled copy in between is recycled; [frame] is the caller's.
     */
    fun upright(frame: Bitmap, rotationDegrees: Int, maxPixelSize: Int = READ_SIZE): Bitmap {
        val small = scaled(frame, maxPixelSize)
        if (rotationDegrees % 360 == 0) return small
        val turned = Bitmap.createBitmap(small, 0, 0, small.width, small.height, Matrix().apply { postRotate(rotationDegrees.toFloat()) }, true)
        if (small !== frame) ScaledBitmaps.dropIntermediate(small, turned)
        return turned
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
        // Each halving's input is dropped as soon as the next exists (never [photo], the caller's).
        var small = photo
        fun step(next: Bitmap) {
            if (small !== photo) ScaledBitmaps.dropIntermediate(small, next)
            small = next
        }
        while (max(small.width, small.height) > ScreenFinder.GRID * 2) {
            step(Bitmap.createScaledBitmap(small, max(1, small.width / 2), max(1, small.height / 2), true))
        }
        step(scaled(small, ScreenFinder.GRID))
        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        if (small !== photo) small.recycle()
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
        val ok = small.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (small !== image) small.recycle()
        return if (ok) out.toByteArray() else null
    }

    /**
     * A copy of [image] for showing in a [maxWidth]×[maxHeight] box (`ContentScale.Fit`): the slide is
     * kept at [READ_SIZE] for reading, but drawing that in a 200 dp row would upload all of it.
     */
    fun preview(image: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val size = ImageSizing.fitWithin(image.width, image.height, maxWidth, maxHeight)
        if (size.width >= image.width && size.height >= image.height) return image
        return Bitmap.createScaledBitmap(image, size.width, size.height, true)
    }

    /** [image] with no side past [maxPixelSize] — itself when it's that small already. */
    fun scaled(image: Bitmap, maxPixelSize: Int): Bitmap {
        if (max(image.width, image.height) <= maxPixelSize) return image
        val size = ImageSizing.fitLongEdge(image.width, image.height, maxPixelSize)
        return Bitmap.createScaledBitmap(image, size.width, size.height, true)
    }
}
