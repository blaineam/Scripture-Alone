package com.blainemiller.scripturealone.ui.listen

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.blainemiller.scripturealone.data.BundledTranslations
import com.blainemiller.scripturealone.data.Canon
import com.blainemiller.scripturealone.data.Chapter
import com.blainemiller.scripturealone.data.ChapterVerse
import com.blainemiller.scripturealone.data.listen.ListenKeys
import com.blainemiller.scripturealone.data.listen.ListenQueue
import com.blainemiller.scripturealone.data.listen.ListenQueue.Item
import com.blainemiller.scripturealone.data.listen.ListenSettings
import com.blainemiller.scripturealone.data.listen.ListenSpeed
import com.blainemiller.scripturealone.data.listen.SleepTimer
import com.blainemiller.scripturealone.data.listen.VoiceCatalog
import com.blainemiller.scripturealone.data.listen.VoiceInfo
import com.blainemiller.scripturealone.data.prefs.ReaderPrefs
import com.blainemiller.scripturealone.data.prefs.readerDataStore
import com.blainemiller.scripturealone.data.rights.TranslationRights
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Reads the Bible aloud — `ScriptureAlone/Listen/ListenController.swift`. One per process: there is
 * one speaker. It outlives the reader's activity, so reading carries on with the screen off and after
 * the reader is swiped away; [ListenPlaybackService] publishes it as a media session for the lock
 * screen, the notification, headsets and cars.
 *
 * Android's `TextToSpeech` speaks verse by verse, the rest of the chapter queued at once so there are
 * no gaps; each utterance's start moves the marker. Two differences from `AVSpeechSynthesizer` shape
 * this: the engine has no pause, so pausing stops and play starts the verse again from its beginning;
 * and the engine is its own app, so a voice that reads over the network is held to the translation's
 * hand-off right (see [VoiceCatalog]).
 *
 * All state is Compose state, read and written on the main thread.
 */
class ListenController private constructor(private val app: Context) {

    sealed interface Phase {
        data object Idle : Phase
        data class Preparing(val message: String) : Phase
        data object Playing : Phase
        data object Paused : Phase
    }

    private val prefs = ReaderPrefs(app.readerDataStore)
    private val saved: ListenSettings = runBlocking {
        runCatching { ListenSettings.from(app.readerDataStore.data.first()) }
            .getOrElse { ListenSettings(null, ListenSpeed.DEFAULT, true, false) }
    }

    var phase by mutableStateOf<Phase>(Phase.Idle)
        private set
    /** The verse being read, while listening. */
    var speakingVerse by mutableStateOf<Int?>(null)
        private set
    /** A short note for the bar — a sleep timer that ended, a voice that couldn't be used. */
    var notice by mutableStateOf<String?>(null)
    /** True while the bar should show. */
    var isPresented by mutableStateOf(false)
        private set
    var sleepTimer by mutableStateOf(SleepTimer.OFF)
        private set
    /** The translation being read. */
    var translationId by mutableStateOf("")
        private set

    /**
     * Bumped each time reading moves on into the next chapter, with that chapter: the reader follows,
     * as `reader.show(next)` does on iOS. A counter, so the reader can tell a new move from an old one.
     */
    var advance by mutableStateOf<Pair<Int, ChapterRef>?>(null)
        private set
    private var advances = 0

    var speed by mutableStateOf(saved.speed)
        private set
    var voiceId by mutableStateOf(saved.voice)
        private set
    var continueChapters by mutableStateOf(saved.continueChapters)
        private set

    /** The voices the picker offers for what is being read, refreshed when the engine is ready. */
    var voices by mutableStateOf<List<VoiceInfo>>(emptyList())
        private set
    /** Bumped when [voices] is refreshed, so the picker can re-read it. */
    var voicesVersion by mutableIntStateOf(0)
        private set

    val isPlaying: Boolean get() = phase == Phase.Playing

    /** "John 3:16", or the chapter while nothing is marked — `nowPlayingTitle`. */
    val nowPlayingTitle: String
        get() = ListenQueue.title(speakingVerse ?: items.firstOrNull { !it.isAnnouncement }?.key)
            ?: passChapter?.let(Canon::display).orEmpty()

    /** "John 3 · BSB" — the album line of the lock-screen entry. */
    val nowPlayingSubtitle: String
        get() = listOfNotNull(passChapter?.let(Canon::display), translationId.ifEmpty { null }).joinToString(" · ")

    /** The voice's name for the bar's status line. */
    val voiceName: String
        get() = voices.firstOrNull { it.id == voiceId }?.name ?: "System Voice"

    // The pass.
    private var scope: ListenQueue.Scope = ListenQueue.Scope.Selection
    private var items: List<Item> = emptyList()
    private var current = 0
    private var rights: TranslationRights = TranslationRights.PUBLIC_DOMAIN
    private val passChapter: ChapterRef?
        get() = (scope as? ListenQueue.Scope.Chapter)?.ref
            ?: (speakingVerse ?: items.firstOrNull { !it.isAnnouncement }?.key)?.let { key ->
                ChapterRef(key / 1_000_000, (key / 1_000) % 1_000)
            }

    // The engine.
    private val main = Handler(Looper.getMainLooper())
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingStart: (() -> Unit)? = null
    /** Utterances carry their pass's generation, so a late callback from a stopped queue is ignored. */
    private var generation = 0
    private var loading: Job? = null

    // Session plumbing.
    private val listeners = mutableListOf<() -> Unit>()
    private var sessionController: ListenableFuture<MediaController>? = null
    private var sleepDeadline: Long? = null
    private val sleepCheck = Runnable { checkSleepTimer() }

    // MARK: Starting

    /**
     * Plays [chapter] from [fromVerse] — the verse at the top of the screen, as the toolbar's Listen
     * does on iOS — to its end, then on into the next chapter.
     */
    fun playChapter(chapter: Chapter, fromVerse: Int) {
        start(chapter.translation.id, chapter.translation.rights, ListenQueue.Scope.Chapter(chapter.ref),
            ListenQueue.chapterItems(chapter.ref, chapter.verses, fromVerse))
    }

    /** Plays the selected verses, then stops. */
    fun playSelection(translation: String, rights: TranslationRights, verses: List<ChapterVerse>) {
        start(translation, rights, ListenQueue.Scope.Selection, ListenQueue.selectionItems(verses))
    }

    /** The toolbar button: start if nothing is up, otherwise play/pause. */
    fun toolbarAction(chapter: Chapter, fromVerse: Int) {
        if (phase != Phase.Idle && isPresented) togglePlayPause() else playChapter(chapter, fromVerse)
    }

    private fun start(translation: String, rights: TranslationRights, scope: ListenQueue.Scope, items: List<Item>) {
        stopOutput()
        if (items.isEmpty()) return
        translationId = translation
        this.rights = rights
        this.scope = scope
        this.items = items
        current = 0
        notice = null
        isPresented = true
        connectSession()
        begin(0)
    }

    private fun begin(index: Int) {
        stopOutput()
        current = index.coerceIn(0, items.lastIndex)
        speakingVerse = ListenQueue.markedVerse(items, current)
        if (!requestFocus()) {
            // A call is in progress: wait, paused, rather than talk over it.
            phase = Phase.Paused
            publish()
            return
        }
        withEngine { speakFrom(current) }
    }

    // MARK: Transport

    fun togglePlayPause() {
        when (phase) {
            Phase.Playing -> pause()
            Phase.Paused -> resume()
            // Nothing is playing, so there is nothing to toggle: a stray headset press must never
            // restart a closed player (the iOS comment on `togglePlayPause`).
            Phase.Idle, is Phase.Preparing -> Unit
        }
    }

    fun pause() {
        if (phase != Phase.Playing && phase !is Phase.Preparing) return
        stopOutput()
        pausedByFocusLoss = false
        phase = Phase.Paused
        publish()
    }

    fun resume() {
        if (phase != Phase.Paused || items.isEmpty()) return
        // The engine can't continue mid-utterance; the verse starts again.
        begin(current)
    }

    fun nextVerse() = skip(1)
    fun previousVerse() = skip(-1)

    private fun skip(delta: Int) {
        if (phase == Phase.Idle || items.isEmpty()) return
        when (val target = ListenQueue.skip(items, current, delta)) {
            null -> Unit
            ListenQueue.Skip.Finished -> passFinished()
            is ListenQueue.Skip.To -> if (phase == Phase.Paused) holdPaused(target.index) else begin(target.index)
        }
    }

    /** Moves to a verse without speaking; play starts from there. */
    private fun holdPaused(index: Int) {
        stopOutput()
        current = index.coerceIn(0, items.lastIndex)
        if (!items[current].isAnnouncement) speakingVerse = items[current].key
        phase = Phase.Paused
        publish()
    }

    /** Stops and hides the bar; the session's notification goes with it. */
    fun stop() {
        isPresented = false
        stopOutput()
        speakingVerse = null
        items = emptyList()
        notice = null
        chooseSleepTimer(SleepTimer.OFF)
        abandonFocus()
        silence?.release()
        silence = null
        publish()
        disconnectSession()
    }

    private fun stopOutput() {
        loading?.cancel()
        loading = null
        pendingStart = null
        generation++
        tts?.stop()
        releaseWake()
        unregisterNoisy()
        holdMediaButtons(false)
        phase = Phase.Idle
    }

    /** Voice or speed changed mid-read: carry on from the same verse. */
    private fun restartFromCurrent() {
        if ((phase != Phase.Playing && phase != Phase.Paused) || items.isEmpty()) return
        if (phase == Phase.Paused) holdPaused(current) else begin(current)
    }

    // MARK: Settings

    fun updateSpeed(value: Double) {
        val clean = ListenSpeed.sanitize(value)
        if (clean == speed) return
        speed = clean
        prefs.write { it[ListenKeys.SPEED] = clean }
        restartFromCurrent()
    }

    fun updateVoice(id: String?) {
        if (id == voiceId) return
        voiceId = id
        prefs.write { if (id == null) it.remove(ListenKeys.VOICE) else it[ListenKeys.VOICE] = id }
        restartFromCurrent()
    }

    fun updateContinueChapters(value: Boolean) {
        continueChapters = value
        prefs.write { it[ListenKeys.CONTINUE] = value }
    }

    /** Whether the reader has been asked for the notification permission yet (Android 13+). */
    var askedNotifications: Boolean = saved.askedNotifications
        private set

    fun markAskedNotifications() {
        askedNotifications = true
        prefs.write { it[ListenKeys.ASKED_NOTIFICATIONS] = true }
    }

    // MARK: Sleep timer

    fun chooseSleepTimer(timer: SleepTimer) {
        main.removeCallbacks(sleepCheck)
        sleepTimer = timer
        sleepDeadline = timer.deadline(SystemClock.elapsedRealtime())
        sleepDeadline?.let { main.postDelayed(sleepCheck, (it - SystemClock.elapsedRealtime()).coerceAtLeast(0)) }
    }

    /**
     * Run when the timer's delay is up and at every verse. The handler's clock stops while the phone
     * sleeps, so the verse check (on the clock that doesn't) is what makes it exact with the screen off.
     */
    private fun checkSleepTimer() {
        if (!SleepTimer.hasExpired(sleepDeadline, SystemClock.elapsedRealtime())) {
            sleepDeadline?.let { main.postDelayed(sleepCheck, (it - SystemClock.elapsedRealtime()).coerceAtLeast(1_000)) }
            return
        }
        main.removeCallbacks(sleepCheck)
        sleepDeadline = null
        pause()
        sleepTimer = SleepTimer.OFF
        notice = "Sleep timer ended."
        publish()
    }

    // MARK: End of a pass

    private fun passFinished() {
        if (phase == Phase.Idle) return
        when (val end = ListenQueue.passEnd(scope, continueChapters, sleepTimer, Canon::next)) {
            is ListenQueue.PassEnd.Continue -> continueInto(end.next)
            is ListenQueue.PassEnd.Stop -> {
                if (end.clearEndOfChapterTimer) chooseSleepTimer(SleepTimer.OFF)
                endPass(null)
            }
        }
    }

    private fun continueInto(next: ChapterRef) {
        val translation = translationId
        stopOutput()
        // Still "playing" to the system while the next chapter loads, so the session stays foreground.
        phase = Phase.Preparing("Opening ${Canon.display(next)}…")
        publish()
        acquireWake()
        loading = work.launch {
            val chapter = withContext(Dispatchers.IO) {
                runCatching { BundledTranslations.source(app, translation).chapter(next) }.getOrNull()
            }
            loading = null
            val nextItems = chapter?.let { ListenQueue.chapterItems(next, it.verses, 1) }.orEmpty()
            if (nextItems.isEmpty()) {
                endPass(null)
                return@launch
            }
            scope = ListenQueue.Scope.Chapter(next)
            items = nextItems
            advances++
            advance = advances to next
            begin(0)
        }
    }

    /** Leaves the bar up, paused at the start of what was read, so play reads it again. */
    private fun endPass(notice: String?) {
        stopOutput()
        current = ListenQueue.restartIndex(items).coerceAtMost(items.lastIndex.coerceAtLeast(0))
        phase = if (items.isEmpty()) Phase.Idle else Phase.Paused
        speakingVerse = null
        this.notice = notice
        publish()
    }

    // MARK: The engine

    /** Runs [action] once the engine is up, starting it on first use. */
    private fun withEngine(action: () -> Unit) {
        if (ttsReady) {
            action()
            return
        }
        pendingStart = action
        phase = Phase.Preparing("Loading voice…")
        publish()
        startEngine()
    }

    /** Binds the device's speech engine, once; a failed start is retried on the next use. */
    private fun startEngine() {
        if (tts != null) return
        tts = TextToSpeech(app) { status -> main.post { engineStarted(status == TextToSpeech.SUCCESS) } }.apply {
            setAudioAttributes(speechAttributes)
            setOnUtteranceProgressListener(progress)
        }
    }

    private fun engineStarted(ok: Boolean) {
        ttsReady = ok
        val action = pendingStart
        pendingStart = null
        if (!ok) {
            tts?.shutdown()
            tts = null
            if (action != null) {
                phase = Phase.Paused
                notice = "There’s no text-to-speech engine on this device. Install Speech Services by Google to listen."
                publish()
            }
            return
        }
        refreshVoices()
        action?.invoke()
    }

    /** The engine's installed voices for the text's language, as the picker offers them. */
    fun refreshVoices() {
        val engine = tts ?: return
        val all = runCatching { engine.voices.orEmpty() }.getOrDefault(emptySet()).map { v ->
            VoiceInfo(
                id = v.name,
                languageTag = v.locale.toLanguageTag(),
                quality = v.quality,
                requiresNetwork = v.isNetworkConnectionRequired,
                installed = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features.orEmpty(),
            )
        }
        allVoices = all
        voices = VoiceCatalog.options(all, TEXT_LANGUAGE, Locale.getDefault().country, allowNetwork)
        voicesVersion++
    }

    /** Starts the engine so the picker can list voices before anything is read. */
    fun prepareVoices() {
        if (ttsReady) refreshVoices() else startEngine()
    }

    private var allVoices: List<VoiceInfo> = emptyList()
    private val allowNetwork: Boolean
        get() = VoiceCatalog.allowsNetworkVoices(rights.permits(TranslationRights.Permission.EXTERNAL_HANDOFF))

    private fun applyVoice(engine: TextToSpeech) {
        refreshVoices()
        when (val resolved = VoiceCatalog.resolve(voiceId, allVoices, TEXT_LANGUAGE, Locale.getDefault().country, allowNetwork)) {
            is VoiceCatalog.Resolution.Use -> useVoice(engine, resolved.voice)
            is VoiceCatalog.Resolution.Refused -> {
                notice = VoiceCatalog.NETWORK_VOICE_REFUSED
                useVoice(engine, resolved.fallback)
            }
        }
        // 0.5–2× maps straight onto the engine's rate multiplier, where 1 is its normal pace.
        engine.setSpeechRate(speed.toFloat())
    }

    private fun useVoice(engine: TextToSpeech, voice: VoiceInfo?) {
        val match = voice?.let { v -> runCatching { engine.voices.orEmpty() }.getOrDefault(emptySet()).firstOrNull { it.name == v.id } }
        if (match != null) {
            engine.voice = match
        } else {
            val region = Locale.getDefault().country.takeIf { Locale.getDefault().language == TEXT_LANGUAGE }
            engine.language = if (region.isNullOrEmpty()) Locale.US else Locale.forLanguageTag("$TEXT_LANGUAGE-$region")
        }
    }

    private fun speakFrom(index: Int) {
        val engine = tts ?: return
        generation++
        val gen = generation
        engine.stop()
        applyVoice(engine)
        for (i in index until items.size) {
            engine.speak(items[i].text, TextToSpeech.QUEUE_ADD, null, "$gen:$i")
            // A breath between verses; a longer one after the chapter announcement.
            if (i < items.lastIndex) {
                engine.playSilentUtterance(if (items[i].isAnnouncement) 500L else 120L, TextToSpeech.QUEUE_ADD, "$gen:pause")
            }
        }
        phase = Phase.Playing
        acquireWake()
        registerNoisy()
        holdMediaButtons(true)
        publish()
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = post(utteranceId) { index -> utteranceStarted(index) }
        override fun onDone(utteranceId: String?) = post(utteranceId) { index -> utteranceFinished(index) }
        @Deprecated("Superseded by onError(String, Int); still abstract, so still implemented.")
        override fun onError(utteranceId: String?) = post(utteranceId) { index -> utteranceFinished(index) }
        override fun onError(utteranceId: String?, errorCode: Int) = post(utteranceId) { index -> utteranceFinished(index) }

        /** Callbacks arrive on a binder thread; the queue lives on the main one. */
        private fun post(id: String?, block: (Int) -> Unit) {
            val (gen, index) = id?.split(':')?.takeIf { it.size == 2 } ?: return
            val g = gen.toIntOrNull() ?: return
            val i = index.toIntOrNull() ?: return
            main.post { if (g == generation) block(i) }
        }
    }

    private fun utteranceStarted(index: Int) {
        if (index !in items.indices) return
        current = index
        if (!items[index].isAnnouncement) speakingVerse = items[index].key
        acquireWake()
        publish()
        checkSleepTimer()
    }

    private fun utteranceFinished(index: Int) {
        if (index == items.lastIndex && phase == Phase.Playing) passFinished()
    }

    // MARK: Audio focus, noisy output, wake

    private val audio = app.getSystemService(AudioManager::class.java)
    private val speechAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private var pausedByFocusLoss = false
    private var hasFocus = false

    /**
     * Spoken word pauses rather than ducks (`setWillPauseWhenDucked`): a navigation prompt over a verse
     * loses both. A call or another app's audio pauses reading; when a short interruption ends, reading
     * resumes — iOS's `.shouldResume`.
     */
    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(speechAttributes)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change ->
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    hasFocus = true
                    if (pausedByFocusLoss && phase == Phase.Paused) {
                        pausedByFocusLoss = false
                        resume()
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    hasFocus = false
                    pause()
                    abandonFocus()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    val wasPlaying = phase == Phase.Playing || phase is Phase.Preparing
                    pause()
                    pausedByFocusLoss = wasPlaying
                }
            }
        }, main)
        .build()

    private fun requestFocus(): Boolean {
        if (hasFocus) return true
        hasFocus = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    private fun abandonFocus() {
        if (!hasFocus) return
        audio.abandonAudioFocusRequest(focusRequest)
        hasFocus = false
        pausedByFocusLoss = false
    }

    /** Headphones pulled out: pause rather than read aloud to the room. */
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }
    private var noisyRegistered = false

    private fun registerNoisy() {
        if (noisyRegistered) return
        ContextCompat.registerReceiver(app, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        runCatching { app.unregisterReceiver(noisy) }
        noisyRegistered = false
    }

    /**
     * The speech engine plays in its own process, but the next chapter is read from disk here: keep the
     * CPU awake while reading, with the screen off, renewed at every verse and never held past one.
     */
    private val wake: PowerManager.WakeLock =
        app.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScriptureAlone:listen")
            .apply { setReferenceCounted(false) }

    private fun acquireWake() = wake.acquire(10 * 60_000L)

    /**
     * The speech engine plays its audio in its own process, so to the system this app never plays
     * anything — and Android hands the headset's buttons to the session of whichever app is *playing*
     * ("Media button session is null" in `dumpsys media_session`, verified on the emulator). A
     * looping buffer of silence, played here while reading, makes this app the one playing, so
     * play/pause and skip on a headset or car reach Listen as they do on iOS.
     */
    private var silence: AudioTrack? = null

    private fun holdMediaButtons(on: Boolean) {
        if (!on) {
            silence?.let { track -> runCatching { track.pause() } }
            return
        }
        val track = silence ?: runCatching {
            val frames = 4_000 // half a second at 8 kHz
            AudioTrack.Builder()
                .setAudioAttributes(speechAttributes)
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(8_000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(frames * 2)
                .build()
                .apply {
                    write(ShortArray(frames), 0, frames)
                    setLoopPoints(0, frames, -1)
                }
        }.getOrNull()?.also { silence = it } ?: return
        runCatching { track.play() }
    }

    private fun releaseWake() {
        if (wake.isHeld) wake.release()
    }

    // MARK: The media session

    /** [ListenPlayer] registers here to hear every change it must publish. */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun publish() {
        for (listener in listeners.toList()) listener()
    }

    /**
     * Connecting a controller brings [ListenPlaybackService] up; the service puts itself in the
     * foreground while reading, which keeps the process alive with the screen off.
     */
    private fun connectSession() {
        if (sessionController != null) return
        val token = SessionToken(app, ComponentName(app, ListenPlaybackService::class.java))
        sessionController = MediaController.Builder(app, token).buildAsync()
    }

    private fun disconnectSession() {
        sessionController?.let { MediaController.releaseFuture(it) }
        sessionController = null
    }

    companion object {
        /**
         * Every bundled translation is English, and imported ones carry no language tag, so the voices
         * offered are the English ones — `SpeechVoices.textLanguage`.
         */
        const val TEXT_LANGUAGE = "en"

        @SuppressLint("StaticFieldLeak") // the application context, which lives as long as the process
        @Volatile private var instance: ListenController? = null

        fun get(context: Context): ListenController =
            instance ?: synchronized(this) {
                instance ?: ListenController(context.applicationContext).also { instance = it }
            }
    }
}
