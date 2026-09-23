package com.blainemiller.scripturealone.ui.importnotes

import com.blainemiller.scripturealone.ui.appearance.RatingPrompt
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.text.countedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blainemiller.scripturealone.data.BundledTranslations
import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.notesimport.ImportedNotes
import com.blainemiller.scripturealone.data.notesimport.LifeBibleImport
import com.blainemiller.scripturealone.data.notesimport.NoteImportError
import com.blainemiller.scripturealone.data.notesimport.NoteImportException
import com.blainemiller.scripturealone.data.notesimport.PastedNotesImport
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.blainemiller.scripturealone.data.translations.TranslationLibrary
import com.blainemiller.scripturealone.data.userdata.NotesImportTally
import com.blainemiller.scripturealone.ui.export.FormButton
import com.blainemiller.scripturealone.ui.export.FormField
import com.blainemiller.scripturealone.ui.export.FormFooter
import com.blainemiller.scripturealone.ui.export.FormHeader
import com.blainemiller.scripturealone.ui.export.FormSheet
import com.blainemiller.scripturealone.ui.export.FormValue
import com.blainemiller.scripturealone.ui.notes.PanelGroup
import com.blainemiller.scripturealone.ui.notes.PanelSeparator
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.blainemiller.scripturealone.ui.reader.ReaderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Brings a reader's notes across from Life Bible (formerly Tecarta Bible), or from anything that can
 * paste or export text — `ScriptureAlone/Import/LifeBibleImportView.swift`.
 *
 * As on iOS it says what it found **before** writing anything, lists what couldn't be placed rather
 * than guessing, and importing twice adds nothing twice (`UserDataStore.importNotes`). Chapter lengths
 * come from the translation being read, so a highlight across a chapter break covers real verses.
 */
@Composable
fun NotesImportSheet(model: ReaderViewModel, palette: ReaderPalette, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var found by remember { mutableStateOf<ImportedNotes?>(null) }
    var outcome by remember { mutableStateOf<NotesImportTally?>(null) }
    var pasting by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val translation = model.translationId

    BackHandler { if (pasting) pasting = false else onDone() }

    fun reading(block: suspend () -> ImportedNotes) {
        working = true
        scope.launch {
            val result = runCatching { withContext(Dispatchers.Default) { block() } }
            working = false
            result.onSuccess { found = it }.onFailure { failure = describe(it) }
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) reading { readFile(context, uri, verseCounter(context, translation)) }
    }

    if (pasting) {
        FormSheet(stringResource(R.string.import_paste_title), palette, leading = stringResource(R.string.common_cancel), onLeading = {
            pasting = false
            pasted = ""
        }) {
            FormHeader("", palette)
            PanelGroup(palette) {
                FormField(
                    pasted, stringResource(R.string.import_paste_placeholder),
                    palette, singleLine = false, minHeight = 260,
                ) { pasted = it }
            }
            FormHeader("", palette)
            PanelGroup(palette) {
                FormButton(stringResource(R.string.import_read), palette, bold = true, enabled = pasted.isNotBlank(), busy = working) {
                    val text = pasted
                    working = true
                    scope.launch {
                        val result = runCatching { withContext(Dispatchers.Default) { PastedNotesImport.parse(text, verseCounter(context, translation)) } }
                        working = false
                        result.onSuccess {
                            found = it
                            pasting = false
                            pasted = ""
                        }.onFailure { failure = describe(it) }
                    }
                }
            }
        }
    } else {
        FormSheet(stringResource(R.string.import_title), palette, leading = stringResource(R.string.common_done), onLeading = onDone) {
            val done = outcome
            val result = found
            when {
                done != null -> Finished(done, palette)
                result != null -> Preview(result, palette, working, onAdd = {
                    working = true
                    scope.launch {
                        val tally = runCatching { model.userData.importNotes(result) }
                        working = false
                        tally.onSuccess { outcome = it }.onFailure { failure = describe(it) }
                        if (tally.isSuccess) RatingPrompt.afterSuccess(context)
                    }
                }, onDifferent = { found = null })
                else -> Instructions(palette, working, onChoose = {
                    pick.launch(arrayOf("application/zip", "text/csv", "text/comma-separated-values", "text/plain", "text/tab-separated-values", "*/*"))
                }, onPaste = { pasting = true })
            }
        }
    }

    failure?.let { message ->
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text(stringResource(R.string.import_failed_title)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { failure = null }) { Text(stringResource(R.string.common_ok), color = palette.accent) } },
            containerColor = com.blainemiller.scripturealone.ui.reader.SheetColors.popover(palette),
            titleContentColor = palette.ink,
            textContentColor = palette.secondary,
        )
    }
}

@Composable
private fun Instructions(palette: ReaderPalette, working: Boolean, onChoose: () -> Unit, onPaste: () -> Unit) {
    FormHeader(stringResource(R.string.import_from_life_bible), palette)
    PanelGroup(palette) { FormButton(stringResource(R.string.import_choose_file), palette, icon = Icons.AutoMirrored.Outlined.NoteAdd, busy = working, onClick = onChoose) }
    FormFooter(
        stringResource(R.string.import_life_bible_footer),
        palette,
    )

    FormHeader(stringResource(R.string.import_from_anywhere), palette)
    PanelGroup(palette) { FormButton(stringResource(R.string.import_paste_from_any_app), palette, icon = Icons.Outlined.ContentPaste, onClick = onPaste) }
    FormFooter(
        stringResource(R.string.import_paste_footer),
        palette,
    )

    FormHeader(stringResource(R.string.import_how_to_title), palette)
    PanelGroup(palette) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Step(1, stringResource(R.string.import_how_to_step_1), palette)
            Step(2, stringResource(R.string.import_how_to_step_2), palette)
            Step(3, stringResource(R.string.import_how_to_step_3), palette)
            Step(4, stringResource(R.string.import_how_to_step_4), palette)
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.WarningAmber, null, tint = palette.secondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.import_how_to_warning), color = palette.secondary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun Step(number: Int, text: String, palette: ReaderPalette) {
    Row(verticalAlignment = Alignment.Top) {
        Text("$number", color = palette.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.width(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = palette.ink, fontSize = 15.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun Preview(result: ImportedNotes, palette: ReaderPalette, working: Boolean, onAdd: () -> Unit, onDifferent: () -> Unit) {
    FormHeader(stringResource(R.string.import_found_title), palette)
    PanelGroup(palette) {
        CountRow(stringResource(R.string.import_count_verse_notes), result.verseNotes.size, Icons.Outlined.FormatQuote, palette)
        PanelSeparator(palette)
        CountRow(stringResource(R.string.import_count_journals), result.journals.size, Icons.AutoMirrored.Outlined.MenuBook, palette)
        PanelSeparator(palette)
        CountRow(stringResource(R.string.import_count_highlights), result.highlights.size, Icons.Outlined.BorderColor, palette)
        PanelSeparator(palette)
        CountRow(stringResource(R.string.import_count_saved), result.saved.size, Icons.Outlined.FavoriteBorder, palette)
    }
    FormFooter(stringResource(R.string.import_preview_footer), palette)

    if (result.unresolved.isNotEmpty()) {
        FormHeader(countedString(R.string.import_unresolved_one, R.string.import_unresolved_other, result.unresolved.size, result.unresolved.size), palette)
        PanelGroup(palette) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (line in result.unresolved.take(8)) Text(line, color = palette.secondary, fontSize = 12.sp, maxLines = 3)
                if (result.unresolved.size > 8) Text(countedString(R.string.import_unresolved_more_one, R.string.import_unresolved_more_other, result.unresolved.size - 8, result.unresolved.size - 8), color = palette.secondary, fontSize = 12.sp)
            }
        }
        FormFooter(
            stringResource(R.string.import_unresolved_footer),
            palette,
        )
    }

    FormHeader("", palette)
    PanelGroup(palette) {
        FormButton(countedString(R.string.import_add_items_one, R.string.import_add_items_other, result.total, result.total), palette, bold = true, busy = working, enabled = result.total > 0, onClick = onAdd)
        PanelSeparator(palette)
        FormButton(stringResource(R.string.import_choose_different), palette, onClick = onDifferent)
    }
}

@Composable
private fun Finished(outcome: NotesImportTally, palette: ReaderPalette) {
    FormHeader(if (outcome.total > 0) stringResource(R.string.import_finished_title) else stringResource(R.string.import_finished_nothing_title), palette)
    PanelGroup(palette) {
        val rows = mutableListOf<@Composable () -> Unit>()
        if (outcome.total > 0) {
            rows += { CountRow(stringResource(R.string.import_count_verse_notes), outcome.notes, Icons.Outlined.FormatQuote, palette) }
            rows += { CountRow(stringResource(R.string.import_count_journals), outcome.journals, Icons.AutoMirrored.Outlined.MenuBook, palette) }
            rows += { CountRow(stringResource(R.string.import_count_highlights), outcome.highlights, Icons.Outlined.BorderColor, palette) }
            rows += { CountRow(stringResource(R.string.import_count_saved), outcome.favorites, Icons.Outlined.FavoriteBorder, palette) }
        }
        if (outcome.alreadyThere > 0) rows += { CountRow(stringResource(R.string.import_count_already_here), outcome.alreadyThere, Icons.Outlined.CheckCircle, palette) }
        rows.forEachIndexed { index, row ->
            if (index > 0) PanelSeparator(palette)
            row()
        }
    }
    FormFooter(
        if (outcome.total > 0) stringResource(R.string.import_finished_footer) else stringResource(R.string.import_finished_nothing_footer),
        palette,
    )
}

@Composable
private fun CountRow(title: String, count: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, palette: ReaderPalette) {
    Column(Modifier.fillMaxWidth()) { FormValue(title, "$count", palette, icon = icon, dim = count == 0) }
}

/**
 * Chapter lengths for the importers, from the translation being read — as iOS passes
 * `model.source.verseCount`. An online translation would have to fetch each chapter, so its chapters
 * are measured in the BSB instead (the same versification). 0 where a chapter can't be read, which the
 * importers treat as "only the verses the reference names".
 */
internal fun verseCounter(context: Context, translation: String): (BookID, Int) -> Int {
    val id = if (TranslationLibrary.kind(translation) == TranslationLibrary.Kind.ONLINE) "BSB" else translation
    val counts = HashMap<Pair<Int, Int>, Int>()
    return { book, chapter ->
        counts.getOrPut(book.number to chapter) {
            runCatching {
                BundledTranslations.source(context, id).chapter(ChapterRef(book.number, chapter)).verses.maxOfOrNull { it.ref.verse } ?: 0
            }.getOrDefault(0)
        }
    }
}

/** A zip is a Life Bible export; anything else is text, read by its shape — `read(_ url:)`. Blocking. */
internal fun readFile(context: Context, uri: Uri, verseCount: (BookID, Int) -> Int): ImportedNotes {
    val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw NoteImportException(NoteImportError.NOTHING_RECOGNISED)
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }.orEmpty()
    val isZip = name.lowercase().endsWith(".zip") ||
        (data.size >= 4 && data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte() && data[2] == 3.toByte() && data[3] == 4.toByte())
    return if (isZip) LifeBibleImport.read(data, verseCount) else PastedNotesImport.parse(decodeText(data), verseCount)
}

/** UTF-8 when it is, else Latin-1 — Swift's `String(data:encoding: .utf8) ?? .isoLatin1`. */
internal fun decodeText(data: ByteArray): String = try {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(data)).toString()
} catch (_: CharacterCodingException) {
    String(data, Charsets.ISO_8859_1)
}

private fun describe(error: Throwable): String = when (error) {
    is NoteImportException -> error.error.description
    else -> error.message ?: error.javaClass.simpleName
}
