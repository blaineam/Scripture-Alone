package com.blainemiller.scripturealone.ui.favorites

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.blainemiller.scripturealone.data.userdata.Highlight
import com.blainemiller.scripturealone.data.userdata.HighlightRun
import com.blainemiller.scripturealone.data.userdata.NoteSearch
import com.blainemiller.scripturealone.data.userdata.NotesPlace
import com.blainemiller.scripturealone.ui.notes.EmptyState
import com.blainemiller.scripturealone.ui.notes.PanelColors
import com.blainemiller.scripturealone.ui.notes.PanelSeparator
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.blainemiller.scripturealone.ui.reader.ReaderViewModel

/**
 * The Highlights scope of the Notes panel — `HighlightsSection` in
 * `ScriptureAlone/Favorites/FavoritesSection.swift`: every highlighted verse in Bible order. Verses in a
 * row marked in one colour read as one passage ("Romans 8:38–39") with that colour's dot and the text in
 * the translation being read. Tapping one opens it; a long press offers Delete (iOS's swipe), which
 * removes the whole run.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HighlightsSection(
    model: ReaderViewModel,
    palette: ReaderPalette,
    highlights: List<Highlight>,
    search: String,
    place: NotesPlace,
    bottomInset: Dp,
    onOpened: () -> Unit,
) {
    val runs = remember(highlights) { HighlightRun.of(highlights) }
    // Each run's text, read off the main thread in the current translation.
    var texts by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    LaunchedEffect(runs, model.translationId) {
        texts = runs.associate { run -> run.range.start.key to model.verses(listOf(run.range)).joinToString(" ") { it.text } }
    }
    val verseCount = { book: com.blainemiller.scripturealone.data.canon.BookID, chapter: Int ->
        model.verseCount(ChapterRef(book.number, chapter))
    }
    val location = model.location
    val rows = runs.filter { run ->
        place.contains(model.displayRange(run.range), location) &&
            NoteSearch.matches(run, texts[run.range.start.key].orEmpty(), run.color.localizedName, search, verseCount, model.numbering)
    }

    if (rows.isEmpty()) {
        EmptyState(
            Icons.Outlined.BorderColor,
            if (search.isBlank()) stringResource(R.string.highlights_empty_title) else stringResource(R.string.notes_no_matches_title),
            if (search.isBlank()) stringResource(R.string.highlights_empty_message)
            else stringResource(R.string.notes_no_matches_message),
            palette,
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(bottom = bottomInset + 24.dp)) {
        itemsIndexed(rows, key = { _, run -> run.range.start.key }) { index, run ->
            val shape = when {
                rows.size == 1 -> RoundedCornerShape(22.dp)
                index == 0 -> RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
                index == rows.lastIndex -> RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp)
                else -> RoundedCornerShape(0.dp)
            }
            var menu by remember { mutableStateOf(false) }
            Column(Modifier.padding(horizontal = 16.dp).clip(shape).background(PanelColors.card(palette))) {
                Box {
                    Column(
                        Modifier.fillMaxWidth()
                            .combinedClickable(role = Role.Button, onLongClickLabel = stringResource(R.string.notes_show_options), onLongClick = { menu = true }) {
                                model.go(run.range.start)
                                onOpened()
                            }
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val colorName = run.color.localizedName
                            Box(
                                Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF000000 or run.color.rgb))
                                    .semantics { contentDescription = colorName },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                model.displayRange(run.range).display, color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                        }
                        val text = texts[run.range.start.key].orEmpty()
                        if (text.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(text, color = palette.secondary, fontSize = 16.sp, lineHeight = 21.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_delete), color = palette.red, fontSize = 15.sp) },
                            onClick = {
                                menu = false
                                model.userData.removeHighlights(run.keys)
                            },
                        )
                    }
                }
                if (index < rows.lastIndex) PanelSeparator(palette)
            }
        }
    }
}
