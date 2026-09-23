package com.blainemiller.scripturealone.data.backup

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor
import androidx.datastore.preferences.core.edit
import com.blainemiller.scripturealone.data.prefs.readerDataStore
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException

/**
 * Auto Backup for the files [SettingsBackup] names (the manifest sets `fullBackupOnly`, so this is
 * full-file backup, never key/value).
 *
 * Backing up picks the files itself rather than letting the rules walk the data directory, so the
 * library can be left out when it would push the backup over quota. Restoring is the platform's:
 * it accepts only paths the XML rules include, which are exactly the ones [SettingsBackup] lists.
 */
class SettingsBackupAgent : BackupAgent() {

    override fun onCreate() {
        super.onCreate()
        // The restore accepts a file under an included directory only when that directory exists
        // (`BackupUtils.isFileSpecifiedInPathList`) — and on a fresh install nothing has made it yet.
        File(filesDir, SettingsBackup.KEEPSAKES).mkdirs()
    }

    override fun onFullBackup(data: FullBackupDataOutput) {
        val prefsDir = File(dataDir, "shared_prefs")
        val settings = SettingsBackup.SHARED_PREFS.map { File(prefsDir, "$it.xml") } +
            File(filesDir, SettingsBackup.READER_SETTINGS)
        val keepsakes = File(filesDir, SettingsBackup.KEEPSAKES).listFiles { f -> f.isFile }.orEmpty().sortedBy { it.name }
        val library = listOf(SettingsBackup.USER_DATA_FILES.map { File(filesDir, it) }) + keepsakes.map { listOf(it) }
        // Called twice per backup — once to measure, once to send — and the same both times.
        SettingsBackup.plan(settings, library, { if (it.isFile) it.length() else 0L }, data.quota)
            .filter { it.isFile }
            .forEach { fullBackupFile(it, data) }
    }

    override fun onRestoreFinished() {
        try {
            runBlocking { applicationContext.readerDataStore.edit { SettingsBackup.forgetDeviceLocal(it) } }
        } catch (e: IOException) {
            // An unreadable store: the reader starts from defaults, and Listen asks again anyway.
            Unit
        }
    }

    // Key/value backup is never used (`fullBackupOnly`).
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) = Unit

    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) = Unit
}
