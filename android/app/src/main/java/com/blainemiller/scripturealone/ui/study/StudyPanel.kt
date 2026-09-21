package com.blainemiller.scripturealone.ui.study

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.blainemiller.scripturealone.data.study.StudySource
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.blainemiller.scripturealone.ui.reader.ReaderViewModel
import com.blainemiller.scripturealone.ui.reader.SheetColors

/** "John 3:16" */
val VerseRef.display: String get() = VerseRange(this, this).display

/**
 * Study content for the verse handed to it last — `StudyPanel.swift`: a header with the back trail
 * and the verse, the tab picker, and the tab. Destinations pushed inside the panel (the map, the
 * timeline, a chart, a place, the credits) replace the tabs, with a back button, as iOS's
 * `NavigationStack` does.
 */
@Composable
fun StudyPanel(
    study: StudyModel,
    reader: ReaderViewModel,
    palette: ReaderPalette,
    isSheet: Boolean,
    onClose: () -> Unit,
    headerModifier: Modifier = Modifier,
) {
    val surface = SheetColors.surface(palette)
    val route = study.routes.lastOrNull()
    Column(Modifier.fillMaxSize().background(surface)) {
        Column(headerModifier) {
            SheetTopBar(
                title = route?.let { routeTitle(it, study, reader) } ?: "Study",
                palette = palette,
                leading = {
                    if (route != null) {
                        GlassBackButton(palette, surface) { study.pop() }
                    } else {
                        GlassIconButton(Icons.Rounded.Close, if (isSheet) "Close Study" else "Hide Study", palette, surface, onClick = onClose)
                    }
                },
                trailing = {
                    if (route == null) {
                        GlassIconButton(Icons.Outlined.Info, "About Study Resources", palette, surface, tint = palette.accent) {
                            study.push(StudyRoute.Sources)
                        }
                    }
                },
            )
            if (route == null) {
                VerseHeader(study, reader, palette)
                SegmentedPicker(
                    StudyTab.entries.map { it.shortTitle }, study.tab.ordinal, palette,
                    Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                ) { study.select(StudyTab.entries[it]) }
            } else if (route is StudyRoute.Viewer) {
                SegmentedPicker(
                    StudyRoute.ViewerTab.entries.map { it.title }, route.tab.ordinal, palette,
                    Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                ) {
                    study.pop()
                    study.push(StudyRoute.Viewer(StudyRoute.ViewerTab.entries[it]))
                }
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(SheetColors.separator(palette)))
        }
        Box(Modifier.fillMaxSize()) {
            val chapter = study.verse?.let { ChapterRef(it.book, it.chapter) } ?: reader.location
            when (route) {
                null -> TabContent(study, reader, palette, chapter)
                StudyRoute.Sources -> StudySourcesScreen(palette)
                StudyRoute.Credits -> ContextCredits(palette)
                is StudyRoute.Viewer -> ContextViewer(route.tab, chapter, study, reader, palette)
                is StudyRoute.Chart -> ChartScreen(route.id, chapter, reader, palette)
                is StudyRoute.PlaceDetail -> PlaceDetailScreen(route.place, reader, palette)
            }
        }
    }
}

private fun routeTitle(route: StudyRoute, study: StudyModel, reader: ReaderViewModel): String = when (route) {
    StudyRoute.Sources -> "Study Resources"
    StudyRoute.Credits -> "Sources & Credits"
    is StudyRoute.Viewer -> com.blainemiller.scripturealone.data.Canon.display(
        study.verse?.let { ChapterRef(it.book, it.chapter) } ?: reader.location,
    )
    is StudyRoute.Chart -> StudyLibrary.contextDataOrNull()?.charts?.firstOrNull { it.id == route.id }?.title ?: "Chart"
    is StudyRoute.PlaceDetail -> route.place.name
}

/** The back trail on the left, the verse on the right. */
@Composable
private fun VerseHeader(study: StudyModel, reader: ReaderViewModel, palette: ReaderPalette) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 2.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val previous = study.history.lastOrNull()
        if (previous != null) {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { study.back(reader) }
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "Back to ${previous.display}" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.ChevronLeft, null, tint = palette.accent, modifier = Modifier.size(22.dp))
                Text(previous.display, color = palette.accent, fontSize = StudyStyle.subheadline, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        Spacer(Modifier.weight(1f))
        study.verse?.let {
            Text(
                it.display, color = palette.ink, fontSize = StudyStyle.headline, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() },
            )
        }
    }
}

@Composable
private fun TabContent(study: StudyModel, reader: ReaderViewModel, palette: ReaderPalette, chapter: ChapterRef) {
    val verse = study.verse
    when {
        study.tab == StudyTab.CONTEXT -> ContextTab(chapter, verse?.verse, study, reader, palette)
        verse == null -> ContentUnavailable(
            Icons.Rounded.TouchApp, "Tap a Verse",
            "Tap any verse to see where else Scripture speaks to it, and what the old commentators said about it.",
            palette, Modifier.padding(top = 24.dp),
        )
        study.tab == StudyTab.CROSS_REFERENCES -> CrossReferencesTab(verse, study, reader, palette)
        study.tab == StudyTab.COMMENTARY -> CommentaryTab(verse, study, reader, palette)
        else -> InterlinearTab(verse, reader, palette)
    }
}

/** "About Study Resources": every dataset Study draws on, with its licence and attribution. */
@Composable
private fun StudySourcesScreen(palette: ReaderPalette) {
    val sources = loaded(Unit) { context -> StudyLibrary.crossReferences(context)?.sources.orEmpty() }
    val interlinear = loaded(Unit) { context -> StudyLibrary.interlinear(context)?.attribution }
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize().background(StudyStyle.groupedBackground(palette)).verticalScroll(rememberScrollState())) {
        GroupedSection(palette) {
            Cell(palette) {
                Text(
                    "Study mode uses only public-domain and openly licensed works. Everything is bundled with the app and works offline; nothing you look up leaves your device.",
                    color = palette.secondary, fontSize = StudyStyle.callout,
                )
            }
        }
        for (source in sources.orEmpty()) {
            GroupedSection(palette, header = if (source.kind == StudySource.Kind.CROSS_REFERENCES) "Cross References" else "Commentary") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(source.name, color = palette.ink, fontSize = StudyStyle.headline, fontWeight = FontWeight.SemiBold)
                    Text(source.author, color = palette.ink, fontSize = StudyStyle.subheadline)
                    Text(source.year, color = palette.secondary, fontSize = StudyStyle.caption)
                    Spacer(Modifier.height(4.dp))
                    Text(source.attribution, color = palette.secondary, fontSize = StudyStyle.caption)
                }
                CellDivider(palette)
                Cell(palette, onClick = source.licenseUrl.takeIf { it.isNotEmpty() }?.let { url -> { uri.openUri(url) } }) {
                    Text("License", color = palette.ink, fontSize = StudyStyle.body, modifier = Modifier.weight(1f))
                    Text(source.license, color = if (source.licenseUrl.isNotEmpty()) palette.accent else palette.secondary, fontSize = StudyStyle.body)
                }
                if (source.url.isNotEmpty()) {
                    CellDivider(palette)
                    Cell(palette, onClick = { uri.openUri(source.url) }) {
                        Text("Source", color = palette.accent, fontSize = StudyStyle.body)
                    }
                }
            }
        }
        interlinear?.let { attribution ->
            GroupedSection(palette, header = "Original Languages") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    for (line in attribution.requiredLines) {
                        Text(line, color = palette.secondary, fontSize = StudyStyle.caption, modifier = Modifier.padding(bottom = 6.dp))
                    }
                }
                CellDivider(palette)
                Cell(palette, onClick = { uri.openUri(attribution.lexiconLicenseUrl) }) {
                    Text("License", color = palette.ink, fontSize = StudyStyle.body, modifier = Modifier.weight(1f))
                    Text(attribution.lexiconLicense, color = palette.accent, fontSize = StudyStyle.body)
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}
