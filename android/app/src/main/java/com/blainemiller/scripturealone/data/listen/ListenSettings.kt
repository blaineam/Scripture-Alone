package com.blainemiller.scripturealone.data.listen

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Listen's settings, under the iOS `SettingsKey` names (`ReaderStyle.swift`), in the same DataStore as
 * the reader's — one `UserDefaults` domain on iOS, one file here. Auto-scroll's speed is a reader
 * setting (`ReaderKeys.AUTO_SCROLL_SPEED`).
 *
 * `listen.engine` and `listen.studioVoice` have no Android counterpart (Mi Speaks is an iOS app), and
 * the sleep timer is not saved on either platform.
 */
object ListenKeys {
    val VOICE = stringPreferencesKey("listen.voice")
    val SPEED = doublePreferencesKey("listen.speed")
    val CONTINUE = booleanPreferencesKey("listen.continue")
    /** Android only: the notification permission has been asked for once, at the first Listen. */
    val ASKED_NOTIFICATIONS = booleanPreferencesKey("listen.askedNotifications")
}

/** The stored values, with iOS's defaults for anything never set. */
data class ListenSettings(
    val voice: String?,
    val speed: Double,
    val continueChapters: Boolean,
    val askedNotifications: Boolean,
) {
    companion object {
        fun from(p: Preferences) = ListenSettings(
            voice = p[ListenKeys.VOICE]?.takeIf { it.isNotBlank() },
            speed = ListenSpeed.sanitize(p[ListenKeys.SPEED]),
            continueChapters = p[ListenKeys.CONTINUE] ?: true,
            askedNotifications = p[ListenKeys.ASKED_NOTIFICATIONS] ?: false,
        )
    }
}
