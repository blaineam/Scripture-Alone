package com.blainemiller.scripturealone.data.study

import com.blainemiller.scripturealone.data.sql.SqlRow
import com.blainemiller.scripturealone.data.sql.SqlSource
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/** [SqlSource] over JDBC, so a store can be tested on the JVM against the real database file. */
class JdbcSqlSource(file: File) : SqlSource, AutoCloseable {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:${file.path}")

    override fun <T> query(sql: String, vararg args: Any, map: (SqlRow) -> T): List<T> =
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
            statement.executeQuery().use { rs ->
                val row = Row(rs)
                buildList { while (rs.next()) add(map(row)) }
            }
        }

    override fun close() = connection.close()

    private class Row(private val rs: ResultSet) : SqlRow {
        override fun long(column: Int) = rs.getLong(column + 1)
        override fun text(column: Int): String = rs.getString(column + 1) ?: ""
        override fun blob(column: Int): ByteArray = rs.getBytes(column + 1) ?: ByteArray(0)
        override fun double(column: Int): Double = rs.getDouble(column + 1)
        override fun isNull(column: Int): Boolean {
            rs.getObject(column + 1)
            return rs.wasNull()
        }
    }
}

/** The iOS app's resources — the files both apps ship — located by the test runner. */
fun resource(path: String): File {
    val root = System.getProperty("scripturealone.resources")
        ?: error("scripturealone.resources is not set; the test needs the real database files")
    return File(root, path).also { require(it.exists()) { "missing $it" } }
}
