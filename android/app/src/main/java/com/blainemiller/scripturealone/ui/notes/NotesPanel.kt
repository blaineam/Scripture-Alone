package com.blainemiller.scripturealone.ui.notes

import androidx.compose.ui.semantics.Role
import com.blainemiller.scripturealone.ui.reader.takesTaps
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.IosShare
import com.blainemiller.scripturealone.ui.export.ExportSupport
import com.blainemiller.scripturealone.ui.keepsake.ExportRequest
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.blainemiller.scripturealone.data.userdata.Favorite
import com.blainemiller.scripturealone.data.userdata.Highlight
import com.blainemiller.scripturealone.data.userdata.NotesPlace
import com.blainemiller.scripturealone.data.userdata.Note
import com.blainemiller.scripturealone.data.userdata.NoteSearch
import com.blainemiller.scripturealone.ui.camera.SlideCapture
import com.blainemiller.scripturealone.ui.camera.SlideCaptureHost
import com.blainemiller.scripturealone.ui.camera.SlideCaptureMenu
import com.blainemiller.scripturealone.ui.favorites.FavoritesSection
import com.blainemiller.scripturealone.ui.favorites.HighlightsSection
import com.blainemiller.scripturealone.ui.reader.ReaderIcons
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.blainemiller.scripturealone.ui.reader.ReaderViewModel
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.text.AppText
import com.blainemiller.scripturealone.text.countedString
import android.text.format.DateFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the panel lists — `NotesPanel.Scope`, in the same order and wording. */
enum class NotesScope(@StringRes val titleRes: Int) {
    NOTES(R.string.notes_title), HIGHLIGHTS(R.string.notes_scope_highlights), FAVORITES(R.string.notes_scope_favorites),
}

/**
 * Every note, highlight and favorite, searchable, and narrowed to the book or chapter on screen
 * ([NotesPlace]) — `ScriptureAlone/Notes/NotesPanel.swift`. On iPhone it is a sheet over the text; so it is
 * here. A note opens in [NoteEditor] within the same sheet, as iOS pushes it onto the panel's stack;
 * [openNote] is that stack, hoisted so Add Note in the selection bar can open straight onto a note.
 *
 * Scan Slide starts a note from a photographed sermon slide (`ui/camera/`); the capture flow is hosted
 * here, over the whole panel, for the list and for the open note's Add from Camera alike.
 *
 * The Export menu exports all notes or those shown (`ui/export/`) and opens Keepsake & Export
 * (`ui/keepsake/`); both sheets are hosted by the reader.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotesPanel(
    model: ReaderViewModel,
    palette: ReaderPalette,
    notes: List<Note>,
    highlights: List<Highlight>,
    favorites: List<Favorite>,
    openNote: String?,
    onOpenNoteChange: (String?) -> Unit,
    onDismiss: () -> Unit,
    /** The scope to open on — Favorites for a favorites link or shortcut. */
    initialScope: NotesScope = NotesScope.NOTES,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var search by rememberSaveable { mutableStateOf("") }
    var scope by rememberSaveable { mutableStateOf(initialScope) }
    var place by rememberSaveable { mutableStateOf(NotesPlace.ALL) }

    fun dismiss() {
        keyboard?.hide()
        onDismiss()
    }

    val editing = openNote?.let { id -> notes.firstOrNull { it.id.toString() == id } }
    BackHandler { if (openNote != null) onOpenNoteChange(null) else dismiss() }
    val capture = remember { SlideCapture() }

    // The slide capture draws over the whole panel — the scanner and review sheet cover everything.
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 10.dp)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(PanelColors.background(palette))
                .takesTaps(),
        ) {
            if (openNote != null) {
                if (editing != null) {
                    NoteEditor(model, palette, editing, capture, onBack = { onOpenNoteChange(null) }, onClose = ::dismiss)
                } else {
                    PanelHeader(stringResource(R.string.notes_section_note), palette, back = true, onLeading = { onOpenNoteChange(null) })
                    EmptyState(ReaderIcons.NoteText, stringResource(R.string.notes_deleted), "", palette)
                }
                return@Column
            }

            val location = model.location
            val verseCount = { book: com.blainemiller.scripturealone.data.canon.BookID, chapter: Int ->
                model.verseCount(ChapterRef(book.number, chapter))
            }
            val filtered = notes.filter { note ->
                // Anchors are KJV ranges; the place is the book or chapter as the translation numbers it.
                (place == NotesPlace.ALL || note.anchors.any { place.contains(model.displayRange(it), location) }) &&
                    NoteSearch.matches(note, search, verseCount, model.numbering)
            }
            PanelHeader(stringResource(R.string.notes_title), palette, back = false, onLeading = ::dismiss) {
                PanelHeaderIcon(ReaderIcons.SquareAndPencil, stringResource(R.string.notes_new_note), palette) {
                    onOpenNoteChange(model.newNote().id.toString())
                }
                SlideCaptureMenu(capture, palette)
                ExportMenu(model, palette, notes, if (scope == NotesScope.NOTES) filtered else notes)
            }
            // Each list has its own prompt — `NotesPanel.searchPrompt`.
            val prompt = when (scope) {
                NotesScope.NOTES -> stringResource(R.string.notes_search_notes)
                NotesScope.HIGHLIGHTS -> stringResource(R.string.notes_search_highlights)
                NotesScope.FAVORITES -> stringResource(R.string.notes_search_favorites)
            }
            PanelSearchField(search, prompt, palette, onChange = { search = it })
            Spacer(Modifier.height(12.dp))
            val scopeTitles = NotesScope.entries.associateWith { stringResource(it.titleRes) }
            Segmented(NotesScope.entries, scope, { scopeTitles.getValue(it) }, palette) { scope = it }
            Spacer(Modifier.height(8.dp))
            val placeTitles = NotesPlace.entries.associateWith { stringResource(it.titleRes) }
            Segmented(NotesPlace.entries, place, { placeTitles.getValue(it) }, palette) { place = it }
            Spacer(Modifier.height(12.dp))

            val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            if (scope == NotesScope.FAVORITES) {
                FavoritesSection(model, palette, favorites, search, bottom, onOpened = ::dismiss, place = place)
                return@Column
            }
            if (scope == NotesScope.HIGHLIGHTS) {
                HighlightsSection(model, palette, highlights, search, place, bottom, onOpened = ::dismiss)
                return@Column
            }

            if (filtered.isEmpty()) {
                EmptyState(
                    ReaderIcons.NoteText,
                    if (search.isBlank()) stringResource(R.string.notes_empty_title) else stringResource(R.string.notes_no_matches_title),
                    if (search.isBlank()) stringResource(R.string.notes_empty_message)
                    else stringResource(R.string.notes_no_matches_message),
                    palette,
                )
                return@Column
            }
            LazyColumn(
                Modifier.fillMaxSize().imePadding(),
                contentPadding = PaddingValues(bottom = bottom + 24.dp),
            ) {
                itemsIndexed(filtered, key = { _, note -> note.id }) { index, note ->
                    val shape = when {
                        filtered.size == 1 -> RoundedCornerShape(22.dp)
                        index == 0 -> RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
                        index == filtered.lastIndex -> RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp)
                        else -> RoundedCornerShape(0.dp)
                    }
                    Column(Modifier.padding(horizontal = 16.dp).clip(shape).background(PanelColors.card(palette))) {
                        NoteRow(note, palette, onOpen = { onOpenNoteChange(note.id.toString()) }, onDelete = { model.userData.deleteNote(note.id) })
                        if (index < filtered.lastIndex) PanelSeparator(palette)
                    }
                }
            }
        }
        // A new note from a slide opens once saved; one added to the open note stays where it is.
        SlideCaptureHost(capture, model, palette, notes, appendTo = editing) { note ->
            if (openNote == null) onOpenNoteChange(note.id.toString())
        }
    }
}

/**
 * The Export menu — `NotesPanel`'s third toolbar item: Export All Notes…, Export N Shown… when a
 * search or scope narrows the list, and Keepsake & Export….
 */
@Composable
private fun ExportMenu(model: ReaderViewModel, palette: ReaderPalette, notes: List<Note>, shown: List<Note>) {
    var open by remember { mutableStateOf(false) }
    fun export(list: List<Note>) {
        open = false
        val sorted = ExportSupport.canonicallySorted(list.map { it.toKeepsake() })
        model.legacy.export = ExportRequest(sorted, if (sorted.size == 1) sorted[0].displayTitle else AppText.get(R.string.notes_title))
    }
    Box {
        PanelHeaderIcon(Icons.Outlined.IosShare, stringResource(R.string.common_export), palette) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.notes_export_all), color = if (notes.isEmpty()) palette.secondary else palette.ink, fontSize = 15.sp) },
                enabled = notes.isNotEmpty(),
                onClick = { export(notes) },
            )
            if (shown.size != notes.size) {
                DropdownMenuItem(
                    text = { Text(countedString(R.string.notes_export_shown_one, R.string.notes_export_shown_other, shown.size, shown.size), color = if (shown.isEmpty()) palette.secondary else palette.ink, fontSize = 15.sp) },
                    enabled = shown.isNotEmpty(),
                    onClick = { export(shown) },
                )
            }
            HorizontalDivider(color = palette.secondary.copy(alpha = 0.25f))
            DropdownMenuItem(
                text = { Text(stringResource(R.string.notes_keepsake_export), color = palette.ink, fontSize = 15.sp) },
                onClick = {
                    open = false
                    model.legacy.settingsOpen = true
                },
            )
        }
    }
}

/** A row: title and date, the passages in the accent, then two lines of the body — `NoteRow`. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(note: Note, palette: ReaderPalette, onOpen: () -> Unit, onDelete: () -> Unit) {
    // Long press offers Delete — the iOS swipe-to-delete.
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier.fillMaxWidth().combinedClickable(role = Role.Button, onLongClickLabel = stringResource(R.string.notes_show_options), onLongClick = { menu = true }, onClick = onOpen)
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    note.displayTitle, color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(shortDate(note), color = palette.secondary, fontSize = 12.sp)
            }
            if (note.anchors.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    note.anchorSummary, color = palette.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (note.body.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(note.body, color = palette.secondary, fontSize = 16.sp, lineHeight = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_delete), color = palette.red, fontSize = 15.sp) },
                onClick = {
                    menu = false
                    onDelete()
                },
            )
        }
    }
}

/** "Sep 21" — `.dateTime.month(.abbreviated).day()`. */
private fun shortDate(note: Note): String =
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMd"), Locale.getDefault())
        .format(note.updatedAt.atZone(ZoneId.systemDefault()))
