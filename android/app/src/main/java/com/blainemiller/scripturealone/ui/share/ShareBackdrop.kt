package com.blainemiller.scripturealone.ui.share

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Paints a card's ground — `ShareBackdrop.swift`, from the same recipe: the same hash and noise
 * ([ShareNoise]), the same numbers, in card points (1080 on the long side). Nothing here is an
 * image file; everything is drawn from code.
 *
 * Fine detail (grain, weave) is generated per output pixel and tiled, so the 2× export is as crisp
 * as the preview; the slow parts (noise fields, contour lines) depend only on the ground and the
 * card's shape and are cached.
 */
object ShareBackdrop {

    /** Paints [background] over a card of [width] × [height] points; the canvas maps points at [pixelsPerPoint]. */
    fun draw(background: ShareBackground, canvas: Canvas, width: Float, height: Float, pixelsPerPoint: Float) {
        val seed = ShareNoise.seed(background.raw)
        fillGround(background, canvas, width, height)
        when (val texture = background.texture) {
            ShareTexture.Clean -> if (background.colors.size > 1) tile(grainTile(seed, 0.012f), canvas, width, height, pixelsPerPoint)
            ShareTexture.Paper -> {
                paper(seed, canvas, width, height, pixelsPerPoint, 1f)
                vignette(canvas, width, height, 0.07f)
            }
            ShareTexture.Linen -> {
                mottle(seed, canvas, width, height, 0.5f)
                tile(weaveTile(false, seed, pixelsPerPoint), canvas, width, height, pixelsPerPoint)
                tile(grainTile(seed, 0.025f), canvas, width, height, pixelsPerPoint)
            }
            ShareTexture.Canvas -> {
                mottle(seed, canvas, width, height, 0.8f)
                tile(weaveTile(true, seed, pixelsPerPoint), canvas, width, height, pixelsPerPoint)
                tile(grainTile(seed, 0.035f), canvas, width, height, pixelsPerPoint)
                vignette(canvas, width, height, 0.06f)
            }
            ShareTexture.Grain -> {
                tile(grainTile(seed, 0.10f), canvas, width, height, pixelsPerPoint)
                vignette(canvas, width, height, 0.35f)
            }
            is ShareTexture.Watercolor -> {
                drawField(watercolorField(texture.pigments, seed, width, height), canvas, width, Paint().apply { blendMode = BlendMode.MULTIPLY })
                paper(seed, canvas, width, height, pixelsPerPoint, 0.5f)
            }
            is ShareTexture.Bokeh -> {
                bokeh(texture.lights, seed, canvas, width, height)
                vignette(canvas, width, height, 0.28f)
                tile(grainTile(seed, 0.03f), canvas, width, height, pixelsPerPoint)
            }
            ShareTexture.Glow -> {
                glow(canvas, width, height)
                vignette(canvas, width, height, 0.22f)
                tile(grainTile(seed, 0.06f), canvas, width, height, pixelsPerPoint)
            }
            is ShareTexture.Lattice -> {
                lattice(texture.line, canvas, width, height)
                tile(grainTile(seed, 0.015f), canvas, width, height, pixelsPerPoint)
            }
            is ShareTexture.Contour -> {
                val (thin, thick) = contourPaths(seed, width, height)
                fadedLines(listOf(Triple(thin, 0.8f, 0.3f), Triple(thick, 1.3f, 0.42f)), texture.line, 0.75f, canvas, width, height)
                tile(grainTile(seed, 0.015f), canvas, width, height, pixelsPerPoint)
            }
        }
    }

    /** The ground as a bitmap of [width] × [height] points at [pixelsPerPoint] (cached; call off the main thread). */
    fun bitmap(background: ShareBackground, width: Float, height: Float, pixelsPerPoint: Float): Bitmap {
        val w = max(1, (width * pixelsPerPoint).roundToInt())
        val h = max(1, (height * pixelsPerPoint).roundToInt())
        val key = "${background.raw} ${width.toInt()}x${height.toInt()} ${w}x$h"
        images.get(key)?.let { return it }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(w / width, h / height)
        draw(background, canvas, width, height, w / width)
        images.put(key, bitmap)
        return bitmap
    }

    /** The ground's average and its lightest and darkest ninth-by-ninth patches, measured from the drawing (cached). */
    fun stats(background: ShareBackground): ShareBackdropStats = statsCache.getOrPut(background) {
        val side = 108
        val block = 12
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(side / 1080f, side / 1080f)
        draw(background, canvas, 1080f, 1080f, side / 1080f)
        val pixels = IntArray(side * side)
        bitmap.getPixels(pixels, 0, side, 0, 0, side, side)
        bitmap.recycle()
        var total = Triple(0.0, 0.0, 0.0)
        var light = -1.0 to 0L
        var dark = 2.0 to 0L
        for (by in 0 until side / block) for (bx in 0 until side / block) {
            var r = 0.0
            var g = 0.0
            var b = 0.0
            for (y in by * block until (by + 1) * block) for (x in bx * block until (bx + 1) * block) {
                val c = pixels[y * side + x]
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
            }
            val n = (block * block).toDouble()
            val color = rgb(r / n, g / n, b / n)
            val lum = ShareContrast.luminance(color)
            if (lum > light.first) light = lum to color
            if (lum < dark.first) dark = lum to color
            total = Triple(total.first + r, total.second + g, total.third + b)
        }
        val n = (side * side).toDouble()
        ShareBackdropStats(rgb(total.first / n, total.second / n, total.third / n), light.second, dark.second)
    }

    // Ground

    private fun fillGround(background: ShareBackground, canvas: Canvas, width: Float, height: Float) {
        val colors = background.colors.map { argb(it) }.toIntArray()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (colors.size == 1) {
            paint.color = colors[0]
        } else {
            paint.shader = when (background.gradient) {
                ShareGradient.VERTICAL -> LinearGradient(0f, 0f, 0f, height, colors, null, Shader.TileMode.CLAMP)
                ShareGradient.DIAGONAL -> LinearGradient(0f, 0f, width, height, colors, null, Shader.TileMode.CLAMP)
                ShareGradient.RADIAL -> RadialGradient(width * 0.5f, height * 0.42f, hypot(width, height) * 0.55f, colors, null, Shader.TileMode.CLAMP)
            }
        }
        canvas.drawRect(0f, 0f, width, height, paint)
    }

    private fun vignette(canvas: Canvas, width: Float, height: Float, alpha: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = RadialGradient(
            width / 2, height / 2, hypot(width, height) / 2,
            intArrayOf(argb(0, 0f), argb(0, 0f), argb(0, alpha)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width, height, paint)
    }

    // Tiles

    /** Fills the card with [tile] repeated, one tile pixel per device pixel. */
    private fun tile(tile: Bitmap?, canvas: Canvas, width: Float, height: Float, pixelsPerPoint: Float) {
        tile ?: return
        val shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        shader.setLocalMatrix(Matrix().apply { setScale(1 / pixelsPerPoint, 1 / pixelsPerPoint) })
        canvas.drawRect(0f, 0f, width, height, Paint().apply { this.shader = shader; isFilterBitmap = false })
    }

    /** 256 × 256 pixels of monochrome grain — `grainTile`. */
    fun grainTile(seed: Int, amount: Float): Bitmap = tiles.getOrPut("grain $seed $amount") {
        val side = 256
        val pixels = IntArray(side * side)
        for (y in 0 until side) for (x in 0 until side) {
            val n = ShareNoise.unit(x, y, seed + 101) + ShareNoise.unit(x, y, seed + 202) - 1
            pixels[y * side + x] = signed(n * amount)
        }
        Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
    }

    /** Sixteen threads each way in device pixels: a plain weave (linen) or over-and-under (canvas) — `weaveTile`. */
    fun weaveTile(canvasWeave: Boolean, seed: Int, pixelsPerPoint: Float): Bitmap? {
        val period = if (canvasWeave) 5.0f else 3.0f
        val threads = 16
        val side = max(1, (threads * period * pixelsPerPoint).roundToInt())
        val pitch = side.toFloat() / threads
        val fade = ((pitch - 1.5f) / 1.5f).coerceIn(0f, 1f)
        if (fade <= 0f) return null
        return tiles.getOrPut("weave $canvasWeave $seed $side") {
            val pixels = IntArray(side * side)
            for (y in 0 until side) {
                val v = (y + 0.5f) / pitch
                val j = v.toInt() % threads
                val across = sin(Math.PI.toFloat() * (v - floor(v)))
                val row = ShareNoise.unit(j, 0, seed + 5)
                for (x in 0 until side) {
                    val u = (x + 0.5f) / pitch
                    val i = u.toInt() % threads
                    val along = sin(Math.PI.toFloat() * (u - floor(u)))
                    val column = ShareNoise.unit(i, 1, seed + 5)
                    val d = if (!canvasWeave) {
                        val value = 0.5f * (across * (0.7f + 0.6f * row)) + 0.5f * (along * (0.7f + 0.6f * column))
                        (value - 0.55f) * 0.11f
                    } else {
                        val over = (i + j) % 2 == 0
                        val shade = if (over) across * (0.8f + 0.4f * row) else along * (0.8f + 0.4f * column)
                        val gap = (1 - across) * (1 - along)
                        (shade * 0.85f - gap * 0.4f - 0.45f) * 0.12f
                    }
                    pixels[y * side + x] = signed(d * fade)
                }
            }
            Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
        }
    }

    /** A signed brightness change as white (lighter) or black (darker) at that opacity. */
    private fun signed(d: Float): Int {
        val alpha = (abs(d) * 255).roundToInt().coerceAtMost(255)
        return (alpha shl 24) or (if (d > 0) 0xFFFFFF else 0)
    }

    // Fields (card points, cached per ground and shape)

    private class Grid(val columns: Int, val rows: Int, val step: Float)

    private fun grid(width: Float, height: Float, step: Float): Grid {
        val columns = ceil(width / step).toInt() + 1
        val exact = width / (columns - 1)
        val rows = ceil(height / exact).toInt() + 1
        return Grid(columns, rows, exact)
    }

    /** Pixel (c, r) of [field] is the value at (c, r) × step points, stretched smoothly over the card. */
    private fun drawField(field: Bitmap, canvas: Canvas, width: Float, paint: Paint) {
        val step = width / (field.width - 1)
        paint.isFilterBitmap = true
        canvas.drawBitmap(field, null, RectF(-step / 2, -step / 2, field.width * step - step / 2, field.height * step - step / 2), paint)
    }

    private fun paper(seed: Int, canvas: Canvas, width: Float, height: Float, pixelsPerPoint: Float, mottle: Float) {
        mottle(seed, canvas, width, height, mottle)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.5f
            strokeCap = Paint.Cap.ROUND
        }
        val short = min(width, height) / 1080f
        val s = seed + 9
        for (i in 0 until 80) {
            val x = ShareNoise.unit(i, 0, s) * width
            val y = ShareNoise.unit(i, 1, s) * height
            val angle = ShareNoise.unit(i, 2, s) * 2 * Math.PI.toFloat()
            val length = (8 + 30 * ShareNoise.unit(i, 3, s)) * max(0.6f, short)
            val bend = (ShareNoise.unit(i, 4, s) - 0.5f) * length * 0.6f
            val dx = cos(angle) * length
            val dy = sin(angle) * length
            val path = Path().apply {
                moveTo(x, y)
                quadTo(x + dx / 2 - dy / length * bend, y + dy / 2 + dx / length * bend, x + dx, y + dy)
            }
            stroke.color = if (ShareNoise.unit(i, 5, s) < 0.6f) argb(0x5A4630, 0.07f) else argb(0xFFFFFF, 0.12f)
            canvas.drawPath(path, stroke)
        }
        tile(grainTile(seed, 0.04f), canvas, width, height, pixelsPerPoint)
    }

    private fun mottle(seed: Int, canvas: Canvas, width: Float, height: Float, amount: Float) {
        val field = fields.getOrPut("mottle $seed ${width.toInt()}x${height.toInt()}") {
            val g = grid(width, height, 6f)
            val pixels = IntArray(g.columns * g.rows)
            for (r in 0 until g.rows) for (c in 0 until g.columns) {
                val x = c * g.step
                val y = r * g.step
                val broad = ShareNoise.fbm(x / 110, y / 110, 4, seed + 3)
                val fine = ShareNoise.fbm(x / 22, y / 22, 2, seed + 4)
                pixels[r * g.columns + c] = signed((broad - 0.5f) * 0.10f + (fine - 0.5f) * 0.04f)
            }
            Bitmap.createBitmap(pixels, g.columns, g.rows, Bitmap.Config.ARGB_8888)
        }
        drawField(field, canvas, width, Paint().apply { alpha = (amount * 255).roundToInt() })
    }

    /** Three pigment washes, heavier toward the corners, with darker dried edges; drawn with multiply. */
    fun watercolorField(pigments: List<Long>, seed: Int, width: Float, height: Float): Bitmap =
        fields.getOrPut("wash $seed ${width.toInt()}x${height.toInt()}") {
            val g = grid(width, height, 5f)
            val tints = pigments.map { Triple(((it shr 16) and 0xFF) / 255f, ((it shr 8) and 0xFF) / 255f, (it and 0xFF) / 255f) }
            val halfW = width / 2
            val halfH = height / 2
            val pixels = IntArray(g.columns * g.rows)
            for (r in 0 until g.rows) for (c in 0 until g.columns) {
                val x = c * g.step
                val y = r * g.step
                val dx = (x - halfW) / halfW
                val dy = (y - halfH) / halfH
                val corner = ShareNoise.smoothstep(0.2f, 0.75f, sqrt(dx * dx + dy * dy) / 1.4142f)
                val weight = 0.2f + 0.8f * corner
                var lr = 1f
                var lg = 1f
                var lb = 1f
                tints.forEachIndexed { k, tint ->
                    val s = seed + k * 13
                    val wx = (ShareNoise.fbm(x / 360, y / 360, 2, s + 20) - 0.5f) * 120
                    val wy = (ShareNoise.fbm(x / 360 + 7.3f, y / 360 + 1.9f, 2, s + 40) - 0.5f) * 120
                    val f = ShareNoise.fbm((x + wx) / 420, (y + wy) / 420, 4, s)
                    var a = ShareNoise.smoothstep(0.52f, 0.7f, f) * 0.42f
                    if (f > 0.52f) a += exp(-((f - 0.535f) / 0.02f).pow(2)) * 0.12f
                    a = min(1f, a * weight)
                    lr *= 1 - a * (1 - tint.first)
                    lg *= 1 - a * (1 - tint.second)
                    lb *= 1 - a * (1 - tint.third)
                }
                pixels[r * g.columns + c] = (0xFF shl 24) or ((lr * 255).roundToInt() shl 16) or ((lg * 255).roundToInt() shl 8) or (lb * 255).roundToInt()
            }
            Bitmap.createBitmap(pixels, g.columns, g.rows, Bitmap.Config.ARGB_8888)
        }

    // Light

    private fun bokeh(lights: List<Long>, seed: Int, canvas: Canvas, width: Float, height: Float) {
        val scale = min(width, height) / 1080f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.SCREEN }
        for (i in 0 until 36) {
            val cx = (ShareNoise.unit(i, 0, seed) * 1.1f - 0.05f) * width
            val cy = (ShareNoise.unit(i, 1, seed) * 1.1f - 0.05f) * height
            val u = ShareNoise.unit(i, 2, seed)
            val radius = (16 + 95 * u * u) * scale
            val light = lights[(ShareNoise.unit(i, 3, seed) * lights.size).toInt() % lights.size]
            val alpha = 0.12f + 0.22f * ShareNoise.unit(i, 4, seed)
            paint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(argb(light, alpha * 0.55f), argb(light, alpha * 0.75f), argb(light, alpha), argb(light, 0f)),
                floatArrayOf(0f, 0.8f, 0.93f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, radius, paint)
        }
    }

    private fun glow(canvas: Canvas, width: Float, height: Float) {
        val long = max(width, height)
        val leaks = listOf(
            floatArrayOf(1.02f, -0.05f, 0.95f, 0.7f) to 0xFF8A3DL,
            floatArrayOf(-0.08f, 1.04f, 0.8f, 0.42f) to 0xE8486BL,
            floatArrayOf(0.9f, 1.08f, 0.45f, 0.3f) to 0xFFC46BL,
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.SCREEN }
        for ((v, color) in leaks) {
            paint.shader = RadialGradient(
                v[0] * width, v[1] * height, v[2] * long,
                intArrayOf(argb(color, v[3]), argb(color, v[3] * 0.45f), argb(color, 0f)), floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, width, height, paint)
        }
    }

    // Lines

    private fun lattice(line: Long, canvas: Canvas, width: Float, height: Float) {
        val spacing = 56f
        val reach = hypot(width, height)
        val path = Path()
        for (degrees in listOf(0.0, 60.0, 120.0)) {
            val angle = Math.toRadians(degrees)
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            val nx = -dy
            val ny = dx
            val corners = listOf(0f, width * nx, height * ny, width * nx + height * ny)
            var k = (floor(corners.min() / spacing) + 0.5f) * spacing
            while (k <= corners.max()) {
                val bx = nx * k
                val by = ny * k
                path.moveTo(bx - dx * reach, by - dy * reach)
                path.lineTo(bx + dx * reach, by + dy * reach)
                k += spacing
            }
        }
        fadedLines(listOf(Triple(path, 1.0f, 0.26f)), line, 0.85f, canvas, width, height)
    }

    /** Contour lines of a gentle noise landscape (cached per ground and shape); every fifth is heavier. */
    fun contourPaths(seed: Int, width: Float, height: Float): Pair<Path, Path> =
        contours.getOrPut("$seed ${width.toInt()}x${height.toInt()}") {
            val g = grid(width, height, 6f)
            val field = FloatArray(g.columns * g.rows)
            for (r in 0 until g.rows) for (c in 0 until g.columns) {
                field[r * g.columns + c] = ShareNoise.fbm(c * g.step / 280, r * g.step / 280, 3, seed + 7)
            }
            val low = field.min()
            val high = field.max()
            val levels = 18
            val thin = Path()
            val thick = Path()
            for (level in 1 until levels) {
                val iso = low + (high - low) * level / levels
                marchingSquares(field, g.columns, g.rows, g.step, iso, if (level % 5 == 0) thick else thin)
            }
            thin to thick
        }

    private fun marchingSquares(field: FloatArray, columns: Int, rows: Int, step: Float, iso: Float, path: Path) {
        fun point(c0: Int, r0: Int, c1: Int, r1: Int): Pair<Float, Float> {
            val a = field[r0 * columns + c0]
            val b = field[r1 * columns + c1]
            val t = if (abs(b - a) < 1e-9f) 0.5f else (iso - a) / (b - a)
            return (c0 + (c1 - c0) * t) * step to (r0 + (r1 - r0) * t) * step
        }
        fun segment(a: Pair<Float, Float>, b: Pair<Float, Float>) {
            path.moveTo(a.first, a.second)
            path.lineTo(b.first, b.second)
        }
        for (r in 0 until rows - 1) for (c in 0 until columns - 1) {
            var index = 0
            if (field[r * columns + c] > iso) index = index or 1
            if (field[r * columns + c + 1] > iso) index = index or 2
            if (field[(r + 1) * columns + c + 1] > iso) index = index or 4
            if (field[(r + 1) * columns + c] > iso) index = index or 8
            if (index == 0 || index == 15) continue
            val top = { point(c, r, c + 1, r) }
            val right = { point(c + 1, r, c + 1, r + 1) }
            val bottom = { point(c, r + 1, c + 1, r + 1) }
            val left = { point(c, r, c, r + 1) }
            when (index) {
                1, 14 -> segment(left(), top())
                2, 13 -> segment(top(), right())
                3, 12 -> segment(left(), right())
                4, 11 -> segment(right(), bottom())
                5 -> { segment(left(), top()); segment(right(), bottom()) }
                6, 9 -> segment(top(), bottom())
                7, 8 -> segment(left(), bottom())
                10 -> { segment(top(), right()); segment(left(), bottom()) }
            }
        }
    }

    /** Strokes the paths (path, width, opacity) in [color], then erases them toward the centre. */
    private fun fadedLines(paths: List<Triple<Path, Float, Float>>, color: Long, centerFade: Float, canvas: Canvas, width: Float, height: Float) {
        canvas.save()
        canvas.clipRect(0f, 0f, width, height)
        canvas.saveLayer(0f, 0f, width, height, null)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        for ((path, lineWidth, alpha) in paths) {
            stroke.strokeWidth = lineWidth
            stroke.color = argb(color, alpha)
            canvas.drawPath(path, stroke)
        }
        val erase = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            blendMode = BlendMode.DST_OUT
            shader = RadialGradient(
                width / 2, height / 2, hypot(width, height) * 0.48f,
                intArrayOf(argb(0, centerFade), argb(0, centerFade * 0.6f), argb(0, 0f)), floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width, height, erase)
        canvas.restore()
        canvas.restore()
    }

    // Helpers

    private val images = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val tiles = ConcurrentHashMap<String, Bitmap>()
    private val fields = ConcurrentHashMap<String, Bitmap>()
    private val contours = ConcurrentHashMap<String, Pair<Path, Path>>()
    private val statsCache = ConcurrentHashMap<ShareBackground, ShareBackdropStats>()

    fun argb(rgb: Long, alpha: Float = 1f): Int =
        ((alpha.coerceIn(0f, 1f) * 255).roundToInt() shl 24) or (rgb and 0xFFFFFF).toInt()

    private fun rgb(r: Double, g: Double, b: Double): Long {
        fun c(v: Double): Long = Math.round(v).coerceIn(0, 255)
        return (c(r) shl 16) or (c(g) shl 8) or c(b)
    }
}

/**
 * The hash and value noise both platforms draw with — identical to `ShareBackdrop.swift`'s, so a
 * ground's grain, weave, washes and lines fall in the same places (`ShareStyleTest` pins values
 * printed by the Swift).
 */
object ShareNoise {
    fun hash(x: Int, y: Int, seed: Int): Int {
        var h = seed * 0x9E3779B1L.toInt()
        h = h xor (x * 0x85EBCA77L.toInt())
        h = Integer.rotateLeft(h, 13)
        h = h xor (y * 0xC2B2AE3DL.toInt())
        h = Integer.rotateLeft(h, 17)
        h *= 0x27D4EB2F
        h = h xor (h ushr 15)
        h *= 0x85EBCA77L.toInt()
        h = h xor (h ushr 13)
        return h
    }

    /** The hash as 0 ..< 1. */
    fun unit(x: Int, y: Int, seed: Int): Float = (hash(x, y, seed) ushr 8).toFloat() / 16_777_216f

    /** Smooth value noise, 0 … 1. */
    fun noise(x: Float, y: Float, seed: Int): Float {
        val fx = floor(x)
        val fy = floor(y)
        val xi = fx.toInt()
        val yi = fy.toInt()
        val tx = x - fx
        val ty = y - fy
        val sx = tx * tx * (3 - 2 * tx)
        val sy = ty * ty * (3 - 2 * ty)
        val a = unit(xi, yi, seed)
        val b = unit(xi + 1, yi, seed)
        val c = unit(xi, yi + 1, seed)
        val d = unit(xi + 1, yi + 1, seed)
        return a + (b - a) * sx + (c - a) * sy + (a - b - c + d) * sx * sy
    }

    fun fbm(x: Float, y: Float, octaves: Int, seed: Int): Float {
        var sum = 0f
        var weight = 0.5f
        var total = 0f
        var frequency = 1f
        for (octave in 0 until octaves) {
            sum += weight * noise(x * frequency, y * frequency, seed + octave * 31)
            total += weight
            weight *= 0.5f
            frequency *= 2f
        }
        return sum / total
    }

    /** A ground's seed: a hash of its name. */
    fun seed(raw: String): Int = raw.fold(7) { acc, c -> acc * 31 + c.code }

    fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }
}
