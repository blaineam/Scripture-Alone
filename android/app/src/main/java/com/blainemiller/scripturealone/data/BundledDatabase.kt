package com.blainemiller.scripturealone.data

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import java.io.File

/**
 * Opens a SQLite database that ships inside the app.
 *
 * **Through the bundled driver, not `android.database.sqlite`.** Android's own SQLite is compiled
 * without FTS5 — a query against `verses_fts` fails with "no such module: fts5" (verified on API 35,
 * SQLite 3.44.3) — and every bundled Bible database searches with FTS5, the same files the iOS app
 * reads. `androidx.sqlite:sqlite-bundled` ships its own SQLite build that has it.
 *
 * Android cannot open an asset in place as a database — assets live inside the APK, not on the
 * filesystem — so each one is copied into private storage the first time it is needed and opened
 * read-only from there. The copy is keyed to the app's version code: a new build that ships a
 * changed database replaces the old copy, and an unchanged launch touches nothing. The assets are
 * stored uncompressed (`noCompress` in the build script), so the copy is a plain stream.
 *
 * A [SQLiteConnection] is not thread-safe. Callers serialise access; [withConnection] does it for
 * them.
 */
object BundledDatabase {

    private val driver = BundledSQLiteDriver()
    private val open = mutableMapOf<String, SQLiteConnection>()

    /** Runs [block] against the named database with exclusive access to its connection. */
    fun <T> withConnection(context: Context, assetName: String, block: (SQLiteConnection) -> T): T {
        val connection = connection(context, assetName)
        return synchronized(connection) { block(connection) }
    }

    @Synchronized
    private fun connection(context: Context, assetName: String): SQLiteConnection =
        open.getOrPut(assetName) {
            val file = ensureCopied(context, assetName)
            driver.open(file.path, SQLITE_OPEN_READONLY)
        }

    private fun ensureCopied(context: Context, assetName: String): File {
        val dir = File(context.noBackupFilesDir, "bundled").apply { mkdirs() }
        val target = File(dir, assetName)
        val stamp = File(dir, "$assetName.version")
        val version = versionCode(context).toString()

        if (target.exists() && stamp.exists() && stamp.readText() == version) return target

        // Written beside the target and moved into place, so a copy interrupted by the process
        // being killed can never leave a truncated database to be opened next launch.
        val partial = File(dir, "$assetName.partial")
        context.assets.open(assetName).use { input ->
            partial.outputStream().use { output -> input.copyTo(output, bufferSize = 1 shl 20) }
        }
        if (!partial.renameTo(target)) {
            target.delete()
            check(partial.renameTo(target)) { "Could not move $assetName into place" }
        }
        stamp.writeText(version)
        return target
    }

    private fun versionCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
}
