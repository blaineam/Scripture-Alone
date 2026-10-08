package com.blainemiller.scripturealone.ui.listen

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Listen on a silenced phone: muted from the start, Unmute for the session, the ringer read on change. */
class ListenMuteTest {

    @Test
    fun silentAndVibrateSilenceTheNormalRingerDoesNot() {
        assertTrue(ListenMute.silencedBy(AudioManager.RINGER_MODE_SILENT))
        assertTrue(ListenMute.silencedBy(AudioManager.RINGER_MODE_VIBRATE))
        assertFalse(ListenMute.silencedBy(AudioManager.RINGER_MODE_NORMAL))
    }

    @Test
    fun aSilencedPhoneStartsMutedAndUnmuteHoldsForTheSession() {
        val mute = ListenMute()
        mute.begin(silenced = true)
        assertTrue(mute.muted)
        mute.unmute()
        assertFalse(mute.muted)
        // The ringer read again, unchanged: the tap holds.
        assertFalse(mute.ringerRead(silenced = true))
        assertFalse(mute.muted)
        // The next session reads the ringer afresh.
        mute.end()
        mute.begin(silenced = true)
        assertTrue(mute.muted)
    }

    @Test
    fun aRingerThatMovesMidSessionApplies() {
        val mute = ListenMute()
        mute.begin(silenced = false)
        assertFalse(mute.muted)
        assertTrue(mute.ringerRead(silenced = true))
        assertTrue(mute.muted)
        assertTrue(mute.ringerRead(silenced = false))
        assertFalse(mute.muted)
    }

    @Test
    fun nothingIsMutedOutsideASession() {
        val mute = ListenMute()
        assertFalse(mute.ringerRead(silenced = true))
        assertFalse(mute.muted)
        mute.begin(silenced = true)
        mute.end()
        assertFalse(mute.muted)
        assertEquals(false, mute.ringerRead(silenced = true))
    }

    @Test
    fun aMutedPlanSpeaksAtNoVolume() {
        val plan = ListenSpeech.Plan(1, emptyList(), 0, 1f, null, "en", "US", false, java.util.Locale.US, volume = 0f)
        assertEquals(0f, plan.volume)
        assertEquals(1f, plan.copy(volume = 1f).volume)
    }
}
