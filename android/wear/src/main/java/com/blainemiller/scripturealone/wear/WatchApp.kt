package com.blainemiller.scripturealone.wear

import android.net.Uri
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.canon.BookGroup
import com.blainemiller.scripturealone.data.canon.BookID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The watch's screens, as `WatchRoute` on the Apple Watch. */
object Routes {
    const val HOME = "home"
    const val BOOKS = "books"
    const val FAVORITES = "favorites"
    const val NOTES = "notes"
    const val TRANSLATIONS = "translations"
    val SCREENS = setOf(BOOKS, FAVORITES, NOTES, TRANSLATIONS)

    fun verse(ref: String) = "verse/$ref"
    fun verse(range: VerseRange) = verse(range.storageString)
    fun book(book: BookID) = "book/${book.number}"
    fun chapter(book: Int, chapter: Int, focus: Int = 0) = "chapter/$book/$chapter/$focus"
    fun note(id: String) = "note/${Uri.encode(id)}"
}

private val Accent = Color(VersePalette.DARK.accent)
private val Secondary = Color(0xFFB4ABA2)
private val HeartRed = Color(0xFFFF453A)
private val NoteOrange = Color(0xFFFF9F0A)
private val WordsOfChrist = Color(VersePalette.WATCH_RED)

@Composable
fun WatchApp(bible: WatchBible, pendingRoute: MutableStateFlow<String?>) {
    MaterialTheme(colors = Colors(primary = Accent, primaryVariant = Accent, onPrimary = Color.Black)) {
        val nav = rememberSwipeDismissableNavController()
        val state by bible.state.collectAsStateWithLifecycle()
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
            composable(Routes.BOOKS) { BooksScreen { nav.navigate(it) } }
            composable("book/{book}") { entry ->
                BookID.of(entry.arguments?.getString("book")?.toIntOrNull() ?: 0)?.let { book ->
                    ChaptersScreen(book) { nav.navigate(it) }
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
            composable(Routes.TRANSLATIONS) { TranslationsScreen(bible, state) }
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
    val context = LocalContext.current
    val today = remember(state.translation) { WatchVerseOfDay.at(WatchDaily.catalog(context), java.time.Instant.now(), state.translation) }
    val favorites = state.snapshot?.items(setOf(Kind.FAVORITE)).orEmpty().size
    val notes = state.snapshot?.items(setOf(Kind.NOTE)).orEmpty().size
    Screen {
        item { Title("Scripture Alone") }
        today?.range?.let { range ->
            item {
                Card(onClick = { go(Routes.verse(range)) }, modifier = Modifier.semantics { contentDescription = "Verse of the Day, ${today.reference}. ${today.text}" }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_sun_horizon), null, tint = Secondary, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("VERSE OF THE DAY", style = MaterialTheme.typography.caption3, color = Secondary)
                    }
                    Text(today.reference, style = MaterialTheme.typography.title3, color = Accent)
                    Text(today.text, style = MaterialTheme.typography.body1, color = Color.White, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item { RowChip("Favorites", R.drawable.ic_heart, HeartRed, trailing = favorites.takeIf { it > 0 }?.toString()) { go(Routes.FAVORITES) } }
        item { RowChip("Notes", R.drawable.ic_note, NoteOrange, trailing = notes.takeIf { it > 0 }?.toString()) { go(Routes.NOTES) } }
        item { RowChip("Read", R.drawable.ic_book) { go(Routes.BOOKS) } }
        item { RowChip("Translation", R.drawable.ic_translate, trailing = state.translation) { go(Routes.TRANSLATIONS) } }
    }
}

// MARK: Verse — `WatchVerseView`

@Composable
private fun VerseScreen(bible: WatchBible, state: WatchBible.State, range: VerseRange, go: (String) -> Unit) {
    val context = LocalContext.current
    val verses by produceState<List<WatchVerse>?>(null, range, state.translation) {
        value = withContext(Dispatchers.IO) { bible.edition(state.translation).verses(range) }
    }
    var speaking by remember { mutableStateOf(false) }
    val speaker = remember { VerseSpeaker(context) { speaking = it } }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }
    val notes = state.snapshot.notesOn(range)
    val start = range.start
    val chapterName = chapterDisplay(start.book, start.chapter)

    Screen {
        item { Title(range.display, Accent) }
        val loaded = verses
        if (loaded != null && loaded.isEmpty()) {
            item { Text("This passage isn’t in the watch’s ${state.translation}.", color = Secondary, textAlign = TextAlign.Center) }
        }
        items(loaded.orEmpty()) { verse -> VerseText(verse, numbered = loaded.orEmpty().size > 1) }
        if (!loaded.isNullOrEmpty()) {
            item {
                Chip(
                    onClick = { if (speaking) speaker.stop() else speaker.speak(loaded.joinToString(" ") { it.text }) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.secondaryChipColors(),
                    icon = { Icon(painterResource(if (speaking) R.drawable.ic_stop else R.drawable.ic_speaker), null, modifier = Modifier.size(ChipDefaults.IconSize)) },
                    label = { Text(if (speaking) "Stop" else "Speak") },
                )
            }
        }
        if (notes.isNotEmpty()) {
            item { ListHeader { Text("Notes") } }
            items(notes) { note ->
                RowChip(note.noteTitle ?: note.reference, R.drawable.ic_note, NoteOrange, secondary = note.noteBody) { go(Routes.note(note.id)) }
            }
        }
        item { RowChip("Read $chapterName", R.drawable.ic_book) { go(Routes.chapter(start.book, start.chapter, start.verse)) } }
        item {
            Text(
                bible.editions.firstOrNull { it.id == state.translation }?.name ?: state.translation,
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
    Text(
        verseAnnotated(verse, numbered),
        modifier = modifier.semantics { contentDescription = if (numbered) "Verse ${verse.ref.verse}. ${verse.text}" else verse.text },
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
        item { Title("Books") }
        for (group in BookGroup.entries) {
            val books = BookID.entries.filter { it.group == group }
            if (books.isEmpty()) continue
            item { ListHeader { Text(group.title, color = Secondary) } }
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
                    Button(
                        onClick = { go(Routes.chapter(book.number, chapter)) },
                        colors = ButtonDefaults.secondaryButtonColors(),
                        modifier = Modifier.size(ButtonDefaults.SmallButtonSize).semantics { contentDescription = "Chapter $chapter" },
                    ) { Text("$chapter", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

@Composable
private fun ChapterScreen(bible: WatchBible, state: WatchBible.State, book: Int, chapter: Int, focus: Int, go: (String) -> Unit) {
    val verses by produceState<List<WatchVerse>?>(null, book, chapter, state.translation) {
        value = withContext(Dispatchers.IO) { bible.edition(state.translation).let { it.verses(it.chapterRange(book, chapter)) } }
    }
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val loaded = verses.orEmpty()
    val colors = state.snapshot.highlightColors(VerseRange(VerseRef(book, chapter, 0), VerseRef(book, chapter, 999)))
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
        item { Title(if (info.isSingleChapter) info.abbreviation else "${info.abbreviation} $chapter") }
        items(loaded) { verse ->
            VerseText(verse, numbered = true, highlight = colors[verse.ref.key]?.let { Color(VersePalette.highlight(it)) }) {
                go(Routes.verse(VerseRange(verse.ref, verse.ref)))
            }
        }
        if (next != null && loaded.isNotEmpty()) {
            item { RowChip(chapterDisplay(next.first, next.second), R.drawable.ic_chevron_right) { go(Routes.chapter(next.first, next.second)) } }
        }
    }
}

private fun chapterDisplay(book: Int, chapter: Int): String {
    val info = BookID.of(book) ?: return ""
    return if (info.isSingleChapter) info.displayName else "${info.displayName} $chapter"
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
        item { Title("Favorites") }
        if (favorites.isEmpty()) {
            item { Empty(R.drawable.ic_heart, "No Favorites", "Favorite a verse on your phone and it appears here.") }
        }
        items(favorites) { item ->
            Card(onClick = { item.verseRange?.let { go(Routes.verse(it)) } }) {
                Text(item.reference, style = MaterialTheme.typography.title3, color = Accent)
                Text(texts[item.range] ?: item.text, style = MaterialTheme.typography.body2, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private val noteDate = DateTimeFormatter.ofPattern("MMM d", Locale.US)

@Composable
private fun NotesScreen(state: WatchBible.State, go: (String) -> Unit) {
    val notes = state.snapshot?.items(setOf(Kind.NOTE)).orEmpty()
    Screen {
        item { Title("Notes") }
        if (notes.isEmpty()) {
            item { Empty(R.drawable.ic_note, "No Notes", "Notes you write on your phone appear here.") }
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
            item { Empty(R.drawable.ic_note, "Note Deleted", null) }
            return@Screen
        }
        item { Title(note.noteTitle ?: note.reference) }
        item { RowChip(note.reference, R.drawable.ic_book) { note.verseRange?.let { go(Routes.verse(it)) } } }
        note.noteBody?.let { body -> item { Text(body, style = MaterialTheme.typography.body1, modifier = Modifier.fillMaxWidth()) } }
        item {
            Text("Edit notes on your phone.", style = MaterialTheme.typography.caption3, color = Secondary, textAlign = TextAlign.Center)
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
        item { Title("Translation") }
        items(bible.editions) { edition ->
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
            val footer = if (phone != null && bible.editions.none { it.id == phone }) {
                "$phone on your phone can’t be read here. Online translations can’t be stored on the watch."
            } else {
                "Follows your phone."
            }
            Text(footer, style = MaterialTheme.typography.caption3, color = Secondary, textAlign = TextAlign.Center)
        }
    }
}

