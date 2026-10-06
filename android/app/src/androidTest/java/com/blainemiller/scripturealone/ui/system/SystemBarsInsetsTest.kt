package com.blainemiller.scripturealone.ui.system

import android.content.pm.ActivityInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blainemiller.scripturealone.MainActivity
import com.blainemiller.scripturealone.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Edge to edge (Play's "edge-to-edge may not display for all users"): the window draws behind the
 * status bar from MainActivity.onCreate, so every screen must pad itself. On the reader and each main
 * screen opened from it — Go To, Notes, Appearance, Study — no button sits under the status bar, a
 * side navigation bar or the display cutout, upright and in landscape. (The release smoke runs it
 * with 3-button navigation, which sits on the side in landscape.)
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SystemBarsInsetsTest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun text(id: Int) = rule.activity.getString(id)

    /** The status bar's (and a top cutout's) height, in window pixels. */
    private fun topInset(): Int {
        val insets = ViewCompat.getRootWindowInsets(rule.activity.window.decorView) ?: return 0
        return insets.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()).top
    }

    private fun waitForReader() {
        rule.waitUntil(30_000) {
            rule.onAllNodes(hasContentDescription(goToPrefix(), substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
    }

    /** "Go to passage, currently %1$s" up to its argument — the toolbar's title button. */
    private fun goToPrefix() = text(R.string.reader_go_to_label).substringBefore("%1").trimEnd(',', ' ', '，', '、')

    /** The side insets — a landscape 3-button navigation bar, a cutout — in window pixels. */
    private fun sideInsets(): Pair<Int, Int> {
        val insets = ViewCompat.getRootWindowInsets(rule.activity.window.decorView) ?: return 0 to 0
        val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        return i.left to i.right
    }

    /** Every button on screen starts below the status bar, and none sits under a side bar or cutout. */
    private fun assertButtonsBelowStatusBar(screen: String) {
        rule.waitForIdle()
        val inset = topInset()
        val (left, right) = sideInsets()
        val width = rule.activity.window.decorView.width
        assertTrue("$screen: the window reports no status-bar inset — not edge to edge?", inset > 0)
        val all = rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .fetchSemanticsNodes()
            .filter { it.boundsInWindow.height > 0f && it.boundsInWindow.width > 0f }
        assertTrue("$screen: no buttons on screen", all.isNotEmpty())
        // A sheet whose every row scrolls (Appearance) has no fixed chrome to hold to the bars.
        val buttons = all.filter { !it.scrolls() }
        for (node in buttons) {
            val label = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString()
            val top = node.boundsInWindow.top
            assertTrue("$screen: button \"$label\" starts at y=$top, under the status bar (inset $inset)", top >= inset - 1)
            val box = node.boundsInWindow
            assertTrue(
                "$screen: button \"$label\" spans x=${box.left}…${box.right}, under a side bar or cutout (insets $left / $right of $width)",
                box.left >= left - 1 && box.right <= width - right + 1,
            )
        }
    }

    /**
     * Whether the node is content in a scrolling list: that may scroll up under a header or the bars
     * (that is what edge to edge is for); the chrome — headers, toolbars, sheet buttons — may not.
     */
    private fun SemanticsNode.scrolls(): Boolean {
        var node: SemanticsNode? = parent
        while (node != null) {
            if (node.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null ||
                node.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null
            ) return true
            node = node.parent
        }
        return false
    }

    private fun open(description: String, substring: Boolean = false) {
        rule.onAllNodes(hasContentDescription(description, substring = substring))[0].performClick()
        rule.mainClock.advanceTimeBy(800)
        rule.waitForIdle()
    }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.mainClock.advanceTimeBy(800)
        rule.waitForIdle()
    }

    private fun checkMainScreens(orientation: String) {
        // The rotation recreates the activity: let the new one settle before touching it.
        Thread.sleep(1_500)
        waitForReader()
        assertButtonsBelowStatusBar("Reader ($orientation)")
        open(goToPrefix(), substring = true)
        assertButtonsBelowStatusBar("Go To ($orientation)")
        back()
        open(text(R.string.reader_notes))
        assertButtonsBelowStatusBar("Notes ($orientation)")
        back()
        open(text(R.string.reader_appearance))
        assertButtonsBelowStatusBar("Appearance ($orientation)")
        back()
        open(text(R.string.reader_study))
        assertButtonsBelowStatusBar("Study ($orientation)")
        back()
    }

    @Test
    fun portrait() {
        rule.runOnUiThread { rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        rule.waitUntil(10_000) { rule.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
        checkMainScreens("portrait")
        rule.runOnUiThread { rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    @Test
    fun landscape() {
        rule.runOnUiThread { rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        rule.waitUntil(10_000) { rule.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        checkMainScreens("landscape")
        rule.runOnUiThread { rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
}
