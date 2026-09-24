package com.blainemiller.scripturealone.ui.study

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.blainemiller.scripturealone.data.study.ImportedStudyLibrary
import com.blainemiller.scripturealone.data.study.ImportedStudyStore
import com.blainemiller.scripturealone.data.study.StudyImage
import com.blainemiller.scripturealone.ui.reader.ReaderPalette

// Maps & Images: the pictures an imported study Bible set beside this chapter's verses (or at the
// head of its book) — `ImportedImagesSection` in `ImportedStudyLibrary.swift`. Full size on a tap.

private class Picture(val store: ImportedStudyStore, val image: StudyImage)

/** The chapter's pictures from every imported study Bible; nothing at all when there are none. */
@Composable
fun ImportedImagesSection(chapter: ChapterRef, palette: ReaderPalette) {
    val sources by ImportedStudyLibrary.sources.collectAsState()
    if (sources.isEmpty()) return
    val pictures = loaded(Triple(chapter.book, chapter.chapter, sources)) { _ ->
        ImportedStudyLibrary.stores().flatMap { store -> store.images(chapter.book, chapter.chapter).map { Picture(store, it) } }
    }.orEmpty()
    if (pictures.isEmpty()) return
    var shown by remember { mutableStateOf<Picture?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SymbolHeading(Icons.Rounded.Photo, stringResource(R.string.context_imported_images), palette)
        Spacer(Modifier.height(4.dp))
        for (picture in pictures) PictureRow(picture, palette) { shown = picture }
    }
    shown?.let { picture -> PictureViewer(picture, palette) { shown = null } }
}

private fun StudyImage.title(untitled: String): String = caption.ifBlank { untitled }.replaceFirstChar { it.uppercase() }

private fun StudyImage.placeLabel(): String =
    anchorKey?.let { VerseRef.fromKey(it).display } ?: book?.let { BookID.of(it)?.displayName }.orEmpty()

@Composable
private fun PictureRow(picture: Picture, palette: ReaderPalette, onClick: () -> Unit) {
    val thumbnail = loaded(picture.store.source.id to picture.image.id) { _ ->
        picture.store.imageData(picture.image.id)?.let { decode(it, 160) }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(9.dp)).background(palette.accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            if (thumbnail != null) {
                Image(thumbnail.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Rounded.Image, null, tint = palette.accent, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                picture.image.title(stringResource(R.string.context_image_untitled)),
                color = palette.ink, fontSize = StudyStyle.subheadline, fontWeight = FontWeight.SemiBold,
            )
            Text(picture.image.placeLabel(), color = palette.secondary, fontSize = StudyStyle.caption)
        }
    }
}

@Composable
private fun PictureViewer(picture: Picture, palette: ReaderPalette, onDismiss: () -> Unit) {
    val full = loaded(picture.store.source.id to -picture.image.id) { _ ->
        picture.store.imageData(picture.image.id)?.let { decode(it, 2048) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(palette.page).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    picture.image.title(stringResource(R.string.context_image_untitled)),
                    color = palette.ink, fontSize = StudyStyle.headline, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done), color = palette.accent) }
            }
            if (full != null) {
                Image(
                    full.asImageBitmap(), picture.image.caption.ifBlank { null },
                    contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = palette.secondary, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
                }
            }
            Text(picture.image.placeLabel(), color = palette.secondary, fontSize = StudyStyle.caption)
            Text(picture.store.source.attribution, color = palette.secondary, fontSize = StudyStyle.caption2)
        }
    }
}

/** Decodes a picture no larger than [maxSide] on its longest side; null for anything Android can't draw (SVG). */
private fun decode(data: ByteArray, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
}
