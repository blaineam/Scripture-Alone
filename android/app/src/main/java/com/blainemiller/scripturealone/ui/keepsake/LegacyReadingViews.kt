package com.blainemiller.scripturealone.ui.keepsake

import androidx.compose.ui.semantics.Role
import com.blainemiller.scripturealone.ui.reader.takesTaps
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.data.keepsake.Keepsake
import com.blainemiller.scripturealone.data.keepsake.KeepsakeNote
import com.blainemiller.scripturealone.data.keepsake.NotesTextExport
import com.blainemiller.scripturealone.ui.export.ExportSupport
import com.blainemiller.scripturealone.ui.notes.EmptyState
import com.blainemiller.scripturealone.ui.notes.PanelColors
import com.blainemiller.scripturealone.ui.notes.PanelHeader
import com.blainemiller.scripturealone.ui.notes.PanelHeaderIcon
import com.blainemiller.scripturealone.ui.notes.PanelSearchField
import com.blainemiller.scripturealone.ui.notes.PanelSeparator
import com.blainemiller.scripturealone.ui.notes.Segmented
import com.blainemiller.scripturealone.ui.reader.ReaderIcons
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.blainemiller.scripturealone.ui.reader.ReaderTypography
import com.blainemiller.scripturealone.ui.reader.ReaderViewModel
import com.blainemiller.scripturealone.ui.reader.SheetColors
import com.blainemiller.scripturealone.ui.reader.glass
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * "Reading Dad’s Bible · Read-only keepsake", above the text while a keepsake is open — `LegacyBanner`.
 * A tap on the dedication expands it; My Bible steps back out.
 */
@Composable
fun LegacyBanner(keepsake: Keepsake, palette: ReaderPalette, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val manifest = keepsake.manifest
    Row(
        modifier.padding(horizontal = 16.dp).widthIn(max = 620.dp).fillMaxWidth()
            // Nearly opaque: without a blur behind it, text scrolling beneath would show through.
            .glass(palette, RoundedCornerShape(20.dp), lifted = true, opacity = 0.985f)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.AutoMirrored.Outlined.MenuBook, null, tint = palette.accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                stringResource(R.string.keepsake_banner_reading, manifest.displayTitle), color = palette.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = ReaderTypography.sourceSerif(15f),
            )
            manifest.dedication?.takeIf { it.isNotEmpty() }?.let {
                Text(
                    it, color = palette.ink, fontSize = 13.sp, fontStyle = FontStyle.Italic, fontFamily = ReaderTypography.sourceSerif(13f),
                    maxLines = if (expanded) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(interactionSource = null, indication = null, onClickLabel = stringResource(if (expanded) R.string.keepsake_banner_show_less else R.string.keepsake_banner_show_dedication)) { expanded = !expanded },
                )
            }
            Text(stringResource(R.string.keepsake_banner_read_only), color = palette.secondary, fontSize = 11.sp)
        }
        Spacer(Modifier.width(8.dp))
        val returnLabel = stringResource(R.string.keepsake_banner_return)
        Box(
            Modifier.clip(CircleShape).background(SheetColors.buttonFill(palette)).clickable(role = Role.Button, onClick = onClose)
                .semantics { contentDescription = returnLabel }
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) { Text(stringResource(R.string.keepsake_banner_my_bible), color = palette.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
    }
}

private enum class LegacyScope(@StringRes val titleRes: Int) { ALL(R.string.keepsake_notes_scope_all), CHAPTER(R.string.keepsake_notes_scope_chapter) }

/**
 * The keepsake's notes, read-only, in place of the reader's own Notes panel — `LegacyNotesPanel`.
 * Search, All Notes / This Chapter, Export (the keepsake's notes, titled "Dad’s Notes"), and each note
 * as it was written, with Copy Note. Nothing can be changed.
 */
@Composable
fun LegacyNotesPanel(
    model: ReaderViewModel,
    keepsake: Keepsake,
    palette: ReaderPalette,
    openNote: String?,
    onOpenNoteChange: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    var scope by rememberSaveable { mutableStateOf(LegacyScope.ALL) }
    BackHandler { if (openNote != null) onOpenNoteChange(null) else onDismiss() }
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(top = 10.dp)
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(PanelColors.background(palette))
            .takesTaps(),
    ) {
        val open = openNote?.let { id -> keepsake.notes.firstOrNull { it.id.toString() == id } }
        if (open != null) {
            LegacyNoteDetail(model, open, palette, onBack = { onOpenNoteChange(null) }, onClose = onDismiss)
            return@Column
        }
        PanelHeader(stringResource(R.string.keepsake_notes_title), palette, back = false, onLeading = onDismiss) {
            PanelHeaderIcon(Icons.Outlined.IosShare, stringResource(R.string.keepsake_notes_export), palette) {
                if (keepsake.notes.isNotEmpty()) {
                    model.legacy.export = ExportRequest(
                        ExportSupport.canonicallySorted(keepsake.notes),
                        ExportSupport.notesTitle(keepsake.manifest.ownerName),
                        keepsake.manifest.preferredTranslation,
                    )
                }
            }
        }
        PanelSearchField(search, stringResource(R.string.keepsake_notes_search_placeholder), palette) { search = it }
        Spacer(Modifier.height(12.dp))
        val scopeTitles = LegacyScope.entries.associateWith { stringResource(it.titleRes) }
        Segmented(LegacyScope.entries, scope, { scopeTitles.getValue(it) }, palette) { scope = it }
        Text(
            stringResource(R.string.keepsake_notes_read_only, keepsake.manifest.displayTitle), color = palette.secondary, fontSize = 13.sp,
            modifier = Modifier.padding(start = 32.dp, top = 8.dp, bottom = 10.dp),
        )
        val location = model.location
        val term = search.trim()
        val notes = keepsake.notes.sortedByDescending { it.updatedAt }.filter { note ->
            (scope == LegacyScope.ALL || note.touches(location.book, location.chapter)) &&
                (term.isEmpty() || listOf(note.title, note.body, note.anchorSummary).any { it.contains(term, ignoreCase = true) })
        }
        if (notes.isEmpty()) {
            EmptyState(
                ReaderIcons.NoteText,
                stringResource(if (term.isEmpty()) R.string.keepsake_notes_empty_title else R.string.keepsake_notes_no_matches),
                stringResource(
                    if (term.isEmpty() && scope == LegacyScope.CHAPTER) R.string.keepsake_notes_empty_chapter else R.string.keepsake_notes_try_another_word,
                ),
                palette,
            )
            return@Column
        }
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + 24.dp)) {
            itemsIndexed(notes, key = { _, note -> note.id }) { index, note ->
                val shape = when {
                    notes.size == 1 -> RoundedCornerShape(22.dp)
                    index == 0 -> RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
                    index == notes.lastIndex -> RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp)
                    else -> RoundedCornerShape(0.dp)
                }
                Column(Modifier.padding(horizontal = 16.dp).clip(shape).background(PanelColors.card(palette))) {
                    LegacyNoteRow(note, palette) { onOpenNoteChange(note.id.toString()) }
                    if (index < notes.lastIndex) PanelSeparator(palette)
                }
            }
        }
    }
}

@Composable
private fun LegacyNoteRow(note: KeepsakeNote, palette: ReaderPalette, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen).padding(horizontal = 18.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                note.displayTitle, color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(note.updatedAt.atZone(ZoneId.systemDefault())),
                color = palette.secondary, fontSize = 12.sp,
            )
        }
        if (note.anchors.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(note.anchorSummary, color = palette.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (note.body.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(note.body, color = palette.secondary, fontSize = 16.sp, lineHeight = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** One note, as it was written: selectable, copyable, never editable — `LegacyNoteDetail`. */
@Composable
private fun LegacyNoteDetail(model: ReaderViewModel, note: KeepsakeNote, palette: ReaderPalette, onBack: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_200)
            copied = false
        }
    }
    PanelHeader(note.displayTitle, palette, back = true, onLeading = onBack) {
        PanelHeaderIcon(if (copied) Icons.Rounded.Check else Icons.Outlined.ContentCopy, stringResource(if (copied) R.string.keepsake_note_copied else R.string.keepsake_note_copy), palette) {
            copyText(context, note)
            copied = true
        }
    }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    SelectionContainer {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = bottom + 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(note.displayTitle, color = palette.ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, fontFamily = ReaderTypography.sourceSerif(24f))
            if (note.anchors.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (range in note.anchors) {
                        Row(
                            Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) {
                                model.go(range.start)
                                onClose()
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.MenuBook, null, tint = palette.accent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(range.display, color = palette.accent, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            if (note.body.isNotEmpty()) {
                Text(note.body, color = palette.ink, fontSize = 17.sp, lineHeight = 26.sp, fontFamily = ReaderTypography.sourceSerif(17f))
            }
            Text(NotesTextExport.dateLine(note), color = palette.secondary, fontSize = 13.sp)
        }
    }
}

private fun copyText(context: Context, note: KeepsakeNote) {
    var text = note.displayTitle
    if (note.anchors.isNotEmpty()) text += "\n" + note.anchorSummary
    if (note.body.isNotEmpty()) text += "\n\n" + note.body
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(note.displayTitle, text))
}
