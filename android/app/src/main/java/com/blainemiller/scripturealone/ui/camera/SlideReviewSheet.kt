package com.blainemiller.scripturealone.ui.camera

import com.blainemiller.scripturealone.ui.reader.takesTaps
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.camera.SlideImage
import com.blainemiller.scripturealone.data.camera.SlideRecognizer
import com.blainemiller.scripturealone.data.reference.Passage
import com.blainemiller.scripturealone.data.reference.ReferenceParser
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.blainemiller.scripturealone.data.slides.SlideParser
import com.blainemiller.scripturealone.data.slides.SlideReading
import com.blainemiller.scripturealone.data.userdata.Note
import com.blainemiller.scripturealone.data.userdata.RANGE_ORDER
import com.blainemiller.scripturealone.ui.notes.PanelColors
import com.blainemiller.scripturealone.ui.notes.PanelGroup
import com.blainemiller.scripturealone.ui.notes.PanelSectionTitle
import com.blainemiller.scripturealone.ui.notes.PanelSeparator
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.blainemiller.scripturealone.ui.reader.ReaderViewModel
import com.blainemiller.scripturealone.ui.reader.SheetColors
import com.blainemiller.scripturealone.ui.reader.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

private sealed interface Phase {
    data object Reading : Phase
    data object Ready : Phase
    data class Failed(val message: String) : Phase
}

private class ReviewLine(text: String) {
    val id: UUID = UUID.randomUUID()
    var text by mutableStateOf(text)
    var included by mutableStateOf(true)
}

/** The review sheet's frame: a dimmed backdrop and a sheet rising to just below the status bar. */
@Composable
internal fun ReviewSheetFrame(visible: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)).takesTaps(onDismiss))
    }
    AnimatedVisibility(visible, enter = slideInVertically(tween(320)) { it }, exit = slideOutVertically(tween(240)) { it }) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(top = 10.dp)) { content() }
    }
}

/**
 * What was read from the slide, before anything is saved — `SlideReviewView.swift`: an editable
 * title, the passages as removable chips, and the other lines with checkboxes. The photo itself is
 * discarded unless the reader chooses to keep it. Saves a new note, or adds to one: its passages
 * through [SlideParser.merging], its lines through [SlideParser.append].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SlideReviewSheet(
    model: ReaderViewModel,
    palette: ReaderPalette,
    slide: ScannedSlide,
    notes: List<Note>,
    appendTo: Note?,
    onDismiss: () -> Unit,
    onSaved: (Note) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<Phase>(Phase.Reading) }
    var title by remember { mutableStateOf("") }
    val ranges = remember { mutableStateListOf<VerseRange>() }
    val lines = remember { mutableStateListOf<ReviewLine>() }
    var passageText by remember { mutableStateOf("") }
    var keepPhoto by remember { mutableStateOf(false) }
    /** null = a new note. */
    var destination by remember { mutableStateOf(appendTo?.id) }
    var saving by remember { mutableStateOf(false) }
    var announcement by remember { mutableStateOf("") }

    val targetNote = destination?.let { id -> notes.firstOrNull { it.id == id } }
    val choices = remember(notes, appendTo) {
        val recent = notes.take(8)
        if (appendTo != null && recent.none { it.id == appendTo.id }) listOf(appendTo) + recent else recent
    }
    val includedLines = lines.filter { it.included }.map { it.text.trim() }.filter { it.isNotEmpty() }
    val nothingToSave = title.isBlank() && ranges.isEmpty() && includedLines.isEmpty()

    /** Unchecks lines the destination already has (a later slide's running header); all back on for a new note. */
    fun markLinesTheNoteHas() {
        for (line in lines) {
            line.included = targetNote?.let { !SlideParser.noteAlreadyHas(line.text, it.title, it.body) } ?: true
        }
    }

    fun apply(reading: SlideReading, resolved: List<VerseRange>) {
        title = reading.title
        ranges.clear()
        ranges += resolved
        lines.clear()
        lines += reading.bodyLines.map(::ReviewLine)
        // Adding to a note that already has this title: don't repeat it as a heading.
        if (appendTo != null && appendTo.title.equals(title, ignoreCase = true)) title = ""
        markLinesTheNoteHas()
    }

    LaunchedEffect(slide.id) {
        try {
            val (_, reading) = SlideRecognizer.read(context, slide.image)
            apply(reading, resolve(model, reading.passages))
            phase = Phase.Ready
            announcement = listOfNotNull(
                reading.title.takeIf { it.isNotEmpty() }?.let { "Title: $it" },
                ranges.size.takeIf { it > 0 }?.let { "$it passages" },
                "${lines.size} other lines",
            ).joinToString(", ", prefix = "Slide read. ")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            phase = Phase.Failed("The text on this photo couldn’t be read. You can still type a title and passages.")
        }
    }
    LaunchedEffect(destination) { markLinesTheNoteHas() }

    fun addTypedPassages() {
        val text = passageText
        scope.launch {
            val added = resolve(model, ReferenceParser.parseList(text))
            if (added.isEmpty()) return@launch
            for (range in added) if (range !in ranges) ranges += range
            passageText = ""
        }
    }

    fun save() {
        if (saving) return
        saving = true
        val heading = title.trim()
        val slideRanges = ranges.toList()
        val slideLines = includedLines
        val target = targetNote
        scope.launch {
            val photo = if (keepPhoto) withContext(Dispatchers.Default) { SlideImage.jpeg(slide.image) } else null
            val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
            val note = if (target != null) {
                val anchors = SlideParser.merging(target.anchors, slideRanges).sortedWith(RANGE_ORDER)
                if (target.title.isBlank()) {
                    target.copy(title = heading, body = SlideParser.append(null, slideLines, target.body), anchors = anchors, updatedAt = now)
                } else {
                    val sameTitle = target.title.equals(heading, ignoreCase = true)
                    target.copy(
                        body = SlideParser.append(if (sameTitle) null else heading, slideLines, target.body, target.title),
                        anchors = anchors, updatedAt = now,
                    )
                }
            } else {
                Note(
                    title = heading, body = SlideParser.body(slideLines),
                    anchors = SlideParser.merging(emptyList(), slideRanges).sortedWith(RANGE_ORDER),
                    createdAt = now, updatedAt = now, origin = "camera",
                )
            }
            model.userData.save(note, photo)
            onSaved(note)
        }
    }

    BackHandler(onBack = onDismiss)
    val adding = targetNote != null
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(PanelColors.background(palette))
            .takesTaps(),
    ) {
        ReviewHeader(
            if (adding) "Add to Note" else "New Note", if (adding) "Add to Note" else "Create Note", palette,
            saveEnabled = phase != Phase.Reading && !nothingToSave && !saving, onCancel = onDismiss, onSave = ::save,
        )
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(bottom = bottom + 24.dp)) {
            Spacer(Modifier.height(8.dp))
            // The photo, and whether to keep it.
            PanelGroup(palette) {
                Image(
                    slide.image.asImageBitmap(), "Photo of the slide", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).padding(horizontal = 18.dp, vertical = 12.dp).clip(RoundedCornerShape(10.dp)),
                )
                PanelSeparator(palette)
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) { keepPhoto = !keepPhoto }.padding(start = 18.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Keep this photo with the note", color = palette.ink, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    Switch(
                        keepPhoto, { keepPhoto = it },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = palette.accent, checkedThumbColor = Color.White, checkedBorderColor = palette.accent,
                            uncheckedTrackColor = SheetColors.tertiaryFill(palette), uncheckedThumbColor = Color.White,
                            uncheckedBorderColor = Color.Transparent,
                        ),
                    )
                }
            }
            Footer(
                if (keepPhoto) "The photo is saved with the note, on this device."
                else "The photo was read on this device and will be discarded — only the text below is kept.",
                palette,
            )

            when (val p = phase) {
                Phase.Reading -> {
                    Spacer(Modifier.height(22.dp))
                    PanelGroup(palette) {
                        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = palette.secondary, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Reading the slide…", color = palette.ink, fontSize = 17.sp)
                        }
                    }
                }
                is Phase.Failed -> {
                    Spacer(Modifier.height(22.dp))
                    PanelGroup(palette) {
                        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.WarningAmber, null, tint = palette.ink, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(p.message, color = palette.ink, fontSize = 17.sp, lineHeight = 22.sp)
                        }
                    }
                }
                Phase.Ready -> Unit
            }
            if (phase == Phase.Reading) return@Column

            // Where it goes.
            Spacer(Modifier.height(22.dp))
            PanelGroup(palette) {
                DestinationPicker(choices, targetNote, palette) { destination = it?.id }
            }

            PanelSectionTitle(if (adding) "Heading" else "Title", palette)
            PanelGroup(palette) {
                ReviewField(
                    title, if (adding) "Heading" else "Title", palette, fontSize = 20f, weight = FontWeight.SemiBold,
                    label = if (adding) "Heading added to the note" else "Note title",
                ) { title = it }
            }
            when {
                adding -> Footer("Added above this slide’s lines. Leave it empty to add just the lines.", palette)
                title.isEmpty() && phase == Phase.Ready -> Footer("No title was found — type one, or the first passage will name the note.", palette)
            }

            PanelSectionTitle("Passages", palette)
            PanelGroup(palette) {
                if (ranges.isNotEmpty()) {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (range in ranges.toList()) PassageChip(range, palette) { ranges.remove(range) }
                    }
                    PanelSeparator(palette)
                }
                ReviewField(
                    passageText, "Add a passage, e.g. John 10:11", palette, singleLine = true, imeAction = ImeAction.Done,
                    onDone = ::addTypedPassages, label = "Add a passage",
                ) { passageText = it }
            }
            if (ranges.isEmpty() && phase == Phase.Ready) Footer("No passages were found on the slide.", palette)

            PanelSectionTitle(if (adding) "Add these lines" else "Start the note with", palette)
            PanelGroup(palette) {
                if (lines.isEmpty()) {
                    Text(
                        if (phase == Phase.Ready) "Nothing else was on the slide." else "", color = palette.secondary, fontSize = 17.sp,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 13.dp),
                    )
                }
                lines.forEachIndexed { index, line ->
                    LineRow(line, palette)
                    if (index < lines.lastIndex) PanelSeparator(palette)
                }
            }
            // Spoken once the slide is read — iOS's announcement.
            if (announcement.isNotEmpty()) {
                Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = announcement })
            }
        }
    }
}

/** Passages resolved against the translation's verse counts, clamped as [SlideParser.ranges] does. */
private suspend fun resolve(model: ReaderViewModel, passages: List<Passage>): List<VerseRange> {
    val counts = HashMap<Pair<Int, Int>, Int>()
    for (passage in passages) {
        val clamped = passage.clamped
        for (chapter in setOf(clamped.startChapter, clamped.endChapter)) {
            val key = clamped.book.number to chapter
            if (key !in counts) counts[key] = model.loadVerseCount(ChapterRef(clamped.book.number, chapter))
        }
    }
    return SlideParser.ranges(passages) { book, chapter -> counts[book.number to chapter] ?: 0 }
}

@Composable
private fun ReviewHeader(title: String, saveTitle: String, palette: ReaderPalette, saveEnabled: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    val surface = PanelColors.background(palette)
    Box(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp)) {
        Box(
            Modifier.align(Alignment.CenterStart).height(44.dp).glass(palette, CircleShape, surface, lifted = true)
                .clickable(role = Role.Button, onClick = onCancel).padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Cancel", color = palette.ink, fontSize = 17.sp)
        }
        Text(
            title, color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 118.dp),
        )
        Box(
            Modifier.align(Alignment.CenterEnd).height(44.dp)
                .glass(palette, CircleShape, surface, lifted = true)
                .clip(CircleShape).clickable(enabled = saveEnabled, role = Role.Button, onClick = onSave).padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                saveTitle, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                color = if (saveEnabled) palette.ink else palette.secondary.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun Footer(text: String, palette: ReaderPalette) {
    Text(text, color = palette.secondary, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 7.dp))
}

/** "Save to": a new note, the note we came from, or one of the most recent — iOS's menu picker. */
@Composable
private fun DestinationPicker(choices: List<Note>, selected: Note?, palette: ReaderPalette, onSelect: (Note?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { open = true }.padding(horizontal = 18.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Save to", color = palette.ink, fontSize = 17.sp)
            Spacer(Modifier.width(12.dp))
            Text(
                selected?.displayTitle ?: "A new note", color = palette.secondary, fontSize = 17.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f),
            )
            Icon(Icons.Rounded.UnfoldMore, null, tint = palette.secondary, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.align(Alignment.TopEnd)) {
            DropdownMenuItem(text = { Text("A new note", color = palette.ink, fontSize = 15.sp) }, onClick = {
                open = false
                onSelect(null)
            })
            for (note in choices) {
                DropdownMenuItem(
                    text = { Text(note.displayTitle, color = palette.ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        open = false
                        onSelect(note)
                    },
                )
            }
        }
    }
}

/** A passage on the review sheet; tapping it removes it. */
@Composable
private fun PassageChip(range: VerseRange, palette: ReaderPalette, onRemove: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(palette.accent.copy(alpha = 0.14f)).clickable(role = Role.Button, onClick = onRemove)
            .semantics(mergeDescendants = true) {
                contentDescription = range.display
                onClick("Remove") { onRemove(); true }
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Book, null, tint = palette.ink, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(range.display, color = palette.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Rounded.Cancel, null, tint = palette.secondary, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun LineRow(line: ReviewLine, palette: ReaderPalette) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.padding(top = 6.dp).size(36.dp).clip(CircleShape).clickable(role = Role.Button) { line.included = !line.included }
                .semantics {
                    role = Role.Checkbox
                    contentDescription = line.text
                    stateDescription = if (line.included) "Included" else "Left out"
                    selected = line.included
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (line.included) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                tint = if (line.included) palette.accent else palette.secondary, modifier = Modifier.size(24.dp),
            )
        }
        Box(Modifier.weight(1f)) {
            ReviewField(line.text, "Line", palette, color = if (line.included) palette.ink else palette.secondary, label = "Edit line", startPadding = 6) {
                line.text = it
            }
        }
    }
}

@Composable
private fun ReviewField(
    value: String,
    placeholder: String,
    palette: ReaderPalette,
    fontSize: Float = 17f,
    weight: FontWeight = FontWeight.Normal,
    color: Color = palette.ink,
    singleLine: Boolean = false,
    imeAction: ImeAction = ImeAction.Default,
    onDone: () -> Unit = {},
    label: String = placeholder,
    startPadding: Int = 18,
    onChange: (String) -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        textStyle = TextStyle(color = color, fontSize = fontSize.sp, fontWeight = weight, lineHeight = (fontSize * 1.3f).sp),
        cursorBrush = SolidColor(palette.accent),
        keyboardOptions = KeyboardOptions(
            capitalization = if (singleLine) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
            autoCorrectEnabled = !singleLine,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth().padding(start = startPadding.dp, end = 18.dp, top = 13.dp, bottom = 13.dp)
            .semantics { contentDescription = label },
        decorationBox = { field ->
            Box {
                if (value.isEmpty()) Text(placeholder, color = palette.secondary.copy(alpha = 0.7f), fontSize = fontSize.sp, fontWeight = weight)
                field()
            }
        },
    )
}
