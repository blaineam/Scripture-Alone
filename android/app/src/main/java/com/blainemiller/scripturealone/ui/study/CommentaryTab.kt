package com.blainemiller.scripturealone.ui.study

import androidx.compose.ui.semantics.Role
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import com.blainemiller.scripturealone.R
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blainemiller.scripturealone.data.Canon
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.assets.AssetPack
import com.blainemiller.scripturealone.data.reference.ReferenceDetector
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.blainemiller.scripturealone.data.study.CommentaryEntry
import com.blainemiller.scripturealone.data.study.StudySource
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.blainemiller.scripturealone.ui.reader.ReaderViewModel

private class LoadedCommentary(
    val entries: List<CommentaryEntry>,
    val introduction: CommentaryEntry?,
    /** Other sources that do comment on this verse, for the empty state. */
    val alternatives: List<StudySource>,
)

/**
 * What the old commentators wrote on the passage around this verse — `CommentaryView.swift`, with its
 * source picker (Calvin, Gill, JFB). Scripture references inside the text are links that open in the
 * reader, leaving a trail back as a cross reference does.
 */
@Composable
fun CommentaryTab(verse: VerseRef, study: StudyModel, reader: ReaderViewModel, palette: ReaderPalette) {
    // Commentary is an on-demand asset pack; cross references and context are in the app, so only
    // this tab waits on a download — iOS's `commentaryDownload`.
    val ready = packReady(AssetPack.COMMENTARY)
    val sources = loaded(ready) { context -> StudyLibrary.commentary(context)?.commentarySources }
    if (sources == null) {
        if (!ready) {
            PackDownload(AssetPack.COMMENTARY, stringResource(R.string.study_commentary_download_failed), palette)
            return
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // The first open copies the 45 MB commentary database out of its pack.
            CircularProgressIndicator(color = palette.secondary, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
        }
        return
    }
    val source = sources.firstOrNull { it.id == study.commentarySource } ?: sources.firstOrNull() ?: return
    val commentary = loaded(source.id to verse.key) { context ->
        val store = StudyLibrary.commentary(context) ?: return@loaded null
        val entries = store.commentary(source.id, verse.key)
        val intro = store.introduction(source.id, verse.book, verse.chapter)
        val alternatives = if (entries.isEmpty()) {
            val commenting = store.sourcesCommenting(verse.key)
            sources.filter { it.id != source.id && it.id in commenting }
        } else {
            emptyList()
        }
        LoadedCommentary(entries, intro, alternatives)
    }

    Column(Modifier.fillMaxSize()) {
        if (sources.size > 1) {
            SegmentedPicker(
                sources.map { it.shortName }, sources.indexOf(source), palette,
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) { study.selectCommentary(sources[it].id) }
        }
        when {
            commentary == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = palette.secondary, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
            }
            commentary.entries.isEmpty() && commentary.introduction == null -> ContentUnavailable(
                Icons.AutoMirrored.Rounded.MenuBook, stringResource(R.string.study_commentary_nothing_title, source.shortName),
                stringResource(R.string.study_commentary_nothing_body, source.name, verse.display), palette, Modifier.padding(top = 16.dp),
            ) {
                for (other in commentary.alternatives) {
                    BorderedButton(stringResource(R.string.study_commentary_read_other, other.shortName), palette, Modifier.padding(top = 6.dp)) { study.selectCommentary(other.id) }
                }
            }
            else -> Reading(commentary, source, study, reader, palette)
        }
    }
}

@Composable
private fun Reading(loaded: LoadedCommentary, source: StudySource, study: StudyModel, reader: ReaderViewModel, palette: ReaderPalette) {
    var introOpen by rememberSaveable(loaded.introduction?.text?.hashCode()) { mutableStateOf(false) }
    val open: (VerseRange) -> Unit = { study.jump(it, reader) }
    SelectionContainer {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column {
                Text(source.name, color = palette.ink, fontSize = StudyStyle.headline, fontWeight = FontWeight.SemiBold)
                Text(source.author, color = palette.secondary, fontSize = StudyStyle.caption)
            }
            loaded.introduction?.let { intro ->
                Column {
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button) { introOpen = !introOpen }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.study_commentary_introduction, Canon.display(ChapterRef(intro.book, intro.chapter))),
                            color = palette.ink, fontSize = StudyStyle.subheadline, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            if (introOpen) Icons.Rounded.KeyboardArrowDown else Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
                            tint = palette.accent, modifier = Modifier.size(22.dp),
                        )
                    }
                    AnimatedVisibility(introOpen) {
                        Paragraphs(intro, palette, open, Modifier.padding(top = 8.dp))
                    }
                }
            }
            for (entry in loaded.entries) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    entry.range?.let { range ->
                        Text(
                            VerseRange(VerseRef.fromKey(range.start), VerseRef.fromKey(range.end)).display.uppercase(),
                            color = palette.secondary, fontSize = StudyStyle.caption, fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.8.sp, modifier = Modifier.semantics { heading() },
                        )
                    }
                    Paragraphs(entry, palette, open)
                }
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(StudyStyle.separator(palette)))
            Text(source.attribution, color = palette.secondary, fontSize = StudyStyle.caption2)
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun Paragraphs(entry: CommentaryEntry, palette: ReaderPalette, open: (VerseRange) -> Unit, modifier: Modifier = Modifier) {
    val paragraphs = remember(entry, palette.accent) { entry.paragraphs.map { linked(it, palette, open) } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (paragraph in paragraphs) {
            Text(paragraph, style = serifStyle(17f, palette.ink, 17f * 1.32f + 4f))
        }
    }
}

/** Turns references like "Rom 5:8" or "Joh 3:22" into links that open in the reader. */
private fun linked(paragraph: String, palette: ReaderPalette, open: (VerseRange) -> Unit): AnnotatedString {
    val matches = ReferenceDetector.detect(paragraph).filter { it.passage.startVerse != null }
    if (matches.isEmpty()) return AnnotatedString(paragraph)
    val style = TextLinkStyles(SpanStyle(color = palette.accent))
    return buildAnnotatedString {
        var at = 0
        for (match in matches) {
            if (match.range.first < at) continue
            append(paragraph.substring(at, match.range.first))
            // A reference to a chapter's end uses 176, the longest chapter, as iOS does without a store.
            val (first, last) = match.passage.range { _, _ -> 176 }
            val start = VerseRange.ref(first)
            val end = VerseRange.ref(last)
            val text = paragraph.substring(match.range.first, match.range.last + 1)
            if (start == null || end == null) {
                append(text)
            } else {
                val range = VerseRange.of(start, end)
                withLink(LinkAnnotation.Clickable(range.storageString, style) { open(range) }) { append(text) }
            }
            at = match.range.last + 1
        }
        append(paragraph.substring(at))
    }
}
