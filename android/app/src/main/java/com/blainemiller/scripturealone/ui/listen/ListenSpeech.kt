package com.blainemiller.scripturealone.ui.listen

import com.blainemiller.scripturealone.data.listen.ListenQueue
import com.blainemiller.scripturealone.data.listen.VoiceCatalog
import com.blainemiller.scripturealone.data.listen.VoiceInfo
import com.blainemiller.scripturealone.speech.SpeechThread
import java.util.Locale

/**
 * What Listen needs of a speech engine — Android's `TextToSpeech` in [TtsListenEngine], a fake in the
 * JVM tests. Every call is made on [ListenSpeech]'s thread, never the main one.
 */
interface ListenEngine {
    /** The engine's voices, as the picker lists them. */
    fun voices(): List<VoiceInfo>
    /** Uses the voice the engine calls [id]; false when it no longer has one by that name. */
    fun useVoice(id: String): Boolean
    fun useLanguage(locale: Locale)
    /** 1 is the engine's normal pace. */
    fun setRate(rate: Float)
    /** 0 (muted: the phone is silenced) to 1, for what is queued from now on. */
    fun setVolume(volume: Float) = Unit
    /** Queues [text] after whatever is queued. */
    fun speak(text: String, utteranceId: String)
    /** Queues a silence after whatever is queued. */
    fun pause(millis: Long, utteranceId: String)
    fun stop()
    fun shutdown()
}

/**
 * Listen's speech, all of it on one thread of its own ([SpeechThread]) — so the main thread never
 * waits on the engine's lock, which some engines hold for seconds (the 1.1.0-rc.5 ANR: a Previous tap
 * stuck in `TextToSpeech.stop`). [ListenController] keeps its state on the main thread and only
 * *asks* for speech here; each call returns at once. Quick skips coalesce: the engine is stopped once
 * and reads from the verse the reader ended on.
 */
class ListenSpeech(create: (onInit: (Boolean) -> Unit) -> ListenEngine) {

    /** One read-through from a verse: what [ListenController.speakFrom] used to say to the engine itself. */
    data class Plan(
        /** Every utterance id carries it, so callbacks from a superseded plan are ignored. */
        val generation: Int,
        val items: List<ListenQueue.Item>,
        val from: Int,
        val rate: Float,
        /** The saved voice, or null for the engine's choice for the language. */
        val voiceId: String?,
        val voiceLanguage: String,
        val homeRegion: String?,
        val allowNetwork: Boolean,
        /** The language to ask for when no voice of the engine's suits. */
        val fallbackLocale: Locale,
        /** 0 while the session is muted by a silenced phone ([ListenMute]), else 1. */
        val volume: Float = 1f,
    )

    private val thread = SpeechThread(
        name = "listen-speech",
        create = create,
        stop = ListenEngine::stop,
        release = ListenEngine::shutdown,
    )

    /** Starts the engine; [onReady] gets its voices on the speech thread, or null if it couldn't start. */
    fun start(onReady: (List<VoiceInfo>?) -> Unit) =
        thread.start { engine -> onReady(engine?.let(::voicesOf)) }

    /** The engine's voices, read on the speech thread (when it is up). */
    fun voices(onVoices: (List<VoiceInfo>) -> Unit) = thread.run { onVoices(voicesOf(it)) }

    /** Stops reading. */
    fun silence() = thread.silence()

    /**
     * Stops whatever is being read and reads [plan]. [onStarted] is told, on the speech thread, the
     * voices the engine listed and whether the saved voice was refused (a network voice for a text
     * whose rights don't allow the hand-off) — unless a newer request superseded this one first.
     */
    fun play(plan: Plan, onStarted: (voices: List<VoiceInfo>, refused: Boolean) -> Unit = { _, _ -> }) =
        thread.say { engine ->
            val all = voicesOf(engine)
            val resolution = VoiceCatalog.resolve(plan.voiceId, all, plan.voiceLanguage, plan.homeRegion, plan.allowNetwork)
            val voice = when (resolution) {
                is VoiceCatalog.Resolution.Use -> resolution.voice
                is VoiceCatalog.Resolution.Refused -> resolution.fallback
            }
            if (voice == null || !engine.useVoice(voice.id)) engine.useLanguage(plan.fallbackLocale)
            // 0.5–2× maps straight onto the engine's rate multiplier, where 1 is its normal pace.
            engine.setRate(plan.rate)
            engine.setVolume(plan.volume)
            val items = plan.items
            for (i in plan.from until items.size) {
                engine.speak(items[i].text, utteranceId(plan.generation, i))
                // A breath between verses; a longer one after the chapter announcement.
                if (i < items.lastIndex) engine.pause(if (items[i].isAnnouncement) 500L else 120L, "${plan.generation}:pause")
            }
            onStarted(all, resolution is VoiceCatalog.Resolution.Refused)
        }

    /** For tests: waits for every request so far to have run on the speech thread. */
    internal fun awaitIdle(timeoutMillis: Long): Boolean = thread.awaitIdle(timeoutMillis)

    private fun voicesOf(engine: ListenEngine): List<VoiceInfo> = runCatching { engine.voices() }.getOrDefault(emptyList())

    companion object {
        /** "generation:index" — parsed back by [parseUtterance]. */
        fun utteranceId(generation: Int, index: Int) = "$generation:$index"

        /** (generation, item index) of an utterance id, or null for a pause or a foreign id. */
        fun parseUtterance(id: String?): Pair<Int, Int>? {
            val (gen, index) = id?.split(':')?.takeIf { it.size == 2 } ?: return null
            return (gen.toIntOrNull() ?: return null) to (index.toIntOrNull() ?: return null)
        }
    }
}
