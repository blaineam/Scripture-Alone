package com.blainemiller.scripturealone.ui.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.util.Log
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.blainemiller.scripturealone.data.camera.SlideImage
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    Box(Modifier.fillMaxSize().background(Color.Black).clickable(interactionSource = null, indication = null) {}) {
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
        }
    }
    DisposableEffect(lifecycleOwner) {
        controller.setImageAnalysisAnalyzer(
            mainExecutor,
            MlKitAnalyzer(listOf(recognizer), ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED, mainExecutor) { result ->
                val text = result.getValue(recognizer) ?: return@MlKitAnalyzer
                highlights = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                    // The bounding box, not the corner points: MlKitAnalyzer maps the box into the
                    // preview's coordinates but leaves the corners in the rotated frame's (seen on the
                    // emulator: every line drawn as a vertical bar).
                    line.boundingBox?.let { box ->
                        Highlight(listOf(Offset(box.left.toFloat(), box.top.toFloat()), Offset(box.right.toFloat(), box.top.toFloat()), Offset(box.right.toFloat(), box.bottom.toFloat()), Offset(box.left.toFloat(), box.bottom.toFloat())))
                    }
                }
            },
        )
        controller.bindToLifecycle(lifecycleOwner)
        controller.initializationFuture.addListener({
            if (!controller.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) useSystemCamera = true
        }, mainExecutor)
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
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

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { PreviewView(it).apply { this.controller = controller; scaleType = PreviewView.ScaleType.FILL_CENTER } },
            modifier = Modifier.fillMaxSize(),
        )
        val slop = with(density) { 12.dp.toPx() }
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures { point -> if (highlights.any { it.contains(point, slop) }) capture() }
            },
        ) {
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

        Text(
            "Point at the slide. Tap any highlighted text or the shutter.",
            color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 24.dp, start = 24.dp, end = 24.dp)
                .background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(horizontal = 14.dp, vertical = 8.dp),
        )

        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(96.dp)) {
                Text(
                    "Cancel", color = Color.White, fontSize = 17.sp,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape)
                        .border(0.5.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                        .clickable(onClick = onCancel).padding(horizontal = 18.dp, vertical = 11.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(76.dp).border(4.dp, Color.White, CircleShape).padding(7.dp).background(Color.White, CircleShape)
                    .clickable(enabled = !capturing, onClick = ::capture)
                    .semantics { contentDescription = "Take photo of slide" },
                contentAlignment = Alignment.Center,
            ) {
                if (capturing) CircularProgressIndicator(color = Color.Black, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(96.dp))
        }
    }
}

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
        Text("Camera Access Is Off", color = palette.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Scripture Alone reads slides on your phone and never uploads them. Turn on camera access in Settings, or choose a photo you’ve already taken.",
            color = palette.secondary, fontSize = 15.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        Box(
            Modifier.widthIn(min = 180.dp).height(46.dp).background(palette.accent, CircleShape).clickable {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.padding(horizontal = 22.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Open Settings", color = if (palette.isDark) Color.Black else Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        DeniedTextButton("Choose from Photos", palette, onChoosePhoto)
        DeniedTextButton("Cancel", palette, onCancel)
    }
}

@Composable
private fun DeniedTextButton(title: String, palette: ReaderPalette, onClick: () -> Unit) {
    Box(
        Modifier.widthIn(min = 180.dp).height(44.dp).clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, color = palette.accent, fontSize = 16.sp)
    }
}
