package com.blainemiller.scripturealone.ui.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowCircleDown
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import com.blainemiller.scripturealone.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.blainemiller.scripturealone.data.assets.AssetLibrary
import com.blainemiller.scripturealone.data.assets.AssetPack
import com.blainemiller.scripturealone.data.assets.AssetState
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import kotlinx.coroutines.launch

/** Whether [pack]'s database is on the device, recomposing when a download finishes. */
@Composable
fun packReady(pack: AssetPack): Boolean {
    val states by AssetLibrary.states.collectAsState()
    return states[pack] == AssetState.Ready
}

/**
 * A Study pack that isn't on the device yet — iOS's `commentaryDownload` and the interlinear sheet's
 * download state: what it is and how big, with **Download**, because 44 MB on a mobile connection is
 * the reader's decision, not the app's; then progress; then, if it failed, why, with Try Again.
 */
@Composable
fun PackDownload(pack: AssetPack, failedTitle: String, palette: ReaderPalette, modifier: Modifier = Modifier) {
    val states by AssetLibrary.states.collectAsState()
    val scope = rememberCoroutineScope()
    // The download itself runs in the library's own scope; leaving the tab doesn't cancel it.
    val download = { scope.launch { AssetLibrary.ensure(pack) }; Unit }
    when (val state = states[pack] ?: AssetState.Absent) {
        is AssetState.Downloading, is AssetState.NeedsConfirmation -> Column(
            modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val fraction = (state as? AssetState.Downloading)?.fraction ?: (state as AssetState.NeedsConfirmation).fraction
            LinearProgressIndicator(
                progress = { fraction }, color = palette.accent, trackColor = palette.secondary.copy(alpha = 0.2f),
                modifier = Modifier.widthIn(max = 220.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            )
            Text(
                if (state is AssetState.NeedsConfirmation) stringResource(R.string.study_pack_waiting_wifi, pack.title)
                else stringResource(R.string.study_pack_downloading, pack.title),
                color = palette.ink, fontSize = StudyStyle.callout,
            )
            Text(pack.explanation, color = palette.secondary, fontSize = StudyStyle.caption, textAlign = TextAlign.Center)
            if (state is AssetState.NeedsConfirmation) BorderedButton(stringResource(R.string.study_pack_download_now), palette) { AssetLibrary.confirm() }
        }
        is AssetState.Failed -> ContentUnavailable(Icons.Rounded.Warning, failedTitle, state.message, palette, modifier.padding(top = 16.dp)) {
            BorderedButton(stringResource(R.string.common_try_again), palette, onClick = download)
        }
        else -> ContentUnavailable(Icons.Rounded.ArrowCircleDown, pack.title, pack.explanation, palette, modifier.padding(top = 16.dp)) {
            BorderedButton(stringResource(R.string.common_download), palette, onClick = download)
        }
    }
}
