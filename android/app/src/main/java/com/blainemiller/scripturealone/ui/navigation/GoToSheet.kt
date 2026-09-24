package com.blainemiller.scripturealone.ui.navigation

import com.blainemiller.scripturealone.ui.reader.CappedFontScale
import kotlin.math.pow
import androidx.compose.ui.semantics.Role
import com.blainemiller.scripturealone.ui.reader.takesTaps
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.data.Canon
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookGroup
import com.blainemiller.scripturealone.data.canon.BookID
import com.blainemiller.scripturealone.data.reference.ReferenceParser
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.blainemiller.scripturealone.data.search.SearchEmphasis
import com.blainemiller.scripturealone.data.search.SearchHit
import com.blainemiller.scripturealone.data.search.VerseSearch
import com.blainemiller.scripturealone.data.topics.CrisisSupport
import com.blainemiller.scripturealone.data.topics.LifeThemeCatalog
import com.blainemiller.scripturealone.data.topics.TopicsLibrary
import com.blainemiller.scripturealone.text.AppLanguage
import com.blainemiller.scripturealone.ui.study.loaded
import com.blainemiller.scripturealone.text.countedString
import com.blainemiller.scripturealone.ui.reader.ReaderPalette
import com.blainemiller.scripturealone.ui.reader.ReaderViewModel
import com.blainemiller.scripturealone.ui.reader.SheetColors
import com.blainemiller.scripturealone.ui.reader.glass
import kotlinx.coroutines.delay

/**
 * The Go To sheet — `ScriptureAlone/Navigation/PassagePicker.swift`. One field takes a reference
 * ("jn 3 16", "rom 8:28-39") or words to search; empty, the sheet shows Recent chapters, Recent
 * Searches, Topics and the two testaments' books, and a book opens its chapter grid. Words that name
 * a topic ("anxious") offer it above the verses; the Topics directory, a theme and a Nave's topic
 * open inside the sheet (`TopicsScreens.kt`), with Back returning through them.
 *
 * Laid out as the iOS sheet is — the same sections in the same order, the same grid minimums (86 pt
 * book tiles, 52 pt chapter cells, 8 pt gaps), the same type scale (headline 17, callout 16, caption2
 * 11) — and coloured from the reader's theme rather than Material's, with glass pills for Close,
 * Back and the field.
 */
@Composable
fun GoToSheet(
    model: ReaderViewModel,
    palette: ReaderPalette,
    /** Words a search link, shortcut or App Action asked for — `AppCommandCenter.searchQuery`. */
    initialQuery: String = "",
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    /** The book whose chapter grid is open — iOS's `NavigationStack` path, one level deep. */
    var book by rememberSaveable { mutableStateOf<Int?>(null) }
    /** The topics opened, deepest last — the rest of iOS's path ([TopicRoute.key]s). */
    var topicStack by rememberSaveable { mutableStateOf(listOf<String>()) }
    val topicRoute = topicStack.lastOrNull()?.let(TopicRoute::of)
    val language = AppLanguage.current
    val topicCatalog = loaded(language) { TopicsLibrary.catalog(it) }
    val topicIndex = loaded(language) { TopicsLibrary.visibleIndex(it) }
    var results by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    /** The query [results] answer, so "No Results" is never shown for a search still running. */
    var answered by remember { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current

    val passage = remember(query) { ReferenceParser.parse(query) }
    val suggested = remember(query) {
        if (query.isEmpty() || query.any { it.isDigit() }) emptyList() else ReferenceParser.books(query).take(6)
    }
    /** Words, not a reference: what iOS sends to the search. */
    val isWordSearch = VerseSearch.isLongEnough(query) && passage == null
    /** Life themes the words speak to — "anxious" is Anxiety — and a Nave's topic named exactly them. */
    /** Someone in crisis gets the crisis card, not whatever topic their words happen to match ("want to die" is also Death & Dying). */
    val crisis = remember(query) { passage == null && CrisisSupport.isCrisis(query) }
    val matchedThemes = remember(query, topicCatalog) { if (passage != null || crisis) emptyList() else topicCatalog?.search(query).orEmpty() }
    val matchedTopic = remember(query, topicIndex) { if (passage != null || crisis || query.length < 3) null else topicIndex?.topic(query) }

    // A reference navigates; anything else searches the text — after 180 ms, as iOS waits, so a
    // search isn't run for every keystroke of a word still being typed.
    LaunchedEffect(query, model.translationId) {
        val text = query
        if (!VerseSearch.isLongEnough(text) || passage != null || !model.isSearchable) {
            results = emptyList()
            answered = text
            return@LaunchedEffect
        }
        delay(180)
        val hits = model.search(text)
        results = hits
        answered = text
    }

    fun dismiss() {
        keyboard?.hide()
        onDismiss()
    }

    fun openResult(hit: SearchHit) {
        model.rememberSearch(query)
        model.go(hit.kjv)   // the KJV key; the reader lands on its own verse
        dismiss()
    }

    fun submit() {
        val p = passage
        if (p != null) {
            model.go(p)
            dismiss()
        } else {
            results.firstOrNull()?.let(::openResult)
        }
    }

    fun openTopic(route: TopicRoute) {
        keyboard?.hide()
        topicStack = topicStack + route.key
    }

    /** A passage chosen in a topic: KJV keys, so the reader lands on its own verse. */
    fun openPassage(range: com.blainemiller.scripturealone.data.VerseRange) {
        model.go(range.start)
        dismiss()
    }

    BackHandler {
        when {
            book != null -> book = null
            topicStack.isNotEmpty() -> topicStack = topicStack.dropLast(1)
            else -> dismiss()
        }
    }

    val surface = SheetColors.surface(palette)
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(surface)
            // Swallows taps so they don't fall through to the scrim behind the sheet.
            .takesTaps(),
    ) {
        val open = book?.let { BookID.of(it) }
        val topicsTitle = stringResource(R.string.nav_topics)
        Header(
            title = open?.displayName ?: topicRoute?.let { topicTitle(it, topicCatalog, topicIndex, topicsTitle) } ?: stringResource(R.string.nav_title),
            palette = palette,
            surface = surface,
            back = open != null || topicRoute != null,
            onLeading = {
                when {
                    open != null -> book = null
                    topicRoute != null -> topicStack = topicStack.dropLast(1)
                    else -> dismiss()
                }
            },
        )
        if (open != null) {
            ChapterGrid(open, model.location, palette) { chapter ->
                model.show(chapter)
                dismiss()
            }
        } else if (topicRoute != null) {
            val catalog = topicCatalog
            when (topicRoute) {
                TopicRoute.Directory -> if (catalog != null) TopicsDirectory(catalog, topicIndex, palette, surface, ::openTopic)
                is TopicRoute.Theme -> catalog?.theme(topicRoute.id)?.let { theme ->
                    LifeThemeScreen(theme, topicIndex, model, palette, ::openTopic, ::openPassage)
                }
                is TopicRoute.Index -> {
                    val index = topicIndex
                    val topic = index?.topic(topicRoute.id)
                    if (index != null && topic != null) IndexTopicScreen(topic, index, model, palette, ::openTopic, ::openPassage)
                }
            }
        } else {
            SearchField(query, palette, surface, onChange = { query = it }, onSubmit = ::submit)
            val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            LazyVerticalGrid(
                columns = GridCells.Adaptive(86.dp * gridGrowth()),
                modifier = Modifier.fillMaxSize().imePadding(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = bottom + 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (query.isEmpty()) {
                    recentSection(model, palette) { chapter ->
                        model.show(chapter)
                        dismiss()
                    }
                    recentSearchesSection(model, palette) { query = it }
                    topicCatalog?.let { topicsSection(it, palette, ::openTopic) }
                    booksSection(R.string.nav_old_testament, BookID.entries.filter { !it.isNewTestament }, palette) {
                        keyboard?.hide()
                        book = it.number
                    }
                    booksSection(R.string.nav_new_testament, BookID.entries.filter { it.isNewTestament }, palette) {
                        keyboard?.hide()
                        book = it.number
                    }
                } else {
                    passage?.let { p ->
                        full("goto") { GoToCard(p.clamped.display, palette, onClick = ::submit) }
                    }
                    if (crisis) {
                        full("crisis") { CrisisCard(palette) { openTopic(TopicRoute.Theme("hope")) } }
                    }
                    for (theme in matchedThemes) {
                        full("topic-${theme.id}") {
                            TopicCard(theme.localizedName, theme.localizedDescription, palette) { openTopic(TopicRoute.Theme(theme.id)) }
                        }
                    }
                    matchedTopic?.let { topic ->
                        full("nave-${topic.id}") {
                            TopicCard(topic.name, topicIndex?.name.orEmpty(), palette) { openTopic(TopicRoute.Index(topic.id)) }
                        }
                    }
                    if (suggested.isNotEmpty()) {
                        items(suggested, key = { "suggest-${it.number}" }) { b ->
                            BookTile(b, palette) {
                                keyboard?.hide()
                                book = b.number
                            }
                        }
                    }
                    when {
                        isWordSearch && !model.isSearchable -> full("unsearchable") {
                            UnsearchableNotice(model.translationAbbreviation, palette) { id -> model.selectTranslation(id) }
                        }
                        results.isNotEmpty() -> resultsSection(results, query, palette, ::openResult)
                        isWordSearch && !crisis && suggested.isEmpty() && matchedThemes.isEmpty() && matchedTopic == null && answered == query -> full("empty") {
                            NoResults(query, palette)
                        }
                    }
                }
            }
        }
    }
}

// Chrome

@Composable
private fun Header(title: String, palette: ReaderPalette, surface: Color, back: Boolean, onLeading: () -> Unit) {
    val backLabel = stringResource(R.string.common_back)
    Box(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp)) {
        if (back) {
            Box(
                Modifier.align(Alignment.CenterStart).size(44.dp)
                    .glass(palette, CircleShape, surface, lifted = true)
                    .clickable(role = Role.Button, onClick = onLeading)
                    .semantics { contentDescription = backLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.ChevronLeft, null, tint = palette.ink, modifier = Modifier.size(28.dp))
            }
        } else {
            Box(
                Modifier.align(Alignment.CenterStart).height(44.dp)
                    .glass(palette, CircleShape, surface, lifted = true)
                    .clickable(role = Role.Button, onClick = onLeading)
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.common_close), color = palette.ink, fontSize = 17.sp)
            }
        }
        Text(
            title, color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 96.dp),
        )
    }
}

/**
 * The sheet's glass search field. Go To's takes focus as the sheet appears, so typing starts at once,
 * as iOS's does; the Topics directory's waits to be tapped, as `.searchable` does.
 */
@Composable
internal fun SearchField(
    query: String,
    palette: ReaderPalette,
    surface: Color,
    placeholder: String = stringResource(R.string.nav_field_placeholder),
    autoFocus: Boolean = true,
    onChange: (String) -> Unit,
    onSubmit: () -> Unit,
) = CappedFontScale {
    val focus = remember { FocusRequester() }
    val fieldLabel = if (autoFocus) stringResource(R.string.nav_field_label) else placeholder
    val clearLabel = stringResource(R.string.common_clear)
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 6.dp)
            .fillMaxWidth()
            .height(50.dp)
            .glass(palette, CircleShape, surface, lifted = true)
            .padding(start = 16.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = palette.secondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = palette.ink, fontSize = 17.sp),
            cursorBrush = SolidColor(palette.accent),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }),
            modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = fieldLabel },
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            placeholder, color = palette.secondary.copy(alpha = 0.8f),
                            fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    field()
                }
            },
        )
        if (query.isNotEmpty()) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button) { onChange("") }.semantics { contentDescription = clearLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Cancel, null, tint = palette.secondary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// Sections

private fun LazyGridScope.full(key: String, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

private fun LazyGridScope.sectionTitle(key: String, @StringRes title: Int, palette: ReaderPalette, trailing: (@Composable () -> Unit)? = null) =
    full(key) {
        // 22 between sections and 10 under the title, as the iOS VStack spacings — less the grid's 8.
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(title), color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
    }

private fun LazyGridScope.recentSection(model: ReaderViewModel, palette: ReaderPalette, onPick: (ChapterRef) -> Unit) {
    if (model.recent.isEmpty()) return
    sectionTitle("recent-title", R.string.nav_recent, palette)
    full("recent") {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (chapter in model.recent) {
                Box(
                    Modifier.height(36.dp).clip(CircleShape).background(SheetColors.buttonFill(palette))
                        .clickable(role = Role.Button) { onPick(chapter) }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(Canon.display(chapter), color = palette.accent, fontSize = 17.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun LazyGridScope.recentSearchesSection(model: ReaderViewModel, palette: ReaderPalette, onPick: (String) -> Unit) {
    if (model.recentSearches.isEmpty()) return
    sectionTitle("searches-title", R.string.nav_recent_searches, palette) {
        Text(
            stringResource(R.string.common_clear), color = palette.accent, fontSize = 15.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { model.clearRecentSearches() }.padding(4.dp),
        )
    }
    full("searches") {
        Column {
            for (term in model.recentSearches) key(term) {
                // Long press offers Remove — the iOS context menu.
                var menu by remember { mutableStateOf(false) }
                val searchAgain = stringResource(R.string.nav_search_again, term)
                Box {
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(role = Role.Button, onLongClickLabel = stringResource(R.string.nav_show_options), onLongClick = { menu = true }) { onPick(term) }
                            .padding(vertical = 9.dp)
                            .semantics { contentDescription = searchAgain },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.History, null, tint = palette.secondary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(term, color = palette.ink, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_remove), color = palette.red, fontSize = 15.sp) },
                            onClick = {
                                menu = false
                                model.forgetSearch(term)
                            },
                        )
                    }
                }
                Separator(palette)
            }
        }
    }
}

/** A few of the life themes to start from, and See All for the whole directory — iOS's `topicsSection`. */
private fun LazyGridScope.topicsSection(catalog: LifeThemeCatalog, palette: ReaderPalette, onOpen: (TopicRoute) -> Unit) {
    val featured = TopicsLibrary.FEATURED.mapNotNull(catalog::theme)
    if (featured.isEmpty()) return
    sectionTitle("topics-title", R.string.nav_topics, palette) {
        Text(
            stringResource(R.string.nav_topics_see_all), color = palette.accent, fontSize = 15.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { onOpen(TopicRoute.Directory) }.padding(4.dp),
        )
    }
    full("topics") {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (theme in featured) {
                Box(
                    Modifier.height(36.dp).clip(CircleShape).background(SheetColors.buttonFill(palette))
                        .clickable(role = Role.Button) { onOpen(TopicRoute.Theme(theme.id)) }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(theme.localizedName, color = palette.accent, fontSize = 17.sp, maxLines = 1)
                }
            }
        }
    }
}

private fun LazyGridScope.booksSection(@StringRes title: Int, books: List<BookID>, palette: ReaderPalette, onPick: (BookID) -> Unit) {
    sectionTitle("title-$title", title, palette)
    items(books, key = { "book-${it.number}" }) { book -> BookTile(book, palette) { onPick(book) } }
}

private fun LazyGridScope.resultsSection(results: List<SearchHit>, query: String, palette: ReaderPalette, onOpen: (SearchHit) -> Unit) {
    val count = results.size
    full("results-title") {
        Text(
            if (count >= VerseSearch.DEFAULT_LIMIT) stringResource(R.string.nav_results_capped, VerseSearch.DEFAULT_LIMIT)
            else countedString(R.string.nav_results_one, R.string.nav_results_other, count, count),
            color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    items(results, key = { "hit-${it.ref.key}" }, span = { GridItemSpan(maxLineSpan) }) { hit ->
        Column {
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(hit) }.padding(vertical = 10.dp)) {
                Text(display(hit.ref), color = palette.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(emphasized(hit.text, query), color = palette.ink, fontSize = 16.sp, lineHeight = 21.sp)
            }
            Separator(palette)
        }
    }
}

// Pieces

/** `VerseRef.display` on iOS — "John 3:16", and "Jude 1:3" too: a verse always shows its chapter. */
private fun display(ref: VerseRef): String = "${Canon.book(ref.book).displayName} ${ref.chapter}:${ref.verse}"

private fun emphasized(text: String, query: String) = buildAnnotatedString {
    append(text)
    for (range in SearchEmphasis.ranges(text, query)) {
        addStyle(SpanStyle(fontWeight = FontWeight.Bold), range.first, range.last + 1)
    }
}

@Composable
private fun Separator(palette: ReaderPalette) {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(SheetColors.separator(palette)))
}

@Composable
private fun GoToCard(display: String, palette: ReaderPalette, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(palette.accent.copy(alpha = 0.12f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.nav_go_to_card), color = palette.secondary, fontSize = 12.sp)
            Spacer(Modifier.height(2.dp))
            Text(display, color = palette.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardReturn, stringResource(R.string.nav_go), tint = palette.secondary, modifier = Modifier.size(22.dp))
    }
}

/**
 * What a words search says in a translation that can't be searched — only a sealed package built
 * without a search index (the shipped ASV has one). Searching another translation behind its name
 * would show the reader words it doesn't have, so it says so and offers the switch as the reader's
 * own choice — iOS's `TranslationPackageError.notSearchable`.
 */
@Composable
private fun UnsearchableNotice(translation: String, palette: ReaderPalette, onSwitch: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(palette.accent.copy(alpha = 0.10f)).padding(16.dp),
    ) {
        Text(stringResource(R.string.nav_unsearchable_title, translation), color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.nav_unsearchable_message),
            color = palette.secondary, fontSize = 15.sp, lineHeight = 20.sp,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (id in listOf("BSB", "KJV").filter { it != translation }) {
                Box(
                    Modifier.height(36.dp).clip(CircleShape).background(SheetColors.buttonFill(palette))
                        .clickable(role = Role.Button) { onSwitch(id) }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.nav_switch_to, id), color = palette.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** `ContentUnavailableView.search(text:)`. */
@Composable
private fun NoResults(query: String, palette: ReaderPalette) {
    Column(Modifier.fillMaxWidth().padding(top = 72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Search, null, tint = palette.secondary, modifier = Modifier.size(52.dp))
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.nav_no_results_title, query), color = palette.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.nav_no_results_message), color = palette.secondary, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

/**
 * A book: its abbreviation over its name, on a wash of its group's colour with a bar of it at the
 * leading edge — `BookTile` in PassagePicker.swift, with the same system colours per group.
 */
@Composable
private fun BookTile(book: BookID, palette: ReaderPalette, onClick: () -> Unit) {
    val tint = groupTint(book.group, palette.isDark)
    Box(
        Modifier.fillMaxWidth().heightIn(min = 54.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.13f).compositeOver(SheetColors.surface(palette)))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = book.displayName },
    ) {
        // matchParentSize, not fillMaxHeight: the tile's height comes from its text, and a child
        // can't fill a height its parent is still measuring.
        Box(Modifier.matchParentSize().padding(vertical = 10.dp)) {
            Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(tint))
        }
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // SF's headline and caption2 set tighter than Roboto's default line heights; these match
            // the iOS tile's 54 pt.
            // Both lines shrink rather than wrap or clip — at a large font size "1 Sam" would otherwise
            // break after the "1" and lose the book.
            ShrinkingText(book.abbreviation, palette.ink, 17.sp, lineHeight = 21.sp, weight = FontWeight.SemiBold)
            ShrinkingText(book.displayName, palette.secondary, 11.sp)
        }
    }
}

/** One line that scales down to fit rather than truncating — SwiftUI's `minimumScaleFactor(0.7)`. */
@Composable
private fun ShrinkingText(text: String, color: Color, size: TextUnit, lineHeight: TextUnit = 13.sp, weight: FontWeight? = null) {
    var scale by remember(text) { mutableStateOf(1f) }
    Text(
        text, color = color, fontSize = size * scale, lineHeight = lineHeight * scale, fontWeight = weight, maxLines = 1, softWrap = false,
        onTextLayout = { if (it.didOverflowWidth && scale > 0.7f) scale -= 0.05f },
    )
}

/**
 * How much wider grid cells get under a larger system font size, so the tiles' text still fits:
 * none at 1×, three columns of books instead of four around 1.3×, two at 2× — the way iOS's grids
 * re-flow under Dynamic Type.
 */
@Composable
private fun gridGrowth(): Float = LocalDensity.current.fontScale.coerceAtLeast(1f).pow(0.75f)

/** The iOS system colours `BookTile.tint` uses, in their light and dark variants. */
private fun groupTint(group: BookGroup, dark: Boolean): Color {
    val (light, night) = when (group) {
        BookGroup.LAW -> 0xA2845E to 0xAC8E68            // .brown
        BookGroup.HISTORY -> 0xFF9500 to 0xFF9F0A        // .orange
        BookGroup.WISDOM -> 0xAF52DE to 0xBF5AF2         // .purple
        BookGroup.MAJOR_PROPHETS -> 0xFF3B30 to 0xFF453A // .red
        BookGroup.MINOR_PROPHETS -> 0xFF2D55 to 0xFF375F // .pink
        BookGroup.GOSPELS -> 0x007AFF to 0x0A84FF        // .blue
        BookGroup.PAUL -> 0x30B0C7 to 0x40C8E0           // .teal
        BookGroup.GENERAL -> 0x34C759 to 0x30D158        // .green
        BookGroup.PROPHECY -> 0x5856D6 to 0x5E5CE6       // .indigo
    }
    return Color(0xFF000000 or (if (dark) night else light).toLong())
}

/** A book's chapters — `ChapterGrid` in PassagePicker.swift. The chapter being read is marked. */
@Composable
private fun ChapterGrid(book: BookID, current: ChapterRef, palette: ReaderPalette, onPick: (ChapterRef) -> Unit) {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(52.dp * gridGrowth()),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottom + 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items((1..book.chapterCount).toList(), key = { it }) { chapter ->
            val ref = ChapterRef(book.number, chapter)
            val here = ref == current
            val label = stringResource(R.string.nav_chapter_label, book.displayName, chapter)
            Box(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (here) palette.accent.copy(alpha = 0.25f) else SheetColors.tertiaryFill(palette))
                    .clickable(role = Role.Button) { onPick(ref) }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$chapter", color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                    style = TextStyle(fontFamily = FontFamily.SansSerif, fontFeatureSettings = "tnum"),
                )
            }
        }
    }
}
