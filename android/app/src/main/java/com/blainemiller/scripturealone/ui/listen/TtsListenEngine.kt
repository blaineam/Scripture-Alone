package com.blainemiller.scripturealone.ui.listen

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.blainemiller.scripturealone.data.listen.VoiceInfo
import java.util.Locale

/**
 * [ListenEngine] over the device's `TextToSpeech`. Built — and so bound — on [ListenSpeech]'s thread,
 * and only ever called there.
 */
class TtsListenEngine(
    context: Context,
    attributes: AudioAttributes,
    progress: UtteranceProgressListener,
    onInit: (Boolean) -> Unit,
) : ListenEngine {

    private val tts = TextToSpeech(context) { status -> onInit(status == TextToSpeech.SUCCESS) }.apply {
        setAudioAttributes(attributes)
        setOnUtteranceProgressListener(progress)
    }

    private fun platformVoices() = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())

    override fun voices(): List<VoiceInfo> = platformVoices().map { v ->
        VoiceInfo(
            id = v.name,
            languageTag = v.locale.toLanguageTag(),
            quality = v.quality,
            requiresNetwork = v.isNetworkConnectionRequired,
            installed = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features.orEmpty(),
        )
    }

    override fun useVoice(id: String): Boolean {
        val match = platformVoices().firstOrNull { it.name == id } ?: return false
        tts.voice = match
        return true
    }

    override fun useLanguage(locale: Locale) {
        tts.language = locale
    }

    override fun setRate(rate: Float) {
        tts.setSpeechRate(rate)
    }

    private var volume = 1f

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
    }

    override fun speak(text: String, utteranceId: String) {
        val params = if (volume < 1f) Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume) } else null
        tts.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
    }

    override fun pause(millis: Long, utteranceId: String) {
        tts.playSilentUtterance(millis, TextToSpeech.QUEUE_ADD, utteranceId)
    }

    override fun stop() {
        tts.stop()
    }

    override fun shutdown() {
        tts.shutdown()
    }
}
