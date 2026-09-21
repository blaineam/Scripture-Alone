package com.blainemiller.scripturealone.ui.favorites

import androidx.compose.ui.semantics.Role
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.blainemiller.scripturealone.data.userdata.Favorite
import com.blainemiller.scripturealone.data.userdata.NoteSearch
import com.blainemiller.scripturealone.ui.notes.EmptyState
import com.blainemiller.scripturealone.ui.notes.PanelColors
import com.blainemiller.scripturealone.ui.notes.PanelSeparator
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.blainemiller.scripturealone.ui.reader.ReaderViewModel
import java.util.UUID

/**
 * The Favorites scope of the Notes panel — `ScriptureAlone/Favorites/FavoritesSection.swift`:
 * favorited passages, newest first, each with its text in the translation being read. Tapping one
 * opens it in the reader; a long press offers Delete (iOS's swipe).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FavoritesSection(
    model: ReaderViewModel,
    palette: ReaderPalette,
    favorites: List<Favorite>,
    search: String,
    bottomInset: Dp,
    onOpened: () -> Unit,
) {
    // Each favorite's text, read off the main thread in the current translation.
    var texts by remember { mutableStateOf<Map<UUID, String>>(emptyMap()) }
    LaunchedEffect(favorites, model.translationId) {
        texts = favorites.associate { favorite ->
            favorite.id to model.verses(listOf(favorite.range)).joinToString(" ") { it.text }
        }
    }
    val verseCount = { book: com.blainemiller.scripturealone.data.canon.BookID, chapter: Int ->
        model.verseCount(ChapterRef(book.number, chapter))
    }
    val rows = favorites.filter { NoteSearch.matches(it, texts[it.id].orEmpty(), search, verseCount) }

    if (rows.isEmpty()) {
        EmptyState(
            Icons.Rounded.FavoriteBorder,
            if (search.isBlank()) "No Favorites Yet" else "No Matches",
            if (search.isBlank()) "Tap verses in the text, then the heart, to keep a passage close."
            else "Try a word or a passage like Rom 8.",
            palette,
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(bottom = bottomInset + 24.dp)) {
        itemsIndexed(rows, key = { _, favorite -> favorite.id }) { index, favorite ->
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
                            .combinedClickable(role = Role.Button, onLongClickLabel = "Show options", onLongClick = { menu = true }) {
                                model.go(favorite.range.start)
                                onOpened()
                            }
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                favorite.range.display, color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            Icon(Icons.Rounded.Favorite, null, tint = Color(0xFFFF3B30), modifier = Modifier.size(14.dp))
                        }
                        val text = texts[favorite.id].orEmpty()
                        if (text.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(text, color = palette.secondary, fontSize = 16.sp, lineHeight = 21.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Delete", color = palette.red, fontSize = 15.sp) },
                            onClick = {
                                menu = false
                                model.userData.deleteFavorite(favorite.id)
                            },
                        )
                    }
                }
                if (index < rows.lastIndex) PanelSeparator(palette)
            }
        }
    }
}
