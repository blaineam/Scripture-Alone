package com.blainemiller.scripturealone.data.online

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import com.blainemiller.scripturealone.data.sql.SqlRow
import java.io.File

/**
 * [CacheDatabaseDriver] over the bundled SQLite driver — the one the app already uses for its
 * shipped stores (see `BundledDatabase`), because Android's platform SQLite is built without FTS5
 * and the cache maintains `verses_fts` exactly as a bundled store does.
 *
 * Every connection is short-lived: the cache opens one per operation and closes it, as the Swift
 * code does, so nothing here is shared between threads.
 */
class BundledCacheDriver(private val driver: BundledSQLiteDriver = BundledSQLiteDriver()) : CacheDatabaseDriver {

    override fun openReadOnly(file: File): CacheDatabase =
        Connection(driver.open(file.path, SQLITE_OPEN_READONLY))

    override fun openForWriting(file: File): CacheDatabase =
        Connection(driver.open(file.path, SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE))

    private class Connection(private val connection: SQLiteConnection) : CacheDatabase {

        override fun execute(sql: String, vararg args: Any?) {
            connection.prepare(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.bindAny(index + 1, value) }
                // A PRAGMA answers with a row; stepping to the end runs any statement completely.
                while (statement.step()) Unit
            }
        }

        override fun <T> query(sql: String, vararg args: Any, map: (SqlRow) -> T): List<T> =
            connection.prepare(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.bindAny(index + 1, value) }
                val row = StatementRow(statement)
                buildList { while (statement.step()) add(map(row)) }
            }

        override fun close() = connection.close()

        private fun SQLiteStatement.bindAny(position: Int, value: Any?) = when (value) {
            null -> bindNull(position)
            is Long -> bindLong(position, value)
            is Int -> bindLong(position, value.toLong())
            is String -> bindText(position, value)
            else -> throw IllegalArgumentException("unsupported bind type ${value::class.simpleName}")
        }
    }

    private class StatementRow(private val statement: SQLiteStatement) : SqlRow {
        override fun long(column: Int) = statement.getLong(column)
        override fun text(column: Int) = statement.getText(column)
        override fun blob(column: Int) = statement.getBlob(column)
        override fun isNull(column: Int) = statement.isNull(column)
    }
}
