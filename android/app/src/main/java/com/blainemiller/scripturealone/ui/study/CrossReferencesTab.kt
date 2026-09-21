package com.blainemiller.scripturealone.ui.study

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.rights.TranslationRights
import com.blainemiller.scripturealone.data.study.CrossReference
import com.blainemiller.scripturealone.data.translations.TranslationLibrary
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.blainemiller.scripturealone.ui.reader.ReaderViewModel

/** A reference and its text in the translation being read. */
private class CrossReferenceRow(val reference: CrossReference, val range: VerseRange, val text: String, val verseCount: Int)

private const val STRONGEST = 6
private const val INITIAL_LIMIT = 40

/**
 * Where else Scripture speaks to this verse — `CrossReferencesView.swift`: the strongest links first,
 * then the rest in canonical order split by testament, each with its text in the translation being
 * read. Tapping one opens it in the reader and leaves a trail back; a long press offers Copy, which
 * asks the translation's rights first.
 */
@Composable
fun CrossReferencesTab(verse: VerseRef, study: StudyModel, reader: ReaderViewModel, palette: ReaderPalette) {
    val translation = reader.translationId
    var showAll by rememberSaveable(verse.key) { mutableStateOf(false) }
    val rows = loaded(verse.key to translation) { context ->
        val store = StudyLibrary.crossReferences(context) ?: return@loaded null
        store.crossReferences(verse.key).map { reference ->
            val range = VerseRange(VerseRef.fromKey(reference.target.start), VerseRef.fromKey(reference.target.end))
            val verses = TranslationLibrary.verses(context, translation, range)
            val text = if (verses.size > 1) verses.joinToString(" ") { "${it.ref.verse} ${it.text}" } else verses.firstOrNull()?.text.orEmpty()
            CrossReferenceRow(reference, range, text, verses.size)
        }
    }
    val source = loaded(Unit) { context -> StudyLibrary.crossReferences(context)?.crossReferenceSource }

    when {
        rows == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = palette.secondary, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
        }
        rows.isEmpty() -> ContentUnavailable(
            Icons.Rounded.AccountTree, "No Cross References", "Nothing is linked to ${verse.display} yet.", palette,
            Modifier.padding(top = 24.dp),
        )
        else -> {
            val top = rows.take(STRONGEST)
            val rest = rows.drop(STRONGEST)
            val shown = (if (showAll) rest else rest.take(INITIAL_LIMIT - STRONGEST)).sortedBy { it.reference.target.start }
            val old = shown.filter { !(BookID.of(it.range.start.book)?.isNewTestament ?: false) }
            val new = shown.filter { BookID.of(it.range.start.book)?.isNewTestament ?: false }
            val maxVotes = maxOf(1, rows.first().reference.votes)
            val rights = reader.chapter?.translation?.rights ?: TranslationRights.PUBLIC_DOMAIN
            val abbreviation = reader.chapter?.translation?.abbreviation ?: translation
            LazyColumn(Modifier.fillMaxSize().background(StudyStyle.groupedBackground(palette))) {
                item("strongest") {
                    GroupedSection(
                        palette, header = "Strongest",
                        footer = if (rows.size > STRONGEST) "${rows.size} references, ranked by how many readers found each one helpful." else null,
                    ) {
                        top.forEachIndexed { i, row ->
                            if (i > 0) CellDivider(palette)
                            ReferenceRow(row, maxVotes, palette, rights, abbreviation) { study.jump(row.range, reader) }
                        }
                    }
                }
                if (old.isNotEmpty()) item("old") {
                    GroupedSection(palette, header = "Old Testament") {
                        old.forEachIndexed { i, row ->
                            if (i > 0) CellDivider(palette)
                            ReferenceRow(row, maxVotes, palette, rights, abbreviation) { study.jump(row.range, reader) }
                        }
                    }
                }
                if (new.isNotEmpty()) item("new") {
                    GroupedSection(palette, header = "New Testament") {
                        new.forEachIndexed { i, row ->
                            if (i > 0) CellDivider(palette)
                            ReferenceRow(row, maxVotes, palette, rights, abbreviation) { study.jump(row.range, reader) }
                        }
                    }
                }
                if (!showAll && rows.size > INITIAL_LIMIT) item("all") {
                    GroupedSection(palette) {
                        Cell(palette, onClick = { showAll = true }) {
                            Text("Show All ${rows.size} References", color = palette.accent, fontSize = StudyStyle.body)
                        }
                    }
                }
                source?.let { s ->
                    item("attribution") {
                        Text(
                            s.attribution, color = palette.secondary, fontSize = StudyStyle.caption2,
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
                        )
                    }
                }
                item("end") { Spacer(Modifier.height(48.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReferenceRow(
    row: CrossReferenceRow,
    maxVotes: Int,
    palette: ReaderPalette,
    rights: TranslationRights,
    abbreviation: String,
    onOpen: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val display = row.range.display
    Box {
        Column(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .semantics(mergeDescendants = true) { onClick("Opens $display in the reader") { onOpen(); true } }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(display, color = palette.accent, fontSize = StudyStyle.subheadline, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                StrengthMeter(row.reference.votes.toFloat() / maxVotes, palette)
            }
            if (row.text.isNotEmpty()) {
                Text(
                    row.text, style = serifStyle(16f, palette.secondary, 21f),
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Go to $display", color = palette.ink) },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = palette.ink) },
                onClick = { menu = false; onOpen() },
            )
            DropdownMenuItem(
                text = { Text("Copy", color = palette.ink) },
                leadingIcon = { Icon(Icons.Rounded.ContentCopy, null, tint = palette.ink) },
                onClick = {
                    menu = false
                    // The translation's own terms decide whether its text may leave the device, and
                    // how much of it — the same gate the reader's selection uses.
                    if (row.text.isNotEmpty() && rights.permits(TranslationRights.Permission.COPY) && rights.mayQuote(row.verseCount)) {
                        clipboard.setText(AnnotatedString("${row.text}\n— $display ($abbreviation)"))
                    } else {
                        Toast.makeText(context, "This translation's terms don't allow copying that passage.", Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
    }
}

/** Three small bars, filled in proportion to a reference's votes against the strongest one. */
@Composable
private fun StrengthMeter(fraction: Float, palette: ReaderPalette) {
    val filled = when {
        fraction > 0.66f -> 3
        fraction > 0.25f -> 2
        else -> 1
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (index in 0 until 3) {
            Box(
                Modifier.width(3.dp).height((6 + index * 3).dp).clip(RoundedCornerShape(2.dp))
                    .background(if (index < filled) palette.accent else palette.secondary.copy(alpha = 0.22f)),
            )
        }
    }
}
