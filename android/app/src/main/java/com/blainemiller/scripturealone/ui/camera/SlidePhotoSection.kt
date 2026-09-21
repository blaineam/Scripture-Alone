package com.blainemiller.scripturealone.ui.camera

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blainemiller.scripturealone.data.camera.SlideImage
import com.blainemiller.scripturealone.data.userdata.Note
import com.blainemiller.scripturealone.ui.notes.PanelGroup
import com.blainemiller.scripturealone.ui.notes.PanelSectionTitle
import com.blainemiller.scripturealone.ui.notes.PanelSeparator
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.blainemiller.scripturealone.ui.reader.ReaderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A photo kept with a camera note, shown in the note editor — `SlidePhotoSection`. Nothing when there is none. */
@Composable
fun SlidePhotoSection(model: ReaderViewModel, palette: ReaderPalette, note: Note) {
    val withPhotos by model.userData.slidePhotos.collectAsState()
    val revision = withPhotos[note.id] ?: return
    var image by remember(note.id) { mutableStateOf<Bitmap?>(null) }
    // Reloaded when another slide's photo replaces this one.
    LaunchedEffect(note.id, revision) {
        val data = model.userData.slidePhoto(note.id) ?: return@LaunchedEffect
        image = withContext(Dispatchers.Default) { SlideImage.decode(data, maxPixelSize = 1600) }
    }
    PanelSectionTitle("Slide Photo", palette)
    PanelGroup(palette) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap.asImageBitmap(), "Photo of the sermon slide", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).padding(horizontal = 18.dp, vertical = 12.dp).clip(RoundedCornerShape(10.dp)),
            )
        } else {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = palette.secondary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            }
        }
        PanelSeparator(palette)
        Text(
            "Remove Photo", color = palette.red, fontSize = 17.sp,
            modifier = Modifier.fillMaxWidth().clickable { model.userData.removeSlidePhoto(note) }.padding(horizontal = 18.dp, vertical = 13.dp),
        )
    }
}
