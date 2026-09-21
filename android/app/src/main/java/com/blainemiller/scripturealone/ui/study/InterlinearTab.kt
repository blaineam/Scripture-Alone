package com.blainemiller.scripturealone.ui.study

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blainemiller.scripturealone.data.BundledTranslations
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.assets.AssetPack
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.blainemiller.scripturealone.data.study.InterlinearAttribution
import com.blainemiller.scripturealone.data.study.InterlinearWord
import com.blainemiller.scripturealone.data.study.LexiconEntry
import com.blainemiller.scripturealone.data.translations.TranslationLibrary
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.blainemiller.scripturealone.ui.reader.ReaderViewModel

private sealed interface Interlinear {
    class Words(
        val words: List<InterlinearWord>,
        /** The verse as the reader is reading it, shown for context. */
        val verseText: String,
        /** True when the English glosses come from the BSB but the reader is in another translation. */
        val glossedFromBSB: Boolean,
        val attribution: InterlinearAttribution,
    ) : Interlinear
    class Failure(val message: String) : Interlinear
}

/**
 * One verse, word by word, with the Hebrew, Aramaic or Greek behind each English word —
 * `InterlinearView.swift`. Tap a word with a Strong's number for its lexicon entry.
 *
 * **The alignment is to the BSB's text** (see `InterlinearStore`): the words are always resolved
 * against the BSB's own wording for the verse, whatever translation is open, and the panel says so
 * when the reader is in another one. STEPBible's licence (CC BY 4.0) requires its credit and
 * statement of changes, so the required lines are always shown beneath the words.
 */
@Composable
fun InterlinearTab(verse: VerseRef, reader: ReaderViewModel, palette: ReaderPalette) {
    val translation = reader.translationId
    // An on-demand asset pack. Already downloaded: opened without asking. Not downloaded: the reader
    // is shown the size and taps to fetch, because 11 MB on a mobile connection is their decision.
    if (!packReady(AssetPack.INTERLINEAR)) {
        PackDownload(AssetPack.INTERLINEAR, "Couldn’t Download Original Languages", palette)
        return
    }
    val state = loaded(verse.key to translation) { context ->
        val store = StudyLibrary.interlinear(context) ?: return@loaded Interlinear.Failure("The original-language data isn't available.")
        // The BSB's own text for the verse — the exact string the word ranges index into.
        val bsb = BundledTranslations.source(context, "BSB").chapter(ChapterRef(verse.book, verse.chapter))
            .verses.firstOrNull { it.ref.verse == verse.verse }?.text
        val shown = TranslationLibrary.verses(context, translation, VerseRange(verse, verse)).firstOrNull()?.text ?: bsb.orEmpty()
        if (bsb == null) return@loaded Interlinear.Failure("This verse has no original-language data.")
        val words = runCatching { store.words(verse.key, bsb) }.getOrElse { return@loaded Interlinear.Failure(it.message ?: "The data didn't load.") }
        if (words.isEmpty()) Interlinear.Failure("This verse has no original-language data.")
        else Interlinear.Words(words, shown, shown != bsb && translation != "BSB", store.attribution)
    }
    when (state) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = palette.secondary, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
        }
        is Interlinear.Failure -> ContentUnavailable(Icons.Rounded.Translate, "No Original-Language Data", state.message, palette, Modifier.padding(top = 24.dp))
        is Interlinear.Words -> WordList(state, palette, reader.fontSize)
    }
}

@Composable
private fun WordList(state: Interlinear.Words, palette: ReaderPalette, readerSize: Float) {
    var expanded by rememberSaveable(state.words) { mutableStateOf<String?>(null) }
    val entry = loaded(expanded) { context -> expanded?.let { StudyLibrary.interlinear(context)?.entry(it) } }
    LazyColumn(Modifier.fillMaxSize().background(StudyStyle.groupedBackground(palette))) {
        item("verse") {
            Column(Modifier.padding(horizontal = 32.dp).padding(top = 16.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(state.verseText, style = serifStyle(minOf(readerSize, 17f) * 0.9f, palette.ink))
                if (state.glossedFromBSB) {
                    // Said plainly rather than left to be noticed: the English beside each word is the
                    // Berean Standard Bible's, which the word-by-word data is keyed to.
                    Text(
                        "English shown word-by-word is the Berean Standard Bible's, which this data is keyed to.",
                        color = palette.secondary, fontSize = StudyStyle.caption2,
                    )
                }
            }
        }
        item("words") {
            GroupedSection(palette, modifier = Modifier.padding(top = 4.dp)) {
                state.words.forEachIndexed { index, word ->
                    if (index > 0) CellDivider(palette)
                    WordRow(word, palette, expanded == word.strongs && word.strongs != null, entry.takeIf { expanded == word.strongs }) {
                        val strongs = word.strongs ?: return@WordRow
                        expanded = if (expanded == strongs) null else strongs
                    }
                }
            }
        }
        item("sources") {
            GroupedSection(palette, header = "Sources") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (line in state.attribution.requiredLines) {
                        Text(line, color = palette.secondary, fontSize = StudyStyle.caption2)
                    }
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun WordRow(word: InterlinearWord, palette: ReaderPalette, isExpanded: Boolean, entry: LexiconEntry?, onTap: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(enabled = word.strongs != null, onClick = onTap)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Hebrew and Aramaic read right to left; the word keeps its own direction.
            CompositionLocalProvider(LocalLayoutDirection provides if (word.language.isRightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Text(word.original, color = palette.ink, fontSize = 22.sp)
            }
            Spacer(Modifier.weight(1f))
            word.strongs?.let {
                Text(it, color = palette.accent, fontSize = StudyStyle.caption, fontFamily = FontFamily.Monospace)
            }
        }
        if (word.transliteration.isNotEmpty()) {
            Text(word.transliteration, color = palette.secondary, fontSize = StudyStyle.callout, fontStyle = FontStyle.Italic)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (word.english.isNotEmpty()) {
                Text(word.english, color = palette.ink, fontSize = StudyStyle.callout, fontWeight = FontWeight.Medium)
            }
            if (word.isSuperscription) {
                Spacer(Modifier.width(6.dp))
                Text("superscription", color = palette.secondary, fontSize = StudyStyle.caption2)
            }
        }
        if (word.parsingDescription.isNotEmpty()) {
            Text(word.parsingDescription, color = palette.secondary, fontSize = StudyStyle.caption)
        }
        if (isExpanded && entry != null) {
            Column(
                Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(palette.accent.copy(alpha = 0.08f))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (entry.gloss.isNotEmpty()) {
                    Text(entry.gloss, color = palette.ink, fontSize = StudyStyle.callout, fontWeight = FontWeight.SemiBold)
                }
                for (sense in entry.senses) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (sense.lemma.isNotEmpty()) {
                            Text("${sense.lemma} · ${sense.gloss}", color = palette.ink, fontSize = StudyStyle.caption, fontWeight = FontWeight.Medium)
                        }
                        if (sense.definition.isNotEmpty()) {
                            Text(sense.definition, color = palette.secondary, fontSize = StudyStyle.caption)
                        }
                    }
                }
            }
        }
    }
}
