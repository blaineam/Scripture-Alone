package com.blainemiller.scripturealone.ui.listen

import android.content.Context
import android.media.AudioManager

/**
 * Whether a listening session is muted because the phone is silenced — `ListenMute` in
 * `ScriptureAlone/Listen/ListenController.swift`, where the iPhone's ring/silent switch decides.
 *
 * Android's reading of that switch is the ringer mode: Silent or Vibrate means a phone its owner has
 * quietened, so Listen starts muted there — the verses are followed on screen without a sound — until
 * the bar's Unmute is tapped. Unmute holds until listening stops; the next session reads the ringer
 * afresh. Reading the ringer again mid-session (back from another app) applies only a change, so an
 * Unmute tap is never undone by a ringer that hasn't moved.
 */
class ListenMute {
    var muted: Boolean = false
        private set
    private var lastSilenced: Boolean? = null

    /**
     * A session starts with the phone read as [silenced]. In a car ([inCar]) the reading plays anyway — the car's volume
     * decides — but the ringer is still remembered as it is, so the ringer broadcast that follows (it is sticky, and
     * arrives the moment the receiver is registered) isn't read as a change and doesn't mute the reading.
     */
    fun begin(silenced: Boolean, inCar: Boolean = false) {
        muted = silenced && !inCar
        lastSilenced = silenced
    }

    /** The ringer read again during the session. Returns whether [muted] changed. */
    fun ringerRead(silenced: Boolean): Boolean {
        val last = lastSilenced
        lastSilenced = silenced
        if (last == null || last == silenced || muted == silenced) return false
        muted = silenced
        return true
    }

    /** The reader tapped Unmute. */
    fun unmute() {
        muted = false
    }

    /** Listening stopped. */
    fun end() {
        muted = false
        lastSilenced = null
    }

    companion object {
        /** Silent or Vibrate: the phone has been quietened (Haven's `deviceSilenced`, ringer half). */
        fun silencedBy(ringerMode: Int): Boolean =
            ringerMode == AudioManager.RINGER_MODE_SILENT || ringerMode == AudioManager.RINGER_MODE_VIBRATE

        /** The phone's ringer read now; false (aloud, as before) if it can't be read. */
        fun deviceSilenced(context: Context): Boolean = runCatching {
            silencedBy(context.getSystemService(AudioManager::class.java).ringerMode)
        }.getOrDefault(false)
    }
}
