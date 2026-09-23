package com.blainemiller.scripturealone.ui.translations

import androidx.compose.ui.semantics.Role
import com.blainemiller.scripturealone.ui.reader.takesTaps
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ViewColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blainemiller.scripturealone.data.BundledTranslations
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.Canon
import com.blainemiller.scripturealone.data.translations.TranslationLibrary
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.blainemiller.scripturealone.ui.reader.ReaderViewModel
import com.blainemiller.scripturealone.ui.reader.SheetColors
import com.blainemiller.scripturealone.ui.reader.glass
import com.blainemiller.scripturealone.ui.study.ContentUnavailable
import com.blainemiller.scripturealone.ui.study.GlassTextButton
import com.blainemiller.scripturealone.ui.study.StudyStyle
import com.blainemiller.scripturealone.ui.study.serifStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.text.AppText

/**
 * [verse] is the number shown: the left-hand translation's own (the right's when it alone has it).
 * Rows pair up by [key], a KJV key, not by number: French Psalm 51:12 sits beside English 51:10.
 */
private class CompareRow(val verse: Int, val left: String?, val right: String?, val key: Int) {
    /** A verse only one side prints — the most interesting row on the screen, so it is marked. */
    val isOneSided: Boolean get() = left == null || right == null
}

private sealed interface Comparison {
    data object Loading : Comparison
    class Rows(val rows: List<CompareRow>) : Comparison
    class Failed(val message: String) : Comparison
}

/**
 * The chapter in two translations, verse beside verse — `CompareView.swift`. Every translation the
 * app can read is eligible: bundled, imported, or online (which fetches the chapter, and caches it
 * within the publisher's limit, as reading it would).
 */
@Composable
fun CompareSheet(reader: ReaderViewModel, palette: ReaderPalette, onClose: () -> Unit) {
    val context = LocalContext.current
    val surface = SheetColors.surface(palette)
    val prefs = remember { context.getSharedPreferences("compare", Context.MODE_PRIVATE) }
    val library by TranslationLibrary.state.collectAsState()
    val candidates = remember(library, reader.translationId) { BundledTranslations.ids.filter { it != reader.translationId } }
    var otherId by remember { mutableStateOf(prefs.getString("compareTranslation", null)) }
    val other = otherId?.takeIf { it in candidates } ?: candidates.firstOrNull()
    val chapter = reader.location
    val left = reader.translationId

    val state by produceState<Comparison>(Comparison.Loading, other, chapter, left) {
        value = Comparison.Loading
        if (other == null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                // The chapter on screen by its own numbers; the other side by the KJV keys those verses
                // hold, which for a translation that numbers differently can reach into a neighbouring
                // chapter.
                val leftSource = BundledTranslations.source(context, left)
                val leftChapter = leftSource.chapter(chapter)
                val span = leftChapter.markKeys
                val rightSource = BundledTranslations.source(context, other)
                val rightNumbering = rightSource.numbering
                val r = if (rightNumbering.isIdentity && leftChapter.numbering.isIdentity) {
                    rightSource.chapter(chapter).verses
                } else {
                    TranslationLibrary.verses(context, other, VerseRange(VerseRef.fromKey(span.first), VerseRef.fromKey(span.last)))
                }
                val lv = HashMap<Int, Pair<Int, String>>()
                for (v in leftChapter.verses) if (v.ref.verse > 0) lv[leftChapter.numbering.kjv(v.ref.key)] = v.ref.verse to v.text
                val rv = HashMap<Int, Pair<Int, String>>()
                for (v in r) if (v.ref.verse > 0) rv[rightNumbering.kjv(v.ref.key)] = v.ref.verse to v.text
                Comparison.Rows(
                    (lv.keys + rv.keys).sorted().map { key ->
                        CompareRow(lv[key]?.first ?: rv[key]?.first ?: key % 1_000, lv[key]?.second, rv[key]?.second, key)
                    },
                )
            }.getOrElse { Comparison.Failed(it.message ?: AppText.get(R.string.translations_compare_unavailable)) }
        }
    }

    Column(Modifier.fillMaxSize().clip(androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(surface)
        .takesTaps()) {
        Box(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 14.dp)) {
            Box(Modifier.align(Alignment.CenterStart)) { GlassTextButton(stringResource(R.string.common_close), palette, surface, onClick = onClose) }
            Box(Modifier.align(Alignment.Center)) {
                Picker(left, other, candidates, palette) {
                    otherId = it
                    prefs.edit().putString("compareTranslation", it).apply()
                }
            }
        }
        Text(
            Canon.display(chapter), color = palette.secondary, fontSize = StudyStyle.footnote, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp),
        )
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(SheetColors.separator(palette)))
        when {
            other == null -> ContentUnavailable(Icons.Rounded.ViewColumn, stringResource(R.string.translations_compare_empty_title), stringResource(R.string.translations_compare_empty_message), palette, Modifier.padding(top = 40.dp))
            state is Comparison.Failed -> ContentUnavailable(
                Icons.Rounded.ErrorOutline, stringResource(R.string.translations_compare_failed_title), (state as Comparison.Failed).message, palette, Modifier.padding(top = 40.dp),
            )
            state is Comparison.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = palette.secondary, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
            }
            else -> {
                val size = reader.fontSize * 0.82f
                LazyColumn(Modifier.fillMaxSize()) {
                    items((state as Comparison.Rows).rows, key = { it.key }) { row ->
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (row.isOneSided) palette.accent.copy(alpha = 0.07f) else androidx.compose.ui.graphics.Color.Transparent)
                                .padding(horizontal = 16.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("${row.verse}", color = palette.accent, fontSize = StudyStyle.caption, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(3.dp))
                                Side(row.left, size, palette)
                            }
                            Column(Modifier.weight(1f)) {
                                Spacer(Modifier.height(18.dp))
                                Side(row.right, size, palette)
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(0.5.dp).background(StudyStyle.separator(palette)))
                    }
                    item { Spacer(Modifier.height(48.dp)) }
                }
            }
        }
    }
}

@Composable
private fun Side(text: String?, size: Float, palette: ReaderPalette) {
    if (text != null) {
        Text(text, style = serifStyle(size, palette.ink))
    } else {
        val missing = stringResource(R.string.translations_compare_verse_missing)
        Text("—", color = palette.secondary, modifier = Modifier.semantics { contentDescription = missing })
    }
}

/** "BSB ⇄ KJV" — the right-hand translation is a menu of every other one. */
@Composable
private fun Picker(left: String, right: String?, candidates: List<String>, palette: ReaderPalette, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val description = if (right != null) stringResource(R.string.translations_compare_picker_description, left, right)
    else stringResource(R.string.translations_compare_picker_description_none, left)
    Box {
        Row(
            Modifier.height(40.dp).glass(palette, CircleShape, SheetColors.surface(palette), lifted = true)
                .clickable(enabled = candidates.isNotEmpty(), role = Role.DropdownList) { open = true }.padding(horizontal = 16.dp)
                .semantics(mergeDescendants = true) { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(left, color = palette.ink, fontSize = StudyStyle.subheadline, fontWeight = FontWeight.SemiBold)
            Icon(Icons.AutoMirrored.Rounded.CompareArrows, null, tint = palette.secondary, modifier = Modifier.padding(horizontal = 6.dp).size(18.dp))
            Text(right ?: stringResource(R.string.translations_compare_choose), color = palette.accent, fontSize = StudyStyle.subheadline, fontWeight = FontWeight.SemiBold)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (id in candidates) {
                DropdownMenuItem(
                    text = { Text(id, color = palette.ink) },
                    trailingIcon = { if (id == right) Icon(Icons.Rounded.Check, stringResource(R.string.translations_selected), tint = palette.accent) },
                    onClick = { open = false; onPick(id) },
                )
            }
        }
    }
}
