package com.blainemiller.scripturealone.data.camera

import com.blainemiller.scripturealone.data.slides.SlideLine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.hypot

/**
 * What the text recognizer saw on one image, in the image's own pixels — ML Kit's `Text` reduced to
 * the parts a slide needs, with no Android or ML Kit types, so the mapping onto [SlideLine] is tested
 * on the JVM against recorded recognizer output (`src/test/resources/slides/`).
 */
data class RecognizedPage(val width: Int, val height: Int, val lines: List<RecognizedLine>) {

    /** The page as JSON — how a recognizer run is recorded for the tests. */
    fun toJson(): String = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("width", width)
        put("height", height)
        put("lines", buildJsonArray {
            for (line in lines) add(buildJsonObject {
                put("text", line.text)
                put("corners", buildJsonArray { line.corners.forEach { add(buildJsonArray { add(JsonPrimitive(it.x)); add(JsonPrimitive(it.y)) }) } })
                put("confidence", line.confidence?.let(::JsonPrimitive) ?: JsonNull)
            })
        })
    })

    companion object {
        fun fromJson(json: String): RecognizedPage {
            val root = Json.parseToJsonElement(json).jsonObject
            return RecognizedPage(
                root.getValue("width").jsonPrimitive.int,
                root.getValue("height").jsonPrimitive.int,
                root.getValue("lines").jsonArray.map { element ->
                    val line = element.jsonObject
                    RecognizedLine(
                        line.getValue("text").jsonPrimitive.content,
                        (line["corners"] as? JsonArray).orEmpty().map { point ->
                            val xy = point.jsonArray
                            RecognizedLine.Point(xy[0].jsonPrimitive.float, xy[1].jsonPrimitive.float)
                        },
                        line["confidence"]?.jsonPrimitive?.floatOrNull,
                    )
                },
            )
        }
    }
}

/**
 * One line of recognized text. [corners] are the line's quadrilateral, clockwise from the top-left of
 * the text as read (ML Kit's `cornerPoints`), so a slide shot at an angle still has its letters'
 * own height. [confidence] is the recognizer's, 0–1, or null when it gave none.
 */
data class RecognizedLine(val text: String, val corners: List<Point>, val confidence: Float?) {
    data class Point(val x: Float, val y: Float)
}

/** [RecognizedPage] → the parser's [SlideLine]s — the second half of `SlideRecognizer.lines(in:)`. */
object SlideLineMapper {

    fun lines(page: RecognizedPage): List<SlideLine> {
        val width = page.width.coerceAtLeast(1).toDouble()
        val height = page.height.coerceAtLeast(1).toDouble()
        return page.lines.mapNotNull { line ->
            val text = BookNameRepair.repair(line.text).trim()
            if (text.isEmpty() || line.corners.size != 4) return@mapNotNull null
            val (topLeft, topRight, bottomRight, bottomLeft) = line.corners
            fun distance(a: RecognizedLine.Point, b: RecognizedLine.Point) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
            // As on iOS: the box is the axis-aligned extent, but the height is the letters' own, measured
            // along the quadrilateral's sides.
            val letterHeight = (distance(topLeft, bottomLeft) + distance(topRight, bottomRight)) / 2
            val minX = line.corners.minOf { it.x } / width
            val maxX = line.corners.maxOf { it.x } / width
            val minY = line.corners.minOf { it.y } / height
            val x = minX.coerceIn(0.0, 1.0)
            SlideLine(
                text,
                x = x,
                y = minY.coerceIn(0.0, 1.0),
                width = (maxX.coerceIn(0.0, 1.0) - x).coerceAtLeast(0.0),
                height = (letterHeight / height).coerceIn(0.0, 1.0),
                confidence = confidence(line.confidence),
            )
        }
    }

    /**
     * ML Kit's line confidence, which runs a little lower than Vision's on clean text but on the same
     * 0–1 scale, so the parser's 0.3 floor means the same thing. A recognizer that reports none (0 or
     * NaN) is trusted, as a line with no score was on iOS.
     */
    internal fun confidence(raw: Float?): Double =
        if (raw == null || raw.isNaN() || raw <= 0f) 1.0 else raw.toDouble().coerceIn(0.0, 1.0)
}
