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

private class CompareRow(val verse: Int, val left: String?, val right: String?) {
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
                val l = BundledTranslations.source(context, left).chapter(chapter).verses
                val r = BundledTranslations.source(context, other).chapter(chapter).verses
                val lv = l.filter { it.ref.verse > 0 }.associate { it.ref.verse to it.text }
                val rv = r.filter { it.ref.verse > 0 }.associate { it.ref.verse to it.text }
                Comparison.Rows((lv.keys + rv.keys).sorted().map { CompareRow(it, lv[it], rv[it]) })
            }.getOrElse { Comparison.Failed(it.message ?: "That translation isn't available.") }
        }
    }

    Column(Modifier.fillMaxSize().clip(androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(surface)
        .takesTaps()) {
        Box(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 14.dp)) {
            Box(Modifier.align(Alignment.CenterStart)) { GlassTextButton("Close", palette, surface, onClick = onClose) }
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
            other == null -> ContentUnavailable(Icons.Rounded.ViewColumn, "Nothing to Compare With", "Add another translation first.", palette, Modifier.padding(top = 40.dp))
            state is Comparison.Failed -> ContentUnavailable(
                Icons.Rounded.ErrorOutline, "Couldn't Load That Translation", (state as Comparison.Failed).message, palette, Modifier.padding(top = 40.dp),
            )
            state is Comparison.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = palette.secondary, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
            }
            else -> {
                val size = reader.fontSize * 0.82f
                LazyColumn(Modifier.fillMaxSize()) {
                    items((state as Comparison.Rows).rows, key = { it.verse }) { row ->
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
        Text("—", color = palette.secondary, modifier = Modifier.semantics { contentDescription = "Not in this translation" })
    }
}

/** "BSB ⇄ KJV" — the right-hand translation is a menu of every other one. */
@Composable
private fun Picker(left: String, right: String?, candidates: List<String>, palette: ReaderPalette, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.height(40.dp).glass(palette, CircleShape, SheetColors.surface(palette), lifted = true)
                .clickable(enabled = candidates.isNotEmpty(), role = Role.DropdownList) { open = true }.padding(horizontal = 16.dp)
                .semantics(mergeDescendants = true) { contentDescription = "Compare $left with ${right ?: "nothing"}" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(left, color = palette.ink, fontSize = StudyStyle.subheadline, fontWeight = FontWeight.SemiBold)
            Icon(Icons.AutoMirrored.Rounded.CompareArrows, null, tint = palette.secondary, modifier = Modifier.padding(horizontal = 6.dp).size(18.dp))
            Text(right ?: "Choose", color = palette.accent, fontSize = StudyStyle.subheadline, fontWeight = FontWeight.SemiBold)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (id in candidates) {
                DropdownMenuItem(
                    text = { Text(id, color = palette.ink) },
                    trailingIcon = { if (id == right) Icon(Icons.Rounded.Check, "Selected", tint = palette.accent) },
                    onClick = { open = false; onPick(id) },
                )
            }
        }
    }
}
