package com.blainemiller.scripturealone.ui.listen

import android.media.AudioAttributes
import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Listen plays from the loudspeaker, or a headset when connected — the media route — and never from the
 * earpiece. Held here because one changed constant (a voice-communication usage, an audio mode, a stream
 * parameter) would quietly move the voice to the earpiece on a real phone.
 */
class ListenAudioTest {

    @Test
    fun listenUsesTheMediaRouteAsSpokenWord() {
        assertEquals(AudioAttributes.USAGE_MEDIA, ListenAudio.USAGE)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, ListenAudio.CONTENT_TYPE)
        assertTrue(ListenAudio.followsMediaRoute(ListenAudio.USAGE))
        assertFalse(ListenAudio.USAGE in ListenAudio.CALL_USAGES)
        for (usage in ListenAudio.CALL_USAGES) assertFalse(ListenAudio.followsMediaRoute(usage))
        // The media session reports the same (media3 shares the platform's values).
        assertEquals(C.USAGE_MEDIA, ListenAudio.USAGE)
        assertEquals(C.AUDIO_CONTENT_TYPE_SPEECH, ListenAudio.CONTENT_TYPE)
    }

    /** The source itself: nothing in Listen picks the earpiece or a call path, and headphones out pauses. */
    @Test
    fun nothingInListenSelectsTheEarpieceOrACallPath() {
        val dir = File("src/main/java/com/blainemiller/scripturealone/ui/listen")
        val source = dir.listFiles { f -> f.name.endsWith(".kt") && f.name != "ListenAudio.kt" }.orEmpty()
            .joinToString("\n") { it.readText() }
        assertTrue("sources found", source.contains("class ListenController"))
        for (forbidden in listOf(
            "setMode(", "MODE_IN_COMMUNICATION", "MODE_IN_CALL", "setSpeakerphoneOn", "setCommunicationDevice",
            "KEY_PARAM_STREAM", "STREAM_VOICE_CALL", "USAGE_VOICE_COMMUNICATION",
        )) {
            assertFalse("Listen mentions $forbidden", source.contains(forbidden))
        }
        assertTrue(source.contains("ACTION_AUDIO_BECOMING_NOISY"))
    }
}
