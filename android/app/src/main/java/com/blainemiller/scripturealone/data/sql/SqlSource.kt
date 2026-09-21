package com.blainemiller.scripturealone.data.sql

/**
 * The few things the read-only stores need from SQLite, and nothing else.
 *
 * It exists so a store can run on the device through the bundled driver ([BundledSqlSource]) *and*
 * be proven on the JVM against the very same database file through JDBC, the way the sealed-ASV
 * reader was proven against every chapter. The stores never see which one they have.
 */
interface SqlSource {
    /** Runs [sql] with positional [args] (Long, Int or String) and maps every row. */
    fun <T> query(sql: String, vararg args: Any, map: (SqlRow) -> T): List<T>
}

/** One result row. Columns are zero-based, as in the SELECT. */
interface SqlRow {
    fun long(column: Int): Long
    fun text(column: Int): String
    fun blob(column: Int): ByteArray
    fun isNull(column: Int): Boolean

    /**
     * A REAL column. Defaults to parsing SQLite's text rendering of the value, which is exact for
     * the coordinates the context store holds; the drivers the app and its tests use override it.
     */
    fun double(column: Int): Double = text(column).toDouble()
}
