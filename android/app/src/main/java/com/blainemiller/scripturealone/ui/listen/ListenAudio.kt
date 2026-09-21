package com.blainemiller.scripturealone.ui.listen

import android.media.AudioAttributes

/**
 * Where Listen's voice comes out: the **media** path, as a spoken-word track — so Android plays it from
 * the loudspeaker, and moves it to a wired or Bluetooth headset by itself the moment one is connected.
 * iOS's `.playback` category with `.spokenAudio` mode does the same.
 *
 * Nothing in Listen sets the audio mode (`MODE_IN_COMMUNICATION` would route to the earpiece), picks a
 * communication device, or hands `TextToSpeech.speak` a `KEY_PARAM_STREAM` — every utterance is
 * spoken with these attributes (`TextToSpeech.setAudioAttributes`), and the silent keep-alive track,
 * audio focus and the media session use them too. Pulling headphones out pauses reading
 * (`ACTION_AUDIO_BECOMING_NOISY`) rather than carrying on from the speaker.
 */
internal object ListenAudio {
    const val USAGE: Int = AudioAttributes.USAGE_MEDIA
    const val CONTENT_TYPE: Int = AudioAttributes.CONTENT_TYPE_SPEECH

    /** Usages Android routes to the earpiece or a call — never Listen's. */
    val CALL_USAGES: Set<Int> = setOf(
        AudioAttributes.USAGE_VOICE_COMMUNICATION,
        AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
    )

    /** Whether [usage] follows the media route: the speaker, or a headset when one is connected. */
    fun followsMediaRoute(usage: Int): Boolean = usage == AudioAttributes.USAGE_MEDIA

    fun attributes(): AudioAttributes = AudioAttributes.Builder().setUsage(USAGE).setContentType(CONTENT_TYPE).build()
}
