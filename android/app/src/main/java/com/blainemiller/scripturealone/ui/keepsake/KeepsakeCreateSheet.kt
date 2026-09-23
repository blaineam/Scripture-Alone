package com.blainemiller.scripturealone.ui.keepsake

import com.blainemiller.scripturealone.ui.appearance.RatingPrompt
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.data.BundledTranslations
import com.blainemiller.scripturealone.data.keepsake.KeepsakeArchive
import com.blainemiller.scripturealone.ui.appearance.SwitchRow
import com.blainemiller.scripturealone.ui.export.CreateDocumentOfType
import com.blainemiller.scripturealone.ui.export.ExportFiles
import com.blainemiller.scripturealone.ui.export.ExportSupport
import com.blainemiller.scripturealone.ui.export.ExportedFile
import com.blainemiller.scripturealone.ui.export.FormButton
import com.blainemiller.scripturealone.ui.export.FormField
import com.blainemiller.scripturealone.ui.export.FormFooter
import com.blainemiller.scripturealone.ui.export.FormHeader
import com.blainemiller.scripturealone.ui.export.FormPicker
import com.blainemiller.scripturealone.ui.export.FormProgress
import com.blainemiller.scripturealone.ui.export.FormSheet
import com.blainemiller.scripturealone.ui.export.FormValue
import com.blainemiller.scripturealone.ui.export.rememberTranslationInfos
import com.blainemiller.scripturealone.ui.notes.PanelGroup
import com.blainemiller.scripturealone.ui.notes.PanelSeparator
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.blainemiller.scripturealone.ui.reader.ReaderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Makes a Keepsake Bible: a file holding a copy of every highlight and note, to give to family —
 * `KeepsakeCreateView.swift`. Name, dedication and translation are remembered under iOS's keys; the
 * passphrase never is. A protected keepsake's key derivation (600,000 rounds) runs off the main thread
 * with its progress shown. The file then goes to the share sheet or Save to Files, as iOS's.
 */
@Composable
fun KeepsakeCreateSheet(model: ReaderViewModel, palette: ReaderPalette, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val legacy = model.legacy
    val highlights by model.userData.highlights.collectAsState()
    val notes by model.userData.notes.collectAsState()
    val translations by rememberTranslationInfos(context)

    var ownerName by remember { mutableStateOf(legacy.remembered(LegacySession.KEY_OWNER)) }
    var dedication by remember { mutableStateOf(legacy.remembered(LegacySession.KEY_DEDICATION)) }
    var translation by remember {
        mutableStateOf(legacy.remembered(LegacySession.KEY_TRANSLATION).takeIf { it in BundledTranslations.ids } ?: model.translationId)
    }
    var protect by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var made by remember { mutableStateOf<Made?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    val uniqueHighlights = KeepsakeBuilder.uniqueHighlightCount(highlights)
    val canCreate = !working && uniqueHighlights + notes.size > 0 && (!protect || passphrase.trim().isNotEmpty())

    val save = rememberLauncherForActivityResult(CreateDocumentOfType()) { uri ->
        val file = made ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch { runCatching { ExportFiles.write(context, uri, file.data) }.onFailure { failure = it.message } }
    }

    fun changed(update: () -> Unit) {
        update()
        made = null
    }

    fun create() {
        failure = null
        working = true
        progress = 0f
        val keepsake = KeepsakeBuilder.make(
            highlights, notes, legacy.bibleID, ownerName, dedication, translation,
            generator = "Scripture Alone ${versionName(context)}".trim(),
        )
        val phrase = if (protect) passphrase else null
        val hintText = if (protect) hint else null
        val name = ExportSupport.safeName(keepsake.manifest.displayTitle) + "." + KeepsakeArchive.FILE_EXTENSION
        val protectedFile = protect
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    // Passphrase key derivation is deliberately slow; progress comes back to the screen.
                    val data = KeepsakeArchive.encode(keepsake, phrase, hintText, progress = { p -> scope.launch { progress = p } })
                    val file = ExportedFile(name, ExportFiles.KEEPSAKE, ExportedFile.Contents.File(data))
                    Made(name, data, protectedFile, ExportFiles.stage(context, file))
                }
            }
            working = false
            result.onSuccess { made = it }.onFailure { failure = it.message ?: it.javaClass.simpleName }
            // A keepsake made is the app's work done: the moment MillerKit's gate may ask for a review.
            if (result.isSuccess) RatingPrompt.afterSuccess(context)
        }
    }

    FormSheet(stringResource(R.string.keepsake_create_a_keepsake), palette, back = true, onLeading = onBack) {
        Text(
            stringResource(R.string.keepsake_create_intro),
            color = palette.secondary, fontSize = 15.sp, lineHeight = 20.sp,
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 10.dp),
        )

        FormHeader(stringResource(R.string.keepsake_create_section_from_you), palette)
        PanelGroup(palette) {
            FormField(ownerName, stringResource(R.string.keepsake_create_name_placeholder), palette) { value ->
                changed { ownerName = value }
                legacy.remember(LegacySession.KEY_OWNER, value)
            }
            PanelSeparator(palette)
            Text(stringResource(R.string.keepsake_create_dedication), color = palette.secondary, fontSize = 12.sp, modifier = Modifier.padding(start = 18.dp, top = 10.dp))
            FormField(dedication, "", palette, singleLine = false, minHeight = 90, label = stringResource(R.string.keepsake_create_dedication)) { value ->
                changed { dedication = value }
                legacy.remember(LegacySession.KEY_DEDICATION, value)
            }
            PanelSeparator(palette)
            FormPicker(stringResource(R.string.keepsake_create_translation), translations, translations.firstOrNull { it.id == translation }, { "${it.id} — ${it.name}" }, palette) {
                changed { translation = it.id }
                legacy.remember(LegacySession.KEY_TRANSLATION, it.id)
            }
        }
        FormFooter(stringResource(R.string.keepsake_create_dedication_footer), palette)

        FormHeader(stringResource(R.string.keepsake_create_section_included), palette)
        PanelGroup(palette) {
            FormValue(stringResource(R.string.keepsake_create_highlights), "$uniqueHighlights", palette)
            PanelSeparator(palette)
            FormValue(stringResource(R.string.keepsake_create_notes), "${notes.size}", palette)
            KeepsakeBuilder.dateSpan(highlights, notes)?.let {
                PanelSeparator(palette)
                FormValue(stringResource(R.string.keepsake_create_from), it, palette)
            }
        }

        FormHeader(stringResource(R.string.keepsake_create_section_privacy), palette)
        PanelGroup(palette) {
            SwitchRow(stringResource(R.string.keepsake_create_protect), protect, palette) { changed { protect = it } }
            if (protect) {
                PanelSeparator(palette)
                // Shown, not hidden: it has to be written down exactly.
                FormField(passphrase, stringResource(R.string.keepsake_passphrase), palette, monospace = true, words = false) { changed { passphrase = it } }
                PanelSeparator(palette)
                FormField(hint, stringResource(R.string.keepsake_create_hint_placeholder), palette, imeAction = ImeAction.Done) { changed { hint = it } }
            }
        }
        FormFooter(
            stringResource(if (protect) R.string.keepsake_create_protect_footer_on else R.string.keepsake_create_protect_footer_off),
            palette,
        )

        FormHeader("", palette)
        PanelGroup(palette) {
            val file = made
            if (file != null) {
                FormButton(stringResource(R.string.keepsake_create_share), palette, icon = Icons.Outlined.Share) {
                    try {
                        context.startActivity(ExportFiles.shareIntent(file.uris, ExportFiles.KEEPSAKE, file.name))
                    } catch (_: ActivityNotFoundException) {
                        failure = context.getString(R.string.keepsake_create_no_receiver)
                    }
                }
                PanelSeparator(palette)
                FormButton(stringResource(R.string.keepsake_create_save_to_files), palette, icon = Icons.Outlined.Folder) { save.launch(ExportFiles.KEEPSAKE to file.name) }
            } else {
                FormButton(stringResource(R.string.keepsake_create_button), palette, enabled = canCreate, bold = true, busy = working && !protect, onClick = ::create)
                if (working && protect) {
                    PanelSeparator(palette)
                    FormProgress(progress, stringResource(R.string.keepsake_create_protecting), palette)
                }
            }
        }
        made?.let { FormFooter(KeepsakeText.ready(it.name, it.protected), palette) }
        failure?.let { FormFooter(it, palette, color = palette.red) }
    }
}

private class Made(val name: String, val data: ByteArray, val protected: Boolean, val uris: List<android.net.Uri>)

private fun versionName(context: android.content.Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
