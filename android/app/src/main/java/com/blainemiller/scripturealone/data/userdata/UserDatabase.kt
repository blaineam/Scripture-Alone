package com.blainemiller.scripturealone.data.userdata

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import com.blainemiller.scripturealone.data.sql.SqlRow
import java.io.File

/**
 * The few things the reader's own data needs from a writable SQLite database.
 *
 * The same shape as `data/sql/SqlSource` plus writes and transactions, for the same reason: the store
 * runs on the device through the bundled SQLite driver ([BundledUserDatabase]) and is proven on the JVM
 * through JDBC, and [UserDataStore] never knows which it has.
 *
 * Arguments are Long, Int, String or null.
 */
interface UserDatabase {
    fun execute(sql: String, vararg args: Any?)
    fun <T> query(sql: String, vararg args: Any?, map: (SqlRow) -> T): List<T>
    /** Runs [block] atomically: all of its writes land, or none do. */
    fun <T> transaction(block: () -> T): T
}

/**
 * [UserDatabase] over the bundled SQLite driver — the one the Bible databases are read with, so the
 * app carries a single SQLite build. One connection, held for each statement or transaction.
 */
class BundledUserDatabase(file: File) : UserDatabase {
    private val connection: SQLiteConnection =
        BundledSQLiteDriver().open(file.path, SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE)

    init {
        // WAL keeps a write from blocking a read and survives a kill mid-write.
        query("PRAGMA journal_mode=WAL") { it.text(0) }
    }

    override fun execute(sql: String, vararg args: Any?) = synchronized(connection) {
        connection.prepare(sql).use { statement ->
            bind(statement, args)
            while (statement.step()) Unit
        }
    }

    override fun <T> query(sql: String, vararg args: Any?, map: (SqlRow) -> T): List<T> = synchronized(connection) {
        connection.prepare(sql).use { statement ->
            bind(statement, args)
            val row = StatementRow(statement)
            buildList { while (statement.step()) add(map(row)) }
        }
    }

    override fun <T> transaction(block: () -> T): T = synchronized(connection) {
        execute("BEGIN IMMEDIATE")
        try {
            val result = block()
            execute("COMMIT")
            result
        } catch (e: Throwable) {
            execute("ROLLBACK")
            throw e
        }
    }

    private fun bind(statement: SQLiteStatement, args: Array<out Any?>) = args.forEachIndexed { index, value ->
        val position = index + 1
        when (value) {
            null -> statement.bindNull(position)
            is Long -> statement.bindLong(position, value)
            is Int -> statement.bindLong(position, value.toLong())
            is String -> statement.bindText(position, value)
            else -> throw IllegalArgumentException("Unsupported bind type ${value::class.simpleName}")
        }
    }

    private class StatementRow(private val statement: SQLiteStatement) : SqlRow {
        override fun long(column: Int) = statement.getLong(column)
        override fun text(column: Int) = statement.getText(column)
        override fun blob(column: Int) = statement.getBlob(column)
        override fun isNull(column: Int) = statement.isNull(column)
    }
}
