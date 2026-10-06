package com.blainemiller.scripturealone.data.image

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.File
import java.nio.ByteBuffer

/**
 * Decodes pictures at the size they are shown — [ImageSizing] picks it from the picture's own size,
 * which `ImageDecoder` reads from the header before decoding a pixel; the decoder then subsamples
 * while decoding, so the full-size bitmap never exists. Null for anything Android can't draw (an SVG).
 */
object ScaledBitmaps {

    /** [data] decoded at the size [target] picks from its own width and height. */
    fun decode(data: ByteArray, target: (width: Int, height: Int) -> ImageSizing.Size): Bitmap? =
        decode(ImageDecoder.createSource(ByteBuffer.wrap(data)), target)

    fun decode(file: File, target: (width: Int, height: Int) -> ImageSizing.Size): Bitmap? =
        decode(ImageDecoder.createSource(file), target)

    fun decode(source: ImageDecoder.Source, target: (width: Int, height: Int) -> ImageSizing.Size): Bitmap? =
        runCatching {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val size = info.size
                if (size.width > 0 && size.height > 0) {
                    val want = target(size.width, size.height)
                    if (want.width < size.width || want.height < size.height) decoder.setTargetSize(want.width, want.height)
                }
            }
        }.getOrNull()

    /** [bitmap] once [replacement] has been made from it — recycled, unless it *is* the replacement. */
    fun dropIntermediate(bitmap: Bitmap, replacement: Bitmap) {
        if (bitmap !== replacement && !bitmap.isRecycled) bitmap.recycle()
    }
}
