package com.blainemiller.scripturealone.ui.camera

import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.blainemiller.scripturealone.data.camera.SlideImage
import com.blainemiller.scripturealone.data.userdata.Note
import com.blainemiller.scripturealone.ui.notes.PanelColors
import com.blainemiller.scripturealone.ui.notes.PanelHeaderIcon
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.blainemiller.scripturealone.ui.reader.ReaderViewModel
import com.blainemiller.scripturealone.ui.reader.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** A photo of a slide, waiting to be read and reviewed. */
class ScannedSlide(val image: Bitmap) {
    val id: UUID = UUID.randomUUID()
}

/** Where the next slide comes from — the capture menu's choices. */
enum class SlideSource { CAMERA, PHOTOS, FILE }

/**
 * Gets a slide image from wherever the reader has one — the camera, their photos, a file or the
 * clipboard — and hands it to review; `ScriptureAlone/Camera/SlideCapture.swift`. Nothing is saved
 * until the review sheet is confirmed.
 */
@Stable
class SlideCapture {
    var slide by mutableStateOf<ScannedSlide?>(null)
    var showCamera by mutableStateOf(false)
    /** A picker to open; [SlideCaptureHost] launches it and clears this. */
    var request by mutableStateOf<SlideSource?>(null)
    var failure by mutableStateOf<String?>(null)
    var decoding by mutableStateOf(false)

    fun accept(image: Bitmap) {
        slide = ScannedSlide(image)
    }

    fun start(source: SlideSource) {
        if (source == SlideSource.CAMERA) showCamera = true else request = source
    }

    /** Decodes a picked or pasted image off the main thread — `accept(data:)`. */
    suspend fun accept(context: Context, uri: Uri, failureMessage: String = "That file doesn’t look like an image.") {
        decoding = true
        val image = withContext(Dispatchers.IO) { SlideImage.decode(context.contentResolver, uri) }
        decoding = false
        if (image != null) accept(image) else failure = failureMessage
    }

    companion object {
        fun canTakePhoto(context: Context): Boolean =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

        fun clipboardHasImage(context: Context): Boolean {
            val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
            // The description only: reading the clip itself would show Android's "pasted" notice.
            return clipboard.primaryClipDescription?.hasMimeType("image/*") == true
        }
    }
}

/**
 * The header control — "Scan Slide" in the Notes panel, "Add from Camera" in a note: a menu of every
 * source, as iOS's `SlideCaptureMenu`. (On iPhone a plain tap goes straight to the camera and a
 * long-press opens the menu; a menu on tap is the Android idiom for a control with choices.)
 */
@Composable
fun SlideCaptureMenu(capture: SlideCapture, palette: ReaderPalette, addingToNote: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    val title = if (addingToNote) "Add from Camera" else "Scan Slide"
    Box {
        PanelHeaderIcon(Icons.Outlined.DocumentScanner, title, palette) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            @Composable
            fun item(label: String, icon: ImageVector, enabled: Boolean = true, action: () -> Unit) = DropdownMenuItem(
                text = { Text(label, color = if (enabled) palette.ink else palette.secondary, fontSize = 15.sp) },
                leadingIcon = { Icon(icon, null, tint = if (enabled) palette.ink else palette.secondary, modifier = Modifier.size(20.dp)) },
                enabled = enabled,
                onClick = {
                    open = false
                    action()
                },
            )
            if (SlideCapture.canTakePhoto(context)) item("Take Photo of Slide", Icons.Outlined.CameraAlt) { capture.start(SlideSource.CAMERA) }
            item("Choose from Photos", Icons.Outlined.PhotoLibrary) { capture.start(SlideSource.PHOTOS) }
            item("Choose Image File…", Icons.Outlined.Description) { capture.start(SlideSource.FILE) }
            item("Paste Image", Icons.Outlined.ContentPaste, enabled = SlideCapture.clipboardHasImage(context)) {
                scope.launch { pasteImage(context, capture) }
            }
        }
    }
}

private suspend fun pasteImage(context: Context, capture: SlideCapture) {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
    val uri = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
    if (uri == null) {
        capture.failure = "There’s no image on the clipboard."
        return
    }
    capture.accept(context, uri, "That image couldn’t be read.")
}

/**
 * Hosts every way of getting a slide in, and the review sheet that follows — `.slideCapture(_:appendTo:)`.
 * [appendTo] is the note a new slide adds to by default (null starts a new note). Draw it over the
 * whole screen: the scanner and the review sheet cover everything beneath.
 */
@Composable
fun SlideCaptureHost(
    capture: SlideCapture,
    model: ReaderViewModel,
    palette: ReaderPalette,
    notes: List<Note>,
    appendTo: Note?,
    onSaved: (Note) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { capture.accept(context, uri, "That photo couldn’t be opened.") }
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { capture.accept(context, uri, "That file couldn’t be opened.") }
    }
    LaunchedEffect(capture.request) {
        when (capture.request) {
            SlideSource.PHOTOS -> photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            SlideSource.FILE -> files.launch(arrayOf("image/*"))
            else -> Unit
        }
        capture.request = null
    }

    if (capture.showCamera) {
        SlideScanner(
            palette,
            onCapture = { image ->
                capture.showCamera = false
                capture.accept(image)
            },
            onChoosePhoto = {
                capture.showCamera = false
                capture.start(SlideSource.PHOTOS)
            },
            onCancel = { capture.showCamera = false },
        )
    }

    ReviewSheetFrame(capture.slide != null, onDismiss = { capture.slide = null }) {
        capture.slide?.let { slide ->
            androidx.compose.runtime.key(slide.id) {
                SlideReviewSheet(model, palette, slide, notes, appendTo, onDismiss = { capture.slide = null }) { note ->
                    capture.slide = null
                    onSaved(note)
                }
            }
        }
    }

    capture.failure?.let { message ->
        FailureAlert("Couldn’t Use That Image", message, palette) { capture.failure = null }
    }
}

/** An iOS alert: title, message, one OK. */
@Composable
internal fun FailureAlert(title: String, message: String, palette: ReaderPalette, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(22.dp)).background(PanelColors.card(palette)).padding(top = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(message, color = palette.secondary, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                Box(
                    Modifier.weight(1f).height(46.dp).glass(palette, CircleShape, PanelColors.card(palette)).clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("OK", color = palette.accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.width(1.dp))
        }
    }
}
