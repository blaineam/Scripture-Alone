package com.blainemiller.scripturealone.ui.study

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContrastRounded
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Sailing
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.GpsFixed
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blainemiller.scripturealone.data.Canon
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.context.ChapterTime
import com.blainemiller.scripturealone.data.context.ChartInfo
import com.blainemiller.scripturealone.data.context.ChartKind
import com.blainemiller.scripturealone.data.context.Era
import com.blainemiller.scripturealone.data.context.FeastsChart
import com.blainemiller.scripturealone.data.context.JourneysChart
import com.blainemiller.scripturealone.data.context.KingsChart
import com.blainemiller.scripturealone.data.context.MapLabel
import com.blainemiller.scripturealone.data.context.Place
import com.blainemiller.scripturealone.data.context.PlaceKind
import com.blainemiller.scripturealone.data.context.PlaceMention
import com.blainemiller.scripturealone.data.context.TimelineEvent
import com.blainemiller.scripturealone.data.context.TribesChart
import com.blainemiller.scripturealone.data.context.keyRange
import com.blainemiller.scripturealone.data.context.overlaps
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.blainemiller.scripturealone.data.translations.TranslationLibrary
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.blainemiller.scripturealone.ui.reader.ReaderViewModel
import com.blainemiller.scripturealone.ui.reader.SheetColors
import com.blainemiller.scripturealone.ui.reader.glass

// Study's Context tab and everything it opens — `StudyContextView.swift` and `Study/Context/`: when
// the chapter happened (its era on a timeline), where (a map of the places it names), and charts for
// the book; the large map, the whole timeline, every chart, a place's verses, and the credits.
// Everything is bundled and offline.

/** "#RRGGBB" → a colour; grey for anything unreadable, as iOS falls back. */
fun contextColor(hex: String): Color {
    val value = hex.trim().removePrefix("#").toLongOrNull(16) ?: 0x888888
    return Color(0xFF000000 or value)
}

/** The base map, labels and prominent places, built once for the app. */
private object MapLibraryCache {
    @Volatile var value: MapLibrary? = null

    @Synchronized
    fun get(context: Context): MapLibrary? = value ?: run {
        val data = StudyLibrary.contextData(context) ?: return null
        MapLibrary(StudyLibrary.basemap(context)?.let(::BasemapPaths), data.labels, data.prominentPlaces)
    }.also { value = it }
}

@Composable
private fun mapLibrary(): MapLibrary? = loaded(Unit) { MapLibraryCache.get(it) }

/** "Acts 13:1–14:28" shortened within a known book: "13:1–14:28". */
private val VerseRange.chapterVerseDisplay: String
    get() {
        val full = display
        val prefix = (BookID.of(start.book)?.displayName ?: "") + " "
        return if (full.startsWith(prefix)) full.removePrefix(prefix) else full
    }

@Composable
fun ContextTab(chapter: ChapterRef, verse: Int?, study: StudyModel, reader: ReaderViewModel, palette: ReaderPalette) {
    class Loaded(val data: StudyLibrary.ContextData, val time: ChapterTime?, val places: List<PlaceMention>, val events: List<TimelineEvent>)
    val loaded = loaded(chapter) { context ->
        val store = StudyLibrary.context(context) ?: return@loaded null
        val data = StudyLibrary.contextData(context) ?: return@loaded null
        Loaded(data, store.time(chapter), store.places(chapter), store.events(chapter))
    }
    val library = mapLibrary()
    if (loaded == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = palette.secondary, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
        }
        return
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SymbolHeading(Icons.Rounded.Timeline, "When", palette)
            WhenSection(chapter, loaded.data.eras, loaded.time, loaded.events, palette, reader) {
                study.push(StudyRoute.Viewer(StudyRoute.ViewerTab.TIMELINE))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SymbolHeading(Icons.Rounded.Map, "Where", palette)
            WhereSection(chapter, verse, loaded.places, library, palette,
                openMap = { study.push(StudyRoute.Viewer(StudyRoute.ViewerTab.MAP)) },
                openPlace = { study.push(StudyRoute.PlaceDetail(it)) })
        }
        val suggested = loaded.data.charts.filter { chapter.book in it.scope }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SymbolHeading(Icons.Rounded.TableChart, "Charts", palette)
            Spacer(Modifier.height(4.dp))
            for (chart in suggested) ChartCard(chart, palette, framed = true) { study.push(StudyRoute.Chart(chart.id)) }
            AccentButton(if (suggested.isEmpty()) "Browse Charts" else "All Charts", palette, icon = Icons.Rounded.GridView) {
                study.push(StudyRoute.Viewer(StudyRoute.ViewerTab.CHARTS))
            }
        }
        AccentButton("Sources & Credits", palette, icon = Icons.Outlined.Info, fontSize = StudyStyle.footnote) {
            study.push(StudyRoute.Credits)
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---- When ----------------------------------------------------------------------------------------

/**
 * A compact band of the Bible's eras with the chapter's place marked. Eras get equal widths: the span
 * from Abraham to the apostles is too uneven to draw to scale at this size.
 */
@Composable
private fun EraBand(eras: List<Era>, time: ChapterTime?, palette: ReaderPalette) {
    Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(18.dp).drawBehind {
        if (eras.isEmpty()) return@drawBehind
        val gap = 2.dp.toPx()
        val w = (size.width - gap * (eras.size - 1)) / eras.size
        eras.forEachIndexed { i, era ->
            val current = time?.eras?.contains(era) == true
            val primary = time?.era == era
            val h = if (primary) size.height else 12.dp.toPx()
            drawRoundRect(
                contextColor(era.color).copy(alpha = if (primary) 1f else if (current) 0.6f else 0.22f),
                topLeft = Offset(i * (w + gap), (size.height - h) / 2),
                size = androidx.compose.ui.geometry.Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
            )
        }
        val index = time?.let { t -> eras.indexOf(t.era) } ?: -1
        if (index >= 0 && time != null) {
            var fraction = 0.5f
            val start = time.era.start
            val end = time.era.end
            val year = time.year
            if (year != null && start != null && end != null && end > start) {
                fraction = ((year - start).toFloat() / (end - start)).coerceIn(0.08f, 0.92f)
            }
            val x = (w + gap) * index + w * fraction
            val tri = Path().apply {
                moveTo(x - 5.dp.toPx(), -11.dp.toPx()); lineTo(x + 5.dp.toPx(), -11.dp.toPx()); lineTo(x, -2.dp.toPx()); close()
            }
            drawPath(tri, palette.ink)
        }
    })
}

@Composable
private fun WhenSection(
    chapter: ChapterRef,
    eras: List<Era>,
    time: ChapterTime?,
    events: List<TimelineEvent>,
    palette: ReaderPalette,
    reader: ReaderViewModel,
    openTimeline: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = openTimeline)
                .semantics(mergeDescendants = true) {
                    contentDescription = time?.let { "Timeline. ${it.era.name}, ${it.era.dates}." } ?: "Timeline"
                },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EraBand(eras, time, palette)
            if (time != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(time.era.name, color = palette.ink, fontSize = StudyStyle.headline, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(time.era.dates, color = palette.secondary, fontSize = StudyStyle.subheadline)
                }
                time.yearLabel?.let { year ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (time.basis == ChapterTime.Basis.EVENTS) Icons.Rounded.Schedule else Icons.Rounded.Edit, null,
                            tint = palette.accent, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (time.basis == ChapterTime.Basis.EVENTS) "${Canon.display(chapter)}: $year" else "Written $year",
                            color = palette.ink, fontSize = StudyStyle.subheadline,
                        )
                    }
                }
                if (time.eras.size > 1) {
                    Text("Also spans: " + time.eras.drop(1).joinToString(", ") { it.name }, color = palette.secondary, fontSize = StudyStyle.footnote)
                }
                time.note?.let { Text(it, color = palette.secondary, fontSize = StudyStyle.footnote) }
            }
        }
        if (events.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (event in events) EventRow(event, compact = true, palette = palette) { reader.go(it) }
            }
        }
    }
}

/** One event, tappable to read its passage. */
@Composable
private fun EventRow(event: TimelineEvent, compact: Boolean, palette: ReaderPalette, highlight: Color? = null, open: (VerseRef) -> Unit) {
    val range = event.range
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(highlight ?: Color.Transparent)
            .let { if (range != null) it.clickable { open(range.start) } else it }
            .semantics(mergeDescendants = true) {}
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            event.date ?: "—", color = palette.secondary, fontSize = StudyStyle.caption,
            modifier = Modifier.widthIn(min = if (compact) 74.dp else 96.dp).padding(end = 10.dp, top = 2.dp),
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(event.name, color = palette.ink, fontSize = if (compact) StudyStyle.subheadline else StudyStyle.body)
                if (event.debated) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Rounded.HelpOutline, "Date debated", tint = palette.secondary, modifier = Modifier.size(14.dp))
                }
            }
            if (range != null) Text(range.display, color = palette.accent, fontSize = StudyStyle.caption)
        }
    }
}

// ---- Where ---------------------------------------------------------------------------------------

@Composable
private fun WhereSection(
    chapter: ChapterRef,
    verse: Int?,
    mentions: List<PlaceMention>,
    library: MapLibrary?,
    palette: ReaderPalette,
    openMap: () -> Unit,
    openPlace: (Place) -> Unit,
) {
    if (mentions.isEmpty()) {
        Text("No places are named in ${Canon.display(chapter)}.", color = palette.secondary, fontSize = StudyStyle.subheadline)
        AccentButton("Open the Map", palette, icon = Icons.Rounded.Map, onClick = openMap)
        return
    }
    // The reader's verse's places first, so they win label space.
    val sorted = mentions.sortedByDescending { m -> verse != null && m.verses.any { it % 1000 == verse } }
    val pins = sorted.map { MapPin.of(it.place) }
    var selected by remember(chapter) { mutableStateOf<Int?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(14.dp))) {
            if (library != null) {
                BibleMap(
                    MapContent(pins, selectedId = selected, fitRect = MapContent.fitRect(pins.filter { !it.isArea || pins.size < 3 })),
                    library, palette, Modifier.fillMaxSize(), fitToken = chapter,
                ) { id ->
                    selected = id
                    mentions.firstOrNull { it.place.id == id }?.place?.let(openPlace)
                }
            }
            Box(
                Modifier.align(Alignment.TopEnd).padding(10.dp).size(34.dp).glass(palette, CircleShape, palette.page, lifted = true)
                    .clickable(onClick = openMap).semantics { contentDescription = "Open Large Map" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.OpenInFull, null, tint = palette.ink, modifier = Modifier.size(17.dp))
            }
        }
        Column {
            mentions.forEachIndexed { index, mention ->
                PlaceRow(mention.place, mention.verses, verse, palette) { openPlace(mention.place) }
                if (index < mentions.lastIndex) Box(Modifier.fillMaxWidth().height(0.5.dp).background(StudyStyle.separator(palette)))
            }
        }
    }
}

private fun PlaceKind.icon(): ImageVector = when (this) {
    PlaceKind.SETTLEMENT -> Icons.Rounded.AccountBalance
    PlaceKind.REGION -> Icons.Rounded.CropFree
    PlaceKind.ISLAND -> Icons.Rounded.RadioButtonUnchecked
    PlaceKind.WATER -> Icons.Rounded.WaterDrop
    PlaceKind.MOUNTAIN -> Icons.Rounded.Landscape
    PlaceKind.SITE -> Icons.Rounded.Place
}

@Composable
private fun PlaceRow(place: Place, verses: List<Int>, highlight: Int?, palette: ReaderPalette, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).semantics(mergeDescendants = true) {}.padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(place.kind.icon(), null, tint = palette.accent, modifier = Modifier.padding(top = 1.dp).size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(place.name, color = palette.ink, fontSize = StudyStyle.body, fontWeight = FontWeight.SemiBold)
                if (place.modernName.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text(place.modernName, color = palette.secondary, fontSize = StudyStyle.caption, modifier = Modifier.padding(bottom = 2.dp))
                }
            }
            val numbers = verses.map { it % 1000 }
            Text(
                (if (numbers.size == 1) "Verse " else "Verses ") + numbers.joinToString(", "),
                color = if (highlight != null && highlight in numbers) palette.accent else palette.secondary, fontSize = StudyStyle.caption,
            )
            if (place.confidenceLevel != Place.Confidence.IDENTIFIED) ConfidenceBadge(place, palette)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = StudyStyle.tertiary(palette), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ConfidenceBadge(place: Place, palette: ReaderPalette) {
    val uncertain = place.confidenceLevel == Place.Confidence.UNCERTAIN
    val parts = listOf(place.confidenceLevel.title) + if (place.isArea && place.kind != PlaceKind.REGION) listOf("approximate area") else emptyList()
    val color = if (uncertain) Color(0xFFE08A1E) else palette.secondary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (uncertain) Icons.Rounded.HelpOutline else Icons.Rounded.GpsFixed, null, tint = color, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(parts.joinToString(" · "), color = color, fontSize = StudyStyle.caption2, fontWeight = FontWeight.SemiBold)
    }
}

// ---- Charts --------------------------------------------------------------------------------------

private fun ChartKind.icon(): ImageVector = when (this) {
    ChartKind.KINGS -> Icons.Rounded.WorkspacePremium
    ChartKind.JOURNEYS -> Icons.Rounded.Sailing
    ChartKind.TRIBES -> Icons.Rounded.Groups
    ChartKind.FEASTS -> Icons.Rounded.CalendarMonth
}

@Composable
private fun ChartCard(chart: ChartInfo, palette: ReaderPalette, framed: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (framed) SheetColors.tertiaryFill(palette) else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(if (framed) 10.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(palette.accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(chart.kind.icon(), null, tint = palette.accent, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(chart.title, color = palette.ink, fontSize = StudyStyle.subheadline, fontWeight = FontWeight.SemiBold)
            Text(chart.subtitle, color = palette.secondary, fontSize = StudyStyle.caption)
        }
        if (framed) Icon(Icons.Rounded.ChevronRight, null, tint = StudyStyle.tertiary(palette), modifier = Modifier.size(20.dp))
    }
}

// ---- The viewer: map, timeline, charts -----------------------------------------------------------

@Composable
fun ContextViewer(tab: StudyRoute.ViewerTab, chapter: ChapterRef, study: StudyModel, reader: ReaderViewModel, palette: ReaderPalette) {
    when (tab) {
        StudyRoute.ViewerTab.MAP -> MapExplorer(chapter, study, palette)
        StudyRoute.ViewerTab.TIMELINE -> FullTimeline(chapter, reader, palette)
        StudyRoute.ViewerTab.CHARTS -> ChartsList(chapter, study, palette)
    }
}

/** Full-size map of the chapter's places, with search across every place — `ContextMapExplorer`. */
@Composable
private fun MapExplorer(chapter: ChapterRef, study: StudyModel, palette: ReaderPalette) {
    val library = mapLibrary()
    val mentions = loaded(chapter) { StudyLibrary.context(it)?.places(chapter) }.orEmpty()
    var query by rememberSaveable { mutableStateOf("") }
    var focus by remember(chapter) { mutableStateOf<Place?>(null) }
    val results = loaded(query) { context -> if (query.isBlank()) emptyList() else StudyLibrary.context(context)?.searchPlaces(query, limit = 12) }.orEmpty()
    val pins = buildList {
        focus?.let { f -> if (mentions.none { it.place.id == f.id }) add(MapPin.of(f)) }
        addAll(mentions.map { MapPin.of(it.place) })
    }
    val fit = focus?.let { MapProjection.fit(listOf(MapPin.of(it).point), minSpan = 3f) } ?: MapContent.fitRect(pins)
    Box(Modifier.fillMaxSize()) {
        if (library != null) {
            BibleMap(MapContent(pins, selectedId = focus?.id, fitRect = fit), library, palette, Modifier.fillMaxSize(),
                fitToken = chapter to focus?.id) { id ->
                val place = mentions.firstOrNull { it.place.id == id }?.place ?: (focus?.takeIf { it.id == id })
                if (place != null) study.push(StudyRoute.PlaceDetail(place))
                else StudyLibrary.contextDataOrNull()?.prominentPlaces?.firstOrNull { it.id == id }?.let { study.push(StudyRoute.PlaceDetail(it)) }
            }
        }
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().height(44.dp).glass(palette, CircleShape, palette.page, lifted = true).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = palette.secondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    query, { query = it }, singleLine = true,
                    textStyle = TextStyle(color = palette.ink, fontSize = 16.sp), cursorBrush = SolidColor(palette.accent),
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Find a place" },
                    decorationBox = { field ->
                        if (query.isEmpty()) Text("Find a place", color = palette.secondary, fontSize = 16.sp)
                        field()
                    },
                )
            }
            if (results.isNotEmpty()) {
                Column(
                    Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SheetColors.popover(palette)),
                ) {
                    results.forEachIndexed { i, place ->
                        if (i > 0) CellDivider(palette)
                        Column(Modifier.fillMaxWidth().clickable { focus = place; query = "" }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(place.name, color = palette.ink, fontSize = StudyStyle.callout)
                            Text(
                                if (place.modernName.isEmpty()) "${place.mentions} verses" else "${place.modernName} · ${place.mentions} verses",
                                color = palette.secondary, fontSize = StudyStyle.caption,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The whole story, era by era, with the reader's chapter marked — `FullTimelineView`. */
@Composable
private fun FullTimeline(chapter: ChapterRef, reader: ReaderViewModel, palette: ReaderPalette) {
    class Loaded(val data: StudyLibrary.ContextData, val time: ChapterTime?, val chapterEvents: Set<Int>)
    val loaded = loaded(chapter) { context ->
        val store = StudyLibrary.context(context) ?: return@loaded null
        Loaded(StudyLibrary.contextData(context) ?: return@loaded null, store.time(chapter), store.events(chapter).map { it.id }.toSet())
    } ?: return
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { EraBand(loaded.data.eras, loaded.time, palette) }
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            for (era in loaded.data.eras) {
                EraCard(era, loaded.time, chapter, loaded.data.events.filter { it.eraId == era.id }, loaded.chapterEvents, palette) { reader.go(it) }
            }
            Text(
                "Dates before the divided kingdom are approximate; ? marks dates that scholars dispute. Sources are listed under Sources & Credits.",
                color = palette.secondary, fontSize = StudyStyle.footnote,
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun EraCard(era: Era, time: ChapterTime?, chapter: ChapterRef, events: List<TimelineEvent>, chapterEvents: Set<Int>, palette: ReaderPalette, open: (VerseRef) -> Unit) {
    val current = time?.era == era
    val color = contextColor(era.color)
    var debate by rememberSaveable(era.id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(SheetColors.tertiaryFill(palette).copy(alpha = if (current) 0.24f else 0.10f))
            .let { if (current) it.border(1.5.dp, color, shape) else it }
            .drawBehind { drawRect(color, size = androidx.compose.ui.geometry.Size(5.dp.toPx(), size.height)) }
            .padding(start = 19.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(era.name, color = palette.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
            Text(era.dates, color = palette.secondary, fontSize = StudyStyle.subheadline)
        }
        if (current && time != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Bookmark, null, tint = color, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    time.yearLabel?.let { "${Canon.display(chapter)} — $it" } ?: "${Canon.display(chapter)} is set here",
                    color = color, fontSize = StudyStyle.subheadline, fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Text(era.summary, color = palette.ink, fontSize = StudyStyle.subheadline)
        for (event in events) {
            EventRow(event, compact = false, palette = palette, highlight = if (event.id in chapterEvents) color.copy(alpha = 0.14f) else null, open = open)
        }
        if (era.debate.isNotEmpty()) {
            Row(Modifier.clickable { debate = !debate }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("About these dates", color = palette.accent, fontSize = StudyStyle.footnote, fontWeight = FontWeight.SemiBold)
            }
            if (debate) Text(era.debate, color = palette.secondary, fontSize = StudyStyle.footnote)
        }
    }
}

/** Every chart, those for the current book first — `ChartsList`. */
@Composable
private fun ChartsList(chapter: ChapterRef, study: StudyModel, palette: ReaderPalette) {
    val charts = loaded(Unit) { StudyLibrary.contextData(it)?.charts }.orEmpty()
    val suggested = charts.filter { chapter.book in it.scope }
    val others = charts.filter { chapter.book !in it.scope }
    Column(Modifier.fillMaxSize().background(StudyStyle.groupedBackground(palette)).verticalScroll(rememberScrollState())) {
        if (suggested.isNotEmpty()) {
            GroupedSection(palette, header = "For ${BookID.of(chapter.book)?.displayName}") {
                suggested.forEachIndexed { i, chart ->
                    if (i > 0) CellDivider(palette, inset = 64.dp)
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) { ChartCard(chart, palette, framed = false) { study.push(StudyRoute.Chart(chart.id)) } }
                }
            }
        }
        GroupedSection(palette, header = if (suggested.isEmpty()) "Charts" else "More Charts") {
            others.forEachIndexed { i, chart ->
                if (i > 0) CellDivider(palette, inset = 64.dp)
                Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) { ChartCard(chart, palette, framed = false) { study.push(StudyRoute.Chart(chart.id)) } }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

// ---- A chart ---------------------------------------------------------------------------------------

@Composable
fun ChartScreen(id: String, chapter: ChapterRef, reader: ReaderViewModel, palette: ReaderPalette) {
    val chart = loaded(id) { context -> StudyLibrary.contextData(context)?.charts?.firstOrNull { it.id == id } } ?: return
    val library = mapLibrary()
    val open: (VerseRange) -> Unit = { reader.go(it.start) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (chart.kind) {
                ChartKind.KINGS -> runCatching { chart.kings() }.getOrNull()?.let { KingsChartView(it, chapter, palette, open) }
                ChartKind.JOURNEYS -> runCatching { chart.journeys() }.getOrNull()?.let { JourneysChartView(it, chapter, library, palette, open) }
                ChartKind.TRIBES -> runCatching { chart.tribes() }.getOrNull()?.let { TribesChartView(it, library, palette, open) }
                ChartKind.FEASTS -> runCatching { chart.feasts() }.getOrNull()?.let { FeastsChartView(it, palette, open) }
            }
        }
        Text(
            chart.sources, color = palette.secondary, fontSize = StudyStyle.caption2,
            modifier = Modifier.fillMaxWidth().background(SheetColors.surface(palette)).padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** A tappable reference that opens the passage in the reader. */
@Composable
private fun ReferenceButton(range: VerseRange, palette: ReaderPalette, label: String? = null, open: (VerseRange) -> Unit) {
    Text(
        label ?: range.display, color = palette.accent, fontSize = StudyStyle.caption, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { open(range) }.padding(vertical = 3.dp)
            .semantics { contentDescription = "Read ${range.display}" },
    )
}

private fun KingsChart.Verdict.title() = when (this) {
    KingsChart.Verdict.GOOD -> "Did right"
    KingsChart.Verdict.EVIL -> "Did evil"
    KingsChart.Verdict.MIXED -> "Mixed"
}

private fun KingsChart.Verdict.icon() = when (this) {
    KingsChart.Verdict.GOOD -> Icons.Rounded.CheckCircle
    KingsChart.Verdict.EVIL -> Icons.Rounded.Cancel
    KingsChart.Verdict.MIXED -> Icons.Rounded.ContrastRounded
}

private fun KingsChart.Verdict.color() = when (this) {
    KingsChart.Verdict.GOOD -> contextColor("#2E8B57")
    KingsChart.Verdict.EVIL -> contextColor("#C0392B")
    KingsChart.Verdict.MIXED -> contextColor("#C98A1B")
}

@Composable
private fun KingsChartView(chart: KingsChart, chapter: ChapterRef, palette: ReaderPalette, open: (VerseRange) -> Unit) {
    val kingsBooks = setOf(BookID.FIRST_KINGS.number, BookID.SECOND_KINGS.number)
    /** While reading 1–2 Kings, the king whose account the chapter falls in. */
    fun current(kings: List<KingsChart.King>): KingsChart.King? {
        if (chapter.book !in kingsBooks) return null
        // Solomon's account ends with 1 Kings 11; after that only the divided kingdoms apply.
        if (kings === chart.united && ChapterRef(BookID.FIRST_KINGS.number, 11) < chapter) return null
        val end = chapter.keyRange.last
        return kings.filter { it.ref.start.book in kingsBooks && it.ref.start.key <= end }.maxByOrNull { it.ref.start.key }
    }
    var judah by rememberSaveable {
        mutableStateOf(chapter.book == BookID.SECOND_CHRONICLES.number || (current(chart.judah) != null && current(chart.israel) == null))
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 640.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (verdict in listOf(KingsChart.Verdict.GOOD, KingsChart.Verdict.MIXED, KingsChart.Verdict.EVIL)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(verdict.icon(), null, tint = verdict.color(), modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(verdict.title(), color = verdict.color(), fontSize = StudyStyle.caption, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            KingsColumn("United Kingdom", "Israel", chart.united, current(chart.united), palette, open)
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.weight(1f)) { KingsColumn("Israel (north)", "Israel", chart.israel, current(chart.israel), palette, open) }
                    Box(Modifier.weight(1f)) { KingsColumn("Judah (south)", "Judah", chart.judah, current(chart.judah), palette, open) }
                }
            } else {
                SegmentedPicker(listOf("Israel", "Judah"), if (judah) 1 else 0, palette) { judah = it == 1 }
                if (judah) KingsColumn("Judah (south)", "Judah", chart.judah, current(chart.judah), palette, open)
                else KingsColumn("Israel (north)", "Israel", chart.israel, current(chart.israel), palette, open)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun KingsColumn(title: String, kingdom: String, kings: List<KingsChart.King>, here: KingsChart.King?, palette: ReaderPalette, open: (VerseRange) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = palette.ink, fontSize = StudyStyle.headline, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        for (king in kings) {
            val isCurrent = king.id == here?.id
            val shape = RoundedCornerShape(10.dp)
            val years = if (king.years.toIntOrNull() != null) "${king.years} years" else king.years
            Row(
                Modifier.fillMaxWidth().clip(shape)
                    .background(if (isCurrent) palette.accent.copy(alpha = 0.14f) else SheetColors.tertiaryFill(palette).copy(alpha = 0.10f))
                    .let { if (isCurrent) it.border(1.5.dp, palette.accent, shape) else it }
                    .clickable { open(king.ref) }
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${king.name}, $kingdom, ${king.reign}, $years. ${king.verdict.title()}." +
                            (if (isCurrent) " The chapter you are reading." else "")
                    }
                    .padding(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(king.verdict.icon(), null, tint = king.verdict.color(), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(king.name, color = palette.ink, fontSize = StudyStyle.subheadline, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(king.reign, color = palette.secondary, fontSize = StudyStyle.caption)
                    }
                    Text("$years · ${king.ref.start.display}", color = palette.secondary, fontSize = StudyStyle.caption)
                    king.prophets?.takeIf { it.isNotEmpty() }?.let { prophets ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Campaign, null, tint = palette.accent, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(prophets.joinToString(", "), color = palette.accent, fontSize = StudyStyle.caption)
                        }
                    }
                    king.note?.let { Text(it, color = palette.secondary, fontSize = StudyStyle.caption) }
                }
            }
        }
    }
}

@Composable
private fun JourneysChartView(chart: JourneysChart, chapter: ChapterRef, library: MapLibrary?, palette: ReaderPalette, open: (VerseRange) -> Unit) {
    val initial = remember(chart, chapter) {
        if (chapter.book != BookID.ACTS.number) chart.journeys.first().id
        else chart.journeys.firstOrNull { it.refs.overlaps(chapter) }?.id ?: if (chapter.chapter > 21) "rome" else chart.journeys.first().id
    }
    var selected by rememberSaveable(chapter) { mutableStateOf(initial) }
    val journey = chart.journeys.firstOrNull { it.id == selected } ?: chart.journeys.first()
    val color = contextColor(journey.color)
    val points = journey.stops.map { MapProjection.point(it.lon, it.lat) }
    val content = MapContent(
        pins = journey.stops.map { MapPin(it.id, it.name, MapProjection.point(it.lon, it.lat), it.kind, it.kind == PlaceKind.REGION, color) },
        routes = listOf(MapRoute(journey.id, points, color)),
        showsBackgroundPlaces = false,
        fitRect = MapProjection.fit(points),
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SegmentedPicker(chart.journeys.map { when (it.id) { "first" -> "1st"; "second" -> "2nd"; "third" -> "3rd"; else -> "Rome" } },
            chart.journeys.indexOf(journey), palette) { selected = chart.journeys[it].id }
        if (library != null) {
            BibleMap(content, library, palette, Modifier.fillMaxWidth().height(340.dp).clip(RoundedCornerShape(14.dp)), fitToken = selected)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(journey.name, color = palette.ink, fontSize = StudyStyle.headline, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(journey.dates, color = palette.secondary, fontSize = StudyStyle.subheadline)
        }
        ReferenceButton(journey.refs, palette, open = open)
        Column {
            journey.stops.forEachIndexed { index, stop ->
                Row(
                    Modifier.fillMaxWidth().clickable { open(stop.ref) }.semantics(mergeDescendants = true) {}.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                        Text("${index + 1}", color = Color.White, fontSize = StudyStyle.caption, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stop.name, color = palette.ink, fontSize = StudyStyle.subheadline, fontWeight = FontWeight.SemiBold)
                        stop.note?.let { Text(it, color = palette.secondary, fontSize = StudyStyle.caption) }
                    }
                    Text(stop.ref.start.display, color = palette.accent, fontSize = StudyStyle.caption)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TribesChartView(chart: TribesChart, library: MapLibrary?, palette: ReaderPalette, open: (VerseRange) -> Unit) {
    val tags = chart.tribes.flatMap { tribe ->
        val name = tribe.name.replace("Joseph: ", "")
        listOfNotNull(
            if (tribe.lon != null && tribe.lat != null) MapTag(name, MapProjection.point(tribe.lon, tribe.lat)) else null,
            if (tribe.lon2 != null && tribe.lat2 != null) MapTag(name, MapProjection.point(tribe.lon2, tribe.lat2)) else null,
        )
    }
    val content = MapContent(tags = tags, showsBackgroundPlaces = false, fitRect = MapProjection.rect(34.6, 31.0, 36.2, 33.3))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (library != null) BibleMap(content, library, palette, Modifier.fillMaxWidth().height(420.dp).clip(RoundedCornerShape(14.dp)), fitToken = 0)
        Text("Label positions mark the approximate center of each allotment (Joshua 13–19).", color = palette.secondary, fontSize = StudyStyle.caption)
        for (tribe in chart.tribes) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SheetColors.tertiaryFill(palette).copy(alpha = 0.12f)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(tribe.name, color = palette.ink, fontSize = StudyStyle.headline, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("Son ${tribe.order} · ${tribe.mother}", color = palette.secondary, fontSize = StudyStyle.caption)
                }
                tribe.note?.let { Text(it, color = palette.secondary, fontSize = StudyStyle.caption) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ReferenceButton(tribe.birth, palette, "Birth", open)
                    ReferenceButton(tribe.jacob, palette, "Jacob’s blessing", open)
                    tribe.moses?.let { ReferenceButton(it, palette, "Moses’ blessing", open) }
                    ReferenceButton(tribe.allotment, palette, if (tribe.name == "Levi") "Cities" else "Land", open)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeastsChartView(chart: FeastsChart, palette: ReaderPalette, open: (VerseRange) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The seven appointed times of Leviticus 23 laid out across the year.
        val spring = chart.feasts.filter { it.later != true && (it.season.startsWith("March") || it.season.startsWith("May")) }
        val autumn = chart.feasts.filter { it.later != true && it.season.startsWith("September") }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for ((title, feasts, color) in listOf(Triple("Spring", spring, contextColor("#3F8F6B")), Triple("Autumn", autumn, contextColor("#C9862B")))) {
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.1f)).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(title, color = color, fontSize = StudyStyle.caption, fontWeight = FontWeight.Bold)
                    for (feast in feasts) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
                            Spacer(Modifier.width(6.dp))
                            Text(feast.name, color = palette.ink, fontSize = StudyStyle.caption)
                        }
                    }
                }
            }
        }
        for (feast in chart.feasts) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SheetColors.tertiaryFill(palette).copy(alpha = 0.12f)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(feast.name, color = palette.ink, fontSize = StudyStyle.headline, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Text(feast.hebrew, color = palette.secondary, fontSize = StudyStyle.subheadline, fontStyle = FontStyle.Italic)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Event, null, tint = palette.ink, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(feast.date, color = palette.ink, fontSize = StudyStyle.caption)
                    Text(" · ${feast.season}", color = palette.secondary, fontSize = StudyStyle.caption)
                }
                if (feast.pilgrim == true || feast.later == true) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (feast.pilgrim == true) Tag("Pilgrim feast", palette)
                        if (feast.later == true) Tag("Later feast", palette)
                    }
                }
                Text(feast.meaning, color = palette.ink, fontSize = StudyStyle.subheadline)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ReferenceButton(feast.refs, palette, open = open)
                    feast.also?.let { ReferenceButton(it, palette, open = open) }
                }
                feast.nt?.let { nt ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(palette.accent.copy(alpha = 0.08f)).padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            (if (feast.interpretive == true) "Often connected with" else "In the New Testament").uppercase(),
                            color = palette.secondary, fontSize = StudyStyle.caption2, fontWeight = FontWeight.Bold,
                        )
                        feast.ntText?.let { Text(it, color = palette.ink, fontSize = StudyStyle.caption) }
                        ReferenceButton(nt, palette, open = open)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Tag(text: String, palette: ReaderPalette) {
    Text(
        text, color = palette.ink, fontSize = StudyStyle.caption2, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(CircleShape).background(palette.accent.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// ---- A place -------------------------------------------------------------------------------------

/** Every verse that mentions a place, with a small map. Tap a verse to read it — `PlaceDetailView`. */
@Composable
fun PlaceDetailScreen(place: Place, reader: ReaderViewModel, palette: ReaderPalette) {
    val library = mapLibrary()
    val uri = LocalUriHandler.current
    val translation = reader.translationId
    val verses = loaded(place.id to translation) { context ->
        val keys = StudyLibrary.context(context)?.versesMentioning(place.id).orEmpty()
        keys.take(400).map { key ->
            val ref = VerseRef.fromKey(key)
            key to TranslationLibrary.verses(context, translation, VerseRange(ref, ref)).firstOrNull()?.text
        }
    }
    Column(Modifier.fillMaxSize().background(StudyStyle.groupedBackground(palette)).verticalScroll(rememberScrollState())) {
        GroupedSection(palette) {
            if (library != null) {
                val pin = MapPin.of(place)
                BibleMap(
                    MapContent(listOf(pin), selectedId = place.id, fitRect = MapProjection.fit(listOf(pin.point), minSpan = 3.2f)),
                    library, palette, Modifier.fillMaxWidth().height(180.dp), fitToken = place.id, showsControls = false,
                )
            }
            if (place.modernName.isNotEmpty()) {
                LabeledCell(palette, "Identified with", place.modernName)
                CellDivider(palette)
            }
            LabeledCell(palette, "Type", place.type.replaceFirstChar { it.uppercase() })
            CellDivider(palette)
            Cell(palette) {
                Text("Confidence", color = palette.ink, fontSize = StudyStyle.body, modifier = Modifier.weight(1f))
                ConfidenceBadge(place, palette)
            }
            if (place.alternatives > 0) {
                CellDivider(palette)
                Cell(palette) {
                    Text(
                        if (place.alternatives == 1) "One other location has been proposed." else "${place.alternatives} other locations have been proposed.",
                        color = palette.secondary, fontSize = StudyStyle.footnote,
                    )
                }
            }
            CellDivider(palette)
            Cell(palette, onClick = { uri.openUri(place.sourceUrl) }) {
                Text("Evidence at OpenBible.info", color = palette.accent, fontSize = StudyStyle.footnote)
            }
        }
        val groups = verses.orEmpty().groupBy { it.first / 1_000_000 }
        for ((book, keys) in groups) {
            GroupedSection(palette, header = "${BookID.of(book)?.displayName} (${keys.size})") {
                keys.forEachIndexed { i, (key, text) ->
                    if (i > 0) CellDivider(palette)
                    val ref = VerseRef.fromKey(key)
                    Column(Modifier.fillMaxWidth().clickable { reader.go(ref) }.padding(horizontal = 16.dp, vertical = 9.dp)) {
                        Text(ref.display, color = palette.ink, fontSize = StudyStyle.subheadline, fontWeight = FontWeight.SemiBold)
                        text?.let { Text(it, color = palette.secondary, fontSize = StudyStyle.subheadline, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

// ---- Credits -------------------------------------------------------------------------------------

/** Credits and licences for the maps, places, timeline and charts — `ContextAttributionView`. */
@Composable
fun ContextCredits(palette: ReaderPalette) {
    val uri = LocalUriHandler.current
    @Composable
    fun credit(title: String, detail: String, links: List<Pair<String, String>>) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = palette.ink, fontSize = StudyStyle.headline, fontWeight = FontWeight.SemiBold)
            Text(detail, color = palette.secondary, fontSize = StudyStyle.subheadline)
            for ((label, url) in links) {
                Text(label, color = palette.accent, fontSize = StudyStyle.subheadline, modifier = Modifier.clickable { uri.openUri(url) })
            }
        }
    }
    Column(Modifier.fillMaxSize().background(StudyStyle.groupedBackground(palette)).verticalScroll(rememberScrollState())) {
        GroupedSection(palette) {
            Cell(palette) {
                Text(
                    "Study context works entirely offline. Nothing about what you read is sent anywhere; the one exception is a link you choose to open.",
                    color = palette.ink, fontSize = StudyStyle.subheadline,
                )
            }
        }
        GroupedSection(palette, header = "Places") {
            credit("OpenBible.info Bible Geocoding Data",
                "Places named in the Bible, their most likely locations, confidence scores and the verses that mention them. By Stephen Smith, OpenBible.info. Licensed CC BY 4.0. Used unmodified except for choosing each place’s most confident identification.",
                listOf("openbible.info/geo" to "https://www.openbible.info/geo/", "CC BY 4.0" to "https://creativecommons.org/licenses/by/4.0/"))
            CellDivider(palette)
            credit("OpenStreetMap",
                "A few site coordinates in the OpenBible data come from OpenStreetMap. © OpenStreetMap contributors, available under the Open Database License.",
                listOf("openstreetmap.org/copyright" to "https://www.openstreetmap.org/copyright"))
        }
        GroupedSection(palette, header = "Base map") {
            credit("Natural Earth",
                "Coastlines, land, lakes and rivers at 1:10 million, clipped to the lands of the Bible and simplified. Public domain. Modern reservoirs and canals are left out.",
                listOf("naturalearthdata.com" to "https://www.naturalearthdata.com"))
        }
        GroupedSection(palette, header = "Timeline and charts") {
            credit("Written for Scripture Alone",
                "Eras, events, and the kings, journeys, tribes and feasts charts were compiled for this app from the biblical text and standard chronologies. Many dates are approximate and some are disputed; the app notes where.",
                emptyList())
            CellDivider(palette)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (line in CHRONOLOGIES) Text(line, color = palette.ink, fontSize = StudyStyle.footnote)
            }
        }
        GroupedSection(palette) {
            Cell(palette) {
                Text(
                    "Scripture Alone is free software under the GNU AGPL. The full list of sources, versions and checksums is in docs/context-sources.md in the source code.",
                    color = palette.secondary, fontSize = StudyStyle.footnote,
                )
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

private val CHRONOLOGIES = listOf(
    "Eugene H. Merrill, Kingdom of Priests: A History of Old Testament Israel, 2nd ed. (2008) — patriarchs to the return, early exodus date.",
    "Edwin R. Thiele, The Mysterious Numbers of the Hebrew Kings, 3rd ed. (1983) — reigns of the kings of Israel and Judah.",
    "Kenneth A. Kitchen, On the Reliability of the Old Testament (2003) — the case for a late exodus date.",
    "Jack Finegan, Handbook of Biblical Chronology, rev. ed. (1998).",
    "Harold W. Hoehner, Chronological Aspects of the Life of Christ (1977).",
    "F. F. Bruce, Paul: Apostle of the Heart Set Free (1977) — Paul’s journeys and letters.",
)

@Suppress("unused")
private val labelKinds = MapLabel.Kind.entries
