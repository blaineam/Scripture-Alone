package com.blainemiller.scripturealone.wear

import android.app.Activity
import android.app.Application
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.input.RemoteInputIntentHelper
import com.blainemiller.scripturealone.data.topics.TopicSearch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Topics on the watch (issue #8): the catalog the phone shares, the browse list, searching by voice and by
 * the system input screen, a topic's passages read one at a time by swiping left, the end page, and
 * swipe-to-dismiss back to the topics.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w227dp-h1600dp-xhdpi")
class WatchTopicsTest {
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
    private fun SemanticsNodeInteractionCollection.exists() = fetchSemanticsNodes().isNotEmpty()
    private fun SemanticsNodeInteractionCollection.tap() {
        val node = onFirst()
        if (node.fetchSemanticsNode().config.contains(SemanticsActions.OnClick)) node.performSemanticsAction(SemanticsActions.OnClick) else node.performClick()
        settle()
    }

    private fun swipeLeftOnScreen() {
        rule.onRoot().performTouchInput { swipeLeft() }
        settle()
    }

    private fun swipeRightOnScreen() {
        rule.onRoot().performTouchInput { swipeRight() }
        settle()
    }

    /** "3 of 12" on a passage page, as (3, 12). */
    private fun position(): Pair<Int, Int>? {
        val pattern = Regex("""(\d+) of (\d+)""")
        return rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes()
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
            .firstNotNullOfOrNull { pattern.matchEntire(it.text)?.let { m -> m.groupValues[1].toInt() to m.groupValues[2].toInt() } }
    }

    /** The screen the app started for a result (the speech recogniser or the input screen), if any. */
    private fun startedForResult(): Intent? {
        var started: Intent? = null
        scenario!!.onActivity { started = shadowOf(it).peekNextStartedActivityForResult()?.intent }
        return started
    }

    private fun answerSpeech(vararg guesses: String) {
        scenario!!.onActivity { activity ->
            val asked = shadowOf(activity).nextStartedActivityForResult
            assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, asked.intent.action)
            val answer = Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(*guesses))
            shadowOf(activity).receiveResult(asked.intent, Activity.RESULT_OK, answer)
        }
        settle()
    }

    private fun answerInput(typed: String) {
        scenario!!.onActivity { activity ->
            val asked = shadowOf(activity).nextStartedActivityForResult
            assertTrue("the system input screen", RemoteInputIntentHelper.isActionRemoteInput(asked.intent))
            val inputs = RemoteInputIntentHelper.getRemoteInputsExtra(asked.intent)!!
            val answer = Intent()
            RemoteInput.addResultsToIntent(inputs.toTypedArray(), answer, Bundle().apply { putCharSequence(inputs[0].resultKey, typed) })
            shadowOf(activity).receiveResult(asked.intent, Activity.RESULT_OK, answer)
        }
        settle()
    }

    // MARK: The catalog

    @Test fun theCatalogIsThePhonesWithoutNave() {
        val catalog = WatchTopics.catalog(app)
        assertEquals(72, catalog.themes.size)
        assertEquals(7, catalog.groups.size)
        val anxiety = catalog.theme("anxiety")!!
        assertEquals("Anxiety & Worry", anxiety.localizedName)
        assertEquals("Worry & Hard Feelings", catalog.groups.first { it.id == anxiety.group }.localizedName)
        assertTrue(anxiety.passages.isNotEmpty())
        // The same search as the phone's: a search word finds the theme.
        assertEquals("anxiety", catalog.search("worried").first().id)
        assertEquals("fear", catalog.search("afraid").first().id)
        assertEquals(TopicSearch.normalize("God’s"), "gods")
    }

    @Config(qualifiers = "de-w227dp-h1600dp-xhdpi")
    @Test fun theCatalogSpeaksTheWatchsLanguage() {
        val catalog = WatchTopics.catalog(app)
        assertEquals("Angst & Sorgen", catalog.theme("anxiety")!!.localizedName)
        // "Angst" finds the three themes the issue names, "Furcht" among them.
        val names = catalog.search("Angst").map { it.localizedName }
        assertTrue(names.toString(), "Angst & Sorgen" in names)
        assertTrue(names.toString(), "Angst vor der Zukunft" in names)
        assertTrue(catalog.search("Furcht").any { it.localizedName == "Furcht" })
    }

    // MARK: Browse

    @Test fun homeOffersTopics() {
        launch()
        assertTrue(text("Topics").exists())
    }

    @Test fun browseGroupsThemesWithTheirPassageCounts() {
        launch(Routes.TOPICS)
        val catalog = WatchTopics.catalog(app)
        assertTrue("a group heading", text("Worry & Hard Feelings").exists())
        val anxiety = catalog.theme("anxiety")!!
        val chip = text("Anxiety & Worry").fetchSemanticsNodes().first()
        val texts = chip.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        assertTrue("the passage count beside the name: $texts", anxiety.passages.size.toString() in texts)
        assertTrue(text("Speak a Topic").exists())
        assertTrue(text("Type a Topic").exists())
        assertFalse("Nave's Topical Bible isn't offered", text("Nave").exists())
    }

    // MARK: Search

    @Test fun typingATopicOpensItsResults() {
        launch(Routes.TOPICS)
        text("Type a Topic").tap()
        val asked = startedForResult()!!
        // The system input screen, with emoji off.
        val input = RemoteInputIntentHelper.getRemoteInputsExtra(asked)!!.single()
        assertTrue("emoji off", input.extras.getBoolean("android.support.wearable.input.extra.DISALLOW_EMOJI", false))
        answerInput("worried")
        waitFor("the result") { text("Anxiety & Worry").exists() && text("“worried”").exists() }
        text("Anxiety & Worry").tap()
        waitFor("the first passage") { position()?.first == 1 }
    }

    @Test fun aTypedWordThatFindsNothingSaysSoAndOffersAnotherTry() {
        launch(Routes.TOPICS)
        text("Type a Topic").tap()
        answerInput("zzzzzz")
        waitFor("the empty result") { text("No topic found for").exists() }
        assertTrue(text("Speak a Topic").exists())
        assertTrue(text("Type a Topic").exists())
    }

    @Test fun speakingTriesEachGuessUntilOneFindsATopic() {
        launch(Routes.TOPICS)
        text("Speak a Topic").tap()
        answerSpeech("qqqqqq", "afraid", "fear")
        waitFor("the result for the second guess") { text("“afraid”").exists() && text("Fear").exists() }
    }

    @Test fun speechThatFindsNothingHandsOverToTheInputScreen() {
        launch(Routes.TOPICS)
        text("Speak a Topic").tap()
        answerSpeech("qqqqqq", "zzzzzz")
        assertTrue("the input screen opens", RemoteInputIntentHelper.isActionRemoteInput(startedForResult()!!))
    }

    // MARK: Reading a topic

    @Test fun swipeLeftReadsEveryPassageToTheEndPage() {
        launch(Routes.topic("anxiety"))
        waitFor("the first passage") { position()?.first == 1 }
        val total = position()!!.second
        assertTrue(total > 1)
        assertTrue("the topic as a caption", text("ANXIETY & WORRY").exists())
        for (n in 1 until total) {
            swipeLeftOnScreen()
            waitFor("passage ${n + 1}") { position() == (n + 1 to total) }
        }
        assertFalse("no end page before the last passage is left", text("Start over").exists())
        swipeLeftOnScreen()
        waitFor("the end page") { text("Start over").exists() }
        assertTrue(text("All topics").exists())
        // Start over reads from the first again.
        text("Start over").tap()
        waitFor("the first passage again") { position() == (1 to total) }
    }

    @Test fun theNextPassageButtonDoesWhatTheSwipeDoes() {
        launch(Routes.topic("fear"))
        waitFor("the first passage") { position()?.first == 1 }
        val total = position()!!.second
        text("Next passage").tap()
        waitFor("the second passage") { position() == (2 to total) }
    }

    @Test fun allTopicsFromTheEndPageReturnsToTheList() {
        launch(Routes.TOPICS)
        text("Anxiety & Worry").tap()
        waitFor("the first passage") { position()?.first == 1 }
        val total = position()!!.second
        repeat(total) { swipeLeftOnScreen() }
        waitFor("the end page") { text("All topics").exists() }
        text("All topics").tap()
        waitFor("the list of topics") { text("Speak a Topic").exists() }
    }

    // MARK: Swipe back

    @Test fun swipeRightLeavesATopicForTheTopics() {
        launch(Routes.TOPICS)
        text("Anxiety & Worry").tap()
        waitFor("the first passage") { position()?.first == 1 }
        swipeRightOnScreen()
        waitFor("the list of topics") { text("Speak a Topic").exists() }
    }

    @Test fun swipeRightAfterASearchLandsOnTheTopicsNotTheResults() {
        launch(Routes.TOPICS)
        text("Type a Topic").tap()
        answerInput("worried")
        waitFor("the result") { text("“worried”").exists() }
        text("Anxiety & Worry").tap()
        waitFor("the first passage") { position()?.first == 1 }
        swipeRightOnScreen()
        waitFor("the list of topics") { text("Speak a Topic").exists() }
        assertFalse("not the results", text("“worried”").exists())
        assertNotNull(WatchTopics.catalog(app).theme("anxiety"))
    }
}
