package com.blainemiller.scripturealone.ui.widget

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.companion.VerseSnapshot
import com.blainemiller.scripturealone.companion.VerseSnapshot.Kind
import com.blainemiller.scripturealone.text.AppText
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** What a Favorites & Notes widget rotates through — `VerseSource` on iOS, chosen per widget. */
enum class VerseSource(val raw: String, @StringRes private val titleRes: Int, val kinds: Set<Kind>) {
    EVERYTHING("everything", R.string.widget_source_everything, Kind.entries.toSet()),
    FAVORITES("favorites", R.string.widget_source_favorites, setOf(Kind.FAVORITE)),
    HIGHLIGHTS("highlights", R.string.widget_source_highlights, setOf(Kind.HIGHLIGHT)),
    NOTES("notes", R.string.widget_source_notes, setOf(Kind.NOTE));

    val title: String get() = AppText.get(titleRes)

    companion object {
        val KEY = stringPreferencesKey("source")
        fun of(raw: String?): VerseSource = entries.firstOrNull { it.raw == raw } ?: EVERYTHING
    }
}

/** One rendering of the widget — `FavoritesEntry` on iOS. */
data class FavoritesEntry(val item: VerseSnapshot.Item?, val position: Int, val total: Int, val source: VerseSource) {
    companion object {
        /**
         * The item for [now]: a new one every three hours, continuing day to day, plus the reader's
         * taps on "Next" — `FavoritesProvider.entries` for the current slot.
         */
        fun at(snapshot: VerseSnapshot?, source: VerseSource, now: Instant, nudge: Int): FavoritesEntry {
            val items = snapshot?.items(source.kinds).orEmpty()
            if (items.isEmpty()) return FavoritesEntry(null, 0, 0, source)
            val index = VerseSnapshot.rotationIndex(now, items.size, SLOT_HOURS, nudge)
            return FavoritesEntry(items[index], index, items.size, source)
        }

        const val SLOT_HOURS = 3
    }
}

/**
 * Favorites & Notes — `FavoritesWidget.swift`: the reader's favorited, highlighted and noted verses,
 * one at a time, a new one every three hours and on each tap of "Next". Long-press → reconfigure picks
 * which kinds it shows, as the iOS widget's "Show" parameter.
 */
class FavoritesWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(WidgetFamily.sizes)
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        withContext(Dispatchers.IO) { if (WidgetSnapshots.read(context) == null) buildNow(context) }
        WidgetClock.schedule(context)
        provideContent {
            // Read inside the composition, as in VerseOfDayWidget: a live session recomposes on update.
            val revision by WidgetRevision.value.collectAsState()
            val source = VerseSource.of(currentState(VerseSource.KEY))
            val entry = remember(revision, source) {
                FavoritesEntry.at(WidgetSnapshots.read(context), source, Instant.now(), WidgetPrefs.nudge(context))
            }
            FavoritesContent(entry)
        }
    }

    /** No snapshot yet (a fresh install, or cleared data): build one rather than show nothing. */
    private suspend fun buildNow(context: Context): VerseSnapshot? {
        val library = WidgetContent.resolve(context).library.first()
        if (library == WidgetLibrary.EMPTY) return null
        val snapshot = WidgetSnapshots.build(context, library, WidgetReaderSettings.current(context).translation)
        WidgetSnapshots.write(context, snapshot)
        return snapshot
    }
}

class FavoritesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FavoritesWidget()
}

/** The widget's "Next" button — `ShowNextVerseIntent`: nudges the rotation forward one verse. */
class ShowNextVerseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetPrefs.advanceNudge(context)
        WidgetRevision.bump()
        FavoritesWidget().updateAll(context)
    }
}

/** `FavoritesCard`. */
@Composable
fun FavoritesContent(entry: FavoritesEntry) {
    val context = LocalContext.current
    val family = WidgetFamily.of(LocalSize.current)
    val item = entry.item
    var root = GlanceModifier.fillMaxSize()
        .appWidgetBackground()
        .background(ImageProvider(R.drawable.widget_page))
        .cornerRadius(android.R.dimen.system_app_widget_background_radius)
        .padding(16.dp)
    item?.verseRange?.let { root = root.clickable(actionStartActivity(openPassageIntent(context, it))) }

    Column(modifier = root) {
        if (item == null) {
            Empty(entry.source)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
                Badge(item)
                Spacer(GlanceModifier.width(6.dp))
                val reference = if (family == WidgetFamily.SMALL) item.verseRange?.abbreviatedDisplay ?: item.reference else item.reference
                AndroidRemoteViews(WidgetText.reference(context, reference), GlanceModifier.defaultWeight())
            }
            val title = item.noteTitle
            if (item.kind == Kind.NOTE && title != null && family != WidgetFamily.SMALL) {
                Spacer(GlanceModifier.height(6.dp))
                Text(
                    title, maxLines = 1,
                    style = TextStyle(color = WidgetColors.secondaryInk, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                )
            }
            Spacer(GlanceModifier.height(if (family == WidgetFamily.SMALL) 6.dp else 8.dp))
            AndroidRemoteViews(WidgetText.verse(context, family, item.text), GlanceModifier.fillMaxWidth().defaultWeight())
            if (family != WidgetFamily.SMALL) {
                Spacer(GlanceModifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
                    Text(
                        "${entry.position + 1} of ${entry.total}",
                        modifier = GlanceModifier.defaultWeight(),
                        style = TextStyle(color = WidgetColors.secondaryInk, fontSize = 11.sp, fontWeight = FontWeight.Medium),
                    )
                    if (entry.total > 1) NextButton()
                }
            }
        }
    }
}

@Composable
private fun Badge(item: VerseSnapshot.Item) {
    when (item.kind) {
        Kind.HIGHLIGHT -> Image(
            ImageProvider(R.drawable.widget_dot), contentDescription = LocalContext.current.getString(R.string.widget_badge_highlight),
            modifier = GlanceModifier.size(10.dp), colorFilter = ColorFilter.tint(WidgetColors.highlight(item.color)),
        )
        Kind.FAVORITE -> Image(
            ImageProvider(R.drawable.ic_widget_heart), contentDescription = LocalContext.current.getString(R.string.widget_badge_favorite),
            modifier = GlanceModifier.size(13.dp), colorFilter = ColorFilter.tint(WidgetColors.heart),
        )
        Kind.NOTE -> Image(
            ImageProvider(R.drawable.ic_widget_note), contentDescription = LocalContext.current.getString(R.string.widget_badge_note),
            modifier = GlanceModifier.size(13.dp), colorFilter = ColorFilter.tint(WidgetColors.accent),
        )
    }
}

/** "Next →" in a tinted capsule, as the iOS widget's bordered capsule button. */
@Composable
private fun NextButton() {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = GlanceModifier
            .background(ImageProvider(R.drawable.widget_capsule))
            .cornerRadius(14.dp)
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .clickable(actionRunCallback<ShowNextVerseAction>())
            .semantics { contentDescription = context.getString(R.string.widget_show_next_verse) },
    ) {
        Text(context.getString(R.string.common_next), style = TextStyle(color = WidgetColors.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium))
        Spacer(GlanceModifier.width(4.dp))
        Image(
            ImageProvider(R.drawable.ic_widget_arrow_forward), contentDescription = null,
            modifier = GlanceModifier.size(12.dp), colorFilter = ColorFilter.tint(WidgetColors.accent),
        )
    }
}

@Composable
private fun Empty(source: VerseSource) {
    Image(
        ImageProvider(R.drawable.ic_widget_heart_outline), contentDescription = null,
        modifier = GlanceModifier.size(22.dp), colorFilter = ColorFilter.tint(WidgetColors.accent),
    )
    Spacer(GlanceModifier.height(6.dp))
    val context = LocalContext.current
    val message = context.getString(
        when (source) {
            VerseSource.HIGHLIGHTS -> R.string.widget_empty_highlights
            VerseSource.NOTES -> R.string.widget_empty_notes
            else -> R.string.widget_empty_favorites
        },
    )
    AndroidRemoteViews(WidgetText.verse(context, WidgetFamily.SMALL, message))
}
