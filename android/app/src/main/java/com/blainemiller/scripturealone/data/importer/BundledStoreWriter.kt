package com.blainemiller.scripturealone.data.importer

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.sqlite.execSQL
import java.io.File

/**
 * [ImportedStoreWriter] over the bundled SQLite driver — the same driver `BundledDatabase` reads
 * with, and for the same reason: Android's framework SQLite has no FTS5, and the store's
 * `verses_fts` table needs it.
 */
class BundledStoreWriter private constructor(private val connection: SQLiteConnection) : ImportedStoreWriter {

    override fun execute(sql: String) = connection.execSQL(sql)

    override fun insert(sql: String, rows: Sequence<List<Any?>>) {
        connection.prepare(sql).use { statement ->
            for (row in rows) {
                statement.reset()
                statement.clearBindings()
                row.forEachIndexed { index, value -> statement.bindAny(index + 1, value) }
                statement.step()
            }
        }
    }

    override fun close() = connection.close()

    private fun SQLiteStatement.bindAny(position: Int, value: Any?) = when (value) {
        null -> bindNull(position)
        is Long -> bindLong(position, value)
        is Int -> bindLong(position, value.toLong())
        is String -> bindText(position, value)
        is ByteArray -> bindBlob(position, value)
        else -> throw IllegalArgumentException("Unsupported bind type ${value::class.simpleName}")
    }

    companion object {
        private val driver = BundledSQLiteDriver()

        /** Opens (creating) a read-write store for [ImportedBibleBuilder.write]. */
        val opener = ImportedStoreWriter.Opener { file: File ->
            BundledStoreWriter(driver.open(file.path, SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE))
        }
    }
}
