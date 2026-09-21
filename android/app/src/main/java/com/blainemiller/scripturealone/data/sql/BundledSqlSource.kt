package com.blainemiller.scripturealone.data.sql

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement

/**
 * [SqlSource] over a connection from the bundled SQLite driver (see `BundledDatabase`). A connection
 * is not thread-safe, so every query holds it for its duration.
 */
class BundledSqlSource(private val connection: SQLiteConnection) : SqlSource {

    override fun <T> query(sql: String, vararg args: Any, map: (SqlRow) -> T): List<T> =
        synchronized(connection) {
            connection.prepare(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.bindAny(index + 1, value) }
                val row = StatementRow(statement)
                buildList { while (statement.step()) add(map(row)) }
            }
        }

    private fun SQLiteStatement.bindAny(position: Int, value: Any) = when (value) {
        is Long -> bindLong(position, value)
        is Int -> bindLong(position, value.toLong())
        is String -> bindText(position, value)
        else -> throw IllegalArgumentException("Unsupported bind type ${value::class.simpleName}")
    }

    private class StatementRow(private val statement: SQLiteStatement) : SqlRow {
        override fun long(column: Int) = statement.getLong(column)
        override fun text(column: Int) = statement.getText(column)
        override fun blob(column: Int) = statement.getBlob(column)
        override fun isNull(column: Int) = statement.isNull(column)
        override fun double(column: Int) = statement.getDouble(column)
    }
}
