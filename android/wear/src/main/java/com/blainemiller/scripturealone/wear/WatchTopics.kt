package com.blainemiller.scripturealone.wear

import android.app.Activity
import android.app.RemoteInput
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.input.wearableExtender
import com.blainemiller.scripturealone.data.VerseRange
import com.blainemiller.scripturealone.data.topics.LifeTheme
import com.blainemiller.scripturealone.data.topics.LifeThemeCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The Topics directory on the watch — the phone's life themes (`data/topics/LifeThemes.kt`, shared),
 * browsed by group, found by voice or keyboard, and read one passage at a time.
 *
 * Only the curated life themes are here: Nave's Topical Bible is English prose that the phone shows
 * in English only, and its long lists don't suit a watch.
 */
object WatchTopics {
    /** The input screen's answer: the key the typed or dictated text comes back under. */
    internal const val INPUT_KEY = "topic"

    @Volatile private var cached: Pair<String, LifeThemeCatalog>? = null

    /**
     * The themes in the watch's language: `LifeThemes.json` (copied in at build time from the iOS
     * core's resources) with the names, descriptions and search words of `life_themes.xml` (copied from
     * the phone app's). Read again when the language changes; empty if the asset is missing.
     */
    @Synchronized
    fun catalog(context: Context): LifeThemeCatalog {
        val tag = context.resources.configuration.locales[0].toLanguageTag()
        cached?.takeIf { it.first == tag }?.let { return it.second }
        val english = runCatching {
            LifeThemeCatalog.parse(context.assets.open("LifeThemes.json").use { it.readBytes().toString(Charsets.UTF_8) })
        }.getOrDefault(LifeThemeCatalog.EMPTY)
        val resources = context.resources
        val localized = english.localized(
            resources.getStringArray(R.array.life_theme_groups).toList(),
            resources.getStringArray(R.array.life_theme_names).toList(),
            resources.getStringArray(R.array.life_theme_descriptions).toList(),
            resources.getStringArray(R.array.life_theme_synonyms).toList(),
        )
        cached = tag to localized
        return localized
    }

    /** The system speech recogniser, asking for free speech and a few guesses. */
    internal fun recognizerIntent(prompt: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)

    /** The system input screen (keyboard, handwriting and voice), without emoji, finishing as a search. */
    internal fun inputIntent(label: String): Intent {
        val input = RemoteInput.Builder(INPUT_KEY)
            .setLabel(label)
            .wearableExtender {
                setEmojisAllowed(false)
                setInputActionType(EditorInfo.IME_ACTION_SEARCH)
            }
            .build()
        return RemoteInputIntentHelper.createActionRemoteInputIntent().also {
            RemoteInputIntentHelper.putRemoteInputsExtra(it, listOf(input))
        }
    }

    /** What the input screen returned, if anything. */
    internal fun typed(data: Intent?): String? =
        data?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence(INPUT_KEY)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}

/** The themes in the watch's language, read once per language. */
@Composable
private fun rememberCatalog(): LifeThemeCatalog {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { WatchTopics.catalog(context) }
}

// MARK: Navigation

/** The result of a search: replaces an earlier one, so Back from a topic reaches the topics, not the results. */
private fun NavController.openSearch(query: String) = navigate(Routes.topicSearch(query)) { popUpTo(Routes.TOPICS) }

/** A topic opened from a list of results leaves the results behind: swipe-to-dismiss lands on the topics. */
private fun NavController.openTopic(id: String) = navigate(Routes.topic(id)) { popUpTo(Routes.TOPICS) }

private fun NavController.openAllTopics() {
    if (!popBackStack(Routes.TOPICS, inclusive = false)) navigate(Routes.TOPICS)
}

// MARK: Speak or type

private class TopicInput(val speak: () -> Unit, val type: () -> Unit)

/**
 * Finding a topic by voice (the system recogniser, each guess tried until one finds a theme) or by the
 * system input screen. A recogniser that is missing, or whose guesses find nothing, hands over to the
 * input screen.
 */
@Composable
private fun rememberTopicInput(catalog: LifeThemeCatalog, nav: NavController): TopicInput {
    val context = LocalContext.current
    val label = stringResource(R.string.wear_topics_input_label)
    val prompt = stringResource(R.string.wear_topics_speak_prompt)
    val typedLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) WatchTopics.typed(result.data)?.let(nav::openSearch)
    }
    val type = {
        try {
            typedLauncher.launch(WatchTopics.inputIntent(label))
        } catch (_: ActivityNotFoundException) {
        }
    }
    val spokenLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val guesses = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty()
            val hit = guesses.firstOrNull { catalog.search(it).isNotEmpty() }
            if (hit != null) nav.openSearch(hit) else type()
        }
    }
    val speak = {
        try {
            spokenLauncher.launch(WatchTopics.recognizerIntent(prompt))
        } catch (_: ActivityNotFoundException) {
            type()
        }
    }
    return remember(catalog, context) { TopicInput(speak, type) }
}

// MARK: Browse — the themes, grouped as on the phone

@Composable
internal fun TopicsScreen(nav: NavController) {
    val catalog = rememberCatalog()
    val input = rememberTopicInput(catalog, nav)
    Screen {
        item { Title(stringResource(R.string.wear_topics)) }
        item { RowChip(stringResource(R.string.wear_topics_speak), R.drawable.ic_mic, onClick = input.speak) }
        item { RowChip(stringResource(R.string.wear_topics_type), R.drawable.ic_keyboard, onClick = input.type) }
        if (catalog.themes.isEmpty()) {
            item { Text(stringResource(R.string.wear_topics_unavailable), color = Secondary, textAlign = TextAlign.Center) }
        }
        for (group in catalog.groups) {
            val themes = catalog.themes(group)
            if (themes.isEmpty()) continue
            item { ListHeader { Text(group.localizedName, color = Secondary) } }
            items(themes) { theme -> ThemeChip(theme) { nav.openTopic(theme.id) } }
        }
    }
}

@Composable
private fun ThemeChip(theme: LifeTheme, onClick: () -> Unit) {
    RowChip(theme.localizedName, null, trailing = theme.passages.size.toString(), onClick = onClick)
}

// MARK: Search results

@Composable
internal fun TopicSearchScreen(nav: NavController, query: String) {
    val catalog = rememberCatalog()
    val input = rememberTopicInput(catalog, nav)
    val found = remember(catalog, query) { catalog.search(query, limit = 5) }
    Screen {
        item { Title(stringResource(R.string.wear_topics)) }
        item { Text("“$query”", style = MaterialTheme.typography.caption3, color = Secondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        if (found.isEmpty()) {
            item { Text(stringResource(R.string.wear_topics_no_match, query), textAlign = TextAlign.Center) }
            item { RowChip(stringResource(R.string.wear_topics_speak), R.drawable.ic_mic, onClick = input.speak) }
            item { RowChip(stringResource(R.string.wear_topics_type), R.drawable.ic_keyboard, onClick = input.type) }
        }
        items(found) { theme -> ThemeChip(theme) { nav.openTopic(theme.id) } }
    }
}

// MARK: A topic's passages, one at a time

/** A passage of a topic as the watch's translation numbers it. */
private class TopicPassage(val shown: VerseRange, val verses: List<WatchVerse>)

@Composable
internal fun TopicScreen(bible: WatchBible, state: WatchBible.State, nav: NavController, id: String) {
    val theme = rememberCatalog().theme(id)
    if (theme == null) {
        Screen {
            item { Title(stringResource(R.string.wear_topics)) }
            item { Text(stringResource(R.string.wear_topics_unavailable), color = Secondary, textAlign = TextAlign.Center) }
            item { RowChip(stringResource(R.string.wear_topics_all), R.drawable.ic_topics) { nav.openAllTopics() } }
        }
        return
    }
    // The passages are KJV keys; those the watch's translation holds, drawn as it numbers them.
    val loaded by produceState<List<TopicPassage>?>(null, id, state.translation, state.editions) {
        value = withContext(Dispatchers.IO) {
            val edition = bible.edition(state.translation)
            theme.passages.mapNotNull { range ->
                edition.verses(range).takeIf { it.isNotEmpty() }?.let { TopicPassage(edition.nativeRange(range), it) }
            }
        }
    }
    val passages = loaded
    var page by rememberSaveable(id) { mutableIntStateOf(0) }
    val last = passages?.size ?: 0
    // The page after the last passage is the end page.
    val next = { if (passages != null && page < last) page++ }

    // Left swipe = next passage. Observed only (nothing is consumed), so the lists still scroll and the
    // right swipe still reaches swipe-to-dismiss.
    Box(Modifier.fillMaxSize().onSwipeLeft(next)) {
        when {
            passages == null -> Screen { item { Title(theme.localizedName) } }
            page < passages.size -> PassagePage(theme, passages, page, state, next)
            else -> EndPage(theme, passages.isEmpty(), state, restart = { page = 0 }, allTopics = { nav.openAllTopics() })
        }
    }
}

@Composable
private fun PassagePage(theme: LifeTheme, passages: List<TopicPassage>, page: Int, state: WatchBible.State, next: () -> Unit) {
    val passage = passages[page]
    val position = stringResource(R.string.wear_topics_passage_of, page + 1, passages.size)
    // A new list state per passage: each starts at its top.
    key(theme.id, page) {
        Screen {
            // The Verse of the Day's look: the topic as a small caption, the reference in the accent
            // colour, the passage in the watch's translation.
            item {
                Text(
                    theme.localizedName.uppercase(), style = MaterialTheme.typography.caption3, color = Secondary,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
            item { Title(passage.shown.display, Accent) }
            items(passage.verses) { verse -> VerseText(verse, numbered = passage.verses.size > 1) }
            item {
                Text(
                    position, style = MaterialTheme.typography.caption3, color = Secondary,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
            // The button for anyone who can't swipe.
            item { RowChip(stringResource(R.string.wear_topics_next), R.drawable.ic_chevron_right, onClick = next) }
            item {
                Text(
                    state.edition?.name ?: state.translation,
                    style = MaterialTheme.typography.caption3, color = Secondary, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun EndPage(theme: LifeTheme, nothingToShow: Boolean, state: WatchBible.State, restart: () -> Unit, allTopics: () -> Unit) {
    Screen {
        item { Title(theme.localizedName) }
        item {
            Text(
                if (nothingToShow) stringResource(R.string.wear_topics_none_in_edition, state.abbreviation)
                else stringResource(R.string.wear_topics_end, pluralStringResource(R.plurals.wear_topics_passages, theme.passages.size, theme.passages.size)),
                color = Secondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
        if (!nothingToShow) item { RowChip(stringResource(R.string.wear_topics_start_over), R.drawable.ic_restart, onClick = restart) }
        item { RowChip(stringResource(R.string.wear_topics_all), R.drawable.ic_topics, onClick = allTopics) }
    }
}

// MARK: Swipe left

/**
 * Calls [action] when a one-finger drag ends well to the left of where it began. The pointer events are
 * only watched (on the initial pass, never consumed): the lists under it still scroll by touch, and a
 * drag to the right is left for swipe-to-dismiss.
 */
internal fun Modifier.onSwipeLeft(action: () -> Unit): Modifier = composed {
    val current = rememberUpdatedState(action)
    pointerInput(Unit) {
        val distance = 48.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var end = down.position
            var oneFinger = true
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.size > 1) oneFinger = false
                event.changes.firstOrNull { it.id == down.id }?.let { end = it.position }
            } while (event.changes.any { it.pressed })
            val dx = end.x - down.position.x
            val dy = end.y - down.position.y
            // Mostly sideways: a vertical scroll that drifts doesn't count.
            if (oneFinger && dx < -distance && abs(dx) > 2 * abs(dy)) current.value()
        }
    }
}
