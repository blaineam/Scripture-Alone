package com.blainemiller.scripturealone.ui.camera

import androidx.compose.ui.semantics.Role
import com.blainemiller.scripturealone.ui.reader.takesTaps
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.data.camera.LumaGrid
import com.blainemiller.scripturealone.data.camera.ScreenFinder
import com.blainemiller.scripturealone.data.camera.SlideImage
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.NumberFormat
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

private enum class CameraAccess { GRANTED, ASKING, DENIED }

/**
 * The camera, full screen — `SlideCameraView.swift`. A live scanner: ML Kit reads the preview as it
 * runs and the text it finds lights up, as VisionKit's scanner highlights it; tapping any of it, or
 * the shutter, takes the picture. Falls back to the system camera where CameraX finds no back camera.
 *
 * Camera access is asked for here, the first time; refused, the screen says how to turn it on — or to
 * choose a photo instead — as iOS's "Camera Access Is Off" does.
 */
@Composable
internal fun SlideScanner(palette: ReaderPalette, onCapture: (Bitmap) -> Unit, onChoosePhoto: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(if (hasCameraPermission(context)) CameraAccess.GRANTED else CameraAccess.ASKING) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        access = if (granted) CameraAccess.GRANTED else CameraAccess.DENIED
    }
    LaunchedEffect(Unit) { if (access == CameraAccess.ASKING) ask.launch(Manifest.permission.CAMERA) }
    // Back from Settings with access turned on: straight into the camera.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (access == CameraAccess.DENIED && hasCameraPermission(context)) access = CameraAccess.GRANTED
    }
    BackHandler(onBack = onCancel)

    Box(Modifier.fillMaxSize().background(Color.Black).takesTaps()) {
        when (access) {
            CameraAccess.GRANTED -> LiveScanner(onCapture, onCancel)
            CameraAccess.ASKING -> Unit
            CameraAccess.DENIED -> CameraDenied(palette, onChoosePhoto, onCancel)
        }
    }
}

private fun hasCameraPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** A recognized line in the preview's own coordinates, for highlighting and tapping. */
private class Highlight(val corners: List<Offset>) {
    fun contains(point: Offset, slop: Float): Boolean {
        val xs = corners.map { it.x }
        val ys = corners.map { it.y }
        return point.x in (xs.min() - slop)..(xs.max() + slop) && point.y in (ys.min() - slop)..(ys.max() + slop)
    }
}

@Composable
private fun LiveScanner(onCapture: (Bitmap) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var highlights by remember { mutableStateOf<List<Highlight>>(emptyList()) }
    var capturing by remember { mutableStateOf(false) }
    var useSystemCamera by remember { mutableStateOf(false) }
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
            // Pinching is handled over the preview, where the taps on text are.
            isPinchToZoomEnabled = false
        }
    }
    // The screen the slide is on, in the preview's coordinates, eased from frame to frame.
    var screen by remember { mutableStateOf<List<Offset>?>(null) }
    val screenMisses = remember { intArrayOf(0) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var minZoom by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(1f) }

    fun setZoom(ratio: Float) {
        val clamped = ratio.coerceIn(minZoom, max(minZoom, maxZoom))
        zoom = clamped
        controller.setZoomRatio(clamped)
    }

    fun sawScreen(found: List<Offset>?) {
        val last = screen
        if (found == null) {
            // A frame or two without it (a hand, a blur) keeps the outline up.
            if (++screenMisses[0] > 6) screen = null
            return
        }
        screenMisses[0] = 0
        screen = if (last == null) found else last.zip(found) { a, b -> a + (b - a) * 0.4f }
    }

    DisposableEffect(lifecycleOwner) {
        val analysis = Executors.newSingleThreadExecutor()
        val text = MlKitAnalyzer(listOf(recognizer), ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED, mainExecutor) { result ->
            val found = result.getValue(recognizer) ?: return@MlKitAnalyzer
            highlights = found.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                // The bounding box, not the corner points: MlKitAnalyzer maps the box into the
                // preview's coordinates but leaves the corners in the rotated frame's (seen on the
                // emulator: every line drawn as a vertical bar).
                line.boundingBox?.let { box ->
                    Highlight(listOf(Offset(box.left.toFloat(), box.top.toFloat()), Offset(box.right.toFloat(), box.top.toFloat()), Offset(box.right.toFloat(), box.bottom.toFloat()), Offset(box.left.toFloat(), box.bottom.toFloat())))
                }
            }
        }
        controller.setImageAnalysisAnalyzer(analysis, ScreenAndTextAnalyzer(text) { found -> mainExecutor.execute { sawScreen(found) } })
        controller.zoomState.observe(lifecycleOwner) { state ->
            zoom = state.zoomRatio
            minZoom = state.minZoomRatio
            maxZoom = state.maxZoomRatio
        }
        controller.bindToLifecycle(lifecycleOwner)
        controller.initializationFuture.addListener({
            if (!controller.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) useSystemCamera = true
        }, mainExecutor)
        onDispose {
            controller.zoomState.removeObservers(lifecycleOwner)
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            analysis.shutdown()
            recognizer.close()
        }
    }

    if (useSystemCamera) {
        SystemCamera(onCapture, onCancel)
        return
    }

    fun capture() {
        if (capturing) return
        capturing = true
        controller.takePicture(mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                scope.launch {
                    val upright = withContext(Dispatchers.Default) {
                        runCatching { image.use { SlideImage.upright(it.toBitmap(), rotation) } }.getOrNull()
                    }
                    capturing = false
                    if (upright != null) onCapture(upright)
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.w("SlideScanner", "Capture failed", exception)
                capturing = false
            }
        })
    }

    Box(Modifier.fillMaxSize().onSizeChanged { viewSize = it }) {
        AndroidView(
            factory = { PreviewView(it).apply { this.controller = controller; scaleType = PreviewView.ScaleType.FILL_CENTER } },
            modifier = Modifier.fillMaxSize(),
        )
        val slop = with(density) { 12.dp.toPx() }
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { point -> if (highlights.any { it.contains(point, slop) }) capture() }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, scale, _ -> if (scale != 1f) setZoom(zoom * scale) }
                },
        ) {
            screen?.let { corners ->
                val outline = Path().apply {
                    moveTo(corners[0].x, corners[0].y)
                    for (corner in corners.drop(1)) lineTo(corner.x, corner.y)
                    close()
                }
                drawPath(outline, Color.White.copy(alpha = 0.9f), style = Stroke(width = 3.dp.toPx(), join = StrokeJoin.Round))
            }
            for (highlight in highlights) {
                val path = Path().apply {
                    moveTo(highlight.corners[0].x, highlight.corners[0].y)
                    for (corner in highlight.corners.drop(1)) lineTo(corner.x, corner.y)
                    close()
                }
                drawPath(path, Color.White.copy(alpha = 0.22f))
                drawPath(path, Color(0xFFFFD60A).copy(alpha = 0.9f), style = Stroke(width = 2.dp.toPx(), join = StrokeJoin.Round))
            }
        }

        Column(
            Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 24.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.camera_scanner_instructions),
                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(horizontal = 14.dp, vertical = 8.dp),
            )
            // A screen that fills little of the frame reads poorly: say to zoom in, while there's zoom left.
            val small = screen?.let { corners -> viewSize.width > 0 && polygonArea(corners) < 0.3f * viewSize.width * viewSize.height } == true
            if (small && zoom < maxZoom - 0.05f) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.camera_zoom_in_on_screen),
                    color = Color(0xFFFFD60A), fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(horizontal = 14.dp, vertical = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ZoomSteps(zoom, maxZoom, ::setZoom)
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(96.dp)) {
                    Text(
                        stringResource(R.string.common_cancel), color = Color.White, fontSize = 17.sp,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape)
                            .border(0.5.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                            .clickable(role = Role.Button, onClick = onCancel).padding(horizontal = 18.dp, vertical = 11.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                val shutterLabel = stringResource(R.string.camera_take_photo_description)
                Box(
                    Modifier.size(76.dp).border(4.dp, Color.White, CircleShape).padding(7.dp).background(Color.White, CircleShape)
                        .clickable(enabled = !capturing, role = Role.Button, onClick = ::capture)
                        .semantics { contentDescription = shutterLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    if (capturing) CircularProgressIndicator(color = Color.Black, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(96.dp))
            }
        }
    }
}

/** The camera app's zoom steps — 1×, 2×, 3×, 5×, as far as the camera goes — the one in use showing the exact zoom. */
@Composable
private fun ZoomSteps(zoom: Float, maxZoom: Float, onZoom: (Float) -> Unit) {
    val steps = listOf(1f, 2f, 3f, 5f).filter { it <= maxZoom + 0.01f }
    if (steps.size < 2) return
    val current = steps.lastOrNull { zoom >= it - 0.05f } ?: steps.first()
    Row(
        Modifier.background(Color.Black.copy(alpha = 0.3f), CircleShape).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (step in steps) {
            val active = step == current
            val description = stringResource(R.string.camera_zoom_level, zoomLabel(step))
            Box(
                Modifier.size(if (active) 42.dp else 34.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f))
                    .clickable(role = Role.Button) { onZoom(step) }
                    .semantics {
                        contentDescription = description
                        selected = active
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    zoomLabel(if (active) zoom else step), color = if (active) Color(0xFFFFD60A) else Color.White,
                    fontSize = if (active) 14.sp else 12.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** "2×", "2.4×" — whole zooms without a decimal. */
private fun zoomLabel(ratio: Float): String {
    val rounded = (ratio * 10).roundToInt() / 10f
    val format = NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 1
    }
    return format.format(rounded) + "×"
}

/** A polygon's area, by the shoelace formula. */
private fun polygonArea(corners: List<Offset>): Float {
    var sum = 0f
    for (i in corners.indices) {
        val a = corners[i]
        val b = corners[(i + 1) % corners.size]
        sum += a.x * b.y - b.x * a.y
    }
    return abs(sum) / 2
}

/**
 * Looks for the screen in each preview frame — [ScreenFinder], on the frame's brightness — then hands
 * the frame on to ML Kit's [text] analyzer. The screen's corners come back in the preview's coordinates,
 * as the text's do: CameraX's sensor-to-preview transform, after the frame's own buffer-to-sensor one.
 */
private class ScreenAndTextAnalyzer(
    private val text: MlKitAnalyzer,
    private val onScreen: (List<Offset>?) -> Unit,
) : ImageAnalysis.Analyzer {
    @Volatile private var sensorToView: Matrix? = null

    override fun getDefaultTargetResolution(): Size? = text.defaultTargetResolution

    override fun getTargetCoordinateSystem(): Int = ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED

    override fun updateTransform(matrix: Matrix?) {
        sensorToView = matrix?.let(::Matrix)
        text.updateTransform(matrix)
    }

    override fun analyze(image: ImageProxy) {
        onScreen(runCatching { screenIn(image) }.getOrNull())
        text.analyze(image)
    }

    private fun screenIn(image: ImageProxy): List<Offset>? {
        val sensorToView = sensorToView ?: return null
        val grid = image.lumaGrid()
        val quad = ScreenFinder.find(grid) ?: return null
        val bufferToView = Matrix()
        if (!image.imageInfo.sensorToBufferTransformMatrix.invert(bufferToView)) return null
        bufferToView.postConcat(sensorToView)
        // The grid covers whole cells of the frame; a sliver at the right and bottom can be left over.
        val step = gridStep(image.width, image.height)
        val points = quad.corners.flatMap { listOf((it.x * grid.width * step).toFloat(), (it.y * grid.height * step).toFloat()) }.toFloatArray()
        bufferToView.mapPoints(points)
        return List(4) { Offset(points[2 * it], points[2 * it + 1]) }
    }
}

/** The frame's brightness from its Y plane, on a grid [ScreenFinder.GRID] cells on its long edge; four samples a cell. */
private fun ImageProxy.lumaGrid(): LumaGrid {
    val plane = planes[0]
    val buffer = plane.buffer
    val step = gridStep(width, height)
    val w = width / step
    val h = height / step
    val luma = IntArray(w * h)
    for (gy in 0 until h) {
        for (gx in 0 until w) {
            var sum = 0
            for (dy in 0..1) {
                for (dx in 0..1) {
                    val x = gx * step + (2 * dx + 1) * step / 4
                    val y = gy * step + (2 * dy + 1) * step / 4
                    sum += buffer.get(y * plane.rowStride + x * plane.pixelStride).toInt() and 0xFF
                }
            }
            luma[gy * w + gx] = sum / 4
        }
    }
    return LumaGrid(luma, w, h)
}

/** How many of the frame's pixels, each way, make one grid cell. */
private fun gridStep(width: Int, height: Int) = max(1, ceil(max(width, height) / ScreenFinder.GRID.toDouble()).toInt())

/**
 * The system camera, for a device where CameraX finds no back camera — `ImagePickerCamera`. The photo
 * goes to a file in the cache (handed over through the app's FileProvider) and is deleted once read.
 */
@Composable
private fun SystemCamera(onCapture: (Bitmap) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = remember { File(File(context.cacheDir, "slide_capture").apply { mkdirs() }, "slide.jpg") }
    val uri: Uri = remember { FileProvider.getUriForFile(context, "${context.packageName}.shareimages", file) }
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        if (!saved) {
            onCancel()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val image = withContext(Dispatchers.IO) { SlideImage.decode(file.readBytes()).also { file.delete() } }
            if (image != null) onCapture(image) else onCancel()
        }
    }
    LaunchedEffect(Unit) { runCatching { take.launch(uri) }.onFailure { onCancel() } }
}

/** "Camera Access Is Off" — iOS's `ContentUnavailableView`, with Open Settings. */
@Composable
private fun CameraDenied(palette: ReaderPalette, onChoosePhoto: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().background(palette.page).windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 36.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.CameraAlt, null, tint = palette.secondary, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.camera_access_off_title), color = palette.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.camera_access_off_message),
            color = palette.secondary, fontSize = 15.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        Box(
            Modifier.widthIn(min = 180.dp).height(46.dp).background(palette.accent, CircleShape).clickable(role = Role.Button) {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.padding(horizontal = 22.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.camera_open_settings), color = if (palette.isDark) Color.Black else Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        DeniedTextButton(stringResource(R.string.camera_choose_from_photos), palette, onChoosePhoto)
        DeniedTextButton(stringResource(R.string.common_cancel), palette, onCancel)
    }
}

@Composable
private fun DeniedTextButton(title: String, palette: ReaderPalette, onClick: () -> Unit) {
    Box(
        Modifier.widthIn(min = 180.dp).height(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, color = palette.accent, fontSize = 16.sp)
    }
}
