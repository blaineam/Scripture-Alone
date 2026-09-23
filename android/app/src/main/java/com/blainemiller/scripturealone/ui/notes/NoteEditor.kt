package com.blainemiller.scripturealone.ui.notes

import androidx.compose.ui.semantics.Role
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.RemoveCircle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.userdata.Note
import com.blainemiller.scripturealone.data.userdata.RANGE_ORDER
import com.blainemiller.scripturealone.ui.camera.SlideCapture
import com.blainemiller.scripturealone.ui.camera.SlideCaptureMenu
import com.blainemiller.scripturealone.ui.camera.SlidePhotoSection
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.blainemiller.scripturealone.ui.reader.ReaderViewModel
import com.blainemiller.scripturealone.ui.reader.glass
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * One note — `ScriptureAlone/Notes/NoteEditor.swift`: its title, its passages (each opens the reader
 * there; add more by typing "Rom 8:1-17" or from the selection), the note itself, and when it was
 * created and edited. The More menu shares it as text or deletes it, after asking.
 *
 * Every change is saved as it is typed and stamps the note's edited time, as iOS's `touch()` does.
 * Add from Camera adds a sermon slide's text and passages to this note ([capture], hosted by the
 * panel), and a slide photo kept with the note shows beneath the body, as iOS's `SlidePhotoSection`.
 * Export… opens the notes export sheet on this one note (`ui/export/`).
 */
@Composable
fun NoteEditor(model: ReaderViewModel, palette: ReaderPalette, note: Note, capture: SlideCapture, onBack: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The fields edit local copies, so the cursor never fights a save coming back.
    var title by remember(note.id) { mutableStateOf(note.title) }
    var body by remember(note.id) { mutableStateOf(note.body) }
    var passageText by remember(note.id) { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }
    val current by androidx.compose.runtime.rememberUpdatedState(note)

    fun save(change: (Note) -> Note) = model.userData.save(change(current).copy(updatedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)))

    LaunchedEffect(note.id) {
        if (note.title.isEmpty() && note.body.isEmpty()) runCatching { titleFocus.requestFocus() }
    }
    // A change made elsewhere — a slide added from the camera — reaches the fields. Typing saves the
    // same text back, so this never fights the cursor.
    LaunchedEffect(note.title) { if (current.title != title) title = current.title }
    LaunchedEffect(note.body) { if (current.body != body) body = current.body }

    fun addTypedPassages() {
        val text = passageText
        scope.launch {
            val ranges = model.resolvePassages(text)
            if (ranges.isEmpty()) return@launch
            save { it.copy(anchors = (it.anchors + ranges).distinct().sortedWith(RANGE_ORDER)) }
            passageText = ""
        }
    }

    Column(Modifier.fillMaxSize()) {
        PanelHeader(note.copy(title = title).displayTitle, palette, back = true, onLeading = onBack) {
            SlideCaptureMenu(capture, palette, addingToNote = true)
            Box {
                PanelHeaderIcon(Icons.Rounded.MoreHoriz, "More", palette) { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Share Note", color = palette.ink, fontSize = 15.sp) },
                        onClick = {
                            menu = false
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, exportText(current))
                                putExtra(Intent.EXTRA_SUBJECT, current.displayTitle)
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Export…", color = palette.ink, fontSize = 15.sp) },
                        onClick = {
                            menu = false
                            model.legacy.export = com.blainemiller.scripturealone.ui.keepsake.ExportRequest(
                                listOf(current.toKeepsake()), current.displayTitle,
                            )
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete Note", color = palette.red, fontSize = 15.sp) },
                        onClick = {
                            menu = false
                            confirmDelete = true
                        },
                    )
                }
            }
        }
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(bottom = bottom + 24.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            PanelGroup(palette) {
                Field(
                    title, "Title", palette, fontSize = 22f, weight = FontWeight.SemiBold,
                    modifier = Modifier.focusRequester(titleFocus),
                ) { value ->
                    title = value
                    save { it.copy(title = value) }
                }
            }

            PanelSectionTitle("Passages", palette)
            PanelGroup(palette) {
                for (range in note.anchors) {
                    // Stored as a KJV range; shown as the translation being read numbers it.
                    PassageRow(model.displayRange(range).display, palette, onOpen = {
                        model.go(range.start)
                        onClose()
                    }, onRemove = { save { it.copy(anchors = it.anchors - range) } })
                    PanelSeparator(palette)
                }
                Row(Modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        Field(
                            passageText, "Add a passage, e.g. Rom 8:1-17", palette, singleLine = true,
                            imeAction = ImeAction.Done, onDone = ::addTypedPassages,
                        ) { passageText = it }
                    }
                    if (model.selection.isNotEmpty()) {
                        Text(
                            "Add Selection", color = palette.accent, fontSize = 16.sp,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) {
                                val ranges = model.selectedRanges
                                save { it.copy(anchors = (it.anchors + ranges).distinct().sortedWith(RANGE_ORDER)) }
                                model.clearSelection()
                            }.padding(6.dp),
                        )
                    }
                }
            }

            PanelSectionTitle("Note", palette)
            PanelGroup(palette) {
                Field(body, "", palette, minHeight = 220, label = "Note") { value ->
                    body = value
                    save { it.copy(body = value) }
                }
            }

            SlidePhotoSection(model, palette, note)

            Spacer(Modifier.height(22.dp))
            PanelGroup(palette) {
                Stamp("Created", note.createdAt, palette)
                PanelSeparator(palette)
                Stamp("Edited", note.updatedAt, palette)
            }
        }
    }

    if (confirmDelete) {
        ConfirmDelete(palette, onCancel = { confirmDelete = false }) {
            confirmDelete = false
            model.userData.deleteNote(note.id)
            onBack()
        }
    }
}

@Composable
private fun PassageRow(display: String, palette: ReaderPalette, onOpen: () -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onOpen).padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Outlined.MenuBook, null, tint = palette.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(display, color = palette.accent, fontSize = 17.sp)
        }
        Box(
            Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = "Remove $display" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.RemoveCircle, null, tint = palette.secondary, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun Field(
    value: String,
    placeholder: String,
    palette: ReaderPalette,
    modifier: Modifier = Modifier,
    fontSize: Float = 17f,
    weight: FontWeight = FontWeight.Normal,
    singleLine: Boolean = false,
    minHeight: Int = 0,
    imeAction: ImeAction = ImeAction.Default,
    onDone: () -> Unit = {},
    /** What TalkBack calls a field with no placeholder — the section title printed above it. */
    label: String? = null,
    onChange: (String) -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        textStyle = TextStyle(color = palette.ink, fontSize = fontSize.sp, fontWeight = weight, lineHeight = (fontSize * 1.3f).sp),
        cursorBrush = SolidColor(palette.accent),
        keyboardOptions = KeyboardOptions(
            capitalization = if (singleLine) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
            autoCorrectEnabled = !singleLine,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier.fillMaxWidth().heightIn(min = minHeight.dp).padding(horizontal = 18.dp, vertical = 13.dp)
            .semantics { (label ?: placeholder.takeIf { it.isNotEmpty() })?.let { contentDescription = it } },
        decorationBox = { field ->
            Box {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(placeholder, color = palette.secondary.copy(alpha = 0.7f), fontSize = fontSize.sp, fontWeight = weight)
                }
                field()
            }
        },
    )
}

@Composable
private fun Stamp(label: String, at: Instant, palette: ReaderPalette) {
    // The label keeps its width and the date wraps, so a large font size never breaks "Created".
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp).semantics(mergeDescendants = true) {}) {
        Text(label, color = palette.secondary, fontSize = 13.sp)
        Spacer(Modifier.width(12.dp))
        Text(
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault())
                .format(at.atZone(ZoneId.systemDefault())),
            color = palette.secondary, fontSize = 13.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f),
        )
    }
}

/**
 * "Delete this note?" — iOS's confirmation dialog. The iOS message says it leaves all the reader's
 * devices; an Android note lives on this one only, so this one says so.
 */
@Composable
private fun ConfirmDelete(palette: ReaderPalette, onCancel: () -> Unit, onDelete: () -> Unit) {
    Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(22.dp)).background(PanelColors.card(palette)).padding(top = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Delete this note?", color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "It will be removed from this device.", color = palette.secondary, fontSize = 14.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DialogButton("Cancel", palette.ink, palette, Modifier.weight(1f), onCancel)
                DialogButton("Delete Note", palette.red, palette, Modifier.weight(1f), onDelete)
            }
        }
    }
}

@Composable
private fun DialogButton(title: String, color: Color, palette: ReaderPalette, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(46.dp).glass(palette, CircleShape, PanelColors.card(palette)).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, color = color, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Markdown-flavoured plain text: title, passages, body — `NoteEditor.exportText`. */
internal fun exportText(note: Note): String {
    val lines = mutableListOf("# ${note.displayTitle}")
    if (note.anchors.isNotEmpty()) lines += note.anchorSummary
    if (note.body.isNotEmpty()) {
        lines += ""
        lines += note.body
    }
    return lines.joinToString("\n")
}
