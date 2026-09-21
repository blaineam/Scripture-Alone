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
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
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
 */
@androidx.annotation.OptIn(UnstableApi::class)
class ListenPlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ListenPlayer(this, ListenController.get(this))
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(open).build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelName(R.string.listen_channel_name)
                .build()
                .apply { setSmallIcon(R.drawable.ic_listen_notification) },
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

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
class ListenPlayer(context: Context, private val listen: ListenController) : SimpleBasePlayer(Looper.getMainLooper()) {

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
                .setAvailableCommands(Player.Commands.Builder().addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP).build())
                .setPlaybackState(Player.STATE_IDLE)
                .build()
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(listen.nowPlayingTitle)
            .setArtist("Scripture Alone")
            .setAlbumTitle(listen.nowPlayingSubtitle)
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
                    Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP,
                    Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
                    Player.COMMAND_GET_AUDIO_ATTRIBUTES,
                ).build(),
            )
            .setPlayWhenReady(reading, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (phase is ListenController.Phase.Preparing) Player.STATE_BUFFERING else Player.STATE_READY)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
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

    override fun handleStop(): ListenableFuture<*> {
        if (listen.isPresented) listen.stop()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        listen.removeListener(onChange)
        return Futures.immediateVoidFuture()
    }
}
