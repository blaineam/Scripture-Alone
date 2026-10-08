package com.blainemiller.scripturealone.app

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModelProvider
import com.blainemiller.scripturealone.MainActivity
import com.blainemiller.scripturealone.data.prefs.readerDataStore
import kotlinx.coroutines.runBlocking
import com.blainemiller.scripturealone.testing.FakeAndroidKeyStore
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before

/**
 * The whole app on the JVM (Robolectric, native graphics): MainActivity with the real reader, the
 * bundled ASV opened through the app's own sealed-package path (an in-memory Android Keystore stands
 * in for the device's), the real databases (androidx.sqlite's bundled SQLite, its JVM build's JNI
 * library — `unpackSqliteJni`), Compose driven through its semantics as TalkBack sees it. Each test
 * launches a fresh activity in a fresh app sandbox.
 */
abstract class AppTest {
    abstract val rule: ComposeTestRule
    protected var scenario: ActivityScenario<MainActivity>? = null

    /**
     * The Keystore stand-in, and the reader's settings back to a fresh install's. Robolectric gives each
     * test a new app sandbox, but the settings' DataStore is a process-wide singleton that would carry
     * one test's choices (Columns off, a chapter) into the next.
     */
    @Before fun freshInstall() {
        FakeAndroidKeyStore.install()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        runBlocking { context.readerDataStore.edit { it.clear() } }
        // AndroidViewModelFactory keeps the first Application it was given in a static and builds every
        // AndroidViewModel with it: on the JVM, a later test's ReaderViewModel would read the first
        // test's files (its notes). One Application per process on a device; one per test here.
        ViewModelProvider.AndroidViewModelFactory::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == ViewModelProvider.AndroidViewModelFactory::class.java }
            .forEach { it.isAccessible = true; it.set(null, null) }
    }

    @After fun close() {
        scenario?.close()
        scenario = null
    }

    /**
     * Launches the reader (optionally with [intent]; by default at John 1 in the ASV, the launch extras'
     * `book` and `chapter` — app state such as the reading position may outlive a test in the JVM) and
     * passes the first-launch guide prompt.
     */
    protected fun launch(intent: Intent? = null, skipWelcome: Boolean = true) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        scenario = ActivityScenario.launch(
            intent ?: Intent(context, MainActivity::class.java).putExtra("book", 43).putExtra("chapter", 1).putExtra("translation", "ASV"),
        )
        settle()
        // The chapter opens on its own thread: wait for John 1 (the default launch) to be on the page.
        if (intent == null) {
            val end = System.currentTimeMillis() + 30_000
            while (!rule.onAllNodesWithContentDescription("Verse 1.", substring = true).fetchSemanticsNodes().isNotEmpty() &&
                System.currentTimeMillis() < end
            ) {
                Thread.sleep(50)
                settle(400)
            }
        }
        if (skipWelcome && rule.onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty()) {
            rule.onAllNodesWithText("Skip").onFirst().performClick()
            settle()
        }
    }

    /** Lets animations, the reader's background loads and recompositions run out. */
    protected fun settle(millis: Long = 1_500) {
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(millis)
        rule.waitForIdle()
    }

    protected fun activity(): MainActivity {
        var found: MainActivity? = null
        scenario!!.onActivity { found = it }
        return found!!
    }

    protected fun desc(text: String, substring: Boolean = true): SemanticsNodeInteractionCollection =
        rule.onAllNodesWithContentDescription(text, substring = substring)

    protected fun text(text: String, substring: Boolean = true): SemanticsNodeInteractionCollection =
        rule.onAllNodesWithText(text, substring = substring)

    protected fun SemanticsNodeInteractionCollection.exists() = fetchSemanticsNodes().isNotEmpty()

    protected fun tapDesc(text: String, substring: Boolean = true): SemanticsNodeInteraction =
        desc(text, substring).onFirst().also { it.activate() }

    protected fun tapText(text: String, substring: Boolean = true): SemanticsNodeInteraction =
        this.text(text, substring).onFirst().also { it.activate() }

    /**
     * Clicks through the node's accessibility action, as TalkBack does — the verse nodes carry
     * semantics only, and a sheet's rows may sit below the fold of the small test window.
     */
    protected fun SemanticsNodeInteraction.activate() {
        val node = fetchSemanticsNode()
        if (node.config.contains(SemanticsActions.OnClick)) performSemanticsAction(SemanticsActions.OnClick) else performClick()
        settle()
    }

    /** Waits, in real time, for background work (rendering on Dispatchers.Default) to show [what]. */
    protected fun waitFor(what: String, timeout: Long = 30_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            settle(400)
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("$what never happened. On screen:\n${screen()}")
    }

    /**
     * [what] is on screen — or comes within a few seconds: chapters, the library and search load on
     * their own threads, and a loaded machine (a full suite beside a simulator) is slower to answer.
     */
    protected fun assertShown(what: String, nodes: SemanticsNodeInteractionCollection) {
        val end = System.currentTimeMillis() + 20_000
        while (!nodes.exists() && System.currentTimeMillis() < end) {
            Thread.sleep(50)
            settle(400)
        }
        assertTrue("$what is not on screen. On screen:\n${screen()}", nodes.exists())
    }

    /** What TalkBack would find on screen: every node's text and description, for a failure's message. */
    protected fun screen(): String = rule.onAllNodes(isRoot()).fetchSemanticsNodes().joinToString("\n---\n") { root ->
        buildList {
            fun walk(node: androidx.compose.ui.semantics.SemanticsNode) {
                val config = node.config
                config.getOrNull(SemanticsProperties.Text)?.joinToString()?.let(::add)
                config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()?.let { add("[$it]") }
                node.children.forEach(::walk)
            }
            walk(root)
        }.joinToString(" | ")
    }

    protected fun back() {
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        settle()
    }
}
