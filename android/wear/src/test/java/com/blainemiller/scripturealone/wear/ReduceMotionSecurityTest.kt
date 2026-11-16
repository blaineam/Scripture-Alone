package com.blainemiller.scripturealone.wear

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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

/**
 * Play vitals (1.0.0.24 and .34, Galaxy Watch 4/5/6): Wear OS refuses the app a read of the restricted
 * `reduce_motion` global setting — "only readable to apps with targetSdkVersion lower than or equal to
 * 34" — and Compose for Wear's `LocalReduceMotion` reads it the moment `SwipeDismissableNavHost`
 * composes, so the watch app died on its first screen. The app must open anyway.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w227dp-h1600dp-xhdpi", shadows = [RestrictedSettingsGlobal::class])
class ReduceMotionSecurityTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun freshWatch() {
        WatchBible::class.java.getDeclaredField("shared").apply { isAccessible = true }.set(null, null)
    }

    @After fun close() = scenario?.close() ?: Unit

    @Test fun theAppOpensWhenTheWatchRefusesTheReduceMotionSetting() {
        scenario = ActivityScenario.launch(Intent(app, MainActivity::class.java))
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_500)
        rule.waitForIdle()
        assertTrue("the home screen drew", rule.onAllNodesWithText("Favorites", substring = true).fetchSemanticsNodes().isNotEmpty())
    }
}
