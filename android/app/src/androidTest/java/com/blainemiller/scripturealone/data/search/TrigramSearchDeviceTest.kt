package com.blainemiller.scripturealone.data.search

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.blainemiller.scripturealone.data.SqliteChapterSource
import com.blainemiller.scripturealone.data.VerseRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Chinese, Japanese and Korean Bibles search with FTS5's `trigram` tokenizer (SQLite 3.34+).
 * Android's own SQLite has no FTS5 at all; this proves the bundled driver the app actually opens them
 * with (`androidx.sqlite:sqlite-bundled`) has the tokenizer, on a device. The JVM tests prove the same
 * queries through sqlite-jdbc. Needs a debug APK, which carries the packs' files in its assets.
 */
@RunWith(AndroidJUnit4::class)
class TrigramSearchDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun chineseFindsTwoAndThreeCharacterWords() {
        val cuvs = SqliteChapterSource(context, "CUVS.sqlite")
        assertTrue(cuvs.search("恩典", 500).size > 40)
        assertEquals(10, cuvs.search("耶和华", 10).size)
        assertTrue(cuvs.search("神 爱世人", 10).any { it.ref == VerseRef(43, 3, 16) })
    }

    @Test
    fun koreanFindsWordsWithTheirParticles() {
        assertTrue(SqliteChapterSource(context, "KRV.sqlite").search("하나님", 5000).size > 3000)
    }

    @Test
    fun segondHitsCarryTheirKJVKey() {
        val hit = SqliteChapterSource(context, "LSG.sqlite").search("cœur pur", 50).first { it.ref == VerseRef(19, 51, 12) }
        assertEquals(VerseRef(19, 51, 10), hit.kjv)
    }
}
