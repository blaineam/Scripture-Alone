package com.blainemiller.scripturealone.ui.keepsake

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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        }
    }

    FormSheet("Create a Keepsake", palette, back = true, onLeading = onBack) {
        Text(
            "A Keepsake Bible is a copy of your highlights and notes that your family can open in Scripture Alone and read as you marked it — the way a well-worn Bible gets passed down. It’s a single file you keep and give however you like: Quick Share, Messages, a USB drive, or with your papers.",
            color = palette.secondary, fontSize = 15.sp, lineHeight = 20.sp,
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 10.dp),
        )

        FormHeader("From You", palette)
        PanelGroup(palette) {
            FormField(ownerName, "Your name, as family knows you", palette) { value ->
                changed { ownerName = value }
                legacy.remember(LegacySession.KEY_OWNER, value)
            }
            PanelSeparator(palette)
            Text("Dedication", color = palette.secondary, fontSize = 12.sp, modifier = Modifier.padding(start = 18.dp, top = 10.dp))
            FormField(dedication, "", palette, singleLine = false, minHeight = 90) { value ->
                changed { dedication = value }
                legacy.remember(LegacySession.KEY_DEDICATION, value)
            }
            PanelSeparator(palette)
            FormPicker("Translation", translations, translations.firstOrNull { it.id == translation }, { "${it.id} — ${it.name}" }, palette) {
                changed { translation = it.id }
                legacy.remember(LegacySession.KEY_TRANSLATION, it.id)
            }
        }
        FormFooter("The dedication opens their copy — a few words to whoever reads it next. The translation is the one it opens in.", palette)

        FormHeader("What’s Included", palette)
        PanelGroup(palette) {
            FormValue("Highlights", "$uniqueHighlights", palette)
            PanelSeparator(palette)
            FormValue("Notes", "${notes.size}", palette)
            KeepsakeBuilder.dateSpan(highlights, notes)?.let {
                PanelSeparator(palette)
                FormValue("From", it, palette)
            }
        }

        FormHeader("Privacy", palette)
        PanelGroup(palette) {
            SwitchRow("Protect with a Passphrase", protect, palette) { changed { protect = it } }
            if (protect) {
                PanelSeparator(palette)
                // Shown, not hidden: it has to be written down exactly.
                FormField(passphrase, "Passphrase", palette, monospace = true, words = false) { changed { passphrase = it } }
                PanelSeparator(palette)
                FormField(hint, "Hint (optional, shown to anyone)", palette, imeAction = ImeAction.Done) { changed { hint = it } }
            }
        }
        FormFooter(
            if (protect) "Write the passphrase down exactly as shown and keep it with the file — for example, with your will. Without it, no one can open this keepsake: not your family, and not us. There is no reset."
            else "Without a passphrase, anyone who has the file can read it, like a Bible on a shelf. That’s usually what you want for family. Add one if the file might travel somewhere less private.",
            palette,
        )

        FormHeader("", palette)
        PanelGroup(palette) {
            val file = made
            if (file != null) {
                FormButton("Share…", palette, icon = Icons.Outlined.Share) {
                    try {
                        context.startActivity(ExportFiles.shareIntent(file.uris, ExportFiles.KEEPSAKE, file.name))
                    } catch (_: ActivityNotFoundException) {
                        failure = "Nothing on this device can receive it."
                    }
                }
                PanelSeparator(palette)
                FormButton("Save to Files…", palette, icon = Icons.Outlined.Folder) { save.launch(ExportFiles.KEEPSAKE to file.name) }
            } else {
                FormButton("Create Keepsake", palette, enabled = canCreate, bold = true, busy = working && !protect, onClick = ::create)
                if (working && protect) {
                    PanelSeparator(palette)
                    FormProgress(progress, "Protecting with your passphrase…", palette)
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
