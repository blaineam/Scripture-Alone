package com.blainemiller.scripturealone.ui.listen

import android.app.PendingIntent
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.blainemiller.scripturealone.data.listen.CarAction
import com.blainemiller.scripturealone.data.listen.CarCatalog
import com.blainemiller.scripturealone.data.listen.CarEntry
import com.blainemiller.scripturealone.data.listen.SpokenReference
import com.blainemiller.scripturealone.data.listen.VoiceQuery
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import com.blainemiller.scripturealone.MainActivity
import com.blainemiller.scripturealone.R
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayOutputStream

/**
 * Listen as the system sees it — iOS's `MPNowPlayingInfoCenter` and `MPRemoteCommandCenter`. A media3
 * session over [ListenPlayer]: it draws the media notification and the lock-screen controls, takes
 * headset and car buttons, and holds the service in the foreground (`mediaPlayback`) while reading, so
 * reading carries on with the screen off.
 *
 * It is also the app's **media library** — what Android Auto browses (`CarLibrary`, the counterpart of iOS's CarPlay
 * screens): today's reading, continue, the books and chapters, favorites. Choosing a row asks [ListenPlayer] to start
 * reading it.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class ListenPlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private val worker: ListeningExecutorService = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    private fun item(e: CarEntry): MediaItem = MediaItem.Builder()
        .setMediaId(e.id)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(e.title).setSubtitle(e.subtitle).setDisplayTitle(e.title)
                .setIsBrowsable(e.isFolder).setIsPlayable(!e.isFolder)
                .setMediaType(if (e.isFolder) MediaMetadata.MEDIA_TYPE_FOLDER_MIXED else MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                .build(),
        )
        .build()

    private val library = object : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
            LibraryResult.ofItem(
                MediaItem.Builder().setMediaId(CarCatalog.ROOT)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle("Scripture Alone").setIsBrowsable(true).setIsPlayable(false).build()).build(),
                params,
            ),
        )

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = worker.submit<LibraryResult<ImmutableList<MediaItem>>> {
            val entry = CarCatalog.index(CarLibrary.tree(this@ListenPlaybackService))[parentId]
            val rows = entry?.children
            if (rows == null) LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            else LibraryResult.ofItemList(ImmutableList.copyOf(rows.drop(page * pageSize).take(pageSize).map(::item)), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = worker.submit<LibraryResult<MediaItem>> {
            val entry = CarCatalog.index(CarLibrary.tree(this@ListenPlaybackService))[mediaId]
            if (entry == null) LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE) else LibraryResult.ofItem(item(entry), null)
        }

        /**
         * A row chosen in a car: the ids pass through to [ListenPlayer.handleSetMediaItems], which starts the reading.
         * A voice request ("Hey Google, read John chapter 1 in Scripture Alone") arrives with no id, only the words it
         * heard — as the item's search query and in the structured extras — which become a "spoken" id ([voiceItem]).
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(mediaItems.map(::voiceItem).toMutableList())

        /**
         * The car's own search box (the magnifier on the media screen, which takes a typed or spoken phrase and sends it straight to
         * the app — the route that needs no assistant's blessing). What was said is read as a passage, today's reading or "continue",
         * and offered as one row to choose; [onGetSearchResult] returns it.
         */
        override fun onSearch(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            android.util.Log.d("SAVoice", "car search query=$query")
            session.notifySearchResultChanged(browser, query, searchItems(query).size, params)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(searchItems(query).drop(page * pageSize).take(pageSize)), params))

        /**
         * A bare "play" (a car's play button, a headset's, Assistant's "resume") when the service is not reading: carry on where the
         * reader left off, as the Continue row does.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = Futures.immediateFuture(
            MediaSession.MediaItemsWithStartPosition(
                listOf(MediaItem.Builder().setMediaId(CarAction.CONTINUE).build()), 0, C.TIME_UNSET,
            ),
        )
    }

    /** The row a phrase typed or spoken into the car's search offers: what it names, as a playable item. */
    private fun searchItems(query: String): List<MediaItem> {
        val id = CarAction.spokenId(listOf(query))
        val title = when (val request = SpokenReference.plan(listOf(query))) {
            SpokenReference.Request.Today -> getString(R.string.car_today)
            SpokenReference.Request.Resume -> getString(R.string.car_continue)
            is SpokenReference.Request.Read -> request.passages.joinToString("; ") { it.display }
            SpokenReference.Request.Unknown -> return emptyList()
        }
        return listOf(item(CarEntry(id, title, getString(R.string.car_listen))))
    }

    override fun onCreate() {
        super.onCreate()
        val player = ListenPlayer(this, ListenController.get(this))
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, library).setSessionActivity(open).build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelName(R.string.listen_channel_name)
                .build()
                .apply { setSmallIcon(R.drawable.ic_listen_notification) },
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    private val main = Handler(Looper.getMainLooper())
    private var holdingForeground = false
    private var holdStartedAt = 0L

    /**
     * A headset's or a car's play button while nothing is reading: media3's MediaButtonReceiver woke this service with
     * `startForegroundService`, a promise to call `startForeground` within seconds — Android 8–11 kill the app with
     * RemoteServiceException when it isn't kept (even when the service stops first), Android 12+ with
     * ForegroundServiceDidNotStartInTimeException. media3 only goes foreground once the player shows a notification, and a
     * "continue" with nothing to read (or one slower than the deadline) never shows one. So the service keeps the promise
     * itself, at once, with a placeholder; media3's own notification replaces it if reading starts ([releaseForegroundHold]).
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON && !isPlaybackOngoing && !ListenController.get(this).isPresented) {
            holdForeground()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun holdForeground() {
        val notifications = NotificationManagerCompat.from(this)
        // media3's channel (DefaultMediaNotificationProvider makes it lazily, with this id and name): one "Listening" channel.
        notifications.createNotificationChannel(
            NotificationChannelCompat.Builder(DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.listen_channel_name))
                .build(),
        )
        val placeholder = NotificationCompat.Builder(this, DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_listen_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.listen_loading_voice))
            .setOngoing(true)
            .setSilent(true)
            .build()
        ServiceCompat.startForeground(
            this, HOLD_NOTIFICATION_ID, placeholder,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        holdingForeground = true
        holdStartedAt = SystemClock.uptimeMillis()
        main.removeCallbacks(releaseForegroundHold)
        main.postDelayed(releaseForegroundHold, HOLD_FIRST_CHECK_MS)
    }

    /**
     * Lets the placeholder go once the request has settled (or after [HOLD_MAX_MS], so a voice that never comes up leaves no
     * placeholder behind; reading that starts later is foregrounded by media3 as usual): nothing when media3 is foreground now (its notification took the
     * place of this one), else the placeholder comes down — and when nothing came of it (no reading to continue), the service
     * stops, as it does when swiped away while paused.
     */
    private val releaseForegroundHold: Runnable = object : Runnable {
        override fun run() {
            if (!holdingForeground) return
            val starting = (session?.player as? ListenPlayer)?.isStarting == true
            if (starting && SystemClock.uptimeMillis() - holdStartedAt < HOLD_MAX_MS) {
                main.postDelayed(this, HOLD_RECHECK_MS)
                return
            }
            holdingForeground = false
            if (isPlaybackOngoing) return
            ServiceCompat.stopForeground(this@ListenPlaybackService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            if (!ListenController.get(this@ListenPlaybackService).isPresented) stopSelf()
        }
    }

    /** Swiped away from Recents while paused: nothing is reading, so nothing stays behind. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacks(releaseForegroundHold)
        session?.run {
            player.release()
            release()
        }
        session = null
        worker.shutdown()
        super.onDestroy()
    }
}

/**
 * What a request with no id is, as an item the player can start: a voice request's words (the search query and the structured
 * extras, [VoiceQuery]) as a "spoken" id, and an empty one — a bare "play" — as "play something", which carries on where the
 * reader left off. An item that has an id (a row chosen in the car) is left alone.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun voiceItem(item: MediaItem): MediaItem {
    if (item.mediaId.isNotEmpty()) return item
    val request = item.requestMetadata
    val extras = request.extras
    val phrases = VoiceQuery.candidates(request.searchQuery, VoiceQuery.extraKeys.associateWith { extras?.getString(it) })
    // What the assistant actually said, for when a request isn't understood (adb logcat -s SAVoice).
    android.util.Log.d("SAVoice", "heard query=${request.searchQuery} extraKeys=${extras?.keySet()?.toList()} phrases=$phrases")
    return item.buildUpon().setMediaId(CarAction.spokenId(phrases)).build()
}

/** The placeholder that keeps a media button's startForegroundService promise ([ListenPlaybackService.onStartCommand]); not media3's id. */
private const val HOLD_NOTIFICATION_ID = 1_002
/** When the placeholder is first looked at: media3 has turned the button into a "continue" by then. */
private const val HOLD_FIRST_CHECK_MS = 3_000L
/** How often after that, while a "continue" is still starting. */
private const val HOLD_RECHECK_MS = 1_000L
/** The longest the placeholder waits for one: the window Android allows a media button's foreground start. */
private const val HOLD_MAX_MS = 10_000L

/** How long after a car chose something a "continue" is taken for the echo of that choice ([ListenPlayer.handleSetMediaItems]). */
private const val RESUMPTION_ECHO_MS = 3_000L

/**
 * [ListenController] presented as a media3 `Player`. It owns nothing: every state it reports is read
 * from the controller, and every command is handed back to it — play/pause, next and previous verse
 * (the headset's double and triple press, the notification's skip buttons), and stop.
 *
 * One media item stands for the pass; its title is the verse being read, so the lock screen follows the
 * reading verse by verse, as the iOS now-playing entry does.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class ListenPlayer(private val context: Context, private val listen: ListenController) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val onChange: () -> Unit = { invalidateState() }

    /** What a car was told when it asked for something that could not be read — shown as the session's error, where Android Auto puts it. */
    private var failure: String? = null
    private fun setFailure(message: String?) {
        failure = message
        invalidateState()
    }
    private val artwork: ByteArray? = runCatching {
        ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap(256, 256)?.let { bitmap ->
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        }
    }.getOrNull()

    init {
        listen.addListener(onChange)
    }

    override fun getState(): State {
        if (!listen.isPresented) {
            return State.Builder()
                .apply { failure?.let { setPlayerError(PlaybackException(it, null, PlaybackException.ERROR_CODE_UNSPECIFIED)) } }
                // A browser (Android Auto) picks what to read while nothing is: setting an item starts it.
                .setAvailableCommands(Player.Commands.Builder().addAll(
                    Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_PREPARE,
                    Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS,
                ).build())
                .setPlaybackState(Player.STATE_IDLE)
                .build()
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(listen.nowPlayingTitle)
            .setArtist("Scripture Alone")
            .setAlbumTitle(listen.translationLabel)
            .setDisplayTitle(listen.nowPlayingTitle)
            .setSubtitle(listen.nowPlayingSubtitle)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .apply { artwork?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
            .build()
        val item = MediaItemData.Builder("listen")
            .setMediaItem(MediaItem.Builder().setMediaId("listen").setMediaMetadata(metadata).build())
            .setMediaMetadata(metadata)
            .setIsSeekable(false)
            .setDurationUs(C.TIME_UNSET)
            .build()
        val phase = listen.phase
        val reading = phase == ListenController.Phase.Playing || phase is ListenController.Phase.Preparing
        return State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder().addAll(
                    Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_PREPARE,
                    Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS,
                    Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
                    Player.COMMAND_GET_AUDIO_ATTRIBUTES, Player.COMMAND_SET_SPEED_AND_PITCH,
                ).build(),
            )
            // The reader's reading speed, which a car's speed button steps through (the counterpart of CarPlay's).
            .setPlaybackParameters(PlaybackParameters(listen.speed.toFloat()))
            .setPlayWhenReady(reading, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (phase is ListenController.Phase.Preparing) Player.STATE_BUFFERING else Player.STATE_READY)
            .setAudioAttributes(
                // The same media / spoken-word route as the voice itself (ListenAudio).
                AudioAttributes.Builder().setUsage(ListenAudio.USAGE).setContentType(ListenAudio.CONTENT_TYPE).build(),
            )
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(0)
            .build()
    }

    /** A chosen item still being started ([CarLibrary.play] is under way). */
    private var starting: Job? = null
    val isStarting: Boolean get() = starting?.isActive == true

    /** When a car last chose something to read (a row, a search result, a voice request), to tell a real "continue" from the echo of one. */
    private var lastChosenAt = Long.MIN_VALUE / 2

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (failure != null) setFailure(null)
        if (playWhenReady) listen.resume() else listen.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> listen.nextVerse()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> listen.previousVerse()
        }
        return Futures.immediateVoidFuture()
    }

    /** A row chosen in a car: read what its id names (today's reading, a chapter, a favorite). */
    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        val id = mediaItems.getOrNull(startIndex.coerceAtLeast(0))?.mediaId
        // A row chosen in a car arrives as set-item, prepare, play — and the play, finding nothing yet reading (it starts a
        // moment later), asks the session to resume, which comes here as "continue". That is not a second request: it must not
        // replace what was just chosen (it read John 1 whatever was asked for).
        if (id == CarAction.CONTINUE && SystemClock.elapsedRealtime() - lastChosenAt < RESUMPTION_ECHO_MS) return Futures.immediateVoidFuture()
        if (id != null && id != CarAction.CONTINUE) lastChosenAt = SystemClock.elapsedRealtime()
        if (id != null && id != "listen") {
            failure = null
            starting = scope.launch {
                if (!CarLibrary.play(context, id)) {
                    // On the phone's screen, and in the car — where the driver is not looking at the phone.
                    listen.notice = context.getString(R.string.plans_unavailable)
                    setFailure(context.getString(R.string.car_not_found))
                }
                listen.startedFromCar = false
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        listen.updateSpeed(playbackParameters.speed.toDouble())
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        if (listen.isPresented) listen.stop()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        listen.removeListener(onChange)
        return Futures.immediateVoidFuture()
    }
}
