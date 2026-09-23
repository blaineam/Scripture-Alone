package com.blainemiller.scripturealone.ui.export

import com.blainemiller.scripturealone.ui.appearance.RatingPrompt
import android.content.ActivityNotFoundException
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.text.AppText
import com.blainemiller.scripturealone.text.countedString
import androidx.compose.ui.unit.dp
import com.blainemiller.scripturealone.data.BundledTranslations
import com.blainemiller.scripturealone.data.TranslationInfo
import com.blainemiller.scripturealone.data.keepsake.KeepsakeNote
import com.blainemiller.scripturealone.data.keepsake.NotesTextExport
import com.blainemiller.scripturealone.data.rights.TranslationRights
import com.blainemiller.scripturealone.data.translations.TranslationLibrary
import com.blainemiller.scripturealone.ui.notes.PanelGroup
import com.blainemiller.scripturealone.ui.notes.PanelSeparator
import com.blainemiller.scripturealone.ui.appearance.SwitchRow
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Export notes as a PDF, Markdown (one file or a folder of files) or plain text, with each note's
 * passages quoted in a chosen translation — `ScriptureAlone/Export/NotesExportSheet.swift`.
 *
 * Prepare Export builds the file off the main thread; then Share… hands it to the share sheet through
 * the `FileProvider`, and Save to Files… writes it where the reader chooses (a folder into a chosen
 * folder). Changing any option discards a prepared export, as on iOS.
 *
 * Verse text leaves the device only under the translation's terms: none unless it permits
 * `NOTES_EXPORT`, and each passage only within its quotation limit ([ExportSupport.verseText]). A
 * licensed text's notice ends every file.
 */
@Composable
fun NotesExportSheet(
    request: com.blainemiller.scripturealone.ui.keepsake.ExportRequest,
    currentTranslation: String,
    palette: ReaderPalette,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notes = request.notes
    val title = request.title
    val formats = ExportFormat.available(notes.size)
    var format by remember { mutableStateOf(ExportFormat.PDF) }
    var includeVerses by remember { mutableStateOf(true) }
    var translation by remember {
        mutableStateOf(request.preferredTranslation?.takeIf { it in BundledTranslations.ids } ?: currentTranslation)
    }
    var exported by remember { mutableStateOf<Staged?>(null) }
    var working by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val translations by rememberTranslationInfos(context)
    val info = translations.firstOrNull { it.id == translation }
    val allowed = info?.rights?.permits(TranslationRights.Permission.NOTES_EXPORT) ?: true

    BackHandler(onBack = onDone)
    // Any change discards what was prepared, as the Swift sheet's onChange handlers do.
    LaunchedEffect(format, includeVerses, translation) { exported = null }

    val saveFile = rememberLauncherForActivityResult(CreateDocumentOfType()) { uri ->
        val staged = exported ?: return@rememberLauncherForActivityResult
        val data = (staged.file.contents as? ExportedFile.Contents.File)?.data ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch { runCatching { ExportFiles.write(context, uri, data) }.onFailure { failure = it.message } }
    }
    val saveFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val staged = exported ?: return@rememberLauncherForActivityResult
        if (tree != null) scope.launch { runCatching { ExportFiles.writeFolder(context, tree, staged.file) }.onFailure { failure = it.message } }
    }

    fun prepare() {
        failure = null
        working = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    val quote = includeVerses && allowed && info != null
                    val file = build(context, notes, title, format, if (quote) info else null)
                    Staged(file, ExportFiles.stage(context, file))
                }
            }
            working = false
            result.onSuccess { exported = it }.onFailure { failure = it.message ?: it.javaClass.simpleName }
            // An export written counts toward the review gate; the ask waits for a finished moment.
            if (result.isSuccess) RatingPrompt.recordSignificantAction(context)
        }
    }

    FormSheet(stringResource(R.string.export_title), palette, leading = stringResource(R.string.common_done), onLeading = onDone) {
        FormHeader(
            if (notes.size == 1) stringResource(R.string.export_header_one_note, title)
            else countedString(R.string.export_header_count_one, R.string.export_header_count_other, notes.size, notes.size),
            palette,
        )
        PanelGroup(palette) {
            formats.forEachIndexed { index, option ->
                if (index > 0) PanelSeparator(palette)
                FormChoice(option.title, option.detail, option == format, palette) { format = option }
            }
        }

        FormHeader("", palette)
        PanelGroup(palette) {
            SwitchRow(stringResource(R.string.export_include_verses), includeVerses && allowed, palette, enabled = allowed) { includeVerses = it }
            if (includeVerses && allowed) {
                PanelSeparator(palette)
                FormPicker(stringResource(R.string.export_translation), translations, info, { "${it.id} — ${it.name}" }, palette) { translation = it.id }
            }
        }
        if (allowed) {
            FormFooter(stringResource(R.string.export_verses_footer), palette)
        } else {
            FormFooter(
                stringResource(R.string.export_not_allowed_footer, info?.abbreviation ?: translation),
                palette,
            )
            PanelGroup(palette, Modifier.padding(top = 14.dp)) {
                FormPicker(stringResource(R.string.export_translation), translations, info, { "${it.id} — ${it.name}" }, palette) { translation = it.id }
            }
        }

        val staged = exported
        if (staged != null) {
            FormHeader(stringResource(R.string.export_ready), palette)
            PanelGroup(palette) {
                FormButton(stringResource(R.string.export_share), palette, icon = Icons.Outlined.Share) {
                    try {
                        context.startActivity(ExportFiles.shareIntent(staged.uris, staged.file.mimeType, staged.file.name))
                    } catch (_: ActivityNotFoundException) {
                        failure = AppText.get(R.string.export_no_receiver)
                    }
                }
                PanelSeparator(palette)
                FormButton(stringResource(R.string.export_save_to_files), palette, icon = Icons.Outlined.Folder) {
                    if (staged.file.isFolder) saveFolder.launch(null) else saveFile.launch(staged.file.mimeType to staged.file.name)
                }
            }
            FormFooter(staged.file.name, palette)
        } else {
            FormHeader("", palette)
            PanelGroup(palette) {
                FormButton(stringResource(R.string.export_prepare), palette, enabled = notes.isNotEmpty(), bold = true, busy = working, onClick = ::prepare)
            }
        }

        failure?.let {
            FormHeader("", palette)
            PanelGroup(palette) { FormFooter(it, palette, color = palette.red) }
        }
    }
}

/** A prepared export and the content URIs the share sheet carries. */
private class Staged(val file: ExportedFile, val uris: List<android.net.Uri>)

/**
 * The export itself — `NotesExportSheet.prepare`. [quoted] is the translation to quote, or null to
 * leave verse text out. Blocking; call off the main thread.
 */
internal fun build(context: Context, notes: List<KeepsakeNote>, title: String, format: ExportFormat, quoted: TranslationInfo?): ExportedFile {
    val verseText: (com.blainemiller.scripturealone.data.VerseRange) -> String? = if (quoted != null) {
        // Never a network request: an online translation quotes what it has cached.
        ExportSupport.verseText(quoted.rights) { range -> TranslationLibrary.verses(context, quoted.id, range) }
    } else {
        { null }
    }
    val notice = quoted?.let { TranslationRights.attributionNotice(it.license, it.copyright) }
    val options = NotesTextExport.Options(title = title, translation = quoted?.abbreviation, notice = notice)
    val base = ExportSupport.safeName(title)
    return when (format) {
        ExportFormat.PDF -> {
            val pdf = NotesPdfRenderer(context).render(
                notes, NotesPdfDocument.Options(title = title, translation = options.translation, notice = notice), verseText,
            )
            ExportedFile("$base.pdf", ExportFiles.PDF, ExportedFile.Contents.File(pdf))
        }
        ExportFormat.MARKDOWN -> ExportedFile(
            "$base.md", ExportFiles.MARKDOWN,
            ExportedFile.Contents.File(NotesTextExport.markdown(notes, options, verseText).toByteArray()),
        )
        ExportFormat.MARKDOWN_FOLDER -> ExportedFile(
            base, ExportFiles.MARKDOWN,
            ExportedFile.Contents.Folder(NotesTextExport.markdownFiles(notes, options, verseText).map { (name, text) -> name to text.toByteArray() }),
        )
        ExportFormat.PLAIN_TEXT -> ExportedFile(
            "$base.txt", ExportFiles.TEXT,
            ExportedFile.Contents.File(NotesTextExport.plainText(notes, options, verseText).toByteArray()),
        )
    }
}

/** Every translation the reader can switch to, with its name and terms, read off the main thread. */
@Composable
fun rememberTranslationInfos(context: Context) = produceState(initialValue = emptyList<TranslationInfo>()) {
    value = withContext(Dispatchers.IO) {
        BundledTranslations.ids.mapNotNull { id -> runCatching { BundledTranslations.source(context, id).info }.getOrNull() }
    }
}
