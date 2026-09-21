package com.blainemiller.scripturealone.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.ArrowCircleDown
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blainemiller.scripturealone.data.assets.AssetLibrary
import com.blainemiller.scripturealone.data.assets.AssetPack
import com.blainemiller.scripturealone.data.assets.AssetState
import kotlin.math.roundToInt

/**
 * "Downloading the King James Version…", above the text while a translation the reader chose is on
 * its way — `TranslationDownloadBanner.swift`.
 *
 * The KJV is an on-demand asset pack. Choosing it keeps the current translation on screen and fetches
 * the other; this says what is happening and how far along it is, and — if the download fails, most
 * often for want of a connection — says so and offers to try again, rather than leaving the reader
 * wondering why nothing changed. When Play holds the download for Wi-Fi, it offers Play's own
 * "download now" dialog instead.
 */
@Composable
fun TranslationDownloadBanner(model: ReaderViewModel, palette: ReaderPalette, modifier: Modifier = Modifier) {
    val states by AssetLibrary.states.collectAsState()
    // A translation download that failed stays visible until retried or another is chosen.
    val pack = model.downloadingPack
        ?: AssetPack.entries.firstOrNull { it.isTranslation && states[it] is AssetState.Failed }
        ?: return
    val state = states[pack] ?: AssetState.Absent
    val failed = state as? AssetState.Failed

    Row(
        modifier.padding(horizontal = 16.dp).widthIn(max = 620.dp).fillMaxWidth()
            // Nearly opaque: without a blur behind it, text scrolling beneath would show through.
            .glass(palette, RoundedCornerShape(20.dp), lifted = true, opacity = 0.985f)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (failed != null) {
            Icon(Icons.Rounded.Warning, null, tint = Color(0xFFFF9500), modifier = Modifier.size(24.dp))
        } else {
            Icon(Icons.Rounded.ArrowCircleDown, null, tint = palette.accent, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (failed != null) "Couldn’t download the ${pack.title}" else "Downloading the ${pack.title}…",
                color = palette.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = ReaderTypography.sourceSerif(15f),
            )
            when (state) {
                is AssetState.Downloading -> Progress(state.fraction, palette)
                is AssetState.NeedsConfirmation -> Text(
                    "Waiting for Wi-Fi. ${pack.explanation}", color = palette.secondary, fontSize = 11.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                is AssetState.Failed -> Text(state.message, color = palette.secondary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                else -> Text(pack.explanation, color = palette.secondary, fontSize = 11.sp)
            }
        }
        val translation = pack.translationId
        when {
            failed != null && translation != null -> {
                Spacer(Modifier.width(8.dp))
                Capsule("Try Again", palette) { model.selectTranslation(translation) }
            }
            state is AssetState.NeedsConfirmation -> {
                Spacer(Modifier.width(8.dp))
                Capsule("Download Now", palette) { AssetLibrary.confirm() }
            }
        }
    }
}

@Composable
private fun Progress(fraction: Float, palette: ReaderPalette) {
    val percent = (fraction * 100).roundToInt()
    LinearProgressIndicator(
        progress = { fraction }, color = palette.accent, trackColor = palette.secondary.copy(alpha = 0.2f),
        modifier = Modifier.widthIn(max = 220.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
            .semantics { contentDescription = "$percent percent" },
    )
}

@Composable
private fun Capsule(title: String, palette: ReaderPalette, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape).background(SheetColors.buttonFill(palette)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) { Text(title, color = palette.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}
