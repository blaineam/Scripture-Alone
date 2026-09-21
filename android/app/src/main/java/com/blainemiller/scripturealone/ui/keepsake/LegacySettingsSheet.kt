package com.blainemiller.scripturealone.ui.keepsake

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blainemiller.scripturealone.data.keepsake.Keepsake
import com.blainemiller.scripturealone.data.keepsake.KeepsakeArchive
import com.blainemiller.scripturealone.ui.export.ExportedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.blainemiller.scripturealone.data.rights.TranslationRights
import com.blainemiller.scripturealone.ui.export.ExportFiles
import com.blainemiller.scripturealone.ui.export.ExportSupport
import com.blainemiller.scripturealone.ui.export.FormButton
import com.blainemiller.scripturealone.ui.export.FormFooter
import com.blainemiller.scripturealone.ui.export.FormHeader
import com.blainemiller.scripturealone.ui.export.FormSheet
import com.blainemiller.scripturealone.ui.notes.PanelGroup
import com.blainemiller.scripturealone.ui.notes.PanelSeparator
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.blainemiller.scripturealone.ui.reader.ReaderTypography
import com.blainemiller.scripturealone.ui.reader.ReaderViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Keepsakes to give, keepsakes received, bringing notes from elsewhere, and exporting notes —
 * `LegacySettingsView.swift` ("Keepsake & Export"), from Appearance and the Notes panel's Export menu.
 * Create a Keepsake is pushed inside the sheet, as on iOS.
 *
 * Not here: iOS's live family sharing sections (a later slice on Android).
 */
@Composable
fun LegacySettingsSheet(model: ReaderViewModel, palette: ReaderPalette, onDone: () -> Unit) {
    var creating by rememberSaveable { mutableStateOf(false) }
    BackHandler { if (creating) creating = false else onDone() }
    if (creating) {
        KeepsakeCreateSheet(model, palette, onBack = { creating = false })
    } else {
        LegacySettingsForm(model, palette, onCreate = { creating = true }, onDone = onDone)
    }
}

@Composable
private fun LegacySettingsForm(model: ReaderViewModel, palette: ReaderPalette, onCreate: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val legacy = model.legacy
    val notes by model.userData.notes.collectAsState()
    var removing by remember { mutableStateOf<KeepsakeLibrary.Entry?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) legacy.pendingFile = uri
    }
    val rights = model.rights
    val mayExport = rights.permits(TranslationRights.Permission.NOTES_EXPORT)

    fun open(entry: KeepsakeLibrary.Entry) {
        legacy.library.keepsake(entry.id)?.let(model::openKeepsake)
        onDone()
    }

    FormSheet("Keepsake & Export", palette, leading = "Done", onLeading = onDone) {
        FormHeader("Your Keepsake Bible", palette)
        PanelGroup(palette) {
            FormButton("Create a Keepsake", palette, icon = Icons.Outlined.CardGiftcard, push = true, onClick = onCreate)
        }
        FormFooter(
            "Give your family a copy of your highlights and notes — a digital version of the Bible you’ve marked over the years. It’s a file you hand over yourself; nothing is sent anywhere.",
            palette,
        )

        FormHeader("Keepsakes You’ve Been Given", palette)
        PanelGroup(palette) {
            for (entry in legacy.entries) {
                KeepsakeRow(
                    entry, palette,
                    isOpen = legacy.reading?.id == entry.id,
                    onOpen = { open(entry) },
                    onShare = {
                        // The stored copy, staged under its title in the export cache the share sheet reads.
                        scope.launch {
                            runCatching {
                                val data = withContext(Dispatchers.IO) { legacy.library.file(entry.id).readBytes() }
                                val name = ExportSupport.safeName(entry.title) + "." + KeepsakeArchive.FILE_EXTENSION
                                val uris = ExportFiles.stage(context, ExportedFile(name, ExportFiles.KEEPSAKE, ExportedFile.Contents.File(data)))
                                context.startActivity(ExportFiles.shareIntent(uris, ExportFiles.KEEPSAKE, entry.title))
                            }
                        }
                    },
                    onRemove = { removing = entry },
                )
                PanelSeparator(palette)
            }
            FormButton("Open a Keepsake File…", palette, icon = Icons.Outlined.MoveToInbox) {
                pick.launch(arrayOf(ExportFiles.KEEPSAKE, "application/zip", "application/octet-stream", "*/*"))
            }
        }
        FormFooter(
            if (legacy.entries.isEmpty()) {
                "When someone gives you a Keepsake Bible, open the file here — or tap it in Messages, Gmail or Files. It stays on this device, apart from your own notes."
            } else {
                "Tap one to read it. Their highlights and notes appear in the text, just as they left them. Touch and hold for more."
            },
            palette,
        )

        FormHeader("Coming From Somewhere Else", palette)
        PanelGroup(palette) {
            FormButton("Bring Notes From Another App…", palette, icon = Icons.Outlined.FileDownload) { legacy.importing = true }
        }
        FormFooter(
            "If you've been reading in Life Bible — the app formerly called Tecarta Bible — your notes, highlights and saved verses can come with you.",
            palette,
        )

        FormHeader("Export", palette)
        PanelGroup(palette) {
            FormButton("Export All Notes…", palette, icon = Icons.Outlined.IosShare, enabled = notes.isNotEmpty() && mayExport) {
                legacy.export = ExportRequest(ExportSupport.canonicallySorted(notes.map { it.toKeepsake() }), "Notes")
            }
        }
        FormFooter(
            if (mayExport) "Your notes as a PDF to print or keep, as Markdown, or as plain text — with the verses they’re about."
            else "${model.translationAbbreviation} doesn’t allow its text to be exported. Switch to another translation to export your notes with the verses they’re about.",
            palette,
        )

        FormHeader("How This Works", palette)
        PanelGroup(palette) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "A keepsake is a snapshot. Notes you write afterwards aren’t in it — when you’d like your family to have them, make a new keepsake. Opening a newer one replaces the older copy on their device.",
                    color = palette.secondary, fontSize = 15.sp, lineHeight = 20.sp,
                )
                Text(
                    "Keepsakes are ordinary files: a ZIP archive of readable text. Even without this app, the words stay recoverable.",
                    color = palette.secondary, fontSize = 15.sp, lineHeight = 20.sp,
                )
            }
        }
    }

    removing?.let { entry ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${entry.title}?") },
            text = { Text("The keepsake will be removed from this device. If you want it again later, you’ll need the original file.") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    legacy.remove(entry.id) { model.closeKeepsake() }
                }) { Text("Remove from This Device", color = palette.red) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel", color = palette.ink) } },
            containerColor = com.blainemiller.scripturealone.ui.reader.SheetColors.popover(palette),
            titleContentColor = palette.ink,
            textContentColor = palette.secondary,
        )
    }
}

/** A keepsake in the library: name, dedication, what's inside — `KeepsakeRow`. Long-press for more. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeepsakeRow(
    entry: KeepsakeLibrary.Entry,
    palette: ReaderPalette,
    isOpen: Boolean,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onLongClick = { menu = true }, onClick = onOpen)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.AutoMirrored.Outlined.MenuBook, null, tint = palette.accent, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.title, color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontFamily = ReaderTypography.sourceSerif(17f))
                entry.manifest.dedication?.takeIf { it.isNotEmpty() }?.let {
                    Text(
                        it, color = palette.secondary, fontSize = 15.sp, fontStyle = FontStyle.Italic, fontFamily = ReaderTypography.sourceSerif(15f),
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(rowDetail(entry), color = palette.secondary, fontSize = 12.sp)
            }
            if (isOpen) Text("Open", color = palette.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Open", color = palette.ink) }, onClick = { menu = false; onOpen() })
            DropdownMenuItem(text = { Text("Share a Copy", color = palette.ink) }, onClick = { menu = false; onShare() })
            DropdownMenuItem(text = { Text("Remove…", color = palette.red) }, onClick = { menu = false; onRemove() })
        }
    }
}

private fun rowDetail(entry: KeepsakeLibrary.Entry): String {
    val parts = mutableListOf<String>()
    entry.manifest.counts?.let { parts += "${it.highlights} highlights · ${it.notes} notes" }
    parts += "made " + DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(entry.manifest.createdAt.atZone(ZoneId.systemDefault()))
    return parts.joinToString(" · ")
}

/** Name, dedication and what's inside — `KeepsakeSummaryHeader`, shared by opening and the banner. */
@Composable
fun KeepsakeSummary(keepsake: Keepsake, palette: ReaderPalette) {
    val manifest = keepsake.manifest
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.AutoMirrored.Outlined.MenuBook, null, tint = palette.accent, modifier = Modifier.size(34.dp))
        Text(manifest.displayTitle, color = palette.ink, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, fontFamily = ReaderTypography.sourceSerif(28f))
        manifest.dedication?.takeIf { it.isNotEmpty() }?.let {
            Text(it, color = palette.ink, fontSize = 17.sp, lineHeight = 23.sp, fontStyle = FontStyle.Italic, fontFamily = ReaderTypography.sourceSerif(17f))
        }
        Text(KeepsakeText.summaryDetail(manifest), color = palette.secondary, fontSize = 13.sp)
    }
}
