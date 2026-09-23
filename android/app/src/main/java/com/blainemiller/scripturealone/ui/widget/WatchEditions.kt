package com.blainemiller.scripturealone.ui.widget

import android.database.sqlite.SQLiteDatabase
import com.blainemiller.scripturealone.companion.WatchEditionBuilder
import java.io.File

/**
 * Writes a Bible's watch edition on the phone — `WatchEdition.write(from:to:)` as `WatchLink.swift`
 * uses it — through Android's SQLite. The statements are [WatchEditionBuilder]'s, shared with the
 * JVM tests.
 */
object WatchEditions {

    /**
     * Writes the edition of the full database [source] to [destination], replacing it. Written beside
     * the destination and moved into place, so an interrupted write never leaves a truncated database.
     */
    fun write(source: File, destination: File) {
        val dir = destination.parentFile ?: error("No directory for $destination")
        dir.mkdirs()
        val partial = File(dir, ".${destination.name}.partial")
        deleteDatabase(partial)
        try {
            // NO_LOCALIZED_COLLATORS: no `android_metadata` table in a file the watch reads.
            val db = SQLiteDatabase.openDatabase(
                partial.path, null, SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
            try {
                WatchEditionBuilder.write(AndroidDatabase(db), source.path)
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            deleteDatabase(partial)
            throw e
        }
        if (!partial.renameTo(destination)) {
            destination.delete()
            check(partial.renameTo(destination)) { "Could not move ${destination.name} into place" }
        }
        deleteDatabase(partial)
    }

    private fun deleteDatabase(file: File) {
        for (suffix in listOf("", "-journal", "-wal", "-shm")) File(file.path + suffix).delete()
    }

    private class AndroidDatabase(private val db: SQLiteDatabase) : WatchEditionBuilder.Database {
        override fun execute(sql: String, vararg args: Any) {
            if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args)
        }

        override fun sourceHasTable(name: String): Boolean =
            db.rawQuery("SELECT 1 FROM src.sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name)).use { it.moveToFirst() }

        override fun transaction(block: () -> Unit) {
            db.beginTransaction()
            try {
                block()
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }
}
