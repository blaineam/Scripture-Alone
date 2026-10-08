package com.blainemiller.scripturealone.data.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * The bitmap audit's before and after, measured on a device: a large photo decoded the old way
 * (BitmapFactory, inSampleSize down to ≥ 2048 — the imported-picture viewer) and the new (ImageDecoder
 * at the viewer's width); a phone screenshot as the User Guide decoded it (full size) and now (its
 * 240 dp width). Logged under tag "BitmapAudit"; the new path must be several times smaller.
 */
@RunWith(AndroidJUnit4::class)
class ScaledBitmapsDeviceTest {

    private fun jpeg(width: Int, height: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(20, 30, 60))
            val paint = Paint().apply { color = Color.WHITE; textSize = height / 12f }
            for (i in 0 until 6) drawText("John 10:11 — the good shepherd", width * 0.05f, height * (0.15f + i * 0.14f), paint)
        }
        return ByteArrayOutputStream().also { bitmap.compress(format, 90, it); bitmap.recycle() }.toByteArray()
    }

    /**
     * The first hardware bitmap in a process sets up the renderer (~13 MB of native heap on the
     * emulator, once, whatever the picture): done before measuring, as the app has long done it.
     */
    @Before
    fun warmUpHardwareBitmaps() {
        ScaledBitmaps.decode(jpeg(64, 64)) { w, h -> ImageSizing.Size(w, h) }?.recycle()
    }

    /** The decode's bitmap bytes and the native heap it took, measured while it is held. */
    private fun measure(label: String, decode: () -> Bitmap?): Pair<Long, Long> {
        System.gc(); Runtime.getRuntime().gc()
        val before = Debug.getNativeHeapAllocatedSize()
        val bitmap = decode() ?: error("$label: nothing decoded")
        // What stays, with the bitmap still held: the decoder's own buffers are let go first.
        repeat(3) { System.gc(); System.runFinalization(); Thread.sleep(100) }
        val heap = Debug.getNativeHeapAllocatedSize() - before
        val bytes = bitmap.allocationByteCount.toLong()
        Log.i("BitmapAudit", "$label: ${bitmap.width}x${bitmap.height} ${bitmap.config}, bitmap ${bytes / 1024} KB, native heap +${heap / 1024} KB")
        bitmap.recycle()
        return bytes to heap
    }

    /** The viewer's decode before this change (ImportedImages.decode(data, 2048)). */
    private fun oldViewerDecode(data: ByteArray, maxSide: Int = 2048): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    @Test
    fun aLargePhotoInTheViewer() {
        val data = jpeg(7200, 5400) // 39 MP
        val (old, oldHeap) = measure("viewer, before (inSampleSize ≥ 2048)") { oldViewerDecode(data) }
        val (new, newHeap) = measure("viewer, after (fillWidth 1080)") {
            ScaledBitmaps.decode(data) { w, h -> ImageSizing.fillWidth(w, h, 1080, 4096) }
        }
        assertEquals(3600L * 2700 * 4, old)
        assertTrue("after $new B vs before $old B", new * 5 < old)
        Log.i("BitmapAudit", "viewer: ${old / 1024} KB → ${new / 1024} KB; native heap +${oldHeap / 1024} KB → +${newHeap / 1024} KB")
    }

    @Test
    fun aGuideScreenshot() {
        val png = jpeg(1320, 2868, Bitmap.CompressFormat.PNG)
        val (old, oldHeap) = measure("guide, before (full size)") { BitmapFactory.decodeByteArray(png, 0, png.size) }
        val (new, newHeap) = measure("guide, after (240 dp at 2.625x = 630 px)") {
            ScaledBitmaps.decode(png) { w, h -> ImageSizing.fitWithin(w, h, 630, Int.MAX_VALUE) }
        }
        assertTrue("after $new B vs before $old B", new * 3 < old)
        Log.i("BitmapAudit", "guide: ${old / 1024} KB → ${new / 1024} KB; native heap +${oldHeap / 1024} KB → +${newHeap / 1024} KB")
    }
}
