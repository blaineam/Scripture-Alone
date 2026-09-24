package com.blainemiller.scripturealone.ui.navigation

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SubdirectoryArrowRight
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.data.ChapterVerse
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.rights.TranslationRights
import com.blainemiller.scripturealone.data.topics.CrisisSupport
import com.blainemiller.scripturealone.data.topics.IndexEntry
import com.blainemiller.scripturealone.data.topics.IndexTopic
import com.blainemiller.scripturealone.data.topics.LifeTheme
import com.blainemiller.scripturealone.data.topics.LifeThemeCatalog
import com.blainemiller.scripturealone.data.topics.TopicSearch
import com.blainemiller.scripturealone.data.topics.TopicalIndex
import com.blainemiller.scripturealone.data.topics.TopicsLibrary
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.blainemiller.scripturealone.ui.reader.ReaderViewModel
import com.blainemiller.scripturealone.ui.reader.SheetColors
import com.blainemiller.scripturealone.ui.study.StudyStyle
import com.blainemiller.scripturealone.ui.study.serifStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Where the Go To sheet can go besides a book's chapters — `TopicsRoute` in `TopicsDirectory.swift`:
 * the Topics directory, a life theme, a topic in Nave's Topical Bible. Kept as a string so the sheet's
 * stack survives a configuration change.
 */
sealed interface TopicRoute {
    val key: String

    data object Directory : TopicRoute { override val key = "directory" }
    data class Theme(val id: String) : TopicRoute { override val key = "theme:$id" }
    data class Index(val id: Int) : TopicRoute { override val key = "index:$id" }

    companion object {
        fun of(key: String): TopicRoute? = when {
            key == "directory" -> Directory
            key.startsWith("theme:") -> Theme(key.removePrefix("theme:"))
            key.startsWith("index:") -> key.removePrefix("index:").toIntOrNull()?.let(::Index)
            else -> null
        }
    }
}

/** A row of an inset-grouped list drawn one lazy item at a time: rounded only where its group ends. */
private fun Modifier.groupedRow(palette: ReaderPalette, first: Boolean, last: Boolean): Modifier =
    padding(horizontal = 16.dp)
        .clip(RoundedCornerShape(topStart = if (first) 12.dp else 0.dp, topEnd = if (first) 12.dp else 0.dp,
                                 bottomStart = if (last) 12.dp else 0.dp, bottomEnd = if (last) 12.dp else 0.dp))
        .background(StudyStyle.cell(palette))

private fun LazyListScope.groupHeader(key: String, title: String, palette: ReaderPalette) = item(key) {
    Text(
        title.uppercase(), color = palette.secondary, fontSize = StudyStyle.footnote, letterSpacing = 0.3.sp,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 22.dp, bottom = 7.dp).semantics { heading() },
    )
}

@Composable
private fun RowDivider(palette: ReaderPalette, show: Boolean) {
    if (show) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(StudyStyle.separator(palette)))
}

/** A tappable row with a trailing chevron. */
@Composable
private fun NavigationRow(palette: ReaderPalette, onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { content() }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Rounded.ChevronRight, null, tint = StudyStyle.tertiary(palette), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ThemeLabel(theme: LifeTheme, palette: ReaderPalette) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(theme.localizedName, color = palette.ink, fontSize = StudyStyle.body, fontWeight = FontWeight.Medium)
        Text(theme.localizedDescription, color = palette.secondary, fontSize = StudyStyle.subheadline, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun LazyListScope.themeRows(prefix: String, themes: List<LifeTheme>, palette: ReaderPalette, onRoute: (TopicRoute) -> Unit) {
    themes.forEachIndexed { i, theme ->
        item("$prefix-${theme.id}") {
            Column(Modifier.groupedRow(palette, i == 0, i == themes.lastIndex)) {
                NavigationRow(palette, { onRoute(TopicRoute.Theme(theme.id)) }) { ThemeLabel(theme, palette) }
                RowDivider(palette, i < themes.lastIndex)
            }
        }
    }
}

private fun LazyListScope.indexRows(prefix: String, topics: List<IndexTopic>, palette: ReaderPalette, onRoute: (TopicRoute) -> Unit) {
    topics.forEachIndexed { i, topic ->
        item("$prefix-${topic.id}") {
            Column(Modifier.groupedRow(palette, i == 0, i == topics.lastIndex)) {
                NavigationRow(palette, { onRoute(TopicRoute.Index(topic.id)) }) {
                    Text(topic.name, color = palette.ink, fontSize = StudyStyle.body)
                }
                RowDivider(palette, i < topics.lastIndex)
            }
        }
    }
}

/**
 * Life themes, grouped, then — in English — Nave's Topical Bible from A to Z, with a search over both:
 * theme names, the words people use for them ("worried", "burned out"), and Nave's topics.
 * `TopicsDirectoryView` in `TopicsDirectory.swift`.
 */
@Composable
internal fun TopicsDirectory(
    catalog: LifeThemeCatalog,
    index: TopicalIndex?,
    palette: ReaderPalette,
    surface: Color,
    onRoute: (TopicRoute) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val searching = TopicSearch.normalize(query).isNotEmpty()
    val lifeTopics = stringResource(R.string.topics_life_topics)
    Column(Modifier.fillMaxSize()) {
        SearchField(
            query, palette, surface, placeholder = stringResource(R.string.topics_search_placeholder), autoFocus = false,
            onChange = { query = it }, onSubmit = {},
        )
        LazyColumn(
            Modifier.fillMaxSize().imePadding().background(StudyStyle.groupedBackground(palette)),
            state = list,
            contentPadding = PaddingValues(bottom = bottom + 24.dp),
        ) {
            if (!searching) {
                for (group in catalog.groups) {
                    groupHeader("group-${group.id}", group.localizedName, palette)
                    themeRows("g", catalog.themes(group), palette, onRoute)
                }
                if (index != null) {
                    val letters = index.lettered
                    groupHeader("nave", index.name, palette)
                    item("nave-intro") {
                        Column(Modifier.groupedRow(palette, first = true, last = true).padding(16.dp)) {
                            Text(stringResource(R.string.topics_nave_intro), color = palette.secondary, fontSize = StudyStyle.subheadline, lineHeight = 20.sp)
                            Spacer(Modifier.height(10.dp))
                            // Android lists have no section index; a row of letters stands in for it.
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for ((letter, _) in letters) {
                                    Box(
                                        Modifier.size(34.dp).clip(CircleShape).background(SheetColors.buttonFill(palette))
                                            .clickable(role = Role.Button) {
                                                scope.launch { list.animateScrollToItem(firstItemOf(letter, catalog, letters)) }
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(letter, color = palette.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                    }
                    for ((letter, topics) in letters) {
                        groupHeader("letter-$letter", letter, palette)
                        indexRows("n", topics, palette, onRoute)
                    }
                }
            } else {
                val crisis = CrisisSupport.isCrisis(query)
                // The crisis card stands alone: "want to die" would otherwise also list Death & Dying.
                val themes = if (crisis) emptyList() else catalog.search(query, limit = catalog.themes.size)
                val topics = if (crisis) emptyList() else index?.search(query, limit = 60).orEmpty()
                if (crisis) {
                    item("crisis") {
                        Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                            CrisisCard(palette) { onRoute(TopicRoute.Theme("hope")) }
                        }
                    }
                }
                if (themes.isEmpty() && topics.isEmpty() && !crisis) {
                    item("empty") { NoTopics(query, palette) }
                }
                if (themes.isNotEmpty()) {
                    groupHeader("found-themes", lifeTopics, palette)
                    themeRows("s", themes, palette, onRoute)
                }
                if (index != null && topics.isNotEmpty()) {
                    groupHeader("found-nave", index.name, palette)
                    indexRows("s", topics, palette, onRoute)
                }
            }
        }
    }
}

/**
 * The position of a letter's heading in the unsearched directory: a heading and a row per theme for
 * each group, the Nave's heading and introduction, then a heading and a row per topic for each letter.
 */
private fun firstItemOf(letter: String, catalog: LifeThemeCatalog, letters: List<Pair<String, List<IndexTopic>>>): Int {
    var position = catalog.groups.sumOf { 1 + catalog.themes(it).size } + 2
    for ((l, topics) in letters) {
        if (l == letter) return position
        position += 1 + topics.size
    }
    return position
}

@Composable
private fun NoTopics(query: String, palette: ReaderPalette) {
    Column(Modifier.fillMaxWidth().padding(top = 72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Search, null, tint = palette.secondary, modifier = Modifier.size(52.dp))
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.nav_no_results_title, query), color = palette.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.nav_no_results_message), color = palette.secondary, fontSize = 15.sp)
    }
}

/**
 * "Topic / Anxiety & Worry" — offered above the verses in Go To when the words name a topic.
 */
@Composable
internal fun TopicCard(title: String, detail: String, palette: ReaderPalette, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(palette.accent.copy(alpha = 0.12f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Rounded.MenuBook, null, tint = palette.accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.nav_topic_card), color = palette.secondary, fontSize = 12.sp)
            Spacer(Modifier.height(2.dp))
            Text(title, color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (detail.isNotEmpty()) {
                Text(detail, color = palette.secondary, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = StudyStyle.tertiary(palette), modifier = Modifier.size(22.dp))
    }
}

/**
 * A life theme: each passage in the translation being read, one tap from the reader, with a long
 * press for Favorites, Copy and Share Text — the last two asking the translation's terms first, as a
 * selection does. `LifeThemeView` in `TopicsDirectory.swift`.
 */
@Composable
internal fun LifeThemeScreen(
    theme: LifeTheme,
    index: TopicalIndex?,
    model: ReaderViewModel,
    palette: ReaderPalette,
    onRoute: (TopicRoute) -> Unit,
    onOpen: (VerseRange) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val translation = model.translationId
    // Each passage as it arrives, so a slow online fetch doesn't hold up the ones already on the device.
    val loaded by produceState(emptyMap<VerseRange, List<ChapterVerse>>() to false, theme.id, translation) {
        value = emptyMap<VerseRange, List<ChapterVerse>>() to false
        val found = mutableMapOf<VerseRange, List<ChapterVerse>>()
        for (range in theme.passages) {
            found[range] = withContext(Dispatchers.IO) { runCatching { TopicsLibrary.passage(context, translation, range) }.getOrDefault(emptyList()) }
            value = found.toMap() to false
        }
        value = found.toMap() to true
    }
    val (texts, done) = loaded
    val related = remember(theme.id, index) { index?.let { i -> theme.naveTopics.mapNotNull { i.topic(it) } }.orEmpty() }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        Modifier.fillMaxSize().background(StudyStyle.groupedBackground(palette)),
        contentPadding = PaddingValues(top = 8.dp, bottom = bottom + 24.dp),
    ) {
        item("description") {
            Text(
                theme.localizedDescription, color = palette.secondary, fontSize = StudyStyle.callout, lineHeight = 21.sp,
                modifier = Modifier.groupedRow(palette, first = true, last = true).fillMaxWidth().padding(16.dp),
            )
            Spacer(Modifier.height(20.dp))
        }
        theme.passages.forEachIndexed { i, range ->
            item("passage-${range.storageString}") {
                Column(Modifier.groupedRow(palette, i == 0, i == theme.passages.lastIndex)) {
                    PassageRow(range, texts[range], done, model, palette, onOpen)
                    RowDivider(palette, i < theme.passages.lastIndex)
                }
            }
        }
        item("from") {
            Text(
                stringResource(R.string.topics_passages_from, model.translationAbbreviation), color = palette.secondary,
                fontSize = StudyStyle.footnote, modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 7.dp),
            )
        }
        if (index != null && related.isNotEmpty()) {
            groupHeader("related", context.getString(R.string.topics_more_in, index.name), palette)
            indexRows("r", related, palette, onRoute)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PassageRow(
    range: VerseRange,
    verses: List<ChapterVerse>?,
    done: Boolean,
    model: ReaderViewModel,
    palette: ReaderPalette,
    onOpen: (VerseRange) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val favorites by model.userData.favorites.collectAsState()
    val favorite = favorites.any { it.range == range }
    val reference = model.displayRange(range).display
    val openHint = stringResource(R.string.study_xref_open_hint, reference)
    val refused = stringResource(R.string.study_xref_copy_refused)

    fun withQuotation(permission: TranslationRights.Permission, use: (String) -> Unit) {
        scope.launch {
            val quotation = if (model.rights.permits(permission)) model.passageQuotation(range) else ""
            if (quotation.isEmpty()) Toast.makeText(context, refused, Toast.LENGTH_SHORT).show() else use(quotation)
        }
    }

    Box {
        Column(
            Modifier.fillMaxWidth()
                .combinedClickable(role = Role.Button, onLongClickLabel = stringResource(R.string.nav_show_options), onClick = { onOpen(range) }, onLongClick = { menu = true })
                .semantics(mergeDescendants = true) { onClick(openHint) { onOpen(range); true } }
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(reference, color = palette.accent, fontSize = StudyStyle.subheadline, fontWeight = FontWeight.SemiBold)
            when {
                !verses.isNullOrEmpty() -> Text(numbered(verses, palette), style = serifStyle(17f, palette.ink, 24f))
                verses != null || done -> Text(stringResource(R.string.topics_passage_unavailable), color = palette.secondary, fontSize = StudyStyle.callout)
                else -> CircularProgressIndicator(color = palette.secondary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.study_xref_go_to, reference), color = palette.ink) },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = palette.ink) },
                onClick = { menu = false; onOpen(range) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(if (favorite) R.string.reader_remove_favorite else R.string.reader_add_favorite), color = palette.ink) },
                leadingIcon = { Icon(if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, tint = palette.ink) },
                onClick = { menu = false; model.userData.toggleFavorite(listOf(range)) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_copy), color = palette.ink) },
                leadingIcon = { Icon(Icons.Rounded.ContentCopy, null, tint = palette.ink) },
                onClick = {
                    menu = false
                    withQuotation(TranslationRights.Permission.COPY) { clipboard.setText(AnnotatedString(it)) }
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.reader_share_text), color = palette.ink) },
                leadingIcon = { Icon(Icons.Rounded.FormatQuote, null, tint = palette.ink) },
                onClick = {
                    menu = false
                    withQuotation(TranslationRights.Permission.SHARE) { text ->
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                            putExtra(Intent.EXTRA_SUBJECT, reference)
                        }
                        context.startActivity(Intent.createChooser(send, null))
                    }
                },
            )
        }
    }
}

/** The verses run together, each after its number in small raised type — unless there is only one. */
private fun numbered(verses: List<ChapterVerse>, palette: ReaderPalette): AnnotatedString = buildAnnotatedString {
    if (verses.size == 1) {
        append(verses[0].text)
        return@buildAnnotatedString
    }
    verses.forEachIndexed { i, verse ->
        if (i > 0) append(" ")
        withStyle(SpanStyle(fontSize = 11.sp, baselineShift = BaselineShift(0.35f), color = palette.secondary)) {
            append("${verse.ref.verse} ")
        }
        append(verse.text)
    }
}

/**
 * A topic in Nave's Topical Bible: its lines, each with its passages as links, and the topics it
 * sends the reader on to. `IndexTopicView` in `TopicsDirectory.swift`.
 */
@Composable
internal fun IndexTopicScreen(
    topic: IndexTopic,
    index: TopicalIndex,
    model: ReaderViewModel,
    palette: ReaderPalette,
    onRoute: (TopicRoute) -> Unit,
    onOpen: (VerseRange) -> Unit,
) {
    val entries by produceState<List<IndexEntry>?>(null, topic.id) {
        value = withContext(Dispatchers.IO) { runCatching { index.entries(topic) }.getOrDefault(emptyList()) }
    }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val rows = entries
    if (rows == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = palette.secondary, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
        }
        return
    }
    // One row per line with words or passages, and one per topic it points to, as iOS lists them.
    val lines = rows.flatMap { entry ->
        (if (entry.label.isNotEmpty() || entry.passages.isNotEmpty()) listOf(entry to null) else emptyList()) +
            entry.seeAlso.map { entry to it }
    }
    LazyColumn(
        Modifier.fillMaxSize().background(StudyStyle.groupedBackground(palette)),
        contentPadding = PaddingValues(top = 16.dp, bottom = bottom + 24.dp),
    ) {
        lines.forEachIndexed { i, (entry, see) ->
            item("line-$i") {
                Column(Modifier.groupedRow(palette, i == 0, i == lines.lastIndex)) {
                    val indent = if (entry.level == 1) 18.dp else 0.dp
                    if (see == null) {
                        Column(Modifier.fillMaxWidth().padding(start = 16.dp + indent, end = 16.dp, top = 10.dp, bottom = 10.dp),
                               verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (entry.label.isNotEmpty()) {
                                Text(
                                    entry.label, color = palette.ink,
                                    fontSize = if (entry.level == 0) StudyStyle.body else StudyStyle.callout,
                                    fontWeight = if (entry.level == 0) FontWeight.Medium else FontWeight.Normal,
                                )
                            }
                            if (entry.passages.isNotEmpty()) {
                                Text(links(entry.passages, model, palette, onOpen), color = palette.secondary, fontSize = StudyStyle.callout, lineHeight = 21.sp)
                            }
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth().clickable(role = Role.Button) { onRoute(TopicRoute.Index(see.id)) }
                                .padding(start = 16.dp + indent, end = 16.dp, top = 11.dp, bottom = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.SubdirectoryArrowRight, null, tint = palette.accent, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.topics_see, see.name), color = palette.accent, fontSize = StudyStyle.body, modifier = Modifier.weight(1f))
                            Icon(Icons.Rounded.ChevronRight, null, tint = StudyStyle.tertiary(palette), modifier = Modifier.size(20.dp))
                        }
                    }
                    RowDivider(palette, i < lines.lastIndex)
                }
            }
        }
        item("attribution") {
            Text(
                index.attribution, color = palette.secondary, fontSize = StudyStyle.caption2,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
            )
        }
    }
}

/** "Ex 6:16–20; Jos 21:4; 1 Ch 6:2", each a link that opens the reader there. */
private fun links(passages: List<VerseRange>, model: ReaderViewModel, palette: ReaderPalette, onOpen: (VerseRange) -> Unit): AnnotatedString {
    val style = TextLinkStyles(SpanStyle(color = palette.accent))
    return buildAnnotatedString {
        passages.forEachIndexed { i, range ->
            if (i > 0) append("; ")
            withLink(LinkAnnotation.Clickable(range.storageString, style) { onOpen(range) }) {
                append(model.displayRange(range).abbreviatedDisplay)
            }
        }
    }
}

/** The directory's title for a route: "Topics", a theme's name, a Nave's topic's. */
internal fun topicTitle(route: TopicRoute, catalog: LifeThemeCatalog?, index: TopicalIndex?, topics: String): String = when (route) {
    TopicRoute.Directory -> topics
    is TopicRoute.Theme -> catalog?.theme(route.id)?.localizedName ?: topics
    is TopicRoute.Index -> index?.topic(route.id)?.name ?: topics
}

/**
 * Shown above everything else when a search reads as someone thinking of ending their life
 * ([CrisisSupport]): a crisis line for their country to call — or text, where it takes messages —
 * the directory of every other country's lines, and passages for a dark day. `CrisisCard` in
 * `TopicsDirectory.swift`. Call opens the dialer with the number filled in; it never places the call.
 */
@Composable
internal fun CrisisCard(palette: ReaderPalette, onPassagesOfHope: () -> Unit) {
    val context = LocalContext.current
    val helpline = remember { CrisisSupport.helpline(Locale.getDefault().country) }
    val pink = if (palette.isDark) Color(0xFFFF375F) else Color(0xFFFF2D55)
    fun open(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // A tablet with no dialer or messaging app: the number is on the button to read.
        }
    }
    Column(
        Modifier.fillMaxWidth().padding(bottom = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(pink.copy(alpha = 0.1f))
            .padding(16.dp)
            .semantics { isTraversalGroup = true },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Favorite, null, tint = pink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.crisis_title), color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
        }
        Text(stringResource(R.string.crisis_body), color = palette.ink, fontSize = 15.sp, lineHeight = 20.sp)
        if (helpline != null) {
            Text(helpline.name, color = palette.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CrisisButton(
                    stringResource(R.string.crisis_call, helpline.display), Icons.Rounded.Phone,
                    fill = palette.accent, content = if (palette.isDark) Color.Black else Color.White, modifier = Modifier.weight(1f),
                ) { open(Intent(Intent.ACTION_DIAL, Uri.parse(helpline.callUri))) }
                helpline.textUri?.let { sms ->
                    CrisisButton(
                        stringResource(R.string.crisis_text, helpline.display), Icons.AutoMirrored.Rounded.Message,
                        fill = palette.accent.copy(alpha = 0.15f), content = palette.accent, modifier = Modifier.weight(1f),
                    ) { open(Intent(Intent.ACTION_SENDTO, Uri.parse(sms))) }
                }
            }
        }
        CrisisLink(
            stringResource(if (helpline == null) R.string.crisis_find_helpline else R.string.crisis_other_countries),
            Icons.Rounded.Language, palette,
        ) { open(Intent(Intent.ACTION_VIEW, Uri.parse(CrisisSupport.DIRECTORY_URL))) }
        CrisisLink(stringResource(R.string.crisis_passages_of_hope), Icons.Rounded.WbTwilight, palette, onPassagesOfHope)
        Text(stringResource(R.string.crisis_emergency), color = palette.secondary, fontSize = 13.sp)
    }
}

@Composable
private fun CrisisButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    fill: Color,
    content: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(fill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = content, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = content, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CrisisLink(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, palette: ReaderPalette, onClick: () -> Unit) {
    Row(
        Modifier.clickable(role = Role.Button, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = palette.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = palette.accent, fontSize = 15.sp)
    }
}
