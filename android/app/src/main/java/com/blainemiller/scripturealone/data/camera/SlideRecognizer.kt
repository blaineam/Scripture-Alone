package com.blainemiller.scripturealone.data.camera

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.util.Log
import com.blainemiller.scripturealone.data.slides.SlideLine
import com.blainemiller.scripturealone.data.slides.SlideParser
import com.blainemiller.scripturealone.data.slides.SlideReading
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device text recognition for a photographed slide — `ScriptureAlone/Camera/SlideRecognizer.swift`.
 * ML Kit's Latin recognizer, with its model bundled in the APK: nothing leaves the device, nothing is
 * downloaded, and the image is never written anywhere by this type.
 */
object SlideRecognizer {

    private const val TAG = "SlideRecognizer"

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Every line of text on [bitmap], which must already be upright. */
    suspend fun page(bitmap: Bitmap): RecognizedPage {
        val text = suspendCancellableCoroutine { continuation ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }
        return page(text, bitmap.width, bitmap.height)
    }

    /** ML Kit's blocks and lines, flattened — Vision's observations are lines too. */
    fun page(text: Text, width: Int, height: Int): RecognizedPage = RecognizedPage(
        width, height,
        text.textBlocks.flatMap { block ->
            block.lines.map { line ->
                val corners = line.cornerPoints?.map { RecognizedLine.Point(it.x.toFloat(), it.y.toFloat()) }
                    ?: line.boundingBox?.let { box ->
                        listOf(box.left to box.top, box.right to box.top, box.right to box.bottom, box.left to box.bottom)
                            .map { (x, y) -> RecognizedLine.Point(x.toFloat(), y.toFloat()) }
                    }
                    ?: emptyList()
                RecognizedLine(line.text, corners, line.confidence)
            }
        },
    )

    /** Recognizes and reads the slide in one step. */
    suspend fun read(context: Context, bitmap: Bitmap): Pair<List<SlideLine>, SlideReading> {
        val page = page(bitmap)
        // A debug build records what the recognizer saw, which is how the tests' fixtures were made.
        // Only on the device's own log, and never in a release build.
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) Log.d(TAG, page.toJson())
        val lines = SlideLineMapper.lines(page)
        return lines to SlideParser.read(lines)
    }
}
