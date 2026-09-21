package com.blainemiller.scripturealone.data.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.blainemiller.scripturealone.data.VerseRef
import com.blainemiller.scripturealone.data.sabible.ChapterRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReaderPrefsTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun keyNamesAreTheIosUserDefaultsKeys() {
        val names = listOf(
            ReaderKeys.POSITION, ReaderKeys.TRANSLATION, ReaderKeys.RECENT, ReaderKeys.RECENT_SEARCHES,
            ReaderKeys.THEME, ReaderKeys.ACCENT, ReaderKeys.FONT_SIZE, ReaderKeys.LINE_SPACING, ReaderKeys.LAYOUT,
            ReaderKeys.RED_LETTERS, ReaderKeys.VERSE_NUMBERS, ReaderKeys.HEADINGS, ReaderKeys.FOOTNOTES, ReaderKeys.AUTO_SCROLL_SPEED,
        ).map { it.name }
        assertEquals(
            listOf(
                "position", "translation", "recent", "recentSearches",
                "reader.theme", "reader.accent", "reader.fontSize", "reader.lineSpacing", "reader.layout",
                "reader.redLetters", "reader.verseNumbers", "reader.headings", "reader.footnotes", "reader.autoScrollSpeed",
            ),
            names,
        )
    }

    @Test
    fun aFirstLaunchHasNothingSaved() {
        val s = ReaderSettings.from(emptyPreferences())
        assertNull(s.position)
        assertNull(s.translation)
        assertNull(s.theme)
        assertNull(s.fontSize)
        assertTrue(s.recent.isEmpty())
        assertTrue(s.recentSearches.isEmpty())
    }

    @Test
    fun aPositionMustNameARealChapter() {
        assertEquals(VerseRef(43, 3, 16), ReaderSettings.validVerse(43_003_016))
        assertEquals(VerseRef(19, 3, 0), ReaderSettings.validVerse(19_003_000)) // a superscription
        assertNull(ReaderSettings.validVerse(0))
        assertNull(ReaderSettings.validVerse(67_001_001))   // no 67th book
        assertNull(ReaderSettings.validVerse(43_022_001))   // John has 21 chapters
        assertNull(ReaderSettings.validVerse(43_000_001))
        assertNull(ReaderSettings.validVerse(-1))
        val s = ReaderSettings.from(mutablePreferencesOf(ReaderKeys.POSITION to 43_022_001))
        assertNull("an impossible position falls back to John 1 in the model", s.position)
    }

    @Test
    fun recentChaptersRoundTripAndDropWhatIsntAChapter() {
        val list = listOf(ChapterRef(43, 3), ChapterRef(19, 23), ChapterRef(65, 1))
        assertEquals("43003001,19023001,65001001", ReaderSettings.encodeRecent(list))
        assertEquals(list, ReaderSettings.decodeRecent(ReaderSettings.encodeRecent(list)))
        assertEquals(
            listOf(ChapterRef(43, 3), ChapterRef(19, 23)),
            ReaderSettings.decodeRecent("43003001, junk,99001001,43003001,19023001,"),
        )
        assertTrue(ReaderSettings.decodeRecent(null).isEmpty())
    }

    @Test
    fun recentSearchesSurviveCommasAndQuotes() {
        val list = listOf("faith, hope, love", "\"Jesus wept\"", "the LORD’s")
        assertEquals(list, ReaderSettings.decodeSearches(ReaderSettings.encodeSearches(list)))
        assertTrue(ReaderSettings.decodeSearches("not json").isEmpty())
        assertTrue(ReaderSettings.decodeSearches("{\"a\":1}").isEmpty())
        assertEquals(listOf("ok"), ReaderSettings.decodeSearches("[\"ok\", 3, null]"))
    }

    @Test
    fun recentChaptersMoveToTheFrontAndStopAtTwelve() {
        var list = emptyList<ChapterRef>()
        for (c in 1..14) list = Recents.chapters(list, ChapterRef(1, c))
        assertEquals(12, list.size)
        assertEquals(ChapterRef(1, 14), list.first())
        assertEquals(ChapterRef(1, 3), list.last())
        list = Recents.chapters(list, ChapterRef(1, 5))
        assertEquals(ChapterRef(1, 5), list.first())
        assertEquals(12, list.size)
        assertEquals(1, list.count { it == ChapterRef(1, 5) })
    }

    @Test
    fun recentSearchesIgnoreCaseAndDiacriticsWhenDeduplicating() {
        var list = Recents.searches(emptyList(), "  shepherd ")
        assertEquals(listOf("shepherd"), list)
        list = Recents.searches(list, "grace")
        list = Recents.searches(list, "Shepherd")
        assertEquals(listOf("Shepherd", "grace"), list)
        list = Recents.searches(list, "naïve")
        list = Recents.searches(list, "NAIVE")
        assertEquals(listOf("NAIVE", "Shepherd", "grace"), list)
        assertEquals("one letter isn't kept", list, Recents.searches(list, "a"))
        for (i in 1..20) list = Recents.searches(list, "term $i")
        assertEquals(12, list.size)
        assertEquals("term 20", list.first())
    }

    /**
     * The whole path, through a real DataStore file: writes land in order, and a second store over the
     * same file — a relaunch — reads them back.
     */
    @Test
    fun settingsPersistAcrossARelaunch() = runBlocking {
        val file = File(folder.root, "reader.preferences_pb")
        val job = Job()
        val first = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        val prefs = ReaderPrefs(first)
        prefs.write { it[ReaderKeys.THEME] = "sepia" }
        prefs.write { it[ReaderKeys.ACCENT] = "sea" }
        prefs.write { it[ReaderKeys.FONT_SIZE] = 23.0 }
        prefs.write { it[ReaderKeys.FOOTNOTES] = false }
        prefs.write { it[ReaderKeys.RECENT] = ReaderSettings.encodeRecent(listOf(ChapterRef(19, 23))) }
        for (verse in 1..30) prefs.write { it[ReaderKeys.POSITION] = VerseRef(43, 3, verse).key }
        withTimeout(5_000) { first.data.first { it[ReaderKeys.POSITION] == 43_003_030 } }
        job.cancelAndJoin()

        val relaunched = ReaderPrefs(PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + Job())) { file })
        val s = relaunched.load()
        assertEquals(VerseRef(43, 3, 30), s.position)
        assertEquals("sepia", s.theme)
        assertEquals("sea", s.accent)
        assertEquals(23.0, s.fontSize!!, 0.0)
        assertEquals(false, s.footnotes)
        assertNull(s.redLetters)
        assertEquals(listOf(ChapterRef(19, 23)), s.recent)
    }
}
