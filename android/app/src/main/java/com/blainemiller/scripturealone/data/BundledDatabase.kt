package com.blainemiller.scripturealone.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Opens a SQLite database that ships inside the app.
 *
 * Android cannot open an asset in place as a database — assets live inside the APK, not on the
 * filesystem — so each one is copied into private storage the first time it is needed and opened
 * read-only from there. The copy is keyed to the app's version code: a new build that ships a
 * changed database replaces the old copy, and an unchanged launch touches nothing.
 *
 * The assets are stored uncompressed (`noCompress` in the build script), so the copy is a plain
 * stream rather than an inflate.
 */
object BundledDatabase {

    private val open = mutableMapOf<String, SQLiteDatabase>()

    @Synchronized
    fun open(context: Context, assetName: String): SQLiteDatabase {
        open[assetName]?.let { if (it.isOpen) return it }
        val file = ensureCopied(context, assetName)
        val db = SQLiteDatabase.openDatabase(
            file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )
        open[assetName] = db
        return db
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
