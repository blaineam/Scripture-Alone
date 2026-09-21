package com.blainemiller.scripturealone.ui.keepsake

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blainemiller.scripturealone.data.keepsake.Keepsake
import com.blainemiller.scripturealone.data.keepsake.KeepsakeArchive
import com.blainemiller.scripturealone.data.keepsake.KeepsakeException
import com.blainemiller.scripturealone.data.keepsake.KeepsakeManifest
import com.blainemiller.scripturealone.ui.export.FormButton
import com.blainemiller.scripturealone.ui.export.FormField
import com.blainemiller.scripturealone.ui.export.FormFooter
import com.blainemiller.scripturealone.ui.export.FormHeader
import com.blainemiller.scripturealone.ui.export.FormProgress
import com.blainemiller.scripturealone.ui.export.FormSheet
import com.blainemiller.scripturealone.ui.export.FormValue
import com.blainemiller.scripturealone.ui.notes.PanelGroup
import com.blainemiller.scripturealone.ui.notes.PanelSeparator
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.blainemiller.scripturealone.ui.reader.ReaderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Shows who a keepsake is from before adding it, asking for its passphrase if it has one —
 * `KeepsakeImportSheet` in `LegacySupport.swift`. The file comes from the system (a `.scripturelegacy`
 * opened from Files, Gmail, Downloads) or Keepsake & Export's Open a Keepsake File…. Unlocking runs the
 * 600,000-round key derivation off the main thread with its progress shown; a wrong passphrase clears
 * the field and says so, every other failure is iOS's message for it.
 */
@Composable
fun KeepsakeImportSheet(model: ReaderViewModel, uri: Uri, palette: ReaderPalette, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember(uri) { mutableStateOf<Phase>(Phase.Loading) }
    var passphrase by remember(uri) { mutableStateOf("") }
    var passphraseError by remember(uri) { mutableStateOf<String?>(null) }
    var unlocking by remember(uri) { mutableStateOf(false) }
    var progress by remember(uri) { mutableFloatStateOf(0f) }
    BackHandler(onBack = onDone)

    LaunchedEffect(uri) {
        phase = withContext(Dispatchers.IO) {
            try {
                val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw KeepsakeException.NotAKeepsake()
                val manifest = KeepsakeArchive.peek(data)
                if (manifest.isEncrypted) Phase.Locked(manifest, data) else Phase.Ready(KeepsakeArchive.decode(data))
            } catch (e: KeepsakeException) {
                Phase.Failed(e.message.orEmpty())
            } catch (e: Exception) {
                Phase.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun unlock(data: ByteArray) {
        if (passphrase.isEmpty() || unlocking) return
        unlocking = true
        passphraseError = null
        progress = 0f
        val phrase = passphrase
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                // Key derivation is deliberately slow; keep it off the main thread.
                runCatching { KeepsakeArchive.decode(data, phrase, progress = { p -> scope.launch { progress = p } }) }
            }
            unlocking = false
            result.onSuccess { phase = Phase.Ready(it) }.onFailure { error ->
                when (error) {
                    is KeepsakeException.WrongPassphrase -> {
                        passphraseError = error.message
                        passphrase = ""
                    }
                    else -> phase = Phase.Failed(error.message ?: error.javaClass.simpleName)
                }
            }
        }
    }

    fun add(keepsake: Keepsake) {
        scope.launch {
            phase = try {
                Phase.Added(keepsake, model.legacy.add(keepsake).previous)
            } catch (e: Exception) {
                Phase.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    val finished = phase is Phase.Added || phase is Phase.Failed
    FormSheet("Keepsake Bible", palette, leading = if (finished) "Done" else "Cancel", onLeading = onDone) {
        when (val current = phase) {
            Phase.Loading -> Column(Modifier.fillMaxWidth().padding(top = 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = palette.secondary, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            }
            is Phase.Locked -> {
                FormHeader("", palette)
                PanelGroup(palette) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Lock, null, tint = palette.secondary, modifier = Modifier.size(26.dp))
                        Text("This keepsake is protected", color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Whoever made it chose a passphrase so only family could open it. It may be written down with the file, or with their papers.",
                            color = palette.secondary, fontSize = 15.sp, lineHeight = 20.sp,
                        )
                    }
                }
                FormHeader("", palette)
                PanelGroup(palette) {
                    FormField(
                        passphrase, "Passphrase", palette, shown = false, words = false, enabled = !unlocking,
                        imeAction = ImeAction.Go, onDone = { unlock(current.data) },
                    ) { passphrase = it }
                    current.manifest.passphraseHint?.let {
                        PanelSeparator(palette)
                        FormValue("Hint", it, palette, dim = true)
                    }
                }
                passphraseError?.let { FormFooter(it, palette, color = palette.red) }
                FormHeader("", palette)
                PanelGroup(palette) {
                    FormButton("Open", palette, enabled = passphrase.isNotEmpty() && !unlocking) { unlock(current.data) }
                    if (unlocking) {
                        PanelSeparator(palette)
                        FormProgress(progress, "Opening with the passphrase…", palette)
                    }
                }
            }
            is Phase.Ready -> {
                FormHeader("", palette)
                PanelGroup(palette) { KeepsakeSummary(current.keepsake, palette) }
                FormHeader("", palette)
                PanelGroup(palette) { FormButton("Add to My Library", palette, bold = true) { add(current.keepsake) } }
                FormFooter("Kept on this device, separate from your own highlights and notes. It’s read-only; nothing in it can be changed.", palette)
            }
            is Phase.Added -> {
                FormHeader("", palette)
                PanelGroup(palette) { KeepsakeSummary(current.keepsake, palette) }
                FormHeader("", palette)
                PanelGroup(palette) {
                    FormButton("Open ${current.keepsake.manifest.displayTitle}", palette, bold = true) {
                        model.openKeepsake(current.keepsake)
                        onDone()
                    }
                }
                FormFooter(
                    current.replaced?.let {
                        "This replaced the copy from ${DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).format(it.atZone(ZoneId.systemDefault()))}."
                    } ?: "You’ll find it again under Appearance → Keepsake & Export.",
                    palette,
                )
            }
            is Phase.Failed -> Column(
                Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 64.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, null, tint = palette.secondary, modifier = Modifier.size(48.dp))
                Text("Can’t Open This Keepsake", color = palette.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Text(current.message, color = palette.secondary, fontSize = 15.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

private sealed class Phase {
    data object Loading : Phase()
    class Locked(val manifest: KeepsakeManifest, val data: ByteArray) : Phase()
    class Ready(val keepsake: Keepsake) : Phase()
    class Added(val keepsake: Keepsake, val replaced: java.time.Instant?) : Phase()
    class Failed(val message: String) : Phase()
}
