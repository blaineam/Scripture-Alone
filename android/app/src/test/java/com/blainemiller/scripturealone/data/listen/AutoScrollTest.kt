package com.blainemiller.scripturealone.data.listen

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoScrollTest {

    @Test
    fun theFourSpeedsAreIosPointsPerSecondAndNames() {
        assertEquals(listOf(16.0, 28.0, 44.0, 64.0), AutoScroll.Speed.entries.map { it.pointsPerSecond })
        assertEquals(listOf("Slow", "Relaxed", "Steady", "Brisk"), AutoScroll.Speed.entries.map { it.title })
        assertEquals(28.0, AutoScroll.DEFAULT_SPEED, 0.0)
    }

    @Test
    fun aStoredSpeedMustBeOneOfTheFour() {
        assertEquals(44.0, AutoScroll.sanitize(44.0), 0.0)
        assertEquals(28.0, AutoScroll.sanitize(null), 0.0)
        assertEquals(28.0, AutoScroll.sanitize(30.0), 0.0)
    }

    @Test
    fun theStepperMovesWholePixelsAndCarriesTheFraction() {
        // 16 pt/s at 2.625 px/pt = 42 px/s; at 60 fps that is 0.7 px a frame.
        val stepper = AutoScroll.Stepper(2.625f)
        val frame = 1_000_000_000L / 60
        assertEquals(0, stepper.step(frame, 16.0)) // the first frame only starts the clock
        val moved = (2..61).sumOf { stepper.step(frame * it, 16.0) }
        assertTrue("moved $moved", moved in 41..42)
    }

    @Test
    fun aLongPauseIsNotScrolledThrough() {
        val stepper = AutoScroll.Stepper(1f)
        stepper.step(1_000_000_000L, 64.0)
        // Ten seconds later (the app was in the background): at most a quarter second's worth.
        assertEquals(16, stepper.step(11_000_000_000L, 64.0))
        stepper.reset()
        assertEquals(0, stepper.step(12_000_000_000L, 64.0))
    }

    @Test
    fun listenSettingsUseTheIosKeysAndDefaults() {
        assertEquals(listOf("listen.voice", "listen.speed", "listen.continue"), listOf(ListenKeys.VOICE, ListenKeys.SPEED, ListenKeys.CONTINUE).map { it.name })
        val defaults = ListenSettings.from(emptyPreferences())
        assertNull(defaults.voice)
        assertEquals(1.0, defaults.speed, 0.0)
        assertTrue(defaults.continueChapters)
        assertFalse(defaults.askedNotifications)
        val stored = ListenSettings.from(
            mutablePreferencesOf(ListenKeys.VOICE to "en-gb-x-gba-local", ListenKeys.SPEED to 1.5, ListenKeys.CONTINUE to false),
        )
        assertEquals("en-gb-x-gba-local", stored.voice)
        assertEquals(1.5, stored.speed, 0.0)
        assertFalse(stored.continueChapters)
    }
}
