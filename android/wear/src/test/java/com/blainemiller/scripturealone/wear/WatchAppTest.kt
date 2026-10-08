package com.blainemiller.scripturealone.wear

import android.app.Application
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

/**
 * The Wear OS app's screens on Robolectric (a watch-wide window, tall enough that every row of a list
 * is composed and findable): home, a verse with its heart
 * (1.1.4: favorite on the watch), Favorites, the reader, and the translation picker — Remove from
 * Watch for what the phone sent, and the footer naming the phone's translation as the phone does, and
 * why one licensed off watches isn't there.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w227dp-h1600dp-xhdpi")
class WatchAppTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun freshWatch() {
        WatchBible::class.java.getDeclaredField("shared").apply { isAccessible = true }.set(null, null)
    }

    @After fun close() = scenario?.close() ?: Unit

    private fun launch(route: String? = null) {
        val intent = Intent(app, MainActivity::class.java).apply { route?.let { putExtra(MainActivity.EXTRA_ROUTE, it) } }
        scenario = ActivityScenario.launch(intent)
        settle()
    }

    private fun settle() {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_500)
        rule.waitForIdle()
    }

    /** Background loads (an edition on Dispatchers.IO) in real time. */
    private fun waitFor(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end) {
            settle()
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("$what never happened")
    }

    private fun text(t: String): SemanticsNodeInteractionCollection = rule.onAllNodesWithText(t, substring = true)
    private fun desc(t: String): SemanticsNodeInteractionCollection = rule.onAllNodesWithContentDescription(t, substring = true)
    private fun SemanticsNodeInteractionCollection.exists() = fetchSemanticsNodes().isNotEmpty()
    private fun SemanticsNodeInteractionCollection.tap() {
        val node = onFirst()
        if (node.fetchSemanticsNode().config.contains(SemanticsActions.OnClick)) node.performSemanticsAction(SemanticsActions.OnClick) else node.performClick()
        settle()
    }

    @Test fun homeOffersTodaysVerseAndTheLibrary() {
        launch()
        assertTrue("Favorites on the home screen", text("Favorites").exists())
        assertTrue("the books", text("Books").exists() || text("Read").exists())
    }

    @Test fun theHeartOnAVerseFavoritesItHere() {
        launch(Routes.verse("43003016-43003016"))
        waitFor("John 3:16") { text("John 3:16").exists() }
        waitFor("its text") { text("loved the world").exists() }
        text("Add to Favorites").tap()
        assertTrue("the heart fills at once", text("Remove from Favorites").exists())
        // …and Favorites lists it before the phone has answered.
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        scenario!!.close()
        launch(Routes.FAVORITES)
        assertTrue(text("John 3:16").exists())
    }

    @Test fun thePickerRemovesWhatThePhoneSentAndExplainsTheRest() {
        val bible = WatchBible.get(app)
        val bsb = File(System.getProperty("scripturealone.watchResources")!!, "BSB-Watch.sqlite").readBytes()
        bible.receiveEdition("BSB", ByteArrayInputStream(bsb), digest = "d1")
        bible.phoneChose("NASB1995", at = 10.0, notForWatch = true, label = "NASB 1995")
        launch(Routes.TRANSLATIONS)
        assertTrue("the edition the phone sent", text("BSB").exists())
        assertTrue("Remove from Watch", text("Remove from Watch").exists())
        assertTrue(
            "the footer names the phone's Bible as the phone does, and why it isn't here",
            text("NASB 1995 on your phone isn’t available on the watch").exists(),
        )
        desc("Remove BSB").tap()
        waitFor("the BSB to leave the picker") { !desc("Remove BSB").exists() }
    }

    @Test fun aTranslationTheWatchCantStoreIsExplained() {
        WatchBible.get(app).phoneChose("ESV", at = 5.0, label = "ESV")
        launch(Routes.TRANSLATIONS)
        assertTrue(text("ESV on your phone can’t be read here").exists())
    }

    @Test fun booksChaptersAndAChapter() {
        launch(Routes.BOOKS)
        text("Genesis").tap()
        rule.onAllNodesWithText("1", substring = false).tap()
        waitFor("Genesis 1 to read") { text("In the beginning").exists() }
    }

    @Test fun aChapterOpensAtItsVerse() {
        launch(Routes.chapter(43, 3, 16))
        waitFor("John 3 to read") { text("loved the world").exists() }
    }

    @Test fun emptyNotesAndHighlights() {
        launch(Routes.NOTES)
        launch(Routes.HIGHLIGHTS)
    }
}
