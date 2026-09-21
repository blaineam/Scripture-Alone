package com.blainemiller.scripturealone.wear

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Reads a passage aloud — `VerseSpeaker.swift`. Android's `TextToSpeech` plays to the watch speaker or
 * a Bluetooth headset without the render-to-file workaround Apple Watch needs. A watch without a
 * speech engine simply reports itself unavailable, and the Speak button is disabled.
 */
class VerseSpeaker(context: Context, private val onSpeaking: (Boolean) -> Unit) {
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) tts.language = Locale.US
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

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
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
