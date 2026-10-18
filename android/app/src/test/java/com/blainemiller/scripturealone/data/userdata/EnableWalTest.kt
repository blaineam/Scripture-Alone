package com.blainemiller.scripturealone.data.userdata

import androidx.sqlite.SQLiteException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The first-launch crash: switching to WAL while another connection holds the file threw SQLITE_BUSY. */
class EnableWalTest {
    private val locked = SQLiteException("Error code: 5, message: database is locked")

    @Test fun retriesWhileTheFileIsLockedThenSwitches() {
        var busy = 3
        val ran = mutableListOf<String>()
        enableWriteAheadLogging(pause = {}) { sql ->
            ran += sql
            if (sql.endsWith("=WAL") && busy-- > 0) throw locked
            if (sql.endsWith("=WAL")) "wal" else "delete"
        }
        assertEquals(4, ran.count { it.endsWith("=WAL") })
    }

    @Test fun staysQuietWhenTheFileStaysLocked() {
        var tries = 0
        enableWriteAheadLogging(attempts = 3, pause = {}) { tries++; throw locked }
        assertEquals(3, tries)
    }

    @Test fun leavesADatabaseAlreadyInWalAlone() {
        val ran = mutableListOf<String>()
        enableWriteAheadLogging(pause = {}) { ran += it; "wal" }
        assertTrue(ran.none { it.endsWith("=WAL") })
    }
}
