package com.blainemiller.scripturealone.data.userdata

import com.blainemiller.scripturealone.data.sql.SqlRow
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Types

/** [UserDatabase] over JDBC, so the store is proven on the JVM against a real SQLite file. */
class JdbcUserDatabase(file: File) : UserDatabase, AutoCloseable {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:${file.path}")

    override fun execute(sql: String, vararg args: Any?) {
        connection.prepareStatement(sql).use { statement ->
            bind(statement, args)
            statement.execute()
        }
    }

    override fun <T> query(sql: String, vararg args: Any?, map: (SqlRow) -> T): List<T> =
        connection.prepareStatement(sql).use { statement ->
            bind(statement, args)
            statement.executeQuery().use { rs ->
                val row = Row(rs)
                buildList { while (rs.next()) add(map(row)) }
            }
        }

    override fun <T> transaction(block: () -> T): T {
        connection.autoCommit = false
        try {
            val result = block()
            connection.commit()
            return result
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    override fun close() = connection.close()

    private fun bind(statement: java.sql.PreparedStatement, args: Array<out Any?>) = args.forEachIndexed { i, value ->
        if (value == null) statement.setNull(i + 1, Types.NULL) else statement.setObject(i + 1, value)
    }

    private class Row(private val rs: ResultSet) : SqlRow {
        override fun long(column: Int) = rs.getLong(column + 1)
        override fun text(column: Int): String = rs.getString(column + 1) ?: ""
        override fun blob(column: Int): ByteArray = rs.getBytes(column + 1) ?: ByteArray(0)
        override fun isNull(column: Int): Boolean {
            rs.getObject(column + 1)
            return rs.wasNull()
        }
    }
}
