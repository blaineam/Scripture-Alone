package com.blainemiller.scripturealone.wear

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.blainemiller.scripturealone.speech.SpeechThread
import java.util.Locale

/**
 * Reads a passage aloud — `VerseSpeaker.swift`. Android's `TextToSpeech` plays to the watch speaker or
 * a Bluetooth headset without the render-to-file workaround Apple Watch needs. A watch without a
 * speech engine simply reports itself unavailable, and the Speak button is disabled.
 *
 * Every `TextToSpeech` call — the bind, `setLanguage`, `speak`, `stop`, `shutdown` — runs on a
 * [SpeechThread] of its own, never the UI thread: the engine's calls wait on a lock its connection
 * thread can hold for seconds, which on the phone was an ANR (1.1.0-rc.5). Compose state is set on main.
 */
class VerseSpeaker(context: Context, private val onSpeaking: (Boolean) -> Unit) {
    /** Whether the speech engine bound and initialised; Compose state, so Speak enables when it does. */
    var ready by mutableStateOf(false)
        private set

    private val main = Handler(Looper.getMainLooper())

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = speaking(true)
        override fun onDone(utteranceId: String?) = speaking(false)
        @Deprecated("Superseded by onError(String, Int); still abstract, so still implemented.")
        override fun onError(utteranceId: String?) = speaking(false)
        override fun onError(utteranceId: String?, errorCode: Int) = speaking(false)
        override fun onStop(utteranceId: String?, interrupted: Boolean) = speaking(false)
    }

    private val speech = SpeechThread(
        name = "verse-speaker",
        create = { onInit ->
            TextToSpeech(context.applicationContext) { status ->
                Log.i("VerseSpeaker", "TextToSpeech init: ${if (status == TextToSpeech.SUCCESS) "SUCCESS" else "ERROR ($status)"}")
                onInit(status == TextToSpeech.SUCCESS)
            }.apply { setOnUtteranceProgressListener(progress) }
        },
        stop = TextToSpeech::stop,
        release = TextToSpeech::shutdown,
    )

    init {
        speech.start { tts ->
            tts?.language = Locale.US
            val ok = tts != null
            main.post { ready = ok }
        }
    }

    /** Speaks [text] in [language] (the Bible's `meta.language`), or US English for the English Bibles. */
    fun speak(text: String, language: String? = null) {
        if (!ready || text.isBlank()) return
        speech.say { tts ->
            tts.language = language?.let(Locale::forLanguageTag) ?: Locale.US
            tts.setSpeechRate(0.95f)
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "verse")
        }
    }

    fun stop() {
        speech.silence()
        onSpeaking(false)
    }

    fun shutdown() {
        speech.close()
    }

    /** Progress arrives on the engine's binder thread; the state it drives is the UI's. */
    private fun speaking(on: Boolean) {
        main.post { onSpeaking(on) }
    }
}
