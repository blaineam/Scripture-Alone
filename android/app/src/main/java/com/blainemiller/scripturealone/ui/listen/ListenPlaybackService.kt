package com.blainemiller.scripturealone.ui.listen

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
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
         * A voice request ("Hey Google, play John 3:16–21 on Scripture Alone") arrives with no id, only the words it
         * heard as the item's search query; they become a "spoken" id the same way.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.map { item ->
                val query = item.requestMetadata.searchQuery
                if (item.mediaId.isEmpty() && query != null) item.buildUpon().setMediaId(CarAction.spokenId(query)).build() else item
            }.toMutableList(),
        )
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

    /** Swiped away from Recents while paused: nothing is reading, so nothing stays behind. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
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

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
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
        if (id != null && id != "listen") {
            scope.launch {
                if (!CarLibrary.play(context, id)) listen.notice = context.getString(R.string.plans_unavailable)
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
