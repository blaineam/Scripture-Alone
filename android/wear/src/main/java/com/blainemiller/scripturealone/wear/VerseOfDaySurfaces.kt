package com.blainemiller.scripturealone.wear

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.ChipColors
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataTimeline
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingTimelineComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.TimeInterval
import androidx.wear.watchface.complications.datasource.TimelineEntry
import com.blainemiller.scripturealone.companion.ScriptureLink
import com.blainemiller.scripturealone.companion.VersePalette
import com.blainemiller.scripturealone.data.daily.DailyVerseCatalog
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.time.Instant

/**
 * The Verse of the Day tile. Apple Watch has no tiles; this is the Smart Stack's rectangular widget in
 * Wear OS form: the reference in the accent, the passage, and "Read", which opens the watch app at it.
 * The tile asks to be refreshed at the next local midnight, and redraws when the translation changes.
 */
class VerseOfDayTileService : TileService() {

    /** Off the main thread: the passage may be read through a received edition's verse numbering. */
    private val worker = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> = worker.submit(
        Callable {
            val now = Instant.now()
            val today = WatchBible.get(this).verseOfDay(now)
            val layout = layout(this, requestParams.deviceConfiguration, today)
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setFreshnessIntervalMillis(DailyVerseCatalog.nextMidnight(now).toEpochMilli() - now.toEpochMilli())
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
                .build()
        },
    )

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())

    companion object {
        private const val RESOURCES_VERSION = "1"

        fun layout(context: Context, device: DeviceParameters, today: WatchVerseOfDay?): LayoutElementBuilders.LayoutElement {
            val secondary = argb(0xFFB4ABA2.toInt())
            val accent = argb(VersePalette.DARK.accent.toInt())
            val label = Text.Builder(context, context.getString(R.string.verse_of_the_day).uppercase())
                .setTypography(Typography.TYPOGRAPHY_CAPTION2).setColor(secondary).build()
            if (today == null) {
                return PrimaryLayout.Builder(device).setPrimaryLabelTextContent(label)
                    .setContent(Text.Builder(context, context.getString(R.string.wear_tile_open_app, context.getString(R.string.app_name))).setColor(argb(0xFFFFFFFF.toInt())).build())
                    .build()
            }
            val content = LayoutElementBuilders.Column.Builder()
                .addContent(Text.Builder(context, today.reference).setTypography(Typography.TYPOGRAPHY_TITLE3).setColor(accent).build())
                .addContent(
                    Text.Builder(context, today.text)
                        .setTypography(Typography.TYPOGRAPHY_BODY2)
                        .setColor(argb(0xFFFFFFFF.toInt()))
                        .setMaxLines(4)
                        .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
                        .build(),
                )
                .build()
            val open = ModifiersBuilders.Clickable.Builder()
                .setId("open")
                .setOnClick(
                    ActionBuilders.LaunchAction.Builder()
                        .setAndroidActivity(
                            ActionBuilders.AndroidActivity.Builder()
                                .setPackageName(context.packageName)
                                .setClassName(MainActivity::class.java.name)
                                .addKeyToExtraMapping(MainActivity.EXTRA_REF, ActionBuilders.stringExtra(today.range?.storageString.orEmpty()))
                                .build(),
                        )
                        .build(),
                )
                .build()
            return PrimaryLayout.Builder(device)
                .setResponsiveContentInsetEnabled(true)
                .setPrimaryLabelTextContent(label)
                .setContent(content)
                .setPrimaryChipContent(
                    CompactChip.Builder(context, context.getString(R.string.wear_read), open, device)
                        .setChipColors(ChipColors(accent, argb(0xFF000000.toInt())))
                        .build(),
                )
                .build()
        }
    }
}

/**
 * The Verse of the Day complication — `VerseOfDayComplication` on the Apple Watch: the reference ("Ps
 * 23:1") in every family, the opening words on the long one. A week of entries, one per local day, as
 * WidgetKit gets a week of timeline entries, so the face rolls over at midnight without waking the app.
 * Tapping opens the watch app at the passage.
 */
class VerseComplicationService : SuspendingTimelineComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationDataTimeline? {
        val week = withContext(Dispatchers.IO) { WatchBible.get(this@VerseComplicationService).verseOfDayWeek(Instant.now()) }
        val first = week.firstOrNull() ?: return null
        return ComplicationDataTimeline(
            defaultComplicationData = data(request.complicationType, first.third) ?: return null,
            timelineEntries = week.drop(1).mapNotNull { (start, end, verse) ->
                data(request.complicationType, verse)?.let { TimelineEntry(TimeInterval(start, end), it) }
            },
        )
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        WatchDaily.catalog(this)?.verses?.firstOrNull { it.ref == "19023001-19023001" }
            ?.let { data(type, WatchVerseOfDay(it, DailyVerseCatalog.FALLBACK_TRANSLATION)) }

    private fun data(type: ComplicationType, verse: WatchVerseOfDay): ComplicationData? {
        val description = PlainComplicationText.Builder(getString(R.string.wear_complication_content_description, verse.reference)).build()
        val icon = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_book)).build()
        val tap = verse.range?.let { tapAction(this, ScriptureLink.url(it)) }
        return when (type) {
            ComplicationType.SHORT_TEXT -> {
                val (book, verses) = verse.shortTextLines
                ShortTextComplicationData.Builder(PlainComplicationText.Builder(verses.ifEmpty { book }).build(), description)
                    .setTitle(PlainComplicationText.Builder(book).build())
                    .setTapAction(tap)
                    .build()
            }
            ComplicationType.LONG_TEXT ->
                LongTextComplicationData.Builder(PlainComplicationText.Builder(verse.text).build(), description)
                    .setTitle(PlainComplicationText.Builder(verse.shortReference).build())
                    .setMonochromaticImage(icon)
                    .setTapAction(tap)
                    .build()
            ComplicationType.MONOCHROMATIC_IMAGE ->
                MonochromaticImageComplicationData.Builder(icon, description).setTapAction(tap).build()
            else -> null
        }
    }

    companion object {
        fun tapAction(context: Context, link: String): PendingIntent = PendingIntent.getActivity(
            context, link.hashCode(),
            Intent(Intent.ACTION_VIEW, Uri.parse(link), context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
