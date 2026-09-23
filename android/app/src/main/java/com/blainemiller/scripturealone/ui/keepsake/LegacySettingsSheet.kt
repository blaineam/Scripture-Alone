package com.blainemiller.scripturealone.ui.keepsake

import androidx.compose.ui.semantics.Role
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blainemiller.scripturealone.R
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
import com.blainemiller.scripturealone.text.AppText
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

    FormSheet(stringResource(R.string.keepsake_settings_title), palette, leading = stringResource(R.string.common_done), onLeading = onDone) {
        FormHeader(stringResource(R.string.keepsake_settings_section_yours), palette)
        PanelGroup(palette) {
            FormButton(stringResource(R.string.keepsake_create_a_keepsake), palette, icon = Icons.Outlined.CardGiftcard, push = true, onClick = onCreate)
        }
        FormFooter(
            stringResource(R.string.keepsake_settings_give_footer),
            palette,
        )

        FormHeader(stringResource(R.string.keepsake_settings_section_given), palette)
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
            FormButton(stringResource(R.string.keepsake_settings_open_file), palette, icon = Icons.Outlined.MoveToInbox) {
                pick.launch(arrayOf(ExportFiles.KEEPSAKE, "application/zip", "application/octet-stream", "*/*"))
            }
        }
        FormFooter(
            stringResource(
                if (legacy.entries.isEmpty()) R.string.keepsake_settings_given_footer_empty else R.string.keepsake_settings_given_footer,
            ),
            palette,
        )

        FormHeader(stringResource(R.string.keepsake_settings_section_elsewhere), palette)
        PanelGroup(palette) {
            FormButton(stringResource(R.string.keepsake_settings_bring_notes), palette, icon = Icons.Outlined.FileDownload) { legacy.importing = true }
        }
        FormFooter(
            stringResource(R.string.keepsake_settings_bring_notes_footer),
            palette,
        )

        FormHeader(stringResource(R.string.keepsake_settings_section_export), palette)
        PanelGroup(palette) {
            FormButton(stringResource(R.string.keepsake_settings_export_all), palette, icon = Icons.Outlined.IosShare, enabled = notes.isNotEmpty() && mayExport) {
                legacy.export = ExportRequest(ExportSupport.canonicallySorted(notes.map { it.toKeepsake() }), context.getString(R.string.keepsake_settings_export_title))
            }
        }
        FormFooter(
            if (mayExport) stringResource(R.string.keepsake_settings_export_footer)
            else stringResource(R.string.keepsake_settings_export_not_allowed, model.translationAbbreviation),
            palette,
        )

        FormHeader(stringResource(R.string.keepsake_settings_section_how), palette)
        PanelGroup(palette) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.keepsake_settings_how_snapshot),
                    color = palette.secondary, fontSize = 15.sp, lineHeight = 20.sp,
                )
                Text(
                    stringResource(R.string.keepsake_settings_how_files),
                    color = palette.secondary, fontSize = 15.sp, lineHeight = 20.sp,
                )
            }
        }
    }

    removing?.let { entry ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.keepsake_remove_title, entry.title)) },
            text = { Text(stringResource(R.string.keepsake_remove_message)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    legacy.remove(entry.id) { model.closeKeepsake() }
                }) { Text(stringResource(R.string.keepsake_remove_confirm), color = palette.red) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.common_cancel), color = palette.ink) } },
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
            Modifier.fillMaxWidth().combinedClickable(role = Role.Button, onLongClickLabel = stringResource(R.string.keepsake_row_show_options), onLongClick = { menu = true }, onClick = onOpen)
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
            if (isOpen) Text(stringResource(R.string.keepsake_row_open_badge), color = palette.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.common_open), color = palette.ink) }, onClick = { menu = false; onOpen() })
            DropdownMenuItem(text = { Text(stringResource(R.string.keepsake_row_share_copy), color = palette.ink) }, onClick = { menu = false; onShare() })
            DropdownMenuItem(text = { Text(stringResource(R.string.keepsake_row_remove), color = palette.red) }, onClick = { menu = false; onRemove() })
        }
    }
}

private fun rowDetail(entry: KeepsakeLibrary.Entry): String {
    val parts = mutableListOf<String>()
    entry.manifest.counts?.let {
        parts += AppText.plural(R.string.keepsake_count_highlights_one, R.string.keepsake_count_highlights_other, it.highlights, it.highlights)
        parts += AppText.plural(R.string.keepsake_count_notes_one, R.string.keepsake_count_notes_other, it.notes, it.notes)
    }
    parts += AppText.get(
        R.string.keepsake_row_made,
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(entry.manifest.createdAt.atZone(ZoneId.systemDefault())),
    )
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
