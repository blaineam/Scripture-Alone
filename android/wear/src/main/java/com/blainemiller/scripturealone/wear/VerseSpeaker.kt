package com.blainemiller.scripturealone.wear

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Reads a passage aloud — `VerseSpeaker.swift`. Android's `TextToSpeech` plays to the watch speaker or
 * a Bluetooth headset without the render-to-file workaround Apple Watch needs. A watch without a
 * speech engine simply reports itself unavailable, and the Speak button is disabled.
 */
class VerseSpeaker(context: Context, private val onSpeaking: (Boolean) -> Unit) {
    /** Whether the speech engine bound and initialised; Compose state, so Speak enables when it does. */
    var ready by mutableStateOf(false)
        private set

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        Log.i("VerseSpeaker", "TextToSpeech init: ${if (status == TextToSpeech.SUCCESS) "SUCCESS" else "ERROR ($status)"}")
        if (status == TextToSpeech.SUCCESS) tts.language = Locale.US
        ready = status == TextToSpeech.SUCCESS
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = onSpeaking(true)
            override fun onDone(utteranceId: String?) = onSpeaking(false)
            @Deprecated("Superseded by onError(String, Int); still abstract, so still implemented.")
            override fun onError(utteranceId: String?) = onSpeaking(false)
            override fun onError(utteranceId: String?, errorCode: Int) = onSpeaking(false)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = onSpeaking(false)
        })
    }

    /** Speaks [text] in [language] (the Bible's `meta.language`), or US English for the English Bibles. */
    fun speak(text: String, language: String? = null) {
        if (!ready || text.isBlank()) return
        tts.language = language?.let(Locale::forLanguageTag) ?: Locale.US
        tts.setSpeechRate(0.95f)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "verse")
    }

    fun stop() {
        tts.stop()
        onSpeaking(false)
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
