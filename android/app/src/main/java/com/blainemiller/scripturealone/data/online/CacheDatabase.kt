package com.blainemiller.scripturealone.data.online

import com.blainemiller.scripturealone.data.sql.SqlSource
import java.io.Closeable
import java.io.File

/**
 * One open SQLite connection, as much of it as [OnlineChapterCache] needs.
 *
 * The Swift cache calls `sqlite3_*` directly. Here that is a seam instead, for the same reason
 * [SqlSource] is one: on the device the connection comes from the bundled driver
 * ([BundledCacheDriver] — Android's own SQLite has no FTS5, and the cache writes `verses_fts`), and
 * on the JVM the very same cache code is proven against a real file through JDBC.
 *
 * Reads go through [query], inherited from [SqlSource]. Failures surface as whatever the driver
 * throws; the cache translates them into [OnlineCacheException], as the Swift helpers do.
 */
interface CacheDatabase : SqlSource, Closeable {
    /**
     * Runs **one** statement to completion, binding [args] positionally (Long, Int, String, or null
     * for SQL NULL). One statement because that is all `androidx.sqlite` will prepare at a time; the
     * cache splits its multi-statement scripts itself.
     */
    fun execute(sql: String, vararg args: Any?)
}

/** Opens [CacheDatabase]s on files. The Swift code's `sqlite3_open_v2` calls, behind an interface. */
interface CacheDatabaseDriver {
    /** `SQLITE_OPEN_READONLY`. Throws if the file cannot be opened. Not `immutable=1`: see [OnlineChapterCache]. */
    fun openReadOnly(file: File): CacheDatabase

    /** `SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE`. Throws if the file cannot be opened or created. */
    fun openForWriting(file: File): CacheDatabase
}
