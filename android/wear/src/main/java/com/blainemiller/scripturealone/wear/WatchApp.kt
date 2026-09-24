package com.blainemiller.scripturealone.wear

import android.net.Uri
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListAnchorType
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.RadioButton
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.blainemiller.scripturealone.companion.VersePalette
import com.blainemiller.scripturealone.companion.VerseSnapshot
import com.blainemiller.scripturealone.companion.VerseSnapshot.Kind
import com.blainemiller.scripturealone.data.VerseNumbering
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.canon.BookGroup
import com.blainemiller.scripturealone.data.canon.BookID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The watch's screens, as `WatchRoute` on the Apple Watch. */
object Routes {
    const val HOME = "home"
    const val BOOKS = "books"
    const val FAVORITES = "favorites"
    const val NOTES = "notes"
    const val HIGHLIGHTS = "highlights"
    const val TRANSLATIONS = "translations"
    val SCREENS = setOf(BOOKS, FAVORITES, NOTES, HIGHLIGHTS, TRANSLATIONS)

    fun verse(ref: String) = "verse/$ref"
    fun verse(range: VerseRange) = verse(range.storageString)
    fun book(book: BookID) = "book/${book.number}"
    fun chapter(book: Int, chapter: Int, focus: Int = 0) = "chapter/$book/$chapter/$focus"
    fun note(id: String) = "note/${Uri.encode(id)}"
}

/** The app's own accent, until the phone sends the reader's (`WatchAccent`). */
private val DefaultAccent = Color(VersePalette.DARK.accent)
private val LocalAccent = staticCompositionLocalOf { DefaultAccent }

/** The reader's accent colour from the phone, or the app's own. */
private val Accent: Color
    @Composable @ReadOnlyComposable get() = LocalAccent.current
private val Secondary = Color(0xFFB4ABA2)
private val HeartRed = Color(0xFFFF453A)
private val NoteOrange = Color(0xFFFF9F0A)
private val HighlightYellow = Color(0xFFFFD60A)
private val WordsOfChrist = Color(VersePalette.WATCH_RED)

@Composable
fun WatchApp(bible: WatchBible, pendingRoute: MutableStateFlow<String?>) {
    val state by bible.state.collectAsStateWithLifecycle()
    // The phone's accent colour (`WatchLinkKeys.accent`) tints the app; its own until the phone has said.
    val accent = state.accent?.let { Color(0xFF000000 or it.toLong()) } ?: DefaultAccent
    CompositionLocalProvider(LocalAccent provides accent) {
    MaterialTheme(colors = Colors(primary = accent, primaryVariant = accent, onPrimary = Color.Black)) {
        val nav = rememberSwipeDismissableNavController()
        val route by pendingRoute.collectAsStateWithLifecycle()
        LaunchedEffect(route) {
            val target = route ?: return@LaunchedEffect
            nav.popBackStack(Routes.HOME, inclusive = false)
            nav.navigate(target)
            pendingRoute.value = null
        }
        SwipeDismissableNavHost(navController = nav, startDestination = Routes.HOME) {
            composable(Routes.HOME) { HomeScreen(bible, state) { nav.navigate(it) } }
            composable("verse/{ref}") { entry ->
                VerseRange.parse(entry.arguments?.getString("ref").orEmpty())?.let { range ->
                    VerseScreen(bible, state, range) { nav.navigate(it) }
                }
            }
            // Keyed on the language books are named in, which follows the edition being read.
            composable(Routes.BOOKS) { key(state.edition?.language) { BooksScreen { nav.navigate(it) } } }
            composable("book/{book}") { entry ->
                BookID.of(entry.arguments?.getString("book")?.toIntOrNull() ?: 0)?.let { book ->
                    key(state.edition?.language) { ChaptersScreen(book) { nav.navigate(it) } }
                }
            }
            composable("chapter/{book}/{chapter}/{focus}") { entry ->
                val args = entry.arguments
                val book = args?.getString("book")?.toIntOrNull() ?: 0
                val chapter = args?.getString("chapter")?.toIntOrNull() ?: 0
                val focus = args?.getString("focus")?.toIntOrNull() ?: 0
                if (BookID.of(book) != null && chapter >= 1) ChapterScreen(bible, state, book, chapter, focus) { nav.navigate(it) }
            }
            composable(Routes.FAVORITES) { FavoritesScreen(bible, state) { nav.navigate(it) } }
            composable(Routes.NOTES) { NotesScreen(state) { nav.navigate(it) } }
            composable("note/{id}") { entry ->
                NoteScreen(state, entry.arguments?.getString("id").orEmpty()) { nav.navigate(it) }
            }
            composable(Routes.HIGHLIGHTS) { HighlightsScreen(bible, state) { nav.navigate(it) } }
            composable(Routes.TRANSLATIONS) { TranslationsScreen(bible, state) }
        }
    }
    }
}

/** A scrolling screen: time at the top, the crown-driven position indicator, round-screen scaling. */
@Composable
private fun Screen(listState: ScalingLazyListState = rememberScalingLazyListState(initialCenterItemIndex = 0), content: ScalingLazyListScope.() -> Unit) {
    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxWidth(),
            state = listState,
            // The title starts below the time, as a watchOS navigation title does, rather than the
            // first item being centred under it.
            autoCentering = null,
            anchorType = ScalingLazyListAnchorType.ItemStart,
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 30.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

@Composable
private fun Title(text: String, color: Color = MaterialTheme.colors.onBackground) {
    ListHeader {
        Text(
            text, color = color, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
private fun RowChip(
    label: String, icon: Int?, tint: Color = Accent, secondary: String? = null, trailing: String? = null, onClick: () -> Unit,
) {
    Chip(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ChipDefaults.secondaryChipColors(),
        icon = icon?.let { { Icon(painterResource(it), contentDescription = null, tint = tint, modifier = Modifier.size(ChipDefaults.IconSize)) } },
        secondaryLabel = secondary?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (trailing != null) Text(trailing, color = Secondary)
            }
        },
    )
}

// MARK: Home — `WatchHomeView`

@Composable
private fun HomeScreen(bible: WatchBible, state: WatchBible.State, go: (String) -> Unit) {
    val verseOfDay by produceState<WatchVerseOfDay?>(null, state.translation, state.editions) {
        value = withContext(Dispatchers.IO) { bible.verseOfDay() }
    }
    val today = verseOfDay
    val favorites = state.snapshot?.items(setOf(Kind.FAVORITE)).orEmpty().size
    val notes = state.snapshot?.items(setOf(Kind.NOTE)).orEmpty().size
    val highlights = state.snapshot?.items(setOf(Kind.HIGHLIGHT)).orEmpty().size
    Screen {
        item { Title(stringResource(R.string.app_name)) }
        today?.range?.let { range ->
            item {
                val description = stringResource(R.string.wear_home_votd_accessibility, today.reference, today.text)
                Card(onClick = { go(Routes.verse(range)) }, modifier = Modifier.semantics { contentDescription = description }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_sun_horizon), null, tint = Secondary, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.verse_of_the_day).uppercase(), style = MaterialTheme.typography.caption3, color = Secondary)
                    }
                    Text(today.reference, style = MaterialTheme.typography.title3, color = Accent)
                    Text(today.text, style = MaterialTheme.typography.body1, color = Color.White, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item { RowChip(stringResource(R.string.wear_favorites), R.drawable.ic_heart, HeartRed, trailing = favorites.takeIf { it > 0 }?.toString()) { go(Routes.FAVORITES) } }
        item { RowChip(stringResource(R.string.wear_notes), R.drawable.ic_note, NoteOrange, trailing = notes.takeIf { it > 0 }?.toString()) { go(Routes.NOTES) } }
        item { RowChip(stringResource(R.string.wear_highlights), R.drawable.ic_highlighter, HighlightYellow, trailing = highlights.takeIf { it > 0 }?.toString()) { go(Routes.HIGHLIGHTS) } }
        item { RowChip(stringResource(R.string.wear_read), R.drawable.ic_book) { go(Routes.BOOKS) } }
        item { RowChip(stringResource(R.string.wear_translation), R.drawable.ic_translate, trailing = state.translation) { go(Routes.TRANSLATIONS) } }
    }
}

// MARK: Verse — `WatchVerseView`

@Composable
private fun VerseScreen(bible: WatchBible, state: WatchBible.State, range: VerseRange, go: (String) -> Unit) {
    val context = LocalContext.current
    // [range] is in KJV keys (a favorite, a note, today's verse); drawn as the Bible numbers it.
    val loadedPassage by produceState<Pair<List<WatchVerse>, VerseRange>?>(null, range, state.translation, state.editions) {
        value = withContext(Dispatchers.IO) { bible.edition(state.translation).let { it.verses(range) to it.nativeRange(range) } }
    }
    val verses = loadedPassage?.first
    val shown = loadedPassage?.second ?: range
    var speaking by remember { mutableStateOf(false) }
    val speaker = remember { VerseSpeaker(context) { speaking = it } }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }
    val notes = state.snapshot.notesOn(range)
    val start = shown.start
    val chapterName = chapterDisplay(start.book, start.chapter)
    val language = state.edition?.language

    Screen {
        item { Title(shown.display, Accent) }
        val loaded = verses
        if (loaded != null && loaded.isEmpty()) {
            item { Text(stringResource(R.string.wear_verse_not_in_edition, state.translation), color = Secondary, textAlign = TextAlign.Center) }
        }
        items(loaded.orEmpty()) { verse -> VerseText(verse, numbered = loaded.orEmpty().size > 1) }
        if (!loaded.isNullOrEmpty()) {
            item {
                Chip(
                    onClick = { if (speaking) speaker.stop() else speaker.speak(loaded.joinToString(" ") { it.text }, language) },
                    enabled = speaker.ready,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.secondaryChipColors(),
                    icon = { Icon(painterResource(if (speaking) R.drawable.ic_stop else R.drawable.ic_speaker), null, modifier = Modifier.size(ChipDefaults.IconSize)) },
                    label = { Text(stringResource(if (speaking) R.string.wear_stop else R.string.wear_speak)) },
                )
            }
        }
        if (notes.isNotEmpty()) {
            item { ListHeader { Text(stringResource(R.string.wear_notes)) } }
            items(notes) { note ->
                RowChip(note.noteTitle ?: note.reference, R.drawable.ic_note, NoteOrange, secondary = note.noteBody) { go(Routes.note(note.id)) }
            }
        }
        item { RowChip(stringResource(R.string.wear_read_chapter, chapterName), R.drawable.ic_book) { go(Routes.chapter(start.book, start.chapter, start.verse)) } }
        item {
            Text(
                state.edition?.name ?: state.translation,
                style = MaterialTheme.typography.caption3, color = Secondary, textAlign = TextAlign.Center,
            )
        }
    }
}

/** A verse with the words of Christ in red and an optional small verse number — `WatchVerseText`. */
@Composable
private fun VerseText(verse: WatchVerse, numbered: Boolean, highlight: Color? = null, onClick: (() -> Unit)? = null) {
    var modifier = Modifier.fillMaxWidth()
    if (highlight != null) modifier = modifier.background(highlight.copy(alpha = 0.28f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp)
    if (onClick != null) modifier = modifier.clickable(onClick = onClick)
    val description = if (numbered) stringResource(R.string.wear_verse_accessibility, verse.ref.verse, verse.text) else verse.text
    Text(
        verseAnnotated(verse, numbered),
        modifier = modifier.semantics { contentDescription = description },
        style = MaterialTheme.typography.body1,
        color = Color.White,
    )
}

/** Scalar offsets (as stored) converted to UTF-16 before they touch the string. */
fun verseAnnotated(verse: WatchVerse, numbered: Boolean): AnnotatedString = buildAnnotatedString {
    if (numbered) {
        withStyle(SpanStyle(color = Secondary, fontWeight = FontWeight.Bold)) { append("${verse.ref.verse} ") }
    }
    val text = verse.text
    val scalars = text.codePointCount(0, text.length)
    var cursor = 0
    for ((start, length) in verse.red.sortedBy { it.first }) {
        val lo = start.coerceIn(cursor, scalars)
        val hi = (start + length).coerceIn(lo, scalars)
        if (lo == hi) continue
        append(text.substring(text.offsetByCodePoints(0, cursor), text.offsetByCodePoints(0, lo)))
        withStyle(SpanStyle(color = WordsOfChrist)) {
            append(text.substring(text.offsetByCodePoints(0, lo), text.offsetByCodePoints(0, hi)))
        }
        cursor = hi
    }
    append(text.substring(text.offsetByCodePoints(0, cursor)))
}

// MARK: Reader — `WatchBooksView`, `WatchChaptersView`, `WatchChapterView`

@Composable
private fun BooksScreen(go: (String) -> Unit) {
    Screen {
        item { Title(stringResource(R.string.wear_books)) }
        for (group in BookGroup.entries) {
            val books = BookID.entries.filter { it.group == group }
            if (books.isEmpty()) continue
            item { ListHeader { Text(stringResource(group.titleRes), color = Secondary) } }
            items(books) { book ->
                RowChip(book.displayName, null) {
                    go(if (book.isSingleChapter) Routes.chapter(book.number, 1) else Routes.book(book))
                }
            }
        }
    }
}

@Composable
private fun ChaptersScreen(book: BookID, go: (String) -> Unit) {
    Screen {
        item { Title(book.abbreviation) }
        items((1..book.chapterCount).chunked(4)) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
                for (chapter in row) {
                    val description = stringResource(R.string.wear_chapter_accessibility, chapter)
                    Button(
                        onClick = { go(Routes.chapter(book.number, chapter)) },
                        colors = ButtonDefaults.secondaryButtonColors(),
                        modifier = Modifier.size(ButtonDefaults.SmallButtonSize).semantics { contentDescription = description },
                    ) { Text("$chapter", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

@Composable
private fun ChapterScreen(bible: WatchBible, state: WatchBible.State, book: Int, chapter: Int, focus: Int, go: (String) -> Unit) {
    // [book], [chapter] and [focus] are the Bible's own numbers; each verse is opened and marked by its
    // KJV key.
    val chapterLoad by produceState<Pair<List<WatchVerse>, VerseNumbering>?>(null, book, chapter, state.translation, state.editions) {
        value = withContext(Dispatchers.IO) {
            bible.edition(state.translation).let { it.nativeVerses(it.chapterRange(book, chapter)) to it.numbering }
        }
    }
    val verses = chapterLoad?.first
    val numbering = chapterLoad?.second ?: VerseNumbering.IDENTITY
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val loaded = verses.orEmpty()
    val colors = state.snapshot.highlightColors(loaded, numbering)
    LaunchedEffect(verses) {
        val index = loaded.indexOfFirst { it.ref.verse == focus }
        if (focus > 1 && index >= 0) listState.scrollToItem(index + 1)
    }
    val info = BookID.of(book)!!
    val next = when {
        chapter < info.chapterCount -> book to chapter + 1
        book < BookID.entries.size -> book + 1 to 1
        else -> null
    }
    Screen(listState) {
        item { Title(if (info.isSingleChapter) info.abbreviation else stringResource(R.string.wear_book_chapter, info.abbreviation, chapter)) }
        items(loaded) { verse ->
            VerseText(verse, numbered = true, highlight = colors[verse.ref.key]?.let { Color(VersePalette.highlight(it)) }) {
                go(Routes.verse(numbering.kjvRange(VerseRange(verse.ref, verse.ref))))
            }
        }
        if (next != null && loaded.isNotEmpty()) {
            item { RowChip(chapterDisplay(next.first, next.second), R.drawable.ic_chevron_right) { go(Routes.chapter(next.first, next.second)) } }
        }
    }
}

@Composable
private fun chapterDisplay(book: Int, chapter: Int): String {
    val info = BookID.of(book) ?: return ""
    return if (info.isSingleChapter) info.displayName else stringResource(R.string.wear_book_chapter, info.displayName, chapter)
}

/** A book group's header — `BookGroup.title` is English in the pure-JVM shared module. */
private val BookGroup.titleRes: Int
    get() = when (this) {
        BookGroup.LAW -> R.string.wear_book_group_law
        BookGroup.HISTORY -> R.string.wear_book_group_history
        BookGroup.WISDOM -> R.string.wear_book_group_wisdom
        BookGroup.MAJOR_PROPHETS -> R.string.wear_book_group_major_prophets
        BookGroup.MINOR_PROPHETS -> R.string.wear_book_group_minor_prophets
        BookGroup.GOSPELS -> R.string.wear_book_group_gospels
        BookGroup.PAUL -> R.string.wear_book_group_paul
        BookGroup.GENERAL -> R.string.wear_book_group_general
        BookGroup.PROPHECY -> R.string.wear_book_group_prophecy
    }

// MARK: Favorites and notes — `WatchLibraryViews`

@Composable
private fun FavoritesScreen(bible: WatchBible, state: WatchBible.State, go: (String) -> Unit) {
    val favorites = state.snapshot?.items(setOf(Kind.FAVORITE)).orEmpty()
    // The text in the watch's own translation, as the Apple Watch reads it from its edition.
    val texts by produceState(emptyMap<String, String>(), favorites, state.translation) {
        value = withContext(Dispatchers.IO) {
            val edition = bible.edition(state.translation)
            favorites.associate { item -> item.range to (item.verseRange?.let(edition::text)?.takeIf { it.isNotEmpty() } ?: item.text) }
        }
    }
    Screen {
        item { Title(stringResource(R.string.wear_favorites)) }
        if (favorites.isEmpty()) {
            item { Empty(R.drawable.ic_heart, stringResource(R.string.wear_favorites_empty_title), stringResource(R.string.wear_favorites_empty_message)) }
        }
        items(favorites) { item ->
            Card(onClick = { item.verseRange?.let { go(Routes.verse(it)) } }) {
                Text(item.reference, style = MaterialTheme.typography.title3, color = Accent)
                Text(texts[item.range] ?: item.text, style = MaterialTheme.typography.body2, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Highlighted verses in Bible order — `WatchHighlightsView`: neighbouring verses in one colour read as
 * one passage (the snapshot groups them), each with its colour's dot and its text in the watch's own
 * translation.
 */
@Composable
private fun HighlightsScreen(bible: WatchBible, state: WatchBible.State, go: (String) -> Unit) {
    val runs = state.snapshot?.items(setOf(Kind.HIGHLIGHT)).orEmpty().sortedWith(compareBy({ it.startKey }, { it.endKey }))
    val texts by produceState(emptyMap<String, String>(), runs, state.translation) {
        value = withContext(Dispatchers.IO) {
            val edition = bible.edition(state.translation)
            runs.associate { item -> item.range to (item.verseRange?.let(edition::text)?.takeIf { it.isNotEmpty() } ?: item.text) }
        }
    }
    Screen {
        item { Title(stringResource(R.string.wear_highlights)) }
        if (runs.isEmpty()) {
            item { Empty(R.drawable.ic_highlighter, stringResource(R.string.wear_highlights_empty_title), stringResource(R.string.wear_highlights_empty_message)) }
        }
        items(runs) { item ->
            Card(onClick = { item.verseRange?.let { go(Routes.verse(it)) } }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.size(8.dp).background(Color(VersePalette.highlight(item.color)), CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(item.reference, style = MaterialTheme.typography.title3, color = Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(texts[item.range] ?: item.text, style = MaterialTheme.typography.body2, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun NotesScreen(state: WatchBible.State, go: (String) -> Unit) {
    val notes = state.snapshot?.items(setOf(Kind.NOTE)).orEmpty()
    // "Sep 21" in the reader's language and order ("21 sept.", "9月21日").
    val locale = LocalConfiguration.current.locales[0]
    val noteDate = remember(locale) { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale) }
    Screen {
        item { Title(stringResource(R.string.wear_notes)) }
        if (notes.isEmpty()) {
            item { Empty(R.drawable.ic_note, stringResource(R.string.wear_notes_empty_title), stringResource(R.string.wear_notes_empty_message)) }
        }
        items(notes) { note ->
            Card(onClick = { go(Routes.note(note.id)) }) {
                Text(note.noteTitle ?: note.reference, style = MaterialTheme.typography.title3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(note.reference, style = MaterialTheme.typography.body2, color = Accent, maxLines = 1)
                Text(noteDate.format(note.date.atZone(ZoneId.systemDefault())), style = MaterialTheme.typography.body2, color = Secondary)
            }
        }
    }
}

@Composable
private fun NoteScreen(state: WatchBible.State, id: String, go: (String) -> Unit) {
    val note = state.snapshot?.items?.firstOrNull { it.id == id }
    Screen {
        if (note == null) {
            item { Empty(R.drawable.ic_note, stringResource(R.string.wear_note_deleted), null) }
            return@Screen
        }
        item { Title(note.noteTitle ?: note.reference) }
        item { RowChip(note.reference, R.drawable.ic_book) { note.verseRange?.let { go(Routes.verse(it)) } } }
        note.noteBody?.let { body -> item { Text(body, style = MaterialTheme.typography.body1, modifier = Modifier.fillMaxWidth()) } }
        item {
            Text(stringResource(R.string.wear_note_edit_on_phone), style = MaterialTheme.typography.caption3, color = Secondary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Empty(icon: Int, title: String, message: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Icon(painterResource(icon), null, tint = Secondary, modifier = Modifier.size(28.dp))
        Text(title, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center)
        if (message != null) Text(message, style = MaterialTheme.typography.body2, color = Secondary, textAlign = TextAlign.Center)
    }
}

// MARK: Translations — `WatchTranslationsView`

@Composable
private fun TranslationsScreen(bible: WatchBible, state: WatchBible.State) {
    Screen {
        item { Title(stringResource(R.string.wear_translation)) }
        items(state.editions) { edition ->
            val selected = edition.id == state.translation
            ToggleChip(
                checked = selected,
                onCheckedChange = { bible.choose(edition.id) },
                label = { Text(edition.id, fontWeight = FontWeight.SemiBold) },
                secondaryLabel = { Text(edition.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                toggleControl = { RadioButton(selected = selected) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            val phone = state.phoneTranslation
            val footer = if (phone != null && state.editions.none { it.id == phone }) {
                stringResource(R.string.wear_translation_phone_unavailable, phone)
            } else {
                stringResource(R.string.wear_translation_follows_phone)
            }
            Text(footer, style = MaterialTheme.typography.caption3, color = Secondary, textAlign = TextAlign.Center)
        }
    }
}

