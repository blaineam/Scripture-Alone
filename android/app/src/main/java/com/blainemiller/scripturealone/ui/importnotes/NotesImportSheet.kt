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
        FormSheet("Paste Notes", palette, leading = "Cancel", onLeading = {
            pasting = false
            pasted = ""
        }) {
            FormHeader("", palette)
            PanelGroup(palette) {
                FormField(
                    pasted, "John 3:16 — the whole gospel in one verse\n\nRomans 8:28 — not that all things are good",
                    palette, singleLine = false, minHeight = 260,
                ) { pasted = it }
            }
            FormHeader("", palette)
            PanelGroup(palette) {
                FormButton("Read", palette, bold = true, enabled = pasted.isNotBlank(), busy = working) {
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
        FormSheet("Bring Your Notes", palette, leading = "Done", onLeading = onDone) {
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
            title = { Text("That didn't work") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { failure = null }) { Text("OK", color = palette.accent) } },
            containerColor = com.blainemiller.scripturealone.ui.reader.SheetColors.popover(palette),
            titleContentColor = palette.ink,
            textContentColor = palette.secondary,
        )
    }
}

@Composable
private fun Instructions(palette: ReaderPalette, working: Boolean, onChoose: () -> Unit, onPaste: () -> Unit) {
    FormHeader("From Life Bible", palette)
    PanelGroup(palette) { FormButton("Choose a File…", palette, icon = Icons.AutoMirrored.Outlined.NoteAdd, busy = working, onClick = onChoose) }
    FormFooter(
        "Life Bible — the app that used to be called Tecarta Bible — can export everything you've written. Your notes, highlights and saved verses come across; nothing is sent anywhere, and the file never leaves your device.",
        palette,
    )

    FormHeader("From Anywhere Else", palette)
    PanelGroup(palette) { FormButton("Paste Notes From Any App…", palette, icon = Icons.Outlined.ContentPaste, onClick = onPaste) }
    FormFooter(
        "Paste notes, or a CSV you exported. Each entry needs to start with a reference — “John 3:16” — so it can be attached to the right verse. Anything that doesn't name a verse is listed for you rather than guessed at.",
        palette,
    )

    FormHeader("How to get the file", palette)
    PanelGroup(palette) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Step(1, "Open Life Bible, or sign in at lifebible.com.", palette)
            Step(2, "Tap the menu, then Settings.", palette)
            Step(3, "Scroll to Advanced and tap “Export your data”.", palette)
            Step(4, "Save LifeBibleData.zip, then choose it above.", palette)
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.WarningAmber, null, tint = palette.secondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Don't tap “Delete your account” — it sits just below Export.", color = palette.secondary, fontSize = 13.sp)
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
    FormHeader("Found in this file", palette)
    PanelGroup(palette) {
        CountRow("Notes on verses", result.verseNotes.size, Icons.Outlined.FormatQuote, palette)
        PanelSeparator(palette)
        CountRow("Journal entries", result.journals.size, Icons.AutoMirrored.Outlined.MenuBook, palette)
        PanelSeparator(palette)
        CountRow("Highlights", result.highlights.size, Icons.Outlined.BorderColor, palette)
        PanelSeparator(palette)
        CountRow("Saved verses", result.saved.size, Icons.Outlined.FavoriteBorder, palette)
    }
    FormFooter("Nothing has been added yet. Importing twice is safe — anything already here is left alone rather than duplicated.", palette)

    if (result.unresolved.isNotEmpty()) {
        FormHeader("${result.unresolved.size} couldn't be placed", palette)
        PanelGroup(palette) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (line in result.unresolved.take(8)) Text(line, color = palette.secondary, fontSize = 12.sp, maxLines = 3)
                if (result.unresolved.size > 8) Text("…and ${result.unresolved.size - 8} more", color = palette.secondary, fontSize = 12.sp)
            }
        }
        FormFooter(
            "These name something this app can't find a verse for. They'll be skipped rather than guessed at, so you can copy them over yourself.",
            palette,
        )
    }

    FormHeader("", palette)
    PanelGroup(palette) {
        FormButton("Add ${result.total} Items", palette, bold = true, busy = working, enabled = result.total > 0, onClick = onAdd)
        PanelSeparator(palette)
        FormButton("Choose a Different File", palette, onClick = onDifferent)
    }
}

@Composable
private fun Finished(outcome: NotesImportTally, palette: ReaderPalette) {
    FormHeader(if (outcome.total > 0) "Brought across" else "Nothing new to add", palette)
    PanelGroup(palette) {
        val rows = mutableListOf<@Composable () -> Unit>()
        if (outcome.total > 0) {
            rows += { CountRow("Notes on verses", outcome.notes, Icons.Outlined.FormatQuote, palette) }
            rows += { CountRow("Journal entries", outcome.journals, Icons.AutoMirrored.Outlined.MenuBook, palette) }
            rows += { CountRow("Highlights", outcome.highlights, Icons.Outlined.BorderColor, palette) }
            rows += { CountRow("Saved verses", outcome.favorites, Icons.Outlined.FavoriteBorder, palette) }
        }
        if (outcome.alreadyThere > 0) rows += { CountRow("Already here", outcome.alreadyThere, Icons.Outlined.CheckCircle, palette) }
        rows.forEachIndexed { index, row ->
            if (index > 0) PanelSeparator(palette)
            row()
        }
    }
    FormFooter(
        if (outcome.total > 0) "They're in your notes and highlights now, kept on this device." else "Everything in that file was already here.",
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
