package com.blainemiller.scripturealone.data.backup

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.blainemiller.scripturealone.data.listen.ListenKeys

/**
 * What Android's backup carries to a reinstall or a new phone — the iCloud key-value store's part on
 * iOS: the reader's own choices, so reinstalling puts the app back as they left it.
 * `res/xml/data_extraction_rules.xml` (Android 12+) and `res/xml/backup_rules.xml` (Android 10–11)
 * list the same files; [SettingsBackupAgent] backs them up and tidies the restore.
 *
 * Only what is listed here goes. Everything else in the app's data stays on the device: downloaded
 * and imported Bibles, caches, index fingerprints, widget and watch state, the rating prompt's
 * counters. Nothing credential-bearing is ever in these files — the API keys live sealed in
 * no-backup storage and travel through Block Store (`data/online/OnlineKeySync.kt`) instead.
 *
 * Auto Backup has a quota (25 MB to the cloud), and a backup over it is not trimmed but skipped
 * whole — so the reader's library (notes, highlights and favorites, whose kept slide photos can
 * grow it, and the keepsakes received) is added only while it fits beside the settings. The settings
 * are a few kilobytes and always go.
 */
object SettingsBackup {

    /** The reader's DataStore, under `filesDir` — appearance, position, translation, Listen, search toggles. */
    const val READER_SETTINGS = "datastore/reader.preferences_pb"

    /**
     * SharedPreferences files (`shared_prefs/<name>.xml`) that are the reader's choices: the study
     * panel's tab and commentary, the compare translation, the keepsake form (owner, dedication, and
     * the id that lets a new keepsake replace the last), and the API.Bible translations picked (ids
     * and names only).
     */
    val SHARED_PREFS = listOf("study", "compare", "legacy", "translations")

    /** Not carried: widget and watch hand-off state, AppSearch fingerprints, rating counters. */
    val DEVICE_SHARED_PREFS = listOf("widgets", "systemSearch", "rating")

    /**
     * The reader's notes, highlights and favorites, with its write-ahead log: the process is stopped
     * while a backup runs, so the database and the log are one consistent moment, and SQLite replays
     * the log when it opens the restored copy. The `-shm` index is rebuilt and never carried.
     */
    const val USER_DATA = "userdata.sqlite"
    val USER_DATA_FILES = listOf(USER_DATA, "$USER_DATA-wal")

    /** Keepsakes received (`.scripturelegacy` files), under `filesDir`. */
    const val KEEPSAKES = "Keepsakes"

    /**
     * Keys in the reader's store that describe this phone rather than the reader, dropped after a
     * restore: the notification permission isn't restored with the app, so Listen must ask again.
     * (A saved Listen voice the new phone lacks needs nothing: `VoiceCatalog.resolve` falls back.)
     */
    val DEVICE_LOCAL_READER_KEYS: List<Preferences.Key<*>> = listOf(ListenKeys.ASKED_NOTIFICATIONS)

    /** Room left under the quota for the archive's headers and the backup's own manifest. */
    const val HEADROOM: Long = 1L shl 20

    /** Auto Backup's documented cloud quota, for a transport that doesn't report one. */
    const val DEFAULT_QUOTA: Long = 25L shl 20

    /**
     * What to back up: every one of [settings], then each group of [library] (all of a group or none
     * of it — a database without its log is an older database) in order, while the total fits
     * [quota] less [HEADROOM].
     */
    fun <T> plan(settings: List<T>, library: List<List<T>>, size: (T) -> Long, quota: Long): List<T> {
        val budget = (if (quota > 0) quota else DEFAULT_QUOTA) - HEADROOM
        var total = settings.sumOf(size)
        val chosen = settings.toMutableList()
        for (group in library) {
            val groupSize = group.sumOf(size)
            if (total + groupSize > budget) continue
            total += groupSize
            chosen += group
        }
        return chosen
    }

    /** Clears [DEVICE_LOCAL_READER_KEYS] from a restored reader store. */
    fun forgetDeviceLocal(prefs: MutablePreferences) {
        DEVICE_LOCAL_READER_KEYS.forEach { prefs.remove(it) }
    }
}
