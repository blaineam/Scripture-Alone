package com.blainemiller.scripturealone.ui.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import androidx.startup.Initializer
import com.blainemiller.scripturealone.companion.VerseSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Keeps the widgets — and the watch — current: `WidgetSnapshotSync.swift`.
 *
 * - When the reader switches translation or turns red letters on or off, Verse of the Day redraws
 *   (iOS reloads the "VerseOfDay" timelines), and the watch is told the new translation.
 * - When the library or the translation changes, the snapshot is rebuilt, a second after the last
 *   change so a burst (a multi-verse highlight, a sync) is one write; if its content changed, the
 *   Favorites widget redraws and the watch gets the new snapshot.
 *
 * Runs for the life of the process, from process start ([WidgetSyncInitializer]), so a change made
 * anywhere in the app is seen without the reader or the store knowing the widgets exist.
 */
object WidgetSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun start(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (started) return
            started = true
        }
        val settings = WidgetReaderSettings.flow(app)

        // The first value is the state the widgets were last drawn in; only changes redraw them.
        settings.drop(1)
            .onEach { VerseOfDayWidget().updateAll(app) }
            .launchIn(scope)

        settings.map { it.translation }.distinctUntilChanged()
            .onEach { translation -> WearPublisher.publishTranslation(app, translation, WidgetPrefs.translationChangedAt(app, translation)) }
            .launchIn(scope)

        val library = WidgetContent.sources(app).flatMapLatest { it.library }
        combine(library, settings.map { it.translation }.distinctUntilChanged(), ::Pair)
            .debounce(1_000)
            .onEach { (library, translation) -> refresh(app, library, translation) }
            .launchIn(scope)
    }

    /** Rebuilds the snapshot; redraws and re-sends it only if its content changed. */
    suspend fun refresh(context: Context, library: WidgetLibrary, translation: String) {
        val snapshot = WidgetSnapshots.build(context, library, translation)
        if (WidgetSnapshots.write(context, snapshot)) {
            FavoritesWidget().updateAll(context)
        }
        WearPublisher.publishSnapshot(context, WidgetSnapshots.read(context) ?: snapshot)
    }

    /** Rebuilds now from whatever source is current, without waiting for a change. */
    suspend fun refreshNow(context: Context) {
        val app = context.applicationContext
        refresh(app, WidgetContent.resolve(app).library.first(), WidgetReaderSettings.current(app).translation)
    }
}

/** Starts [WidgetSync] at process start. Registered in the manifest under androidx.startup. */
class WidgetSyncInitializer : Initializer<Unit> {
    override fun create(context: Context) = WidgetSync.start(context)
    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}

/**
 * Redraws both widgets at the moments their content changes by the clock — local midnight for Verse
 * of the Day, each three-hour slot for Favorites & Notes — and when the clock or time zone is changed.
 *
 * A non-waking alarm: a widget only matters when the screen is on, and the system delivers a missed
 * non-waking alarm as soon as the device wakes. What is drawn is computed from the time at drawing, so
 * a late alarm draws the right passage, never yesterday's.
 */
object WidgetClock {
    const val ACTION_TICK = "com.blainemiller.scripturealone.widget.TICK"

    fun schedule(context: Context, now: Instant = Instant.now()) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val at = VerseSnapshot.nextSlot(now, FavoritesEntry.SLOT_HOURS)
        alarms.set(AlarmManager.RTC, at.toEpochMilli(), pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, WidgetClockReceiver::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

class WidgetClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                VerseOfDayWidget().updateAll(app)
                FavoritesWidget().updateAll(app)
                WidgetClock.schedule(app)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * DEBUG builds only (registered in `src/debug/AndroidManifest.xml`): switches the demo library on or
 * off for emulator checks and screenshots, as the iOS app's `-seedDemoLibrary` launch argument —
 *
 *     adb shell am broadcast -n <package>/com.blainemiller.scripturealone.ui.widget.WidgetDemoReceiver --ez on true
 */
class WidgetDemoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        WidgetContent.setDemoLibrary(app, intent.getBooleanExtra("on", true))
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                WidgetSync.refreshNow(app)
            } finally {
                pending.finish()
            }
        }
    }
}
