package com.blainemiller.scripturealone.ui.study

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blainemiller.scripturealone.data.context.Basemap
import com.blainemiller.scripturealone.data.context.MapLabel
import com.blainemiller.scripturealone.data.context.Place
import com.blainemiller.scripturealone.data.context.PlaceKind
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.blainemiller.scripturealone.ui.reader.glass
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// The offline Bible map — `BibleMapView.swift` and `MapGeometry.swift`. Natural Earth land, lakes and
// rivers from `Basemap.bin`, drawn on a Canvas (there is no tile provider and no network), with the
// chapter's places, journey routes and priority-placed labels on top. Drag to pan, pinch to zoom.
// Geometry is in points (dp) throughout, as on iOS, so the zoom thresholds carry over unchanged.

/**
 * Equirectangular projection tuned for the Levant: longitude scaled by cos 32°, so shapes look right
 * around Jerusalem. Map units are degrees of latitude; y grows southward, like the screen.
 */
object MapProjection {
    val lonScale: Double = cos(32.0 * Math.PI / 180)

    /** The whole map: 20°W–60°E, 10°N–45°N. */
    val bounds: Rect = rect(minLon = -20.0, minLat = 10.0, maxLon = 60.0, maxLat = 45.0)

    fun point(lon: Double, lat: Double): Offset = Offset((lon * lonScale).toFloat(), (-lat).toFloat())

    fun rect(minLon: Double, minLat: Double, maxLon: Double, maxLat: Double): Rect =
        Rect(point(minLon, maxLat), point(maxLon, minLat))

    /** Smallest rect containing the points, grown to at least [minSpan] degrees each way. */
    fun fit(points: List<Offset>, minSpan: Float = 2.4f): Rect? {
        if (points.isEmpty()) return null
        var r = Rect(points[0], points[0])
        for (p in points.drop(1)) r = Rect(min(r.left, p.x), min(r.top, p.y), max(r.right, p.x), max(r.bottom, p.y))
        val w = max(r.width, minSpan * lonScale.toFloat())
        val h = max(r.height, minSpan)
        return Rect(Offset(r.center.x - w / 2, r.center.y - h / 2), Size(w, h))
    }
}

/** Where the map is looking: the centre (map units) and points per map unit. */
data class MapCamera(val center: Offset, val scale: Float) {

    fun toScreen(p: Offset, size: Size) = Offset((p.x - center.x) * scale + size.width / 2, (p.y - center.y) * scale + size.height / 2)
    fun toMap(p: Offset, size: Size) = Offset((p.x - size.width / 2) / scale + center.x, (p.y - size.height / 2) / scale + center.y)

    /** The map-unit rect visible on screen. */
    fun visibleRect(size: Size): Rect = Rect(toMap(Offset.Zero, size), Size(size.width / scale, size.height / scale))

    /** Keeps the map filling the view and the scale in range. */
    fun clamped(size: Size): MapCamera {
        val s = scale.coerceIn(minimumScale(size), MAXIMUM_SCALE)
        val b = MapProjection.bounds
        val halfW = size.width / 2 / s
        val halfH = size.height / 2 / s
        val x = if (b.width <= halfW * 2) b.center.x else center.x.coerceIn(b.left + halfW, b.right - halfW)
        val y = if (b.height <= halfH * 2) b.center.y else center.y.coerceIn(b.top + halfH, b.bottom - halfH)
        return MapCamera(Offset(x, y), s)
    }

    /** Zooms by [factor], keeping the map point under [anchor] (screen) fixed. */
    fun zoomed(factor: Float, anchor: Offset, size: Size): MapCamera {
        val before = toMap(anchor, size)
        val scaled = copy(scale = (scale * factor).coerceIn(minimumScale(size), MAXIMUM_SCALE))
        val after = scaled.toMap(anchor, size)
        return scaled.copy(center = Offset(scaled.center.x + before.x - after.x, scaled.center.y + before.y - after.y)).clamped(size)
    }

    companion object {
        const val MAXIMUM_SCALE = 2400f

        fun minimumScale(size: Size): Float {
            val b = MapProjection.bounds
            if (size.width <= 0 || size.height <= 0) return 4f
            return max(size.width / b.width, size.height / b.height)
        }

        fun fitting(rect: Rect, size: Size, padding: Float = 36f): MapCamera {
            val w = max(size.width - padding * 2, 40f)
            val h = max(size.height - padding * 2, 40f)
            val s = min(w / max(rect.width, 0.01f), h / max(rect.height, 0.01f))
            return MapCamera(rect.center, s).clamped(size)
        }
    }
}

/** A place drawn prominently on the map (mentioned in the chapter, or a journey stop). */
data class MapPin(
    val id: Int,
    val name: String,
    val point: Offset,
    val kind: PlaceKind,
    val isArea: Boolean,
    val tint: Color? = null,
) {
    companion object {
        fun of(place: Place, tint: Color? = null) = MapPin(
            place.id, place.name, MapProjection.point(place.longitude, place.latitude), place.kind, place.isArea, tint,
        )
    }
}

data class MapRoute(val id: String, val points: List<Offset>, val color: Color)

/** A large area label, e.g. a tribe's allotment. */
data class MapTag(val text: String, val point: Offset)

data class MapContent(
    val pins: List<MapPin> = emptyList(),
    val routes: List<MapRoute> = emptyList(),
    val tags: List<MapTag> = emptyList(),
    /** Draw the most-mentioned places faintly for orientation. */
    val showsBackgroundPlaces: Boolean = true,
    val selectedId: Int? = null,
    /** The region to show first (map units); the whole map when null. */
    val fitRect: Rect? = null,
) {
    companion object {
        fun fitRect(pins: List<MapPin>, extra: List<Offset> = emptyList()): Rect? = MapProjection.fit(pins.map { it.point } + extra)
    }
}

/** Map colours, tuned for legibility on a light and a dark page — `MapPalette`. */
class MapPalette(
    val sea: Color, val land: Color, val coast: Color, val water: Color, val river: Color, val graticule: Color,
    val ink: Color, val secondaryInk: Color, val waterInk: Color, val halo: Color, val pin: Color, val pinOutline: Color,
    val background: Color,
) {
    companion object {
        private fun hex(value: Long) = Color(0xFF000000 or value)

        fun of(dark: Boolean): MapPalette = if (dark) {
            MapPalette(hex(0x101C26), hex(0x2A2B2A), hex(0x4D6475), hex(0x15283A), hex(0x4F86AD), Color.White.copy(alpha = 0.05f),
                hex(0xECE7DD), hex(0xA8A193), hex(0x7FAED0), hex(0x1A1B1A).copy(alpha = 0.9f), hex(0xFF7A5C), hex(0x1A1B1A), hex(0x6E6A61))
        } else {
            MapPalette(hex(0xCFE2EC), hex(0xF4EFE3), hex(0x8FAEC0), hex(0xBCD7E6), hex(0x6C9FC2), Color.Black.copy(alpha = 0.05f),
                hex(0x2B2620), hex(0x7A6F60), hex(0x3F6F92), hex(0xF4EFE3).copy(alpha = 0.95f), hex(0xC0392B), Color.White, hex(0x8A8172))
        }
    }
}

/** Base-map geometry as paths in map units, built once per base map — `BasemapPaths`. */
class BasemapPaths(map: Basemap) {
    val landCoarse = polygons(map, Basemap.Kind.LAND, Basemap.Detail.COARSE)
    val landFine = polygons(map, Basemap.Kind.LAND, Basemap.Detail.FINE)
    val lakesCoarse = polygons(map, Basemap.Kind.LAKE, Basemap.Detail.COARSE)
    val lakesFine = polygons(map, Basemap.Kind.LAKE, Basemap.Detail.FINE)
    val riversCoarse = lines(map, Basemap.Detail.COARSE, 0..7)
    /** Major rivers (Natural Earth rank ≤ 6) and the rest, so minor ones appear only up close. */
    val riversMajorFine = lines(map, Basemap.Detail.FINE, 0..6)
    val riversMinorFine = lines(map, Basemap.Detail.FINE, 7..255)

    private companion object {
        fun polygons(map: Basemap, kind: Basemap.Kind, detail: Basemap.Detail) = Path().apply {
            fillType = PathFillType.EvenOdd
            for (ring in map.layer(kind, detail)?.rings.orEmpty()) {
                ring.points.forEachIndexed { i, p ->
                    val q = MapProjection.point(p.x, p.y)
                    if (i == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y)
                }
                close()
            }
        }

        fun lines(map: Basemap, detail: Basemap.Detail, ranks: IntRange) = Path().apply {
            for (ring in map.layer(Basemap.Kind.RIVER, detail)?.rings.orEmpty()) {
                if (ring.rank !in ranks) continue
                ring.points.forEachIndexed { i, p ->
                    val q = MapProjection.point(p.x, p.y)
                    if (i == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y)
                }
            }
        }
    }
}

/** What the map draws from beyond its own content: the base map, the authored labels, the prominent places. */
class MapLibrary(val paths: BasemapPaths?, val labels: List<MapLabel>, val prominentPlaces: List<Place>)

/**
 * The map view. [fitToken] re-frames the map on [MapContent.fitRect] when it changes; until the reader
 * pans or zooms, a resize re-frames too.
 */
@Composable
fun BibleMap(
    content: MapContent,
    library: MapLibrary,
    palette: ReaderPalette,
    modifier: Modifier = Modifier,
    fitToken: Any? = null,
    showsControls: Boolean = true,
    onSelect: ((Int) -> Unit)? = null,
) {
    val colors = remember(palette.isDark) { MapPalette.of(palette.isDark) }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 256)
    var sizeDp by remember { mutableStateOf(Size.Zero) }
    var camera by remember { mutableStateOf<MapCamera?>(null) }
    var userMoved by remember { mutableStateOf(false) }
    val animation = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val currentContent by rememberUpdatedState(content)
    val select by rememberUpdatedState(onSelect)

    fun fit(animated: Boolean) {
        val size = sizeDp
        if (size.width <= 0 || size.height <= 0) return
        userMoved = false
        val target = MapCamera.fitting(currentContent.fitRect ?: MapProjection.bounds, size)
        val from = camera
        if (!animated || from == null) {
            camera = target
            return
        }
        scope.launch {
            animation.snapTo(0f)
            animation.animateTo(1f, tween(320)) {
                val t = value
                // Interpolate the scale geometrically, so a big zoom doesn't rush its first half.
                val s = from.scale * Math.pow((target.scale / from.scale).toDouble(), t.toDouble()).toFloat()
                camera = MapCamera(Offset(from.center.x + (target.center.x - from.center.x) * t, from.center.y + (target.center.y - from.center.y) * t), s)
            }
        }
    }

    LaunchedEffect(fitToken) { if (camera != null) fit(animated = true) }

    val summary = if (content.pins.isEmpty()) {
        "Map of the lands of the Bible."
    } else {
        val names = content.pins.take(12).joinToString(", ") { it.name }
        "Map showing $names${if (content.pins.size > 12) ", and ${content.pins.size - 12} more" else ""}."
    }

    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { px ->
                val size = with(density) { Size(px.width.toDp().value, px.height.toDp().value) }
                sizeDp = size
                val c = camera
                if (c == null || !userMoved) fit(animated = false) else camera = c.clamped(size)
            }
            .semantics { contentDescription = summary },
    ) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val c = camera ?: return@detectTransformGestures
                        userMoved = true
                        val anchor = Offset(centroid.x / density.density, centroid.y / density.density)
                        val moved = c.copy(center = Offset(c.center.x - pan.x / density.density / c.scale, c.center.y - pan.y / density.density / c.scale))
                        camera = if (zoom != 1f) moved.zoomed(zoom, anchor, sizeDp) else moved.clamped(sizeDp)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { tap ->
                        val c = camera ?: return@detectTapGestures
                        val location = Offset(tap.x / density.density, tap.y / density.density)
                        val renderer = MapRenderer(currentContent, c, sizeDp, colors, library, measurer, density)
                        renderer.markers()
                            .map { it to hypot(it.screen.x - location.x, it.screen.y - location.y) }
                            .filter { (m, d) -> d < if (m.emphasized) 26f else 16f }
                            .minWithOrNull(compareBy({ if (it.first.emphasized) 0 else 1 }, { it.second }))
                            ?.let { select?.invoke(it.first.id) }
                    }
                },
        ) {
            val c = camera
            if (c == null) {
                drawRect(colors.sea)
            } else {
                // Everything is laid out in dp, as on iOS; one scale maps it onto the pixels.
                withTransform({ scale(density.density, density.density, Offset.Zero) }) {
                    MapRenderer(content, c, sizeDp, colors, library, measurer, density).draw(this)
                }
            }
        }
        if (showsControls) {
            Column(Modifier.align(Alignment.BottomEnd).padding(10.dp)) {
                MapControl(Icons.Rounded.Add, "Zoom In", palette) {
                    camera?.let { userMoved = true; camera = it.zoomed(1.8f, sizeDp.center, sizeDp) }
                }
                Box(Modifier.size(8.dp))
                MapControl(Icons.Rounded.Remove, "Zoom Out", palette) {
                    camera?.let { userMoved = true; camera = it.zoomed(1 / 1.8f, sizeDp.center, sizeDp) }
                }
                Box(Modifier.size(8.dp))
                MapControl(Icons.Rounded.CenterFocusStrong, "Show All Places", palette) { fit(animated = true) }
            }
        }
    }
}

private val Size.center: Offset get() = Offset(width / 2, height / 2)

@Composable
private fun MapControl(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, palette: ReaderPalette, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).glass(palette, CircleShape, palette.page, lifted = true).clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = palette.ink, modifier = Modifier.size(20.dp))
    }
}

/** Draws one frame of the map. A pure function of its inputs, so hit-testing reuses it. */
private class MapRenderer(
    val content: MapContent,
    val camera: MapCamera,
    val viewSize: Size,
    val colors: MapPalette,
    val library: MapLibrary,
    val measurer: TextMeasurer,
    val density: Density,
) {
    class Marker(
        val id: Int, val name: String, val screen: Offset, val emphasized: Boolean, val kind: PlaceKind,
        val isArea: Boolean, val tint: Color?,
    )

    /** Places drawn at this zoom, emphasized first, then background places by prominence. */
    fun markers(): List<Marker> {
        val visible = camera.visibleRect(viewSize).inflate(40 / camera.scale)
        val result = mutableListOf<Marker>()
        val seen = mutableSetOf<Int>()
        val emphasizedPoints = mutableListOf<Offset>()
        for (pin in content.pins) {
            if (!seen.add(pin.id)) continue
            val screen = camera.toScreen(pin.point, viewSize)
            // Two names for one site (Jerusalem and Zion) share a marker.
            if (emphasizedPoints.any { hypot(it.x - screen.x, it.y - screen.y) < 3 }) continue
            emphasizedPoints += screen
            result += Marker(pin.id, pin.name, screen, true, pin.kind, pin.isArea, pin.tint)
        }
        if (!content.showsBackgroundPlaces) return result
        val threshold = when {
            camera.scale < 12 -> 150
            camera.scale < 30 -> 50
            camera.scale < 70 -> 16
            camera.scale < 160 -> 6
            camera.scale < 400 -> 2
            else -> 1
        }
        var count = 0
        for (place in library.prominentPlaces) {
            if (place.mentions < threshold || place.id in seen) continue
            val point = MapProjection.point(place.longitude, place.latitude)
            if (!visible.contains(point)) continue
            val screen = camera.toScreen(point, viewSize)
            if (emphasizedPoints.any { hypot(it.x - screen.x, it.y - screen.y) < 3 }) continue
            seen += place.id
            result += Marker(place.id, place.name, screen, false, place.kind, place.isArea, null)
            count++
            if (count >= 220) break
        }
        return result
    }

    fun draw(scope: DrawScope) = with(scope) {
        val bounds = Rect(Offset.Zero, viewSize)
        drawRect(colors.sea, Offset.Zero, viewSize)
        drawBasemap()
        drawRoutes()

        val markers = markers()
        val occupied = mutableListOf<Rect>()
        // Background dots first so emphasized pins sit on top.
        for (m in markers) {
            if (m.emphasized || m.isArea) continue
            drawCircle(if (m.kind == PlaceKind.WATER) colors.river else colors.background, 2.2f, m.screen)
        }
        for (m in markers) if (m.emphasized) drawPin(m)?.let { occupied += it }

        // Labels, most important first; anything that would collide is skipped. A chapter's own place
        // names may, as a last resort, overlap a dot — but never each other.
        val labels = mutableListOf<Pair<TextLayoutResult, Offset>>()
        val strong = mutableListOf<Rect>()
        fun place(layout: TextLayoutResult, near: Offset, gap: Float, centered: Boolean, isStrong: Boolean = false) {
            val w = layout.size.width.toFloat()
            val h = layout.size.height.toFloat()
            val d = gap * 0.7f
            val candidates = if (centered) {
                listOf(Offset(near.x - w / 2, near.y - h / 2), Offset(near.x - w / 2, near.y + 14), Offset(near.x - w / 2, near.y - 14 - h),
                    Offset(near.x - w * 0.15f, near.y - h / 2), Offset(near.x - w * 0.85f, near.y - h / 2))
            } else {
                listOf(Offset(near.x + gap, near.y - h / 2), Offset(near.x - gap - w, near.y - h / 2), Offset(near.x - w / 2, near.y - gap - h),
                    Offset(near.x - w / 2, near.y + gap), Offset(near.x + d, near.y - d - h), Offset(near.x + d, near.y + d),
                    Offset(near.x - d - w, near.y - d - h), Offset(near.x - d - w, near.y + d))
            }
            val area = Rect(bounds.left - w / 3, bounds.top - 4, bounds.right + w / 3, bounds.bottom + 4)
            for (lenient in if (isStrong) listOf(false, true) else listOf(false)) {
                for (origin in candidates) {
                    val rect = Rect(origin, Size(w, h))
                    val padded = rect.inflate(2f).let { Rect(it.left, rect.top - 1, it.right, rect.bottom + 1) }
                    val blockers = if (lenient) strong else occupied
                    if (!area.containsRect(rect) || blockers.any { it.overlaps(padded) }) continue
                    occupied += padded
                    if (isStrong) strong += padded
                    labels += layout to origin
                    return
                }
            }
        }

        for (m in markers) if (m.emphasized) {
            place(label(m, m.id == content.selectedId), m.screen, if (m.isArea) 0f else 8f, m.isArea, isStrong = true)
        }
        for (tag in content.tags) {
            val layout = measure(tag.text.uppercase(), TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp, color = colors.secondaryInk))
            place(layout, camera.toScreen(tag.point, viewSize), 0f, centered = true, isStrong = true)
        }
        drawAuthoredLabels(occupied)
        for (m in markers) if (!m.emphasized) {
            place(label(m, m.id == content.selectedId), m.screen, if (m.isArea) 0f else 5f, m.isArea)
        }
        for ((layout, origin) in labels) drawText(layout, topLeft = origin)
    }

    private fun Rect.containsRect(r: Rect) = r.left >= left && r.top >= top && r.right <= right && r.bottom <= bottom

    /** Text laid out at dp scale, with the soft halo iOS draws behind map labels. */
    private fun measure(text: String, style: TextStyle): TextLayoutResult {
        val halo = style.copy(shadow = Shadow(colors.halo, Offset.Zero, 3.2f))
        // Measured in the canvas's dp space: text sizes are divided by the density the canvas
        // scales back up by, so an 11 sp label is 11 sp on screen.
        return measurer.measure(
            text, halo,
            constraints = Constraints(maxWidth = 320),
            density = Density(1f, density.fontScale),
        )
    }

    private fun label(m: Marker, selected: Boolean): TextLayoutResult {
        val name = if (m.isArea) m.name.uppercase() else m.name
        val style = if (m.emphasized) {
            TextStyle(
                fontSize = if (m.isArea) 12.sp else 15.sp, fontWeight = FontWeight.SemiBold,
                color = if (selected) colors.pin else if (m.kind == PlaceKind.WATER) colors.waterInk else colors.ink,
            )
        } else {
            TextStyle(fontSize = if (m.isArea) 11.sp else 12.sp, color = if (m.kind == PlaceKind.WATER) colors.waterInk else colors.secondaryInk)
        }.let { s -> if (m.isArea) s.copy(letterSpacing = 1.2.sp) else s }
            .let { s -> if (m.kind == PlaceKind.WATER) s.copy(fontStyle = FontStyle.Italic) else s }
        return measure(name, style)
    }

    /** The rect the pin occupies, so labels avoid it. */
    private fun DrawScope.drawPin(m: Marker): Rect? {
        val color = m.tint ?: if (m.kind == PlaceKind.WATER) colors.river else colors.pin
        val p = m.screen
        if (m.id == content.selectedId) drawCircle(color, 11f, p, style = Stroke(2f))
        if (m.isArea) {
            drawCircle(color.copy(alpha = 0.12f), 14f, p)
            drawCircle(color.copy(alpha = 0.55f), 14f, p, style = Stroke(1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))))
            return null // Areas are labelled across their middle.
        }
        drawCircle(color, 5.5f, p)
        drawCircle(colors.pinOutline, 5.5f, p, style = Stroke(1.6f))
        return Rect(p.x - 6.5f, p.y - 6.5f, p.x + 6.5f, p.y + 6.5f)
    }

    private fun DrawScope.drawBasemap() {
        val paths = library.paths ?: return
        val unit = 1 / camera.scale
        val fine = camera.scale >= 45
        withTransform({
            translate(viewSize.width / 2, viewSize.height / 2)
            scale(camera.scale, camera.scale, Offset.Zero)
            translate(-camera.center.x, -camera.center.y)
        }) {
            // Graticule every 5° (1° up close), faint.
            val step = if (camera.scale > 180) 1.0 else 5.0
            val visible = camera.visibleRect(viewSize)
            val grid = Path()
            var lon = floor(visible.left / MapProjection.lonScale / step) * step
            while (lon * MapProjection.lonScale <= visible.right) {
                val x = (lon * MapProjection.lonScale).toFloat()
                grid.moveTo(x, visible.top); grid.lineTo(x, visible.bottom)
                lon += step
            }
            var lat = floor(-visible.bottom / step) * step
            while (-lat >= visible.top) {
                grid.moveTo(visible.left, (-lat).toFloat()); grid.lineTo(visible.right, (-lat).toFloat())
                lat += step
            }
            drawPath(grid, colors.graticule, style = Stroke(unit * 0.8f))

            val land = if (fine) paths.landFine else paths.landCoarse
            drawPath(land, colors.land)
            drawPath(land, colors.coast, style = Stroke(unit * 0.9f, join = StrokeJoin.Round))
            if (fine) {
                drawPath(paths.riversMajorFine, colors.river,
                    style = Stroke(unit * if (camera.scale > 150) 1.8f else 1.3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                if (camera.scale > 110) {
                    drawPath(paths.riversMinorFine, colors.river.copy(alpha = 0.8f), style = Stroke(unit * 0.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            } else {
                drawPath(paths.riversCoarse, colors.river, style = Stroke(unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            val lakes = if (fine) paths.lakesFine else paths.lakesCoarse
            drawPath(lakes, colors.water)
            drawPath(lakes, colors.coast, style = Stroke(unit * 0.7f))
        }
    }

    private fun DrawScope.drawRoutes() {
        for (route in content.routes) {
            if (route.points.size < 2) continue
            val screen = route.points.map { camera.toScreen(it, viewSize) }
            val path = Path().apply {
                moveTo(screen[0].x, screen[0].y)
                for (p in screen.drop(1)) lineTo(p.x, p.y)
            }
            drawPath(path, colors.halo, style = Stroke(6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, route.color, style = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            // Direction arrows midway along longer legs.
            for ((a, b) in screen.zipWithNext()) {
                val dx = b.x - a.x
                val dy = b.y - a.y
                val length = hypot(dx, dy)
                if (length <= 44) continue
                val mid = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
                val ux = dx / length
                val uy = dy / length
                val arrow = Path().apply {
                    moveTo(mid.x + ux * 6, mid.y + uy * 6)
                    lineTo(mid.x - ux * 5 - uy * 5, mid.y - uy * 5 + ux * 5)
                    lineTo(mid.x - ux * 5 + uy * 5, mid.y - uy * 5 - ux * 5)
                    close()
                }
                drawPath(arrow, route.color)
            }
        }
    }

    private fun DrawScope.drawAuthoredLabels(occupied: MutableList<Rect>) {
        val bounds = Rect(-60f, -20f, viewSize.width + 60, viewSize.height + 20)
        for (label in library.labels) {
            if (camera.scale < label.minimumScale) continue
            // Close-up labels (Dead Sea, Jordan) would clutter the overview and vice versa.
            if (label.minimumScale == 0.0 && camera.scale > 90) continue
            val point = camera.toScreen(MapProjection.point(label.longitude, label.latitude), viewSize)
            if (!bounds.contains(point)) continue
            val color = if (label.kind == MapLabel.Kind.LAND) colors.secondaryInk.copy(alpha = 0.75f) else colors.waterInk
            val land = label.kind == MapLabel.Kind.LAND
            val layout = measure(
                if (land) label.text.uppercase() else label.text,
                TextStyle(
                    fontSize = if (label.kind == MapLabel.Kind.SEA) 16.sp else 12.sp, color = color,
                    letterSpacing = if (land) 2.sp else 0.sp, fontStyle = if (land) FontStyle.Normal else FontStyle.Italic,
                ),
            )
            val w0 = layout.size.width.toFloat()
            val h0 = layout.size.height.toFloat()
            val radians = -label.angle * Math.PI / 180
            val w = (abs(w0 * cos(radians)) + abs(h0 * sin(radians))).toFloat()
            val h = (abs(w0 * sin(radians)) + abs(h0 * cos(radians))).toFloat()
            val rect = Rect(point.x - w / 2, point.y - h / 2, point.x + w / 2, point.y + h / 2)
            if (occupied.any { it.overlaps(rect) }) continue
            occupied += rect
            rotate((-label.angle).toFloat(), pivot = point) {
                drawText(layout, topLeft = Offset(point.x - w0 / 2, point.y - h0 / 2))
                val sub = label.subtitle
                if (sub != null && camera.scale > 40) {
                    val subtitle = measure(sub, TextStyle(fontSize = 11.sp, fontStyle = FontStyle.Italic, color = color.copy(alpha = 0.8f)))
                    val sw = subtitle.size.width.toFloat()
                    drawText(subtitle, topLeft = Offset(point.x - sw / 2, point.y - h0 / 2 + h0 * 0.85f))
                }
            }
        }
    }
}

