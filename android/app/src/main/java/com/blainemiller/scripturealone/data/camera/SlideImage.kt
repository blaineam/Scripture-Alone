package com.blainemiller.scripturealone.data.camera

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
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
