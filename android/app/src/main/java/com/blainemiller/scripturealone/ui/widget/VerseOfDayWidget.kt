package com.blainemiller.scripturealone.ui.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.blainemiller.scripturealone.R
import com.blainemiller.scripturealone.text.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Verse of the Day on the home screen — `VerseOfDayWidget.swift`.
 *
 * The passage is a pure function of the local date (`DailyVerseCatalog`), so it is the one the iPhone
 * shows today. Where WidgetKit takes a week of timeline entries, an Android widget is redrawn:
 * [WidgetClock] sets an alarm for the next local midnight, and the widget redraws when the reader
 * switches translation or turns red letters on or off ([WidgetSync]).
 */
class VerseOfDayWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(WidgetFamily.sizes)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = WidgetReaderSettings.current(context)
        val catalog = withContext(Dispatchers.IO) { DailyVerseLibrary.catalog(context) }
        WidgetClock.schedule(context)
        provideContent {
            // Read inside the composition: while a Glance session is alive an update recomposes it
            // rather than calling provideGlance again, so anything read above would go stale.
            val revision by WidgetRevision.value.collectAsState()
            val settings by remember { WidgetReaderSettings.flow(context) }.collectAsState(initial)
            val entry = remember(revision, settings.translation) {
                VerseOfDayEntry.at(catalog, Instant.now(), settings.translation)
            }
            VerseOfDayContent(entry, redLetters = settings.redLetters)
        }
    }
}

class VerseOfDayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = VerseOfDayWidget()
}

/** `HomeVerseCard`: the verse on a warm page, the reference in the accent. */
@Composable
fun VerseOfDayContent(entry: VerseOfDayEntry, redLetters: Boolean) {
    val context = LocalContext.current
    val family = WidgetFamily.of(LocalSize.current)
    val open = entry.range?.let { actionStartActivity(openPassageIntent(context, it)) }
    var root = GlanceModifier.fillMaxSize()
        .appWidgetBackground()
        .background(ImageProvider(R.drawable.widget_page))
        .cornerRadius(android.R.dimen.system_app_widget_background_radius)
        .padding(16.dp)
        .semantics { contentDescription = context.getString(R.string.widget_votd_accessibility, entry.reference, entry.text) }
    if (open != null) root = root.clickable(open)

    Column(modifier = root) {
        if (family == WidgetFamily.SMALL) {
            // In a Row, as the other headers are: as a bare Column child beside the weighted verse,
            // Glance measured the verse to nothing (seen on the emulator: the small widget lost its text).
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                AndroidRemoteViews(WidgetText.reference(context, entry.shortReference), GlanceModifier.defaultWeight())
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    ImageProvider(R.drawable.ic_widget_sun_horizon), contentDescription = null,
                    modifier = GlanceModifier.size(13.dp), colorFilter = ColorFilter.tint(WidgetColors.secondaryInk),
                )
                Spacer(GlanceModifier.width(5.dp))
                // The theme in the app's language (`DailyVerse.localizedTheme`), English where it has none.
                val label = if (family == WidgetFamily.LARGE) {
                    context.getString(R.string.widget_votd_with_theme, entry.verse.theme(AppLanguage.current))
                } else {
                    context.getString(R.string.widget_votd_name)
                }
                Text(
                    label.uppercase(),
                    maxLines = 1,
                    style = TextStyle(color = WidgetColors.secondaryInk, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                )
            }
        }
        Spacer(GlanceModifier.height(if (family == WidgetFamily.SMALL) 6.dp else 10.dp))
        AndroidRemoteViews(
            WidgetText.verse(context, family, entry.text, entry.red.takeIf { redLetters }),
            GlanceModifier.fillMaxWidth().defaultWeight(),
        )
        if (family != WidgetFamily.SMALL) {
            Spacer(GlanceModifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom, modifier = GlanceModifier.fillMaxWidth()) {
                AndroidRemoteViews(WidgetText.reference(context, entry.reference), GlanceModifier.defaultWeight())
                Spacer(GlanceModifier.width(8.dp))
                Text(
                    entry.translation,
                    maxLines = 1,
                    style = TextStyle(color = WidgetColors.secondaryInk, fontSize = 11.sp, fontWeight = FontWeight.Medium),
                )
            }
        }
    }
}
