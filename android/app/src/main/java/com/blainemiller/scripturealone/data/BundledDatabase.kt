package com.blainemiller.scripturealone.data

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import com.blainemiller.scripturealone.data.assets.AssetLibrary
import com.blainemiller.scripturealone.data.assets.AssetPack
import com.blainemiller.scripturealone.data.assets.AssetPackMissingException
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
 * **Most databases are asset packs now** (`data/assets/`). A name that belongs to a pack is resolved
 * through [AssetLibrary] — the app's assets when the pack is install-time or the build is a debug
 * APK, otherwise the copy taken out of a downloaded pack — and one that isn't on the device yet
 * throws [AssetPackMissingException], which callers treat as "not downloaded", never as damage.
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
            val file = file(context, assetName)
            driver.open(file.path, SQLITE_OPEN_READONLY)
        }

    /**
     * The database as a file on disk — also the way to reach `ASV.sabible`, which
     * [com.blainemiller.scripturealone.data.sabible.TranslationPackage] reads by positional file reads
     * rather than through SQLite. A pack's file comes through [AssetLibrary]; anything else is an
     * asset of the base module.
     *
     * @throws AssetPackMissingException when the file's pack hasn't been downloaded.
     */
    fun file(context: Context, assetName: String): File {
        val pack = AssetPack.forFile(assetName) ?: return copyFromAssets(context, assetName)
        return AssetLibrary.file(context, pack) ?: throw AssetPackMissingException(pack)
    }

    /** Whether the APK's assets — the base module's, and any install-time pack's — hold [name]. */
    fun hasAsset(context: Context, name: String): Boolean = name in assetNames(context)

    @Volatile private var assetNamesCache: Set<String>? = null

    private fun assetNames(context: Context): Set<String> = assetNamesCache
        ?: context.assets.list("").orEmpty().toSet().also { assetNamesCache = it }

    /**
     * The asset copied out of the APK if this build hasn't yet. Serialised, so two first opens can't
     * race on the same `.partial` file — on a lock of its own rather than this object's, because
     * [AssetLibrary] calls in here holding a pack's lock while [connection] holds this object's and
     * waits on that same pack lock.
     */
    fun copyFromAssets(context: Context, assetName: String): File = synchronized(copyLock) { copy(context, assetName) }

    private val copyLock = Any()

    private fun copy(context: Context, assetName: String): File {
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
