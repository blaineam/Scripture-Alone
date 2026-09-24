package com.blainemiller.scripturealone.ui.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import androidx.startup.Initializer
import com.blainemiller.scripturealone.companion.VerseSnapshot
import com.blainemiller.scripturealone.data.assets.AssetLibrary
import com.blainemiller.scripturealone.data.assets.AssetState
import com.blainemiller.scripturealone.data.prefs.ReaderKeys
import com.blainemiller.scripturealone.data.prefs.readerDataStore
import com.blainemiller.scripturealone.ui.reader.ReaderAccent
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.net.Uri
import com.blainemiller.scripturealone.companion.WearLink
import com.blainemiller.scripturealone.data.translations.TranslationLibrary
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.Wearable
import java.time.Instant

/**
 * Keeps the widgets — and the watch — current: `WidgetSnapshotSync.swift`.
 *
 * - When the reader switches translation or turns red letters on or off, Verse of the Day redraws
 *   (iOS reloads the "VerseOfDay" timelines), and the watch is told the new translation.
 * - When the reader picks another accent colour, the watch is told it (its dark-page value).
 * - When the reader's imports change, the watch is sent each one's edition and the list of them —
 *   and again when the watch reports holding less than it should (`WatchLink.importsChanged`).
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

        // The watch wears the reader's accent too.
        app.readerDataStore.data
            .catch { if (it is java.io.IOException) emit(emptyPreferences()) else throw it }
            .map { (ReaderAccent.fromRaw(it[ReaderKeys.ACCENT]) ?: ReaderAccent.SUNRISE).watchHex }
            .distinctUntilChanged()
            .onEach { WearPublisher.publishAccent(app, it) }
            .launchIn(scope)

        // A locale Bible's watch edition, once its pack is on the phone — so also when the pack lands
        // after the switch (first launch fetches it without waiting).
        val readyBibles = AssetLibrary.states.map { states -> states.filterValues { it == AssetState.Ready }.keys }.distinctUntilChanged()
        combine(settings.map { it.translation }.distinctUntilChanged(), readyBibles, ::Pair)
            .map { it.first }
            .onEach { translation -> WearPublisher.publishEdition(app, translation) }
            .launchIn(scope)

        // Every import's watch edition, and the list that removes one deleted here — once the library
        // has scanned its directory — and again whenever the watch reports what it holds.
        TranslationLibrary.state
            .map { it.importsLoaded to it.imported }
            .distinctUntilChanged()
            .onEach { syncImports(app) }
            .launchIn(scope)
        watchHeldReports(app)

        val library = WidgetContent.sources(app).flatMapLatest { it.library }
        combine(library, settings.map { it.translation }.distinctUntilChanged(), ::Pair)
            .debounce(1_000)
            .onEach { (library, translation) -> refresh(app, library, translation) }
            .launchIn(scope)
    }

    private val importsLock = Mutex()
    private var retry: Job? = null

    /**
     * Sends the watch what it lacks of the reader's imports ([WearPublisher.publishImports]). One pass
     * at a time; a failed send is tried again 30 seconds later, as iOS retries a failed transfer.
     */
    private suspend fun syncImports(app: Context) {
        val ok = importsLock.withLock {
            val state = TranslationLibrary.state.value
            WearPublisher.publishImports(app, state.importsLoaded, state.imported)
        }
        if (!ok && retry?.isActive != true) {
            retry = scope.launch {
                delay(30_000)
                syncImports(app)
            }
        }
    }

    /** The watch reported the editions it holds — after a reinstall, say — so check what it lacks. */
    private fun watchHeldReports(app: Context) {
        val uri = Uri.Builder().scheme("wear").path(WearLink.PATH_HELD).build()
        try {
            Wearable.getDataClient(app).addListener(
                { events ->
                    events.release()
                    scope.launch { syncImports(app) }
                },
                uri, DataClient.FILTER_LITERAL,
            )
        } catch (e: RuntimeException) {
            // No Play services: no watch to hear from.
        }
    }

    /** Rebuilds the snapshot; redraws and re-sends it only if its content changed. */
    suspend fun refresh(context: Context, library: WidgetLibrary, translation: String) {
        val snapshot = WidgetSnapshots.build(context, library, translation)
        val previous = WidgetSnapshots.read(context)
        if (WidgetSnapshots.write(context, snapshot)) {
            WidgetRevision.bump()
            FavoritesWidget().updateAll(context)
            // Verse of the Day reads an import's coming passages and its label from the snapshot too.
            if (previous?.daily != snapshot.daily || previous?.abbreviation != snapshot.abbreviation) {
                VerseOfDayWidget().updateAll(context)
            }
        }
        WearPublisher.publishSnapshot(context, WidgetSnapshots.read(context) ?: snapshot)
    }

    /** Rebuilds now from whatever source is current, without waiting for a change. */
    suspend fun refreshNow(context: Context) {
        val app = context.applicationContext
        refresh(app, WidgetContent.resolve(app).library.first(), WidgetReaderSettings.current(app).translation)
    }
}

/**
 * Bumped whenever what a widget shows may have changed for a reason its composition can't observe —
 * the clock, a new snapshot, the "Next" nudge. A live Glance session recomposes on update rather than
 * calling `provideGlance` again, so the widgets key what they compute on this.
 */
object WidgetRevision {
    val value = kotlinx.coroutines.flow.MutableStateFlow(0)
    fun bump() = value.update { it + 1 }
}

/** Starts [WidgetSync] at process start. Registered in the manifest under androidx.startup. */
class WidgetSyncInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        WidgetContent.install(UserDataWidgetSource(context))
        WidgetSync.start(context)
    }
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
                WidgetRevision.bump()
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
 * DEBUG builds only (registered in `src/debug/AndroidManifest.xml`): redraws the widgets, and switches
 * the demo library on or off, for emulator checks and screenshots, as the iOS app's `-seedDemoLibrary` launch argument —
 *
 *     adb shell am broadcast -n <package>/com.blainemiller.scripturealone.ui.widget.WidgetDemoReceiver --ez on true
 */
class WidgetDemoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        // "pin" asks the launcher to place a widget ("votd" or "favorites") — the emulator check's way
        // to put one on the home screen without dragging it from the picker.
        when (intent.getStringExtra("pin")) {
            "votd" -> pin(app, VerseOfDayWidgetReceiver::class.java)
            "favorites" -> pin(app, FavoritesWidgetReceiver::class.java)
        }
        if (intent.hasExtra("on")) WidgetContent.setDemoLibrary(app, intent.getBooleanExtra("on", true))
        // Then redraw both, as after an app update or a clock tick.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                WidgetSync.refreshNow(app)
                WidgetRevision.bump()
                VerseOfDayWidget().updateAll(app)
                FavoritesWidget().updateAll(app)
            } finally {
                pending.finish()
            }
        }
    }

    private fun pin(context: Context, receiver: Class<*>) {
        val manager = AppWidgetManager.getInstance(context)
        if (manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(ComponentName(context, receiver), null, null)
    }
}
